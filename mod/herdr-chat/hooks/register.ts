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
type State = {
  paneId: string | undefined
  sessionId: string
  cwd: string
  socketPath: string
  pending: Outgoing[]
  offline: boolean
  inFlight: boolean
  submitChain: Promise<unknown>
}

const SYNC_MS = 1000

async function resolveSocket($: EngineInterface): Promise<string> {
  const explicit = await $.env.get('HERDR_MOBILE_CHAT_SOCK')
  if (explicit) return explicit
  const runtime = await $.env.get('XDG_RUNTIME_DIR')
  if (runtime) return `${runtime}/herdr-mobile/chat.sock`
  const id = await $.process.run(['id', '-u'])
  return `/tmp/herdr-mobile-${id.stdout.trim()}/chat.sock`
}

async function queueResync($: EngineInterface, s: State) {
  s.sessionId = await $.session.id()
  const history = await $.session.messages({ as: 'api' })
  const events = Array.isArray(history) ? normalizeSnapshot(history) : []
  s.pending.push({ type: 'hello', sessionId: s.sessionId, cwd: s.cwd }, { type: 'snapshot', events })
}

async function tick($: EngineInterface, s: State) {
  if (s.inFlight || !s.paneId) return
  s.inFlight = true
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
  } finally {
    s.inFlight = false
  }
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
  }

  on('session.start', async ($, e, next) => {
    const r = await next(e)
    s.paneId = await $.env.get('HERDR_PANE_ID')
    if (!s.paneId) return r
    s.cwd = e.cwd
    s.socketPath = await resolveSocket($)
    await queueResync($, s)
    $.clock.every(SYNC_MS, () => void tick($, s))
    return r
  })

  on('session.end', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && e.reason === 'clear') {
      s.pending = []
      await queueResync($, s)
    }
    return r
  })

  on('session.append', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && shouldForward(e) && (e.message.role === 'user' || e.message.role === 'assistant')) {
      s.pending.push(...normalizeBlocks(e.message.role, r.message?.content ?? e.message.content, e.uuid))
    }
    return r
  })

  on('turn.start', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId) s.pending.push({ type: 'state', state: 'working' })
    return r
  })

  on('turn.complete', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && !e.agentId) s.pending.push({ type: 'state', state: 'idle' })
    return r
  })
}
