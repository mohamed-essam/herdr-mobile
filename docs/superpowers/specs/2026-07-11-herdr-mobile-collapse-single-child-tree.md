# Collapse Single-Child Tree Levels — Design Spec

**Date:** 2026-07-11
**Component:** herdr-mobile — Android app only (`ui/`); no companion change.

## Goal

Reduce visual clutter in both tree views (main dashboard and sidebar drawer) by
eliding a redundant **tab** row whenever it carries no grouping information:

- a **single-pane tab** merges up (its one pane shows directly under the workspace), and
- a **single-tab workspace** merges up (its sole tab's panes show directly under the workspace).

Both rules reduce to one operation: **elide the tab row and promote its panes to
workspace-direct children.** The workspace and repo rows always stay — they carry
number, repo name, status, and (sidebar) actions that a pane row does not.

## Decisions (grilled)

- **Per-tab, aggressive.** A single-pane tab always elides, even when its sibling
  tabs are multi-pane. A workspace can therefore show a mix of tab rows (for
  multi-pane tabs) and loose promoted panes (for single-pane tabs), all as
  workspace-direct children at the same indent.
- **Sidebar keeps tab actions on the promoted pane.** Eliding a tab row would
  otherwise drop that tab's action menu. The promoted pane's action sheet gains a
  single **"Tab actions…"** pivot that re-opens the sheet as the parent tab,
  keeping New shell / New agent / Rename tab / Close tab reachable.
- **Empty tabs are not elided.** A tab with zero panes keeps its row so the
  workspace is never silently blank.

## Architecture

Purely a presentation-layer change. The underlying `buildTree` / `buildRepoTree`
model, the `collapsed` set (keyed by real IDs), and all wire/state code are
untouched. Only the two flatten functions change, plus one shared pure primitive
they both consume.

## Part 1 — Shared elision primitive (`ui/TreeModel.kt`)

Add two pure, public, tested declarations:

```kotlin
/** A tab row is elided (its panes promoted directly under the workspace) when it
 *  carries no grouping information: it is the workspace's only tab, or it holds a
 *  single pane. Empty tabs keep their row so the workspace is never silently blank. */
fun tabElided(ws: WorkspaceNode, tab: TabNode): Boolean =
    tab.panes.isNotEmpty() && (ws.tabs.size == 1 || tab.panes.size == 1)

/** Ordered display children of a workspace after tab elision: either a visible tab
 *  (render its row + its panes) or a promoted pane (tab elided, pane hoisted to a
 *  workspace-direct child). Order follows the workspace's tab order. */
sealed interface WsChild {
    data class TabGroup(val tab: TabNode) : WsChild
    data class PromotedPane(val pane: Pane, val parentTab: TabNode) : WsChild
}

fun workspaceChildren(ws: WorkspaceNode): List<WsChild> =
    ws.tabs.flatMap { t ->
        if (tabElided(ws, t)) t.panes.map { WsChild.PromotedPane(it, t) }
        else listOf(WsChild.TabGroup(t))
    }
```

Notes:
- The synthetic `(unknown)` workspace has exactly one (blank) tab holding all
  orphan panes; `workspaceChildren` elides it, so orphans render directly with no
  "—" tab row.
- `PromotedPane.parentTab` is retained so the sidebar can build the tab's action
  menu (Part 3).

## Part 2 — Sidebar rendering (`ui/SidebarDrawer.kt`)

`flatten` stops iterating `w.tabs` and iterates `workspaceChildren(w)`:

- `WsChild.TabGroup` → `Row.TabRow(tab, expanded)` as today, then (if expanded)
  its panes as **nested** `Row.PaneRowItem`.
- `WsChild.PromotedPane` → a **promoted** `Row.PaneRowItem` (no preceding tab row),
  always emitted (a lone pane needs no collapse).

`Row.PaneRowItem` changes:

```kotlin
data class PaneRowItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?) : Row
```

Indentation in `PaneTreeRow`: nested panes keep `start = 52.dp`; promoted panes
use `start = 40.dp` (aligned just under the tab-row content, one step shallower
than a nested pane, reading as a workspace-direct child). Pass the `promoted` flag
into `PaneTreeRow` to select the start padding.

`rowKey` for a pane stays `"p:" + pane.paneId` (unaffected by `promoted`).

## Part 3 — Sidebar merged tab-actions (`ui/RowAction.kt`, `ui/SidebarDrawer.kt`, `ui/DashboardScreen.kt`)

`RowAction` gains an optional parent-tab action:

```kotlin
val mergedTab: RowAction? = null
```

`paneAction` gains an optional parent tab; when the pane came from an elided tab
it attaches the tab's `RowAction`:

```kotlin
private fun paneAction(pane: Pane, parentTab: TabNode? = null) = RowAction(
    kind = NodeKind.PANE,
    id = pane.paneId,
    label = pane.agent ?: "shell",
    isAgent = pane.agent != null,
    mergedTab = parentTab?.let { tabAction(it) },
)
```

`PaneTreeRow` builds its action target as `paneAction(pane, parentTab)` (passing the
`parentTab` from the promoted row; `null` for nested panes).

`RowActionSheet`: for a `NodeKind.PANE` target with `mergedTab != null`, prepend a
single item **"Tab actions…"** whose click invokes a new `onTabActions` callback.
The pane's own Split / Move / Rename / Close items are unchanged.

```kotlin
// in RowActionSheet(...), add param: onTabActions: () -> Unit = {}
NodeKind.PANE -> {
    if (target.mergedTab != null) SheetItem("Tab actions…", onTabActions)
    SheetItem("Split right", onClick = { onSplit("right") })
    SheetItem("Split down", onClick = { onSplit("down") })
    SheetItem("Move…", onMove)
}
```

`DashboardScreen` wires `onTabActions = { target.mergedTab?.let { actionTarget = it } }`
in the `actionTarget?.let { target -> RowActionSheet(...) }` block. Re-opening the
sheet with the tab's `RowAction` reuses the existing `NodeKind.TAB` branch and all
of its already-wired callbacks (New shell, New agent, Rename, Close) — no new
per-action plumbing.

## Part 4 — Dashboard rendering (`ui/DashboardScreen.kt`)

`flattenRepoTree` stops iterating `w.tabs` and iterates `workspaceChildren(w)`:

- `WsChild.TabGroup` → `DashRow.TabRow(tab, expanded)` as today, then (if expanded)
  its panes as **nested** `DashRow.PaneRowItem`.
- `WsChild.PromotedPane` → a **promoted** `DashRow.PaneRowItem` (no tab row),
  always emitted.

`DashRow.PaneRowItem` gains `promoted: Boolean`. In the `LazyColumn`, the pane leaf
chooses its indent by the flag:

```kotlin
is DashRow.PaneRowItem -> Box(Modifier.padding(start = if (row.promoted) 32.dp else 48.dp)) {
    PaneRow(row.pane) { p -> selected = p }
}
```

A promoted pane's card edge (`start=32` + `PaneRow`'s own 12dp) lands at ~44dp,
aligning with the Tab-header level, so it reads as a workspace-direct child rather
than one step deeper. The dashboard has no row actions, so nothing else changes.

`dashRowKey` for a pane stays `"p:" + pane.paneId`.

## Testing (`app/src/test/java/dev/herdr/mobile/ui/TreeCollapseTest.kt`)

New test file in package `dev.herdr.mobile.ui` (so it sees the model types):

- **`tabElided` truth table:**
  - single-tab workspace, tab with panes → `true`.
  - multi-tab workspace, a tab with exactly 1 pane → `true` for that tab.
  - multi-tab workspace, a tab with ≥2 panes → `false`.
  - any tab with 0 panes → `false`.
- **`workspaceChildren`:**
  - single-tab workspace with 3 panes → three `PromotedPane`, `parentTab` = that tab,
    order = pane order.
  - workspace with a 2-pane tab **and** a 1-pane tab → a `TabGroup` for the 2-pane
    tab and a `PromotedPane` for the 1-pane tab, in tab order.
  - the `(unknown)` synthetic workspace (one blank tab, ≥1 orphan pane) →
    all `PromotedPane`, no `TabGroup`.

No companion tests (app-only change).

## Touch Points

- `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` — `tabElided`,
  `WsChild`, `workspaceChildren`.
- `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` — `flatten` via
  `workspaceChildren`; `Row.PaneRowItem` gains `promoted`/`parentTab`; promoted
  indent; `paneAction(pane, parentTab)`.
- `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt` — `RowAction.mergedTab`.
- `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` — `flattenRepoTree`
  via `workspaceChildren`; `DashRow.PaneRowItem` gains `promoted`; promoted indent;
  `RowActionSheet` "Tab actions…" pivot + `onTabActions` wiring.
- `app/app/src/test/java/dev/herdr/mobile/ui/TreeCollapseTest.kt` — new tests.

## Non-Goals

- No change to repo grouping or the workspace/repo rows themselves.
- No eliding of the repo level (single-workspace repos keep their repo row) or the
  workspace level (single-tab workspaces keep their workspace row — only the tab
  elides).
- No change to sort order, collapse-state model, or any companion/wire code.
- No collapse affordance for promoted panes (a lone pane needs none).

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
