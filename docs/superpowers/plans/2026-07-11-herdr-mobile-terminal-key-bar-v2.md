# Terminal Key Bar v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the horizontally-scrolling terminal key bar with a fixed two-row 4×2 grid + grouped arrow d-pad, tactile compact caps, a manual collapse toggle, and no `^C`.

**Architecture:** App-only Compose change in `ui/TerminalScreen.kt` — rewrite the bar composables (`KeyToolbar`/`KeyCap`/`ModifierKey`/`DPad`) into a fixed grid with tactile fills, a recessed d-pad well, and a `rememberSaveable` expand/collapse toggle. Then remove the now-unused `TermKey.CTRL_C` from `ui/TerminalKeys.kt` and its test assertion. The modifier state machine, byte sequences, long-press lock, and ripple are unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- App-only. No edits to `companion/`, wire types, or the `terminal-view`/`terminal-emulator` vendored modules.
- Keys: `ctrl alt esc tab` (row 1) / `home end pgup pgdn` (row 2), equal width; grouped d-pad `↑` over `← ↓ →` on a recessed well. **No `^C`.**
- Compact density (verbatim): cap min-height `34.dp`; intra-grid gaps `4.dp`; bar padding `6.dp`; arrow caps `30.dp × 34.dp`; d-pad well padding `3.dp`, corner `12.dp`.
- Tactile fills (verbatim): `CapBrush = verticalGradient(0xFF3A3C50, 0xFF2A2C3D)`; `ArrowBrush = verticalGradient(0xFF4A4D63, 0xFF363849)`; `DpadWell = 0xFF11111B`. Each cap: `shadow(1.dp, shapes.small, clip=false)` then `clip(shapes.small)` then `background(fill)`.
- Modifier armed colors (theme roles): OFF=`CapBrush`/`onSurface`; ONE_SHOT=`primaryContainer`/`onPrimaryContainer`; LOCKED=`primary`/`onPrimary`. Gesture unchanged: `combinedClickable(onClick=tap, onLongClick=lock)`.
- Collapse: `var expanded by rememberSaveable { mutableStateOf(true) }`; expanded shows a slim right-edge `CollapseTab` (`⌄`, 22.dp × 72.dp); collapsed shows a full-width `ExpandHandle` (`⌃`, 20.dp tall). Modifier state persists across collapse.
- Every key press: `session.write(bytesFor(key), 0, b.size)` + `mods.consumeOneShot()`.
- Build ordering: the bar rewrite (Task 1) must land before the `CTRL_C` enum removal (Task 2) — the current bar references `TermKey.CTRL_C`, so removing it first breaks the build.
- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Test: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

---

### Task 1: Rewrite the key bar — two-row grid, tactile caps, collapse toggle

No new unit tests: this is Compose UI wiring over the already-tested `ModifierKeys`/`bytesFor` primitives. Verified by a clean `assembleDebug` and the existing suite staying green. After this task `TermKey.CTRL_C` is unused (its only reference, the `^C` cap, is removed) but still present in the enum — that is intended; Task 2 removes it.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (imports near lines 3-36; the bar composables at lines 284-388; the call site at line 228 stays `session?.let { KeyToolbar(it, mods) }`)

**Interfaces:**
- Consumes: `ModifierKeys` (`.ctrl`, `.alt`, `.tapCtrl()`, `.tapAlt()`, `.lockCtrl()`, `.lockAlt()`, `.consumeOneShot()`), `ModState`, `TermKey` (ESC/TAB/HOME/END/PGUP/PGDN/UP/DOWN/LEFT/RIGHT), `bytesFor` — all in package `dev.herdr.mobile.ui`, no import needed.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Add the new imports**

In `TerminalScreen.kt`, add these imports alongside the existing `androidx.compose.*` imports:

```kotlin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
```

Remove these two imports (only the old scrolling strip used them):

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
```

- [ ] **Step 2: Replace the bar composables**

Replace the entire block from `@Composable private fun KeyToolbar(...)` through the end of `ModifierKey` (lines 284-388) with:

```kotlin
// Tactile cap fills (the bar is always the dark terminal surface).
private val CapBrush = Brush.verticalGradient(listOf(Color(0xFF3A3C50), Color(0xFF2A2C3D)))
private val ArrowBrush = Brush.verticalGradient(listOf(Color(0xFF4A4D63), Color(0xFF363849)))
private val DpadWell = Color(0xFF11111B)

@Composable
private fun KeyToolbar(session: RemoteTerminalSession, mods: ModifierKeys) {
    var expanded by rememberSaveable { mutableStateOf(true) }
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
                        ModifierKey("ctrl", mods.ctrl, Modifier.weight(1f), { mods.tapCtrl() }, { mods.lockCtrl() })
                        ModifierKey("alt", mods.alt, Modifier.weight(1f), { mods.tapAlt() }, { mods.lockAlt() })
                        KeyCap("esc", Modifier.weight(1f)) { send(TermKey.ESC) }
                        KeyCap("tab", Modifier.weight(1f)) { send(TermKey.TAB) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyCap("home", Modifier.weight(1f)) { send(TermKey.HOME) }
                        KeyCap("end", Modifier.weight(1f)) { send(TermKey.END) }
                        KeyCap("pgup", Modifier.weight(1f)) { send(TermKey.PGUP) }
                        KeyCap("pgdn", Modifier.weight(1f)) { send(TermKey.PGDN) }
                    }
                }
                VerticalDivider(
                    Modifier.height(72.dp).padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                DPad { send(it) }
                CollapseTab { expanded = false }
            }
        }
    }
}

/** Recessed well holding ↑ over ← ↓ →. */
@Composable
private fun DPad(onKey: (TermKey) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(DpadWell).padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArrowCap("↑") { onKey(TermKey.UP) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrowCap("←") { onKey(TermKey.LEFT) }
            ArrowCap("↓") { onKey(TermKey.DOWN) }
            ArrowCap("→") { onKey(TermKey.RIGHT) }
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

/** Tactile filled cap with ripple; [modifier] carries the row weight. */
@Composable
private fun KeyCap(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .shadow(1.dp, MaterialTheme.shapes.small, clip = false)
            .clip(MaterialTheme.shapes.small)
            .background(CapBrush)
            .defaultMinSize(minHeight = 34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** Fixed-size tactile arrow cap for the d-pad. */
@Composable
private fun ArrowCap(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .shadow(1.dp, MaterialTheme.shapes.small, clip = false)
            .clip(MaterialTheme.shapes.small)
            .background(ArrowBrush)
            .size(width = 30.dp, height = 34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall)
    }
}

/** Sticky modifier cap: fill/text reflect [state]; tap arms, long-press locks. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(label: String, state: ModState, modifier: Modifier = Modifier, onTap: () -> Unit, onLock: () -> Unit) {
    val fill = when (state) {
        ModState.OFF -> CapBrush
        ModState.ONE_SHOT -> SolidColor(MaterialTheme.colorScheme.primaryContainer)
        ModState.LOCKED -> SolidColor(MaterialTheme.colorScheme.primary)
    }
    val fg = when (state) {
        ModState.OFF -> MaterialTheme.colorScheme.onSurface
        ModState.ONE_SHOT -> MaterialTheme.colorScheme.onPrimaryContainer
        ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier
            .shadow(1.dp, MaterialTheme.shapes.small, clip = false)
            .clip(MaterialTheme.shapes.small)
            .background(fill)
            .defaultMinSize(minHeight = 34.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLock),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
            color = fg, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
    }
}
```

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests pass. (If the compiler flags `rememberScrollState`/`horizontalScroll` as unused, confirm they were removed in Step 1. `TermKey.CTRL_C` is now unused but still compiles — that is expected; Task 2 removes it.)

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): two-row tactile terminal key bar with collapse toggle"
```

---

### Task 2: Remove the now-unused `^C` key

No new unit tests: this drops a dead enum value and its stale assertion. Verified by `assembleDebug` + the suite green.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt` (`enum class TermKey`; the `bytesFor` `when`)
- Modify: `app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt` (`bytesForControlKeys`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `TermKey` without `CTRL_C`; `bytesFor` without the `CTRL_C` branch.

- [ ] **Step 1: Drop the `CTRL_C` assertion from the test**

In `TerminalKeysTest.kt`, in `bytesForControlKeys`, remove the `CTRL_C` line so it reads:

```kotlin
    @Test fun bytesForControlKeys() {
        assertArrayEquals(byteArrayOf(0x1b), bytesFor(TermKey.ESC))
        assertArrayEquals(byteArrayOf(0x09), bytesFor(TermKey.TAB))
    }
```

- [ ] **Step 2: Remove `CTRL_C` from the enum and `bytesFor`**

In `TerminalKeys.kt`, delete `CTRL_C` from the enum declaration:

```kotlin
enum class TermKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PGUP, PGDN }
```

and delete its branch from `bytesFor` (remove the single line `TermKey.CTRL_C -> byteArrayOf(0x03)`). Leave every other branch unchanged.

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `TerminalKeysTest` passes (the remaining `bytesFor` assertions and the `ModifierKeys` tests). The `bytesFor` `when` stays exhaustive because `CTRL_C` is gone from the enum.

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt
git commit -m "chore(app): drop unused ^C terminal key"
```

---

## Notes for the implementer

- Do Task 1 before Task 2. Task 1 removes the only reference to `TermKey.CTRL_C` (the `^C` cap); Task 2 then removes the dead enum value. Reversing the order breaks the build.
- All symbols (`ModifierKeys`, `ModState`, `TermKey`, `bytesFor`) are in package `dev.herdr.mobile.ui`, same as `TerminalScreen.kt` — no imports needed for them. `TerminalKeysTest` is in `dev.herdr.mobile` and imports them explicitly.
- `VerticalDivider`, `Surface`, `Text`, `MaterialTheme` come from the existing `import androidx.compose.material3.*`; `Arrangement`, `Column`, `Row`, `Box`, `Spacer`, `size`, `width`, `height`, `defaultMinSize`, `fillMaxWidth` from the existing `import androidx.compose.foundation.layout.*`. `background`, `clickable`, `combinedClickable`, `ExperimentalFoundationApi`, `FontFamily` are already imported.
- Do not touch the modifier state machine, byte sequences, long-press lock, or ripple — only the bar's layout/styling and the `^C` removal change.
