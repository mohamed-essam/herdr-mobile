# Paseo-Inspired Dashboard Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Headers-mono/body-sans typography, and turn the main dashboard into a collapsible Repo > Workspace > Tab > Pane tree with colored letter-monogram repo avatars.

**Architecture:** Pure builders (`buildRepoTree`) + pure helpers (`monogram`, `colorIndexFor`) are unit-tested; the theme change and Compose rows are build/device-verified. The dashboard reuses the sidebar's `flatten(tree, collapsed) -> rows -> LazyColumn` pattern with an added repo level. No companion/protocol change; the embedded terminal and the sidebar drawer are untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material3; JUnit4 unit tests (`org.junit`).

## Global Constraints

- Typography: **title** styles stay `FontFamily.Monospace`; **body** and **label** styles use the system default (sans). Terminal (`TerminalView`) untouched.
- Repo key per workspace, first non-blank of: `worktree.repoName` → first pane's `cwd` basename → `label` → `workspaceId`; the synthetic orphan workspace (`workspaceId` blank) → `"(unknown)"`.
- Repo ordering: by minimum workspace `number` in the group; `"(unknown)"` group last.
- Repo collapse key is `"repo:" + repoKey` (never collides with workspace/tab ids). Repos default expanded.
- Avatar: monogram = first 1–2 alphanumeric chars of the name, uppercased (`"?"` if none); background from a fixed Catppuccin accent list via `colorIndexFor` (stable, non-negative); dark ink for contrast.
- The sidebar drawer's tree and all row-action/close/move logic are unchanged.
- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Unit tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
- Paths are relative to repo root `~/herdr-mobile`.

---

### Task 1: Typography split (headers mono, body sans)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/theme/Theme.kt` (`HerdrTypography`)

**Interfaces:**
- Produces: no new symbols; changes the global `Typography` so title styles are monospace and body/label styles are sans.

- [ ] **Step 1: Edit `HerdrTypography`**

Replace the entire `HerdrTypography` definition (currently sets `FontFamily.Monospace` on all nine styles) with title-only monospace:

```kotlin
// Headers monospace (terminal identity: the "herdr ❯" wordmark, titles, row
// headers); body + labels use the system sans default for readability. The
// embedded terminal has its own font and is unaffected.
private val HerdrTypography = Typography(
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
)
```

Leave the rest of the file (imports, `base`, `HerdrShapes`, `HerdrTheme`) unchanged. Body/label/headline/display styles now fall through to the Material `Typography()` defaults (sans).

- [ ] **Step 2: Build to verify it compiles**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/theme/Theme.kt
git commit -m "feat(app): mixed typography — headers mono, body sans"
```

---

### Task 2: `buildRepoTree` — repo grouping over the workspace tree

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt` (append `RepoNode`, `repoKeyFor`, `buildRepoTree`)
- Test: `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt` (create)

**Interfaces:**
- Consumes: existing `WorkspaceNode(ws: Workspace, tabs: List<TabNode>)`, `TabNode(tab: Tab, panes: List<Pane>)` from `TreeModel.kt`.
- Produces:
  - `data class RepoNode(val repoKey: String, val displayName: String, val workspaces: List<WorkspaceNode>)`
  - `fun repoKeyFor(node: WorkspaceNode): String`
  - `fun buildRepoTree(nodes: List<WorkspaceNode>): List<RepoNode>`

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.Tab
import dev.herdr.mobile.net.Workspace
import dev.herdr.mobile.net.Worktree
import dev.herdr.mobile.ui.TabNode
import dev.herdr.mobile.ui.WorkspaceNode
import dev.herdr.mobile.ui.buildRepoTree
import dev.herdr.mobile.ui.repoKeyFor
import org.junit.Assert.*
import org.junit.Test

class RepoTreeTest {
    private fun wsNode(
        id: String, number: Int = 1, label: String = "", repoName: String? = null, cwd: String = "",
    ): WorkspaceNode {
        val ws = Workspace(
            workspaceId = id, label = label, number = number,
            worktree = repoName?.let { Worktree(repoName = it, isLinkedWorktree = true) },
        )
        val pane = Pane(paneId = "$id:p1", workspaceId = id, tabId = "$id:t1", cwd = cwd)
        val tab = TabNode(Tab(tabId = "$id:t1", workspaceId = id), listOf(pane))
        return WorkspaceNode(ws, listOf(tab))
    }

    @Test fun keyFallbackOrder() {
        assertEquals("ops", repoKeyFor(wsNode("w1", repoName = "ops", cwd = "/home/x/ignored")))
        assertEquals("omega3", repoKeyFor(wsNode("w2", cwd = "/home/x/omega3")))
        assertEquals("L", repoKeyFor(wsNode("w3", label = "L")))
        assertEquals("w4", repoKeyFor(wsNode("w4")))
        // synthetic orphan workspace (blank id) buckets under "(unknown)"
        val orphan = WorkspaceNode(Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE), emptyList())
        assertEquals("(unknown)", repoKeyFor(orphan))
    }

    @Test fun groupsSameRepoAndOrders() {
        val a = wsNode("w1", number = 4, repoName = "ops")
        val b = wsNode("w2", number = 2, repoName = "ops")   // same repo, lower number
        val c = wsNode("w3", number = 3, repoName = "core")
        val orphan = WorkspaceNode(Workspace(workspaceId = "", label = "(unknown)", number = Int.MAX_VALUE), emptyList())

        val repos = buildRepoTree(listOf(a, c, b, orphan))

        // core (min#3) before ops (min#2)? No: ops min# is 2 -> ops first, then core (3), unknown last.
        assertEquals(listOf("ops", "core", "(unknown)"), repos.map { it.repoKey })
        val ops = repos.first { it.repoKey == "ops" }
        assertEquals(2, ops.workspaces.size)
        // intra-group order preserved as given (a before b)
        assertEquals(listOf("w1", "w2"), ops.workspaces.map { it.ws.workspaceId })
        assertEquals("ops", ops.displayName)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: FAIL — unresolved references `buildRepoTree` / `repoKeyFor` (not defined yet).

- [ ] **Step 3: Implement in `TreeModel.kt`**

Append to `app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt`:

```kotlin
data class RepoNode(val repoKey: String, val displayName: String, val workspaces: List<WorkspaceNode>)

/**
 * Repo key for a workspace: worktree.repoName > first pane's cwd basename >
 * label > workspaceId. The synthetic orphan workspace (blank workspaceId,
 * emitted by buildTree) buckets under "(unknown)".
 */
fun repoKeyFor(node: WorkspaceNode): String {
    if (node.ws.workspaceId.isBlank()) return "(unknown)"
    node.ws.worktree?.repoName?.takeIf { it.isNotBlank() }?.let { return it }
    node.tabs.asSequence().flatMap { it.panes.asSequence() }
        .map { it.cwd }.firstOrNull { it.isNotBlank() }
        ?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
    node.ws.label.takeIf { it.isNotBlank() }?.let { return it }
    return node.ws.workspaceId
}

/**
 * Groups workspace nodes by repo key, preserving each group's intra-order.
 * Repos are ordered by the minimum workspace number in the group; the
 * "(unknown)" group always sorts last.
 */
fun buildRepoTree(nodes: List<WorkspaceNode>): List<RepoNode> {
    val groups = LinkedHashMap<String, MutableList<WorkspaceNode>>()
    for (n in nodes) groups.getOrPut(repoKeyFor(n)) { mutableListOf() }.add(n)
    return groups.entries
        .map { (key, ws) -> RepoNode(key, key, ws) }
        .sortedWith(
            compareBy(
                { if (it.repoKey == "(unknown)") 1 else 0 },
                { it.workspaces.minOf { w -> w.ws.number } },
                { it.displayName },
            ),
        )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoTreeTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TreeModel.kt app/app/src/test/java/dev/herdr/mobile/RepoTreeTest.kt
git commit -m "feat(app): buildRepoTree repo grouping + tests"
```

---

### Task 3: Repo avatar (monogram + deterministic color)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/theme/Palette.kt` (append `colorIndexFor`, `avatarColor`, `AvatarInk`)
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/RepoAvatar.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/RepoAvatarTest.kt`

**Interfaces:**
- Produces:
  - `fun colorIndexFor(seed: String, size: Int): Int` (Palette.kt)
  - `fun avatarColor(seed: String, dark: Boolean): Color` (Palette.kt)
  - `fun monogram(name: String): String` (RepoAvatar.kt)
  - `@Composable fun RepoAvatar(name: String, modifier: Modifier = Modifier)` (RepoAvatar.kt)

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/RepoAvatarTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.monogram
import dev.herdr.mobile.ui.theme.colorIndexFor
import org.junit.Assert.*
import org.junit.Test

class RepoAvatarTest {
    @Test fun monogramRules() {
        assertEquals("GE", monogram("getpaseo/paseo"))
        assertEquals("K", monogram("k"))
        assertEquals("12", monogram("123repo"))
        assertEquals("?", monogram("/-_"))
        assertEquals("?", monogram(""))
    }

    @Test fun colorIndexIsStableAndInRange() {
        assertEquals(colorIndexFor("ops", 6), colorIndexFor("ops", 6)) // deterministic
        for (seed in listOf("ops", "core", "omega3", "", "a/b/c")) {
            val i = colorIndexFor(seed, 6)
            assertTrue("index $i out of range for '$seed'", i in 0 until 6)
        }
        assertEquals(0, colorIndexFor("anything", 0)) // guard: size <= 0
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoAvatarTest"`
Expected: FAIL — unresolved references `monogram` / `colorIndexFor`.

- [ ] **Step 3: Add color helpers to `Palette.kt`**

Append to `app/app/src/main/java/dev/herdr/mobile/ui/theme/Palette.kt`:

```kotlin
/** Dark ink for monogram text on a bright avatar accent (both themes). */
val AvatarInk = Color(0xFF11111B)

/** Stable, non-negative index into a palette of [size] for [seed]. */
fun colorIndexFor(seed: String, size: Int): Int {
    if (size <= 0) return 0
    var h = 0
    for (c in seed) h = h * 31 + c.code
    return ((h % size) + size) % size
}

private val avatarAccentsDark = listOf(Mocha.mauve, Mocha.blue, Mocha.green, Mocha.yellow, Mocha.peach, Mocha.red)
private val avatarAccentsLight = listOf(Latte.mauve, Latte.blue, Latte.green, Latte.yellow, Latte.peach, Latte.red)

/** Deterministic avatar background color for [seed], theme-aware. */
fun avatarColor(seed: String, dark: Boolean): Color {
    val palette = if (dark) avatarAccentsDark else avatarAccentsLight
    return palette[colorIndexFor(seed, palette.size)]
}
```

(`Color`, `Mocha`, `Latte` are already defined in this file.)

- [ ] **Step 4: Create `RepoAvatar.kt`**

Create `app/app/src/main/java/dev/herdr/mobile/ui/RepoAvatar.kt`:

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.ui.theme.AvatarInk
import dev.herdr.mobile.ui.theme.avatarColor

/** First 1–2 alphanumeric chars of [name], uppercased; "?" when none. */
fun monogram(name: String): String {
    val letters = name.filter { it.isLetterOrDigit() }
    return when {
        letters.isEmpty() -> "?"
        letters.length == 1 -> letters.uppercase()
        else -> letters.take(2).uppercase()
    }
}

/** A colored rounded-square monogram avatar for a repo name. */
@Composable
fun RepoAvatar(name: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(avatarColor(name, dark)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogram(name),
            color = AvatarInk,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.RepoAvatarTest"`
Expected: PASS.

- [ ] **Step 6: Build to verify the composable compiles**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/theme/Palette.kt app/app/src/main/java/dev/herdr/mobile/ui/RepoAvatar.kt app/app/src/test/java/dev/herdr/mobile/RepoAvatarTest.kt
git commit -m "feat(app): repo monogram avatar + deterministic color"
```

---

### Task 4: Render the repo tree on the dashboard

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt` (add `repoTree`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (replace flat pane list with repo-tree render)

**Interfaces:**
- Consumes: `RepoNode`, `buildRepoTree` (Task 2); `RepoAvatar` (Task 3); existing `vm.tree`, `vm.collapsed`, `vm.toggleExpanded`, `PaneRow`, `WorkspaceNode`, `TabNode`.
- Produces: `vm.repoTree: StateFlow<List<RepoNode>>`.

- [ ] **Step 1: Add `repoTree` to the ViewModel**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add the import `import kotlinx.coroutines.flow.map` (near the other `kotlinx.coroutines.flow.*` imports), then add this right after the existing `val tree = …` declaration (after line 36):

```kotlin
    val repoTree: StateFlow<List<RepoNode>> =
        tree.map { buildRepoTree(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

- [ ] **Step 2: Add the two new imports to `DashboardScreen.kt`**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt`, add:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
```

(`Box`, `Row`, `Spacer`, `width`, `padding` come from the existing `androidx.compose.foundation.layout.*`; `FontWeight` and `items`/`LazyColumn` are already imported; `RepoNode`/`WorkspaceNode`/`TabNode`/`RepoAvatar` are same-package.)

- [ ] **Step 3: Collect `repoTree` in `DashboardScreen`**

In `DashboardScreen`, next to the existing `val tree by vm.tree.collectAsState()` (line 52), add:

```kotlin
    val repoTree by vm.repoTree.collectAsState()
```

- [ ] **Step 4: Replace the flat pane list with the repo-tree render**

In `DashboardScreen`, replace this block (currently lines 96–104):

```kotlin
                if (panes.isEmpty()) {
                    EmptyState(connected)
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        items(panes, key = { it.paneId }) { pane ->
                            PaneRow(pane) { p -> if (p.agent != null) selected = p }
                        }
                    }
                }
```

with:

```kotlin
                if (panes.isEmpty()) {
                    EmptyState(connected)
                } else {
                    val rows = flattenRepoTree(repoTree, collapsed)
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        items(rows, key = { dashRowKey(it) }) { row ->
                            when (row) {
                                is DashRow.Repo -> RepoHeaderRow(row) { vm.toggleExpanded("repo:${row.node.repoKey}") }
                                is DashRow.Ws -> WsHeaderRow(row) { vm.toggleExpanded(row.node.ws.workspaceId) }
                                is DashRow.TabRow -> TabHeaderRow(row) { vm.toggleExpanded(row.node.tab.tabId) }
                                is DashRow.PaneRowItem -> Box(Modifier.padding(start = 24.dp)) {
                                    PaneRow(row.pane) { p -> if (p.agent != null) selected = p }
                                }
                            }
                        }
                    }
                }
```

- [ ] **Step 5: Add the flatten helpers + header row composables**

At the end of `DashboardScreen.kt` (top-level, after the last existing composable), add:

```kotlin
private sealed interface DashRow {
    data class Repo(val node: RepoNode, val expanded: Boolean, val paneCount: Int) : DashRow
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : DashRow
    data class TabRow(val node: TabNode, val expanded: Boolean) : DashRow
    data class PaneRowItem(val pane: Pane) : DashRow
}

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
            for (t in w.tabs) {
                val tOpen = t.tab.tabId !in collapsed
                rows.add(DashRow.TabRow(t, tOpen))
                if (!tOpen) continue
                t.panes.forEach { rows.add(DashRow.PaneRowItem(it)) }
            }
        }
    }
    return rows
}

private fun dashRowKey(r: DashRow): String = when (r) {
    is DashRow.Repo -> "r:" + r.node.repoKey
    is DashRow.Ws -> "w:" + r.node.ws.workspaceId
    is DashRow.TabRow -> "t:" + r.node.tab.tabId
    is DashRow.PaneRowItem -> "p:" + r.pane.paneId
}

@Composable
private fun RepoHeaderRow(row: DashRow.Repo, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RepoAvatar(row.node.displayName)
        Spacer(Modifier.width(10.dp))
        Text(
            row.node.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${row.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WsHeaderRow(row: DashRow.Ws, onToggle: () -> Unit) {
    val ws = row.node.ws
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 28.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            ws.label.ifEmpty { "(unknown)" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ws.number > 0) {
            Spacer(Modifier.width(6.dp))
            Text("#${ws.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TabHeaderRow(row: DashRow.TabRow, onToggle: () -> Unit) {
    val tab = row.node.tab
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 44.dp, end = 12.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            if (tab.label.isEmpty()) "—" else "tab ${tab.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 6: Build the debug APK**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the full unit suite**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests green (including `RepoTreeTest`, `RepoAvatarTest`). Note: `CompanionClientTest` was recently de-flaked; if it ever fails with a MockWebServer teardown `IOException`, re-run once.

- [ ] **Step 8: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): repo-grouped dashboard tree with avatars"
```

---

## Manual Verification (after all tasks)

Rebuild + reinstall on the phone, then:

1. Dashboard shows **repo group headers** with colored monogram avatars; under each, Workspace → Tab → Pane nested.
2. Tapping a repo/workspace/tab header collapses/expands that level; repos start expanded.
3. Tapping an **agent** pane opens the terminal (unchanged).
4. Titles/headers render **monospace**; body text and subtitles render **sans**; the terminal's own font is unchanged.
5. The sidebar drawer is unchanged.
