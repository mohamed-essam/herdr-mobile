import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import { queueAppended, type State } from '../hooks/register'

const state = (paneId: string | undefined): State => ({ paneId, sessionId: '', cwd: '', socketPath: '', pending: [], offline: false, inFlight: false, submitChain: Promise.resolve(), needResync: false, lastState: undefined, timer: undefined, transcriptPath: undefined })

type Sync = { paneId: string; sessionId: string; events: { type: string; [k: string]: unknown }[] }

// Wires the world beneath the plugin: env, clock, session reads, and a fake
// companion that records each /sync body and answers with queued messages.
function world(on: On, opts: { pane?: string; down?: () => boolean; nosock?: boolean; xdg?: string } = {}) {
  if (opts.xdg) mock.env(on, { HERDR_PANE_ID: 'w1:p1', XDG_RUNTIME_DIR: opts.xdg })
  else if (opts.nosock) mock.env(on, { HERDR_PANE_ID: 'w1:p1' })
  else mock.env(on, opts.pane === undefined ? { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : opts.pane ? { HERDR_PANE_ID: opts.pane, HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : {})
  const clock = mock.clock(on)
  const syncs: Sync[] = []
  const outbox: { id: string; text: string }[] = []
  const submitted: string[] = []
  const answer = { resync: false }
  const current = { id: 'sess-1', history: [
    { role: 'user', content: [{ type: 'text', text: 'earlier question' }] },
    { role: 'assistant', content: [{ type: 'text', text: 'earlier answer' }] },
  ] as unknown[] }
  // Transcript files the fake fs can read (any other path rejects) and the
  // paths the plugin asked for.
  const files: Record<string, string> = {}
  const reads: string[] = []
  let submitGate: Promise<void> = Promise.resolve()
  on('fs.read', ($, e) => {
    reads.push(e.path)
    const text = files[e.path]
    if (text === undefined) throw new Error('ENOENT')
    return { value: text }
  })
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  // No settings hooks beneath: the classic events answer with no decision.
  on('classic.SessionStart', () => ({}))
  on('classic.UserPromptSubmit', () => ({}))
  on('classic.Stop', () => ({}))
  on('session.id', () => ({ value: current.id }))
  on('session.messages', () => ({ value: current.history as never }))
  on('http.fetch', ($, e) => {
    if (opts.down?.()) throw new Error('ECONNREFUSED')
    expect(e.url).toBe('http://chat/sync')
    expect(e.init?.socketPath).toBe(opts.xdg ? `${opts.xdg}/herdr-mobile/chat.sock` : '/s/chat.sock')
    syncs.push(JSON.parse(String(e.init?.body)))
    const messages = outbox.splice(0)
    const body = answer.resync ? { messages, resync: true } : { messages }
    answer.resync = false
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify(body) } }
  })
  on('prompt.submit', async ($, e) => {
    await submitGate
    submitted.push(e.text)
    return { text: e.text }
  })
  return {
    clock, syncs, outbox, submitted, current, answer, files, reads,
    hold() { let release!: () => void; submitGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
  }
}

const TRANSCRIPT = [
  JSON.stringify({ type: 'queue-operation', operation: 'enqueue' }),
  JSON.stringify({ type: 'user', uuid: 'u1', isSidechain: false, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'from the transcript' } }),
  JSON.stringify({ type: 'user', uuid: 'm1', isMeta: true, isSidechain: false, timestamp: '2026-10-04T15:10:15.000Z', message: { role: 'user', content: 'Base directory for this skill: /x' } }),
].join('\n')

const start = ($: any) => $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: true })

describe('herdr-chat', () => {
  test('first sync carries hello then a snapshot of the history', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(1000)
    expect(w.syncs[0]?.paneId).toBe('w1:p1')
    expect(w.syncs[0]?.sessionId).toBe('sess-1')
    const [hello, snap] = w.syncs[0]!.events
    expect(hello).toEqual({ type: 'hello', sessionId: 'sess-1', cwd: '/repo' })
    expect(snap?.type).toBe('snapshot')
    expect((snap as any).events).toEqual([
      { type: 'user_text', uuid: 'snap-0', text: 'earlier question' },
      { type: 'assistant_text', uuid: 'snap-1#0', text: 'earlier answer' },
    ])
  })

  test('does nothing without HERDR_PANE_ID', async ($, on) => {
    const w = world(on, { pane: '' })
    await start($)
    await w.clock.advance(3000)
    expect(w.syncs.length).toBe(0)
  })

  // The kit cannot drive session.append end-to-end (a test hook cannot answer it
  // beneath the plugin), so the forwarding logic is unit-tested directly.
  test('does nothing without a socket env var (no /tmp fallback)', async ($, on) => {
    const w = world(on, { nosock: true })
    await start($)
    await w.clock.advance(3000)
    expect(w.syncs.length).toBe(0)
  })

  test('falls back to XDG_RUNTIME_DIR for the socket path', async ($, on) => {
    const w = world(on, { xdg: '/run/user/7' })
    await start($)
    await w.clock.advance(1000)
    expect(w.syncs.length).toBe(1)
  })

  test('forwarded rows are queued in order', () => {
    const s = state('w1:p1')
    queueAppended(s, { door: 'prompt', uuid: 'u1', message: { role: 'user', content: [{ type: 'text', text: 'run tests' }] } }, undefined)
    queueAppended(s, { door: 'response', uuid: 'a1', message: { role: 'assistant', content: [{ type: 'text', text: 'ok' }] } }, undefined)
    expect(s.pending).toEqual([
      { type: 'user_text', uuid: 'u1', text: 'run tests', ts: expect.any(Number) },
      { type: 'assistant_text', uuid: 'a1#0', text: 'ok', ts: expect.any(Number) },
    ])
  })

  test('attachment-door rows and subagent rows are dropped', () => {
    const s = state('w1:p1')
    queueAppended(s, { door: 'attachment', uuid: 'x1', message: { role: 'user', content: [] } }, undefined)
    queueAppended(s, { door: 'response', uuid: 's1', agentId: 'sub', message: { role: 'assistant', content: [{ type: 'text', text: 'subagent' }] } }, undefined)
    expect(s.pending).toEqual([])
  })

  test('stored (rewritten) content is preferred over the incoming content', () => {
    const s = state('w1:p1')
    queueAppended(s, { door: 'prompt', uuid: 'u1', message: { role: 'user', content: [{ type: 'text', text: 'secret' }] } }, { content: [{ type: 'text', text: 'redacted' }] })
    expect(s.pending).toEqual([{ type: 'user_text', uuid: 'u1', text: 'redacted', ts: expect.any(Number) }])
  })

  test('nothing is queued when paneId is unset', () => {
    const s = state(undefined)
    queueAppended(s, { door: 'prompt', uuid: 'u1', message: { role: 'user', content: [{ type: 'text', text: 'hi' }] } }, undefined)
    expect(s.pending).toEqual([])
  })

  test('turn start and main-thread completion queue working then idle', async ($, on) => {
    const w = world(on)
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    on('turn.complete', () => ({ text: 'answer' }))
    await start($)
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await $.turn.complete({ answer: 'a', durationMs: 1, isAborted: false, turnId: 't1', agentId: 'sub', reason: 'completed' } as never)
    await $.turn.complete({ answer: 'a', durationMs: 1, isAborted: false, turnId: 't1', reason: 'completed' } as never)
    await w.clock.advance(1000)
    const states = w.all().filter(e => e.type === 'state')
    expect(states).toEqual([{ type: 'state', state: 'working' }, { type: 'state', state: 'idle' }])
  })

  test('outbox messages are submitted as the user, in order', async ($, on) => {
    const w = world(on)
    await start($)
    w.outbox.push({ id: 'm1', text: 'first' }, { id: 'm2', text: 'second' })
    await w.clock.advance(1000)
    await w.clock.settle()
    expect(w.submitted).toEqual(['first', 'second'])
  })

  test('sync keeps running while a submit is pending', async ($, on) => {
    const w = world(on)
    await start($)
    const release = w.hold()
    w.outbox.push({ id: 'm1', text: 'while busy' })
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    expect(w.syncs.length).toBe(3)
    expect(w.submitted).toEqual([])
    release()
    await w.clock.settle()
    expect(w.submitted).toEqual(['while busy'])
  })

  test('offline then recovered re-sends hello and snapshot', async ($, on) => {
    let down = false
    const w = world(on, { down: () => down })
    await start($)
    await w.clock.advance(1000) // initial hello+snapshot
    down = true
    await w.clock.advance(1000) // fails, batch dropped
    down = false
    await w.clock.advance(1000) // empty probe succeeds -> queue resync
    await w.clock.advance(1000) // resync goes out
    const last = w.syncs[w.syncs.length - 1]!.events.map(e => e.type)
    expect(last).toEqual(['hello', 'snapshot'])
  })

  for (const reason of ['clear', 'resume'] as const) {
    test(`/${reason} resyncs under the new session id with the new history`, async ($, on) => {
      const w = world(on)
      on('session.end', ($, e) => ({ sessionId: e.sessionId }))
      await start($)
      await w.clock.advance(1000)
      await $.session.end({ reason, sessionId: 'sess-1', resume: undefined as never })
      w.current.id = 'sess-2'
      w.current.history = [{ role: 'user', content: [{ type: 'text', text: 'fresh question' }] }]
      await w.clock.advance(1000)
      const sync = w.syncs[1]!
      expect(sync.sessionId).toBe('sess-2')
      expect(sync.events.map(e => e.type)).toEqual(['hello', 'snapshot'])
      expect(sync.events[0]).toEqual({ type: 'hello', sessionId: 'sess-2', cwd: '/repo' })
      expect((sync.events[1] as any).events).toEqual([{ type: 'user_text', uuid: 'snap-0', text: 'fresh question' }])
    })
  }

  test('recovery after an outage re-sends the last turn state after the snapshot', async ($, on) => {
    let down = false
    const w = world(on, { down: () => down })
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    await start($)
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await w.clock.advance(1000)
    down = true
    await w.clock.advance(1000)
    down = false
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    const last = w.syncs[w.syncs.length - 1]!.events.map(e => e.type === 'state' ? (e as any).state : e.type)
    expect(last).toEqual(['hello', 'snapshot', 'working'])
  })

  test('a resync request from the companion re-sends hello and snapshot next tick', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(1000) // initial hello+snapshot
    w.answer.resync = true // e.g. the companion restarted between ticks
    await w.clock.advance(1000) // empty sync, answered with resync:true
    await w.clock.advance(1000)
    expect(w.syncs[1]!.events).toEqual([])
    expect(w.syncs[2]!.events.map(e => e.type)).toEqual(['hello', 'snapshot'])
    await w.clock.advance(1000)
    expect(w.syncs[3]!.events).toEqual([])
  })

  test('a non-interactive session (claude -p, SDK) leaves the pane alone', async ($, on) => {
    const w = world(on)
    await $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: false })
    await w.clock.advance(3000)
    expect(w.syncs.length).toBe(0)
  })

  test('history comes from the transcript named by classic.SessionStart', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(1000)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
    const snap = w.syncs[0]!.events[1] as any
    expect(snap.type).toBe('snapshot')
    expect(snap.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'from the transcript', ts: 1791126614835 }])
  })

  for (const ev of ['UserPromptSubmit', 'Stop'] as const) {
    test(`a later classic.${ev} moves the transcript path for the next resync`, async ($, on) => {
      const w = world(on)
      w.files['/t/p1.jsonl'] = TRANSCRIPT
      w.files['/t/p2.jsonl'] = TRANSCRIPT.replace('from the transcript', 'moved transcript')
      await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
      await start($)
      await w.clock.advance(1000)
      if (ev === 'UserPromptSubmit') await $.classic.UserPromptSubmit({ prompt: 'hi', transcript_path: '/t/p2.jsonl' })
      else await $.classic.Stop({ stop_hook_active: false, transcript_path: '/t/p2.jsonl' })
      w.answer.resync = true
      await w.clock.advance(1000) // answered with resync:true
      await w.clock.advance(1000) // resync goes out
      expect(w.reads).toEqual(['/t/p1.jsonl', '/t/p2.jsonl'])
      const snap = w.syncs[2]!.events[1] as any
      expect(snap.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'moved transcript', ts: 1791126614835 }])
    })
  }

  test('an empty transcript_path keeps the known path', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await $.classic.UserPromptSubmit({ prompt: 'hi', transcript_path: '' })
    await start($)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
  })

  test('an unreadable transcript falls back to the api-form history', async ($, on) => {
    const w = world(on)
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/missing.jsonl' })
    await start($)
    await w.clock.advance(1000)
    expect(w.reads).toEqual(['/t/missing.jsonl'])
    expect((w.syncs[0]!.events[1] as any).events).toEqual([
      { type: 'user_text', uuid: 'snap-0', text: 'earlier question' },
      { type: 'assistant_text', uuid: 'snap-1#0', text: 'earlier answer' },
    ])
  })

  test('a transcript without message rows falls back to the api-form history', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = JSON.stringify({ type: 'queue-operation' })
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(1000)
    expect((w.syncs[0]!.events[1] as any).events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
  })

  test('live rows carry the time they were queued', () => {
    const s = state('w1:p1')
    const before = Date.now()
    queueAppended(s, { door: 'response', uuid: 'a1', message: { role: 'assistant', content: [{ type: 'text', text: 'ok' }, { type: 'tool_use', id: 't1', name: 'Bash', input: {} }] } }, undefined)
    expect(s.pending.length).toBe(2)
    for (const e of s.pending as any[]) {
      expect(typeof e.ts).toBe('number')
      expect(e.ts >= before && e.ts <= Date.now()).toBe(true)
    }
  })
})
