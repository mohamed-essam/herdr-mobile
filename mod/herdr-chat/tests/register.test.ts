import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import { queueAppended, type State } from '../hooks/register'
import { eventsFromTranscript } from '../hooks/transcript'

const state = (paneId: string | undefined): State => ({ paneId, sessionId: '', cwd: '', socketPath: '', pending: [], imageQueue: [], offline: false, inFlight: false, building: false, submitChain: Promise.resolve(), needResync: false, lastState: undefined, timer: undefined, transcriptPath: undefined, historyLacksPath: false })

const MB = 1024 * 1024

// The first `limit` UTF-8 bytes of `s`, as text; a character the cut falls
// inside is dropped (`dropped`).
function cutUtf8(s: string, limit: number): { text: string; cut: boolean; dropped: boolean } {
  let bytes = 0
  let i = 0
  while (i < s.length) {
    const c = s.codePointAt(i)!
    const n = c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4
    if (bytes + n > limit) return { text: s.slice(0, i), cut: true, dropped: bytes < limit }
    bytes += n
    i += c > 0xffff ? 2 : 1
  }
  return { text: s, cut: false, dropped: false }
}

type Sync = {
  paneId: string
  sessionId: string
  events: { type: string; [k: string]: unknown }[]
  images?: Record<string, { mediaType: string; data: string }>
}

// A small history's resync, all in one sync body.
const RESYNC = ['hello', 'snapshot_begin', 'snapshot_chunk', 'snapshot_end']

// Wires the world beneath the plugin: env, clock, session reads, and a fake
// companion that records each /sync body and answers with queued messages.
function world(on: On, opts: { pane?: string; down?: () => boolean; nosock?: boolean; xdg?: string } = {}) {
  if (opts.xdg) mock.env(on, { HERDR_PANE_ID: 'w1:p1', XDG_RUNTIME_DIR: opts.xdg })
  else if (opts.nosock) mock.env(on, { HERDR_PANE_ID: 'w1:p1' })
  else mock.env(on, opts.pane === undefined ? { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : opts.pane ? { HERDR_PANE_ID: opts.pane, HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : {})
  const clock = mock.clock(on)
  const syncs: Sync[] = []
  const sizes: number[] = []
  const outbox: { id: string; text: string }[] = []
  const submitted: string[] = []
  const answer = { resync: false }
  const current = { id: 'sess-1', history: [
    { role: 'user', content: [{ type: 'text', text: 'earlier question' }] },
    { role: 'assistant', content: [{ type: 'text', text: 'earlier answer' }] },
  ] as unknown[] }
  // Transcript files a fake `tail -n +<line> <path>` serves (another path
  // exits 1, `boom` makes the run reject), with stdout cut at `limit` bytes as
  // the engine cuts at 4 MiB: a character straddling the cut is dropped.
  // `reads` lists each read's path (its first run); `runs` every run.
  const files: Record<string, string> = {}
  const reads: string[] = []
  const runs: { line: number; cut: boolean; dropped: boolean }[] = []
  const tail = { limit: 4194304 }
  let submitGate: Promise<void> = Promise.resolve()
  let readGate: Promise<void> = Promise.resolve()
  on('process.run', async ($, e) => {
    const [cmd, flag, from, dashes, path] = e.argv
    expect([cmd, flag, dashes, e.argv.length]).toEqual(['tail', '-n', '--', 5])
    const line = Number(from!.slice(1))
    if (line === 1) reads.push(path!)
    await readGate
    if (path === 'boom') throw new Error('spawn failed')
    const file = files[path!]
    if (file === undefined) return { value: { exitCode: 1, stdout: '', stderr: 'no such file', isStdoutTruncated: false, isStderrTruncated: false } }
    const out = cutUtf8(file.split('\n').slice(line - 1).join('\n'), tail.limit)
    runs.push({ line, cut: out.cut, dropped: out.dropped })
    return { value: { exitCode: 0, stdout: out.text, stderr: '', isStdoutTruncated: out.cut, isStderrTruncated: false } }
  })
  on('session.start', ($, e) => ({ cwd: e.cwd }))
  // No settings hooks beneath: the classic events answer with no decision.
  on('classic.SessionStart', () => ({}))
  on('classic.UserPromptSubmit', () => ({}))
  on('classic.Stop', () => ({}))
  on('session.id', () => ({ value: current.id }))
  const counts = { messages: 0 }
  on('session.messages', () => { counts.messages++; return { value: current.history as never } })
  on('http.fetch', ($, e) => {
    if (opts.down?.()) throw new Error('ECONNREFUSED')
    expect(e.url).toBe('http://chat/sync')
    expect(e.init?.socketPath).toBe(opts.xdg ? `${opts.xdg}/herdr-mobile/chat.sock` : '/s/chat.sock')
    sizes.push(String(e.init?.body).length)
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
    clock, syncs, sizes, outbox, submitted, current, answer, files, reads, runs, tail, counts,
    hold() { let release!: () => void; submitGate = new Promise(r => (release = r)); return release },
    holdReads() { let release!: () => void; readGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
    kinds: (i: number) => syncs[i]!.events.map(e => e.type),
    // Each complete chunked snapshot in send order: the session its hello
    // named, its begin's total, its chunks' events, the sync carrying its end.
    snapshots() {
      const out: { sessionId: string; total: number; events: any[]; end: number }[] = []
      let cur = { sessionId: '', total: -1, events: [] as any[], end: -1 }
      syncs.forEach((s, i) => {
        for (const e of s.events as any[]) {
          if (e.type === 'hello') cur = { sessionId: e.sessionId, total: -1, events: [], end: -1 }
          else if (e.type === 'snapshot_begin') cur.total = e.total
          else if (e.type === 'snapshot_chunk') cur.events.push(...e.events)
          else if (e.type === 'snapshot_end') out.push({ ...cur, end: i })
        }
      })
      return out
    },
  }
}

const TRANSCRIPT = [
  JSON.stringify({ type: 'queue-operation', operation: 'enqueue' }),
  JSON.stringify({ type: 'user', uuid: 'u1', isSidechain: false, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: 'from the transcript' } }),
  JSON.stringify({ type: 'user', uuid: 'm1', isMeta: true, isSidechain: false, timestamp: '2026-10-04T15:10:15.000Z', message: { role: 'user', content: 'Base directory for this skill: /x' } }),
].join('\n')

const imageRow = (uuid: string, data: string) =>
  JSON.stringify({ type: 'user', uuid, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: [{ type: 'image', source: { type: 'base64', media_type: 'image/png', data } }] } })

const start = ($: any) => $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: true })

describe('herdr-chat', () => {
  test('hello and the chunked history go out once the history is built', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(1000) // the build starts; the heartbeat still goes out
    expect(w.syncs[0]?.events).toEqual([])
    await w.clock.advance(1000)
    expect(w.syncs[1]?.paneId).toBe('w1:p1')
    expect(w.syncs[1]?.sessionId).toBe('sess-1')
    expect(w.kinds(1)).toEqual(RESYNC)
    expect(w.syncs[1]!.events[0]).toEqual({ type: 'hello', sessionId: 'sess-1', cwd: '/repo' })
    const [snap] = w.snapshots()
    expect(snap!.total).toBe(2)
    expect(snap!.events).toEqual([
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

  test('images of a forwarded row go to the image queue', () => {
    const s = state('w1:p1')
    queueAppended(s, { door: 'tool-result', uuid: 'r1', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: [{ type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'QUJD' } }] }] } }, undefined)
    expect(s.pending).toEqual([{ type: 'tool_result', toolUseId: 't1', isError: false, preview: '', images: ['r1#0.0'], ts: expect.any(Number) }])
    expect(s.imageQueue).toEqual([['r1#0.0', { mediaType: 'image/png', data: 'QUJD' }]])
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
    await w.clock.advance(2000) // the start resync goes out first
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
    await w.clock.advance(2000) // initial hello+snapshot
    down = true
    await w.clock.advance(1000) // fails, batch dropped
    down = false
    await w.clock.advance(1000) // empty probe succeeds -> history rebuilt
    await w.clock.advance(1000) // resync goes out
    expect(w.kinds(w.syncs.length - 1)).toEqual(RESYNC)
    expect(w.snapshots().length).toBe(2)
  })

  for (const reason of ['clear', 'resume'] as const) {
    test(`/${reason} resyncs under the new session id with the new history`, async ($, on) => {
      const w = world(on)
      on('session.end', ($, e) => ({ sessionId: e.sessionId }))
      await start($)
      await w.clock.advance(2000)
      await $.session.end({ reason, sessionId: 'sess-1', resume: undefined as never })
      w.current.id = 'sess-2'
      w.current.history = [{ role: 'user', content: [{ type: 'text', text: 'fresh question' }] }]
      await w.clock.advance(2000)
      const sync = w.syncs[3]!
      expect(sync.sessionId).toBe('sess-2')
      expect(w.kinds(3)).toEqual(RESYNC)
      expect(sync.events[0]).toEqual({ type: 'hello', sessionId: 'sess-2', cwd: '/repo' })
      expect(w.snapshots()[1]!.events).toEqual([{ type: 'user_text', uuid: 'snap-0', text: 'fresh question' }])
    })
  }

  test('recovery after an outage re-sends the last turn state after the snapshot', async ($, on) => {
    let down = false
    const w = world(on, { down: () => down })
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    await start($)
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await w.clock.advance(2000)
    down = true
    await w.clock.advance(1000)
    down = false
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    const last = w.syncs[w.syncs.length - 1]!.events.map(e => e.type === 'state' ? (e as any).state : e.type)
    expect(last).toEqual([...RESYNC, 'working'])
  })

  test('a resync request from the companion re-sends hello and snapshot', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000) // initial hello+snapshot
    w.answer.resync = true // e.g. the companion restarted between ticks
    await w.clock.advance(1000) // empty sync, answered with resync:true
    await w.clock.advance(1000) // the history is rebuilt
    await w.clock.advance(1000)
    expect(w.syncs[2]!.events).toEqual([])
    expect(w.syncs[3]!.events).toEqual([])
    expect(w.kinds(4)).toEqual(RESYNC)
    await w.clock.advance(1000)
    expect(w.syncs[5]!.events).toEqual([])
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
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
    expect(w.snapshots()[0]!.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'from the transcript', ts: 1791126614835 }])
  })

  for (const ev of ['UserPromptSubmit', 'Stop'] as const) {
    test(`a later classic.${ev} moves the transcript path for the next resync`, async ($, on) => {
      const w = world(on)
      w.files['/t/p1.jsonl'] = TRANSCRIPT
      w.files['/t/p2.jsonl'] = TRANSCRIPT.replace('from the transcript', 'moved transcript')
      await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
      await start($)
      await w.clock.advance(2000)
      if (ev === 'UserPromptSubmit') await $.classic.UserPromptSubmit({ prompt: 'hi', transcript_path: '/t/p2.jsonl' })
      else await $.classic.Stop({ stop_hook_active: false, transcript_path: '/t/p2.jsonl' })
      w.answer.resync = true
      await w.clock.advance(1000) // answered with resync:true
      await w.clock.advance(2000) // rebuilt, then sent
      expect(w.reads).toEqual(['/t/p1.jsonl', '/t/p2.jsonl'])
      expect(w.snapshots()[1]!.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'moved transcript', ts: 1791126614835 }])
    })
  }

  test('an empty transcript_path keeps the known path', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await $.classic.UserPromptSubmit({ prompt: 'hi', transcript_path: '' })
    await start($)
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
  })

  test('an unreadable transcript falls back to the api-form history', async ($, on) => {
    const w = world(on)
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/missing.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['/t/missing.jsonl'])
    expect(w.snapshots()[0]!.events).toEqual([
      { type: 'user_text', uuid: 'snap-0', text: 'earlier question' },
      { type: 'assistant_text', uuid: 'snap-1#0', text: 'earlier answer' },
    ])
  })

  test('a transcript without message rows falls back to the api-form history', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = JSON.stringify({ type: 'queue-operation' })
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
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

  test('a transcript read in many cut pieces yields the same history as one read', async ($, on) => {
    const w = world(on)
    const rows: string[] = []
    for (let i = 0; i < 30; i++) {
      rows.push(JSON.stringify({ type: 'user', uuid: `u${i}`, isSidechain: false, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: `q${i} héllo wörld ✓ — 😀 ${'é'.repeat(i)}` } }))
      rows.push(JSON.stringify({ type: 'assistant', uuid: `a${i}`, isSidechain: false, timestamp: '2026-10-04T15:10:15.000Z', message: { role: 'assistant', content: [{ type: 'text', text: `a${i} ünïcödé 日本語 ${'ß'.repeat(i)}` }] } }))
    }
    const file = rows.join('\n') + '\n'
    w.files['/t/big.jsonl'] = file
    w.tail.limit = 700
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/big.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.runs.length > 5).toBe(true)
    expect(w.runs.some(r => r.dropped)).toBe(true) // a character straddled a cut
    const snap = w.snapshots()[0]!
    expect(snap.events.length).toBe(60)
    expect(snap.events).toEqual(eventsFromTranscript(file))
  })

  test('a line longer than the read limit is skipped, the rest still read', async ($, on) => {
    const w = world(on)
    const row = (uuid: string, text: string) => JSON.stringify({ type: 'user', uuid, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: text } })
    w.files['/t/p1.jsonl'] = [row('u1', 'before'), row('u2', 'x'.repeat(500)), row('u3', 'after')].join('\n')
    w.tail.limit = 200
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.text)).toEqual(['before', 'after'])
  })

  test('a transcript run that rejects falls back to the api-form history', async ($, on) => {
    const w = world(on)
    await $.classic.SessionStart({ source: 'startup', transcript_path: 'boom' })
    await start($)
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['boom'])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
  })

  test('the first transcript path after a path-less snapshot triggers one upgrade resync', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    w.files['/t/p2.jsonl'] = TRANSCRIPT
    await start($)
    await w.clock.advance(2000)
    expect(w.reads).toEqual([])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await w.clock.advance(2000)
    expect(w.kinds(3)).toEqual(RESYNC)
    expect(w.snapshots()[1]!.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'from the transcript', ts: 1791126614835 }])
    await $.classic.UserPromptSubmit({ prompt: 'hi', transcript_path: '/t/p2.jsonl' })
    await w.clock.advance(2000)
    expect(w.syncs[4]!.events).toEqual([])
    expect(w.syncs[5]!.events).toEqual([])
    expect(w.reads).toEqual(['/t/p1.jsonl'])
  })

  test('session.start itself reads nothing: the first tick does the resync', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    expect(w.reads).toEqual([])
    expect(w.counts.messages).toBe(0)
    await w.clock.advance(1000)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
    await w.clock.advance(1000)
    expect(w.kinds(1)).toEqual(RESYNC)
  })

  for (const reason of ['clear', 'resume'] as const) {
    test(`/${reason} forgets the old transcript until the new session names its own`, async ($, on) => {
      const w = world(on)
      on('session.end', ($, e) => ({ sessionId: e.sessionId }))
      w.files['/t/old.jsonl'] = TRANSCRIPT
      w.files['/t/new.jsonl'] = TRANSCRIPT.replace('from the transcript', 'new session')
      await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/old.jsonl' })
      await start($)
      await w.clock.advance(2000)
      await $.session.end({ reason, sessionId: 'sess-1', resume: undefined as never })
      w.current.id = 'sess-2'
      await w.clock.advance(2000) // no path known: api-form fallback
      expect(w.reads).toEqual(['/t/old.jsonl'])
      expect(w.snapshots()[1]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
      await $.classic.SessionStart({ source: reason, transcript_path: '/t/new.jsonl' })
      await w.clock.advance(2000) // upgrade resync from the new transcript
      expect(w.reads).toEqual(['/t/old.jsonl', '/t/new.jsonl'])
      const snap = w.snapshots()[2]!
      expect(w.syncs[snap.end]!.sessionId).toBe('sess-2')
      expect(snap.sessionId).toBe('sess-2')
      expect(snap.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'new session', ts: 1791126614835 }])
    })
  }

  test('a long history goes out in chunks of at most 2 MB, one chunk per sync, live rows after the end', async ($, on) => {
    const w = world(on)
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    // 72 rows of 64 KB text (~4.6 MB): three chunks.
    const rows = Array.from({ length: 72 }, (_, i) =>
      JSON.stringify({ type: 'user', uuid: `u${i}`, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: `${i} ${'x'.repeat(64 * 1024 - 8)}` } }))
    w.files['/t/long.jsonl'] = rows.join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/long.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.kinds(1)).toEqual(['hello', 'snapshot_begin', 'snapshot_chunk'])
    await $.turn.start({ text: 'hi', turnId: 't1' }) // a live row queued mid-snapshot
    await w.clock.advance(1000)
    expect(w.kinds(2)).toEqual(['snapshot_chunk'])
    await w.clock.advance(1000)
    expect(w.kinds(3)).toEqual(['snapshot_chunk', 'snapshot_end', 'state'])
    const chunks = w.all().filter(e => e.type === 'snapshot_chunk') as any[]
    expect(chunks.length).toBe(3)
    for (const c of chunks) expect(JSON.stringify(c.events).length).toBeLessThanOrEqual(2 * MB)
    const [snap] = w.snapshots()
    expect(snap!.total).toBe(72)
    expect(snap!.events.map((e: any) => e.uuid)).toEqual(rows.map((_, i) => `u${i}`))
  })

  test('a slow history read still syncs every tick; rows queued meanwhile follow the snapshot', async ($, on) => {
    const w = world(on)
    on('turn.start', ($, e) => ({ turnId: e.turnId }))
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    const release = w.holdReads()
    await w.clock.advance(1000) // the read starts and blocks
    await $.turn.start({ text: 'hi', turnId: 't1' })
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    await w.clock.advance(1000)
    expect(w.syncs.length).toBe(4)
    for (const s of w.syncs) expect(s.events).toEqual([])
    release()
    await w.clock.settle()
    await w.clock.advance(1000)
    expect(w.syncs.length).toBe(5)
    expect(w.syncs[4]!.events.map(e => e.type === 'state' ? (e as any).state : e.type)).toEqual([...RESYNC, 'working', 'working'])
    expect(w.reads).toEqual(['/t/p1.jsonl'])
  })

  test('a resync request answered while the history is being read does not restart the read', async ($, on) => {
    const w = world(on)
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    const release = w.holdReads()
    await w.clock.advance(1000)
    w.answer.resync = true // the companion has no hello yet
    await w.clock.advance(1000)
    release()
    await w.clock.settle()
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['/t/p1.jsonl'])
    expect(w.kinds(2)).toEqual(RESYNC)
    expect(w.syncs[3]!.events).toEqual([])
  })

  test('images ride beside the events, at most 6 MB per body, over consecutive syncs', async ($, on) => {
    const w = world(on)
    const big = 'A'.repeat(1.5 * MB)
    w.files['/t/img.jsonl'] = Array.from({ length: 6 }, (_, i) => imageRow(`i${i}`, big)).join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(4000)
    expect(w.snapshots()[0]!.events).toEqual(Array.from({ length: 6 }, (_, i) =>
      ({ type: 'user_text', uuid: `i${i}`, text: '', images: [`i${i}#0`], ts: 1791126614835 })))
    for (const n of w.sizes) expect(n).toBeLessThanOrEqual(6 * MB)
    const carried = w.syncs.map(s => Object.keys(s.images ?? {}))
    expect(carried[0]).toEqual([])
    expect(carried[1]!.length > 0 && carried[2]!.length > 0).toBe(true)
    expect([...carried[1]!, ...carried[2]!]).toEqual(Array.from({ length: 6 }, (_, i) => `i${i}#0`))
    expect(carried[3]).toEqual([])
    expect(w.syncs[1]!.images!['i0#0']).toEqual({ mediaType: 'image/png', data: big })
  })

  test('an image over 5 MB is dropped, never sent, and does not block the rest', async ($, on) => {
    const w = world(on)
    w.files['/t/img.jsonl'] = [imageRow('huge', 'A'.repeat(6 * MB)), imageRow('small', 'QUJD')].join('\n')
    w.tail.limit = 16 * MB // the row itself must be readable here
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(4000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.images)).toEqual([['huge#0'], ['small#0']])
    const sent = w.syncs.flatMap(s => Object.keys(s.images ?? {}))
    expect(sent).toEqual(['small#0'])
    for (const n of w.sizes) expect(n).toBeLessThanOrEqual(6 * MB)
  })
})
