# Terminal Key Bar Redesign — Design Spec

**Date:** 2026-07-11
**Component:** herdr-mobile — Android app only (`ui/`); no companion change.

## Goal

Replace the terminal view's accessory key row — currently seven outlined,
feedback-less chips (`esc tab ^C ↑ ↓ ← →`) that read as clunky — with a proper
**terminal-key bar**: the keys a phone soft keyboard lacks (modifiers + Esc/Tab +
navigation), restyled as flat key-caps with press feedback, plus working sticky
**Ctrl**/**Alt** modifiers and a pinned arrow d-pad.

The soft keyboard already provides letters, numbers, and symbols; the bar exists
only for what it can't reach.

## Decisions (grilled)

- **Key set:** `Ctrl  Alt  Esc  Tab  ^C  Home  End  PgUp  PgDn` + arrows
  `↑ ↓ ← →`. (`^C` kept as a one-tap convenience alongside sticky Ctrl.)
- **Layout: row + pinned d-pad.** A horizontally-scrolling strip of the special
  keys on the left; a thin vertical divider; a compact arrow d-pad pinned at the
  right edge that never scrolls away.
- **Visual: flat filled key-caps**, no border stroke, rounded `shapes.small`,
  mono `labelLarge`, real ripple press feedback.
- **Modifiers: one-shot + long-press lock.** Single tap arms for the next key
  then auto-clears; long-press locks until tapped off. (Originally scoped as
  double-tap-to-lock; changed to long-press during final review because
  double-tap detection imposes a ~300ms latency on every single-tap arm, and
  `combinedClickable(onClick, onLongClick)` also restores ripple + a11y that the
  `detectTapGestures` approach lacked.)

## Feasibility (verified in the vendored Termux view)

`TerminalView.inputCodePoint` (terminal-view/.../TerminalView.java:858) computes
`controlDown = controlDownFromEvent || mClient.readControlKey()` and
`altDown = ... || mClient.readAltKey()` for **both** hardware keys and
soft-keyboard text (soft text routes through `sendTextToTerminal` →
`inputCodePoint`, line 431). It then applies the control transform (a→0x01, etc.,
lines 863-885). So a sticky modifier implemented as `readControlKey()`/
`readAltKey()` returning `true` makes a Gboard-typed letter emit the correct
control byte. These hooks already exist on `TerminalViewClientImpl` and are
currently hardcoded `false`. `onCodePoint(codePoint, controlDown, session)` is
called once per code point (line 861), *after* the modifier is read — the natural
place to clear a one-shot modifier so the current key keeps it and the next does
not.

## Part 1 — `ui/TerminalKeys.kt` (new, pure, tested)

```kotlin
package dev.herdr.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Sticky-modifier state. */
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
    private fun tap(s: ModState): ModState = if (s == ModState.OFF) ModState.ONE_SHOT else ModState.OFF
    /** Double tap: -> LOCKED (from any state). */
    private fun lock(): ModState = ModState.LOCKED

    fun tapCtrl() { ctrl = tap(ctrl) }
    fun tapAlt() { alt = tap(alt) }
    fun lockCtrl() { ctrl = lock() }
    fun lockAlt() { alt = lock() }

    /** Clear modifiers that were armed for a single key; LOCKED persists. */
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

## Part 2 — `ui/TerminalViewClientImpl.kt`

Add a `ModifierKeys` constructor parameter and wire the three touch points; nothing
else changes.

```kotlin
class TerminalViewClientImpl(
    private val view: TerminalView,
    initialPx: Int,
    private val bounds: FontBounds,
    private val mods: ModifierKeys,
    private val onFontSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
    ...
    override fun readControlKey(): Boolean = mods.readCtrl()
    override fun readAltKey(): Boolean = mods.readAlt()
    // Called once per input code point, after the modifier is read into controlDown.
    // Clear one-shot modifiers so they apply to exactly this key; return false to not consume.
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        mods.consumeOneShot()
        return false
    }
    ...
}
```

`readShiftKey()`/`readFnKey()` stay `false` (the soft keyboard owns Shift; no Fn
key in the bar).

## Part 3 — `ui/TerminalScreen.kt`

- Create the holder once and thread it to both consumers:
  ```kotlin
  val mods = remember { ModifierKeys() }
  ```
  Pass `mods` into the `TerminalViewClientImpl(...)` construction in the
  `AndroidView` factory (new 4th argument, before the font-size lambda), and into
  the new key bar.
- Replace `KeyToolbar` / `KeyChip` / `clickableNoRipple` with the restyled bar.
  `KeyToolbar(session, mods)`:
  - A `Surface(color = surfaceContainer)` wrapping a `Row(fillMaxWidth, padding 6dp)`:
    - Left: `Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = CenterVertically)` holding, in order: `ModifierKey("ctrl", mods.ctrl, ::tapCtrl, ::lockCtrl)`, `ModifierKey("alt", …)`, then `KeyCap` for `esc`, `tab`, `^C`, `home`, `end`, `pgup`, `pgdn`. `KeyCap` taps call `session.write(bytesFor(key), 0, bytesFor(key).size)` and, to drop a lingering one-shot, `mods.consumeOneShot()`.
    - A `VerticalDivider` (1dp, `outlineVariant`, vertical padding).
    - Right (pinned, not weighted, not scrolled): `DPad(session)` — a `Column` with a centered `↑` above a `Row` of `← ↓ →`, using the same `KeyCap` visuals sized compactly.
- `KeyCap(label, onClick)`: flat filled cap.
  ```kotlin
  Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.padding(horizontal = 3.dp),
  ) {
      Box(
          Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
              .clickable(onClick = onClick),
          contentAlignment = Alignment.Center,
      ) {
          Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.padding(horizontal = 12.dp))
      }
  }
  ```
  (`clickable` restores the ripple; the old `clickableNoRipple` helper is removed.)
- `ModifierKey(label, state, onTap, onDoubleTap)`: same cap, but its fill and text
  color derive from `state`, and it uses tap/double-tap detection:
  - `OFF` → `surfaceContainerHigh` fill, `onSurface` text.
  - `ONE_SHOT` → `primaryContainer` fill, `onPrimaryContainer` text.
  - `LOCKED` → `primary` fill, `onPrimary` text.
  - Gesture: `Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }) }`.

The bar remains outside `imePadding()`'s inner content the same way it is today
(it sits below the terminal `Box`, inside the `Column` that already has
`imePadding()`), so it rides above the soft keyboard unchanged.

## Testing (`app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt`)

Pure JUnit (package `dev.herdr.mobile`, importing `dev.herdr.mobile.ui.*`):

- **`bytesFor` sequences:** assert each `TermKey` maps to the exact bytes — e.g.
  `ESC` → `[0x1b]`, `TAB` → `[0x09]`, `CTRL_C` → `[0x03]`, `UP` → `0x1b,'[','A'`,
  `HOME` → `0x1b,'[','H'`, `END` → `0x1b,'[','F'`, `PGUP` → `0x1b,'[','5','~'`,
  `PGDN` → `0x1b,'[','6','~'`.
- **`ModifierKeys` state machine:**
  - fresh → `ctrl == OFF`, `readCtrl() == false`.
  - `tapCtrl()` → `ONE_SHOT`, `readCtrl() == true`.
  - `tapCtrl()` again → `OFF`.
  - `lockCtrl()` → `LOCKED`, `readCtrl() == true`; `tapCtrl()` from `LOCKED` → `OFF`.
  - `consumeOneShot()` clears `ONE_SHOT` (→ `OFF`) but leaves `LOCKED` untouched.
  - `ctrl` and `alt` are independent (arming one doesn't change the other).

No companion or instrumentation tests; the Compose bar itself is thin wiring over
these tested units.

## Touch Points

- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt` — new: `ModState`,
  `ModifierKeys`, `TermKey`, `bytesFor`.
- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt` — `mods`
  param; `readControlKey`/`readAltKey`/`onCodePoint` wired.
- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` — `remember { ModifierKeys() }`;
  client construction gains `mods`; `KeyToolbar`/`KeyChip`/`clickableNoRipple`
  replaced by `KeyToolbar(session, mods)` + `KeyCap` + `ModifierKey` + `DPad`.
- `app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt` — new tests.

## Non-Goals

- No modifier-encoded arrow/nav sequences (e.g. `ESC[1;5A` for Ctrl+Up) and no
  application-cursor-mode handling — the bar sends fixed normal-mode sequences,
  matching the existing arrows.
- No Shift or Fn key in the bar (soft keyboard owns Shift; no Fn need).
- No companion, wire-protocol, or terminal-emulator/terminal-view module changes.
- No change to font sizing, attach/reconnect, or clipboard behavior.

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
