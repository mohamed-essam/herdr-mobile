# herdr-mobile Chat View v2 (subagent threads, workflow grouping, background tasks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each subagent and workflow agent as a glanceable card under the call that started it, with its full thread one tap away, and a background-tasks strip in the chat plus a "⟳ N" badge on the dashboard.

**Architecture:** The mod stops dropping `agentId` rows. It links each agent to its parent `Agent`/`Workflow` tool use (via `agent.spawn` and workflow `journal.jsonl`), tags the agent's events with `agentId`, and sends `agent` (link + status) and `tasks` controls. The companion stores each agent's events in a per-agent thread buffer. It keeps agent summaries (adding activity computed from thread events) and the task list as pane state, and serves threads through `chat_open {agentId}` on protocol 10. The app renders agent cards in the timeline, a read-only thread screen, a tasks strip and sheet, and a dashboard badge.

**Tech Stack:** Claude Code 2.1.289 hooks-module plugin (TypeScript, `claude plugin test`); Go 1.23; Kotlin + Jetpack Compose + Material3.

**Spec:** `docs/superpowers/specs/2026-10-05-herdr-mobile-chat-v2-agents-tasks-design.md` (builds on the v1 and v1.1 chat view specs).

**How to read this plan.** This follows the v1.1 plan's convention. Each task gives exact contracts (wire shapes, names, limits) and exact test cases, not whole-file code. The code has been through several review rounds. Implementers read the current file before changing it and fit the change into its existing structure, naming and comment style. Exact values below are binding.

**One refinement over the spec (binding).** §1.2 of the spec has the mod compute each agent's `activity`. Instead, **the companion computes it** from the agent's thread events with its existing `activityOf` (the same function the dashboard uses), and merges it into the summary it stores and sends. The mod's `agent` control carries no `activity`. Wire shapes to the app are unchanged. Task 4 updates the spec's §1.2/§1.3 wording to match.

## Global Constraints

- `companionProtocol` 9 → 10. Every change is additive. A protocol-9 app never sends `agentId` and must see exactly today's behavior. New frames go only to main-stream chat subscribers. Unknown fields are ignored.
- Hooks never do I/O beyond what they already do. `agent.spawn` and `tool.call` hooks only `await next(e)` and record into state. Journal reads, file listing and transcript reads happen in the 1 s sync tick or the background history build. `session.append` hooks keep awaiting `next(e)` and returning its result unchanged.
- The engine's static check: `$` may only be used as `$.noun.event(...)` at a call site inside the hook, or inside a **top-level function declaration** that receives `$`. Never pass `$` to an arrow function or a const helper. A violation fails the module load with "`$` is passed to … which is not a function declared at the top of this file". Check `--debug-file` output when live-testing.
- `tool.call` results as `next(e)` resolves them: `{ result: <BuiltinToolResults[tool]>, text, ... }`. Shapes from the spike, binding:
  - Bash background: `result.backgroundTaskId: string`
  - Agent background: `result.status === "async_launched"`, `result.agentId`
  - Workflow: `result.status === "async_launched"`, `result.taskId`, `result.runId`, `result.workflowName`, `result.summary`, `result.transcriptDir`
  - Monitor: `result.taskId`
- `agent.spawn`: input `e.tool_use_id`, `e.description`, `e.subagentType`, `e.parentAgentId?`. `next(e)` resolves `{ model, agentId? }` or `{ deny }`.
- Task notification text: `<task-notification>` containing `<task-id>`, `<tool-use-id>`, `<status>`, `<summary>`. Status words: `completed` → `done`; `failed`, `killed`, `stopped` and anything else → `failed`.
- Ids accepted in file paths: agent id `^[a-z0-9]{1,64}$`, run id `^wf_[a-z0-9-]{1,64}$`. Anything else is ignored, never interpolated.
- Limits:
  - mod: unlinked hold 200 events per id, dropped after 3 ticks; finished tasks expire after 10 min (600 000 ms); resync replays the newest 20 agents
  - companion: `ThreadCap = 1000`, `ThreadsMax = 20`; thread snapshot tail = the existing `SnapshotTail` (300); thread history page ≤ `HistoryMax` (300)
- Agent `status` values on every wire: `running` | `done` | `failed`. Agent `kind`: `subagent` | `workflow`. Task `kind`: `shell` | `subagent` | `workflow` | `monitor`. Task `status`: `running` | `done` | `failed`. Times are epoch ms numbers.
- App: at most one thread subscription at a time (the open thread screen). Image cache stays keyed by `(paneId, id)`, shared by the main stream and threads.
- Suites:
  - mod: `claude plugin test mod/herdr-chat`, `claude plugin validate mod/herdr-chat`, and `npx -p typescript tsc -p <scratch tsconfig that includes /home/messam/tmp/golang/claude-1000/bundled-skills/2.1.289/42c18bb875218fb9edf6e55d55211d7a/plugin-authoring/types/claude-code.d.ts and the mod's hooks + tests>`. The `tsc` on PATH is not TypeScript.
  - Go: `go -C companion vet ./...`, `go -C companion test -race ./...`, `gofmt -l companion` (prints nothing).
  - app: `export ANDROID_HOME=/home/messam/Android/Sdk; cd app && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`. A fresh worktree needs `app/local.properties` copied from the main checkout first.
- Commit messages end with the line `Co-Authored-By: Claude <noreply@anthropic.com>`.

## Review Focus

1. **A busy subagent (hundreds of tool calls) next to a long main chat:** main history must not be pushed out and the dashboard line must stay main-thread. Test in Task 4: 1500 thread events leave the main ring and summary untouched, and the thread keeps the newest 1000.
2. **A workflow agent's first rows arrive before its journal line is written:** rows are held, not lost, and released in order once linked. A fork/compaction agent that never links is dropped after 3 ticks without growing memory. Test in Task 1.
3. **The same background agent notifies twice (resumed):** one task entry, updated in place, with no duplicate in the strip. Test in Task 2.
4. **A thread screen open across a companion restart or a main resync:** the thread re-opens on the new epoch, or shows "no longer available". No crash, no stale events mixed in. Tests in Tasks 4 and 6.
5. **Back from a nested thread, then back again:** each back pops one level, and the last back returns to the chat at its scroll position, never to the dashboard. Test in Task 8.

---

### Task 1: Mod — link agents and route their rows

**Files:** Modify `mod/herdr-chat/hooks/register.ts`, `mod/herdr-chat/hooks/normalize.ts`. Create `mod/herdr-chat/hooks/agents.ts` (pure state and logic, no `$`). Tests: `mod/herdr-chat/tests/agents.test.ts` (new), `register.test.ts`, `normalize.test.ts`.

**Interfaces (produced):**
- `agents.ts`:
  ```ts
  export type AgentKind = 'subagent' | 'workflow'
  export type AgentStatus = 'running' | 'done' | 'failed'
  export type AgentLink = {
    agentId: string; parentToolUseId: string; parentAgentId?: string
    kind: AgentKind; label: string; type?: string; phase?: string
    status: AgentStatus; ts: number
  }
  export type Workflow = { toolUseId: string; transcriptDir: string; name: string; journalLine: number }
  export type AgentsState = {
    links: Map<string, AgentLink>
    workflows: Map<string, Workflow>                 // by runId
    held: Map<string, { events: ChatEvent[]; ticks: number }>  // unlinked agentId → held events
    ignored: Set<string>                             // gave up linking
  }
  export const HOLD_EVENTS = 200
  export const HOLD_TICKS = 3
  export const AGENT_ID: RegExp  // /^[a-z0-9]{1,64}$/
  export const RUN_ID: RegExp    // /^wf_[a-z0-9-]{1,64}$/
  export function newAgentsState(): AgentsState
  export function resetAgents(a: AgentsState): void
  // Records a spawn. Returns the `agent` control to queue, or null.
  export function linkSpawn(a: AgentsState, e: { tool_use_id: string; description: string; subagentType: string; parentAgentId?: string }, agentId: string | undefined, now: number): AgentControl | null
  export function recordWorkflow(a: AgentsState, toolUseId: string, result: unknown): void   // needs runId (RUN_ID) + transcriptDir
  // Parses journal lines (only `started` lines with a valid agentId link). Returns the controls for newly linked agents.
  export function linkJournal(a: AgentsState, runId: string, lines: string[], now: number): AgentControl[]
  // The events of a linked agent, tagged; holds an unlinked one's; drops an ignored one's.
  export function routeAgentEvents(a: AgentsState, agentId: string, events: ChatEvent[]): ChatEvent[]  // returns tagged events to queue (possibly empty)
  // Called once per tick after journal reads: releases held events of agents now linked (tagged, in arrival order), ages the rest, moves any past HOLD_TICKS into `ignored`.
  export function releaseHeld(a: AgentsState): ChatEvent[]
  export function setStatus(a: AgentsState, agentId: string, status: AgentStatus, now: number): AgentControl | null   // null when unknown or unchanged
  export type AgentControl = { type: 'agent'; agent: AgentLink }
  ```
  Tagged event = the normalized `ChatEvent` plus `agentId: string`. `ChatEvent` in `normalize.ts` gains optional `agentId?: string` on every variant.
- `normalize.ts`: `shouldForward` no longer checks `agentId`. The other conditions are unchanged.
- `register.ts`:
  - `State` gains `agents: AgentsState`.
  - `queueAppended`: with `e.agentId`, the normalized events go through `routeAgentEvents`. Images are still queued as today. A row from a linked agent whose status is `done`/`failed` sets it back to `running` (`setStatus`) and queues that control.
  - New hook `on('agent.spawn', async ($, e, next) => { const r = await next(e); …linkSpawn…; return r })`. It queues the control into `s.pending`, and does nothing when `!s.paneId`.
  - New hook `on('tool.call', { tool: 'Workflow' }, async ($, e, next) => { const r = await next(e); recordWorkflow(...); return r })`.
  - The `turn.complete` hook: with `e.agentId`, call `setStatus(..., e.isAborted ? 'failed' : 'done')` and queue it. The main idle logic is unchanged.
  - `tick`: before `takeBody`, when `held` is non-empty and not offline/building, for each workflow read new journal lines with a top-level `async function readJournal($, wf): Promise<string[]>`. It uses `$.process.run(['tail', '-n', `+${wf.journalLine}`, '--', `${wf.transcriptDir}/journal.jsonl`])` and advances `journalLine` by the complete lines read; use `splitPiece` for a cut output. Then call `linkJournal`, then `releaseHeld`, and append the controls and released events to `s.pending`. A failed read is skipped silently.
  - `session.end` with `clear`/`resume` calls `resetAgents`.

**Ordering rule:** an agent's `agent` control is queued before its first released or routed events.

**Steps:**
- [ ] Write failing tests in `agents.test.ts` (pure):
  - (a) `linkSpawn` with an agentId returns `{type:'agent', agent:{agentId:'aa1', parentToolUseId:'toolu_1', kind:'subagent', label:'Background echo test', type:'general-purpose', status:'running', ts}}`. Without an agentId it returns null and links nothing. With a non-matching id (`'../x'`) it returns null.
  - (b) `recordWorkflow` accepts the spike's Workflow result (`{status:'async_launched', taskId:'wfu18ne1l', runId:'wf_4ddcdf59-066', workflowName:'tiny-two-agents', transcriptDir:'/p/subagents/workflows/wf_4ddcdf59-066'}`) and ignores one with `runId:'../evil'` or no `transcriptDir`.
  - (c) `linkJournal` over the spike's journal lines (`{"type":"launched"}`, two `started` lines with agentIds `a3ffd04f6c0bcfe58`/`a3fbe2c38f901f28e`, labels and `phase:"Reply"`, two `result` lines, one malformed line) links both as `kind:'workflow'` with `parentToolUseId` = the workflow's tool use id, `label`, `phase`. It returns two controls, and a second call with the same lines returns none.
  - (d) `routeAgentEvents` for a linked id returns the events with `agentId` added. For an unknown id it returns `[]` and holds them; 250 held events keep the newest 200. For an ignored id it returns `[]` and holds nothing.
  - (e) `releaseHeld` after linking returns the held events tagged, in order. An id unlinked across 3 `releaseHeld` calls is moved to `ignored` and its events are freed.
  - (f) `setStatus` returns null for an unchanged status and a control on change.
  - (g) `resetAgents` empties everything.
- [ ] Write failing tests in `normalize.test.ts`: `shouldForward` accepts `{door:'response', agentId:'aa1', message:{role:'assistant'}}` and still rejects `door:'attachment'` and `isMeta` rows that carry an `agentId`.
- [ ] Write failing tests in `register.test.ts`:
  - Replace the old "subagent rows are dropped" test with: a subagent row from a spawn-linked agent is queued tagged with `agentId`; an attachment-door row with an `agentId` is still dropped.
  - Driving `$.agent.spawn(...)` through the kit (mock `agent.spawn` beneath to answer `{ model:'haiku', agentId:'aa1' }`) queues the `agent` control. If the kit can't drive `agent.spawn` end-to-end, export the handler body as `onSpawned(s, e, r)` and unit-test it, as `queueAppended` is tested.
  - A workflow: the Workflow `tool.call` result is recorded; a row from an unknown agent is held; the next tick's fake `tail` on `<transcriptDir>/journal.jsonl` returns the journal; the following sync body has the `agent` control and then the tagged held event. Extend the `world()` fake `tail` to serve that path from `files`.
  - `turn.complete` with `agentId` and `isAborted:false` queues status `done`; with `isAborted:true`, `failed`. The main `state` events are unchanged.
  - `/clear` resets agents: a later row from the old agent is held, not routed.
- [ ] Run the mod suite: the new tests FAIL.
- [ ] Implement as specified.
- [ ] Run the mod suite (test + validate + tsc): all green.
- [ ] Commit: `feat(mod): link subagents and workflow agents to their calls and forward their rows`.

### Task 2: Mod — background tasks

**Files:** Create `mod/herdr-chat/hooks/tasks.ts` (pure). Modify `register.ts`, `normalize.ts`. Tests: `mod/herdr-chat/tests/tasks.test.ts` (new), `normalize.test.ts`, `register.test.ts`.

**Interfaces (produced):**
- `tasks.ts`:
  ```ts
  export type TaskKind = 'shell' | 'subagent' | 'workflow' | 'monitor'
  export type TaskStatus = 'running' | 'done' | 'failed'
  export type Task = { id: string; kind: TaskKind; label: string; toolUseId: string; status: TaskStatus; startedAt: number; endedAt?: number }
  export const TASK_TTL_MS = 600_000
  export const LABEL_MAX = 120
  export type TasksState = Map<string, Task>
  // From a tool.call result. Returns true when the list changed.
  export function taskFromLaunch(t: TasksState, tool: string, toolUseId: string, input: Record<string, unknown>, result: unknown, now: number): boolean
  // From a parsed notice. Returns true when the list changed.
  export function taskFromNotice(t: TasksState, n: { taskId?: string; toolUseId?: string; status: string; summary: string }, now: number): boolean
  // Drops finished tasks older than TASK_TTL_MS. Returns true when any were dropped.
  export function expireTasks(t: TasksState, now: number): boolean
  export function tasksControl(t: TasksState): { type: 'tasks'; tasks: Task[] }  // running first by startedAt, then finished by endedAt desc
  ```
  Launch rules:
  - `Bash` with `result.backgroundTaskId` → shell, labeled with `input.command` (one line, clipped to `LABEL_MAX`).
  - `Agent` with `result.status === 'async_launched'` and `result.agentId` → subagent, labeled with `input.description`.
  - `Workflow` with `result.taskId` → workflow, labeled with `result.workflowName || result.summary`.
  - `Monitor` with `result.taskId` → monitor, labeled with `input.description || input.command`.
  - Anything else changes nothing. The task `id` is the task id from the result.
- Notice rules:
  - Match by `taskId`, else by `toolUseId` against `task.toolUseId`.
  - Set the status (per Global Constraints) and `endedAt = now`.
  - An unknown notice adds `{ id: taskId ?? toolUseId ?? 'notice-'+now, kind: 'subagent' when the summary starts with `Agent "`, 'workflow' when it starts with `Workflow`, else 'shell', label: summary, status, startedAt: now, endedAt: now }`.
  - A repeat notice for the same id updates it in place (no duplicate).
- `normalize.ts`: the `task_notice` event gains optional `taskId?: string` and `toolUseId?: string`, parsed from `<task-id>`/`<tool-use-id>` (omitted when empty). Existing fields are unchanged.
- `register.ts`:
  - `State` gains `tasks: TasksState`.
  - Hooks `on('tool.call', { tool: 'Bash' | 'Agent' | 'Monitor' }, …)` (one hook per tool, each `await next(e)` then `taskFromLaunch`). The Workflow hook from Task 1 also calls `taskFromLaunch`.
  - `queueAppended` (main rows only) feeds each produced `task_notice` event to `taskFromNotice`. A subagent notice also calls `setStatus` on that agent (`agents.ts`) when its `taskId` is a linked agent id.
  - `tick` calls `expireTasks` before `takeBody`.
  - Whenever any of these return true, a fresh `tasksControl` is appended to `s.pending`. Queued earlier `tasks` controls still in `pending` are removed first, so only the newest goes out.
  - `/clear` and `/resume` clear tasks and queue an empty `tasks` control.

**Steps:**
- [ ] Write failing tests in `tasks.test.ts`, using the four launch results from the spike verbatim (Bash `backgroundTaskId:'b4prbe90d'`, Agent `async_launched`/`agentId:'a0ab069d9186e35de'`, Workflow `taskId:'wfu18ne1l'`, Monitor `{taskId:'m1', timeoutMs:300000}`). Assert:
  - the kinds, labels and running status
  - a foreground Agent result (`status:'completed'`) adds nothing
  - a 300-character command is clipped to 120 with `…`
  - a notice matched by taskId and one matched only by toolUseId both set `done`
  - `failed`/`killed` set `failed`
  - a repeat notice updates in place (size stays 1)
  - an unknown notice adds a finished entry
  - `expireTasks` at `endedAt + 600_000` drops it, and keeps it at `endedAt + 599_999`
  - `tasksControl` ordering
- [ ] Write failing tests in `normalize.test.ts`: the spike's notice text yields `task_notice` with `taskId:'a0ab069d9186e35de'`, `toolUseId:'toolu_01AHLnnYchSVz4oxU8iSiip3'`, `status:'completed'`, `summary:'Agent "Background echo test" finished'`.
- [ ] Write failing tests in `register.test.ts`:
  - a background Bash `tool.call` (mock `tool.call` beneath answering the spike's result) puts a `tasks` control with one running shell in the next sync
  - a later notice row puts one with it done
  - two changes within one tick send a single `tasks` control
  - `/clear` sends `{type:'tasks', tasks:[]}`
- [ ] Run: FAIL. Implement. Run the mod suite: green.
- [ ] Commit: `feat(mod): track background tasks from launches and notices`.

### Task 3: Mod — resync agents and threads from the session's files

**Files:** Modify `register.ts`, `transcript.ts`, `agents.ts`. Tests: `register.test.ts`, `transcript.test.ts`.

**Interfaces (produced):**
- `transcript.ts`: `addTranscriptLine(h, line, opts?: { sidechain?: boolean })`. With `sidechain: true` the `isSidechain` filter is skipped; everything else is unchanged. `TranscriptHistory` gains `workflowRuns: Map<string, string>` (runId → Workflow tool use id), and `finishTranscriptHistory` returns it beside the events. `readTranscript($, path, opts?)` in `register.ts` passes it through.
- `agents.ts`: `linkFromMeta(a, agentId, metaJson: string, now): boolean`. It parses `{ toolUseId, description, agentType }`, links `kind:'subagent'` with status `done`, and returns false on bad JSON, a missing toolUseId or a bad id. `linkJournal` (Task 1) marks agents with a `result` line `done`.
- `register.ts`, `buildHistory` after the main snapshot is built:
  1. `sessionDir = path.replace(/\.jsonl$/, '')` (only when the main history came from a transcript path).
  2. `$.process.run(['find', `${sessionDir}/subagents`, '-maxdepth', '1', '-name', 'agent-*.meta.json'])` → for each file whose id (between `agent-` and `.meta.json`) matches `AGENT_ID`, `tail -n +1` it and `linkFromMeta`.
  3. Workflows: the transcript reader collects `h.workflowRuns: Map<runId, toolUseId>` from tool_result rows whose `toolUseResult` is `{ taskType: 'local_workflow', runId }` (`runId` must match `RUN_ID`; the tool use id is the row's `tool_result` block's `tool_use_id`). Verified in the spike transcript: the row carries `toolUseResult.{taskId, runId, taskType:'local_workflow', transcriptDir}`. For each run, read `${sessionDir}/subagents/workflows/${runId}/journal.jsonl` and `linkJournal` its agents to that tool use. The path is built from `sessionDir` and the validated runId; the row's own `transcriptDir` is never used. Record each run in `agents.workflows` too, so a still-running workflow's later agents link live.
  4. Agents to replay: the linked ids, ordered by their transcript file's modification time, newest 20. Use `find <dir> -maxdepth 1 -name 'agent-*.jsonl' -printf '%T@ %p\n'` for both `subagents/` and each run dir.
  5. For each, `readTranscript($, file, { sidechain: true })` → per-thread `snapshot_begin {total, agentId}`, `snapshot_chunk {events, agentId}` (the same `chunkEvents`), `snapshot_end {agentId}`. Each event is tagged with `agentId`. Images merge into the history image set under the same newest-30 rule.
  6. Status: `$.agent.list()` (`running`/`waiting`/`pending` → running, `completed` → done, `failed`/`killed` → failed). Otherwise keep the file-derived status.
  7. Queue the `agent` controls (all of them, before any thread snapshot), then the thread snapshots, then `tasksControl` with subagents that `$.agent.list()` says are running re-added as running tasks (`kind:'subagent'`, label = description, `toolUseId` from the link).
  - Every read failure skips that agent or run. The main snapshot never fails because of agent files.

**Steps:**
- [ ] Write failing tests in `transcript.test.ts`: a sidechain row is dropped by default and kept with `{ sidechain: true }`.
- [ ] Write failing tests in `register.test.ts` with a fixture tree in `files` (the main transcript `/c/projects/p/sess-1.jsonl` holding an `Agent` tool_use `toolu_A`, and a `Workflow` tool_use `toolu_W` whose tool_result row carries `toolUseResult:{taskType:'local_workflow', runId:'wf_r1'}`; `/c/projects/p/sess-1/subagents/agent-aa1.meta.json` + `agent-aa1.jsonl` (sidechain rows); `/c/projects/p/sess-1/subagents/workflows/wf_r1/journal.jsonl` + `agent-bb2.jsonl`). Extend the fake `find` to the new argument shapes (`-name` patterns with `*`, `-type d`, `-printf '%T@ %p\n'` printing a fixed mtime order). Assert:
  - the resync sends `agent` controls for `aa1` (parent `toolu_A`) and `bb2` (parent `toolu_W`, kind workflow) before their thread snapshots
  - each thread snapshot's events carry `agentId` and come from the sidechain file
  - a meta file named `agent-../../x.meta.json` is never read
  - 25 agents replay only the newest 20
  - a `find` that exits 1 still delivers the main snapshot
- [ ] Write a failing test in `transcript.test.ts`: a tool_result row shaped like the spike's (`toolUseResult: {taskId:'wfu18ne1l', runId:'wf_4ddcdf59-066', taskType:'local_workflow', transcriptDir:'…'}`, block `tool_use_id:'toolu_W'`) yields `workflowRuns.get('wf_4ddcdf59-066') === 'toolu_W'`; a row with `runId:'../x'` yields nothing.
- [ ] Run: FAIL. Implement. Run the mod suite: green.
- [ ] Commit: `feat(mod): rebuild subagent and workflow threads on resync`.

### Task 4: Companion — threads, agent summaries and tasks in chatbridge

**Files:** Modify `companion/internal/chatbridge/hub.go`, `server.go`. Test: `companion/internal/chatbridge/v2_test.go` (new). Also update spec §1.2/§1.3 in `docs/superpowers/specs/2026-10-05-herdr-mobile-chat-v2-agents-tasks-design.md` per the refinement at the top of this plan.

**Interfaces (produced):**
- Constants `ThreadCap = 1000`, `ThreadsMax = 20`.
- `type thread struct { events []Entry; seq int; subs map[int]chan Update; staging []json.RawMessage; lastActive time.Time; activity *Activity }`.
- `pane` gains `threads map[string]*thread`, `agents map[string]json.RawMessage` (the merged summary object per agentId) and `tasks json.RawMessage` (nil = none).
- `apply`:
  - A chat event whose JSON has a non-empty `agentId` goes to `p.thread(agentID)` (created on demand, `ThreadsMax` eviction below). It never goes to the main ring, the activity, or `track`. Its `activityOf` result becomes `thread.activity`. If that agent has a summary, the summary is re-merged and an `Update{Kind:"agent"}` is fanned to main subscribers when it changed.
  - `snapshot_begin`/`snapshot_chunk`/`snapshot_end` with `agentId` stage and swap that thread (seqs from 1, the newest `ThreadCap`) and fan `Update{Kind:"snapshot", AgentID}` to its subscribers.
  - `{type:"agent", agent:{…}}`: the object must have a non-empty `agentId` (else ignored). The merged summary = the mod's object with `activity` set to `thread.activity` when known. Store it in `p.agents[agentID]` and fan `Update{Kind:"agent", Agent: raw}` if the bytes changed.
  - `{type:"tasks", tasks:[…]}`: `tasks` must be a JSON array (else ignored). Store it and fan `Update{Kind:"tasks", Tasks: raw}` if changed. Count `"status":"running"` elements into `p.bgRunning`.
- Eviction: creating thread #21 evicts the thread with the oldest `lastActive` whose summary status isn't `running` (else the oldest overall). Its subscribers get closed channels, and its summary is deleted with an `Update{Kind:"agent_removed", AgentID}` fanned to main subscribers.
- `swap` (main resync, new epoch) clears `threads` (closing their subscribers), `agents` and `tasks`, and resets `bgRunning`.
- `Update` gains `AgentID string`, `Agent json.RawMessage`, `Tasks json.RawMessage`.
- `Snapshot` gains `AgentID string`, `Missing bool`, `Agents []json.RawMessage` (main only, sorted by the object's `ts` ascending) and `Tasks json.RawMessage` (main only).
- `Subscribe(paneID, agentID string)` replaces `Subscribe(paneID)`. Update all callers and the `wsserver.ChatHub` interface in Task 5; keep this task compiling by updating the wsserver call to pass `""`. With an agentID: the thread's snapshot (newest `SnapshotTail`, `HasMore`), or `Missing: true` with a channel that receives nothing until `cancel`.
- `History(paneID, agentID string, epoch, beforeSeq, limit int)` does the same per thread; `ok` is false for an unknown thread.
- `Summary` gains `BgRunning int`, included in `sameSummary` and delivered through `onSummary`.

**Steps:**
- [ ] Write failing tests in `v2_test.go`:
  - (a) A thread event (`{"type":"tool_use","agentId":"aa1",…}`) is not in the main snapshot and doesn't change `Summary.Activity`. `Subscribe(p,"aa1")` returns it at seq 1.
  - (b) 1500 thread events keep the newest 1000 and leave the main ring empty.
  - (c) The `agent` control is stored and appears in the main snapshot's `Agents`, with `activity` filled from the thread's last tool_use. A later thread event changes the activity and fans one `agent` update; an identical control fans none.
  - (d) The `tasks` control: its array is in the snapshot `Tasks`, `BgRunning` counts the running entries, `onSummary` fires with `BgRunning:2`, and a non-array `tasks` is ignored.
  - (e) The 21st thread evicts the oldest non-running one: its subscriber channel closes and an `agent_removed` update fans.
  - (f) A per-thread chunked snapshot swaps only that thread.
  - (g) A main `snapshot_end` (new epoch) clears threads, agents and tasks, and closes thread subscribers.
  - (h) `Subscribe(p,"zz9")` → `Missing:true`. `History(p,"zz9",…)` → `ok:false`. Thread `History` pages correctly.
  - (i) A thread event carrying images still stores the images in the pane LRU.
- [ ] Run `go -C companion test ./internal/chatbridge/`: FAIL.
- [ ] Implement. Update `server.go` only if `/sync` parsing needs it (it shouldn't: events stay raw).
- [ ] Update the spec wording (refinement).
- [ ] Run vet, `test -race` and gofmt: green.
- [ ] Commit: `feat(companion): per-agent chat threads, agent summaries and background tasks`.

### Task 5: Companion — protocol 10 on the WebSocket and the dashboard badge

**Files:** Modify `companion/internal/proto/proto.go`, `companion/internal/wsserver/server.go`, `companion/internal/state/store.go`, `companion/internal/engine/engine.go`. Tests: `proto_test.go`, `wsserver/server_test.go`, `state/store_test.go`.

**Interfaces (produced):**
- `proto.ClientMsg` gains `AgentID string `json:"agentId"``.
- `welcome` reports `companionProtocol: 10`.
- `ChatSnapshot(s)` adds `"agentId"` when non-empty, `"missing": true` when set, and `"agents"` (always an array, possibly empty) and `"tasks"` (array or omitted when nil) on main snapshots (`AgentID == ""`).
- `ChatEvent(paneID, agentID, epoch, e)` adds `"agentId"` when non-empty.
- New `ChatAgent(paneID string, agent json.RawMessage)` → `{"t":"chat_agent","paneId","agent"}`.
- New `ChatAgentRemoved(paneID, agentID)` → `{"t":"chat_agent","paneId","agentId","removed":true}`.
- New `ChatTasks(paneID, tasks)` → `{"t":"chat_tasks","paneId","tasks"}`.
- `ChatHistoryPage` gains `agentID` (echoed when non-empty).
- `wsserver`:
  - `ChatHub.Subscribe(paneID, agentID string)` and `History(paneID, agentID string, epoch, beforeSeq, limit int)`.
  - `client.chats` is keyed by `paneID + "\x00" + agentID`. `openChat` keeps at most one thread subscription per pane per client: opening a thread closes that pane's previous thread subscription, never the main one.
  - The forwarder maps `agent` → `ChatAgent`, `agent_removed` → `ChatAgentRemoved`, `tasks` → `ChatTasks`, and `snapshot`/`event` with the agentId.
  - `chat_close` with `agentId` closes only that thread.
- `state.Pane` gains `BgRunning int `json:"bgRunning,omitempty"``. `Store.SetSummary(paneID, activity, ask, bgRunning int)` compares and stores it like the others. `engine.go` passes `s.BgRunning`.

**Steps:**
- [ ] Write failing tests:
  - proto: the shapes of the frames above (main snapshot with empty `agents` is `[]`; thread snapshot has `agentId` and no `agents`/`tasks` keys; missing; chat_agent; chat_tasks; history page echo).
  - wsserver (with the existing fake hub, extended to the new signatures):
    - `chat_open {paneId}` gets a main snapshot, then `chat_agent`/`chat_tasks` frames as the hub fans them
    - `chat_open {paneId, agentId:'aa1'}` gets a thread snapshot and only that thread's events
    - opening a second thread closes the first (its updates stop) while main updates continue
    - `chat_close {paneId, agentId}` leaves main open
    - a protocol-9-style client (never sends agentId) sees no `agentId` keys in snapshot/event frames for main traffic
    - `welcome` says 10
  - state: `SetSummary` with bgRunning 2 reports a change, and the same value again reports none; the JSON omits `bgRunning` when 0.
- [ ] Run: FAIL. Implement. Run vet, `test -race` and gofmt: green.
- [ ] Commit: `feat(companion): protocol 10 — thread subscriptions, chat_agent, chat_tasks, bgRunning`.

### Task 6: App — protocol, chat state, thread-aware repository

**Files:** Modify `app/app/src/main/java/dev/herdr/mobile/net/Protocol.kt`, `data/ChatModel.kt`, `data/ChatRepository.kt`, `ui/DashboardViewModel.kt`. Tests: `ProtocolTest.kt`, `ChatReducerTest.kt`, `ChatRepositoryTest.kt`.

**Interfaces (produced):**
- `Protocol.kt`:
  ```kotlin
  data class AgentSummary(val agentId: String, val parentToolUseId: String, val parentAgentId: String? = null,
      val kind: String, val label: String, val type: String? = null, val phase: String? = null,
      val status: String, val activity: PaneActivity? = null, val ts: Long = 0)
  data class BgTask(val id: String, val kind: String, val label: String, val toolUseId: String,
      val status: String, val startedAt: Long, val endedAt: Long? = null)
  ```
  - `Pane` gains `bgRunning: Int = 0`.
  - `ServerFrame.ChatSnapshot` gains `agentId: String? = null`, `missing: Boolean = false`, `agents: List<AgentSummary>? = null`, `tasks: List<BgTask>? = null`. `null` = not sent; it stays distinct from empty.
  - `ChatEventFrame` and `ChatHistoryPage` gain `agentId: String? = null`.
  - New `ServerFrame.ChatAgent(paneId, agent: AgentSummary?, removedId: String?)` and `ServerFrame.ChatTasks(paneId, tasks: List<BgTask>)`.
  - `ClientMsg.chatOpen(paneId, agentId: String? = null)`, `chatClose(paneId, agentId: String? = null)` and `chatHistory(reqId, paneId, epoch, beforeSeq, limit, agentId: String? = null)` add `agentId` only when non-null.
  - A malformed agent/task element is skipped.
- `ChatModel.kt`: `ChatView` gains `agents: Map<String, AgentSummary> = emptyMap()`, `tasks: List<BgTask> = emptyList()`, `missing: Boolean = false`. `ChatReducer.onFrame`:
  - a snapshot with non-null `agents`/`tasks` replaces them, and `missing` is copied
  - `ChatAgent` upserts by `agentId` or removes `removedId`
  - `ChatTasks` replaces `tasks`
  - everything else is unchanged
- `ChatRepository.kt`:
  - `data class ChatKey(val paneId: String, val agentId: String? = null)`
  - `view(key: ChatKey)`, `open(key)`, `close(key)` and `loadOlder(key)` take a key, and the existing `paneId`-only calls become `ChatKey(paneId)`
  - `onFrame` routes by `ChatKey(f.paneId, f.agentId)`. `ChatAgent`/`ChatTasks` go to `ChatKey(paneId)`
  - gap re-open sends `chatOpen(paneId, agentId)` for the gapped key when it's opened
  - `onReconnected` re-opens every opened key
  - `send`/`retry`/`answer` stay main-only (`ChatKey(paneId)`)
  - images are unchanged
- `DashboardViewModel`: `chatView(paneId, agentId: String? = null)`, `openChat(paneId, agentId = null)` (`lastOpenedPaneId` set only when `agentId == null`), `closeChat(paneId, agentId = null)`, `loadOlderChat(paneId, agentId = null)`.

**Steps:**
- [ ] Write failing tests:
  - Protocol:
    - parse a main `chat_snapshot` with `agents` (one with activity) and `tasks`, and a thread snapshot with `agentId` and no agents (→ `agents == null`)
    - `missing:true`
    - `chat_agent` upsert and `removed`
    - `chat_tasks`
    - `chat_event` with agentId
    - `bgRunning` on a pane
    - a bad agent element skipped
    - `chatOpen("p")` has no `agentId` key and `chatOpen("p","aa1")` has it
  - Reducer:
    - the snapshot sets agents/tasks
    - a thread-style snapshot with null agents keeps existing ones
    - `ChatAgent` upsert/remove
    - `ChatTasks` replace
  - Repository:
    - frames with agentId update only that key's view
    - a gap in a thread re-opens it with agentId
    - `onReconnected` re-sends both open keys
    - `chat_agent` lands on the main view
- [ ] Run the app suite: FAIL. Implement. Run the suite and build: green.
- [ ] Commit: `feat(app): protocol 10 frames and thread-aware chat repository`.

### Task 7: App — agent cards in the timeline

**Files:** Modify `ui/ChatTimeline.kt`, `ui/ChatScreen.kt`. Create `ui/AgentCards.kt` (composables). Test: `ChatTimelineTest.kt`.

**Interfaces (produced):**
- `TimelineItem.AgentCard(override val key: String, val entry: ChatEntry, val call: ChatEvent.ToolUse, override val head: Boolean) : TimelineItem` with `speaker = Speaker.Agent`. The key is `"a:" + call.toolUseId`, so it is stable across epochs and pages.
- `buildTimeline(entries, epoch, pageStarts)`: a `tool_use` whose `tool` is `"Agent"` or `"Workflow"` flushes the current rail and becomes an `AgentCard`. The rail resumes after it. Everything else is unchanged.
- `fun agentsFor(call: ChatEvent.ToolUse, agents: Map<String, AgentSummary>): List<AgentSummary>`: agents whose `parentToolUseId == call.toolUseId`, ordered by `ts`.
- `fun workflowGroups(agents: List<AgentSummary>): List<Pair<String?, List<AgentSummary>>>`: grouped by `phase` in first-seen order.
- `const val WORKFLOW_ROWS = 6`.
- `AgentCards.kt`:
  - `@Composable fun SubagentCard(call, agent: AgentSummary?, now: Long, onOpen: (String) -> Unit)`:
    - the label (`agent.label`, else the call's summary)
    - type
    - status: running uses `StatusIndicator`'s pulsing working dot; done is `✓` in green; failed is `✗` in red, both in JetBrains Mono like the dashboard glyphs
    - the activity line via `activityLine(agent.activity)`, or `"starting…"` when there is no agent yet
    - elapsed: `staleLabel`-style duration from `call.ts` to now while running, or to `agent.ts` when done
    - tapping it calls `onOpen(agentId)`; there is no tap before the agent is known
  - `@Composable fun WorkflowCard(call, agents: List<AgentSummary>, now, onOpen)`:
    - the name (the call's summary)
    - the overall status: running if any is running, failed if any failed, else done
    - phase headings with compact rows (status glyph, label, activity line)
    - a "+N more" row after `WORKFLOW_ROWS` that expands in place (`rememberSaveable` per toolUseId)
- `ChatScreen`: render `AgentCard` items in a `GutterRow` with the speaker label like tool rails. It gets `view.agents` and an `onOpenThread: (agentId: String) -> Unit` parameter, wired in Task 8 (until then a no-op passed from `PaneScreen`).

**Steps:**
- [ ] Write failing tests in `ChatTimelineTest.kt`:
  - `[Read, Agent, Bash]` tool_uses → `Tools(Read)`, `AgentCard`, `Tools(Bash)`
  - the AgentCard key is `"a:toolu_A"` in epoch 1 and epoch 2
  - a Workflow tool_use becomes an AgentCard
  - an AskUserQuestion tool_use is still dropped
  - `agentsFor` filters by parent and sorts by ts
  - `workflowGroups` keeps first-seen phase order
  - a page seam before an Agent call doesn't change its key
- [ ] Run: FAIL. Implement. Run the suite and build: green.
- [ ] Commit: `feat(app): subagent and workflow cards in the chat timeline`.

### Task 8: App — thread screen and back stack

**Files:** Modify `ui/ChatScreen.kt`, `ui/PaneScreen.kt`. Create `ui/ThreadScreen.kt`. Create `ui/ThreadStack.kt` (pure). Test: `ThreadStackTest.kt` (new).

**Interfaces (produced):**
- `ThreadStack.kt`:
  ```kotlin
  data class ThreadStack(val ids: List<String> = emptyList()) {
      val top: String? get() = ids.lastOrNull()
      fun push(id: String) = if (top == id) this else copy(ids = ids + id)
      fun pop() = copy(ids = ids.dropLast(1))
  }
  ```
- `ChatScreen` refactor: extract the list body (lines that build `items`, `results`, `running`, the follow/entry-scroll/paging effects, and the `LazyColumn` with its pill overlay) into `@Composable fun ChatTimelineList(vm, paneId: String, agentId: String?, view: ChatView, agents: Map<String, AgentSummary>, agentLabel: String, listState: LazyListState, readOnly: Boolean, onOpenThread: (String) -> Unit, modifier: Modifier)`. `ChatScreen` keeps the header, banner, question sheet and composer, and calls it with `agentId = null, readOnly = false`. With `readOnly`, pending messages and question cards' answer controls aren't shown, and question events render as plain text rows.
- `PaneScreen`:
  - holds `var threads by remember(pane.paneId) { mutableStateOf(ThreadStack()) }` and the chat's `LazyListState` (hoisted with `rememberLazyListState()` here and passed to `ChatScreen`, so it survives the thread screen)
  - when `threads.top != null` it shows `ThreadScreen(vm, pane, agentId = threads.top, agents = mainView.agents, onOpen = { threads = threads.push(it) }, onBack = { threads = threads.pop() })` instead of the chat
  - `BackHandler(enabled = threads.top != null) { threads = threads.pop() }` sits above the dashboard's handler, so system back pops a thread before leaving the pane
- `ThreadScreen`:
  - `DisposableEffect(agentId) { vm.openChat(pane.paneId, agentId); onDispose { vm.closeChat(pane.paneId, agentId) } }`
  - `PaneHeader` with `onBack` = pop, the breadcrumb `"‹label› · ‹type›"` and the status from the summary, and no terminal toggle
  - body: `ChatTimelineList(readOnly = true, agentId = agentId, onOpenThread = onOpen)` with its own `rememberLazyListState()` keyed by agentId
  - `view.missing` → a centered "This thread is no longer available" plus a back `SecondaryButton`
- Leaving the pane (dashboard back) clears the stack (it's `remember`ed per pane).

**Steps:**
- [ ] Write failing tests in `ThreadStackTest.kt`: push/pop order; pushing the top again is a no-op; popping empty stays empty; `top` after `push(a)`, `push(b)`, `pop()` is `a`.
- [ ] Run: FAIL. Implement `ThreadStack`, then the refactor (no behavior change for the chat: run the full suite after the extraction, before adding `ThreadScreen`), then `ThreadScreen` and the `PaneScreen` wiring.
- [ ] Run the suite and build: green.
- [ ] Commit: `feat(app): read-only subagent thread screen with a back stack`.

### Task 9: App — tasks strip, tasks sheet, dashboard badge

**Files:** Create `ui/TasksStrip.kt`. Modify `ui/ChatScreen.kt`, `ui/DashboardModel.kt`, `ui/DashboardScreen.kt` (or whichever composable renders the pane rows and `NeedsYouCard`). Test: `DashboardModelTest.kt`, `TasksModelTest.kt` (new).

**Interfaces (produced):**
- Pure, in `TasksStrip.kt`:
  ```kotlin
  data class TaskCounts(val running: Int, val done: Int, val failed: Int)
  fun taskCounts(tasks: List<BgTask>): TaskCounts
  fun stripLabel(c: TaskCounts): String?   // "⟳ 2 running · 1 done · 1 failed", omitting zero parts; null when all zero
  fun taskDuration(t: BgTask, now: Long): String   // "4s", "2m 05s", "1h 03m"; running counts to now
  fun taskGlyph(kind: String): String   // shell "$", subagent "◆", workflow "⧉", monitor "◉", else "•"
  fun bgBadge(p: Pane): String?   // "⟳ N" when p.bgRunning > 0, else null
  ```
- `@Composable fun TasksStrip(tasks: List<BgTask>, onClick: () -> Unit)`: one line under the header, only when `stripLabel` is non-null.
- `@Composable fun TasksSheet(tasks, now, onPick: (BgTask) -> Unit, onDismiss)`: a `ModalBottomSheet` of rows (glyph, label, status, duration ticking every second while any is running). `onPick` for kind `subagent`/`workflow` scrolls the chat list to the `AgentCard` whose call's `toolUseId == task.toolUseId` (index via `items.indexOfFirst`) and dismisses. Shell and monitor rows aren't clickable.
- `ChatScreen` shows `TasksStrip(view.tasks)` between `PaneHeader` and the banner.
- Dashboard rows (`paneGroup` rows and `NeedsYouCard`) show `bgBadge(pane)` as a small meta-style chip after the activity line.

**Steps:**
- [ ] Write failing tests in `TasksModelTest.kt`:
  - `taskCounts`
  - `stripLabel` for (2,1,0) → "⟳ 2 running · 1 done", for (0,0,1) → "⟳ 1 failed", and for zeros → null
  - `taskDuration` for 4 000 ms → "4s", 125 000 → "2m 05s", 3 780 000 → "1h 03m", and a running task counted to `now`
  - `taskGlyph` mapping
- [ ] Write failing tests in `DashboardModelTest.kt`: `bgBadge` of a pane with `bgRunning = 2` → "⟳ 2", and with 0 → null.
- [ ] Run: FAIL. Implement. Run the suite and build: green.
- [ ] Commit: `feat(app): background tasks strip and sheet, dashboard badge`.

### Task 10: Docs, rollout, live verification

**Files:** Modify `README.md` (protocol note: 10 replaces the chat view v1.1 protocol line; mention subagent threads and the tasks strip) and `CHANGELOG.md` (an Unreleased entry).

**Steps:**
- [ ] Update README and CHANGELOG. Commit: `docs: chat view v2 — subagent threads, workflows, background tasks`.
- [ ] Live on the emulator (`scripts/dev-emulator.sh` steps run by hand, with a **test companion on another port**; never kill the user's production `herdr-mobiled`):
  1. In a herdr pane, run a Claude session with the worktree's mod via `--plugin-dir mod/herdr-chat` and `--debug-file`. Check the debug file shows the module loaded (no `$` static-check error).
  2. Prompt it with the spike's scenario: a foreground subagent, a background subagent, a background `sleep 20` Bash, and a two-agent workflow.
  3. Verify in the app:
     - an agent card per subagent with a live activity line
     - the workflow card's two rows under "Reply"
     - tapping a card opens its thread (rows match the subagent's work)
     - back returns to the chat at the same scroll position
     - the strip shows running counts then done
     - the sheet's durations tick
     - the dashboard row shows "⟳ N" while tasks run
     - restarting the test companion mid-run recovers the cards and threads (the resync path)
- [ ] Report the results to the user with what was and wasn't verified. Then, on approval: install on the phone, rebuild and restart `~/.local/bin/herdr-mobiled`, and have the user run `/reload-plugins` in their Claude panes.
