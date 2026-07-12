# herdr-mobile — raw-terminal attach + structural actions (rename/close)

**Status:** design approved 2026-07-08
**Predecessors:**
- `docs/superpowers/specs/2026-07-08-herdr-mobile-sidebar-design.md` (workspace/tab/pane tree, shipped)
- `docs/superpowers/specs/2026-07-08-herdr-mobile-terminal-polish-design.md` (interactive terminal polish, shipped)

## Summary

Two paired capabilities, shipped as one coherent slice:

1. **Raw-terminal attach.** Make *non-agent* (shell) panes first-class attachable
   terminals, exactly like agent panes. This was previously assumed impossible
   because `herdr agent attach` rejects non-agent targets — but herdr exposes a
   separate `herdr terminal attach <terminal_id> [--takeover]` command that
   streams *any* pane's PTY (agent or shell) with no agent resolution. Switching
   the companion to it unlocks shell panes for free.

2. **Structural actions — rename + close.** Let the user rename and close
   workspaces, tabs, and panes from the sidebar tree via a long-press / `⋮`
   action sheet, backed by herdr's existing socket methods. This is the first
   slice of structural management; create/split/move/swap/zoom are explicitly
   deferred.

## Goals

- Tapping a shell pane in the sidebar opens a live interactive terminal, the
  same experience as tapping an agent pane.
- From any workspace/tab/pane row, the user can rename the item and close it,
  with destructive closes gated by a confirmation scaled to the blast radius.
- Structural changes are reflected in the tree promptly (an action triggers an
  immediate companion re-poll), and failures surface as a snackbar.

## Non-goals (explicitly deferred)

- **Create / split / move / swap / zoom.** No `pane.split`, `pane.move`,
  `tab.create`, `workspace.create`, `agent.start`, `pane.zoom` in this cut.
- **Reset-to-default rename.** Only `pane.rename` accepts a cleared label;
  `workspace.rename`/`tab.rename` require a label. To keep the UI uniform,
  rename always sets a non-empty label; no "reset to default" affordance.
- **Undo for close.** herdr close is immediate and irreversible server-side
  (no reopen API); no undo snackbar.
- **Focus/activation actions** (`workspace.focus`, `tab.focus`, `agent.focus`).
- **Swipe-to-action gestures** (conflict with the drawer's drag-to-dismiss).

## Verified findings (from the herdr 0.7.x source + a live 0.7.1 server)

Cloned `github.com/ogulcancelik/herdr` to `the herdr repo`.

- **`herdr agent attach <target>` is agent-only.** `src/cli/agent.rs`
  `agent_attach` resolves the target *to an agent*, reads
  `result.agent.terminal_id`, then calls `client::run_terminal_attach`. Live,
  attaching the shell pane `w7:p2` by `pane_id` and by `terminal_id` both fail
  with `{"error":{"code":"agent_not_found"}}`.
- **`herdr terminal attach <terminal_id> [--takeover]` is the raw primitive.**
  `src/cli.rs` `terminal_attach` calls the *same* `run_terminal_attach`
  (`src/client/mod.rs:828`) directly with the `terminal_id`, no agent
  resolution; it sends `ClientMessage::AttachTerminal { terminal_id, takeover }`.
  Live-verified: `herdr terminal attach term_656073c5e393fa --takeover` on the
  shell pane streamed real VT output over a PTY (alt-screen enter, mouse modes,
  clear, cursor positioning, drawn content). The local 0.7.1 binary already
  supports the command (`herdr terminal attach <terminal_id> [--takeover]`;
  detach is `ctrl+b q`, irrelevant since the companion owns the PTY).
- **Every pane carries a `terminal_id`.** `pane.list` returns `terminal_id`
  for agent and shell panes alike (e.g. shell `w7:p2` →
  `term_656073c5e393fa`, `agent_status:"unknown"`, no `agent` field). The
  companion currently polls `pane.list` but drops `terminal_id`.
- **No raw-output socket event exists.** `events.subscribe` accepts only
  lifecycle kinds (`pane.created/closed/focused/moved/exited`,
  `pane.agent_detected`, `pane.output_matched`, `pane.agent_status_changed`,
  and workspace/tab/worktree equivalents). This is why a *socket-only* raw
  terminal would have to poll `pane.read`; the PTY attach above avoids that
  entirely, so we use the attach, not polling.
- **Exact method shapes** (`src/api/schema/*.rs`):
  - `workspace.rename` → `{ workspace_id: String, label: String }`
  - `tab.rename` → `{ tab_id: String, label: String }`
  - `pane.rename` → `{ pane_id: String, label: Option<String> }` (null clears)
  - `workspace.close` → `{ workspace_id: String }`
  - `tab.close` → `{ tab_id: String }`
  - `pane.close` → `{ pane_id: String }`

## Design

### 1. Raw-terminal attach

**Companion.**
- `herdr.PaneInfo` gains `TerminalID string \`json:"terminal_id"\``.
- `state.Pane` gains `TerminalID string \`json:"terminalId"\`` (camelCase,
  app-facing), populated in the pane conversion.
- `wsserver` `attachArgv` changes from
  `["herdr","agent","attach",target,"--takeover"]` to
  `["herdr","terminal","attach",target,"--takeover"]`. The `target` the app
  sends becomes the pane's `terminal_id` (see app change below). `--takeover`
  stays fixed (the phone always seizes the attachment; graceful takeover of the
  *phone* is already handled).

**App.**
- `net.Pane` gains `terminalId: String` (defaulted `""`; additive parse).
- `DashboardViewModel.openTerminal` / the term-open path sends the pane's
  `terminalId` as the attach target instead of `paneId`. (Panes always have a
  `terminal_id`; if for any reason it is blank, the row is not attachable.)
- `SidebarDrawer.PaneTreeRow`: remove the `isAgent` gate on `clickable` — all
  panes are tappable and call `onSelectPane`. Shell panes keep their dimmed
  label/cwd styling (visual cue) but are live.
- `companionProtocol` is bumped 3 → 4 (additive: old clients ignore the new
  `terminalId` field; the field is additive on an existing frame).

### 2. Action sheet (long-press + `⋮`)

- A new `RowActionSheet` (`ModalBottomSheet`) shows the item title and two
  actions: **Rename**, **Close**.
- Each `WorkspaceRow` / `TabRowView` / `PaneTreeRow` is wrapped in
  `combinedClickable(onClick = <existing>, onLongClick = { open sheet })` and
  gains a trailing `⋮` `IconButton` that opens the same sheet. Both affordances
  route to one callback `onRowAction(target)` where `target` identifies the
  node (kind + id + current label +, for close, the computed blast radius).
- The sheet, rename dialog, and confirm dialog are hoisted into
  `DashboardScreen` state (the drawer emits intents; the screen owns the
  dialogs), so they render above the drawer and survive drawer scroll.

### 3. Rename

- Selecting **Rename** opens an `AlertDialog` with an `OutlinedTextField`
  prefilled with the current label; **Save** is enabled only for non-blank,
  changed text.
- Save calls `vm.renameNode(kind, id, label)`, which sends
  `{"t":"action","reqId":<uuid>,"op":"rename","kind":<kind>,"id":<id>,"label":<label>}`.

### 4. Close

- Selecting **Close** either closes immediately (no-confirm case) or opens a
  confirm `AlertDialog`. The decision is a pure function:

  ```
  needsCloseConfirm(kind, node):
    pane      -> node.isAgent                       // shell pane: false; agent pane: true
    tab       -> node.paneCount > 1 || node.hasAgent
    workspace -> true
  ```

  where `hasAgent` for a tab means any of its panes has a non-null `agent`.
- Confirm dialog copy is scaled:
  - pane (agent): "Close this agent pane? The running agent will be terminated."
  - tab: "Close tab '<label>'? This ends <N> pane(s)."
  - workspace: "Close workspace '<label>'? This ends <N> pane(s) across <M> tab(s)."
- Confirm (or the no-confirm path) calls `vm.closeNode(kind, id)`, sending
  `{"t":"action","reqId":<uuid>,"op":"close","kind":<kind>,"id":<id>}`.
- **Currently-viewed pane:** if the closed pane's id equals the pane open in
  `TerminalScreen`, the app navigates back to the dashboard. Implementation:
  `DashboardViewModel` exposes the closed-pane id (e.g. a `SharedFlow` or by
  clearing the `selected`/route state for that pane) so the nav layer pops the
  terminal.

### 5. Protocol / data flow (companion proto 3 → 4, additive)

- **App → companion action frame:**
  `{"t":"action","reqId":String,"op":"rename"|"close","kind":"workspace"|"tab"|"pane","id":String,"label":String?}`
  (`label` present only for `op=="rename"`).
- **Companion → app result frame:**
  `{"t":"action_result","reqId":String,"ok":Boolean,"error":String?}`.
- The companion maps `(op, kind)` → socket method and params:
  - `rename`+`workspace` → `workspace.rename {workspace_id:id, label}`
  - `rename`+`tab` → `tab.rename {tab_id:id, label}`
  - `rename`+`pane` → `pane.rename {pane_id:id, label}`
  - `close`+`workspace` → `workspace.close {workspace_id:id}`
  - `close`+`tab` → `tab.close {tab_id:id}`
  - `close`+`pane` → `pane.close {pane_id:id}`
- On a **successful** socket call the companion triggers an **immediate
  re-poll** (reusing the engine's existing `pollOnce`, exposed via a
  non-blocking "poke"), so the next snapshot broadcast reflects the change
  without waiting for the poll interval. `action_result` carries only ok/error;
  the tree updates through the existing `workspaces`/`tabs`/`panes` snapshots.
- On a **failed** socket call, `action_result.ok=false` with the herdr error
  message; the app shows a snackbar. No re-poll needed.

### 6. App wiring

- `DashboardViewModel`:
  - `renameNode(kind, id, label)` and `closeNode(kind, id)` — build a `reqId`,
    send the action frame via the existing `Io`/companion client.
  - `actionResults: SharedFlow<ActionResult>` — parsed from `action_result`
    frames; the screen collects it to show error snackbars.
  - closed-viewed-pane signal (see §4).
- `PaneRepository.onFrame` gains an `action_result` branch feeding
  `actionResults` (parallel to the existing snapshot handling; no re-sort).
- `DashboardScreen` hosts the `RowActionSheet`, rename `AlertDialog`, confirm
  `AlertDialog`, and a `SnackbarHost`.

## Global constraints

- Reuse the existing theme (Catppuccin `statusColor`/`statusGlyph`/typography),
  the existing sidebar tree (`buildTree`, `WorkspaceNode`/`TabNode`), and the
  existing companion poll/broadcast path (`engine.pollOnce`, snapshot frames).
- `companionProtocol` bump is 3 → 4 and strictly additive: a new `terminalId`
  field on the pane frame, and new `action` / `action_result` frame types. Old
  clients ignore unknown fields/frames.
- Attach uses `herdr terminal attach <terminal_id> --takeover` for **all**
  panes; do not keep a second (agent-attach) code path.
- Rename always sends a non-empty label. Close confirmation follows
  `needsCloseConfirm` exactly (agents, multi-pane tabs, and workspaces confirm).
- No new socket methods are invented; only the six documented rename/close
  methods and the `terminal attach` CLI are used.

## Testing

**Companion unit tests (Go):**
- `PaneInfo` parses `terminal_id`; `state.Pane` carries `terminalId` end to end.
- `attachArgv` builds `["herdr","terminal","attach",<terminal_id>,"--takeover"]`.
- Action handler routes each `(op,kind)` pair to the correct socket method with
  the correct params (table test over the six combinations).
- Successful action triggers a re-poll; failed socket call yields
  `action_result{ok:false,error:...}` and no re-poll.
- Existing frame-order / snapshot tests still pass with proto 4.

**App unit tests (JVM, no device):**
- `needsCloseConfirm(kind, node)` truth table: shell pane → false; agent pane →
  true; single-shell-pane tab → false; single-agent-pane tab → true; multi-pane
  tab → true; workspace → true.
- `action` frame encodes correctly for rename (with label) and close (without);
  `action_result` parses ok/error.
- `Pane` parses `terminalId`; `buildTree` unaffected by the new field.

**Live (emulator + phone):**
- Tap a shell pane → live interactive terminal (same as agent).
- Rename a workspace, a tab, and a pane → labels update in the tree.
- Close: a shell pane (no confirm), an agent pane (confirm), a multi-pane tab
  (confirm w/ count), a workspace (confirm w/ counts).
- Close the pane currently open in the terminal → app returns to dashboard.
- Force a herdr error (e.g. close the last workspace) → error snackbar, tree
  unchanged.

## Rollout / commits (high level)

1. Companion: parse+carry `terminal_id`; switch `attachArgv` to
   `terminal attach`; app `Pane.terminalId` + attach-by-terminalId + tappable
   shell panes; proto bump. (Raw-terminal attach — independently testable.)
2. Companion: `action` frame handler + `(op,kind)`→method dispatch +
   `action_result` + re-poll poke; unit tests.
3. App: `RowActionSheet` + long-press/`⋮` affordances wired to `onRowAction`.
4. App: rename dialog + `renameNode`; `needsCloseConfirm` + confirm dialog +
   `closeNode`; snackbar host + `actionResults`; return-to-dashboard on
   closing the viewed pane.
5. Live validation on emulator + phone; docs/memory update.
