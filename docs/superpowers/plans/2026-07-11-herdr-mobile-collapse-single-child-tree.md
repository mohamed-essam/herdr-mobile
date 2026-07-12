# Collapse Single-Child Tree Levels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Elide a redundant tab row in both tree views (dashboard + sidebar) whenever it carries no grouping info — a single-pane tab or a single-tab workspace — promoting its panes to workspace-direct children.

**Architecture:** A pure, tested primitive in `TreeModel.kt` (`tabElided`, `workspaceChildren`) decides elision; both flatten functions consume it instead of iterating `w.tabs`. Promoted panes render one indent step shallower. The sidebar preserves the elided tab's action menu via a "Tab actions…" pivot on the promoted pane's sheet. No companion, wire, state, or collapse-model change.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- App-only change. No edits to `companion/`, `net/` wire types, state model, or the `collapsed` set (keyed by real IDs).
- Elision rule (verbatim): a tab row is elided when `tab.panes.isNotEmpty() && (ws.tabs.size == 1 || tab.panes.size == 1)`. Empty tabs are never elided.
- Per-tab aggressive: a single-pane tab elides even among multi-pane sibling tabs; a workspace can mix tab rows and promoted panes.
- Workspace and repo rows are always kept. Only the tab level elides. No repo/workspace-level elision.
- Promoted-pane indents: sidebar `start = 40.dp` (nested panes stay `52.dp`); dashboard pane `Box(start = 32.dp)` (nested panes stay `48.dp`).
- Promoted panes have no collapse affordance (always emitted when their workspace is expanded).
- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Test: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

---

### Task 1: Shared elision primitive + tests

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (append after `buildRepoTree`, end of file)
- Test: `app/app/src/test/java/dev/herdr/mobile/TreeCollapseTest.kt` (create)

**Interfaces:**
- Consumes: `WorkspaceNode(val ws: Workspace, val tabs: List<TabNode>)`, `TabNode(val tab: Tab, val panes: List<Pane>)` (already in `TreeModel.kt`).
- Produces:
  - `fun tabElided(ws: WorkspaceNode, tab: TabNode): Boolean`
  - `sealed interface WsChild` with `data class TabGroup(val tab: TabNode) : WsChild` and `data class PromotedPane(val pane: Pane, val parentTab: TabNode) : WsChild`
  - `fun workspaceChildren(ws: WorkspaceNode): List<WsChild>`

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/TreeCollapseTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.WsChild
import dev.herdr.mobile.ui.tabElided
import dev.herdr.mobile.ui.workspaceChildren
import org.junit.Assert.*
import org.junit.Test

class TreeCollapseTest {
    private fun pane(id: String, ws: String, tab: String) =
        Pane(paneId = id, workspaceId = ws, tabId = tab)

    private fun tabNode(tabId: String, ws: String, panes: List<Pane>) =
        TabNode(Tab(tabId = tabId, workspaceId = ws), panes)

    private fun wsNode(id: String, tabs: List<TabNode>) =
        WorkspaceNode(Workspace(workspaceId = id, label = id, number = 1), tabs)

    @Test fun singleTabWorkspaceElidesEvenWithManyPanes() {
        val t = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val ws = wsNode("w1", listOf(t))
        assertTrue(tabElided(ws, t))
    }

    @Test fun singlePaneTabElidesAmongSiblings() {
        val big = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val lone = tabNode("t2", "w1", listOf(pane("p3", "w1", "t2")))
        val ws = wsNode("w1", listOf(big, lone))
        assertFalse(tabElided(ws, big))
        assertTrue(tabElided(ws, lone))
    }

    @Test fun emptyTabNeverElides() {
        val empty = tabNode("t1", "w1", emptyList())
        val other = tabNode("t2", "w1", listOf(pane("p1", "w1", "t2"), pane("p2", "w1", "t2")))
        val ws = wsNode("w1", listOf(empty, other))
        assertFalse(tabElided(ws, empty))
        val soloEmpty = wsNode("w2", listOf(tabNode("t3", "w2", emptyList())))
        assertFalse(tabElided(soloEmpty, soloEmpty.tabs[0]))
    }

    @Test fun workspaceChildrenPromotesSingleTabPanes() {
        val t = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1"), pane("p3", "w1", "t1")))
        val children = workspaceChildren(wsNode("w1", listOf(t)))
        assertEquals(3, children.size)
        assertTrue(children.all { it is WsChild.PromotedPane })
        assertEquals(listOf("p1", "p2", "p3"), children.map { (it as WsChild.PromotedPane).pane.paneId })
        assertTrue(children.all { (it as WsChild.PromotedPane).parentTab.tab.tabId == "t1" })
    }

    @Test fun workspaceChildrenMixesGroupsAndPromotedInOrder() {
        val big = tabNode("t1", "w1", listOf(pane("p1", "w1", "t1"), pane("p2", "w1", "t1")))
        val lone = tabNode("t2", "w1", listOf(pane("p3", "w1", "t2")))
        val children = workspaceChildren(wsNode("w1", listOf(big, lone)))
        assertEquals(2, children.size)
        assertTrue(children[0] is WsChild.TabGroup)
        assertEquals("t1", (children[0] as WsChild.TabGroup).tab.tab.tabId)
        assertTrue(children[1] is WsChild.PromotedPane)
        assertEquals("p3", (children[1] as WsChild.PromotedPane).pane.paneId)
    }

    @Test fun unknownWorkspacePromotesOrphans() {
        val blankTab = tabNode("", "", listOf(pane("o1", "", ""), pane("o2", "", "")))
        val unknown = WorkspaceNode(
            Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE),
            listOf(blankTab),
        )
        val children = workspaceChildren(unknown)
        assertEquals(2, children.size)
        assertTrue(children.all { it is WsChild.PromotedPane })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TreeCollapseTest"`
Expected: FAIL — compilation error, `unresolved reference: tabElided` / `workspaceChildren` / `WsChild`.

- [ ] **Step 3: Implement the primitive**

Append to `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (after `buildRepoTree`, which ends the file):

```kotlin

/**
 * A tab row is elided (its panes promoted directly under the workspace) when it
 * carries no grouping information: it is the workspace's only tab, or it holds a
 * single pane. Empty tabs keep their row so the workspace is never silently blank.
 */
fun tabElided(ws: WorkspaceNode, tab: TabNode): Boolean =
    tab.panes.isNotEmpty() && (ws.tabs.size == 1 || tab.panes.size == 1)

/**
 * A workspace's display children after tab elision: either a visible tab (render
 * its row + its panes) or a promoted pane (its tab was elided; the pane is hoisted
 * to a workspace-direct child). Order follows the workspace's tab order.
 */
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

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TreeCollapseTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/TreeCollapseTest.kt
git commit -m "feat(app): tab-elision primitive for single-child tree collapse"
```

---

### Task 2: Dashboard rendering via workspaceChildren

No new unit tests: this is Compose wiring whose logic lives in Task 1's tested primitive. Verification is a clean `assembleDebug` plus the unchanged unit suite staying green.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (`DashRow.PaneRowItem` at line 381; `flattenRepoTree` at lines 385-405; the `is DashRow.PaneRowItem` render at lines 112-117)

**Interfaces:**
- Consumes: `workspaceChildren(ws)`, `WsChild.TabGroup`, `WsChild.PromotedPane` from Task 1 (same package `dev.herdr.mobile.ui`, no import needed).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add `promoted` to the dashboard pane row type**

In `DashboardScreen.kt`, change the `PaneRowItem` line inside `private sealed interface DashRow` (line 381) from:

```kotlin
    data class PaneRowItem(val pane: Pane) : DashRow
```

to:

```kotlin
    data class PaneRowItem(val pane: Pane, val promoted: Boolean) : DashRow
```

- [ ] **Step 2: Route `flattenRepoTree` through `workspaceChildren`**

Replace the whole `flattenRepoTree` function (lines 385-405) with:

```kotlin
/** A node is expanded unless its id is in [collapsed]; repos key on "repo:<key>". */
private fun flattenRepoTree(repos: List<RepoNode>, collapsed: Set<String>): List<DashRow> {
    val rows = mutableListOf<DashRow>()
    for (r in repos) {
        val rOpen = "repo:${r.repoKey}" !in collapsed
        val count = r.workspaces.sumOf { w -> w.tabs.sumOf { it.panes.size } }
        rows.add(DashRow.Repo(r, rOpen, count))
        if (!rOpen) continue
        for (w in r.workspaces) {
            val wOpen = w.ws.workspaceId !in collapsed
            rows.add(DashRow.Ws(w, wOpen))
            if (!wOpen) continue
            for (child in workspaceChildren(w)) {
                when (child) {
                    is WsChild.TabGroup -> {
                        val t = child.tab
                        val tOpen = t.tab.tabId !in collapsed
                        rows.add(DashRow.TabRow(t, tOpen))
                        if (tOpen) t.panes.forEach { rows.add(DashRow.PaneRowItem(it, promoted = false)) }
                    }
                    is WsChild.PromotedPane ->
                        rows.add(DashRow.PaneRowItem(child.pane, promoted = true))
                }
            }
        }
    }
    return rows
}
```

- [ ] **Step 3: Choose the pane indent by the promoted flag**

In the `LazyColumn`'s `when (row)` block, replace the `is DashRow.PaneRowItem` branch (lines 112-117) with:

```kotlin
                                // Promoted panes (their tab was elided) sit one step
                                // shallower — start=32 puts the card edge at the Tab-header
                                // level (44dp) as a workspace-direct child; nested panes
                                // stay at start=48, one step deeper than their Tab header.
                                is DashRow.PaneRowItem -> Box(
                                    Modifier.padding(start = if (row.promoted) 32.dp else 48.dp),
                                ) {
                                    PaneRow(row.pane) { p -> selected = p }
                                }
```

- [ ] **Step 4: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests (incl. `TreeCollapseTest`, `RepoTreeTest`) pass.

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): collapse single-child tabs in the dashboard tree"
```

---

### Task 3: Sidebar rendering + merged tab-actions

No new unit tests: Compose wiring over Task 1's tested primitive; verified by `assembleDebug` + green unit suite.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt` (the `RowAction` data class, lines 14-23)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (`Row.PaneRowItem` line 35; `flatten` lines 39-53; `items` dispatch line 94; `PaneTreeRow` lines 157-203; `RowActionSheet` PANE branch lines 252-256; `paneAction` lines 333-338)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (the `RowActionSheet(...)` call, lines 126-149)

**Interfaces:**
- Consumes: `workspaceChildren`, `WsChild.TabGroup`, `WsChild.PromotedPane`, `TabNode` (all package `dev.herdr.mobile.ui`); `RowActionSheet`, `paneAction`, `tabAction` (in `SidebarDrawer.kt`).
- Produces: `RowAction.mergedTab: RowAction?`; `RowActionSheet(..., onTabActions: () -> Unit = {})`.

- [ ] **Step 1: Add `mergedTab` to `RowAction`**

In `RowAction.kt`, replace the `RowAction` data class (lines 14-23) with:

```kotlin
data class RowAction(
    val kind: NodeKind,
    val id: String,
    val label: String,
    val paneCount: Int = 0,
    val tabCount: Int = 0,
    val isAgent: Boolean = false,
    val hasAgent: Boolean = false,
    val workspaceId: String = "",
    // For a promoted pane (its tab was elided), the parent tab's action so the
    // pane's sheet can pivot to tab operations. Null for normal panes.
    val mergedTab: RowAction? = null,
)
```

- [ ] **Step 2: Add `promoted`/`parentTab` to the sidebar pane row type**

In `SidebarDrawer.kt`, change the `PaneRowItem` line in `private sealed interface Row` (line 35) from:

```kotlin
    data class PaneRowItem(val pane: Pane) : Row
```

to:

```kotlin
    data class PaneRowItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?) : Row
```

- [ ] **Step 3: Route `flatten` through `workspaceChildren`**

Replace the whole `flatten` function (lines 38-53) with:

```kotlin
/** A node is expanded unless its id is in [collapsed]. */
private fun flatten(tree: List<WorkspaceNode>, collapsed: Set<String>): List<Row> {
    val rows = mutableListOf<Row>()
    for (w in tree) {
        val wOpen = w.ws.workspaceId !in collapsed
        rows.add(Row.Ws(w, wOpen))
        if (!wOpen) continue
        for (child in workspaceChildren(w)) {
            when (child) {
                is WsChild.TabGroup -> {
                    val t = child.tab
                    val tOpen = t.tab.tabId !in collapsed
                    rows.add(Row.TabRow(t, tOpen))
                    if (tOpen) t.panes.forEach {
                        rows.add(Row.PaneRowItem(it, promoted = false, parentTab = null))
                    }
                }
                is WsChild.PromotedPane ->
                    rows.add(Row.PaneRowItem(child.pane, promoted = true, parentTab = child.parentTab))
            }
        }
    }
    return rows
}
```

- [ ] **Step 4: Pass the new fields to `PaneTreeRow`**

In the `LazyColumn`'s `items` dispatch, change the `is Row.PaneRowItem` line (line 94) from:

```kotlin
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
```

to:

```kotlin
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, row.promoted, row.parentTab, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
```

- [ ] **Step 5: Update `PaneTreeRow` — signature, indent, merged action**

Replace the whole `PaneTreeRow` composable (lines 157-203) with:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneTreeRow(
    pane: Pane, promoted: Boolean, parentTab: TabNode?, dark: Boolean,
    focusedPaneId: String?, lastOpenedPaneId: String?, onSelectPane: (Pane) -> Unit,
    onRowAction: (RowAction) -> Unit,
) {
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    // A promoted pane carries its elided parent tab so its sheet can pivot to tab actions.
    val action = paneAction(pane, parentTab)
    // Shell panes are now attachable too (herdr terminal attach by terminal_id);
    // keep the dimmed styling as a cue but allow the tap. A pane with no
    // terminal_id is not attachable, so it stays non-clickable.
    val attachable = pane.terminalId.isNotBlank()
    val clickable = Modifier.fillMaxWidth()
        .let {
            if (attachable) {
                it.combinedClickable(onClick = { onSelectPane(pane) }, onLongClick = { onRowAction(action) })
            } else {
                it
            }
        }
    Row(
        clickable
            .then(if (marked) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            // Promoted panes (elided tab) sit at 40dp — one step shallower than a
            // tab-nested pane (52dp) — reading as a workspace-direct child.
            .padding(start = if (promoted) 40.dp else 52.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked) {
            Text("▎", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
        }
        StatusGlyph(pane.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        val label = pane.agent ?: "shell"
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.alpha(if (isAgent) 1f else 0.5f),
        )
        val base = pane.cwd.substringAfterLast('/')
        if (base.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(base, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(if (isAgent) 0.8f else 0.4f))
        }
        Spacer(Modifier.weight(1f))
        RowActionDots("pane actions") { onRowAction(action) }
    }
}
```

- [ ] **Step 6: `paneAction` attaches the parent tab's action**

Replace `paneAction` (lines 333-338) with:

```kotlin
private fun paneAction(pane: Pane, parentTab: TabNode? = null) = RowAction(
    kind = NodeKind.PANE,
    id = pane.paneId,
    label = pane.agent ?: "shell",
    isAgent = pane.agent != null,
    mergedTab = parentTab?.let { tabAction(it) },
)
```

- [ ] **Step 7: `RowActionSheet` — add the "Tab actions…" pivot**

In `RowActionSheet` add the parameter `onTabActions: () -> Unit = {}` to its signature (after `onClose: () -> Unit,`), and replace the `NodeKind.PANE` branch (lines 252-256) with:

```kotlin
                NodeKind.PANE -> {
                    if (target.mergedTab != null) SheetItem("Tab actions…", onTabActions)
                    SheetItem("Split right", onClick = { onSplit("right") })
                    SheetItem("Split down", onClick = { onSplit("down") })
                    SheetItem("Move…", onMove)
                }
```

The updated signature reads:

```kotlin
fun RowActionSheet(
    target: RowAction,
    onNewTab: () -> Unit,
    onNewAgent: () -> Unit,
    onNewShell: () -> Unit,
    onSplit: (String) -> Unit,   // "right" | "down"
    onMove: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onTabActions: () -> Unit = {},
    onDismiss: () -> Unit,
) {
```

- [ ] **Step 8: Wire `onTabActions` from `DashboardScreen`**

In `DashboardScreen.kt`, in the `actionTarget?.let { target -> RowActionSheet(...) }` block, add the `onTabActions` argument. Change the `onClose = { ... }` argument's closing so `onTabActions` sits between `onClose` and `onDismiss`. Insert, right before `onDismiss = { actionTarget = null },` (line 148):

```kotlin
                onTabActions = { target.mergedTab?.let { actionTarget = it } },
```

- [ ] **Step 9: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests pass.

- [ ] **Step 10: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/RowAction.kt app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): collapse single-child tabs in the sidebar with tab-action pivot"
```

---

## Notes for the implementer

- All new symbols (`tabElided`, `workspaceChildren`, `WsChild`) live in package `dev.herdr.mobile.ui`, the same package as `DashboardScreen.kt` and `SidebarDrawer.kt` — no imports needed there. `TreeCollapseTest` is in `dev.herdr.mobile` and imports them explicitly (all are `public`).
- Do not touch `buildTree` or `buildRepoTree` — they still sort/group; only the flatten step changes.
- Do not add a collapse key for promoted panes; they are always emitted when the workspace is expanded.
- The `(unknown)` synthetic workspace has one blank tab and therefore elides automatically — expect its orphan panes to render with no "—" tab row. This is intended.
