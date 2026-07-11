# Terminal Key Bar — Match the Mockup (Rounded, Tactile, JetBrains Mono) — Design Spec

**Date:** 2026-07-11
**Component:** herdr-mobile — Android app only (`ui/TerminalScreen.kt`).

## Goal

Close the gap between the shipped key bar and the approved mockup. The layout is
correct; only the cap *styling* drifted, for three reasons found in the code:

1. **Square corners:** the caps clip to `MaterialTheme.shapes.small`, but the app's
   `HerdrShapes` sets every shape to `RoundedCornerShape(0.dp)` (deliberate square
   TUI identity). So caps render square; the mock drew them rounded.
2. **Wrong font:** caps use `FontFamily.Monospace` = the *system* monospace. The
   mock used **JetBrains Mono**, which the app bundles but only loads as a native
   `Typeface` for the terminal, never as a Compose font.
3. **Flat, not tactile:** a 1dp elevation shadow on a near-black bar reads as
   nothing, so the keycap depth never showed.

Decision (grilled): **match the mock** — rounded, tactile, JetBrains Mono caps, as
a scoped exception to the app's square identity (physical keys are rounded). Only
the keycaps change; every other panel stays square.

## Scope

Single file: `ui/TerminalScreen.kt`. No change to layout, density, the collapse
toggle, `ModifierKeys`, `bytesFor`, `TerminalKeys.kt`, the theme, or the terminal's
own native font. No new tests (pure styling; the tested primitives are untouched).

## Part 1 — Load JetBrains Mono as a Compose font

Build the family once in `KeyToolbar` from the bundled asset and thread it to every
cap (Compose BOM 2026.06.01 supports the asset-font overload):

```kotlin
val ctx = LocalContext.current
val mono = remember { FontFamily(Font("fonts/JetBrainsMono-Regular.ttf", ctx.assets)) }
```

Each cap's `Text` uses `fontFamily = mono` instead of `FontFamily.Monospace`.

## Part 2 — Rounded, tactile keycaps

Add module-level constants (the bar is always the dark terminal surface, so the
tactile fills are fixed colors; modifier armed/locked stay theme roles):

```kotlin
private val CapShape = RoundedCornerShape(9.dp)
private val CapLip = Color(0xFF15151F)
private val CapBrush = Brush.verticalGradient(listOf(Color(0xFF3E4056), Color(0xFF2A2C3D)))
private val ArrowBrush = Brush.verticalGradient(listOf(Color(0xFF4E5168), Color(0xFF363849)))
private val DpadWell = Color(0xFF11111B)
```

A shared `Keycap` renders the two-layer lip (dark base + gradient face inset 2dp at
the bottom → a crisp lip that reads on a dark ground). It replaces the repeated
`shadow→clip→background` chain (also resolving the prior review's duplication note):

```kotlin
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
```

The three cap composables become thin wrappers:

```kotlin
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

## Part 3 — Thread `mono` through the bar

`KeyToolbar` creates `mono` (Part 1) and passes it to every cap and to `DPad`;
`DPad` gains a `mono: FontFamily` parameter it forwards to `ArrowCap`. Every
`ModifierKey`/`KeyCap` call gains `mono` in the argument list (after the state/label,
before the weight modifier). The layout, order, divider, collapse tab/handle,
`send()`, and density values are otherwise unchanged.

Example (row 1):

```kotlin
ModifierKey("ctrl", mods.ctrl, mono, Modifier.weight(1f), { mods.tapCtrl() }, { mods.lockCtrl() })
ModifierKey("alt", mods.alt, mono, Modifier.weight(1f), { mods.tapAlt() }, { mods.lockAlt() })
KeyCap("esc", mono, Modifier.weight(1f)) { send(TermKey.ESC) }
KeyCap("tab", mono, Modifier.weight(1f)) { send(TermKey.TAB) }
```

and `DPad(mono) { send(it) }`, with `DPad`'s three `ArrowCap` calls passing `mono`.

`CollapseTab`/`ExpandHandle` are unchanged (their `⌄`/`⌃` glyphs keep
`FontFamily.Monospace`).

## Imports

- **Add:** `androidx.compose.ui.platform.LocalContext`, `androidx.compose.ui.text.font.Font`.
- **Remove:** `androidx.compose.ui.draw.shadow` (the two-layer lip replaces the only shadow use).
- Already present and still used: `RoundedCornerShape`, `Color`, `Brush`, `SolidColor`,
  `clip`, `background`, `clickable`, `combinedClickable`, `ExperimentalFoundationApi`,
  `FontFamily`. (`Column`, `Row`, `Box`, `BoxScope`, `height`, `width`, `fillMaxWidth`,
  `padding` come from the existing `foundation.layout.*` wildcard.)

## Testing

- No unit tests (styling only). Verified by `assembleDebug` + the existing suite
  staying green, and validated on device against the mockup (rounded corners,
  JetBrains Mono letterforms, visible bottom lip).

## Touch Points

- `app/app/src/main/java/dev/herdr/mobile/ui/TerminalScreen.kt` — `mono` font family;
  `CapShape`/`CapLip`/`CapBrush`/`ArrowBrush`/`DpadWell` constants; shared `Keycap`;
  `KeyCap`/`ArrowCap`/`ModifierKey`/`DPad`/`KeyToolbar` updated; import add/remove.

## Non-Goals

- No change to layout, density, collapse toggle, byte sequences, modifier state
  machine, or `TerminalKeys.kt`.
- No change to `HerdrShapes`/the theme — only the keycaps override to rounded; the
  rest of the app stays square.
- No new font asset (reuse the bundled `assets/fonts/JetBrainsMono-Regular.ttf`).

## Build / Test Commands

- App build: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug`
- App tests: `cd app && ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:testDebugUnitTest`
