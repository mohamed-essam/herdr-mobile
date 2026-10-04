import type { EngineInterface, Register } from 'claude-code'
import { normalizeBlocks, normalizeSnapshot, shouldForward, type ChatEvent } from './normalize'
import { addTranscriptLine, finishTranscriptHistory, newTranscriptHistory, splitPiece } from './transcript'

type Control =
  | { type: 'hello'; sessionId: string; cwd: string }
  | { type: 'snapshot'; events: ChatEvent[] }
  | { type: 'state'; state: 'working' | 'idle' }
type Outgoing = ChatEvent | Control

// Per-load mutable state. Helpers are top-level functions (the engine only
// follows `$` into functions declared at the top of this file), so the state
// travels in this object rather than in register()'s closure.
export type State = {
  paneId: string | undefined
  sessionId: string
  cwd: string
  socketPath: string
  pending: Outgoing[]
  offline: boolean
  inFlight: boolean
  submitChain: Promise<unknown>
  needResync: boolean
  lastState: 'working' | 'idle' | undefined
  timer: { cancel: () => void } | undefined
  // The session's transcript file, from the latest classic event carrying it
  // (it moves when the session enters a worktree).
  transcriptPath: string | undefined
  // The last snapshot used the api-form history because no transcript path
  // was known yet: the first path to arrive asks for one upgrade resync.
  historyLacksPath: boolean
}

const SYNC_MS = 1000

async function resolveSocket($: EngineInterface): Promise<string> {
  const explicit = await $.env.get('HERDR_MOBILE_CHAT_SOCK')
  if (explicit) return explicit
  const runtime = await $.env.get('XDG_RUNTIME_DIR')
  if (runtime) return `${runtime}/herdr-mobile/chat.sock`
  // No predictable /tmp fallback: another local user could pre-create it.
  return ''
}

// Reads the transcript in pieces (`$.fs.read` refuses files over 4 MiB, and
// real transcripts grow far past that): each `tail -n +<line>` run's output is
// cut at 4 MiB, its complete lines are parsed as they come, and the next run
// starts at the first line not yet read. Reading by line, never by byte
// offset, means a cut inside a multi-byte character only ever touches the
// incomplete last line, which is read again whole by the next run. Rejects
// when the file can't be read.
async function readTranscript($: EngineInterface, path: string) {
  const h = newTranscriptHistory()
  let line = 1
  for (;;) {
    const r = await $.process.run(['tail', '-n', `+${line}`, '--', path])
    if (r.exitCode !== 0) throw new Error(`tail exited ${r.exitCode}`)
    const piece = splitPiece(r.stdout, r.isStdoutTruncated)
    for (const l of piece.lines) addTranscriptLine(h, l)
    if (!r.isStdoutTruncated) break
    line += piece.advance
  }
  return finishTranscriptHistory(h)
}

// History from the transcript file (real uuids, timestamps, meta rows
// dropped); the api-form history when the path is unknown, the read fails or
// the file has no message rows.
async function readHistory($: EngineInterface, s: State): Promise<ChatEvent[]> {
  s.historyLacksPath = !s.transcriptPath
  if (s.transcriptPath) {
    let events: ChatEvent[] | null = null
    try {
      events = await readTranscript($, s.transcriptPath)
    } catch {
      events = null
    }
    if (events) return events
  }
  const history = await $.session.messages({ as: 'api' })
  return Array.isArray(history) ? normalizeSnapshot(history) : []
}

// Reads the session fresh and puts hello + snapshot (+ the last known state)
// ahead of rows queued while the reads were awaited. Callers clear `pending`
// first when what is queued is already covered by the snapshot.
async function queueResync($: EngineInterface, s: State) {
  s.sessionId = await $.session.id()
  const events = await readHistory($, s)
  const head: Outgoing[] = [{ type: 'hello', sessionId: s.sessionId, cwd: s.cwd }, { type: 'snapshot', events }]
  if (s.lastState) head.push({ type: 'state', state: s.lastState })
  // Rows appended during the awaits above stay after the snapshot.
  s.pending = [...head, ...s.pending]
}

async function tick($: EngineInterface, s: State) {
  if (s.inFlight || !s.paneId) return
  s.inFlight = true
  try {
    if (s.needResync && !s.offline) {
      s.needResync = false
      s.pending = []
      await queueResync($, s)
    }
    const batch = s.offline ? [] : s.pending
    if (!s.offline) s.pending = []
    try {
      const res = await $.http.fetch('http://chat/sync', {
        method: 'POST',
        socketPath: s.socketPath,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ paneId: s.paneId, sessionId: s.sessionId, events: batch }),
      })
      if (!res.ok) throw new Error(`sync ${res.status}`)
      if (s.offline) {
        s.offline = false
        s.needResync = false
        s.pending = []
        await queueResync($, s)
      }
      const { messages = [], resync } = JSON.parse(res.text) as {
        messages?: { id: string; text: string }[]
        resync?: boolean
      }
      // The companion has no hello for this session (e.g. it restarted
      // between two ticks): resend hello + snapshot on the next tick.
      if (resync === true) s.needResync = true
      for (const m of messages) {
        // Not awaited: submit resolves only when Claude goes idle, and this
        // loop is the heartbeat that keeps the pane chat-capable meanwhile.
        s.submitChain = s.submitChain.then(() => $.prompt.submit({ text: m.text, asUser: true })).catch(() => {})
      }
    } catch {
      s.offline = true
      s.pending = []
    }
  } catch {
    // A failed session read: retry the resync on the next tick.
    s.needResync = true
  } finally {
    s.inFlight = false
  }
}

type Appended = {
  door: string
  uuid: string
  agentId?: string
  message: { isMeta?: true; role?: string; content?: unknown }
}

// Queues the forwardable part of an appended row. Prefers the stored (possibly
// rewritten) content over the incoming one. Exported so tests can drive it
// directly: the test kit cannot answer session.append end-to-end.
export function queueAppended(s: State, e: Appended, stored: { content?: unknown } | undefined): void {
  if (!s.paneId || !shouldForward(e)) return
  const role = e.message.role
  if (role !== 'user' && role !== 'assistant') return
  s.pending.push(...normalizeBlocks(role, stored?.content ?? e.message.content, e.uuid, Date.now()))
}

export const register: Register = on => {
  const s: State = {
    paneId: undefined,
    sessionId: '',
    cwd: '',
    socketPath: '',
    pending: [],
    offline: false,
    inFlight: false,
    submitChain: Promise.resolve(),
    needResync: false,
    lastState: undefined,
    timer: undefined,
    transcriptPath: undefined,
    historyLacksPath: false,
  }

  // Only remembers the path; the read happens at the next resync.
  const trackTranscript = (path: string) => {
    if (!path) return
    s.transcriptPath = path
    if (s.historyLacksPath && s.paneId) {
      s.historyLacksPath = false
      s.needResync = true
    }
  }
  on('classic.SessionStart', async ($, e, next) => {
    trackTranscript(e.transcript_path)
    return next(e)
  })
  on('classic.UserPromptSubmit', async ($, e, next) => {
    trackTranscript(e.transcript_path)
    return next(e)
  })
  on('classic.Stop', async ($, e, next) => {
    trackTranscript(e.transcript_path)
    return next(e)
  })

  on('session.start', async ($, e, next) => {
    const r = await next(e)
    // Headless children (claude -p, SDK) inherit HERDR_PANE_ID; only the
    // interactive session owns the pane's chat and outbox.
    if (e.isInteractive !== true) return r
    s.paneId = await $.env.get('HERDR_PANE_ID')
    if (!s.paneId) return r
    s.cwd = e.cwd
    s.socketPath = await resolveSocket($)
    if (!s.socketPath) {
      s.paneId = undefined
      return r
    }
    // Hooks do no I/O: the first tick reads the session and sends hello + snapshot.
    s.needResync = true
    s.timer?.cancel()
    s.timer = $.clock.every(SYNC_MS, () => void tick($, s))
    return r
  })

  on('session.end', async ($, e, next) => {
    const r = await next(e)
    // /clear and /resume continue the process under another session id; the
    // resync (fresh id + history) happens at the next tick, not here.
    if (s.paneId && (e.reason === 'clear' || e.reason === 'resume')) {
      s.pending = []
      s.needResync = true
      // The new session's transcript is named by its own classic event; until
      // then the api form stands in (and that path's arrival upgrades it).
      s.transcriptPath = undefined
    }
    return r
  })

  on('session.append', async ($, e, next) => {
    const r = await next(e)
    queueAppended(s, e, r.message)
    return r
  })

  on('turn.start', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId) {
      s.lastState = 'working'
      s.pending.push({ type: 'state', state: 'working' })
    }
    return r
  })

  on('turn.complete', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && !e.agentId) {
      s.lastState = 'idle'
      s.pending.push({ type: 'state', state: 'idle' })
    }
    return r
  })
}
