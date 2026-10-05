# herdr-mobile — Chat View v2: subagent threads, workflow grouping, background tasks — Design Spec

**Date:** 2026-10-05
**Builds on:** `2026-10-04-herdr-mobile-chat-view-design.md` (v1) and `2026-10-04-herdr-mobile-chat-view-v1.1-design.md` (v1.1). Everything there still holds unless this document changes it.
**Components:** `mod/herdr-chat`, companion (`chatbridge`, `proto`, `wsserver`, pane state), app.
**Protocol:** `companionProtocol` 9 → 10 (additive).

## Goal

Three of the v1 spec's "v2 candidates". Permission approvals stay out.

1. **Subagent threads.** You can see at a glance that a subagent is alive and what it's doing, from a card under the `Agent` call that started it. Tapping the card opens its whole thread.
2. **Workflow grouping.** A `Workflow` call's agents show as rows on its card, grouped by phase. Each row opens that agent's thread.
3. **Background tasks.** A strip in the chat shows which background shells, subagents, workflows and monitors are running or done, with durations. The dashboard row gets a "⟳ N" badge while any are running.

Success means a pane running subagents and a workflow stays readable on the phone. The main chat carries one compact card per agent, and the details are one tap away.

## Spike findings (2026-10-05, Claude Code 2.1.289, `claude -p` with a logging mod)

| Question | Finding |
|---|---|
| Subagent → its Agent call | `agent.spawn` fires for model-called Agents: `e.tool_use_id` is the parent's Agent tool use, and `next(e)` resolves `{ model, agentId }`. Every subagent row arrives through `session.append` with `agentId` set. On disk, `<sessionDir>/subagents/agent-<id>.meta.json` has `{ agentType, description, toolUseId }` and `agent-<id>.jsonl` holds its rows (`isSidechain: true`). |
| Workflow → its Workflow call | Workflow agents go through no `agent.spawn` and are absent from `$.agent.list()`, but their rows still arrive through `session.append` with `agentId` set. The first row's origin is `{ kind: "coordinator" }`. The `Workflow` tool result has `{ status: "async_launched", taskId, runId, workflowName, summary, transcriptDir }`. `<transcriptDir>/journal.jsonl` lines are `{type:"started", agentId, label, phase}` and `{type:"result", agentId, result}`. Each agent's rows are in `<transcriptDir>/agent-<id>.jsonl`. |
| Background task ids | Bash with `run_in_background`: result `backgroundTaskId`. Agent with `run_in_background`: result `{ isAsync: true, status: "async_launched", agentId }` (task id = agentId). Workflow: result `taskId`. |
| Background task completion | A `session.append` row with door `delivery`, origin `{ kind: "task-notification" }`, whose text holds `<task-notification>` with `<task-id>`, `<tool-use-id>`, `<status>`, `<summary>`. A resumed agent can notify again under the same task id. |
| `classic.Stop` `background_tasks` | Never runs for a plugin (`cc-plugin-sec-default` bypasses `classic.*`). Not usable. |
| `$.agent.list()` | Live status for subagents: `running` → `completed`. |

Noise: a subagent's or workflow agent's conversation also gets engine `attachment` rows (environment, skill listing, deferred tools) and `isMeta` rows. The existing `shouldForward` filter (roles user/assistant, doors prompt/response/tool-result/delivery, no `isMeta`) must drop them. Tests pin this.

## 1. Mod (`mod/herdr-chat`)

### 1.1 Linking agents to their parent card

State gains:

- `agents: Map<agentId, AgentLink>`, where `AgentLink = { parentToolUseId, kind: "subagent" | "workflow", label, type?, phase?, parentAgentId?, status, activity?, ts }`.
- `workflows: Map<runId, { toolUseId, transcriptDir, name }>` for workflows not yet finished.
- `unlinked: Set<agentId>`: ids seen in rows but not yet linked.

Sources:

- **Subagent.** An `agent.spawn` hook (`await next(e)`, then record) maps the result's `agentId` → `{ parentToolUseId: e.tool_use_id, kind: "subagent", label: e.description, type: e.subagentType, parentAgentId: e.parentAgentId }`. A denied or failed spawn (no `agentId`) records nothing.
- **Workflow.** A `tool.call` hook on `Workflow` reads the result. When it has `runId` and `transcriptDir`, it records `workflows[runId]`.
- **Workflow agent.** When a forwardable row arrives with an `agentId` not in `agents`, the row is held and the id goes into `unlinked`. On the next tick, before building the body, the mod reads `journal.jsonl` of each workflow in `workflows` (`$.process.run(['tail', '-n', '+<next line>', '--', path])`, remembering how many lines were already read, so each read only gets new lines) and links every `started` line: `{ parentToolUseId: workflow.toolUseId, kind: "workflow", label, phase }`. Held rows for newly linked ids are then routed (1.2). An id still unlinked after 3 ticks has its held rows dropped and is ignored from then on. This covers forks, compaction agents and anything else unrelated. At most one journal read per running workflow per tick, and only while `unlinked` is non-empty.
- A workflow leaves `workflows` when its task notice arrives (1.4). Its agents stay in `agents`.

### 1.2 Routing rows

`shouldForward` stops rejecting rows with an `agentId`. `queueAppended`:

- **No `agentId`:** unchanged (main stream).
- **Linked `agentId`:** the normalized events are queued with `agentId` added to each event. Then the agent's summary updates:
  - `activity` follows the same rules as the companion's dashboard `activityOf` (tool line, or the first text line).
  - `ts` = now.
  - If the summary changed, an `agent` control is queued.
- **Unlinked `agentId`:** held per 1.1, with a cap of 200 events per id. Older held events drop first.

Status:

- A `turn.complete` hook with `e.agentId` linked → `status: "done"`, or `"failed"` when `e.isAborted`.
- A task notice for that agent (1.4) sets the status from `<status>`: `completed` → `done`, `failed`/`killed` → `failed`.
- A new row from a `done` agent (a resumed agent) → `running`.

The main `turn.complete` handling (pane state idle) still ignores events that have an `agentId`, as today.

Nested subagents: `parentAgentId` is kept. The parent card for a nested agent's `parentToolUseId` lives in the parent agent's thread, so the app finds it there.

### 1.3 Outgoing controls (`/sync` body)

- `{ type: "agent", agent: { agentId, parentToolUseId, parentAgentId?, kind, label, type?, phase?, status, activity?, ts } }`: the whole summary, sent each time it changes.
- `{ type: "tasks", tasks: Task[] }`: the whole list, sent each time it changes.
- Chat events may carry `agentId`.
- Per-thread resync: `{ type: "snapshot_begin", total, agentId }`, `{ type: "snapshot_chunk", events, agentId }`, `{ type: "snapshot_end", agentId }`.

### 1.4 Background tasks

State: `tasks: Map<taskId, Task>`, where `Task = { id, kind: "shell" | "subagent" | "workflow" | "monitor", label, toolUseId, status: "running" | "done" | "failed", startedAt, endedAt? }`.

- **Launch.** In the `tool.call` hook, after `next(e)`:
  - Bash result with `backgroundTaskId` → shell. The label is the command, clipped to 120 characters.
  - Agent result with `status: "async_launched"` and `agentId` → subagent. The label is the description.
  - Workflow result with `taskId` → workflow. The label is the workflow name, or its summary.
  - Monitor result with a task id → monitor. The label is the tool input's description or command. The implementation confirms the result's id field against the d.ts `Monitor` result type. If there is no id, monitors are left out.
- **Completion.** The existing task-notification parse (normalize.ts `TASK_NOTIFICATION`) also extracts `<task-id>` and `<tool-use-id>`. The task is matched by task id, else by tool-use id. Its status is set (completed → done, else failed) and `endedAt` = now. A notice for an unknown task adds a finished entry labeled from `<summary>`.
- **Expiry.** A finished task is removed 10 minutes after `endedAt`, checked on each tick.
- **Reset.** `/clear` and `/resume` (`session.end`) empty `tasks`, `agents`, `workflows` and `unlinked`.

The `task_notice` chat event stays as it is.

### 1.5 Resync

Today's resync rebuilds the main history. It now also rebuilds agents and threads. `sessionDir` = the transcript path without `.jsonl`.

1. For each `subagents/agent-*.meta.json`, link `agentId` → `{ parentToolUseId: toolUseId, kind: "subagent", label: description, type: agentType }`.
2. For each `Workflow` tool result in the replayed main history with `runId`/`transcriptDir`, read its `journal.jsonl` and link the `started` agents. Each one with a `result` line counts as done.
3. Keep only the 20 most recently modified agent transcripts.
4. For each kept agent, read its `.jsonl` with the transcript reader, without the `isSidechain` filter for these files, and send a per-thread chunked snapshot. The same caps apply per thread (500 events, 4 MB), and images share the newest-30 window.
5. Status:
   - Done if its last row is a final assistant reply with no pending tool use, or if `$.agent.list()` says `completed`/`failed`/`killed`.
   - Running if `$.agent.list()` says `running`/`waiting`/`pending`.
   - Otherwise done.
6. Then send an `agent` control per kept agent and one `tasks` control.

After a resync, a running background task can only be known if `$.agent.list()` lists it (subagents). Background shells and workflows started before the mod's restart are not recovered.

Files are listed with `$.process.run(['find', <dir>, '-maxdepth', '1', '-name', '<pattern>'])` and read with the existing piecewise `tail` reader (`readTranscript`, generalized to accept a per-file line filter), the same way the transcript is found and read today. Paths are built only from the session directory and ids matching `^[a-z0-9]+$` (agent ids) or `^wf_[a-z0-9-]+$` (run ids). Anything else is ignored.

## 2. Companion

### 2.1 Pane state in chatbridge

`pane` gains:

- `agents map[string]json.RawMessage`: the latest `agent` control's `agent` object per `agentId`, replaced in place.
- `tasks json.RawMessage`: the latest `tasks` array.
- `threads map[string]*thread`, where `thread = { events []Entry; seq int; subs map[int]chan Update; staging []json.RawMessage; lastActive time.Time }`.

Rules:

- A chat event with `agentId` goes to that thread's buffer (created on first use) and never to the main buffer. It is excluded from the pane's activity/summary, so the dashboard line stays main-thread.
- **Caps:** `ThreadCap = 1000` events per thread and `ThreadsMax = 20` per pane. When a 21st thread is created, the thread with the oldest `lastActive` whose agent summary status is not `running` is evicted, along with its summary. If all are running, the oldest is evicted anyway.
- Per-thread `snapshot_begin/chunk/end` stage and swap like the main stream, under the pane's current epoch.
- A main resync (new epoch) clears `agents`, `tasks` and `threads`, and closes thread subscribers. Their next `chat_open` gets the rebuilt thread.
- `Drop(pane)` clears everything, as today.
- Images stay in the pane's shared LRU (30 images, 40 MB).

### 2.2 Updates

`Update.Kind` gains:

- `"agent"`: carries the raw agent object. Fanned out to main-stream subscribers.
- `"tasks"`: carries the raw tasks array. Fanned out to main-stream subscribers.

Thread subscribers get `"event"` and `"snapshot"` for their thread only.

The main `Snapshot` gains `Agents []json.RawMessage` (sorted by `ts`) and `Tasks json.RawMessage`.

`Subscribe(paneID, agentID string)`: with an empty agentID it behaves as today. Otherwise it returns the thread's newest `SnapshotTail` entries plus `HasMore`, or `Missing: true` when the thread doesn't exist. `History(paneID, agentID, epoch, beforeSeq, limit)` does the same per thread.

`Summary` gains `BgRunning int`: the number of tasks with status `running`, counted when a `tasks` control arrives. It flows through `onSummary` into pane state as it does now.

### 2.3 WebSocket protocol 10

- `chat_open {paneId, agentId?}` / `chat_close {paneId, agentId?}`.
- `chat_snapshot {paneId, agentId?, epoch, state, events, hasMore, missing?, agents?, tasks?}`. `agents` and `tasks` appear on main snapshots only.
- `chat_event {paneId, agentId?, epoch, seq, event}`.
- `chat_history` request `{reqId, paneId, agentId?, epoch, beforeSeq, limit}`. The response echoes `agentId`.
- `chat_agent {paneId, agent}` and `chat_tasks {paneId, tasks}`, sent to main-stream subscribers.
- Pane state objects gain `bgRunning` (omitted when 0).
- `companionProtocol` = 10.

A protocol-9 app never sends `agentId`. It gets the main stream as before and ignores `chat_agent`, `chat_tasks` and the extra snapshot fields.

## 3. App

### 3.1 Model and data

- `ChatEvent`/protocol parsing gains `agentId` on events, `AgentSummary`, `Task`, and the `chat_agent`/`chat_tasks` frames, plus `bgRunning` on `Pane`.
- Chat state gains `agents: Map<String, AgentSummary>` and `tasks: List<Task>`, applied by the reducer from the snapshot and the frames.
- `ChatRepository` is keyed by `(paneId, agentId?)`. A thread reuses the whole reducer (entries, epoch, paging, images). The app keeps at most one thread subscription: the open thread screen.

### 3.2 Timeline agent cards

`buildTimeline` takes `agents` and pulls each `tool_use` whose tool is `Agent` or `Workflow` out of the tool rail into `TimelineItem.AgentCard(toolUseId, tool, summary, agents)`. The rail splits around it.

- **Subagent card:**
  - kind glyph, label, type, status (running pulses, done ✓, failed ✗)
  - one activity line in the dashboard's format (`activityLine`)
  - elapsed time

  It finds its agent by `parentToolUseId == toolUseId`. Before the summary arrives, it shows the tool's summary and "starting…". Tapping it opens the thread screen.
- **Workflow card:** the workflow name and status, then one compact row per agent whose `parentToolUseId` is this tool use, grouped under phase headings, each showing label, status and activity. Beyond 6 rows, a "+N more" row expands in place. Tapping a row opens that agent's thread.
- Agent cards keep stable keys (`"a:" + toolUseId`) across history pages, matching the chat scroll fix.

### 3.3 Thread screen

- Opened from a card inside `PaneScreen`, it replaces the chat view. It uses the shared pane header: "← label · type · status".
- It renders the chat timeline read-only: Markdown, images, long-press copy, scroll-up paging, and agent cards for nested subagents. It has no composer, no question sheet and no interrupt.
- On enter it sends `chat_open(paneId, agentId)`. On leave it sends `chat_close`.
- Opening a nested agent pushes onto a small stack. System back and header ← pop it, and the last pop returns to the chat with its scroll position kept. The chat's `LazyListState` is hoisted above the thread switch.
- `missing` shows "This thread is no longer available" and a back button.

### 3.4 Tasks strip and dashboard badge

- **Strip:** under the chat header, only while `tasks` is non-empty: "⟳ 2 running · 1 done". Running counts first, and finished tasks drop off after their 10-minute expiry. Tapping it opens a bottom sheet with one row per task:
  - kind glyph (shell `$`, subagent, workflow, monitor)
  - label
  - status
  - duration (ticking each second while running)

  Tapping a subagent or workflow row closes the sheet and scrolls the chat to its card, if that card is loaded. A shell or monitor row does nothing.
- **Dashboard:** `PaneRow`/`NeedsYouCard` show "⟳ N" when `pane.bgRunning > 0`.

## 4. Error handling

- An unlinkable agent's rows are dropped after 3 ticks (1.1).
- A journal or meta read that fails is skipped. The agent stays unlinked and its rows are eventually dropped.
- A thread evicted from the companion → `missing` (3.3).
- A summary whose parent card isn't loaded is kept. The card shows it once the history page with the parent comes in.
- A bad `agent`/`tasks` control is logged and ignored by the companion, which never fails the whole `/sync`.

## 5. Testing

- **Mod (vitest + kit):**
  - `agent.spawn` linking, and workflow linking from a journal fixture
  - held rows released on link and dropped after 3 ticks
  - noise rows (attachment, isMeta) dropped for agent rows
  - `agentId` on routed events, and `agent` controls only on change
  - status from turn.complete, aborts and notices, and a resumed agent going back to running
  - tasks: the launch shapes from the spike fixtures, notice matching by task id and by tool-use id, an unknown-notice entry, 10-minute expiry, reset on `/clear`
  - resync from a `subagents/` fixture tree, including the id/path validation
- **Companion (go test):**
  - thread routing and summary exclusion
  - caps and eviction order
  - agents/tasks in the snapshot and in updates
  - thread subscribe/history/missing
  - per-thread chunked snapshot
  - epoch reset clearing threads
  - `BgRunning` in the summary
  - protocol 10 frames in wsserver, and a protocol-9-style client unaffected
- **App (JUnit):**
  - parsing of the new frames and fields
  - reducer for agents/tasks
  - `buildTimeline` agent cards: subagent, workflow grouping, late summary, the rail split, stable keys
  - task strip counts and expiry
  - thread back stack
- **Live:** on the emulator, then the phone, the spike's scenario in a herdr pane: a foreground subagent, a background subagent, a background Bash and a two-agent workflow. Check the cards, opening a thread, the strip and sheet, the dashboard badge, back from a thread, and a companion restart in the middle.

## Rollout

Mod, companion and app ship together. The installed mod reloads with `/reload-plugins`. `~/.local/bin/herdr-mobiled` must be rebuilt and restarted. README protocol notes and CHANGELOG are updated.

## Out of scope

Permission approvals, live output of background shells, stopping tasks from the phone, sending messages to a subagent, teammates (agents in other panes), and recovering background shells and workflows that started before a mod restart.
