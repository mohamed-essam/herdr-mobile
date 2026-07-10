# Terminal Reconnecting Overlay — Design Spec

**Date:** 2026-07-10
**Component:** herdr-mobile Android app (`ui/TerminalScreen.kt`)

## Goal

While an open terminal session is not live (its WebSocket dropped and is
reconnecting, or it is re-attaching / first-connecting), dim the entire
terminal body with a semi-transparent scrim and show an animated
"reconnecting…" indicator, so the user gets clear feedback that the terminal
is temporarily unusable.

## Problem

`TerminalScreen` already tracks connection state and maintains a `status`
string. On a mid-session WS drop it clears `termId` and sets
`status = "reconnecting…"`; on reconnect it re-attaches
(`"connecting…"` → `"connected"`). But `status` is surfaced only as a **tiny
subtitle in the top app bar** — the terminal body itself gives no indication,
so a dropped connection looks like a frozen but live terminal. Input is
silently dropped during this window (`sendInput`/`sendResize` no-op when
`termId == null`), compounding the confusion.

## Approach

Add a dim scrim + spinner overlay to the terminal `Box`, driven entirely off
state already tracked. No companion, protocol, or new persistent state.

### Visibility logic (pure, unit-testable)

A top-level function in `TerminalScreen.kt`:

```kotlin
fun showReconnectOverlay(emulatorReady: Boolean, takenOver: Boolean, status: String): Boolean =
    emulatorReady && !takenOver && status != "connected"
```

- `emulatorReady` — do not scrim before a terminal view/emulator exists.
- `!takenOver` — the existing opaque "taken over / Reattach" overlay owns the
  terminal-ended case; the two overlays are mutually exclusive.
- `status != "connected"` — covers `"reconnecting…"` (WS drop),
  `"connecting…"` (post-reconnect re-attach and initial connect), and any
  `"failed: …"` attach error. Keying on the already-maintained `status`
  string keeps the scrim smooth across the whole drop → re-attach →
  connected sequence rather than flickering when `status` transitions from
  `"reconnecting…"` to `"connecting…"` mid-recovery.

**Accepted behavior:** on first terminal open the scrim shows briefly with
`"connecting…"` before the session connects — the same "you can't type yet"
feedback, consistent with reconnect. This is intended, not a bug.

### Overlay UI

Layered inside the existing terminal `Box`, over the `AndroidView`, drawn when
`showReconnectOverlay(...)` is true:

- A full-size scrim: `Modifier.fillMaxSize().background(
  MaterialTheme.colorScheme.background.copy(alpha = 0.6f))` — the frozen last
  screen remains faintly visible behind it.
- The scrim consumes tap gestures (`pointerInput(Unit) { detectTapGestures {} }`)
  so tapping the dead terminal does not pop the soft keyboard or steal focus.
  Input is already inert during reconnect; this only suppresses the stray
  keyboard.
- Centered content (`contentAlignment = Alignment.Center`): a `Row` with the
  animated ASCII `spinnerFrame()` (reused from `ui/StatusIndicator.kt`),
  tinted with `statusColor("working", isSystemInDarkTheme())` (herdr's
  working-status color), a small `Spacer`, then a `Text(status)` in an
  on-surface color.

`spinnerFrame()` is an existing `@Composable fun` in `StatusIndicator.kt`
returning the current frame of the `"|" "/" "-" "\"` spinner; calling it here
subscribes this overlay to the same animation.

## Scope Boundary

- Only the visual overlay is added. No change to reconnect/re-attach logic,
  input handling, or the `takenOver` overlay.
- The overlay does not add a manual "retry" affordance — reconnect is
  automatic (`CompanionClient` auto-reconnects; the existing `LaunchedEffect`
  re-attaches). The `takenOver` overlay retains its `Reattach` button for the
  terminal-ended case, which is unchanged.

## Touch Points

- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`
  - add the top-level `showReconnectOverlay(...)` function.
  - add the scrim overlay `Box` inside the terminal `Box` (sibling to the
    existing `if (takenOver) { … }` overlay).
  - new imports as needed: `androidx.compose.foundation.gestures.detectTapGestures`,
    `androidx.compose.ui.input.pointer.pointerInput` (plus any already present:
    `background`, `fillMaxSize`, `Alignment`, `Row`, `Spacer`, `width`, `Text`,
    `statusColor`, `spinnerFrame`, `isSystemInDarkTheme`).
- Test: `app/app/src/test/java/dev/herdr/mobile/ReconnectOverlayTest.kt` (new)

## Testing

### Unit (JVM, `app/src/test`)

`ReconnectOverlayTest` covers the `showReconnectOverlay` truth table:

- `emulatorReady=false` → false (any status, e.g. `"reconnecting…"`).
- `emulatorReady=true, takenOver=true, status="reconnecting…"` → false
  (takenOver overlay wins).
- `emulatorReady=true, takenOver=false, status="connected"` → false.
- `emulatorReady=true, takenOver=false, status="reconnecting…"` → true.
- `emulatorReady=true, takenOver=false, status="connecting…"` → true.

### Build + manual (on-device)

- `:app:assembleDebug` succeeds.
- On device: open a terminal (brief `"connecting…"` scrim, then live). Drop
  connectivity (e.g. stop/rebind the companion, or toggle the phone's network)
  → terminal dims with the animated spinner + `"reconnecting…"`. Restore
  connectivity → scrim clears and the terminal re-attaches.

## Non-Goals

- No unit test of the Compose rendering itself (no Compose UI test harness in
  the project; the extracted pure function is the tested seam, consistent with
  existing practice).
- No change to scrollback restoration (a reconnect still opens a fresh attach;
  pre-drop scrollback is not restored — unchanged from today).
