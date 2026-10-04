import type { EngineInterface, Register } from 'claude-code'
import { normalizeBlocks, normalizeSnapshot, shouldForward, utf8Bytes, type ChatEvent, type ChatImage, type Normalized } from './normalize'
import { addTranscriptLine, finishTranscriptHistory, newTranscriptHistory, splitPiece } from './transcript'

// History goes out as begin (`total`: its event count), chunks, end.
type Control =
  | { type: 'hello'; sessionId: string; cwd: string }
  | { type: 'snapshot_begin'; total: number }
  | { type: 'snapshot_chunk'; events: ChatEvent[] }
  | { type: 'snapshot_end' }
  | { type: 'state'; state: 'working' | 'idle' }
type Outgoing = ChatEvent | Control

// A chunk's events serialize to at most CHUNK_BYTES; a /sync body, images
// included, to at most BODY_BYTES (the companion's limit is 8 MB); an image
// over IMAGE_BYTES is dropped (its reference stays, answered `missing`).
export const CHUNK_BYTES = 2 * 1024 * 1024
export const BODY_BYTES = 6 * 1024 * 1024
export const IMAGE_BYTES = 5 * 1024 * 1024

// Per-load mutable state. Helpers are top-level functions (the engine only
// follows `$` into functions declared at the top of this file), so the state
// travels in this object rather than in register()'s closure.
export type State = {
  paneId: string | undefined
  sessionId: string
  cwd: string
  socketPath: string
  pending: Outgoing[]
  // Images the queued events reference, sent beside them as the body allows.
  imageQueue: Array<[string, ChatImage]>
  offline: boolean
  inFlight: boolean
  // A history build (transcript read) is running in the background; until it
  // ends, ticks send heartbeats only and queued rows wait behind it.
  building: boolean
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
async function readHistory($: EngineInterface, s: State): Promise<Normalized> {
  s.historyLacksPath = !s.transcriptPath
  if (s.transcriptPath) {
    let history: Normalized | null = null
    try {
      history = await readTranscript($, s.transcriptPath)
    } catch {
      history = null
    }
    if (history) return history
  }
  const history = await $.session.messages({ as: 'api' })
  return Array.isArray(history) ? normalizeSnapshot(history) : { events: [], images: {} }
}

// Splits the history into chunks whose events serialize to ≤ CHUNK_BYTES (an
// event larger than that alone still makes one chunk).
export function chunkEvents(events: readonly ChatEvent[]): ChatEvent[][] {
  const out: ChatEvent[][] = []
  let cur: ChatEvent[] = []
  let bytes = 2 // [ ]
  for (const ev of events) {
    const size = utf8Bytes(JSON.stringify(ev))
    if (cur.length && bytes + 1 + size > CHUNK_BYTES) {
      out.push(cur)
      cur = []
      bytes = 2
    }
    bytes += size + (cur.length ? 1 : 0)
    cur.push(ev)
  }
  if (cur.length) out.push(cur)
  return out
}

// Throws away what is queued (the new history covers it) and rebuilds the
// history in the background; a request made while a build runs is kept for
// the tick after it ends.
function resync($: EngineInterface, s: State) {
  s.pending = []
  s.imageQueue = []
  if (s.building) {
    s.needResync = true
    return
  }
  s.needResync = false
  s.building = true
  void buildHistory($, s)
}

// Reads the session fresh (the transcript read can take several ticks) and
// puts hello + the chunked snapshot (+ the last known state) ahead of rows
// queued meanwhile, its images ahead of theirs.
async function buildHistory($: EngineInterface, s: State) {
  try {
    const sessionId = await $.session.id()
    const { events, images } = await readHistory($, s)
    s.sessionId = sessionId
    const head: Outgoing[] = [{ type: 'hello', sessionId, cwd: s.cwd }, { type: 'snapshot_begin', total: events.length }]
    for (const chunk of chunkEvents(events)) head.push({ type: 'snapshot_chunk', events: chunk })
    head.push({ type: 'snapshot_end' })
    if (s.lastState) head.push({ type: 'state', state: s.lastState })
    s.pending = [...head, ...s.pending]
    s.imageQueue = [...Object.entries(images), ...s.imageQueue]
  } catch {
    // A failed session read: retry on the next tick.
    s.needResync = true
  } finally {
    s.building = false
  }
}

// Takes the next /sync body's share of the queues: rows up to (not past) a
// second snapshot chunk, then images while the body stays ≤ BODY_BYTES. An
// image that doesn't fit waits; one over IMAGE_BYTES is dropped.
function takeBody(s: State): { events: Outgoing[]; images: Record<string, ChatImage> } {
  let bytes = utf8Bytes(JSON.stringify({ paneId: s.paneId, sessionId: s.sessionId, events: [], images: {} }))
  let n = 0
  let chunks = 0
  for (; n < s.pending.length; n++) {
    const ev = s.pending[n]!
    if (ev.type === 'snapshot_chunk' && ++chunks > 1) break
    const size = utf8Bytes(JSON.stringify(ev)) + (n ? 1 : 0)
    if (n && bytes + size > BODY_BYTES) break
    bytes += size
  }
  const events = s.pending.slice(0, n)
  s.pending = s.pending.slice(n)
  const images: Record<string, ChatImage> = {}
  const rest: Array<[string, ChatImage]> = []
  let count = 0
  for (const entry of s.imageQueue) {
    const [id, img] = entry
    const imgBytes = utf8Bytes(JSON.stringify(img))
    if (imgBytes > IMAGE_BYTES) continue
    const size = utf8Bytes(JSON.stringify(id)) + 1 + imgBytes + (count ? 1 : 0)
    if (bytes + size > BODY_BYTES) {
      rest.push(entry)
      continue
    }
    images[id] = img
    bytes += size
    count++
  }
  s.imageQueue = rest
  return { events, images }
}

async function tick($: EngineInterface, s: State) {
  if (s.inFlight || !s.paneId) return
  s.inFlight = true
  try {
    if (s.needResync && !s.offline && !s.building) resync($, s)
    // Offline: an empty probe. Building: a heartbeat; queued rows wait for
    // the history to go first.
    const held = s.offline || s.building
    const { events, images } = held ? { events: [], images: {} } : takeBody(s)
    const res = await $.http.fetch('http://chat/sync', {
      method: 'POST',
      socketPath: s.socketPath,
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        paneId: s.paneId,
        sessionId: s.sessionId,
        events,
        ...(Object.keys(images).length ? { images } : {}),
      }),
    })
    if (!res.ok) throw new Error(`sync ${res.status}`)
    if (s.offline) {
      s.offline = false
      resync($, s)
    }
    const { messages = [], resync: again } = JSON.parse(res.text) as {
      messages?: { id: string; text: string }[]
      resync?: boolean
    }
    // The companion has no hello for this session (e.g. it restarted
    // between two ticks): rebuild and resend. A heartbeat sent while the
    // history was being built is expected to get this answer; its hello is
    // already on the way.
    if (again === true && !held) s.needResync = true
    for (const m of messages) {
      // Not awaited: submit resolves only when Claude goes idle, and this
      // loop is the heartbeat that keeps the pane chat-capable meanwhile.
      s.submitChain = s.submitChain.then(() => $.prompt.submit({ text: m.text, asUser: true })).catch(() => {})
    }
  } catch {
    s.offline = true
    s.pending = []
    s.imageQueue = []
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
  const n = normalizeBlocks(role, stored?.content ?? e.message.content, e.uuid, Date.now())
  s.pending.push(...n.events)
  s.imageQueue.push(...Object.entries(n.images))
}

export const register: Register = on => {
  const s: State = {
    paneId: undefined,
    sessionId: '',
    cwd: '',
    socketPath: '',
    pending: [],
    imageQueue: [],
    offline: false,
    inFlight: false,
    building: false,
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
      s.imageQueue = []
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
