# Key Bar Fidelity (Rounded, Tactile, JetBrains Mono) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the terminal key caps match the approved mockup — rounded corners, a visible tactile lip, and JetBrains Mono — without touching layout, density, the collapse toggle, or any tested logic.

**Architecture:** A single-file Compose restyle in `ui/TerminalScreen.kt`: load JetBrains Mono from the bundled asset as a Compose `FontFamily`, give caps an explicit `RoundedCornerShape(9.dp)` (scoped override of the theme's square `shapes.small`), and render depth via a shared two-layer `Keycap` (dark lip base + gradient face inset 2dp) that reads on the dark bar. `TerminalKeys.kt`, the theme, and the terminal's native font are untouched.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2026.06.01), JUnit4.

## Global Constraints

- App-only, single file (`ui/TerminalScreen.kt`). No change to layout, density, collapse toggle, `send()`, `ModifierKeys`, `bytesFor`, `TerminalKeys.kt`, `HerdrShapes`/theme, or the terminal's native `Typeface`.
- Font: build `mono = FontFamily(Font("fonts/JetBrainsMono-Regular.ttf", ctx.assets))` once via `LocalContext`; every cap `Text` uses `fontFamily = mono` (not `FontFamily.Monospace`). `CollapseTab`/`ExpandHandle` glyphs keep `FontFamily.Monospace`.
- Cap constants (verbatim): `CapShape = RoundedCornerShape(9.dp)`; `CapLip = 0xFF15151F`; `CapBrush = verticalGradient(0xFF3E4056, 0xFF2A2C3D)`; `ArrowBrush = verticalGradient(0xFF4E5168, 0xFF363849)`; `DpadWell = 0xFF11111B`.
- Tactile lip: `Keycap` = outer Box (`height(34.dp)`, `clip(CapShape)`, `background(CapLip)`, `TopCenter`) wrapping inner Box (`fillMaxWidth()`, `height(32.dp)`, `clip(CapShape)`, `background(face)`, `then(click)`).
- Modifier face/fg by state: OFF=`CapBrush`/`onSurface`; ONE_SHOT=`SolidColor(primaryContainer)`/`onPrimaryContainer`; LOCKED=`SolidColor(primary)`/`onPrimary`. Gesture unchanged: `combinedClickable(onClick=onTap, onLongClick=onLock)`.
- Imports: ADD `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.text.font.Font`; REMOVE `androidx.compose.ui.draw.shadow`.
- No new tests (styling only); verify `assembleDebug` + existing suite green.
- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Test: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

---

### Task 1: Rounded tactile JetBrains-Mono key caps

No new unit tests: pure Compose styling over already-tested primitives. Verified by a clean `assembleDebug` + the existing suite staying green (and validated on device).

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (imports near the top; the bar block from `private val CapBrush = …` through the end of `ModifierKey`)

**Interfaces:**
- Consumes: `RemoteTerminalSession`, `ModifierKeys`, `ModState`, `TermKey`, `bytesFor` (unchanged, same package).
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Fix imports**

In `TerminalScreen.kt`, ADD:

```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
```

REMOVE (the two-layer lip replaces the only `shadow` use):

```kotlin
import androidx.compose.ui.draw.shadow
```

- [ ] **Step 2: Replace the bar block**

Replace the entire block from `private val CapBrush = …` through the closing brace of `ModifierKey` with the following. (`CollapseTab` and `ExpandHandle` are included unchanged so the replacement is one contiguous region.)

```kotlin
// Tactile cap language (the bar is always the dark terminal surface).
private val CapShape = RoundedCornerShape(9.dp)
private val CapLip = Color(0xFF15151F)
private val CapBrush = Brush.verticalGradient(listOf(Color(0xFF3E4056), Color(0xFF2A2C3D)))
private val ArrowBrush = Brush.verticalGradient(listOf(Color(0xFF4E5168), Color(0xFF363849)))
private val DpadWell = Color(0xFF11111B)

@Composable
private fun KeyToolbar(session: RemoteTerminalSession, mods: ModifierKeys) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val ctx = LocalContext.current
    val mono = remember { FontFamily(Font("fonts/JetBrainsMono-Regular.ttf", ctx.assets)) }
    fun send(key: TermKey) {
        val b = bytesFor(key)
        session.write(b, 0, b.size)
        mods.consumeOneShot()   // bar keys don't combine with a modifier; drop a lingering one-shot
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        if (!expanded) {
            ExpandHandle { expanded = true }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModifierKey("ctrl", mods.ctrl, mono, Modifier.weight(1f), { mods.tapCtrl() }, { mods.lockCtrl() })
                        ModifierKey("alt", mods.alt, mono, Modifier.weight(1f), { mods.tapAlt() }, { mods.lockAlt() })
                        KeyCap("esc", mono, Modifier.weight(1f)) { send(TermKey.ESC) }
                        KeyCap("tab", mono, Modifier.weight(1f)) { send(TermKey.TAB) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("home", mono, Modifier.weight(1f)) { send(TermKey.HOME) }
                        KeyCap("end", mono, Modifier.weight(1f)) { send(TermKey.END) }
                        KeyCap("pgup", mono, Modifier.weight(1f)) { send(TermKey.PGUP) }
                        KeyCap("pgdn", mono, Modifier.weight(1f)) { send(TermKey.PGDN) }
                    }
                }
                VerticalDivider(
                    Modifier.height(72.dp).padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DPad(mono) { send(it) }
                CollapseTab { expanded = false }
            }
        }
    }
}

/** Recessed well holding ↑ over ← ↓ →. */
@Composable
private fun DPad(mono: FontFamily, onKey: (TermKey) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(DpadWell).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArrowCap("↑", mono) { onKey(TermKey.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrowCap("←", mono) { onKey(TermKey.LEFT) }
            ArrowCap("↓", mono) { onKey(TermKey.DOWN) }
            ArrowCap("→", mono) { onKey(TermKey.RIGHT) }
        }
    }
}

/** Slim full-height tab that collapses the bar; costs width, not height. */
@Composable
private fun CollapseTab(onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 2.dp).width(22.dp).height(72.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "hide keys" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌄", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium)
    }
}

/** Thin handle shown while collapsed; tap to restore the bar. */
@Composable
private fun ExpandHandle(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "show keys" },
        contentAlignment = Alignment.Center,
    ) {
        Text("⌃", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium)
    }
}

/** Raised keycap: dark lip base + gradient face inset 2dp at the bottom.
 *  [outer] carries the row weight (specials) or fixed width (arrows);
 *  [click] is the caller's clickable/combinedClickable modifier. */
@Composable
private fun Keycap(outer: Modifier, face: Brush, click: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        outer.height(34.dp).clip(CapShape).background(CapLip),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.fillMaxWidth().height(32.dp).clip(CapShape).background(face).then(click),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** Tactile filled cap with ripple; [modifier] carries the row weight. */
@Composable
private fun KeyCap(label: String, mono: FontFamily, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Keycap(modifier, CapBrush, Modifier.clickable(onClick = onClick)) {
        Text(label, fontFamily = mono, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Fixed-size tactile arrow cap for the d-pad. */
@Composable
private fun ArrowCap(label: String, mono: FontFamily, onClick: () -> Unit) {
    Keycap(Modifier.width(30.dp), ArrowBrush, Modifier.clickable(onClick = onClick)) {
        Text(label, fontFamily = mono, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall)
    }
}

/** Sticky modifier cap: face/text reflect [state]; tap arms, long-press locks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: ModState, mono: FontFamily, modifier: Modifier = Modifier, onTap: () -> Unit, onLock: () -> Unit) {
    val face = when (state) {
        ModState.OFF -> CapBrush
        ModState.ONE_SHOT -> SolidColor(MaterialTheme.colorScheme.primaryContainer)
        ModState.LOCKED -> SolidColor(MaterialTheme.colorScheme.primary)
    }
    val fg = when (state) {
        ModState.OFF -> MaterialTheme.colorScheme.onSurface
        ModState.ONE_SHOT -> MaterialTheme.colorScheme.onPrimaryContainer
        ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    Keycap(modifier, face, Modifier.combinedClickable(onClick = onTap, onLongClick = onLock)) {
        Text(label, fontFamily = mono, style = MaterialTheme.typography.labelMedium,
            color = fg, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
```

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests pass. (If the compiler flags `shadow` as unused, confirm it was removed in Step 1; if it flags `Font`/`LocalContext` as unresolved, confirm they were added.)

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): rounded tactile JetBrains Mono key caps to match mockup"
```

---

## Notes for the implementer

- `Font("fonts/JetBrainsMono-Regular.ttf", ctx.assets)` uses the Compose asset-font overload `Font(path, assetManager)` — the asset already exists at `app/app/src/main/assets/fonts/JetBrainsMono-Regular.ttf` (do not add a `res/font` copy).
- Only the caps round; do not change `HerdrShapes` or any other component — the app stays square everywhere else.
- `Keycap`'s `click` parameter receives a fully-built `Modifier.clickable(...)` or `Modifier.combinedClickable(...)`; `combinedClickable` is built inside `ModifierKey`, which already carries `@OptIn(ExperimentalFoundationApi::class)`.
- `Column`, `Row`, `Box`, `BoxScope`, `height`, `width`, `fillMaxWidth`, `padding`, `Arrangement` come from the existing `androidx.compose.foundation.layout.*` wildcard; `RoundedCornerShape`, `Color`, `Brush`, `SolidColor`, `clip`, `background`, `clickable`, `combinedClickable`, `ExperimentalFoundationApi`, `FontFamily`, `rememberSaveable`, `semantics`/`contentDescription` are already imported.
