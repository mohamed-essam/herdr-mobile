import type { EngineInterface, Register } from 'claude-code'
import { linkJournal, linkSpawn, newAgentsState, recordWorkflow, releaseHeld, resetAgents, routeAgentEvents, setStatus, type AgentControl, type AgentsState, type Workflow } from './agents'
import { normalizeBlocks, normalizeSnapshot, shouldForward, utf8Bytes, type ChatEvent, type ChatImage, type Normalized } from './normalize'
import { addTranscriptLine, finishTranscriptHistory, HISTORY_IMAGES, newTranscriptHistory, splitPiece } from './transcript'

// History goes out as begin (`total`: its event count), chunks, end.
type Control =
  | { type: 'hello'; sessionId: string; cwd: string }
  | { type: 'snapshot_begin'; total: number }
  | { type: 'snapshot_chunk'; events: ChatEvent[] }
  | { type: 'snapshot_end' }
  | { type: 'state'; state: 'working' | 'idle' }
  // An AskUserQuestion dialog the phone may answer (its tool input's questions).
  | { type: 'question'; uuid: string; toolUseId: string; questions: unknown[]; ts: number }
  // A subagent or workflow agent linked to its parent call, or its new status.
  | AgentControl
type Outgoing = ChatEvent | Control

// A chunk's events serialize to at most CHUNK_BYTES; a /sync body, images
// included, to at most BODY_BYTES ($.http.fetch refuses a request body over
// 4 MiB characters; UTF-8 bytes never undercount them); an image over
// IMAGE_BYTES is dropped (its reference stays, answered `missing`).
export const CHUNK_BYTES = 2 * 1024 * 1024
export const BODY_BYTES = 4_000_000
export const IMAGE_BYTES = 3_500_000
// Only the newest history images are sent (HISTORY_IMAGES, the companion
// keeps 30 per pane), newest first, while their sizes as the companion counts
// them (data + media type) sum to at most HISTORY_IMAGE_BYTES, its per-pane
// byte cap: its eviction of the oldest-stored then never drops the newest,
// and the ones the phone shows first arrive first. Older references stay in
// their events (answered `missing`).
export { HISTORY_IMAGES }
export const HISTORY_IMAGE_BYTES = 40 * 1024 * 1024
// Only the newest HISTORY_EVENTS history events are sent: the companion's
// ring keeps no more.
export const HISTORY_EVENTS = 5000

// Per-load mutable state. Helpers are top-level functions (the engine only
// follows `$` into functions declared at the top of this file), so the state
// travels in this object rather than in register()'s closure.
export type State = {
  paneId: string | undefined
  sessionId: string
  cwd: string
  socketPath: string
  pending: Outgoing[]
  // Images the queued events reference, sent beside them as the body allows;
  // `bytes`: the image's serialized size, measured once when queued.
  imageQueue: QueuedImage[]
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
  // (it moves when the session enters a worktree). Preferred when present;
  // classic events are often routed beneath user-tier plugins (the built-in
  // security plugin does so), so each read otherwise finds the file by id.
  transcriptPath: string | undefined
  // The last snapshot used the api-form history because no transcript path
  // was known or found: the first classic path to arrive asks for one upgrade
  // resync.
  historyLacksPath: boolean
  // Question events whose dialog race has not settled, by toolUseId: every
  // resync re-sends them (the history has no question events).
  openQuestions: Map<string, Outgoing>
  agents: AgentsState
}

export type QueuedImage = { id: string; img: ChatImage; bytes: number }

function queued(id: string, img: ChatImage): QueuedImage {
  return { id, img, bytes: utf8Bytes(JSON.stringify(img)) }
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
// incomplete last line, which is read again whole by the next run. A single
// line over 4 MiB (a row with large images) is re-read alone with its long
// base64 `data` strings emptied (those images are then answered `missing`);
// still too long, it is skipped. Rejects when the file can't be read.
async function readTranscript($: EngineInterface, path: string) {
  const h = newTranscriptHistory()
  let line = 1
  for (;;) {
    const r = await $.process.run(['tail', '-n', `+${line}`, '--', path])
    if (r.exitCode !== 0) throw new Error(`tail exited ${r.exitCode}`)
    const piece = splitPiece(r.stdout, r.isStdoutTruncated)
    for (const l of piece.lines) addTranscriptLine(h, l)
    if (piece.overlong) {
      const stripped = await readStripped($, path, line)
      if (stripped !== undefined) addTranscriptLine(h, stripped)
    }
    if (!r.isStdoutTruncated) break
    line += piece.advance
  }
  return finishTranscriptHistory(h)
}

// Line `line` of the file with every base64 `data` string of 1000+ characters
// emptied; undefined when that fails or is still cut at the read limit. (A
// counted repeat beyond {1000} makes sed's regex too big or very slow.)
async function readStripped($: EngineInterface, path: string, line: number): Promise<string | undefined> {
  try {
    const script = `${line}{s#"data":"[A-Za-z0-9+/=]{1000}[A-Za-z0-9+/=]*"#"data":""#g;p;q}`
    const r = await $.process.run(['sed', '-n', '-E', script, '--', path])
    if (r.exitCode !== 0 || r.isStdoutTruncated) return undefined
    return r.stdout
  } catch {
    return undefined
  }
}

// A session id is the transcript file's name; only a plain one is looked up.
const SESSION_ID = /^[A-Za-z0-9-]{1,128}$/

// Finds the session's transcript as <config dir>/projects/<project>/<id>.jsonl
// (config dir: CLAUDE_CONFIG_DIR, else ~/.claude). Looked up fresh at every
// read, never cached: the file moves when the session enters a worktree, and
// /clear and /resume change the id. Resolves undefined when not found.
async function findTranscript($: EngineInterface, sessionId: string): Promise<string | undefined> {
  if (!SESSION_ID.test(sessionId)) return undefined
  let configDir = await $.env.get('CLAUDE_CONFIG_DIR')
  if (!configDir) {
    const home = await $.env.get('HOME')
    if (!home) return undefined
    configDir = `${home}/.claude`
  }
  try {
    const r = await $.process.run(['find', `${configDir}/projects`, '-maxdepth', '2', '-name', `${sessionId}.jsonl`, '-print', '-quit'])
    if (r.exitCode !== 0) return undefined
    return r.stdout.split('\n').map(l => l.trim()).find(l => l) || undefined
  } catch {
    return undefined
  }
}

// History from the transcript file (real uuids, timestamps, meta rows
// dropped): the classic-supplied path, else the one found by session id. The
// api-form history when neither is known, the read fails or the file has no
// message rows.
async function readHistory($: EngineInterface, s: State, sessionId: string): Promise<Normalized> {
  const path = s.transcriptPath || (await findTranscript($, sessionId))
  s.historyLacksPath = !path
  if (path) {
    let history: Normalized | null = null
    try {
      history = await readTranscript($, path)
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
    const history = await readHistory($, s, sessionId)
    const events = history.events.slice(-HISTORY_EVENTS)
    s.sessionId = sessionId
    const head: Outgoing[] = [{ type: 'hello', sessionId, cwd: s.cwd }, { type: 'snapshot_begin', total: events.length }]
    for (const chunk of chunkEvents(events)) head.push({ type: 'snapshot_chunk', events: chunk })
    head.push({ type: 'snapshot_end' })
    if (s.lastState) head.push({ type: 'state', state: s.lastState })
    head.push(...s.openQuestions.values())
    // A question queued during the build is in both: send it once, here.
    const queued = s.pending.filter(ev => !(ev.type === 'question' && s.openQuestions.has(ev.toolUseId)))
    s.pending = [...head, ...queued]
    s.imageQueue = [...historyImages(events, history.images), ...s.imageQueue]
  } catch {
    // A failed session read: retry on the next tick.
    s.needResync = true
  } finally {
    s.building = false
  }
}

// The history images to send (see HISTORY_IMAGE_BYTES): the newest of those
// the sent events reference, newest first, skipping empty and over-IMAGE_BYTES
// ones (never sent), until the next would pass the companion's byte cap.
export function historyImages(events: readonly ChatEvent[], images: Record<string, ChatImage>): QueuedImage[] {
  const referenced = new Set<string>()
  for (const ev of events) if ('images' in ev) for (const id of ev.images ?? []) referenced.add(id)
  // Entries keep history order (ids are never integer-like keys).
  const newest = Object.entries(images).filter(([id]) => referenced.has(id)).slice(-HISTORY_IMAGES).reverse()
  const out: QueuedImage[] = []
  let stored = 0
  for (const [id, img] of newest) {
    const q = queued(id, img)
    if (!img.data || q.bytes > IMAGE_BYTES) continue
    stored += img.data.length + img.mediaType.length
    if (stored > HISTORY_IMAGE_BYTES) break
    out.push(q)
  }
  return out
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
  const rest: QueuedImage[] = []
  let count = 0
  for (const entry of s.imageQueue) {
    const { id, img } = entry
    if (entry.bytes > IMAGE_BYTES) continue
    const size = utf8Bytes(JSON.stringify(id)) + 1 + entry.bytes + (count ? 1 : 0)
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

// The workflow journal's new complete lines (one read per tick), advancing
// the workflow's line cursor past them; none when the read fails or the file
// is not there yet. A cut piece's last line is read again whole next time; a
// single line over the read limit is skipped.
async function readJournal($: EngineInterface, wf: Workflow): Promise<string[]> {
  try {
    const r = await $.process.run(['tail', '-n', `+${wf.journalLine}`, '--', `${wf.transcriptDir}/journal.jsonl`])
    if (r.exitCode !== 0) return []
    const piece = splitPiece(r.stdout, r.isStdoutTruncated)
    // A whole piece's last element is '' or a line still being written.
    const lines = r.isStdoutTruncated ? piece.lines : piece.lines.slice(0, -1)
    wf.journalLine += r.isStdoutTruncated ? piece.advance : lines.length
    return lines
  } catch {
    return []
  }
}

// Links workflow agents from their journals and releases the rows held for
// agents now linked (their control first), then ages the rest.
export async function linkWorkflowAgents($: EngineInterface, s: State) {
  const controls: AgentControl[] = []
  for (const [runId, wf] of s.agents.workflows) {
    const lines = await readJournal($, wf)
    if (lines.length) controls.push(...linkJournal(s.agents, runId, lines, Date.now()))
  }
  s.pending.push(...controls, ...releaseHeld(s.agents))
}

async function tick($: EngineInterface, s: State) {
  if (s.inFlight || !s.paneId) return
  s.inFlight = true
  try {
    if (s.needResync && !s.offline && !s.building) resync($, s)
    // Offline: an empty probe. Building: a heartbeat; queued rows wait for
    // the history to go first.
    const held = s.offline || s.building
    if (!held && s.agents.held.size) await linkWorkflowAgents($, s)
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

// One string answer per question text; anything else is no answer.
function answerOf(v: unknown): Record<string, string> | null {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null
  const entries = Object.entries(v)
  if (!entries.length || entries.some(([, a]) => typeof a !== 'string')) return null
  return Object.fromEntries(entries) as Record<string, string>
}

// Long-polls the companion for the phone's answer to `toolUseId` until one
// comes or `race.over` (the dialog answered). The companion holds each poll up
// to 25 s and answers `{answer: null}` on timeout. Polls start at most once
// per SYNC_MS: a poll that ended (null, failed, an older companion without
// /answer) sooner than that waits out the rest; one held longer is re-polled
// at once. Never rejects; resolves null once the race is over.
//
// Budget: a `$.clock` wait (this pacing timer included) counts against the
// hook's 10 s budget on its own; `$.http.fetch` and `next(e)` do not. The
// loop is safe only because askPhone's caller starts the dialog's `next(e)`
// before the first poll and the loop stops (nothing more is awaited) once the
// race settles, so `next(e)` is in flight for every wait here.
async function pollAnswer($: EngineInterface, s: State, toolUseId: string, race: { over: boolean }): Promise<Record<string, string> | null> {
  while (!race.over) {
    const started = await $.clock.now()
    try {
      const res = await $.http.fetch('http://chat/answer', {
        method: 'POST',
        socketPath: s.socketPath,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ paneId: s.paneId, toolUseId }),
      })
      if (!res.ok) throw new Error(`answer ${res.status}`)
      const answer = answerOf((JSON.parse(res.text) as { answer?: unknown }).answer)
      if (answer && !race.over) return answer
    } catch {
      // Failed: paced below like a null answer.
    }
    if (race.over) break
    const rest = started + SYNC_MS - (await $.clock.now())
    if (rest > 0) await new Promise<void>(r => $.clock.after(rest, r))
  }
  return null
}

export type AskOutcome<R> = { dialog: R } | { phone: Record<string, string> } | { aborted: true }

// Queues the question (kept open for resyncs until the race settles) and
// races the terminal dialog, already started by the caller, against the
// phone's long-poll. `signal` (the hook's `next.signal`) aborting ends the
// race at once and stops the polling. Exported so tests can abort it: the
// kit has no way to abandon a tool.call from above.
export async function askPhone<R>($: EngineInterface, s: State, toolUseId: string, questions: unknown[], dialog: Promise<R>, signal: AbortSignal): Promise<AskOutcome<R>> {
  const question: Outgoing = { type: 'question', uuid: toolUseId, toolUseId, questions, ts: Date.now() }
  s.pending.push(question)
  s.openQuestions.set(toolUseId, question)
  const race = { over: false }
  let onAbort = () => {}
  const aborted = new Promise<AskOutcome<R>>(r => (onAbort = () => r({ aborted: true })))
  if (signal.aborted) onAbort()
  else signal.addEventListener('abort', onAbort, { once: true })
  const never = new Promise<never>(() => {})
  const phone = pollAnswer($, s, toolUseId, race).then(a => (a ? { phone: a } : never))
  try {
    return await Promise.race([dialog.then(r => ({ dialog: r })), phone, aborted])
  } finally {
    race.over = true
    s.openQuestions.delete(toolUseId)
    signal.removeEventListener('abort', onAbort)
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
  if (e.agentId) {
    // Its row: the agent is running (again), and its control goes first.
    const revived = n.events.length && s.agents.links.get(e.agentId)?.status !== 'running' ? setStatus(s.agents, e.agentId, 'running', Date.now()) : null
    if (revived) s.pending.push(revived)
    s.pending.push(...routeAgentEvents(s.agents, e.agentId, n.events))
  } else s.pending.push(...n.events)
  for (const [id, img] of Object.entries(n.images)) if (img.data) s.imageQueue.push(queued(id, img))
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
    openQuestions: new Map(),
    agents: newAgentsState(),
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
      // The new session's transcript is found by its new id at the resync, or
      // named by its own classic event (whose arrival upgrades an api-form
      // stand-in).
      s.transcriptPath = undefined
      resetAgents(s.agents)
    }
    return r
  })

  on('session.append', async ($, e, next) => {
    const r = await next(e)
    queueAppended(s, e, r.message)
    return r
  })

  // The terminal dialog races the phone: the question rides the next sync and
  // whichever answers first wins. A phone answer closes the dialog (a hook
  // that returns while its `next` is pending aborts what runs beneath); when
  // the dialog wins, a poll still in flight is ignored.
  //
  // Budget: the hook's 10 s clock stops only while a `next(e)` or a non-clock
  // `$` call is in flight; a `$.clock` wait alone would count. `next(e)` is
  // started here, before askPhone's first poll, and stays in flight until the
  // race settles, after which nothing more is awaited; so the whole wait,
  // poll pacing timers included, costs the hook nothing.
  on('tool.call', { tool: 'AskUserQuestion' }, async ($, e, next) => {
    if (!s.paneId) return next(e)
    const won = await askPhone($, s, e.tool_use_id, e.questions, next(e), next.signal)
    if ('dialog' in won) return won.dialog
    if ('phone' in won) return { result: { questions: e.questions, answers: won.phone } }
    // Abandoned (interrupted, or a hook above settled): the engine has gone
    // on without this answer.
    return { deny: 'interrupted' }
  })

  // Subagents are linked to their Agent call here: the hook only records.
  on('agent.spawn', async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && 'agentId' in r) {
      const c = linkSpawn(s.agents, e, r.agentId, Date.now())
      if (c) s.pending.push(c)
    }
    return r
  })

  // A workflow's agents are linked later, from its journal (see tick).
  on('tool.call', { tool: 'Workflow' }, async ($, e, next) => {
    const r = await next(e)
    if (s.paneId && 'result' in r) recordWorkflow(s.agents, e.tool_use_id, r.result)
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
    if (s.paneId && e.agentId) {
      const c = setStatus(s.agents, e.agentId, e.isAborted ? 'failed' : 'done', Date.now())
      if (c) s.pending.push(c)
    } else if (s.paneId) {
      s.lastState = 'idle'
      s.pending.push({ type: 'state', state: 'idle' })
    }
    return r
  })
}
