# herdr-mobile — terminal polish (font size, keyboard resize, graceful takeover)

**Status:** design approved 2026-07-08
**Predecessor:** `docs/superpowers/specs/2026-07-08-herdr-mobile-terminal-design.md` (interactive terminal, shipped)

## Summary

Three app-side fixes to the interactive terminal:

1. **Configurable font size** — replace the hardcoded `setTextSize(36)` px with a
   density-scaled default, enable pinch-to-zoom (already routed by the vendored
   `TerminalView` but no-op'd in our client), and persist the chosen size to the
   existing DataStore.
2. **Keyboard resizes the terminal** — the soft keyboard currently overlays the
   terminal; it should shrink the terminal (via IME insets), which re-renders
   herdr at the smaller size through the existing resize path.
3. **Graceful takeover** — when the phone's terminal is taken over elsewhere (or
   the attach otherwise ends), show a clean "taken over / ended — Reattach"
   overlay instead of herdr's raw "terminal taken over" text, with a button that
   re-attaches.

All three are app-only. No companion or protocol change.

## Goals

- Terminal text is a sensible size on any device density, and the user can
  pinch-to-zoom it; the chosen size survives app restarts.
- Bringing up the soft keyboard shrinks the terminal so the prompt stays visible,
  and herdr re-renders at the new geometry.
- Losing the attachment (takeover, or any `term_exit`) shows a clear, actionable
  state, not raw terminal bleed-through, and the user can reattach in one tap.

## Non-goals (explicitly deferred)

- **A settings screen.** The font size is stored and pinch-adjustable now; a UI
  slider/settings page comes later and will drive the same stored value.
- **Changing the takeover policy.** The phone keeps taking over unconditionally
  on open (the user's choice); we only make the *reverse* (phone getting taken
  over) graceful. No "ask before stealing" flow.
- **Companion / protocol changes.** `term_*` frames and `companionProtocol` are
  unchanged.
- **Font family selection**, per-pane font sizes, or reflow-on-resize semantics
  beyond what the Termux engine already does.

## Verified assumptions (from the current code)

- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt:99` sets
  `setTextSize(36)` (a fixed px value; `TerminalView.setTextSize(int)` takes px).
- The vendored `TerminalView` handles pinch: `onScale(...)` calls
  `mScaleFactor = mClient.onScale(mScaleFactor)`
  (`app/terminal-view/.../TerminalView.java:189-193`). Our
  `TerminalViewClientImpl.onScale(scale) = 1.0f` no-ops it.
- `MainActivity` calls `enableEdgeToEdge()`; there is **no** `windowSoftInputMode`
  in `AndroidManifest.xml`, and `TerminalScreen`'s `AndroidView` uses
  `Modifier.padding(pad).fillMaxSize()` with no `imePadding()`.
- The resize path already exists: `RemoteTerminalSession.updateSize` (invoked by
  `TerminalView.onSizeChanged`) recomputes geometry and calls `Io.sendResize` →
  `vm.termResize` → `term_resize` → companion `pty.Resize`. Rotation already uses
  it; keyboard-driven shrink will reuse it.
- The DataStore lives in `app/app/src/main/java/dev/herdr/mobile/data/Settings.kt`
  (`preferencesDataStore("settings")`, `stringPreferencesKey` for `companion_url`
  and `push_endpoint`).
- On `term_exit`, `TerminalScreen` currently sets `status = "session ended (code)"`
  and leaves the (now stale) terminal render visible. The "terminal taken over"
  wording is herdr's own output, fed into the emulator before the attach process
  exits — not our string.

## Design

### 1. Font size

**Sizing constants (density-scaled).** Derive from
`resources.displayMetrics.density` at the call site (a pure helper takes density
+ stored value and returns px):

- `default = round(16 * density)`
- `min = round(8 * density)`
- `max = round(32 * density)`
- `step = round(2 * density)` (per pinch threshold)

**Persistence.** `Settings` gains:

```kotlin
val terminalFontSize: Flow<Int?> = context.dataStore.data.map { it[FONT_SIZE_KEY] }
suspend fun setTerminalFontSize(px: Int) { context.dataStore.edit { it[FONT_SIZE_KEY] = px } }
// private val FONT_SIZE_KEY = intPreferencesKey("terminal_font_size")
```

**ViewModel wiring.** `DashboardViewModel` takes a `Settings` (added to its
constructor; `MainActivity` already builds one). It exposes:

- `terminalFontSize: StateFlow<Int?>` — the raw stored value (null until the user
  has ever zoomed); the screen fills the density default when null.
- `fun setTerminalFontSize(px: Int)` — persists via `viewModelScope.launch`.

**Pinch behavior.** A pure helper computes the next size:

```kotlin
// returns null if the accumulated scale hasn't crossed a step threshold yet
fun steppedFontSize(currentPx: Int, scale: Float, minPx: Int, maxPx: Int, stepPx: Int): Int?
```

`TerminalViewClientImpl` holds the current px (seeded from the stored/default
value), an `onFontSizeChanged: (Int) -> Unit` callback, and the min/max/step. Its
`onScale(scale)` implementation:

- If `scale` crosses the zoom-in threshold (`> 1.1f`) or zoom-out (`< 0.9f`):
  compute the new clamped size, if it changed call `view.setTextSize(newPx)` +
  `onFontSizeChanged(newPx)` + update its held px, and **return `1.0f`** (reset
  the accumulator).
- Otherwise return `scale` (keep accumulating).

**Screen wiring.** `TerminalScreen` collects `terminalFontSize`, computes the px
to use (`stored ?: densityDefault`), calls `setTextSize(px)` in the `factory`,
and passes `onFontSizeChanged = { vm.setTerminalFontSize(it) }` into the client.
Because the stored value seeds `setTextSize` on open, a size chosen on one
terminal applies to the next and survives restart.

### 2. Keyboard resize

- Add `android:windowSoftInputMode="adjustResize"` to the `<activity>` in
  `AndroidManifest.xml`.
- In `TerminalScreen`, add `.imePadding()` to the terminal `AndroidView`'s
  modifier (alongside the existing `padding(pad)` and `fillMaxSize()`), so the
  view's height shrinks when the IME appears. The shrink triggers
  `TerminalView.onSizeChanged → RemoteTerminalSession.updateSize →
  Io.sendResize → term_resize`, so herdr re-renders at the new geometry with no
  bytes dropped (same path rotation uses).
- The key toolbar (`bottomBar`) stays reachable above the keyboard.

### 3. Graceful takeover / reattach

- Track whether the exit was user-initiated. On `term_exit` for the active
  `termId` that the user did **not** trigger (i.e. still on the screen), set a
  state that shows an **overlay** over the terminal: a centered
  `⚠ terminal ended or was taken over elsewhere` message + a **[Reattach]**
  button. The overlay hides the stale/raw terminal render.
- **Reattach** clears the dead `termId`, resets status to `connecting…`, and
  opens a fresh attach (`vm.openTerminal(pane.paneId, cols, rows)` with the
  emulator's current `mColumns`/`mRows`), then dismisses the overlay on success.
  This reuses the existing open logic; refactor the open into a callable action
  so both the initial/auto-reconnect path and the Reattach button use it.
- The existing WS-drop reconnect behavior (clear termId, "reconnecting…",
  auto-reattach on reconnect) is unchanged; the new overlay is specifically for
  `term_exit` while still connected.

## Global constraints

- App-only: no changes to the companion, `proto`, or `companionProtocol`.
- Reuse the existing theme (`statusColor`/`statusGlyph`/typography/Catppuccin) and
  the existing DataStore (`preferencesDataStore("settings")`).
- Reuse the existing resize path (`updateSize → sendResize → term_resize`) for
  keyboard resize — do not add a second resize mechanism.
- The font-size default must be density-derived (not a fixed px), clamped to
  `[min, max]`; the stored value wins when present.
- No new app→companion frames.

## Testing

- **Unit tests (JVM, no device):**
  - `Settings` `terminal_font_size` round-trip (write → read).
  - `steppedFontSize` pure helper: zoom-in steps up and clamps at `max`; zoom-out
    steps down and clamps at `min`; sub-threshold scale returns null (no change).
  - `DashboardViewModel` exposes `terminalFontSize` and `setTerminalFontSize`
    persists (assert the flow reflects a set value, mirroring existing VM tests).
- **Live (emulator + phone):**
  - Font: default renders at a sensible size on the phone; pinch-in/out changes
    it smoothly and clamps; the size persists across closing/reopening the
    terminal and across an app restart.
  - Keyboard: focusing the terminal brings up the IME and the terminal shrinks
    above it (prompt visible), and herdr reflows to the new width/height; hiding
    the keyboard restores full size.
  - Takeover: force a takeover (open the same pane on a second client) → the
    phone shows the "taken over elsewhere — Reattach" overlay (not raw text) →
    Reattach returns to a live terminal.

## Rollout / commits (high level)

1. `Settings` + `DashboardViewModel` font-size storage + `steppedFontSize` helper
   + unit tests.
2. `TerminalViewClientImpl.onScale` pinch implementation + `TerminalScreen`
   density default / seed / persist wiring.
3. Keyboard resize: manifest `adjustResize` + `imePadding()`.
4. Graceful takeover overlay + Reattach action.
5. Live validation on the emulator + phone; docs/memory update.
