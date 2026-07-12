# Terminal Key Bar Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the terminal view's outlined chip row with a flat-key-cap terminal-key bar — sticky Ctrl/Alt modifiers, Esc/Tab/^C, Home/End/PgUp/PgDn, and a pinned arrow d-pad.

**Architecture:** A new pure `ui/TerminalKeys.kt` holds the modifier state machine (`ModifierKeys`) and the `bytesFor(TermKey)` byte sequences. `TerminalViewClientImpl` delegates its existing `readControlKey()`/`readAltKey()` hooks to the shared `ModifierKeys` (the exact mechanism `TerminalView.inputCodePoint` uses to apply modifiers to soft-keyboard letters) and clears one-shots in `onCodePoint`. `TerminalScreen` owns a single `remember { ModifierKeys() }`, threads it to the client and to the restyled bar.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- App-only change. No edits to `companion/`, wire types, or the `terminal-view` / `terminal-emulator` vendored modules.
- Key set: `Ctrl  Alt  Esc  Tab  ^C  Home  End  PgUp  PgDn` + arrows `↑ ↓ ← →`. No Shift, no Fn (soft keyboard owns Shift).
- Byte sequences (xterm normal-cursor-mode, verbatim): Esc `0x1b`; Tab `0x09`; ^C `0x03`; Up `ESC [ A`; Down `ESC [ B`; Right `ESC [ C`; Left `ESC [ D`; Home `ESC [ H`; End `ESC [ F`; PgUp `ESC [ 5 ~`; PgDn `ESC [ 6 ~`.
- Modifier semantics: single tap `OFF→ONE_SHOT` (tap again `→OFF`); double tap `→LOCKED`; tap from `LOCKED→OFF`. `consumeOneShot()` clears `ONE_SHOT` only, never `LOCKED`. Ctrl and Alt are independent.
- One-shot auto-clears after the next input code point, via the client's `onCodePoint` (which fires after the modifier is read). Tapping any non-modifier bar key also clears a lingering one-shot.
- Visual: flat filled key-caps, `surfaceContainerHigh` fill, `shapes.small`, **no border stroke**, mono `labelLarge`, ripple press feedback, ≥44dp touch targets. Modifier armed colors: `OFF`=`surfaceContainerHigh`/`onSurface`; `ONE_SHOT`=`primaryContainer`/`onPrimaryContainer`; `LOCKED`=`primary`/`onPrimary`.
- Layout: horizontally-scrolling specials strip (`weight(1f)`) + `VerticalDivider` + pinned d-pad (never scrolls).
- Build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Test: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

---

### Task 1: `TerminalKeys.kt` — modifier state machine + key bytes (pure, tested)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin; uses `androidx.compose.runtime.mutableStateOf`).
- Produces:
  - `enum class ModState { OFF, ONE_SHOT, LOCKED }`
  - `class ModifierKeys` with observable `var ctrl: ModState` / `var alt: ModState`; methods `readCtrl(): Boolean`, `readAlt(): Boolean`, `tapCtrl()`, `tapAlt()`, `lockCtrl()`, `lockAlt()`, `consumeOneShot()`.
  - `enum class TermKey { ESC, TAB, CTRL_C, UP, DOWN, LEFT, RIGHT, HOME, END, PGUP, PGDN }`
  - `fun bytesFor(key: TermKey): ByteArray`

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.ModState
import dev.herdr.mobile.ui.ModifierKeys
import dev.herdr.mobile.ui.TermKey
import dev.herdr.mobile.ui.bytesFor
import org.junit.Assert.*
import org.junit.Test

class TerminalKeysTest {
    private fun esc(vararg tail: Char) =
        byteArrayOf(0x1b) + tail.map { it.code.toByte() }.toByteArray()

    @Test fun bytesForControlKeys() {
        assertArrayEquals(byteArrayOf(0x1b), bytesFor(TermKey.ESC))
        assertArrayEquals(byteArrayOf(0x09), bytesFor(TermKey.TAB))
        assertArrayEquals(byteArrayOf(0x03), bytesFor(TermKey.CTRL_C))
    }

    @Test fun bytesForArrows() {
        assertArrayEquals(esc('[', 'A'), bytesFor(TermKey.UP))
        assertArrayEquals(esc('[', 'B'), bytesFor(TermKey.DOWN))
        assertArrayEquals(esc('[', 'C'), bytesFor(TermKey.RIGHT))
        assertArrayEquals(esc('[', 'D'), bytesFor(TermKey.LEFT))
    }

    @Test fun bytesForNav() {
        assertArrayEquals(esc('[', 'H'), bytesFor(TermKey.HOME))
        assertArrayEquals(esc('[', 'F'), bytesFor(TermKey.END))
        assertArrayEquals(esc('[', '5', '~'), bytesFor(TermKey.PGUP))
        assertArrayEquals(esc('[', '6', '~'), bytesFor(TermKey.PGDN))
    }

    @Test fun modifierOneShotCycle() {
        val m = ModifierKeys()
        assertEquals(ModState.OFF, m.ctrl)
        assertFalse(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.ONE_SHOT, m.ctrl)
        assertTrue(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.OFF, m.ctrl)
    }

    @Test fun modifierLockThenTapOff() {
        val m = ModifierKeys()
        m.lockCtrl()
        assertEquals(ModState.LOCKED, m.ctrl)
        assertTrue(m.readCtrl())
        m.tapCtrl()
        assertEquals(ModState.OFF, m.ctrl)
    }

    @Test fun consumeOneShotClearsOneShotNotLocked() {
        val m = ModifierKeys()
        m.tapCtrl()   // ONE_SHOT
        m.lockAlt()   // LOCKED
        m.consumeOneShot()
        assertEquals(ModState.OFF, m.ctrl)
        assertEquals(ModState.LOCKED, m.alt)
    }

    @Test fun ctrlAndAltIndependent() {
        val m = ModifierKeys()
        m.tapCtrl()
        assertEquals(ModState.ONE_SHOT, m.ctrl)
        assertEquals(ModState.OFF, m.alt)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalKeysTest"`
Expected: FAIL — compilation error, `unresolved reference: ModifierKeys` / `TermKey` / `bytesFor`.

- [ ] **Step 3: Implement `TerminalKeys.kt`**

Create `app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt`:

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sticky-modifier state for a bar modifier key. */
enum class ModState { OFF, ONE_SHOT, LOCKED }

/**
 * Single source of truth for the Ctrl/Alt sticky modifiers, shared by the key bar
 * (reads state to highlight, mutates on tap) and TerminalViewClientImpl (reads via
 * readControlKey/readAltKey; clears one-shots after a code point). Backed by Compose
 * snapshot state so the bar recomposes on change.
 */
class ModifierKeys {
    var ctrl by mutableStateOf(ModState.OFF)
    var alt by mutableStateOf(ModState.OFF)

    fun readCtrl(): Boolean = ctrl != ModState.OFF
    fun readAlt(): Boolean = alt != ModState.OFF

    /** Single tap: OFF -> ONE_SHOT; ONE_SHOT/LOCKED -> OFF. */
    private fun tapped(s: ModState): ModState = if (s == ModState.OFF) ModState.ONE_SHOT else ModState.OFF

    fun tapCtrl() { ctrl = tapped(ctrl) }
    fun tapAlt() { alt = tapped(alt) }
    fun lockCtrl() { ctrl = ModState.LOCKED }
    fun lockAlt() { alt = ModState.LOCKED }

    /** Clear modifiers armed for a single key; LOCKED persists. */
    fun consumeOneShot() {
        if (ctrl == ModState.ONE_SHOT) ctrl = ModState.OFF
        if (alt == ModState.ONE_SHOT) alt = ModState.OFF
    }
}

/** Non-printing keys the bar sends as literal byte sequences. */
enum class TermKey { ESC, TAB, CTRL_C, UP, DOWN, LEFT, RIGHT, HOME, END, PGUP, PGDN }

private val ESC = byteArrayOf(0x1b)

/** Byte sequence for a [TermKey] (xterm normal-cursor-mode forms). */
fun bytesFor(key: TermKey): ByteArray = when (key) {
    TermKey.ESC -> ESC
    TermKey.TAB -> byteArrayOf(0x09)
    TermKey.CTRL_C -> byteArrayOf(0x03)
    TermKey.UP -> ESC + "[A".toByteArray()
    TermKey.DOWN -> ESC + "[B".toByteArray()
    TermKey.RIGHT -> ESC + "[C".toByteArray()
    TermKey.LEFT -> ESC + "[D".toByteArray()
    TermKey.HOME -> ESC + "[H".toByteArray()
    TermKey.END -> ESC + "[F".toByteArray()
    TermKey.PGUP -> ESC + "[5~".toByteArray()
    TermKey.PGDN -> ESC + "[6~".toByteArray()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalKeysTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt
git commit -m "feat(app): terminal key bar modifier state machine + key bytes"
```

---

### Task 2: Wire `ModifierKeys` through the terminal view client

Plumbing only — no visible change yet. Verified by `assembleDebug` + the existing unit suite staying green (the modifier logic is already covered by Task 1). This task changes the `TerminalViewClientImpl` constructor, so it MUST also update the single call site in `TerminalScreen.kt` in the same task or the build breaks; the visible bar rewrite is Task 3.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt` (constructor lines 11-16; `readControlKey`/`readAltKey` lines 47-48; `onCodePoint` line 51)
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (add a `remember`; client construction at line 151)

**Interfaces:**
- Consumes: `ModifierKeys` (Task 1; same package `dev.herdr.mobile.ui`, no import needed).
- Produces: `TerminalViewClientImpl(view, initialPx, bounds, mods, onFontSizeChanged)` — new 4th positional param `mods: ModifierKeys` before the font-size lambda.

- [ ] **Step 1: Add the `mods` parameter and delegate the hooks**

In `TerminalViewClientImpl.kt`, change the constructor (lines 11-16) to add `mods`:

```kotlin
class TerminalViewClientImpl(
    private val view: TerminalView,
    initialPx: Int,
    private val bounds: FontBounds,
    private val mods: ModifierKeys,
    private val onFontSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
```

Change `readControlKey`/`readAltKey` (lines 47-48) from `= false` to delegate:

```kotlin
    override fun readControlKey(): Boolean = mods.readCtrl()
    override fun readAltKey(): Boolean = mods.readAlt()
```

Change `onCodePoint` (line 51) from `= false` to clear one-shots. `inputCodePoint`
reads the modifier into `controlDown` *before* calling `onCodePoint`, so the current
key keeps the modifier and the next does not:

```kotlin
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        mods.consumeOneShot()
        return false
    }
```

Leave `readShiftKey()`/`readFnKey()` returning `false`.

- [ ] **Step 2: Create the holder and pass it to the client**

In `TerminalScreen.kt`, add the holder alongside the other `remember`s. After line 51
(`val scope = rememberCoroutineScope()`) add:

```kotlin
    val mods = remember { ModifierKeys() }
```

Then update the client construction (line 151) to pass `mods` as the 4th argument:

```kotlin
                            val c = TerminalViewClientImpl(this, initialPx, bounds, mods) { vm.setTerminalFontSize(it) }
```

(Leave the existing `KeyToolbar(it)` call at line 228 unchanged — Task 3 replaces it.)

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests (incl. `TerminalKeysTest`) pass. Behavior is unchanged at runtime (nothing arms the modifiers yet), which is correct for this task.

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): delegate terminal modifier hooks to ModifierKeys"
```

---

### Task 3: Restyled key bar — flat caps, modifier keys, pinned d-pad

No new unit tests: Compose UI wiring over Task 1's tested primitives. Verified by `assembleDebug` + existing suite green.

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (`KeyToolbar` call at line 228; replace `KeyToolbar`/`KeyChip`/`clickableNoRipple` at lines 281-317)

**Interfaces:**
- Consumes: `ModifierKeys`, `ModState`, `TermKey`, `bytesFor` (Task 1); `mods` from Task 2.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Pass `mods` to the bar**

In `TerminalScreen.kt`, change the bar call (line 228) from:

```kotlin
            session?.let { KeyToolbar(it) }
```

to:

```kotlin
            session?.let { KeyToolbar(it, mods) }
```

- [ ] **Step 2: Replace the bar composables**

Replace the entire block from `@Composable private fun KeyToolbar(...)` through the
end of `clickableNoRipple` (lines 281-317) with the restyled bar. This removes
`KeyChip` and the `clickableNoRipple` helper (their only use was the old bar):

```kotlin
@Composable
private fun KeyToolbar(session: RemoteTerminalSession, mods: ModifierKeys) {
    fun send(key: TermKey) {
        val b = bytesFor(key)
        session.write(b, 0, b.size)
        mods.consumeOneShot()   // bar keys don't combine with a modifier; drop a lingering one-shot
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifierKey("ctrl", mods.ctrl, onTap = { mods.tapCtrl() }, onDoubleTap = { mods.lockCtrl() })
                ModifierKey("alt", mods.alt, onTap = { mods.tapAlt() }, onDoubleTap = { mods.lockAlt() })
                KeyCap("esc") { send(TermKey.ESC) }
                KeyCap("tab") { send(TermKey.TAB) }
                KeyCap("^C") { send(TermKey.CTRL_C) }
                KeyCap("home") { send(TermKey.HOME) }
                KeyCap("end") { send(TermKey.END) }
                KeyCap("pgup") { send(TermKey.PGUP) }
                KeyCap("pgdn") { send(TermKey.PGDN) }
            }
            VerticalDivider(
                modifier = Modifier.height(48.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DPad { send(it) }
        }
    }
}

/** Centered up arrow above a left/down/right row. */
@Composable
private fun DPad(onKey: (TermKey) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        KeyCap("↑") { onKey(TermKey.UP) }
        Row {
            KeyCap("←") { onKey(TermKey.LEFT) }
            KeyCap("↓") { onKey(TermKey.DOWN) }
            KeyCap("→") { onKey(TermKey.RIGHT) }
        }
    }
}

/** Flat filled key-cap with ripple feedback. */
@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(3.dp),
    ) {
        Box(
            Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** A sticky modifier cap: fill/text color reflects [state]; single vs double tap. */
@Composable
private fun ModifierKey(label: String, state: ModState, onTap: () -> Unit, onDoubleTap: () -> Unit) {
    val bg = when (state) {
        ModState.OFF -> MaterialTheme.colorScheme.surfaceContainerHigh
        ModState.ONE_SHOT -> MaterialTheme.colorScheme.primaryContainer
        ModState.LOCKED -> MaterialTheme.colorScheme.primary
    }
    val fg = when (state) {
        ModState.OFF -> MaterialTheme.colorScheme.onSurface
        ModState.ONE_SHOT -> MaterialTheme.colorScheme.onPrimaryContainer
        ModState.LOCKED -> MaterialTheme.colorScheme.onPrimary
    }
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(3.dp),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
```

- [ ] **Step 3: Build and run the unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all existing unit tests pass. (If the compiler reports an unused import for a symbol only the old `KeyChip` used, remove that import — but `horizontalScroll`, `rememberScrollState`, `clickable`, `FontFamily`, `pointerInput`, and `detectTapGestures` are all still used by the new bar.)

- [ ] **Step 4: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): restyled terminal key bar with modifiers and d-pad"
```

---

## Notes for the implementer

- All new symbols (`ModState`, `ModifierKeys`, `TermKey`, `bytesFor`) live in package `dev.herdr.mobile.ui`, the same package as the files edited in Tasks 2 and 3 — no imports needed there. `TerminalKeysTest` is in `dev.herdr.mobile` and imports them explicitly (all `public`).
- `VerticalDivider`, `Surface`, `Text`, `MaterialTheme` come from the existing wildcard `import androidx.compose.material3.*` in `TerminalScreen.kt`. `defaultMinSize` comes from the existing `import androidx.compose.foundation.layout.*`.
- Do not touch the `terminal-view` / `terminal-emulator` modules; the modifier mechanism already exists there (`TerminalView.inputCodePoint` ORs in `readControlKey()`/`readAltKey()`).
- `ModifierKeys` backing with `mutableStateOf` is intentional: the bar observes it for recomposition, and the client reads the current value on the UI thread — both correct.
