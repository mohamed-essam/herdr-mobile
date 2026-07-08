# herdr-mobile Terminal Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the terminal font size configurable (density default + pinch-to-zoom + persisted), make the soft keyboard shrink the terminal, and replace herdr's raw "terminal taken over" bleed-through with a clean Reattach overlay.

**Architecture:** All app-side. Font size persists in the existing DataStore and is injected into the ViewModel as a flow + persist lambda (no Android Context in the VM, so JVM tests keep working); a pure `steppedFontSize` helper drives pinch. Keyboard resize uses `imePadding()` on the terminal+toolbar column, reusing the existing `updateSize → sendResize → term_resize` path. Takeover shows an overlay on `term_exit` with a Reattach action that reuses the attach logic.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), AndroidX DataStore (preferences), vendored Termux `terminal-view`/`terminal-emulator`.

## Global Constraints

- App-only: no changes to the companion, `proto`, `companionProtocol`, or WS frames.
- Reuse the existing DataStore (`preferencesDataStore("settings")` in `data/Settings.kt`) and the existing theme (Catppuccin/typography).
- Reuse the existing resize path (`RemoteTerminalSession.updateSize → Io.sendResize → vm.termResize → term_resize`); do not add a second resize mechanism.
- Font default MUST be density-derived (not fixed px), clamped to `[min, max]`; a stored value wins when present.
- `DashboardViewModel`'s new font members MUST be constructor params with defaults so existing `DashboardViewModel(client, repo)` call sites and tests keep compiling.
- Keep the phone's unconditional `--takeover` on open; only make the reverse (phone getting taken over) graceful.

---

### Task 1: Font-size storage — Settings key + ViewModel members + MainActivity wiring

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt`

**Interfaces:**
- Consumes: existing `Settings` DataStore, `DashboardViewModel(client, repo)`.
- Produces:
  - `Settings.terminalFontSize: Flow<Int?>` + `suspend fun setTerminalFontSize(px: Int)` (key `intPreferencesKey("terminal_font_size")`)
  - `DashboardViewModel(client, repo, fontSizeStore: Flow<Int?> = MutableStateFlow(null), persistFontSize: (Int) -> Unit = {})`
  - `DashboardViewModel.terminalFontSize: StateFlow<Int?>` (from `fontSizeStore`, eager, initial null)
  - `DashboardViewModel.setTerminalFontSize(px: Int)` (calls `persistFontSize`)

- [ ] **Step 1: Add the DataStore key + accessors**

In `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`, add the import `androidx.datastore.preferences.core.intPreferencesKey`, a key, and the two accessors:

```kotlin
private val FONT_SIZE_KEY = intPreferencesKey("terminal_font_size")
```

Inside `class Settings`, after the `pushEndpoint` members:

```kotlin
    val terminalFontSize: Flow<Int?> = context.dataStore.data.map { it[FONT_SIZE_KEY] }
    suspend fun setTerminalFontSize(px: Int) { context.dataStore.edit { it[FONT_SIZE_KEY] = px } }
```

- [ ] **Step 2: Add font members to the ViewModel**

In `app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt`, add imports `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.stateIn` (some may already be present from the tree work — don't duplicate). Change the constructor and add the members:

```kotlin
class DashboardViewModel(
    private val client: CompanionClient,
    private val repo: PaneRepository,
    fontSizeStore: Flow<Int?> = MutableStateFlow(null),
    private val persistFontSize: (Int) -> Unit = {},
) : ViewModel() {
```

Add (near the other StateFlows):

```kotlin
    val terminalFontSize: StateFlow<Int?> =
        fontSizeStore.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setTerminalFontSize(px: Int) = persistFontSize(px)
```

Leave all existing members (`tree`, `collapsed`, `panes`, terminal passthroughs, etc.) unchanged.

- [ ] **Step 3: Wire Settings into the ViewModel in MainActivity**

In `app/app/src/main/java/dev/herdr/mobile/MainActivity.kt`, the `settings` is already built before the VM. Change the VM construction (currently `val vm = DashboardViewModel(CompanionClient(), PaneRepository())`) to:

```kotlin
        val settings = Settings(applicationContext)
        val vm = DashboardViewModel(
            CompanionClient(),
            PaneRepository(),
            fontSizeStore = settings.terminalFontSize,
            persistFontSize = { px -> lifecycleScope.launch { settings.setTerminalFontSize(px) } },
        )
```

(`lifecycleScope` and `kotlinx.coroutines.launch` are already imported. Keep the existing `settings` usages for `companionUrl`/`pushEndpoint`.)

- [ ] **Step 4: Write the failing test**

Append to `app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt` (its `@Before` already sets `Dispatchers.setMain(Dispatchers.Unconfined)`; add imports `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.delay`, `kotlinx.coroutines.withTimeout`, `kotlinx.coroutines.runBlocking` if not present):

```kotlin
    @Test fun terminalFontSizeReflectsStoreAndPersistCallsBack() = runBlocking {
        var persisted: Int? = null
        val vm = DashboardViewModel(
            CompanionClient(), PaneRepository(),
            fontSizeStore = MutableStateFlow(28),
            persistFontSize = { persisted = it },
        )
        withTimeout(1000) { while (vm.terminalFontSize.value == null) delay(10) }
        assertEquals(28, vm.terminalFontSize.value)
        vm.setTerminalFontSize(44)
        assertEquals(44, persisted)
    }
```

- [ ] **Step 5: Run test to verify it fails, then passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.DashboardViewModelTest"`
Expected: FAILS to compile before Steps 2-3 (unresolved `terminalFontSize`), PASSES after (existing VM tests + the new one).

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/data/Settings.kt app/app/src/main/java/dev/herdr/mobile/ui/DashboardViewModel.kt app/app/src/main/java/dev/herdr/mobile/MainActivity.kt app/app/src/test/java/dev/herdr/mobile/DashboardViewModelTest.kt
git commit -m "feat(app): persist terminal font size (Settings + ViewModel)"
```

---

### Task 2: Pure font-size helpers (density bounds + stepping)

**Files:**
- Create: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalFont.kt`
- Test: `app/app/src/test/java/dev/herdr/mobile/TerminalFontTest.kt`

**Interfaces:**
- Produces:
  - `data class FontBounds(val default: Int, val min: Int, val max: Int, val step: Int)`
  - `fun fontBounds(density: Float): FontBounds` — `default=round(16*d), min=round(8*d), max=round(32*d), step=round(2*d)`
  - `fun steppedFontSize(currentPx: Int, scale: Float, bounds: FontBounds): Int?` — null if `scale` is sub-threshold (`0.9f..1.1f`); otherwise the new clamped size (may equal `currentPx` at a bound)

- [ ] **Step 1: Write the failing test**

Create `app/app/src/test/java/dev/herdr/mobile/TerminalFontTest.kt`:

```kotlin
package dev.herdr.mobile

import dev.herdr.mobile.ui.FontBounds
import dev.herdr.mobile.ui.fontBounds
import dev.herdr.mobile.ui.steppedFontSize
import org.junit.Assert.*
import org.junit.Test

class TerminalFontTest {
    private val b = FontBounds(default = 32, min = 16, max = 64, step = 4)

    @Test fun boundsScaleWithDensity() {
        val f = fontBounds(2.0f)
        assertEquals(32, f.default) // 16*2
        assertEquals(16, f.min)     // 8*2
        assertEquals(64, f.max)     // 32*2
        assertEquals(4, f.step)     // 2*2
    }

    @Test fun subThresholdScaleReturnsNull() {
        assertNull(steppedFontSize(32, 1.0f, b))
        assertNull(steppedFontSize(32, 1.05f, b))
        assertNull(steppedFontSize(32, 0.95f, b))
    }

    @Test fun zoomInStepsUpAndClamps() {
        assertEquals(36, steppedFontSize(32, 1.2f, b)) // +step
        assertEquals(64, steppedFontSize(64, 1.2f, b)) // already max -> stays max
    }

    @Test fun zoomOutStepsDownAndClamps() {
        assertEquals(28, steppedFontSize(32, 0.8f, b)) // -step
        assertEquals(16, steppedFontSize(16, 0.8f, b)) // already min -> stays min
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalFontTest"`
Expected: FAIL to compile ("unresolved reference: fontBounds").

- [ ] **Step 3: Write the implementation**

Create `app/app/src/main/java/dev/herdr/mobile/ui/TerminalFont.kt`:

```kotlin
package dev.herdr.mobile.ui

import kotlin.math.roundToInt

/** Terminal font-size bounds in px, derived from screen density. */
data class FontBounds(val default: Int, val min: Int, val max: Int, val step: Int)

fun fontBounds(density: Float): FontBounds = FontBounds(
    default = (16f * density).roundToInt(),
    min = (8f * density).roundToInt(),
    max = (32f * density).roundToInt(),
    step = (2f * density).roundToInt(),
)

/**
 * Next terminal font size for a pinch [scale]. Returns null while the pinch is
 * sub-threshold (accumulate more); otherwise the new size clamped to
 * [FontBounds.min]..[FontBounds.max] (may equal [currentPx] at a bound).
 */
fun steppedFontSize(currentPx: Int, scale: Float, bounds: FontBounds): Int? = when {
    scale > 1.1f -> (currentPx + bounds.step).coerceIn(bounds.min, bounds.max)
    scale < 0.9f -> (currentPx - bounds.step).coerceIn(bounds.min, bounds.max)
    else -> null
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "dev.herdr.mobile.TerminalFontTest"`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalFont.kt app/app/src/test/java/dev/herdr/mobile/TerminalFontTest.kt
git commit -m "feat(app): pure terminal font-size bounds + stepping helper"
```

---

### Task 3: Pinch-to-zoom + density default in the terminal

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`

**Interfaces:**
- Consumes: `FontBounds`, `fontBounds`, `steppedFontSize` (Task 2); `DashboardViewModel.terminalFontSize`/`setTerminalFontSize` (Task 1).
- Produces:
  - `TerminalViewClientImpl(view: TerminalView, initialPx: Int, bounds: FontBounds, onFontSizeChanged: (Int) -> Unit)` with a working `onScale` and `fun applyFontSize(px: Int)` (applies without notifying).

- [ ] **Step 1: Implement pinch in the client**

Rewrite `app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt`'s constructor and `onScale` (keep every other override exactly as-is):

```kotlin
class TerminalViewClientImpl(
    private val view: TerminalView,
    initialPx: Int,
    private val bounds: FontBounds,
    private val onFontSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
    private var textSizePx = initialPx

    /** Apply a size without notifying the persist callback (used to seed a stored value). */
    fun applyFontSize(px: Int) {
        textSizePx = px
        view.setTextSize(px)
    }

    override fun onScale(scale: Float): Float {
        val next = steppedFontSize(textSizePx, scale, bounds) ?: return scale
        if (next != textSizePx) {
            textSizePx = next
            view.setTextSize(next)
            onFontSizeChanged(next)
        }
        return 1.0f // threshold crossed: reset the gesture accumulator
    }
```

(The existing `onSingleTapUp`, `shouldEnforceCharBasedInput`, all `log*`, etc. remain unchanged below.)

- [ ] **Step 2: Seed + persist font size in TerminalScreen**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`:

Add state near the other `remember`s (after `var view by ...`):

```kotlin
    var client by remember { mutableStateOf<TerminalViewClientImpl?>(null) }
    val storedFont by vm.terminalFontSize.collectAsState()
```

In the `AndroidView` `factory`, replace the `setTextSize(36)` + `setTerminalViewClient(TerminalViewClientImpl(this))` lines with:

```kotlin
                    val density = ctx.resources.displayMetrics.density
                    val bounds = fontBounds(density)
                    val initialPx = storedFont ?: bounds.default
                    val c = TerminalViewClientImpl(this, initialPx, bounds) { vm.setTerminalFontSize(it) }
                    client = c
                    setTextSize(initialPx)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTerminalViewClient(c)
```

(Everything else in the factory — `RemoteTerminalSession`, `attachSession`, `doOnLayout`, readiness — stays the same.)

Add a late-apply effect (in case the stored value loads after the factory ran) alongside the other `LaunchedEffect`s:

```kotlin
    // Apply a stored font size that arrives after the view was created.
    LaunchedEffect(storedFont) {
        val px = storedFont ?: return@LaunchedEffect
        client?.applyFontSize(px)
    }
```

- [ ] **Step 3: Build + run the app unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests green (no test changes here — this is UI wiring verified by compilation + the existing suite).

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalViewClientImpl.kt app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): pinch-to-zoom + density-scaled terminal font size"
```

---

### Task 4: Keyboard resizes the terminal

**Files:**
- Modify: `app/app/src/main/AndroidManifest.xml`
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`

**Interfaces:**
- Consumes: existing `RemoteTerminalSession.updateSize → Io.sendResize` resize path.
- Produces: terminal + key toolbar sit in an `imePadding()` column so the keyboard shrinks the terminal (triggering `term_resize`) and the toolbar stays above the keyboard.

- [ ] **Step 1: Add adjustResize to the activity**

In `app/app/src/main/AndroidManifest.xml`, add `android:windowSoftInputMode="adjustResize"` to the `<activity>` element that hosts `MainActivity` (the one with the LAUNCHER intent filter). Example (attributes may differ — add only the one attribute):

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
```

- [ ] **Step 2: Move the toolbar into an imePadding column**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`, the `Scaffold` currently has `bottomBar = { session?.let { KeyToolbar(it) } }` and an `AndroidView` as the content. Change to: remove the `bottomBar` parameter, and make the content a `Column` with `imePadding()` holding the terminal (weighted) and the toolbar. Add imports `androidx.compose.foundation.layout.imePadding` and (if not present) `androidx.compose.foundation.layout.Column`.

Replace the `Scaffold(...) { pad -> AndroidView(...) }` content block with:

```kotlin
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
                },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    // ... unchanged factory body from Task 3 ...
                },
            )
            session?.let { KeyToolbar(it) }
        }
    }
```

(Keep the exact factory body produced by Task 3. The only structural changes: `bottomBar` removed, terminal wrapped in a weighted `Column` with `.imePadding()`, toolbar moved below the terminal.)

- [ ] **Step 3: Build + run the app unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; unit tests green.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/AndroidManifest.xml app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): soft keyboard resizes the terminal (imePadding + adjustResize)"
```

---

### Task 5: Graceful takeover overlay + Reattach

**Files:**
- Modify: `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`

**Interfaces:**
- Consumes: `vm.openTerminal`, `vm.closeTerminal`, the emulator's `mColumns`/`mRows`, existing `termId`/`status`/`view`/`emulatorReady` state.
- Produces: an overlay shown on `term_exit` (not user-initiated) with a Reattach button that opens a fresh attach.

- [ ] **Step 1: Extract the attach action + add takenOver state**

In `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt`, add state and a shared attach function near the top of the composable (after the existing `remember`s), plus `rememberCoroutineScope`:

```kotlin
    var takenOver by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun attachOnce() {
        val emu = view?.mEmulator
        val cols = emu?.mColumns ?: 80
        val rows = emu?.mRows ?: 24
        status = "connecting…"
        runCatching { vm.openTerminal(pane.paneId, cols, rows) }
            .onSuccess { termId = it; status = "connected"; takenOver = false }
            .onFailure { status = "failed: ${it.message}" }
    }
```

Add the imports `androidx.compose.runtime.rememberCoroutineScope`, `androidx.compose.foundation.layout.Box`, `androidx.compose.ui.Alignment`, `androidx.compose.material3.Button`, and `androidx.compose.ui.text.style.TextAlign` if not already present.

- [ ] **Step 2: Route the (re)attach effect and term_exit through the new state**

Change the existing `LaunchedEffect(connected, emulatorReady)` open block so its success path calls `attachOnce()` instead of inlining `openTerminal` (keep the disconnect handling identical):

```kotlin
    LaunchedEffect(connected, emulatorReady) {
        if (!connected) {
            if (termId != null) termId = null
            if (emulatorReady) status = "reconnecting…"
            return@LaunchedEffect
        }
        if (!emulatorReady || termId != null) return@LaunchedEffect
        attachOnce()
    }
```

In the `LaunchedEffect(termId)` frame collector, change the `TermExit` branch to mark the takeover state and clear the dead id (a `term_exit` while we're still on the screen means the attach ended out from under us — takeover or the agent process ended):

```kotlin
                is ServerFrame.TermExit -> if (f.termId == id) {
                    status = "taken over elsewhere"
                    termId = null
                    takenOver = true
                }
```

- [ ] **Step 3: Show the overlay over the terminal**

Wrap the terminal `AndroidView` (inside the weighted `Column` from Task 4) in a `Box` so the overlay can sit on top, and render the overlay when `takenOver` is true. Replace the `AndroidView(modifier = Modifier.weight(1f)...)` with:

```kotlin
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // ... unchanged factory body ...
                    },
                )
                if (takenOver) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("⚠", style = MaterialTheme.typography.headlineMedium, color = statusColor("blocked", isSystemInDarkTheme()))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "terminal ended or was taken over elsewhere",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { scope.launch { attachOnce() } }, shape = MaterialTheme.shapes.small) {
                            Text("Reattach")
                        }
                    }
                }
            }
```

Add imports `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.isSystemInDarkTheme`, `dev.herdr.mobile.ui.theme.statusColor`, `kotlinx.coroutines.launch`.

Reattach clears `takenOver` on success (via `attachOnce`). The `DisposableEffect(Unit) { onDispose { termId?.let { vm.closeTerminal(it) } } }` stays unchanged — leaving the screen still closes any live session, and a `term_exit` from that close is not processed because the frame collector is cancelled on dispose.

- [ ] **Step 4: Build + run the app unit suite**

Run: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; unit tests green.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt
git commit -m "feat(app): graceful 'taken over' overlay with Reattach"
```

---

### Task 6: Live validation (controller)

**Files:** none (validation only).

This task is performed by the controller on the emulator + physical phone; no code changes.

- [ ] **Step 1: Deploy** — rebuild the APK (`./gradlew :app:assembleDebug`), install on the emulator and the phone. (Companion is unchanged; no redeploy needed.)
- [ ] **Step 2: Font** — open a terminal on the phone: default text is a sensible size; pinch-in/out changes it and clamps at the bounds; close and reopen the terminal → size persists; kill and relaunch the app → size still persists.
- [ ] **Step 3: Keyboard** — tap the terminal to raise the IME: the terminal shrinks above the keyboard (prompt visible), herdr reflows to the new geometry, and the key toolbar remains reachable above the keyboard; dismiss the IME → terminal restores.
- [ ] **Step 4: Takeover** — with a terminal open on the phone, open the same pane on the emulator (forces a `--takeover`): the phone shows the "terminal ended or was taken over elsewhere — Reattach" overlay (no raw text bleed); tap Reattach → live terminal returns.
- [ ] **Step 5: Update docs/memory** — record the shipped polish and any gotchas.

---

## Notes for the executor

- The `Settings` DataStore key itself is thin glue (mirrors the existing untested `companion_url`/`push_endpoint` keys) and needs an Android `Context`/Robolectric to unit-test, which this project doesn't set up. The real logic is covered by `TerminalFontTest` (stepping/bounds) and `DashboardViewModelTest` (store→flow, persist callback); the key wiring is verified by build + live validation. This is a deliberate, scoped testing decision, not a gap to fill with a Context-dependent test.
- Tasks 3, 4, and 5 all edit `TerminalScreen.kt` sequentially; each builds on the file state left by the previous task (the factory body from Task 3 is preserved verbatim through Tasks 4-5).
