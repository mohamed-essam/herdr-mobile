# Collapse the Redundant Label Stack — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix audit finding #9 — a single-workspace repo shows its name up to three times. Fold the redundant repo+workspace header into one merged row (both views), and make the dashboard pane card lead with the agent instead of the repo-name cwd.

**Architecture:** Both views share `flattenTree`, so the fold is a new `TreeRow.RepoWs` variant emitted by the shared flatten; each view renders it. Pane labels de-dup via pure helpers keyed on an enclosing `repoLabel` that `PaneItem` now carries.

**Tech Stack:** Kotlin / Jetpack Compose; JUnit.

## Global Constraints

- App-only; no companion/protocol/theme/sort changes; multi-workspace repos unchanged.
- Fold is **loss-free**: fold only when a repo has exactly one workspace whose label is blank or equals the repo `displayName`.
- Merged row keeps the workspace `#number`; the sidebar merged row keeps the workspace status glyph + `⋯` actions (New tab/New agent/Rename/Close), guarded off when the workspace id is blank (the `(unknown)` orphan).
- Merged row's collapse key is the **workspace id**.
- `TreeRow.RepoWs` field for the workspace is named `wsNode` (avoid `row.ws.ws`); `treeRowKey(RepoWs)` = `"rw:" + repo.repoKey`.

---

## Task 1: Fold single-workspace repos into a merged header

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (RepoWs arm + `RepoWsHeaderRow`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (RepoWs arm + `RepoWsRow`)
- Test: `app/app/src/test/java/dev/herdr/mobile/FlattenTreeTest.kt`

**Interfaces:**
- Produces: `TreeRow.RepoWs(repo: RepoNode, wsNode: WorkspaceNode, expanded: Boolean, paneCount: Int)`; `TreeRow.PaneItem` gains `repoLabel: String`; `fun foldableWorkspace(repo: RepoNode): WorkspaceNode?`. Task 2 consumes `PaneItem.repoLabel`.

- [ ] **Step 1: Write the failing tests**

Add to `FlattenTreeTest.kt` (keep existing tests) these cases, plus a helper:

```kotlin
// ws label == repo displayName → foldable
private fun foldedRepo(key: String, wsNum: Int, panes: List<Pane>): RepoNode {
    val ws = WorkspaceNode(
        Workspace(workspaceId = "ws-$key", label = key, number = wsNum),
        listOf(TabNode(Tab(tabId = "tab-$key", workspaceId = "ws-$key", number = 1), panes)),
    )
    return RepoNode(key, key, listOf(ws))
}

@Test fun singleWorkspaceRepoWithRedundantLabelFolds() {
    val rows = flattenTree(listOf(foldedRepo("r", 3, listOf(pane("p1", "ws-r", "tab-r")))), emptySet())
    val rw = rows[0] as TreeRow.RepoWs
    assertEquals("r", rw.repo.repoKey); assertEquals(3, rw.wsNode.ws.number); assertEquals(1, rw.paneCount)
    assertTrue(rows.none { it is TreeRow.Repo || it is TreeRow.Ws })   // no separate repo/ws rows
    val pi = rows[1] as TreeRow.PaneItem                                // single tab elided → promoted
    assertTrue(pi.promoted); assertEquals("r", pi.repoLabel)
}

@Test fun singleWorkspaceRepoWithDistinctLabelDoesNotFold() {
    val ws = WorkspaceNode(
        Workspace(workspaceId = "ws-r", label = "feature-x", number = 1),
        listOf(TabNode(Tab(tabId = "tab-r", workspaceId = "ws-r", number = 1), listOf(pane("p1", "ws-r", "tab-r")))),
    )
    val rows = flattenTree(listOf(RepoNode("r", "r", listOf(ws))), emptySet())
    assertTrue(rows[0] is TreeRow.Repo); assertTrue(rows[1] is TreeRow.Ws)
}

@Test fun twoWorkspaceRepoDoesNotFold() {
    val w1 = WorkspaceNode(Workspace(workspaceId = "w1", label = "r", number = 1),
        listOf(TabNode(Tab(tabId = "t1", workspaceId = "w1", number = 1), listOf(pane("p1", "w1", "t1")))))
    val w2 = WorkspaceNode(Workspace(workspaceId = "w2", label = "r", number = 2),
        listOf(TabNode(Tab(tabId = "t2", workspaceId = "w2", number = 1), listOf(pane("p2", "w2", "t2")))))
    val rows = flattenTree(listOf(RepoNode("r", "r", listOf(w1, w2))), emptySet())
    assertTrue(rows[0] is TreeRow.Repo); assertEquals(2, rows.count { it is TreeRow.Ws })
}

@Test fun collapsedRepoWsHidesChildren() {
    val rows = flattenTree(listOf(foldedRepo("r", 1, listOf(pane("p1", "ws-r", "tab-r")))), setOf("ws-r"))
    assertTrue(rows[0] is TreeRow.RepoWs && !(rows[0] as TreeRow.RepoWs).expanded)
    assertTrue(rows.none { it is TreeRow.PaneItem })
}
```

Also UPDATE the existing `orphanTreeHasUniqueKeysAndUnknownRepo`: the synthetic `(unknown)` workspace has `label == displayName == "(unknown)"`, so it now FOLDS. Change its two type assertions from `TreeRow.Repo`/repoKey to:
```kotlin
assertTrue(rows.any { it is TreeRow.RepoWs && it.repo.repoKey == "(unknown)" })
assertTrue(rows.any { it is TreeRow.PaneItem && it.pane.paneId == "orphan" })
```
(The `keys.size == keys.toSet().size` uniqueness assertion stays — it now also covers a `RepoWs` key.)

- [ ] **Step 2: Run — expect FAIL** (unresolved `TreeRow.RepoWs`/`foldableWorkspace`, `PaneItem.repoLabel`)

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.FlattenTreeTest"`
Expected: compile failure.

- [ ] **Step 3: Update `TreeModel.kt`**

Add the variant to `TreeRow` (after `Repo`) and `repoLabel` to `PaneItem`:
```kotlin
// A repo folded with its sole, label-redundant workspace into one header row.
data class RepoWs(val repo: RepoNode, val wsNode: WorkspaceNode, val expanded: Boolean, val paneCount: Int) : TreeRow
```
```kotlin
data class PaneItem(val pane: Pane, val promoted: Boolean, val parentTab: TabNode?, val repoLabel: String) : TreeRow
```

Add the fold predicate:
```kotlin
/** When a repo has exactly one workspace whose label adds nothing over the repo
 *  name, the repo and workspace header rows are redundant — return that workspace
 *  so they fold into one. Null for multi-workspace repos or a sole workspace with
 *  a distinct label (folding would drop that label). */
fun foldableWorkspace(repo: RepoNode): WorkspaceNode? {
    if (repo.workspaces.size != 1) return null
    val w = repo.workspaces[0]
    return if (w.ws.label.isBlank() || w.ws.label == repo.displayName) w else null
}
```

Replace `flattenTree` with the folding version + a shared child emitter:
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

/** A node is expanded unless its id is in [collapsed]; repos key on "repo:<key>". */
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
```

Add the `RepoWs` arm to `treeRowKey`:
```kotlin
is TreeRow.RepoWs -> "rw:" + row.repo.repoKey
```

- [ ] **Step 4: Add the dashboard merged-header row**

In `DashboardScreen.kt`, add the `when` arm (alongside the others in the `items` block):
```kotlin
is TreeRow.RepoWs -> RepoWsHeaderRow(row) { vm.toggleExpanded(row.wsNode.ws.workspaceId) }
```
Add the composable (near `RepoHeaderRow`):
```kotlin
@Composable
private fun RepoWsHeaderRow(row: TreeRow.RepoWs, onToggle: () -> Unit) {
    val ws = row.wsNode.ws
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RepoAvatar(row.repo.displayName)
        Spacer(Modifier.width(10.dp))
        Text(
            row.repo.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (ws.number > 0) {
            Spacer(Modifier.width(8.dp))
            Text("#${ws.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text("${row.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
```
Leave the `TreeRow.PaneItem` arm as `PaneRow(row.pane) { p -> selected = p }` for now (Task 2 changes PaneRow). `FontWeight`/`TextOverflow`/`RepoAvatar` are already used/imported in this file.

- [ ] **Step 5: Add the sidebar merged-header row**

In `SidebarDrawer.kt`, add the `when` arm:
```kotlin
is TreeRow.RepoWs -> RepoWsRow(row, dark, onToggle, onRowAction)
```
Add the composable (near `RepoRow`/`WorkspaceRow`):
```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoWsRow(row: TreeRow.RepoWs, dark: Boolean, onToggle: (String) -> Unit, onRowAction: (RowAction) -> Unit) {
    val ws = row.wsNode.ws
    val hasActions = ws.workspaceId.isNotBlank()   // orphan (unknown) has none
    val base = Modifier.fillMaxWidth()
    val row1 = if (hasActions)
        base.combinedClickable(onClick = { onToggle(ws.workspaceId) }, onLongClick = { onRowAction(wsAction(row.wsNode)) })
    else base.clickable { onToggle(ws.workspaceId) }
    Row(
        row1.padding(start = 12.dp, end = 12.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RepoAvatar(row.repo.displayName)
        Spacer(Modifier.width(10.dp))
        StatusGlyph(ws.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(
            row.repo.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (ws.number > 0) {
            Spacer(Modifier.width(6.dp))
            Text("#${ws.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text("${row.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        if (hasActions) RowActionDots("workspace actions") { onRowAction(wsAction(row.wsNode)) }
    }
}
```
`RepoAvatar`, `StatusGlyph`, `RowActionDots`, `wsAction`, `combinedClickable`, `clickable`, `TextOverflow`, `FontWeight` are all already in this file.

- [ ] **Step 6: Run tests + build — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass (a `DashboardViewModelTest` MockWebServer timeout is a known unrelated flake — rerun if hit).

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/test/java/dev/herdr/mobile/FlattenTreeTest.kt
git commit -m "feat(app): fold single-workspace repos into one header row"
```

---

## Task 2: Agent-first pane labels (de-dup the pane title)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/PaneRow.kt` (helpers + `PaneRow` signature/render)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (PaneItem arm passes `repoLabel`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (`PaneTreeRow` uses helpers + `repoLabel`)
- Test: `app/app/src/test/java/dev/herdr/mobile/PaneStatusTest.kt` (add label-helper cases)

**Interfaces:**
- Consumes: `TreeRow.PaneItem.repoLabel` (Task 1).
- Produces: `fun panePrimaryLabel(pane: Pane, repoLabel: String): String`; `fun paneSecondaryLabel(pane: Pane, repoLabel: String): String?`.

- [ ] **Step 1: Write the failing tests**

Add to `PaneStatusTest.kt`:
```kotlin
@Test fun primaryLabelLeadsWithAgentOrDifferentiatingCwd() {
    // agent → agent name, regardless of cwd
    assertEquals("claude", panePrimaryLabel(Pane(paneId = "1", agent = "claude", cwd = "/home/u/repo"), "repo"))
    // shell in repo root → generic "shell"
    assertEquals("shell", panePrimaryLabel(Pane(paneId = "2", agent = null, cwd = "/home/u/repo"), "repo"))
    // shell in a subdir → the subdir
    assertEquals("src", panePrimaryLabel(Pane(paneId = "3", agent = null, cwd = "/home/u/repo/src"), "repo"))
    // shell with blank cwd → "shell"
    assertEquals("shell", panePrimaryLabel(Pane(paneId = "4", agent = null, cwd = ""), "repo"))
}

@Test fun secondaryLabelShowsSubdirForAgentsOnly() {
    // agent in repo root → no secondary (redundant)
    assertNull(paneSecondaryLabel(Pane(paneId = "1", agent = "claude", cwd = "/home/u/repo"), "repo"))
    // agent in a subdir → the subdir
    assertEquals("src", paneSecondaryLabel(Pane(paneId = "2", agent = "claude", cwd = "/home/u/repo/src"), "repo"))
    // shell → no secondary (subdir already carried by the title)
    assertNull(paneSecondaryLabel(Pane(paneId = "3", agent = null, cwd = "/home/u/repo/src"), "repo"))
}
```
Add imports: `dev.herdr.mobile.ui.panePrimaryLabel`, `dev.herdr.mobile.ui.paneSecondaryLabel`, and `org.junit.Assert.assertNull` (or use the existing `import org.junit.Assert.*`).

- [ ] **Step 2: Run — expect FAIL** (unresolved helpers)

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.PaneStatusTest"`
Expected: compile failure.

- [ ] **Step 3: Add the helpers to `PaneRow.kt`**

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

- [ ] **Step 4: Change `PaneRow` to agent-first**

Signature: `fun PaneRow(pane: Pane, repoLabel: String, onClick: (Pane) -> Unit)`.
Replace the `title`/`subtitle` derivation and the subtitle render so the subtitle line is omitted when null:
```kotlin
val title = panePrimaryLabel(pane, repoLabel)
val secondary = paneSecondaryLabel(pane, repoLabel)
```
In the `Column`, keep the bold title `Text(title, …)`; render the subtitle `Text` (and its preceding `Spacer(Modifier.height(2.dp))`) only inside `if (secondary != null) { … }`, using `secondary` as the text. The left accent bar and trailing `StatusIndicator` are unchanged.

- [ ] **Step 5: Update both call sites**

- Dashboard `TreeRow.PaneItem` arm: `PaneRow(row.pane, row.repoLabel) { p -> selected = p }`.
- Sidebar `PaneTreeRow`: add a `repoLabel: String` parameter; replace `val label = pane.agent ?: "shell"` with `val label = panePrimaryLabel(pane, repoLabel)`, and replace the trailing dim `base = pane.cwd.substringAfterLast('/')` block so it shows `paneSecondaryLabel(pane, repoLabel)` only when non-null. The `PaneItem` arm passes it: `PaneTreeRow(row.pane, row.promoted, row.parentTab, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction, row.repoLabel)` (add `repoLabel` as the last param, matching the composable's new signature).

- [ ] **Step 6: Run tests + build — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/PaneRow.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/test/java/dev/herdr/mobile/PaneStatusTest.kt
git commit -m "feat(app): pane cards lead with the agent, not the repo-name cwd"
```

---

## Final Verification (whole branch)

- `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Live on device:
  - A single-workspace repo shows ONE header (`[WT] wt-dashboards  #4  2`), not repo-then-workspace.
  - Its pane cards read `claude` / `shell` (agent-first), not a third `wt-dashboards`; a pane in a subdirectory shows that subdir.
  - The drawer's merged row `⋯` still opens the workspace actions; the `(unknown)` orphan row has none.
  - A repo with two worktree-workspaces still shows the repo header + both workspace rows.
