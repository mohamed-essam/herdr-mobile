import type { EngineInterface, Register } from 'claude-code'
import { normalizeBlocks, normalizeSnapshot, shouldForward, type ChatEvent } from './normalize'

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

// Reads the session fresh and puts hello + snapshot (+ the last known state)
// ahead of rows queued while the reads were awaited. Callers clear `pending`
// first when what is queued is already covered by the snapshot.
async function queueResync($: EngineInterface, s: State) {
  s.sessionId = await $.session.id()
  const history = await $.session.messages({ as: 'api' })
  const events = Array.isArray(history) ? normalizeSnapshot(history) : []
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
      const { messages = [] } = JSON.parse(res.text) as { messages?: { id: string; text: string }[] }
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
  s.pending.push(...normalizeBlocks(role, stored?.content ?? e.message.content, e.uuid))
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
  }

  on('session.start', async ($, e, next) => {
    const r = await next(e)
    s.paneId = await $.env.get('HERDR_PANE_ID')
    if (!s.paneId) return r
    s.cwd = e.cwd
    s.socketPath = await resolveSocket($)
    if (!s.socketPath) {
      s.paneId = undefined
      return r
    }
    await queueResync($, s)
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
