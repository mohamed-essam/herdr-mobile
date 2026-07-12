# Terminal Reconnecting Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dim the terminal body with an animated "reconnecting…" overlay whenever the terminal session is not live (WS dropped / re-attaching / first-connecting).

**Architecture:** Add a pure `showReconnectOverlay(...)` decision function (unit-tested) plus a Compose scrim overlay in the existing terminal `Box`, both in `ui/TerminalScreen.kt`. Driven entirely off state already tracked (`connected`→`status`, `emulatorReady`, `takenOver`). No companion, protocol, or new persistent state.

**Tech Stack:** Kotlin, Jetpack Compose, Material3; JUnit4 unit tests (`org.junit`); Termux `TerminalView` embedded via `AndroidView`.

## Global Constraints

- The overlay is visible exactly when `emulatorReady && !takenOver && status != "connected"` (the string `"connected"` is the exact live-state value set in `attachOnce`).
- Reuse the existing `spinnerFrame()` (`@Composable fun` in `ui/StatusIndicator.kt`, same `dev.herdr.mobile.ui` package — no import needed) and `statusColor(status, dark)` for the spinner tint; do NOT add a new spinner.
- Scrim = `MaterialTheme.colorScheme.background.copy(alpha = 0.6f)`; the frozen last screen must remain faintly visible (semi-transparent, not opaque).
- The scrim must consume tap gestures so a dead terminal does not pop the soft keyboard.
- The `takenOver` overlay and all reconnect/re-attach/input logic are unchanged — this change is additive UI only.
- Android build: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- Unit tests: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`

---

### Task 1: `showReconnectOverlay` decision function + unit test

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (add one top-level function)
- Test: `app/app/src/test/java/dev/herdr/mobile/ReconnectOverlayTest.kt` (create)

**Interfaces:**
- Produces: `fun showReconnectOverlay(emulatorReady: Boolean, takenOver: Boolean, status: String): Boolean` — top-level in package `dev.herdr.mobile.ui`. Returns `emulatorReady && !takenOver && status != "connected"`. Task 2 calls it to decide whether to draw the scrim.

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/ReconnectOverlayTest.kt` (mirrors the JUnit4 style of `RowActionTest.kt` — `org.junit.Test` + `org.junit.Assert.*`):

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.showReconnectOverlay
import org.junit.Assert.*
import org.junit.Test

class ReconnectOverlayTest {
    @Test fun overlayVisibilityTruthTable() {
        // not ready yet -> never show, even if disconnected
        assertFalse(showReconnectOverlay(emulatorReady = false, takenOver = false, status = "reconnecting…"))
        // taken over owns the screen -> reconnect scrim suppressed
        assertFalse(showReconnectOverlay(emulatorReady = true, takenOver = true, status = "reconnecting…"))
        // live -> no scrim
        assertFalse(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "connected"))
        // WS dropped -> scrim
        assertTrue(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "reconnecting…"))
        // connecting / re-attaching -> scrim
        assertTrue(showReconnectOverlay(emulatorReady = true, takenOver = false, status = "connecting…"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ReconnectOverlayTest"`
Expected: FAIL — compilation error / unresolved reference `showReconnectOverlay` (the function does not exist yet).

- [ ] **Step 3: Add the function**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`, add this top-level function (place it right after the `TerminalScreen` composable's closing brace, before the `terminalSessionClient` helper):

```kotlin
/**
 * The reconnect scrim is shown while the terminal exists but is not live: the
 * WS dropped ("reconnecting…") or we are (re-)attaching ("connecting…"). It is
 * suppressed before the emulator exists and when the terminal was taken over /
 * ended (that opaque overlay owns the screen). "connected" is the sole live
 * status set by attachOnce.
 */
fun showReconnectOverlay(emulatorReady: Boolean, takenOver: Boolean, status: String): Boolean =
    emulatorReady && !takenOver && status != "connected"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.ReconnectOverlayTest"`
Expected: PASS (`BUILD SUCCESSFUL`, tests green).

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt app/app/src/test/java/dev/herdr/mobile/ReconnectOverlayTest.kt
git commit -m "feat(app): showReconnectOverlay decision function + test"
```

---

### Task 2: Draw the dim scrim + spinner overlay

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` (add imports + overlay `Box`)

**Interfaces:**
- Consumes: `showReconnectOverlay(emulatorReady, takenOver, status)` from Task 1; existing in-scope state `emulatorReady`, `takenOver`, `status` (all `remember`ed vars in `TerminalScreen`); existing `spinnerFrame()` and `statusColor(status, dark)`.

- [ ] **Step 1: Add the two new imports**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`, add these imports (the other symbols used — `background`, `fillMaxSize`, `Row`, `Spacer`, `width`, `Alignment`, `Text`, `isSystemInDarkTheme`, `statusColor`, `MaterialTheme`, `Modifier`, `dp` — are already imported; `spinnerFrame` is same-package, no import):

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
```

Place them in alphabetical position among the existing `androidx.compose.*` imports (near the top of the file).

- [ ] **Step 2: Add the overlay Box**

In the terminal `Box` (the `Box(Modifier.weight(1f).fillMaxWidth()) { … }` that contains the `AndroidView` and the `if (takenOver) { … }` overlay), add the scrim overlay immediately AFTER the closing brace of the `if (takenOver) { … }` block and before the terminal `Box`'s closing brace:

```kotlin
                if (showReconnectOverlay(emulatorReady, takenOver, status)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                            // Swallow taps so a dead terminal doesn't pop the soft keyboard.
                            .pointerInput(Unit) { detectTapGestures {} },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                spinnerFrame(),
                                color = statusColor("working", isSystemInDarkTheme()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
```

Note: `takenOver` and `showReconnectOverlay(...)` are mutually exclusive (the function returns false when `takenOver` is true), so the two overlays never draw at once. Drawing the scrim last means it layers above the `AndroidView`.

- [ ] **Step 3: Build the debug APK to verify it compiles**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the unit suite to confirm nothing regressed**

Run: `cd ~/herdr-mobile/app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests green (including `ReconnectOverlayTest`).

- [ ] **Step 5: Commit**

```bash
cd ~/herdr-mobile
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): dim terminal with reconnecting overlay"
```

---

## Manual Verification (after both tasks)

Rebuild + reinstall the app, then on device:

1. Open a terminal — a brief dim `"connecting…"` scrim shows, then the live terminal.
2. Drop connectivity (e.g. stop/rebind the companion, or toggle the phone's network) → the terminal dims (frozen screen faintly visible) with the animated ASCII spinner + `"reconnecting…"`.
3. Restore connectivity → the scrim clears and the terminal re-attaches.
4. Trigger a takeover/exit (open the same pane elsewhere) → the opaque "taken over / Reattach" overlay shows, NOT the dim scrim (they don't stack).
