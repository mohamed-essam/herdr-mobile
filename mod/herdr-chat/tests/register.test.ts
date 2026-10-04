import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'

type Sync = { paneId: string; sessionId: string; events: { type: string; [k: string]: unknown }[] }

// Wires the world beneath the plugin: env, clock, session reads, and a fake
// companion that records each /sync body and answers with queued messages.
function world(on: On, opts: { pane?: string; down?: () => boolean } = {}) {
  mock.env(on, opts.pane === undefined ? { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : opts.pane ? { HERDR_PANE_ID: opts.pane, HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : {})
  const clock = mock.clock(on)
  const syncs: Sync[] = []
  const outbox: { id: string; text: string }[] = []
  const submitted: string[] = []
  let submitGate: Promise<void> = Promise.resolve()
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('session.id', () => ({ value: 'sess-1' }))
  on('session.messages', () => ({
    value: [
      { role: 'user', content: [{ type: 'text', text: 'earlier question' }] },
      { role: 'assistant', content: [{ type: 'text', text: 'earlier answer' }] },
    ],
  }))
  on('http.fetch', ($, e) => {
    if (opts.down?.()) throw new Error('ECONNREFUSED')
    expect(e.url).toBe('http://chat/sync')
    expect(e.init?.socketPath).toBe('/s/chat.sock')
    syncs.push(JSON.parse(String(e.init?.body)))
    const messages = outbox.splice(0)
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ messages }) } }
  })
  on('prompt.submit', async ($, e) => {
    await submitGate
    submitted.push(e.text)
    return { text: e.text }
  })
  return {
    clock, syncs, outbox, submitted,
    hold() { let release!: () => void; submitGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
  }
}

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

  test('forwarded rows are queued in order; the stored row is returned unchanged', async ($, on) => {
    const w = world(on)
    // KIT LIMITATION (2.1.289): the kit cannot answer session.append beneath the plugin; a hook that answers without next is skipped, and next(e) reaches the throwing bottom. This test fails with 'no implementation for session.append' until the kit supports it.
    on('session.append', ($, e) => ({ message: e.message, uuid: e.uuid }))
    await start($)
    await w.clock.advance(1000)
    const r = await $.session.append({ door: 'prompt', uuid: 'u1', origin: { kind: 'user' } as never, message: { type: 'user', role: 'user', content: [{ type: 'text', text: 'run tests' }] } })
    expect(r.uuid).toBe('u1')
    await $.session.append({ door: 'response', uuid: 'a1', origin: { kind: 'model', model: 'm' }, message: { type: 'assistant', role: 'assistant', content: [{ type: 'text', text: 'ok' }] } })
    await $.session.append({ door: 'attachment', uuid: 'x1', origin: { kind: 'model', model: 'm' }, message: { type: 'attachment', content: [] } })
    await $.session.append({ door: 'response', uuid: 's1', agentId: 'sub', origin: { kind: 'model', model: 'm' }, message: { type: 'assistant', role: 'assistant', content: [{ type: 'text', text: 'subagent' }] } })
    await w.clock.advance(1000)
    expect(w.syncs[1]?.events).toEqual([
      { type: 'user_text', uuid: 'u1', text: 'run tests' },
      { type: 'assistant_text', uuid: 'a1#0', text: 'ok' },
    ])
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

  test('/clear re-sends hello and snapshot', async ($, on) => {
    const w = world(on)
    on('session.end', ($, e) => ({ sessionId: e.sessionId }))
    await start($)
    await w.clock.advance(1000)
    await $.session.end({ reason: 'clear', sessionId: 'sess-1', resume: undefined as never })
    await w.clock.advance(1000)
    expect(w.syncs[1]?.events.map(e => e.type)).toEqual(['hello', 'snapshot'])
  })
})
