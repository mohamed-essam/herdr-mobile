# herdr-mobile — Unify Sidebar & Dashboard Tree Order — Design Spec

**Date:** 2026-07-11
**Component:** Android app only (`app/`).
**Source:** UI/UX audit finding #10 — the dashboard and sidebar render the same
repo/workspace/pane tree in different orders (dashboard: repo-grouped,
attention+recency; sidebar: flat, by workspace number), so a spatial map formed
in one screen is wrong in the other.

## Goal

Make the sidebar and dashboard render the **same tree, in the same order, with the
same structure** — full unification. The sidebar adopts the dashboard's
repo → workspace → pane grouping and its attention+recency sort (including monogram
repo headers). The two views become one tree at two densities: rich cards on the
dashboard, compact action rows in the drawer.

## Root cause & the durable fix

The divergence exists because there are **two independent flatten functions** that
nothing forces to agree:
- `flattenRepoTree` (private in `DashboardScreen.kt`) — repo-grouped, attention-sorted.
- `flatten` (private in `SidebarDrawer.kt`) — flat, number-sorted.

The fix is **one shared flatten** in `TreeModel.kt`, the same pattern that already
keeps both views agreeing on single-child elision (`workspaceChildren`). Both
screens consume it and render each row type in their own chrome, so they can never
disagree on order or structure again.

## Global Constraints

- App-only; no companion, protocol, or theme changes.
- Do NOT change the sort itself — `buildRepoTree`'s attention+recency ordering
  (blocked > done > rest, then `lastActivity` desc, then `number`, "(unknown)" last)
  stays exactly as is. The sidebar simply adopts it.
- Repo collapse uses the **same** collapse key as the dashboard: `"repo:<repoKey>"`
  in the shared `vm.collapsed` set. Workspace/tab collapse is already shared; repo
  collapse joins it (collapsing a repo in one view collapses it in the other).
- The shared row model must carry `parentTab` on promoted panes (the sidebar's
  pane action sheet pivots to tab actions via it); the dashboard ignores that field.
- `MoveDestinationSheet` still consumes `vm.tree` (a tab picker — out of scope,
  finding #16).

## Component 1 — Shared tree model (`app/.../ui/TreeModel.kt`, new code)

```kotlin
/** One flattened, renderable row of the repo→workspace→tab→pane tree, shared by
 *  the dashboard and the sidebar so the two views can never disagree on order or
 *  structure. Each screen renders these rows in its own chrome. */
sealed interface TreeRow {
    data class Repo(val node: RepoNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : TreeRow
    data class Tab(val node: TabNode, val expanded: Boolean) : TreeRow
    // promoted = its tab was elided and it's hoisted to a workspace-direct child.
    // parentTab = that elided tab (for the sidebar's tab-action pivot); null otherwise.
    data class PaneItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?) : TreeRow
}

/** A node is expanded unless its id is in [collapsed]; repos key on "repo:<key>". */
fun flattenTree(repos: List<RepoNode>, collapsed: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    for (r in repos) {
        val rOpen = "repo:${r.repoKey}" !in collapsed
        val count = r.workspaces.sumOf { w -> w.tabs.sumOf { it.panes.size } }
        rows.add(TreeRow.Repo(r, rOpen, count))
        if (!rOpen) continue
        for (w in r.workspaces) {
            val wOpen = w.ws.workspaceId !in collapsed
            rows.add(TreeRow.Ws(w, wOpen))
            if (!wOpen) continue
            for (child in workspaceChildren(w)) {
                when (child) {
                    is WsChild.TabGroup -> {
                        val t = child.tab
                        val tOpen = t.tab.tabId !in collapsed
                        rows.add(TreeRow.Tab(t, tOpen))
                        if (tOpen) t.panes.forEach {
                            rows.add(TreeRow.PaneItem(it, promoted = false, parentTab = null))
                        }
                    }
                    is WsChild.PromotedPane ->
                        rows.add(TreeRow.PaneItem(child.pane, promoted = true, parentTab = child.parentTab))
                }
            }
        }
    }
    return rows
}

fun treeRowKey(row: TreeRow): String = when (row) {
    is TreeRow.Repo -> "r:" + row.node.repoKey
    is TreeRow.Ws -> "w:" + row.node.ws.workspaceId
    is TreeRow.Tab -> "t:" + row.node.tab.tabId
    is TreeRow.PaneItem -> "p:" + row.pane.paneId
}
```

This merges the two current flatten functions verbatim in behavior (dashboard's
repo level + sidebar's `parentTab` carry). `flattenTree`/`treeRowKey` become public
and unit-tested (the current private flatten functions are untested).

## Component 2 — Dashboard (`app/.../ui/DashboardScreen.kt`)

- Delete the private `DashRow` sealed interface, `flattenRepoTree`, and `dashRowKey`.
- Render from the shared model: `flattenTree(repoTree, collapsed)`, keyed by
  `treeRowKey`. The `when(row)` maps `TreeRow.Repo → RepoHeaderRow`,
  `Ws → WsHeaderRow`, `Tab → TabHeaderRow`, `PaneItem → PaneRow` (the pane card).
- The existing `RepoHeaderRow`/`WsHeaderRow`/`TabHeaderRow` composables keep their
  bodies; only their parameter types change from `DashRow.X` to `TreeRow.X`.
- Pane indent unchanged: `Box(padding(start = if (row.promoted) 32.dp else 48.dp))`.
- The `SidebarDrawer(...)` call passes `repos = repoTree` (was `tree = tree`).

## Component 3 — Sidebar (`app/.../ui/SidebarDrawer.kt`)

- Signature: replace `tree: List<WorkspaceNode>` with `repos: List<RepoNode>`.
- Delete the private `Row` sealed interface, `flatten`, and `rowKey`; render from
  the shared `flattenTree(repos, collapsed)` keyed by `treeRowKey`.
- Add a **repo header row** composable (mirrors `RepoHeaderRow` at sidebar density):
  `RepoAvatar(node.displayName)` + name (bold) + trailing pane count + leading
  `▾/▸` chevron; the whole row is `clickable` → `onToggle("repo:${node.repoKey}")`.
  No `⋯` (repos aren't structural nodes; the dashboard's repo header has no actions).
- The `when(row)` maps `Repo → (new RepoRow)`, `Ws → WorkspaceRow`,
  `Tab → TabRowView`, `PaneItem → PaneTreeRow(pane, promoted, parentTab, …)`.
- **Indents** shift one level deeper to sit under the repo header, mirroring the
  dashboard hierarchy (start padding, dp): repo header **12**, workspace **28**,
  tab header **44**, promoted pane **40** (one step shallower than a tab-nested
  pane — a workspace-direct child), nested pane **56**. (These may be tuned ±4 for
  visual harmony during implementation, keeping the relative depth order.)
- **Drop the redundant `⑂ repoName` trailing chip** on `WorkspaceRow` — the repo
  header now carries the repo identity, so the chip is duplicate. (The `#number`
  chip and pane count stay.)
- All other row chrome is unchanged: `⋯` row actions, long-press, `StatusGlyph`,
  the focus/last-opened `▎` highlight, tap-to-open (which closes the drawer).

## Data flow (unchanged plumbing)

`vm.repoTree` (`StateFlow<List<RepoNode>>`, already derived as
`tree.map { buildRepoTree(it) }`) feeds BOTH `flattenTree` call sites. `vm.collapsed`
is the single shared collapse set. No ViewModel change is required.

## Testing

New JVM unit tests for the pure functions (`app/app/src/test/.../FlattenTreeTest.kt`):
- Order: given a `repoTree` with two repos each with workspaces/panes, `flattenTree`
  emits `Repo, Ws, (promoted panes | Tab + panes)…` in repo order then
  within-repo workspace order — i.e. the exact `buildRepoTree` sequence.
- Collapse: a repo id in `collapsed` emits only its `Repo` row (subtree hidden); a
  workspace id in `collapsed` emits its `Ws` row but none of its children; a tab id
  in `collapsed` emits its `Tab` row but not its panes.
- Promotion: a single-tab workspace yields `PaneItem(promoted = true, parentTab = <tab>)`;
  a multi-tab workspace yields `Tab` rows with `PaneItem(promoted = false, parentTab = null)`.
- `treeRowKey`: distinct keys across a mixed tree (repo/ws/tab/pane), including the
  synthetic "(unknown)" workspace + orphan tab (blank ids) not colliding.

No new tests for the render composables (styling); verified by build + on-device.

## What is explicitly unchanged

- The sort logic (`buildRepoTree`, `attentionTier`, `workspaceTier`, `repoKeyFor`).
- The dashboard's card rendering and pane indent.
- `MoveDestinationSheet` (still `vm.tree`).
- `ViewModel` surface (`tree`, `repoTree`, `collapsed`, `toggleExpanded`).
- Single-child tab elision (`workspaceChildren`/`tabElided`), reused as-is.

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

## Live Validation (device)

- Open the drawer: repos appear with monogram avatars in the **same top-to-bottom
  order** as the dashboard; workspaces within a repo match the dashboard's order.
- Collapse a repo in the drawer → it's collapsed on the dashboard too (and vice
  versa). Same for a workspace.
- A single-tab workspace shows its pane hoisted directly under the workspace (no tab
  row) in both views; tapping a pane opens its terminal and closes the drawer.
- The workspace row no longer shows a duplicate `⑂ repo` chip.
