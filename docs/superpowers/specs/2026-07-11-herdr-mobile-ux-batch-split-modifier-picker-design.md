# herdr-mobile — UX Batch: Split label, Armed modifier, Agent picker — Design Spec

**Date:** 2026-07-11
**Component:** Android app only (`app/`).
**Source:** UI/UX audit findings #5, #2, #20 — three independent fixes in one cycle.

## Goal

1. **#5** — the pane "Split right/down" actions silently spawn a shell. Relabel them
   "Split shell right/down" so the outcome is explicit (chosen fix: relabel-only; no
   agent-split, no picker coupling).
2. **#2** — the armed one-shot Ctrl/Alt state on the key bar is nearly invisible and
   exposes no accessibility state. Give it a distinct, colorblind-safe treatment + a
   screen-reader `stateDescription`.
3. **#20** — the New Agent picker is a long, unsorted, unsearchable, undescribed list
   of slugs. Add a pinned search filter, a persisted Recent group, and one-line
   descriptions.

## Global Constraints

- App-only; no companion/protocol/theme/sort changes.
- #5 is a pure label change: the `onSplit` wiring (which creates a shell) is unchanged.
- Recent agents persist via the existing `Settings` DataStore + the ViewModel's
  store/persist-lambda pattern (mirroring `terminalFontSize`), capped at 5, MRU order.
- Descriptions are a static curated map; unknown slugs render with no description
  (never a wrong label).

## Part 1 — #5: Relabel the shell split *(`SidebarDrawer.kt`)*

In `RowActionSheet`, the `NodeKind.PANE` branch, change the two split items' labels
only (the `onSplit("right")`/`onSplit("down")` callbacks are unchanged):

```kotlin
SheetItem("Split shell right", onClick = { onSplit("right") })
SheetItem("Split shell down", onClick = { onSplit("down") })
```

No other change for #5.

## Part 2 — #2: Distinct, accessible armed-modifier state *(`TerminalScreen.kt`)*

The three `ModState`s must differ by **shape and text, not just color**, and carry a
screen-reader state.

- `Keycap` gains an optional border so a state can draw a ring:
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
  (`KeyCap`/`ArrowCap` pass no border — unchanged.)

- `ModifierKey` renders the three states distinctly and adds `stateDescription`:
  ```kotlin
  @OptIn(ExperimentalFoundationApi::class)
  @Composable
  private fun ModifierKey(label: String, state: ModState, mono: FontFamily, modifier: Modifier = Modifier,
                          onTap: () -> Unit, onLock: () -> Unit) {
      val primary = MaterialTheme.colorScheme.primary
      val face = when (state) {
          ModState.OFF -> CapBrush
          ModState.ONE_SHOT -> SolidColor(primary.copy(alpha = 0.22f))   // faint mauve wash
          ModState.LOCKED -> SolidColor(primary)
      }
      val fg = when (state) {
          ModState.OFF -> MaterialTheme.colorScheme.onSurface
          ModState.ONE_SHOT -> MaterialTheme.colorScheme.onSurface
          ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
      }
      val border = if (state == ModState.ONE_SHOT) BorderStroke(1.5.dp, primary) else null  // ring = armed
      val text = if (state == ModState.LOCKED) label.uppercase() else label                 // CTRL = locked
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
  State cues: OFF = dark, no ring, `ctrl`; ARMED = faint mauve + **primary ring**, `ctrl`;
  LOCKED = solid primary, no ring, **`CTRL`**. Ring-vs-solid-vs-plain is non-color;
  uppercase marks locked; `stateDescription` covers TalkBack.

- Imports to add: `androidx.compose.foundation.BorderStroke`, `androidx.compose.foundation.border`.
  (`semantics`/`stateDescription` are already imported for the collapse handles;
  `SolidColor`/`Brush`/`Color` already present.)

## Part 3 — #20: A usable agent picker

### New pure helpers + catalog (`app/.../ui/AgentCatalog.kt`, new)

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

/** MRU update: most-recent first, de-duplicated, capped. */
fun updatedMru(current: List<String>, picked: String, cap: Int = 5): List<String> =
    (listOf(picked) + current.filterNot { it == picked }).take(cap)
```

(The map covers the confidently-known slugs; others — e.g. `pi`, `agy`, `kiro`,
`hermes` — intentionally have no description rather than a guessed one.)

### Persistence (`Settings.kt`)

```kotlin
private val RECENT_AGENTS_KEY = stringPreferencesKey("recent_agents")

val recentAgents: Flow<List<String>> =
    context.dataStore.data.map { it[RECENT_AGENTS_KEY]?.split("\n")?.filter { s -> s.isNotBlank() } ?: emptyList() }

suspend fun addRecentAgent(name: String) {
    context.dataStore.edit { prefs ->
        val cur = prefs[RECENT_AGENTS_KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        prefs[RECENT_AGENTS_KEY] = updatedMru(cur, name).joinToString("\n")
    }
}
```

### ViewModel (`DashboardViewModel.kt`) — mirror the font-size pattern

- Constructor gains `recentAgentsStore: Flow<List<String>> = MutableStateFlow(emptyList())`
  and `persistRecentAgent: (String) -> Unit = {}`.
- Expose `val recentAgents: StateFlow<List<String>> = recentAgentsStore.stateIn(scope, WhileSubscribed, emptyList())`
  (matching how `terminalFontSize` is exposed).
- `fun recordRecentAgent(name: String) = persistRecentAgent(name)`.
- `MainActivity` wires `recentAgentsStore = settings.recentAgents`,
  `persistRecentAgent = { name -> lifecycleScope.launch { settings.addRecentAgent(name) } }`
  (mirroring the existing `persistFontSize`).

### The sheet (`AgentPickerSheet` in `SidebarDrawer.kt`)

New signature: `AgentPickerSheet(agents: List<String>, recent: List<String>, onPick: (String) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit)`.

Layout inside the existing `ModalBottomSheet` + scrolling `Column`:
1. Title "New agent".
2. A pinned **search** `OutlinedTextField` (`rememberSaveable` query; square shape via
   `MaterialTheme.shapes.small`, placeholder "search agents").
3. If the query is blank **and** `recent` is non-empty: a dim "recent" label, then the
   recent names (intersected with `agents`, preserving MRU order) as items.
4. A dim "all" label (shown only when a recent group is above it), then
   `filterAgents(agents, query)` as items.
5. "Other…" at the bottom (always).

Each agent item shows the name (bodyLarge) with `describeAgent(name)` beneath it
(labelSmall, `onSurfaceVariant`) when non-null. Reuse a small item composable; the
existing `SheetItem` is text-only, so add an `AgentItem(name, onClick)` that renders
name + optional description.

### Call site (`DashboardScreen.kt`)

- `val recent by vm.recentAgents.collectAsState()`, pass `recent = recent`.
- In `onPick`: call `vm.recordRecentAgent(name)` before `vm.createNode(...)`.
- In `OtherAgentDialog`'s confirm (the "Other…" path): after parsing, if
  `cmd.name.isNotBlank()` call `vm.recordRecentAgent(cmd.name)`.

## Testing

- `AgentCatalogTest` (new, pure): `describeAgent` known/unknown/case-insensitive;
  `filterAgents` blank→all, substring, case-insensitive, no-match→empty;
  `updatedMru` prepend, de-dupe (existing name moves to front), cap at 5, order.
- MRU semantics are covered by the pure `updatedMru` tests above (prepend, de-dupe,
  cap, order). A `DashboardViewModel` test asserts `recordRecentAgent(name)` invokes the
  injected `persistRecentAgent` lambda (mirroring the existing font-size VM test) — this
  covers the wiring without a DataStore integration test. `Settings.addRecentAgent`
  itself is a thin `updatedMru` + DataStore write; not separately unit-tested.
- `ModifierKeys` transitions already covered by `TerminalKeysTest` (unchanged).
- Render/visual (#2 states, #20 sheet, #5 labels): build + on-device.

## What is explicitly unchanged

- The split behavior (still creates a shell); the modifier state machine
  (`ModifierKeys`); the sort/tree; `MoveDestinationSheet`; companion/protocol.

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

## Live Validation (device)

- Pane sheet reads "Split shell right/down".
- Arm Ctrl (single tap) → the cap shows a clear mauve ring (not a near-invisible tint);
  long-press → solid `CTRL`. TalkBack announces off/armed/locked.
- New Agent sheet: a search box filters as you type; recently-used agents appear in a
  Recent group at top; known agents show a one-line description; "Other…" remains.
