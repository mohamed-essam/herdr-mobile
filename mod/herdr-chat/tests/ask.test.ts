import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import { askPhone, type State } from '../hooks/register'
import { newAgentsState } from '../hooks/agents'

type Sync = { paneId: string; sessionId: string; events: { type: string; [k: string]: unknown }[] }
type Poll = { paneId: string; toolUseId: string }
// What the fake companion does with one /answer poll: answer it (`null` = the
// long-poll timed out), fail it, or hold it until released.
type Reply = { answer: Record<string, string> | null } | 'fail' | Promise<{ answer: Record<string, string> | null }>

const QUESTIONS = [
  { question: 'Which color do you prefer?', header: 'Color', options: [{ label: 'Red', description: 'warm' }, { label: 'Blue', description: 'cool' }], multiSelect: false },
]

// The world beneath the plugin: env, clock, session reads, a fake companion
// (/sync records bodies; /answer serves `replies` in order, then nulls) and
// the terminal dialog (a test tool.call hook held until `answerDialog`).
function world(on: On, opts: { pane?: boolean } = {}) {
  mock.env(on, opts.pane === false ? {} : { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' })
  const clock = mock.clock(on)
  const syncs: Sync[] = []
  const polls: Poll[] = []
  const replies: Reply[] = []
  const answer = { resync: false }
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  on('session.id', () => ({ value: 'sess-1' }))
  let readGate: Promise<void> = Promise.resolve()
  on('session.messages', async () => {
    await readGate
    return { value: [] as never }
  })
  on('http.fetch', async ($, e) => {
    expect(e.init?.socketPath).toBe('/s/chat.sock')
    expect(e.init?.method).toBe('POST')
    if (e.url === 'http://chat/sync') {
      syncs.push(JSON.parse(String(e.init?.body)))
      const body = answer.resync ? { messages: [], resync: true } : { messages: [] }
      answer.resync = false
      return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify(body) } }
    }
    expect(e.url).toBe('http://chat/answer')
    polls.push(JSON.parse(String(e.init?.body)))
    const r = replies.shift() ?? { answer: null }
    if (r === 'fail') throw new Error('ECONNREFUSED')
    return { value: { status: 200, ok: true, headers: {}, text: JSON.stringify(await r) } }
  })
  const dialog = { calls: 0, toolUseId: '', aborted: false }
  let answerDialog!: (label: string) => void
  const shown = new Promise<string>(r => (answerDialog = r))
  on('tool.call', { tool: 'AskUserQuestion' }, async ($, e) => {
    dialog.calls++
    dialog.toolUseId = e.tool_use_id
    const label = await shown
    return { result: { questions: e.questions, answers: { [e.questions[0]!.question]: label } } } as never
  })
  return {
    clock, syncs, polls, replies, dialog, answerDialog, answer,
    hold() {
      let release!: (a: Record<string, string> | null) => void
      replies.push(new Promise(r => (release = a => r({ answer: a }))))
      return release
    },
    holdReads() { let release!: () => void; readGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
  }
}

const state = (): State => ({ paneId: 'w1:p1', sessionId: '', cwd: '', socketPath: '/s/chat.sock', pending: [], imageQueue: [], offline: false, inFlight: false, building: false, submitChain: Promise.resolve(), needResync: false, lastState: undefined, timer: undefined, transcriptPath: undefined, historyLacksPath: false, openQuestions: new Map(), agents: newAgentsState(), tasks: new Map(), tasksQueued: false })

const start = ($: any) => $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: true })
const ask = ($: any) => $.tool.call({ tool: 'AskUserQuestion', questions: QUESTIONS })

// Lets in-flight hooks and fetches run.
async function flush(w: { clock: { settle: () => Promise<void> } }) {
  for (let i = 0; i < 5; i++) await w.clock.settle()
}

describe('AskUserQuestion from the phone', () => {
  test('a phone answer on the 2nd poll wins while the dialog is open; the question was queued', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000) // hello + snapshot out
    w.replies.push({ answer: null }, { answer: { 'Which color do you prefer?': 'Blue' } })
    const call = ask($)
    await flush(w)
    expect(w.polls.length).toBe(1)
    await w.clock.advance(1000) // an instant null is re-polled a second after it started
    const r = await call
    expect(w.dialog.calls).toBe(1)
    expect(w.polls.length).toBe(2)
    expect(w.polls[0]).toEqual({ paneId: 'w1:p1', toolUseId: w.dialog.toolUseId })
    expect(r.result).toEqual({ questions: QUESTIONS, answers: { 'Which color do you prefer?': 'Blue' } })
    await w.clock.advance(1000)
    const q = w.all().filter(e => e.type === 'question')
    expect(q).toEqual([{ type: 'question', uuid: w.dialog.toolUseId, toolUseId: w.dialog.toolUseId, questions: QUESTIONS, ts: expect.any(Number) }])
  })

  test('the dialog answering first wins unchanged; a late phone answer is ignored', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    const release = w.hold()
    const call = ask($)
    await flush(w)
    expect(w.polls.length).toBe(1)
    w.answerDialog('Red')
    const r = await call
    expect(r.result).toEqual({ questions: QUESTIONS, answers: { 'Which color do you prefer?': 'Red' } })
    release({ 'Which color do you prefer?': 'Blue' })
    await flush(w)
    await w.clock.advance(3000)
    expect(w.polls.length).toBe(1) // no poll after the dialog won
  })

  test('a failing /answer keeps polling once per interval; the dialog still answers', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    w.replies.push('fail', 'fail', 'fail')
    const release = w.hold()
    const call = ask($)
    await flush(w)
    expect(w.polls.length).toBe(1)
    await w.clock.advance(1000)
    await flush(w)
    expect(w.polls.length).toBe(2)
    await w.clock.advance(1000)
    await flush(w)
    await w.clock.advance(1000)
    await flush(w)
    expect(w.polls.length).toBe(4) // three failures, then the held poll
    w.answerDialog('Red')
    const r = await call
    expect(r.result).toEqual({ questions: QUESTIONS, answers: { 'Which color do you prefer?': 'Red' } })
    release(null)
  })

  test('a malformed phone answer is not used', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    w.replies.push({ answer: { 'Which color do you prefer?': 7 } as never })
    const release = w.hold()
    const call = ask($)
    await flush(w)
    await w.clock.advance(1000)
    await flush(w)
    expect(w.polls.length).toBe(2)
    w.answerDialog('Red')
    expect((await call).result).toEqual({ questions: QUESTIONS, answers: { 'Which color do you prefer?': 'Red' } })
    release(null)
  })

  test('a companion answering null at once is polled about once per second', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    const call = ask($)
    await flush(w)
    expect(w.polls.length).toBe(1)
    for (let i = 0; i < 3; i++) {
      await w.clock.advance(1000)
      await flush(w)
    }
    expect(w.polls.length).toBe(4)
    w.answerDialog('Red')
    await call
  })

  test('a poll answered null after a long hold is re-polled at once', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    const release = w.hold()
    const second = w.hold()
    const call = ask($)
    await flush(w)
    await w.clock.advance(20000) // the companion holds the poll
    await flush(w)
    expect(w.polls.length).toBe(1)
    release(null)
    await flush(w)
    expect(w.polls.length).toBe(2) // no clock advance needed
    w.answerDialog('Red')
    await call
    second(null)
  })

  test('a resync while the dialog is open re-sends the question after snapshot_end; not once it settled', async ($, on) => {
    const w = world(on)
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    await start($)
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await w.clock.advance(2000)
    const release = w.hold()
    const call = ask($)
    await flush(w)
    await w.clock.advance(1000) // the question goes out live
    expect(w.all().filter(e => e.type === 'question').length).toBe(1)
    w.answer.resync = true
    await w.clock.advance(1000) // answered resync:true
    await w.clock.advance(1000) // rebuilt (pending cleared)
    await $.turn.start({ text: 'again', turnId: 't2' }) // a row queued meanwhile
    await w.clock.advance(1000) // resent
    const last = w.syncs[w.syncs.length - 1]!.events
    expect(last.map(e => e.type)).toEqual(['hello', 'snapshot_begin', 'snapshot_end', 'state', 'question', 'state'])
    expect(last[4]).toEqual({ type: 'question', uuid: w.dialog.toolUseId, toolUseId: w.dialog.toolUseId, questions: QUESTIONS, ts: expect.any(Number) })
    w.answerDialog('Red')
    await call
    release(null)
    await flush(w)
    w.answer.resync = true
    for (let i = 0; i < 3; i++) await w.clock.advance(1000)
    const after = w.syncs[w.syncs.length - 1]!.events.map(e => e.type)
    expect(after).toEqual(['hello', 'snapshot_begin', 'snapshot_end', 'state'])
  })

  test('without HERDR_PANE_ID the dialog alone answers', async ($, on) => {
    const w = world(on, { pane: false })
    await start($)
    await w.clock.advance(2000)
    const call = ask($)
    await flush(w)
    w.answerDialog('Red')
    const r = await call
    expect(r.result).toEqual({ questions: QUESTIONS, answers: { 'Which color do you prefer?': 'Red' } })
    expect(w.polls.length).toBe(0)
    expect(w.syncs.length).toBe(0)
  })

  test('a question raised while the history is being built goes out once, after snapshot_end', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    w.answer.resync = true
    await w.clock.advance(1000) // answered resync:true
    const releaseRead = w.holdReads()
    await w.clock.advance(1000) // the rebuild starts and waits on the read
    const release = w.hold()
    const call = ask($) // queued while the build runs
    await flush(w)
    releaseRead()
    await flush(w)
    await w.clock.advance(1000)
    const last = w.syncs[w.syncs.length - 1]!.events.map(e => e.type)
    expect(last).toEqual(['hello', 'snapshot_begin', 'snapshot_end', 'question'])
    expect(w.all().filter(e => e.type === 'question').length).toBe(1)
    w.answerDialog('Red')
    await call
    release(null)
  })

  // The kit cannot abandon a tool.call from above (test hooks sit beneath),
  // so askPhone is driven directly with a hand-made `$`: a clock the test
  // moves and an /answer that holds every poll until released.
  test('an abandoned dialog (next.signal aborted) ends the race at once and stops polling', async () => {
    const s = state()
    const polls: string[] = []
    const held: Array<() => void> = []
    const timers: Array<() => void> = []
    const $ = {
      clock: { now: async () => 0, after: (ms: number, fn: () => void) => (timers.push(fn), { cancel() {} }) },
      http: {
        fetch: async (url: string, init: { body: string }) => {
          polls.push(url)
          expect(JSON.parse(init.body)).toEqual({ paneId: 'w1:p1', toolUseId: 'tu-1' })
          await new Promise<void>(r => held.push(r))
          return { status: 200, ok: true, headers: {}, text: JSON.stringify({ answer: null }) }
        },
      },
    }
    const ctl = new AbortController()
    const outcome = askPhone($ as any, s, 'tu-1', QUESTIONS, new Promise<never>(() => {}), ctl.signal)
    for (let i = 0; i < 5; i++) await Promise.resolve()
    expect(polls).toEqual(['http://chat/answer'])
    expect([...s.openQuestions.keys()]).toEqual(['tu-1'])
    expect(s.pending.map(e => e.type)).toEqual(['question'])
    ctl.abort()
    expect(await outcome).toEqual({ aborted: true })
    expect(s.openQuestions.size).toBe(0)
    held.splice(0).forEach(r => r()) // the poll in flight answers null
    for (let i = 0; i < 10; i++) await Promise.resolve()
    timers.splice(0).forEach(f => f())
    for (let i = 0; i < 10; i++) await Promise.resolve()
    expect(polls.length).toBe(1)
    expect(timers.length).toBe(0)
  })
})
