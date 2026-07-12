# UX Batch: Split label, Armed modifier, Agent picker — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship three independent UI/UX audit fixes in one branch — #20 (agent picker: search + recent + descriptions), #2 (visible/accessible armed modifier), #5 (honest "Split shell" label).

**Architecture:** #20 adds pure helpers + a static catalog (`AgentCatalog.kt`), a persisted MRU via the existing `Settings`/ViewModel store-lambda pattern, and a reworked `AgentPickerSheet`. #2 restyles `ModifierKey` (ring/uppercase/`stateDescription`). #5 is a two-label change.

**Tech Stack:** Kotlin / Jetpack Compose; JUnit.

## Global Constraints

- App-only; no companion/protocol/theme/sort changes.
- Recent agents persist via `Settings` DataStore + VM ctor lambdas (mirror `terminalFontSize`/`persistFontSize`), MRU, cap 5.
- Descriptions are a curated static map; unknown slugs → no description (never guessed).
- #5 changes labels only; the shell-creating `onSplit` wiring is unchanged.

---

## Task 1: Agent catalog + recent-agents persistence (#20 part A)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/AgentCatalog.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/AgentCatalogTest.kt` (new), `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt` (add one case)

**Interfaces:**
- Produces: `describeAgent(name): String?`, `filterAgents(all, query): List<String>`, `updatedMru(current, picked, cap=5): List<String>`; `DashboardViewModel.recentAgents: StateFlow<List<String>>` + `recordRecentAgent(name)`. Task 2 consumes these.

- [ ] **Step 1: Write the failing helper tests**

Create `AgentCatalogTest.kt`:
```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.data.updatedMru
import dev.herdr.mobile.ui.describeAgent
import dev.herdr.mobile.ui.filterAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCatalogTest {
    @Test fun describeKnownUnknownCaseInsensitive() {
        assertEquals("OpenAI Codex CLI", describeAgent("codex"))
        assertEquals("OpenAI Codex CLI", describeAgent("CODEX"))
        assertNull(describeAgent("agy"))
    }
    @Test fun filterBlankReturnsAll() {
        val all = listOf("claude", "codex", "gemini")
        assertEquals(all, filterAgents(all, "  "))
    }
    @Test fun filterSubstringCaseInsensitive() {
        assertEquals(listOf("codex"), filterAgents(listOf("claude", "codex", "gemini"), "ODE"))
        assertEquals(emptyList<String>(), filterAgents(listOf("claude"), "zzz"))
    }
    @Test fun mruPrependsDedupesCaps() {
        assertEquals(listOf("codex", "claude"), updatedMru(listOf("claude"), "codex"))
        assertEquals(listOf("claude", "codex"), updatedMru(listOf("codex", "claude"), "claude")) // move to front
        assertEquals(5, updatedMru(listOf("a", "b", "c", "d", "e"), "f").size)                    // cap
        assertEquals("f", updatedMru(listOf("a", "b", "c", "d", "e"), "f").first())
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved helpers)

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.AgentCatalogTest"`
Expected: compile failure.

- [ ] **Step 3: Create `AgentCatalog.kt`**

```kotlin
package dev.herdr.mobile.ui

/** One-line descriptions for well-known agent slugs; unknown slugs return null. */
val agentDescriptions: Map<String, String> = mapOf(
    "claude" to "Anthropic Claude Code",
    "codex" to "OpenAI Codex CLI",
    "gemini" to "Google Gemini CLI",
    "cursor" to "Cursor agent",
    "copilot" to "GitHub Copilot CLI",
    "cline" to "Cline coding agent",
    "opencode" to "OpenCode agent",
    "amp" to "Sourcegraph Amp",
    "grok" to "xAI Grok CLI",
    "droid" to "Factory Droid",
    "kimi" to "Moonshot Kimi CLI",
    "devin" to "Cognition Devin",
)

fun describeAgent(name: String): String? = agentDescriptions[name.lowercase()]

/** Case-insensitive substring filter; blank query returns the list unchanged. */
fun filterAgents(all: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) all else all.filter { it.contains(q, ignoreCase = true) }
}
```

(`updatedMru` lives in the `data` layer — it's a persistence concern used only by
`Settings` — so `AgentCatalog` carries no MRU logic and there's no data→ui dependency.)

- [ ] **Step 4: Run helper tests — expect PASS**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.AgentCatalogTest"`
Expected: 4 pass.

- [ ] **Step 5: Add persistence to `Settings.kt`**

Add a top-level MRU helper in this file (data layer) and the key:
```kotlin
/** MRU update: most-recent first, de-duplicated, capped. */
fun updatedMru(current: List<String>, picked: String, cap: Int = 5): List<String> =
    (listOf(picked) + current.filterNot { it == picked }).take(cap)

private val RECENT_AGENTS_KEY = stringPreferencesKey("recent_agents")
```
Inside the `Settings` class (calls the local `updatedMru`):
```kotlin
val recentAgents: Flow<List<String>> =
    context.dataStore.data.map { it[RECENT_AGENTS_KEY]?.split("\n")?.filter { s -> s.isNotBlank() } ?: emptyList() }

suspend fun addRecentAgent(name: String) {
    context.dataStore.edit { prefs ->
        val cur = prefs[RECENT_AGENTS_KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        prefs[RECENT_AGENTS_KEY] = updatedMru(cur, name).joinToString("\n")
    }
}
```

- [ ] **Step 6: Add the failing VM test**

In `DashboardViewModelTest.kt`, add (mirroring the font-size test at lines ~73-78):
```kotlin
@Test fun recordRecentAgentInvokesPersist() {
    var recorded: String? = null
    val vm = DashboardViewModel(
        CompanionClient(), PaneRepository(),
        recentAgentsStore = MutableStateFlow(listOf("claude")),
        persistRecentAgent = { recorded = it },
    )
    vm.recordRecentAgent("codex")
    assertEquals("codex", recorded)
}
```
(Use the same test-construction style already in this file; add any missing import like `kotlinx.coroutines.flow.MutableStateFlow`.)

- [ ] **Step 7: Extend the ViewModel**

Add constructor params (after `persistFontSize`):
```kotlin
recentAgentsStore: Flow<List<String>> = MutableStateFlow(emptyList()),
private val persistRecentAgent: (String) -> Unit = {},
```
Add members (near `terminalFontSize`):
```kotlin
val recentAgents: StateFlow<List<String>> =
    recentAgentsStore.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
fun recordRecentAgent(name: String) = persistRecentAgent(name)
```

- [ ] **Step 8: Wire `MainActivity`**

In the `DashboardViewModel(...)` construction, add (mirroring `persistFontSize`):
```kotlin
recentAgentsStore = settings.recentAgents,
persistRecentAgent = { name -> lifecycleScope.launch { settings.addRecentAgent(name) } },
```

- [ ] **Step 9: Run full suite + build — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass (a `DashboardViewModelTest` MockWebServer timeout is a known unrelated flake — rerun if hit). `AgentPickerSheet` is untouched this task and still compiles.

- [ ] **Step 10: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/AgentCatalog.kt app/app/src/main/java/dev/herdr/mobile/data/Settings.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/main/java/dev/herdr/mobile/MainActivity.kt app/app/src/test/java/dev/herdr/mobile/AgentCatalogTest.kt app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): agent catalog + persisted recent-agents (picker groundwork)"
```

---

## Task 2: Reworked agent picker sheet (#20 part B)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (`AgentPickerSheet` + `AgentItem`/`GroupLabel`)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt` (call site + record on pick/other)

**Interfaces:**
- Consumes: `describeAgent`/`filterAgents` (Task 1), `vm.recentAgents`/`vm.recordRecentAgent` (Task 1).

- [ ] **Step 1: Rework `AgentPickerSheet`**

Replace the composable:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    agents: List<String>, recent: List<String>,
    onPick: (String) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        var query by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("New agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("search agents") }, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            val recentShown = if (query.isBlank()) recent.filter { it in agents } else emptyList()
            if (recentShown.isNotEmpty()) {
                GroupLabel("recent")
                recentShown.forEach { AgentItem(it) { onPick(it) } }
                GroupLabel("all")
            }
            filterAgents(agents, query).forEach { AgentItem(it) { onPick(it) } }
            SheetItem("Other…", onOther, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AgentItem(name: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        describeAgent(name)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp))
}
```
Add imports: `androidx.compose.material3.OutlinedTextField`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.saveable.rememberSaveable`. (`Column`, `clickable`, `verticalScroll`, `rememberScrollState`, `FontWeight`, `Text`, `MaterialTheme` are already imported/available.)

- [ ] **Step 2: Update the call site in `DashboardScreen.kt`**

In the `agentPickerFor?.let { target -> … }` block:
- Add `val recent by vm.recentAgents.collectAsState()`.
- Pass `recent = recent` to `AgentPickerSheet`.
- In `onPick`, call `vm.recordRecentAgent(name)` before `vm.createNode(...)`.
- In the `showOtherDialog` → `OtherAgentDialog` `onConfirm`: after `parseAgentCommand(input)`, if `cmd.name.isNotBlank()` call `vm.recordRecentAgent(cmd.name)` (alongside the existing `createNode`).

- [ ] **Step 3: Build + full suite — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardScreen.kt
git commit -m "feat(app): searchable agent picker with recent group and descriptions"
```

---

## Task 3: Armed-modifier visibility (#2) + Split label (#5)

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (`Keycap` border param, `ModifierKey` states) — #2
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt` (two split labels) — #5

**Interfaces:** none (leaf UI changes).

- [ ] **Step 1: Add an optional border to `Keycap`**

```kotlin
@Composable
private fun Keycap(outer: Modifier, face: Brush, click: Modifier, border: BorderStroke? = null,
                   content: @Composable BoxScope.() -> Unit) {
    Box(outer.height(34.dp).clip(CapShape).background(CapLip), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(CapShape).background(face)
                .then(if (border != null) Modifier.border(border, CapShape) else Modifier)
                .then(click),
            contentAlignment = Alignment.Center, content = content,
        )
    }
}
```
`KeyCap` and `ArrowCap` call `Keycap(...)` positionally with the trailing content lambda — the new `border` param has a default, so their existing calls are unaffected (verify they still compile; if either passes the content as a trailing lambda after `click`, it remains valid).

- [ ] **Step 2: Restyle `ModifierKey`**

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: ModState, mono: FontFamily, modifier: Modifier = Modifier,
                        onTap: () -> Unit, onLock: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val face = when (state) {
        ModState.OFF -> CapBrush
        ModState.ONE_SHOT -> SolidColor(primary.copy(alpha = 0.22f))
        ModState.LOCKED -> SolidColor(primary)
    }
    val fg = when (state) {
        ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = if (state == ModState.ONE_SHOT) BorderStroke(1.5.dp, primary) else null
    val text = if (state == ModState.LOCKED) label.uppercase() else label
    val desc = when (state) { ModState.OFF -> "off"; ModState.ONE_SHOT -> "armed"; ModState.LOCKED -> "locked" }
    val click = Modifier
        .combinedClickable(onClick = onTap, onLongClick = onLock)
        .semantics { stateDescription = desc }
    Keycap(modifier, face, click, border) {
        Text(text, fontFamily = mono, style = MaterialTheme.typography.labelMedium,
            color = fg, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
```
Add imports: `androidx.compose.foundation.BorderStroke`, `androidx.compose.foundation.border`. (`semantics`/`stateDescription` are already imported in this file for the collapse/expand handles; `SolidColor`/`Brush`/`Color`/`combinedClickable`/`ExperimentalFoundationApi` are present.)

- [ ] **Step 3: Relabel the split items (#5)**

In `SidebarDrawer.kt` `RowActionSheet`, `NodeKind.PANE` branch (lines ~288-289):
```kotlin
SheetItem("Split shell right", onClick = { onSplit("right") })
SheetItem("Split shell down", onClick = { onSplit("down") })
```

- [ ] **Step 4: Build + full suite — expect green**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass (`TerminalKeysTest` still green — the state machine is unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt app/app/src/main/java/dev/herdr/mobile/ui/SidebarDrawer.kt
git commit -m "feat(app): visible/accessible armed modifier; honest 'Split shell' labels"
```

---

## Final Verification (whole branch)

- `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Live on device:
  - Pane sheet reads "Split shell right/down".
  - Tap Ctrl on the key bar → clear mauve ring (armed); long-press → solid `CTRL` (locked); tap again → off. TalkBack announces off/armed/locked.
  - New Agent sheet: search filters live; a Recent group shows recently-used agents at top; known agents show a one-line description; "Other…" remains and a chosen "Other" command's name enters Recent.
