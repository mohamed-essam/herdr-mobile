# herdr-mobile — sidebar / tree navigation (v2, phase 2)

**Status:** design approved 2026-07-08
**Predecessor:** `docs/superpowers/specs/2026-07-08-herdr-mobile-terminal-design.md` (interactive terminal, shipped)

## Summary

Add a Paseo-style slide-over **drawer** that presents herdr's structure as a
collapsible **workspace → tab → pane** tree. The drawer is a navigator layered
over the existing flat dashboard (which is unchanged and remains the home view).
Tapping an agent pane in the tree opens its terminal. The companion starts
serving workspace- and tab-level metadata (labels, rolled-up status, worktree
info) so the tree can show friendly names instead of raw ids.

## Goals

- A left drawer (hamburger + edge-swipe) listing all workspaces → tabs → panes.
- Friendly labels at every level (workspace/tab `label`, agent name or "shell").
- Collapsible workspaces and tabs; expansion state remembered while the app runs.
- Per-pane status indicator (glyph + braille spinner) like the main screen, plus
  a rolled-up status on each workspace/tab group.
- A focused-pane marker (herdr-focused pane and last-opened pane) so you can see
  "where you are".
- Shell (non-agent) panes shown for full hierarchy context, dimmed and inert.
- Tapping an agent pane opens the existing `TerminalScreen`.
- Paseo-inspired aesthetic on the existing Catppuccin/monospace base, including a
  worktree/branch badge on linked-worktree workspaces.

## Non-goals (explicitly deferred)

- **Filter / jump field (`⌘K`).** A search box over the tree. Nice with many
  panes; deferred to keep this phase bounded.
- **Structural actions** (create/split/close/rename/move workspaces, tabs, panes
  from the tree). Still out of scope.
- **Shell-pane terminals.** `herdr agent attach` is agent-only; shell panes stay
  visible but inert, as in the terminal phase.
- **Retiring or restyling the flat dashboard.** The dashboard stays exactly as
  today; the drawer is purely additive.
- **Auth on the WS.** Companion remains unauthenticated on a private bind
  (Tailscale / loopback / `0.0.0.0` for local testing).

## Verified assumptions (probed against herdr 0.7.1)

`herdr workspace list` returns, per workspace:
`workspace_id`, `label` (e.g. "omega3", "apollo", "herdr-mobile",
"wt-cost-dashboards"), `number`, `active_tab_id`, `agent_status` (rolled-up),
`focused`, `pane_count`, `tab_count`, and — for linked worktrees — a `worktree`
object with `repo_name`, `repo_root`, `checkout_path`, `is_linked_worktree`.

`herdr tab list` returns, per tab: `tab_id`, `label` (e.g. "1", "2"), `number`,
`workspace_id`, `agent_status`, `focused`, `pane_count`.

`pane.list` already provides (existing): `pane_id`, `workspace_id`, `tab_id`,
`cwd`, `focused`, `agent`, `agent_status`.

Joining panes to tabs (`tab_id`) and tabs to workspaces (`workspace_id`) is
enough to build the full tree. A pane whose `tab_id`/`workspace_id` has no
matching tab/workspace entry (a race between polls) is bucketed under a
synthetic "(unknown)" group rather than dropped.

## Architecture

```
 herdr socket ── workspace.list ──┐
              ── tab.list ────────┤  companion   ── ws: panes ──────▶  app ── TreeModel ─▶ Drawer
              ── pane.list ───────┘  (poll ~1.5s) ── ws: workspaces ─▶       (dashboard also
                                                   ── ws: tabs ───────▶        consumes panes)
```

The companion polls three list endpoints on the same cycle and pushes each as a
full-list frame when it changes. The app already consumes `panes`; it now also
consumes `workspaces` and `tabs`, joins them into a tree, and renders the drawer.
The flat dashboard is an unchanged, independent consumer of `panes`.

## Companion (Go)

### Wire types (`internal/herdr/types.go`)
Add:

```go
type WorktreeInfo struct {
    RepoName        string `json:"repo_name"`
    RepoRoot        string `json:"repo_root"`
    CheckoutPath    string `json:"checkout_path"`
    IsLinkedWorktree bool  `json:"is_linked_worktree"`
}

type WorkspaceInfo struct {
    WorkspaceID string        `json:"workspace_id"`
    Label       string        `json:"label"`
    Number      int           `json:"number"`
    ActiveTabID string        `json:"active_tab_id"`
    AgentStatus string        `json:"agent_status"`
    Focused     bool          `json:"focused"`
    PaneCount   int           `json:"pane_count"`
    TabCount    int           `json:"tab_count"`
    Worktree    *WorktreeInfo `json:"worktree,omitempty"`
}

type TabInfo struct {
    TabID       string `json:"tab_id"`
    Label       string `json:"label"`
    Number      int    `json:"number"`
    WorkspaceID string `json:"workspace_id"`
    AgentStatus string `json:"agent_status"`
    Focused     bool   `json:"focused"`
    PaneCount   int    `json:"pane_count"`
}
```

Plus the corresponding result envelopes (`workspace_list` → `{workspaces:[...]}`,
`tab_list` → `{tabs:[...]}`), mirroring `paneListResult`.

### herdr client
Add `ListWorkspaces()` and `ListTabs()` methods next to the existing pane-list
call, using the same one-request-per-connection NDJSON pattern (`workspace.list`,
`tab.list`).

### Engine / state
The poll loop that fetches `pane.list` also fetches `workspace.list` and
`tab.list`. The state snapshot gains `workspaces []WorkspaceInfo` and
`tabs []TabInfo`. On change (compared to the last snapshot), emit the new frames.
Panes keep their existing per-pane update/removed path unchanged.

### WS protocol (bump companionProtocol 2 → 3; additive)
Companion → app, mirroring the existing `panes` frame:
- `{"t":"workspaces","workspaces":[ WorkspaceInfo... ]}`
- `{"t":"tabs","tabs":[ TabInfo... ]}`

Both are full-list snapshots pushed on change (same semantics as the existing
`panes` frame). No new app → companion frames. `Welcome` reports
`companionProtocol: 3`.

## App (Kotlin / Compose)

### Protocol (`net/Protocol.kt`)
- Add `Workspace` and `Tab` data classes matching the wire types (with
  `@SerialName` where needed; `worktree` nullable).
- Add `ServerFrame.Workspaces(list)` and `ServerFrame.Tabs(list)` variants and
  parse the `workspaces`/`tabs` frame types.

### CompanionClient / ViewModel
- `CompanionClient` exposes `workspaces: StateFlow<List<Workspace>>` and
  `tabs: StateFlow<List<Tab>>` (updated on the new frames), alongside the
  existing `panes`.
- `DashboardViewModel` exposes a derived `tree: StateFlow<List<TreeNode>>` that
  joins workspaces + tabs + panes:

```kotlin
sealed interface TreeNode {
    data class WorkspaceNode(val ws: Workspace, val tabs: List<TabNode>) : TreeNode
    data class TabNode(val tab: Tab, val panes: List<Pane>) : TreeNode
}
```

  Ordering: workspaces by `number`, tabs by `number`, panes by `paneId`. Panes
  with no matching tab/workspace go under a synthetic "(unknown)" workspace/tab
  so nothing is silently dropped. Expansion state (`Set<String>` of expanded
  workspace/tab ids) lives in the ViewModel and survives recomposition.

### Drawer (`ui/SidebarDrawer.kt`)
- `ModalNavigationDrawer` wrapping the dashboard `Scaffold`. Opened by a
  hamburger (`≡`) added to the dashboard top bar and by left edge-swipe; closed
  by scrim tap, back, or selecting an agent pane.
- Tree rendering (a `LazyColumn` of flattened visible rows):
  - **WorkspaceRow:** `▾`/`▸` chevron, `label` + dim `#number`, rolled-up status
    glyph (`statusGlyph`/spinner via `statusColor`), `pane_count`, and — when
    `worktree.is_linked_worktree` — a dim `⑂ <repo_name>` badge. Tapping the row
    toggles expansion.
  - **TabRow (indented):** `▾`/`▸` chevron, `label`, rolled-up status glyph.
    Tapping toggles expansion.
  - **PaneRow (indented further):** status glyph + agent name (or "shell" for
    `agent == null`), dim cwd basename. Agent panes are tappable → close drawer,
    open `TerminalScreen`. Shell panes are dimmed and inert.
  - **Focused marker:** the herdr-focused pane (`pane.focused`) and the
    last-opened pane (tracked in the ViewModel) show a left accent bar in
    `primary`.
- Reuses `statusColor` / `statusGlyph` / `SpinnerFrames` / `spinnerFrame()` from
  the existing theme + `StatusIndicator`; no new palette.

### Dashboard changes
- Add the hamburger affordance to `HerdrTopBar` and wire the drawer state.
- Nothing else about the dashboard changes; `PaneRow`, terminal flow, reconnect,
  and empty/reconnecting states are untouched.

## Visual design (Paseo-inspired)

- Drawer surface: `surfaceContainerLow` (deeper crust), sharp corners, monospace
  labels — same terminal aesthetic as the rest of the app.
- Hierarchy shown by indentation + tree glyphs (`▾ ▸`), dense rows, no nested
  cards — Paseo's minimal sidebar feel.
- Subtle accents only: status color on the glyph, `primary` accent bar for the
  focused/selected row, dim (`onSurfaceVariant`) for secondary text, numbers, and
  the worktree badge.
- Worktree/branch badge (`⑂ repo`) echoes Paseo's parallel-worktree indicators.

## Testing

- **Companion unit tests** (against the `fakeherdr` harness, no real herdr):
  - `workspace.list` / `tab.list` parse into `WorkspaceInfo` / `TabInfo`
    (including the nullable `worktree`).
  - Engine emits `workspaces` / `tabs` frames on change and not when unchanged.
  - `Welcome` reports `companionProtocol: 3`.
- **App unit tests:**
  - `workspaces` / `tabs` frame deserialization (round-trip, `worktree` present
    and absent).
  - `TreeModel` join: panes bucket under the correct tab/workspace; ordering by
    number/paneId; orphan pane → "(unknown)" group; collapse/expand toggles the
    visible row set.
- **Live (emulator harness + physical phone):** open the drawer, verify the real
  tree (e.g. omega3 with 2 tabs, apollo, herdr-mobile with a worktree badge where
  applicable), confirm rolled-up + per-pane status and the focused marker track
  herdr, tap an agent pane → terminal opens, shell panes are inert.

## Global constraints

- Companion `companionProtocol` becomes **3**; all new frames are additive — an
  older app ignoring `workspaces`/`tabs` still works.
- Reuse the existing theme (`statusColor`, `statusGlyph`, `SpinnerFrames`,
  Catppuccin palette, mono typography, sharp shapes) — no new colors or fonts.
- App stays GPLv3 (Termux vendoring, unchanged).
- No new app → companion frames; the drawer is read-only navigation.
- Nothing dropped silently: orphan panes surface under an "(unknown)" group.

## Rollout / commits (high level)

1. Companion: `WorkspaceInfo`/`TabInfo` types + `workspace.list`/`tab.list`
   client calls + tests.
2. Companion: engine poll + snapshot + `workspaces`/`tabs` frames (protocol 3)
   + tests.
3. App: `Workspace`/`Tab` types + `Workspaces`/`Tabs` frames in `Protocol.kt`
   + `CompanionClient` flows + tests.
4. App: `DashboardViewModel.tree` join + expansion state + tests.
5. App: `SidebarDrawer` UI + hamburger in the top bar; agent-pane tap → terminal.
6. Live validation on the emulator + phone; docs/memory update.
