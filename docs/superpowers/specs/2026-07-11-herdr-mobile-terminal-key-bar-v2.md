# Terminal Key Bar v2 — Two-Row Grid, Tactile, Collapsible — Design Spec

**Date:** 2026-07-11
**Component:** herdr-mobile — Android app only (`ui/`); no companion change.

## Goal

Rework the terminal key bar so it no longer scrolls horizontally and reads as
polished rather than "ugly", while giving as much height as possible back to the
terminal. Every key is visible at once in a fixed two-row grid; the caps get a
tactile treatment; and the whole bar collapses to a thin handle on demand.

This supersedes the v1 layout (a horizontally-scrolling strip + pinned d-pad). The
v1 mechanics that work — the sticky-modifier state machine, the byte sequences,
one-shot + long-press lock, ripple — are kept unchanged.

## Decisions (grilled, with rendered mockups)

- **Layout:** a fixed **4×2** block of specials on the left + a divider + a grouped
  **arrow d-pad** on the right, spanning both rows. Nothing scrolls; every key
  shows at once.
  - Row 1: `ctrl  alt  esc  tab`  · Row 2: `home  end  pgup  pgdn` (equal width).
  - `ctrl`/`alt` are the sticky modifiers; the rest send bytes.
  - D-pad: `↑` centered over `← ↓ →`, on a recessed "well" so the four arrows read
    as one navigation control.
- **`^C` removed** — redundant now that Ctrl is sticky (arm Ctrl, tap `c`). This
  frees the 8th special slot so the left block is exactly 4×2.
- **Tactile caps:** a soft top-light gradient + a 1dp drop shadow so caps read as
  physical keys. The depth is shadow/gradient only — **no extra height**.
- **Compact density:** 34dp cap min-height, 4dp gaps, 6dp bar padding (~84–88px
  expanded, vs the ~102px v1 default). Two key-rows is the structural floor for
  this layout (the d-pad cross needs two rows), so density is the only height lever
  short of hiding the bar.
- **Manual collapse toggle:** a slim vertical chevron tab (`⌄`) at the right edge
  of the expanded bar — it costs **width, not height**, so expanded stays compact.
  Tapping it collapses the bar to a thin full-width handle (`⌃`, ~20dp) that
  reclaims ~4 terminal lines; tapping the handle restores the bar. State is
  remembered across recomposition/rotation (`rememberSaveable`), default expanded.
  The modifier state persists across a collapse, so typing still applies an armed
  modifier while the bar is hidden.

## Part 1 — `ui/TerminalKeys.kt` (cleanup only)

Remove the now-unused `^C` key:
- Delete `CTRL_C` from `enum class TermKey`.
- Delete the `TermKey.CTRL_C -> byteArrayOf(0x03)` branch from `bytesFor`.

Nothing else in this file changes (the `ModifierKeys` state machine and all other
`bytesFor` sequences stay exactly as shipped).

**Ordering:** the current bar references `TermKey.CTRL_C`, so this removal must land
*after* Part 2 drops the `^C` key — otherwise the build breaks mid-change. The plan
must sequence the bar rewrite first, then this cleanup.

## Part 2 — `ui/TerminalScreen.kt` (bar rewrite)

Replace `KeyToolbar` / `KeyCap` / `ModifierKey` / `DPad` (the whole block,
currently lines 284-388) with the composables below. The bar call site stays
`session?.let { KeyToolbar(it, mods) }`.

The bar is always the dark Catppuccin terminal surface, so the tactile fills are
fixed colors (matching the approved mockup); the modifier armed/locked states use
theme roles so they track the accent.

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

**Imports to add** to `TerminalScreen.kt` (all `androidx.compose.*`):
`foundation.shape.RoundedCornerShape`, `ui.draw.clip`, `ui.draw.shadow`,
`ui.graphics.Brush`, `ui.graphics.Color`, `ui.graphics.SolidColor`,
`runtime.saveable.rememberSaveable`, `ui.semantics.contentDescription`,
`ui.semantics.semantics`.

**Imports to remove** (only the old scrolling strip used them):
`foundation.horizontalScroll`, `foundation.rememberScrollState`.

`combinedClickable` / `ExperimentalFoundationApi` (added in v1's fix), `background`,
`clickable`, `FontFamily` remain in use. `VerticalDivider`, `Surface`, `Text`,
`MaterialTheme` come from the existing `material3.*` wildcard; `Arrangement`,
`Spacer`, `Column`, `Row`, `Box`, `size`, `width`, `height`, `defaultMinSize` from
the existing `layout.*` wildcard.

## Part 3 — `TerminalKeysTest.kt`

The existing `bytesForControlKeys` test asserts `ESC`, `TAB`, and `CTRL_C`. Remove
the `CTRL_C` assertion; keep `ESC`/`TAB`. All other tests are unchanged.

## Testing

- Companion: none (app-only).
- Unit: the pure logic (`ModifierKeys`, `bytesFor` for the remaining keys) stays
  covered by `TerminalKeysTest`; the `CTRL_C` assertion is dropped with its key.
- The bar itself (layout, tactile styling, collapse toggle) is Compose wiring over
  the tested primitive — verified by `assembleDebug` + the existing suite staying
  green, and validated on device. No new unit tests.

## Touch Points

- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalKeys.kt` — remove `TermKey.CTRL_C` + its `bytesFor` branch.
- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` — rewrite the bar composables; add/remove imports.
- `app/app/src/test/java/dev/herdr/mobile/TerminalKeysTest.kt` — drop the `CTRL_C` assertion.

## Non-Goals

- No change to the modifier state machine, byte sequences, long-press-lock, or ripple.
- No auto-hide-with-keyboard (chose manual toggle); no animation requirement on the collapse (a plain show/hide is fine).
- No modifier-encoded arrow/nav sequences; no application-cursor-mode handling.
- No companion / wire / terminal-view / terminal-emulator changes.

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
