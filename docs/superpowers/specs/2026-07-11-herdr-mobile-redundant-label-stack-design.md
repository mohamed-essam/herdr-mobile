# herdr-mobile — Collapse the Redundant Label Stack — Design Spec

**Date:** 2026-07-11
**Component:** Android app only (`app/`).
**Source:** UI/UX audit finding #9 (with #27 as a natural companion) — a
single-workspace repo stacks the same name up to three times: repo header,
workspace header, and (on the dashboard) the pane card's bold title.

## Goal

Show a repo's name **once**. Two redundancies to remove:
1. **Repo header ↔ workspace header** repeat (both views) when a repo has a single
   workspace whose label adds nothing over the repo name.
2. **Dashboard pane card leads with the cwd basename** (= the repo name a third
   time). Lead with the agent instead (which is what the sidebar already does —
   this also closes the #27 field-emphasis inversion).

Because both views now share `flattenTree`, the structural change lives there and
both benefit; neither can drift.

## Global Constraints

- App-only; no companion/protocol/theme/sort changes.
- Fold is **loss-free**: only fold a repo+workspace when the sole workspace's label
  is blank or equals the repo's `displayName`. A distinct workspace label keeps both
  rows.
- Multi-workspace repos are unchanged (repo header + one `Ws` row per workspace).
- The merged row must retain the workspace's `#number`, status (sidebar), and
  actions (sidebar `⋯` → New tab/New agent/Rename/Close). The synthetic
  `(unknown)` orphan repo (blank workspace id) gets no `⋯` (existing blank-id guard).
- Collapse key for the merged row is the **workspace id** (its children gate on it).

## Part 1 — Fold predicate + shared flatten (`TreeModel.kt`)

```kotlin
/** When a repo has exactly one workspace whose label adds nothing over the repo
 *  name, the repo and workspace header rows are redundant — return that workspace
 *  so they can fold into one row. Null when the repo has multiple workspaces or
 *  its sole workspace carries a distinct label (folding would lose that label). */
fun foldableWorkspace(repo: RepoNode): WorkspaceNode? {
    if (repo.workspaces.size != 1) return null
    val w = repo.workspaces[0]
    return if (w.ws.label.isBlank() || w.ws.label == repo.displayName) w else null
}
```

`TreeRow` gains a merged variant and `PaneItem` gains `repoLabel`:

```kotlin
sealed interface TreeRow {
    data class Repo(val node: RepoNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    // A repo folded with its sole (label-redundant) workspace into one header.
    data class RepoWs(val repo: RepoNode, val ws: WorkspaceNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : TreeRow
    data class Tab(val node: TabNode, val expanded: Boolean) : TreeRow
    data class PaneItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?, val repoLabel: String) : TreeRow
}
```

`flattenTree` chooses per repo; child emission is shared (no duplication):

```kotlin
private fun MutableList<TreeRow>.addWorkspaceChildren(
    w: WorkspaceNode, repoLabel: String, collapsed: Set<String>,
) {
    for (child in workspaceChildren(w)) {
        when (child) {
            is WsChild.TabGroup -> {
                val t = child.tab
                val tOpen = t.tab.tabId !in collapsed
                add(TreeRow.Tab(t, tOpen))
                if (tOpen) t.panes.forEach {
                    add(TreeRow.PaneItem(it, promoted = false, parentTab = null, repoLabel = repoLabel))
                }
            }
            is WsChild.PromotedPane ->
                add(TreeRow.PaneItem(child.pane, promoted = true, parentTab = child.parentTab, repoLabel = repoLabel))
        }
    }
}

fun flattenTree(repos: List<RepoNode>, collapsed: Set<String>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    for (r in repos) {
        val count = r.workspaces.sumOf { w -> w.tabs.sumOf { it.panes.size } }
        val fold = foldableWorkspace(r)
        if (fold != null) {
            val wOpen = fold.ws.workspaceId !in collapsed
            rows.add(TreeRow.RepoWs(r, fold, wOpen, count))
            if (wOpen) rows.addWorkspaceChildren(fold, r.displayName, collapsed)
        } else {
            val rOpen = "repo:${r.repoKey}" !in collapsed
            rows.add(TreeRow.Repo(r, rOpen, count))
            if (!rOpen) continue
            for (w in r.workspaces) {
                val wOpen = w.ws.workspaceId !in collapsed
                rows.add(TreeRow.Ws(w, wOpen))
                if (wOpen) rows.addWorkspaceChildren(w, r.displayName, collapsed)
            }
        }
    }
    return rows
}

fun treeRowKey(row: TreeRow): String = when (row) {
    is TreeRow.Repo -> "r:" + row.node.repoKey
    is TreeRow.RepoWs -> "rw:" + row.repo.repoKey
    is TreeRow.Ws -> "w:" + row.node.ws.workspaceId
    is TreeRow.Tab -> "t:" + row.node.tab.tabId
    is TreeRow.PaneItem -> "p:" + row.pane.paneId
}
```

## Part 2 — Agent-first pane labels (`PaneRow.kt`)

Two pure, testable helpers de-duplicate the pane label against the repo:

```kotlin
/** Pane's primary label, de-duplicated against its enclosing [repoLabel]. Agent
 *  panes lead with the agent; a shell leads with a differentiating cwd subdir,
 *  else the generic "shell". Never repeats the repo name. */
fun panePrimaryLabel(pane: Pane, repoLabel: String): String {
    pane.agent?.let { return it }
    val base = pane.cwd.substringAfterLast('/')
    return if (base.isNotBlank() && base != repoLabel) base else "shell"
}

/** Secondary (dim) line: the cwd subdir when it adds info beyond the repo label
 *  for an agent pane; null when redundant or already carried by the title. */
fun paneSecondaryLabel(pane: Pane, repoLabel: String): String? {
    if (pane.agent == null) return null
    val base = pane.cwd.substringAfterLast('/')
    return if (base.isNotBlank() && base != repoLabel) base else null
}
```

Outcomes: agent in repo root → `claude` / (none); agent in `src/` → `claude` / `src`;
shell in root → `shell`; shell in `src/` → `src`.

`PaneRow` signature becomes `fun PaneRow(pane: Pane, repoLabel: String, onClick: (Pane) -> Unit)`.
The bold `title` uses `panePrimaryLabel(pane, repoLabel)`; the dim `subtitle` uses
`paneSecondaryLabel(pane, repoLabel)` and the subtitle line is omitted when null.
The left status accent bar and the trailing `StatusIndicator` are unchanged.

## Part 3 — Render the merged row + reuse pane helpers

### Dashboard (`DashboardScreen.kt`)
- `when` gains `is TreeRow.RepoWs -> RepoWsHeaderRow(row) { vm.toggleExpanded(row.ws.ws.workspaceId) }`.
- New `RepoWsHeaderRow(row: TreeRow.RepoWs, onToggle)`: chevron + `RepoAvatar(row.repo.displayName)` +
  name (titleMedium bold) + `#number` (if `> 0`) + trailing pane count — the repo header's
  avatar/weight with the workspace's ordinal. (No status glyph/actions — the dashboard's
  headers never carry them.)
- `TreeRow.PaneItem` arm passes the label: `PaneRow(row.pane, row.repoLabel) { p -> selected = p }`.
  Existing `Repo`/`Ws` arms unchanged (multi-workspace repos).

### Sidebar (`SidebarDrawer.kt`)
- `when` gains `is TreeRow.RepoWs -> RepoWsRow(row, dark, onToggle, onRowAction)`.
- New `RepoWsRow`: chevron + `RepoAvatar` + `StatusGlyph(ws.agentStatus)` + name + `#number` +
  pane count + `RowActionDots("workspace actions")` → `onRowAction(wsAction(row.ws))`, and
  long-press on the row does the same — EXCEPT when `row.ws.ws.workspaceId` is blank (orphan),
  where the `⋯`/long-press are omitted (mirrors `onRowAction`'s existing blank-id guard).
  Toggle = `onToggle(row.ws.ws.workspaceId)`. Indent: repo level (start 12dp).
- `PaneTreeRow` gains a `repoLabel: String` param; its label uses `panePrimaryLabel` and the
  trailing dim cwd uses `paneSecondaryLabel` (dropped when redundant). The `PaneItem` arm
  passes `row.repoLabel`.

## Testing

- `FlattenTreeTest` (extend): a single-workspace repo whose ws label == repo name emits ONE
  `RepoWs` (no separate `Repo`/`Ws`) followed by its children; a single-workspace repo whose
  ws label DIFFERS emits `Repo` + `Ws` (no fold); a two-workspace repo emits `Repo` + two `Ws`;
  `PaneItem.repoLabel` equals the enclosing repo `displayName`; collapsing the `RepoWs` row
  (its workspace id) hides its children; `treeRowKey` unique across a tree containing a `RepoWs`.
- New `PaneLabelTest` (or extend `PaneStatusTest`): `panePrimaryLabel`/`paneSecondaryLabel`
  for agent-in-root, agent-in-subdir, shell-in-root, shell-in-subdir, and blank cwd.
- Render composables: no unit tests (styling); verified by build + on-device.

## What is explicitly unchanged

- The sort (`buildRepoTree` etc.), single-child tab elision (`workspaceChildren`/`tabElided`),
  the `ViewModel` surface, and `MoveDestinationSheet`.
- Multi-workspace repos render exactly as today.
- `StatusIndicator`/`StatusGlyph`, the focus `▎` highlight, tap-to-open, and the action sheets.

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

## Live Validation (device)

- A single-workspace repo shows ONE header (`[WT] wt-dashboards  #4`), not repo-then-workspace.
- Its pane cards read `claude` / `shell` (agent-first), not a third `wt-dashboards`; a pane in a
  subdirectory shows that subdir as secondary.
- In the drawer, the merged row's `⋯` still opens the workspace actions (New tab / New agent /
  Rename / Close); the `(unknown)` orphan row has none.
- A repo with two worktree-workspaces still shows the repo header + both workspace rows.
