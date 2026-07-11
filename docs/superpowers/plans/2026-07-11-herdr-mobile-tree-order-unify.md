# Unify Sidebar & Dashboard Tree Order — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix audit finding #10 — make the sidebar render the same repo-grouped tree, in the same attention+recency order, as the dashboard, by having both consume ONE shared flatten function.

**Architecture:** Extract a shared `TreeRow` model + `flattenTree(repos, collapsed)` + `treeRowKey` into `TreeModel.kt` (the same single-source pattern as `workspaceChildren`). The dashboard drops its private `flattenRepoTree`; the sidebar drops its private flat `flatten`, switches to `List<RepoNode>`, and gains repo header rows. Neither view can drift again.

**Tech Stack:** Kotlin / Jetpack Compose; JUnit (JVM unit tests).

## Global Constraints

- App-only; no companion/protocol/theme changes.
- Do NOT change the sort (`buildRepoTree`/`attentionTier`/`workspaceTier`/`repoKeyFor` stay exactly as is). The sidebar adopts the existing order.
- Repo collapse uses the SAME key as the dashboard: `"repo:<repoKey>"` in `vm.collapsed`.
- The shared `TreeRow.PaneItem` carries `parentTab: TabNode?` (sidebar's tab-action pivot); the dashboard ignores it.
- `MoveDestinationSheet` keeps using `vm.tree` (out of scope).
- Sidebar indent scale (start padding, dp): repo header **12**, workspace **28**, tab header **44**, promoted pane **40**, nested pane **56**.

---

## Task 1: Shared tree-row model + flatten (TreeModel.kt)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (add `TreeRow`, `flattenTree`, `treeRowKey`)
- Test: `app/app/src/test/java/dev/herdr/mobile/FlattenTreeTest.kt` (new)

**Interfaces:**
- Produces: `sealed interface TreeRow { Repo(node: RepoNode, expanded: Boolean, paneCount: Int); Ws(node: WorkspaceNode, expanded: Boolean); Tab(node: TabNode, expanded: Boolean); PaneItem(pane: Pane, promoted: Boolean, parentTab: TabNode?) }`; `fun flattenTree(repos: List<RepoNode>, collapsed: Set<String>): List<TreeRow>`; `fun treeRowKey(row: TreeRow): String`. Tasks 2 and 3 consume these.

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/FlattenTreeTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.ui.RepoNode
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.TreeRow
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.flattenTree
import dev.herdr.mobile.ui.treeRowKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlattenTreeTest {
    private fun pane(id: String, ws: String, tab: String) =
        Pane(paneId = id, workspaceId = ws, tabId = tab, terminalId = "term-$id")
    private fun tnode(id: String, ws: String, num: Int, panes: List<Pane>) =
        TabNode(Tab(tabId = id, workspaceId = ws, number = num), panes)
    private fun wnode(id: String, num: Int, tabs: List<TabNode>) =
        WorkspaceNode(Workspace(workspaceId = id, label = id, number = num), tabs)

    // repoA: one ws, one tab (elided → 2 promoted panes)
    private val p1 = pane("p1", "wA", "tA"); private val p2 = pane("p2", "wA", "tA")
    private val tA = tnode("tA", "wA", 1, listOf(p1, p2))
    private val wA = wnode("wA", 1, listOf(tA))
    // repoB: one ws, two tabs — tB1 visible (2 nested panes), tB2 elided (1 promoted pane)
    private val p3 = pane("p3", "wB", "tB1"); private val p4 = pane("p4", "wB", "tB1")
    private val p5 = pane("p5", "wB", "tB2")
    private val tB1 = tnode("tB1", "wB", 1, listOf(p3, p4))
    private val tB2 = tnode("tB2", "wB", 2, listOf(p5))
    private val wB = wnode("wB", 2, listOf(tB1, tB2))
    private val repos = listOf(
        RepoNode("repoA", "repoA", listOf(wA)),
        RepoNode("repoB", "repoB", listOf(wB)),
    )

    @Test fun flattensInRepoThenWorkspaceOrderWithPromotion() {
        val rows = flattenTree(repos, emptySet())
        // repoA: Repo, Ws, 2 promoted panes; repoB: Repo, Ws, Tab(tB1), p3, p4, promoted p5
        assertTrue(rows[0] is TreeRow.Repo && (rows[0] as TreeRow.Repo).node.repoKey == "repoA")
        assertEquals(2, (rows[0] as TreeRow.Repo).paneCount)
        assertTrue(rows[1] is TreeRow.Ws && (rows[1] as TreeRow.Ws).node.ws.workspaceId == "wA")
        val a1 = rows[2] as TreeRow.PaneItem; val a2 = rows[3] as TreeRow.PaneItem
        assertEquals("p1", a1.pane.paneId); assertTrue(a1.promoted); assertEquals("tA", a1.parentTab?.tab?.tabId)
        assertEquals("p2", a2.pane.paneId); assertTrue(a2.promoted)
        assertTrue(rows[4] is TreeRow.Repo && (rows[4] as TreeRow.Repo).node.repoKey == "repoB")
        assertTrue(rows[5] is TreeRow.Ws && (rows[5] as TreeRow.Ws).node.ws.workspaceId == "wB")
        assertTrue(rows[6] is TreeRow.Tab && (rows[6] as TreeRow.Tab).node.tab.tabId == "tB1")
        val n3 = rows[7] as TreeRow.PaneItem; val n4 = rows[8] as TreeRow.PaneItem
        assertEquals("p3", n3.pane.paneId); assertTrue(!n3.promoted); assertEquals(null, n3.parentTab)
        assertEquals("p4", n4.pane.paneId)
        val prom5 = rows[9] as TreeRow.PaneItem
        assertEquals("p5", prom5.pane.paneId); assertTrue(prom5.promoted); assertEquals("tB2", prom5.parentTab?.tab?.tabId)
        assertEquals(10, rows.size)
    }

    @Test fun collapsedRepoHidesSubtree() {
        val rows = flattenTree(repos, setOf("repo:repoA"))
        // repoA collapsed → only its Repo row; repoB fully expanded after it
        assertTrue(rows[0] is TreeRow.Repo && (rows[0] as TreeRow.Repo).node.repoKey == "repoA")
        assertTrue((rows[0] as TreeRow.Repo).expanded.not())
        assertTrue(rows[1] is TreeRow.Repo && (rows[1] as TreeRow.Repo).node.repoKey == "repoB")
    }

    @Test fun collapsedWorkspaceHidesChildren() {
        val rows = flattenTree(repos, setOf("wB"))
        // wB present but none of its tabs/panes
        assertTrue(rows.any { it is TreeRow.Ws && it.node.ws.workspaceId == "wB" && !it.expanded })
        assertTrue(rows.none { it is TreeRow.PaneItem && it.pane.paneId in setOf("p3", "p4", "p5") })
        assertTrue(rows.none { it is TreeRow.Tab && it.node.tab.tabId == "tB1" })
    }

    @Test fun collapsedTabHidesItsPanesOnly() {
        val rows = flattenTree(repos, setOf("tB1"))
        assertTrue(rows.any { it is TreeRow.Tab && it.node.tab.tabId == "tB1" && !it.expanded })
        assertTrue(rows.none { it is TreeRow.PaneItem && it.pane.paneId in setOf("p3", "p4") })
        assertTrue(rows.any { it is TreeRow.PaneItem && it.pane.paneId == "p5" }) // tB2 promoted, unaffected
    }

    @Test fun rowKeysAreUnique() {
        val keys = flattenTree(repos, emptySet()).map { treeRowKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (`TreeRow`/`flattenTree`/`treeRowKey` unresolved)

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.FlattenTreeTest"`
Expected: compile failure (unresolved references).

- [ ] **Step 3: Add the shared model to `TreeModel.kt`**

Append to `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`:

```kotlin
/** One flattened, renderable row of the repo→workspace→tab→pane tree, shared by
 *  the dashboard and the sidebar so the two views can never disagree on order or
 *  structure. Each screen renders these rows in its own chrome. */
sealed interface TreeRow {
    data class Repo(val node: RepoNode, val expanded: Boolean, val paneCount: Int) : TreeRow
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : TreeRow
    data class Tab(val node: TabNode, val expanded: Boolean) : TreeRow
    // promoted: its tab was elided and it's hoisted to a workspace-direct child.
    // parentTab: that elided tab (for the sidebar's tab-action pivot); null otherwise.
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

- [ ] **Step 4: Run — expect PASS**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.FlattenTreeTest"`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/FlattenTreeTest.kt
git commit -m "feat(app): shared flattenTree/TreeRow for both tree views"
```

---

## Task 2: Dashboard consumes the shared flatten (DashboardScreen.kt)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`

**Interfaces:**
- Consumes: `TreeRow`, `flattenTree`, `treeRowKey` (Task 1). No new public surface.

- [ ] **Step 1: Swap the flatten + row mapping**

In the `LazyColumn` items block (currently lines ~102-119), replace `flattenRepoTree`/`dashRowKey`/`DashRow.*` with the shared names:

```kotlin
val rows = flattenTree(repoTree, collapsed)
LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
    items(rows, key = { treeRowKey(it) }) { row ->
        when (row) {
            is TreeRow.Repo -> RepoHeaderRow(row) { vm.toggleExpanded("repo:${row.node.repoKey}") }
            is TreeRow.Ws -> WsHeaderRow(row) { vm.toggleExpanded(row.node.ws.workspaceId) }
            is TreeRow.Tab -> TabHeaderRow(row) { vm.toggleExpanded(row.node.tab.tabId) }
            is TreeRow.PaneItem -> Box(
                Modifier.padding(start = if (row.promoted) 32.dp else 48.dp),
            ) {
                PaneRow(row.pane) { p -> selected = p }
            }
        }
    }
}
```

- [ ] **Step 2: Delete the now-unused private model + update composable param types**

- Delete the private `sealed interface DashRow { … }`, `private fun flattenRepoTree(…)`, and `private fun dashRowKey(…)` (lines ~382-424).
- Change the three header composables' parameter types (bodies unchanged):
  - `private fun RepoHeaderRow(row: DashRow.Repo, onToggle: () -> Unit)` → `row: TreeRow.Repo`
  - `private fun WsHeaderRow(row: DashRow.Ws, onToggle: () -> Unit)` → `row: TreeRow.Ws`
  - `private fun TabHeaderRow(row: DashRow.TabRow, onToggle: () -> Unit)` → `row: TreeRow.Tab`
- `TreeRow.Repo` exposes `node`/`expanded`/`paneCount` and `TreeRow.Ws`/`TreeRow.Tab` expose `node`/`expanded` — same field names the bodies already use, so no body edits.

- [ ] **Step 3: Build + full unit suite — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass (a `DashboardViewModelTest` MockWebServer timeout is a known unrelated flake — rerun if hit).

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "refactor(app): dashboard renders from shared flattenTree"
```

---

## Task 3: Sidebar adopts the repo-grouped tree (SidebarDrawer.kt)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (the `SidebarDrawer(...)` call site + remove the now-unused `tree` collect)

**Interfaces:**
- Consumes: `TreeRow`, `flattenTree`, `treeRowKey` (Task 1); `RepoNode` (existing); `RepoAvatar` (existing, same package).

- [ ] **Step 1: Change the SidebarDrawer signature + render from shared rows**

- Change the parameter `tree: List<WorkspaceNode>` to `repos: List<RepoNode>`.
- Delete the private `sealed interface Row { … }`, `private fun flatten(…)`, and `private fun rowKey(…)`.
- Replace the `LazyColumn` body:

```kotlin
val rows = flattenTree(repos, collapsed)
LazyColumn(Modifier.fillMaxSize()) {
    items(rows, key = { treeRowKey(it) }) { row ->
        when (row) {
            is TreeRow.Repo -> RepoRow(row, onToggle)
            is TreeRow.Ws -> WorkspaceRow(row, dark, onToggle, onRowAction)
            is TreeRow.Tab -> TabRowView(row, dark, onToggle, onRowAction)
            is TreeRow.PaneItem -> PaneTreeRow(row.pane, row.promoted, row.parentTab, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
        }
    }
}
```

- Update `WorkspaceRow`/`TabRowView` parameter types from `Row.Ws`/`Row.TabRow` to `TreeRow.Ws`/`TreeRow.Tab` (field names `node`/`expanded` are identical, bodies unchanged except the indent + chip edits below).

- [ ] **Step 2: Add the `RepoRow` composable**

```kotlin
@Composable
private fun RepoRow(row: TreeRow.Repo, onToggle: (String) -> Unit) {
    val node = row.node
    Row(
        Modifier.fillMaxWidth()
            .clickable { onToggle("repo:${node.repoKey}") }
            .padding(start = 12.dp, end = 12.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RepoAvatar(node.displayName)
        Spacer(Modifier.width(10.dp))
        Text(
            node.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${row.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
```

Add imports if missing: `androidx.compose.foundation.clickable` (already used), `androidx.compose.ui.text.style.TextOverflow`.

- [ ] **Step 3: Apply the indent scale + drop the redundant repo chip**

- `WorkspaceRow`: change `.padding(horizontal = 12.dp, vertical = 10.dp)` to `.padding(start = 28.dp, end = 12.dp).padding(vertical = 10.dp)`. **Delete** the trailing worktree chip block:
  ```kotlin
  ws.worktree?.repoName?.let { repo ->
      Text("⑂ $repo", …)
      Spacer(Modifier.width(8.dp))
  }
  ```
  (Keep the `#number` chip, the `paneCount`, and `RowActionDots`.)
- `TabRowView`: change `.padding(start = 32.dp, end = 12.dp)` to `.padding(start = 44.dp, end = 12.dp)`.
- `PaneTreeRow`: change `.padding(start = if (promoted) 40.dp else 52.dp, end = 12.dp)` to `.padding(start = if (promoted) 40.dp else 56.dp, end = 12.dp)`.

- [ ] **Step 4: Update the call site in `DashboardScreen.kt`**

- The `SidebarDrawer(...)` call (~line 77): change `tree = tree` to `repos = repoTree`.
- Remove the now-unused `val tree by vm.tree.collectAsState()` (~line 54) IF it has no other reader. (Note: `MoveDestinationSheet` uses a separate `val moveTree by vm.tree.collectAsState()` at ~line 223 — leave that.) If the Kotlin compiler still sees `tree` referenced elsewhere, keep it; otherwise delete the unused line to avoid a warning.

- [ ] **Step 5: Build + full unit suite — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): sidebar renders the repo-grouped tree in dashboard order"
```

---

## Final Verification (whole branch)

- `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Live on device:
  - Open the drawer — repos appear with monogram avatars in the SAME top-to-bottom order as the dashboard; workspaces within a repo match.
  - Collapse a repo in the drawer → collapsed on the dashboard too (and vice versa); same for a workspace.
  - A single-tab workspace shows its pane hoisted directly under the workspace in both views; tapping a pane opens its terminal and closes the drawer; `⋯`/long-press still open the action sheets.
  - The workspace row no longer shows a duplicate `⑂ repo` chip.
