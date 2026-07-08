# herdr-mobile — structural create + move

**Status:** design approved 2026-07-08
**Predecessor:** `docs/superpowers/specs/2026-07-08-herdr-mobile-structural-actions-design.md` (raw attach + rename/close, shipped)

## Summary

Completes structural management from the phone. Rename and close shipped in the
predecessor; this cut adds the remaining operations that make sense without a 2D
layout view:

- **Create** — new workspace, new tab, new shell pane (`pane.split`), and new
  agent (`agent.start`).
- **Move** — relocate a pane to an existing tab, a new tab, or a new workspace
  (`pane.move`).

**Swap and zoom are deliberately excluded** — both are spatial operations on
herdr's 2D pane layout, which the app never renders (it attaches one pane
full-screen), so they have no meaningful mobile referent.

All ops ride the existing action-sheet + re-poll machinery. Creating an agent or
shell drops the user straight into the new pane's live terminal.

## Goals

- From the sidebar, create a new workspace, tab, shell pane, or agent — and land
  in the new pane's terminal immediately (for agent/shell).
- The "New agent" picker offers herdr's known agents plus a free-typed "Other…"
  command, with cwd inherited from the creation context (no path typing in the
  common case).
- Move any pane to an existing tab, a new tab, or a new workspace; the tree
  reflects the change promptly.

## Non-goals (explicitly deferred / excluded)

- **Swap (`pane.swap`) and zoom (`pane.zoom`)** — poor mobile fit (spatial /
  layout-only); excluded, not merely deferred.
- **Label prompts on create** — new workspace/tab/shell are one-tap; herdr
  auto-numbers and the user can Rename afterward (rename already shipped). Only
  the "Other…" agent path takes typed input (the command).
- **`ratio`, `env`, per-op focus toggles** — `pane.split`/`agent.start` accept
  these; we use herdr defaults (no ratio, no env, focus per the auto-open rule).
- **Undo** — herdr create/move/close are immediate and server-side; no undo.
- **A 2D layout editor** — moves pick a destination from a list, not a canvas.

## Verified findings (herdr 0.7.x source + live 0.7.1 socket)

Method names (from the server's method enum) and param shapes
(`src/api/schema/*.rs`), all confirmed:

- `workspace.create` → `{ cwd?, focus, label?, env }`
- `tab.create` → `{ workspace_id?, cwd?, focus, label?, env }`
- `pane.split` → `{ workspace_id?, target_pane_id?, direction, ratio?, cwd?, focus, env }`;
  returns `{ type:"pane_info", pane:{ pane_id, terminal_id, workspace_id, tab_id, ... } }`.
- `agent.start` → `{ name, cwd?, workspace_id?, tab_id?, split?, focus, argv, env }`
  (`name` = label, `argv` = the command to run; both required).
- `pane.move` → `{ pane_id, destination, focus }`; returns
  `{ type:"pane_move", move_result:{ changed, previous_*; pane:{...} } }`.
- **`SplitDirection`** serializes snake_case: `"right"` | `"down"`.
- **`PaneMoveDestination`** is internally tagged (`#[serde(tag="type", rename_all="snake_case")]`):
  - `{ "type":"tab", "tab_id":<id>, "target_pane_id"?:<id>, "split":"right"|"down", "ratio"?:<f32> }`
  - `{ "type":"new_tab", "workspace_id"?:<id>, "label"?:<str> }`
  - `{ "type":"new_workspace", "label"?:<str>, "tab_label"?:<str> }`
- **Known agents** for the picker come from `server.agent_manifests` →
  `manifests[].agent` (live: `claude`, `codex`, `gemini`, `pi`, `cursor`). These
  are detection names, not launch specs, so `agent.start` supplies
  `argv=[name]` by default.

Live-verified end to end: `pane.split {target_pane_id, direction:"down"}` created
a shell; `pane.move {pane_id, destination:{type:"new_tab", label:"movetest"}}`
returned `move_result.changed=true` with the moved pane; cleanup left no stray
tab.

## Design

### 1. Entry points — context-aware action sheet + header `+`

The `RowActionSheet` from the predecessor becomes context-aware; a `+` is added
to the drawer header.

| Row | Sheet items (in order) |
|---|---|
| Drawer header | `+` → **New workspace** |
| Workspace | New tab · New agent · Rename · Close |
| Tab | New shell · New agent · Rename · Close |
| Pane | Split right · Split down · Move… · Rename · Close |

The sheet's item list is derived from the `RowAction.kind`. Rename/Close are
unchanged.

### 2. Create flows

- **New workspace** (header `+`) → `workspace.create {focus:true}`.
- **New tab** (workspace sheet) → `tab.create {workspace_id:<ws>, focus:true}`.
- **New shell** (tab sheet) → `pane.split {workspace_id:<ws of tab>, direction:"down", focus:true}`
  (adds a shell pane to that tab's workspace; herdr splits the active pane).
- **Split right / Split down** (pane sheet) →
  `pane.split {target_pane_id:<pane>, direction:"right"|"down", focus:true}`.
- **New agent** (workspace/tab sheet) → opens the **agent picker** (below), then
  `agent.start`.

All four create kinds are **one tap** (no label prompt). Each returns a new pane;
the app **auto-opens** its `terminal_id` (attaches the terminal full-screen),
per the approved behavior. The tree also refreshes via the existing re-poll.

Placement/cwd: create ops carry the context ids (`workspace_id` from a workspace
row, or the tab's `workspace_id` from a tab row; `target_pane_id` from a pane
row). cwd is omitted so herdr inherits it from that context. `focus:true` so the
new pane becomes herdr's active pane (consistent with auto-opening it).

### 3. New-agent picker

A `ModalBottomSheet` (or dialog) listing the agents from `server.agent_manifests`
plus a final **Other…** row.

- Selecting a known agent `X` → `agent.start { name:"X", argv:["X"], <context>, split:"down"(tab)/none(ws), focus:true }`.
- Selecting **Other…** → an `AlertDialog` with a text field. On submit, the
  string is split on whitespace into `argv`; `name` = the basename of `argv[0]`
  (e.g. `"claude --model opus"` → name `"claude"`, argv `["claude","--model","opus"]`).
  Empty input is rejected (Start disabled until non-blank).

The companion fetches the agent list once (see protocol) so the app can render
the picker; if the list is unavailable the picker still shows **Other…**.

### 4. Move flow

"Move…" (pane sheet) opens a **destination sheet**: a scrollable list of existing
tabs (each labeled `workspace-label / tab-label`) followed by **New tab** and
**New workspace**.

- Existing tab → `pane.move { pane_id, destination:{ type:"tab", tab_id:<t>, split:"down" }, focus:false }`.
- New tab → `pane.move { pane_id, destination:{ type:"new_tab" }, focus:false }`.
- New workspace → `pane.move { pane_id, destination:{ type:"new_workspace" }, focus:false }`.

Move is reorganizing, so it **stays in the tree** (no auto-open); the re-poll
updates positions. The destination tab list is built client-side from the tree
the app already holds (workspaces + tabs), excluding the pane's current tab.

### 5. Protocol (companionProtocol 4 → 5, additive)

The existing `action` frame (rename/close) stays as-is. Two new app→companion
frames, because create/move carry richer params and create must return the new
pane:

- **`create`**:
  `{ "t":"create", "reqId", "what":"agent"|"shell"|"tab"|"workspace", "workspaceId"?, "tabId"?, "paneId"?, "direction"?:"right"|"down", "agentName"?, "argv"?:[...], "cwd"? }`
  → companion replies **`created`**:
  `{ "t":"created", "reqId", "ok":bool, "error"?, "paneId"?, "terminalId"? }`.
  On `ok`, the app auto-opens `terminalId`.
- **`move`**:
  `{ "t":"move", "reqId", "paneId", "dest":"tab"|"new_tab"|"new_workspace", "tabId"?, "direction"?:"down" }`
  → companion replies with the existing **`action_result`** `{ ok, error? }`.

Also, so the app can populate the agent picker:
- **`list_agents`** (app→companion) → **`agents`**
  `{ "t":"agents", "agents":[<name>...] }`, sourced from
  `server.agent_manifests`. Sent on demand when the picker opens (cached by the
  app for the session).

The companion maps `create.what` → the right herdr method, builds params from the
context ids, and returns the created pane's `pane_id`/`terminal_id` from the
method result. `move.dest` → the tagged `PaneMoveDestination`. Both trigger the
existing `Poke()` re-poll on success; failures return `ok:false` with the herdr
error, surfaced by the existing action-error snackbar.

### 6. App wiring

- `RowActionSheet` gains create/move/split items keyed off `RowAction.kind`; the
  drawer header gains a `+`.
- New composables: `AgentPickerSheet` (+ the "Other…" `AlertDialog`) and
  `MoveDestinationSheet` (tab list + new-tab/new-workspace).
- `DashboardViewModel` gains `createNode(...)`, `moveNode(...)`, and
  `agents: StateFlow<List<String>>` (+ `refreshAgents()`); `createNode` on
  success emits the new `terminalId` so `DashboardScreen` opens the terminal
  (reuse the existing `selected`-pane mechanism, keyed by the returned pane).
- `CompanionClient` gains `sendCreate(...)` (returns the created pane ids or
  throws), `sendMove(...)`, and `listAgents()`.
- Reuse the existing `actionErrors` snackbar and re-poll.

## Global constraints

- companionProtocol 4 → 5, strictly additive: new `create`/`created`,
  `move`, `list_agents`/`agents` frames; old clients/companions ignore unknown
  frames.
- Use only herdr's documented methods: `workspace.create`, `tab.create`,
  `pane.split`, `agent.start`, `pane.move`, `server.agent_manifests`. No new
  socket methods; `SplitDirection` snake_case; `PaneMoveDestination` internally
  tagged exactly as verified.
- Create is one-tap (no label prompt); only "Other…" takes typed input. Agent
  known-list comes from `agent_manifests`; unknown agents go through "Other…".
- Create (agent/shell) auto-opens the returned pane; move stays in the tree.
- Reuse the existing action sheet, `action_result` error path, `actionErrors`
  snackbar, and `Poke()` re-poll. Swap and zoom are out of scope.
- Attach still uses `terminal attach <terminal_id>`; the auto-open path reuses
  the existing terminal-open flow with the returned `terminalId`.

## Testing

**Companion unit tests (Go):**
- `create.what` → correct herdr method + params, table-driven over
  agent/shell/tab/workspace (incl. context-id mapping and `direction`); the
  `created` reply carries the new `paneId`/`terminalId` from the method result.
- `move.dest` → correct `PaneMoveDestination` JSON for tab/new_tab/new_workspace
  (assert the `type` tag + fields).
- `list_agents` → `agents` frame from a stubbed `server.agent_manifests`.
- Success pokes a re-poll; failure returns `ok:false` + error, no poke.
- herdr.Client: `CreateWorkspace`/`CreateTab`/`SplitPane`/`StartAgent`/`MovePane`
  hit the right methods with the right params (fake-herdr, table-driven), and
  parse the returned pane ids.

**App unit tests (JVM):**
- `create`/`move`/`list_agents` frame builders (correct keys; `direction`/`argv`
  present only when set); `created`/`agents` parse.
- "Other…" command → `argv` split + `name` basename (pure helper, table-driven:
  `"claude"`, `"claude --model opus"`, `" htop "`).
- Move destination list built from the tree excludes the pane's current tab.
- VM `createNode` surfaces the returned `terminalId` for auto-open; `moveNode`
  errors reach `actionErrors`.

**Live (emulator + phone):**
- New workspace (header `+`) → lands in its shell terminal.
- New tab / New shell → new pane appears + terminal opens.
- New agent: pick claude → agent starts in context, terminal opens; "Other…"
  → type a command → it runs.
- Move a pane to an existing tab, a new tab, and a new workspace → tree reflects
  each; no auto-open.
- Force a herdr error (e.g. bad "Other…" command) → error snackbar.

## Rollout / commits (high level)

1. Companion: herdr.Client create/split/start/move methods + `agent_manifests`
   fetch; unit tests.
2. Companion: `create`/`created`, `move`, `list_agents`/`agents` frames +
   handler (method routing, returned-pane extraction, re-poll); proto 4→5.
3. App: protocol frames + builders + "Other…" argv helper + VM
   createNode/moveNode/agents; unit tests.
4. App: context-aware action sheet + header `+`; AgentPickerSheet (+ Other
   dialog); MoveDestinationSheet; auto-open on create.
5. Live validation on emulator + phone; docs/memory update.
