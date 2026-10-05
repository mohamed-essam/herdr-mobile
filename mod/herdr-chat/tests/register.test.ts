import { describe, expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'
import { BODY_BYTES, HISTORY_IMAGE_BYTES, IMAGE_BYTES, linkWorkflowAgents, queueAppended, type State } from '../hooks/register'
import { eventsFromTranscript } from '../hooks/transcript'
import { linkSpawn, newAgentsState, recordWorkflow } from '../hooks/agents'
import { taskFromLaunch } from '../hooks/tasks'

const state = (paneId: string | undefined): State => ({ paneId, sessionId: '', cwd: '', socketPath: '', pending: [], imageQueue: [], offline: false, inFlight: false, building: false, submitChain: Promise.resolve(), needResync: false, lastState: undefined, timer: undefined, transcriptPath: undefined, historyLacksPath: false, openQuestions: new Map(), agents: newAgentsState(), tasks: new Map(), tasksQueued: false })

const MB = 1024 * 1024
const FETCH_BODY_LIMIT = 4 * MB

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
function world(on: On, opts: { pane?: string; down?: () => boolean; nosock?: boolean; xdg?: string; env?: Record<string, string> } = {}) {
  if (opts.xdg) mock.env(on, { HERDR_PANE_ID: 'w1:p1', XDG_RUNTIME_DIR: opts.xdg })
  else if (opts.nosock) mock.env(on, { HERDR_PANE_ID: 'w1:p1' })
  else mock.env(on, opts.pane === undefined ? { HERDR_PANE_ID: 'w1:p1', HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock', ...opts.env } : opts.pane ? { HERDR_PANE_ID: opts.pane, HERDR_MOBILE_CHAT_SOCK: '/s/chat.sock' } : {})
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
  // A fake `find <root> -maxdepth 2 -name <name> -print -quit` prints the
  // first file under <root> so named; `finds` lists each run's argv.
  const files: Record<string, string> = {}
  const reads: string[] = []
  const finds: string[][] = []
  const runs: { line: number; cut: boolean; dropped: boolean }[] = []
  const seds: number[] = []
  const tail = { limit: 4194304 }
  let submitGate: Promise<void> = Promise.resolve()
  let readGate: Promise<void> = Promise.resolve()
  // A fake `find <dir> -maxdepth 1 -name <glob> [-printf '%T@ %p\n']` (the
  // resync's listings, kept in `scans`, apart from `finds`) prints each file
  // whose path is <dir>/<name matching the glob>; `-printf` puts its
  // `mtimes` entry (default 0) first. It exits 1 when nothing is under <dir>
  // (as find does for a missing dir), or always with `scan.exit` set.
  const scans: string[][] = []
  const mtimes: Record<string, number> = {}
  const scan = { exit: 0 }
  const agentList: { id: string; status: string; description: string; type: string; teammateId?: string }[] = []
  on('agent.list', () => ({ value: agentList as never }))
  on('process.run', async ($, e) => {
    if (e.argv[0] === 'find' && e.argv[3] === '1') {
      scans.push([...e.argv])
      const [, dir, depth, depthN, nameFlag, glob, printf, format] = e.argv
      expect([depth, depthN, nameFlag]).toEqual(['-maxdepth', '1', '-name'])
      if (e.argv.length === 8) expect([printf, format]).toEqual(['-printf', '%T@ %p\n'])
      else expect(e.argv.length).toBe(6)
      const pattern = glob!.split('*').map(p => p.replace(/[.+?^$()|[\]\\{}]/g, '\\$&')).join('.*')
      const name = new RegExp('^' + pattern + '$')
      const under = Object.keys(files).filter(f => f.startsWith(`${dir}/`))
      const hits = under.filter(f => name.test(f.slice(dir!.length + 1)))
      const stdout = hits.map(f => (printf ? `${mtimes[f] ?? 0}.0000000000 ${f}\n` : `${f}\n`)).join('')
      const exitCode = scan.exit || (under.length ? 0 : 1)
      return { value: { exitCode, stdout: exitCode ? '' : stdout, stderr: '', isStdoutTruncated: false, isStderrTruncated: false } }
    }
    if (e.argv[0] === 'find') {
      finds.push([...e.argv])
      const [, root, depth, depthN, nameFlag, name, print, quit] = e.argv
      expect([depth, depthN, nameFlag, print, quit, e.argv.length]).toEqual(['-maxdepth', '2', '-name', '-print', '-quit', 8])
      const hit = Object.keys(files).find(f => f.startsWith(`${root}/`) && f.endsWith(`/${name}`) && f.slice(root!.length + 1).split('/').length <= 2)
      return { value: { exitCode: 0, stdout: hit ? `${hit}\n` : '', stderr: '', isStdoutTruncated: false, isStderrTruncated: false } }
    }
    // A fake `sed -n -E '<line>{s#…#…#g;p;q}' -- <path>`: prints that one
    // line with long base64 `data` strings emptied, cut at `limit` as above.
    if (e.argv[0] === 'sed') {
      const [, n, ext, script, dashes, path] = e.argv
      expect([n, ext, dashes, e.argv.length]).toEqual(['-n', '-E', '--', 6])
      expect(script).toMatch(/^\d+\{s#"data":"\[A-Za-z0-9\+\/=\]\{1000\}\[A-Za-z0-9\+\/=\]\*"#"data":""#g;p;q\}$/)
      const line = Number(/^(\d+)\{/.exec(script!)![1])
      seds.push(line)
      const text = files[path!]!.split('\n')[line - 1]!.replace(/"data":"[A-Za-z0-9+/=]{1000,}"/g, '"data":""')
      const out = cutUtf8(`${text}\n`, tail.limit)
      return { value: { exitCode: 0, stdout: out.text, stderr: '', isStdoutTruncated: out.cut, isStderrTruncated: false } }
    }
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
    // The engine refuses a request body over 4 MiB characters before sending it.
    if (String(e.init?.body).length > FETCH_BODY_LIMIT) throw new Error('herdr-chat: $.http.fetch: request body over the limit')
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
    clock, syncs, sizes, outbox, submitted, current, answer, files, reads, finds, runs, seds, tail, counts, scans, mtimes, scan, agentList,
    hold() { let release!: () => void; submitGate = new Promise(r => (release = r)); return release },
    holdReads() { let release!: () => void; readGate = new Promise(r => (release = r)); return release },
    all: () => syncs.flatMap(s => s.events),
    kinds: (i: number) => syncs[i]!.events.map(e => e.type),
    // Each complete agent thread snapshot in send order.
    threads() {
      const out: { agentId: string; total: number; events: any[] }[] = []
      const open = new Map<string, { agentId: string; total: number; events: any[] }>()
      for (const e of syncs.flatMap(s => s.events) as any[]) {
        if (!e.agentId || !e.type.startsWith('snapshot_')) continue
        if (e.type === 'snapshot_begin') open.set(e.agentId, { agentId: e.agentId, total: e.total, events: [] })
        else if (e.type === 'snapshot_chunk') open.get(e.agentId)!.events.push(...e.events)
        else if (e.type === 'snapshot_end') out.push(open.get(e.agentId)!)
      }
      return out
    },
    // Each complete chunked (main) snapshot in send order: the session its hello
    // named, its begin's total, its chunks' events, the sync carrying its end.
    snapshots() {
      const out: { sessionId: string; total: number; events: any[]; end: number }[] = []
      let cur = { sessionId: '', total: -1, events: [] as any[], end: -1 }
      syncs.forEach((s, i) => {
        for (const e of s.events as any[]) {
          if (e.agentId) continue
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
    const img = { mediaType: 'image/png', data: 'QUJD' }
    expect(s.imageQueue).toEqual([{ id: 'r1#0.0', img, bytes: JSON.stringify(img).length }])
  })

  test('a linked agent’s rows are queued tagged; attachment-door rows are dropped', () => {
    const s = state('w1:p1')
    linkSpawn(s.agents, { tool_use_id: 'toolu_1', description: 'd', subagentType: 'general-purpose' }, 'aa1', 1)
    queueAppended(s, { door: 'attachment', uuid: 'x1', agentId: 'aa1', message: { role: 'user', content: [] } }, undefined)
    queueAppended(s, { door: 'attachment', uuid: 'x2', message: { role: 'user', content: [] } }, undefined)
    expect(s.pending).toEqual([])
    queueAppended(s, { door: 'response', uuid: 's1', agentId: 'aa1', message: { role: 'assistant', content: [{ type: 'text', text: 'subagent' }] } }, undefined)
    expect(s.pending).toEqual([{ type: 'assistant_text', uuid: 's1#0', text: 'subagent', agentId: 'aa1', ts: expect.any(Number) }])
  })

  test('a row from an unknown agent is held, not queued', () => {
    const s = state('w1:p1')
    queueAppended(s, { door: 'response', uuid: 's1', agentId: 'zz9', message: { role: 'assistant', content: [{ type: 'text', text: 'early' }] } }, undefined)
    expect(s.pending).toEqual([])
    expect(s.agents.held.get('zz9')?.events.length).toBe(1)
  })

  test('a row from a finished agent sets it running again, control first', () => {
    const s = state('w1:p1')
    linkSpawn(s.agents, { tool_use_id: 'toolu_1', description: 'd', subagentType: 'general-purpose' }, 'aa1', 1)
    s.agents.links.get('aa1')!.status = 'done'
    queueAppended(s, { door: 'response', uuid: 's1', agentId: 'aa1', message: { role: 'assistant', content: [{ type: 'text', text: 'again' }] } }, undefined)
    expect(s.pending.map(e => e.type)).toEqual(['agent', 'assistant_text'])
    expect((s.pending[0] as any).agent.status).toBe('running')
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

  test('a subagent turn.complete queues its status; the main state events are unchanged', async ($, on) => {
    const w = world(on)
    on('turn.complete', () => ({ text: 'answer' }))
    on('agent.spawn', () => ({ model: 'haiku', agentId: 'aa1' }))
    await start($)
    await w.clock.advance(2000)
    await $.agent.spawn({ prompt: 'p', description: 'd' } as never)
    const done = (isAborted: boolean) => $.turn.complete({ answer: 'a', durationMs: 1, isAborted, turnId: 't1', agentId: 'aa1', reason: 'completed' } as never)
    await done(false)
    await done(true)
    await w.clock.advance(1000)
    const agents = w.all().filter(e => e.type === 'agent').map((e: any) => [e.agent.agentId, e.agent.status])
    expect(agents).toEqual([['aa1', 'running'], ['aa1', 'done'], ['aa1', 'failed']])
    expect(w.all().filter(e => e.type === 'state')).toEqual([])
  })

  // The kit cannot answer session.append and the plugin's state is out of
  // reach, so the tick's journal step runs on a state of the test's own, over
  // a fake `tail` of its own.
  test('a held row’s workflow agent links from the journal, its control first', async () => {
    const files: Record<string, string> = {}
    const $ = {
      process: {
        run: async (argv: string[]) => {
          expect([argv[0], argv[1], argv[3], argv.length]).toEqual(['tail', '-n', '--', 5])
          const file = files[argv[4]!]
          if (file === undefined) return { exitCode: 1, stdout: '', stderr: '', isStdoutTruncated: false, isStderrTruncated: false }
          return { exitCode: 0, stdout: file.split('\n').slice(Number(argv[2]!.slice(1)) - 1).join('\n'), stderr: '', isStdoutTruncated: false, isStderrTruncated: false }
        },
      },
    } as never
    const w = { files }
    const s = state('w1:p1')
    recordWorkflow(s.agents, 'toolu_wf', { status: 'async_launched', taskId: 'wfu18ne1l', runId: 'wf_4ddcdf59-066', workflowName: 'tiny-two-agents', summary: 's', transcriptDir: '/p/wf' })
    queueAppended(s, { door: 'response', uuid: 'r1', agentId: 'a3ffd04f6c0bcfe58', message: { role: 'assistant', content: [{ type: 'text', text: 'hi from agent' }] } }, undefined)
    expect(s.pending).toEqual([])
    // Not there yet: nothing links, the cursor stays.
    await linkWorkflowAgents($, s)
    expect(s.pending).toEqual([])
    w.files['/p/wf/journal.jsonl'] = '{"type":"launched"}\n{"type":"started","agentId":"a3ffd04f6c0bcfe58","label":"Say one","phase":"Reply"}\n{"type":"res'
    await linkWorkflowAgents($, s)
    expect(s.pending.map(e => e.type)).toEqual(['agent', 'assistant_text'])
    expect((s.pending[0] as any).agent).toMatchObject({ agentId: 'a3ffd04f6c0bcfe58', parentToolUseId: 'toolu_wf', kind: 'workflow', label: 'Say one', phase: 'Reply', status: 'running' })
    expect(s.pending[1]).toMatchObject({ text: 'hi from agent', agentId: 'a3ffd04f6c0bcfe58' })
    // The cut last line is read again, whole, next time.
    w.files['/p/wf/journal.jsonl'] += 'ult","agentId":"a3ffd04f6c0bcfe58","result":"x"}\n'
    s.pending = []
    await linkWorkflowAgents($, s)
    expect(s.pending.map(e => [e.type, (e as any).agent?.status])).toEqual([['agent', 'done']])
  })

  // Resync from the session's files: the main transcript with an Agent call
  // (toolu_A) and a Workflow call (toolu_W, run wf_r1), the subagent's meta
  // and transcript, the run's journal and its agent's transcript.
  const SESS = '/c/projects/p/sess-1'
  const sideRow = (agentId: string, uuid: string, role: 'user' | 'assistant', text: string) =>
    JSON.stringify({ parentUuid: null, isSidechain: true, agentId, type: role, uuid, timestamp: '2026-10-05T09:24:37.482Z', message: { role, content: role === 'user' ? text : [{ type: 'text', text }] } })
  const meta = (toolUseId: string, description: string) =>
    JSON.stringify({ agentType: 'general-purpose', description, toolUseId, spawnDepth: 1, requestShape: 'background', requestNonInteractive: true, model: 'haiku' })
  function sessionFiles(w: ReturnType<typeof world>) {
    w.files[`${SESS}.jsonl`] = [
      JSON.stringify({ type: 'user', uuid: 'u1', timestamp: '2026-10-05T09:20:00.000Z', message: { role: 'user', content: 'go' } }),
      JSON.stringify({ type: 'assistant', uuid: 'a1', timestamp: '2026-10-05T09:20:01.000Z', message: { role: 'assistant', content: [{ type: 'tool_use', id: 'toolu_A', name: 'Agent', input: { description: 'Background echo test', prompt: 'p', run_in_background: true } }] } }),
      JSON.stringify({ type: 'assistant', uuid: 'a2', timestamp: '2026-10-05T09:20:02.000Z', message: { role: 'assistant', content: [{ type: 'tool_use', id: 'toolu_W', name: 'Workflow', input: { name: 'tiny-two-agents' } }] } }),
      JSON.stringify({ type: 'user', uuid: 'r2', timestamp: '2026-10-05T09:20:03.000Z', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_W', content: 'launched' }] }, toolUseResult: { status: 'async_launched', taskId: 'wfu18ne1l', taskType: 'local_workflow', workflowName: 'tiny-two-agents', runId: 'wf_r1', summary: 's', transcriptDir: '/evil/dir' } }),
    ].join('\n')
    w.files[`${SESS}/subagents/agent-aa1.meta.json`] = meta('toolu_A', 'Background echo test')
    w.files[`${SESS}/subagents/agent-aa1.jsonl`] = [sideRow('aa1', 's1', 'user', 'Run `echo sub-bg`'), sideRow('aa1', 's2', 'assistant', 'sub-bg')].join('\n') + '\n'
    w.files[`${SESS}/subagents/workflows/wf_r1/journal.jsonl`] = [
      JSON.stringify({ type: 'started', agentId: 'bb2', label: 'Say one', phase: 'Reply' }),
      JSON.stringify({ type: 'result', agentId: 'bb2', result: 'one' }),
    ].join('\n') + '\n'
    w.files[`${SESS}/subagents/workflows/wf_r1/agent-bb2.jsonl`] = sideRow('bb2', 'w1', 'assistant', 'one') + '\n'
    w.mtimes[`${SESS}/subagents/agent-aa1.jsonl`] = 100
    w.mtimes[`${SESS}/subagents/workflows/wf_r1/agent-bb2.jsonl`] = 200
  }
  const startAt = async ($: any) => {
    await $.classic.SessionStart({ source: 'startup', transcript_path: `${SESS}.jsonl` })
    await start($)
  }

  test('a resync links subagents and workflow agents from the files, their controls before their threads', async ($, on) => {
    const w = world(on)
    sessionFiles(w)
    await startAt($)
    await w.clock.advance(6000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid ?? e.toolUseId)).toEqual(['u1', 'a1#0', 'a2#0', 'toolu_W'])
    const all = w.all() as any[]
    const agents = all.filter(e => e.type === 'agent').map(e => e.agent)
    expect(agents).toEqual([
      { agentId: 'aa1', parentToolUseId: 'toolu_A', kind: 'subagent', label: 'Background echo test', type: 'general-purpose', status: 'done', ts: expect.any(Number) },
      { agentId: 'bb2', parentToolUseId: 'toolu_W', kind: 'workflow', label: 'Say one', phase: 'Reply', status: 'done', ts: expect.any(Number) },
    ])
    const lastControl = Math.max(...agents.map(a => all.findIndex(e => e.type === 'agent' && e.agent.agentId === a.agentId)))
    const firstThread = all.findIndex(e => e.type === 'snapshot_begin' && e.agentId)
    expect(firstThread > lastControl).toBe(true)
    // The journal and the paths came from the session dir, never the row's transcriptDir.
    expect(w.reads.some(r => r.startsWith('/evil'))).toBe(false)
    expect(w.reads).toContain(`${SESS}/subagents/workflows/wf_r1/journal.jsonl`)
  })

  test('each thread snapshot comes from the agent’s own file, its events tagged', async ($, on) => {
    const w = world(on)
    sessionFiles(w)
    await startAt($)
    await w.clock.advance(6000)
    const ts = Date.parse('2026-10-05T09:24:37.482Z')
    expect(w.threads()).toEqual([
      { agentId: 'aa1', total: 2, events: [
        { type: 'user_text', uuid: 's1', text: 'Run `echo sub-bg`', ts, agentId: 'aa1' },
        { type: 'assistant_text', uuid: 's2#0', text: 'sub-bg', ts, agentId: 'aa1' },
      ] },
      { agentId: 'bb2', total: 1, events: [{ type: 'assistant_text', uuid: 'w1#0', text: 'one', ts, agentId: 'bb2' }] },
    ])
  })

  test('agent.list sets the status; a running subagent is listed as a running task, after the threads', async ($, on) => {
    const w = world(on)
    sessionFiles(w)
    w.agentList.push({ id: 'aa1', status: 'running', description: 'Background echo test', type: 'general-purpose' })
    await startAt($)
    await w.clock.advance(6000)
    const all = w.all() as any[]
    expect(all.filter(e => e.type === 'agent').map(e => [e.agent.agentId, e.agent.status])).toEqual([['aa1', 'running'], ['bb2', 'done']])
    const tasks = all.findIndex(e => e.type === 'tasks')
    expect(all[tasks].tasks).toMatchObject([{ id: 'aa1', kind: 'subagent', label: 'Background echo test', toolUseId: 'toolu_A', status: 'running' }])
    expect(tasks > all.findLastIndex(e => e.type === 'snapshot_end')).toBe(true)
  })

  test('a meta file whose name is not a plain agent id is never read', async ($, on) => {
    const w = world(on)
    sessionFiles(w)
    w.files[`${SESS}/subagents/agent-../../x.meta.json`] = meta('toolu_X', 'evil')
    await startAt($)
    await w.clock.advance(6000)
    expect(w.reads.filter(r => r.includes('..'))).toEqual([])
    expect((w.all() as any[]).filter(e => e.type === 'agent').map(e => e.agent.agentId)).toEqual(['aa1', 'bb2'])
  })

  test('of 25 agents, only the newest 20 threads are replayed', async ($, on) => {
    const w = world(on)
    w.files[`${SESS}.jsonl`] = JSON.stringify({ type: 'user', uuid: 'u1', timestamp: '2026-10-05T09:20:00.000Z', message: { role: 'user', content: 'go' } })
    const ids = Array.from({ length: 25 }, (_, i) => `c${String(i + 1).padStart(2, '0')}`)
    // Modified in reverse id order: c01 newest.
    ids.forEach((id, i) => {
      w.files[`${SESS}/subagents/agent-${id}.meta.json`] = meta(`toolu_${id}`, id)
      w.files[`${SESS}/subagents/agent-${id}.jsonl`] = sideRow(id, `x${id}`, 'assistant', id) + '\n'
      w.mtimes[`${SESS}/subagents/agent-${id}.jsonl`] = 1000 - i
    })
    await startAt($)
    await w.clock.advance(40000)
    expect((w.all() as any[]).filter(e => e.type === 'agent').length).toBe(25)
    expect(w.threads().map(t => t.agentId).sort()).toEqual(ids.slice(0, 20))
  })

  test('a listing that fails still delivers the main snapshot', async ($, on) => {
    const w = world(on)
    sessionFiles(w)
    w.scan.exit = 1
    await startAt($)
    await w.clock.advance(3000)
    expect(w.scans.length > 0).toBe(true)
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid ?? e.toolUseId)).toEqual(['u1', 'a1#0', 'a2#0', 'toolu_W'])
    expect(w.threads()).toEqual([])
  })

  test('/clear resets the agents: a later completion of the old agent queues nothing', async ($, on) => {
    const w = world(on)
    on('session.end', ($, e) => ({ sessionId: e.sessionId }))
    on('turn.complete', () => ({ text: 'answer' }))
    on('agent.spawn', () => ({ model: 'haiku', agentId: 'aa1' }))
    await start($)
    await w.clock.advance(2000)
    await $.agent.spawn({ prompt: 'p', description: 'd' } as never)
    await $.session.end({ reason: 'clear', sessionId: 'sess-1', resume: undefined as never })
    await w.clock.advance(2000)
    await $.turn.complete({ answer: 'a', durationMs: 1, isAborted: false, turnId: 't1', agentId: 'aa1', reason: 'completed' } as never)
    await w.clock.advance(1000)
    expect(w.all().filter(e => e.type === 'agent')).toEqual([])
  })

  // Background tasks. The Bash launch is driven end to end (a mock tool.call
  // beneath answers the spike's result); notice rows reach the plugin through
  // session.append, which the kit can't answer, so those go through queueAppended.
  const noticeRow = (uuid: string, taskId: string, status = 'completed') => ({
    door: 'delivery', uuid,
    message: { role: 'user', content: [{ type: 'text', text: `<task-notification>\n<task-id>${taskId}</task-id>\n<status>${status}</status>\n<summary>Background command "x" ${status}</summary>\n</task-notification>` }] },
  })
  const tasksOf = (events: { type: string }[]) => events.filter(e => e.type === 'tasks') as unknown as { tasks: { id: string; kind: string; label: string; status: string }[] }[]

  test('a background Bash call puts a tasks control with one running shell in the next sync', async ($, on) => {
    const w = world(on)
    on('tool.call', { tool: 'Bash' }, () => ({ result: { backgroundTaskId: 'b4prbe90d', stdout: '', stderr: '' }, text: '' }) as never)
    await start($)
    await w.clock.advance(2000)
    await $.tool.call({ tool: 'Bash', command: 'sleep 25', run_in_background: true } as never)
    await w.clock.advance(1000)
    const sent = tasksOf(w.all() as never)
    expect(sent.length).toBe(1)
    expect(sent[0]!.tasks).toMatchObject([{ id: 'b4prbe90d', kind: 'shell', label: 'sleep 25', status: 'running' }])
  })

  test('a foreground Bash call sends no tasks control', async ($, on) => {
    const w = world(on)
    on('tool.call', { tool: 'Bash' }, () => ({ result: { stdout: 'hi', stderr: '' }, text: '' }) as never)
    await start($)
    await w.clock.advance(2000)
    await $.tool.call({ tool: 'Bash', command: 'echo hi' } as never)
    await w.clock.advance(1000)
    expect(tasksOf(w.all() as never)).toEqual([])
  })

  test('a notice row marks the task done, in a fresh tasks control', () => {
    const s = state('w1:p1')
    taskFromLaunch(s.tasks, 'Bash', 'toolu_b', { command: 'sleep 25' }, { backgroundTaskId: 'b4prbe90d' }, 1)
    queueAppended(s, noticeRow('d1', 'b4prbe90d'), undefined)
    const sent = tasksOf(s.pending as never)
    expect(sent.length).toBe(1)
    expect(sent[0]!.tasks).toMatchObject([{ id: 'b4prbe90d', status: 'done' }])
    expect(s.pending.map(e => e.type)).toEqual(['task_notice', 'tasks'])
  })

  test('two changes within one tick leave a single, newest tasks control', () => {
    const s = state('w1:p1')
    taskFromLaunch(s.tasks, 'Bash', 'toolu_b', { command: 'a' }, { backgroundTaskId: 'b1' }, 1)
    taskFromLaunch(s.tasks, 'Bash', 'toolu_c', { command: 'b' }, { backgroundTaskId: 'b2' }, 2)
    queueAppended(s, noticeRow('d1', 'b1'), undefined)
    queueAppended(s, noticeRow('d2', 'b2', 'failed'), undefined)
    const sent = tasksOf(s.pending as never)
    expect(sent.length).toBe(1)
    expect(Object.fromEntries(sent[0]!.tasks.map(t => [t.id, t.status]))).toEqual({ b1: 'done', b2: 'failed' })
  })

  test('a subagent notice ends the linked agent too', () => {
    const s = state('w1:p1')
    linkSpawn(s.agents, { tool_use_id: 'toolu_a', description: 'd', subagentType: 'general-purpose' }, 'a0ab069d9186e35de', 1)
    queueAppended(s, noticeRow('d1', 'a0ab069d9186e35de'), undefined)
    expect(s.pending.map(e => e.type)).toEqual(['task_notice', 'agent', 'tasks'])
    expect((s.pending[1] as any).agent).toMatchObject({ agentId: 'a0ab069d9186e35de', status: 'done' })
  })

  test('a subagent row does not feed the tasks list', () => {
    const s = state('w1:p1')
    linkSpawn(s.agents, { tool_use_id: 'toolu_a', description: 'd', subagentType: 'general-purpose' }, 'aa1', 1)
    queueAppended(s, { ...noticeRow('d1', 'zz9'), agentId: 'aa1' }, undefined)
    expect(s.tasks.size).toBe(0)
  })

  test('/clear sends an empty tasks control; an outage resync keeps the list', async ($, on) => {
    let down = false
    const w = world(on, { down: () => down })
    on('session.end', ($, e) => ({ sessionId: e.sessionId }))
    on('tool.call', { tool: 'Bash' }, () => ({ result: { backgroundTaskId: 'b4prbe90d' }, text: '' }) as never)
    await start($)
    await w.clock.advance(2000)
    await $.tool.call({ tool: 'Bash', command: 'sleep 25', run_in_background: true } as never)
    await w.clock.advance(1000)
    down = true
    await w.clock.advance(1000)
    down = false
    await w.clock.advance(3000)
    // The resync did not clear it: a later clear sends the empty list.
    await $.session.end({ reason: 'clear', sessionId: 'sess-1', resume: undefined as never })
    await w.clock.advance(3000)
    const sent = tasksOf(w.all() as never)
    expect(sent[sent.length - 1]).toEqual({ type: 'tasks', tasks: [] })
    expect(sent.slice(0, -1).every(c => c.tasks.length === 1)).toBe(true)
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
      expect(w.kinds(3)).toEqual([...RESYNC, 'tasks'])
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
    expect(w.seds).toEqual([2]) // re-read without image data, still too long
  })

  test('a line over the read limit is re-read with its image data emptied; its text stays, its image goes missing', async ($, on) => {
    const w = world(on)
    const row = (uuid: string, content: unknown) => JSON.stringify({ type: 'user', uuid, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content } })
    w.files['/t/p1.jsonl'] = [
      row('u1', 'before'),
      row('u2', [{ type: 'text', text: 'see this' }, { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'A'.repeat(5000) } }, { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'QUJD' } }]),
      row('u3', 'after'),
    ].join('\n')
    w.tail.limit = 1000
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(3000)
    expect(w.seds).toEqual([2])
    expect(w.snapshots()[0]!.events).toEqual([
      { type: 'user_text', uuid: 'u1', text: 'before', ts: 1791126614835 },
      { type: 'user_text', uuid: 'u2', text: 'see this', images: ['u2#1', 'u2#2'], ts: 1791126614835 },
      { type: 'user_text', uuid: 'u3', text: 'after', ts: 1791126614835 },
    ])
    // The emptied image is never queued (answered `missing`); a small one is kept.
    expect(w.syncs.flatMap(s => Object.keys(s.images ?? {}))).toEqual(['u2#2'])
  })

  test('a transcript run that rejects falls back to the api-form history', async ($, on) => {
    const w = world(on)
    await $.classic.SessionStart({ source: 'startup', transcript_path: 'boom' })
    await start($)
    await w.clock.advance(2000)
    expect(w.reads).toEqual(['boom'])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
  })

  test('without a classic path the transcript is found by session id under ~/.claude/projects', async ($, on) => {
    const w = world(on, { env: { HOME: '/home/u' } })
    w.files['/home/u/.claude/projects/-repo/sess-1.jsonl'] = TRANSCRIPT
    await start($)
    await w.clock.advance(2000)
    expect(w.finds).toEqual([['find', '/home/u/.claude/projects', '-maxdepth', '2', '-name', 'sess-1.jsonl', '-print', '-quit']])
    expect(w.reads).toEqual(['/home/u/.claude/projects/-repo/sess-1.jsonl'])
    expect(w.counts.messages).toBe(0)
    expect(w.snapshots()[0]!.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'from the transcript', ts: 1791126614835 }])
  })

  test('CLAUDE_CONFIG_DIR replaces ~/.claude for the id lookup', async ($, on) => {
    const w = world(on, { env: { HOME: '/home/u', CLAUDE_CONFIG_DIR: '/cfg' } })
    w.files['/cfg/projects/-repo/sess-1.jsonl'] = TRANSCRIPT
    await start($)
    await w.clock.advance(2000)
    expect(w.finds.map(f => f[1])).toEqual(['/cfg/projects'])
    expect(w.reads).toEqual(['/cfg/projects/-repo/sess-1.jsonl'])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['u1'])
  })

  test('an id lookup that finds nothing falls back to the api-form history', async ($, on) => {
    const w = world(on, { env: { HOME: '/home/u' } })
    await start($)
    await w.clock.advance(2000)
    expect(w.finds.length).toBe(1)
    expect(w.reads).toEqual([])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
  })

  for (const id of ['../../etc/passwd', 'a b', '*', '']) {
    test(`an unsafe session id (${JSON.stringify(id)}) is never looked up`, async ($, on) => {
      const w = world(on, { env: { HOME: '/home/u' } })
      w.current.id = id
      await start($)
      await w.clock.advance(2000)
      expect(w.finds).toEqual([])
      expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
    })
  }

  test('no HOME and no CLAUDE_CONFIG_DIR: no lookup, api-form history', async ($, on) => {
    const w = world(on)
    await start($)
    await w.clock.advance(2000)
    expect(w.finds).toEqual([])
    expect(w.snapshots()[0]!.events.map((e: any) => e.uuid)).toEqual(['snap-0', 'snap-1#0'])
  })

  test('a classic path is preferred over the id lookup', async ($, on) => {
    const w = world(on, { env: { HOME: '/home/u' } })
    w.files['/t/p1.jsonl'] = TRANSCRIPT
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/p1.jsonl' })
    await start($)
    await w.clock.advance(2000)
    expect(w.finds).toEqual([])
    expect(w.reads).toEqual(['/t/p1.jsonl'])
  })

  test('the id lookup runs fresh at every resync (/clear finds the new session)', async ($, on) => {
    const w = world(on, { env: { HOME: '/home/u' } })
    on('session.end', ($, e) => ({ sessionId: e.sessionId }))
    w.files['/home/u/.claude/projects/-repo/sess-1.jsonl'] = TRANSCRIPT
    w.files['/home/u/.claude/projects/-wt/sess-2.jsonl'] = TRANSCRIPT.replace('from the transcript', 'new session')
    await start($)
    await w.clock.advance(2000)
    await $.session.end({ reason: 'clear', sessionId: 'sess-1', resume: undefined as never })
    w.current.id = 'sess-2'
    await w.clock.advance(2000)
    expect(w.finds.map(f => f[5])).toEqual(['sess-1.jsonl', 'sess-2.jsonl'])
    expect(w.reads).toEqual(['/home/u/.claude/projects/-repo/sess-1.jsonl', '/home/u/.claude/projects/-wt/sess-2.jsonl'])
    const snap = w.snapshots()[1]!
    expect(snap.sessionId).toBe('sess-2')
    expect(snap.events).toEqual([{ type: 'user_text', uuid: 'u1', text: 'new session', ts: 1791126614835 }])
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

  test('images ride beside the events, under the engine body limit, over consecutive syncs', async ($, on) => {
    const w = world(on)
    const big = 'A'.repeat(1.5 * MB)
    w.files['/t/img.jsonl'] = Array.from({ length: 6 }, (_, i) => imageRow(`i${i}`, big)).join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(6000)
    expect(w.snapshots()[0]!.events).toEqual(Array.from({ length: 6 }, (_, i) =>
      ({ type: 'user_text', uuid: `i${i}`, text: '', images: [`i${i}#0`], ts: 1791126614835 })))
    for (const n of w.sizes) expect(n).toBeLessThanOrEqual(BODY_BYTES)
    const carried = w.syncs.map(s => Object.keys(s.images ?? {}))
    expect(carried[0]).toEqual([])
    // Two 1.5 MB images per body: three bodies, newest first.
    expect(carried.slice(1, 4).map(c => c.length)).toEqual([2, 2, 2])
    expect(carried.slice(1, 4).flat()).toEqual(Array.from({ length: 6 }, (_, i) => `i${5 - i}#0`))
    expect(carried[4]).toEqual([])
    expect(w.syncs[1]!.images!['i5#0']).toEqual({ mediaType: 'image/png', data: big })
  })

  test('only the newest 30 history images are sent, newest first; every reference stays', async ($, on) => {
    const w = world(on)
    w.files['/t/img.jsonl'] = Array.from({ length: 40 }, (_, i) => imageRow(`i${i}`, 'QUJD')).join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(3000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.images)).toEqual(Array.from({ length: 40 }, (_, i) => [`i${i}#0`]))
    const sent = w.syncs.flatMap(s => Object.keys(s.images ?? {}))
    expect(sent).toEqual(Array.from({ length: 30 }, (_, i) => `i${39 - i}#0`))
  })

  // The companion evicts its oldest-stored image past 40 MiB (data + media
  // type): history images stay under that, so the newest are never evicted.
  test('history images stay within the companion image byte cap, the newest kept', async ($, on) => {
    const w = world(on)
    const big = 'A'.repeat(1.5 * MB)
    w.files['/t/img.jsonl'] = Array.from({ length: 30 }, (_, i) => imageRow(`i${i}`, big)).join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(20000)
    const sent = w.syncs.flatMap(s => Object.entries(s.images ?? {}))
    expect(sent.length).toBe(26)
    expect(sent.map(([id]) => id)).toEqual(Array.from({ length: 26 }, (_, i) => `i${29 - i}#0`))
    expect(sent.reduce((n, [, img]) => n + img.data.length + img.mediaType.length, 0)).toBeLessThanOrEqual(HISTORY_IMAGE_BYTES)
    expect(HISTORY_IMAGE_BYTES).toBeLessThanOrEqual(40 * MB)
  })

  test('only the newest 5000 history events are sent (the companion ring)', async ($, on) => {
    const w = world(on)
    const rows = Array.from({ length: 5003 }, (_, i) =>
      JSON.stringify({ type: 'user', uuid: `u${i}`, timestamp: '2026-10-04T15:10:14.835Z', message: { role: 'user', content: `q${i}` } }))
    w.files['/t/long.jsonl'] = rows.join('\n')
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/long.jsonl' })
    await start($)
    await w.clock.advance(3000)
    const [snap] = w.snapshots()
    expect(snap!.total).toBe(5000)
    expect(snap!.events.length).toBe(5000)
    expect(snap!.events[0].uuid).toBe('u3')
    expect(snap!.events[4999].uuid).toBe('u5002')
  })

  test('an image over IMAGE_BYTES is dropped, never sent, and does not block the rest', async ($, on) => {
    const w = world(on)
    w.files['/t/img.jsonl'] = [imageRow('huge', 'A'.repeat(IMAGE_BYTES + 1)), imageRow('small', 'QUJD')].join('\n')
    w.tail.limit = 16 * MB // the row itself must be readable here
    await $.classic.SessionStart({ source: 'startup', transcript_path: '/t/img.jsonl' })
    await start($)
    await w.clock.advance(4000)
    expect(w.snapshots()[0]!.events.map((e: any) => e.images)).toEqual([['huge#0'], ['small#0']])
    const sent = w.syncs.flatMap(s => Object.keys(s.images ?? {}))
    expect(sent).toEqual(['small#0'])
    for (const n of w.sizes) expect(n).toBeLessThanOrEqual(BODY_BYTES)
  })
})
