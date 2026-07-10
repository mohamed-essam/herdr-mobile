package dev.herdr.mobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * herdr's look is a dark, terminal-first Catppuccin palette (the default theme
 * on herdr.dev). We mirror it: Mocha for dark, Latte for light, with the same
 * status semantics herdr uses for agents (blocked/working/idle/done).
 * https://catppuccin.com
 */

// ── Catppuccin Mocha (dark) ───────────────────────────────────────────────
private object Mocha {
    val crust = Color(0xFF11111B)
    val mantle = Color(0xFF181825)
    val base = Color(0xFF1E1E2E)
    val surface0 = Color(0xFF313244)
    val surface1 = Color(0xFF45475A)
    val surface2 = Color(0xFF585B70)
    val overlay0 = Color(0xFF6C7086)
    val subtext1 = Color(0xFFBAC2DE)
    val text = Color(0xFFCDD6F4)
    val mauve = Color(0xFFCBA6F7)
    val blue = Color(0xFF89B4FA)
    val green = Color(0xFFA6E3A1)
    val yellow = Color(0xFFF9E2AF)
    val peach = Color(0xFFFAB387)
    val red = Color(0xFFF38BA8)
    val overlay2 = Color(0xFF9399B2)
}

// ── Catppuccin Latte (light) ──────────────────────────────────────────────
private object Latte {
    val crust = Color(0xFFDCE0E8)
    val base = Color(0xFFEFF1F5)
    val surface0 = Color(0xFFCCD0DA)
    val surface1 = Color(0xFFBCC0CC)
    val overlay0 = Color(0xFF9CA0B0)
    val subtext1 = Color(0xFF5C5F77)
    val text = Color(0xFF4C4F69)
    val mauve = Color(0xFF8839EF)
    val blue = Color(0xFF1E66F5)
    val green = Color(0xFF40A02B)
    val yellow = Color(0xFFDF8E1D)
    val peach = Color(0xFFFE640B)
    val red = Color(0xFFD20F39)
}

val MochaColorScheme = darkColorScheme(
    primary = Mocha.mauve,
    onPrimary = Mocha.crust,
    primaryContainer = Mocha.surface1,
    onPrimaryContainer = Mocha.mauve,
    secondary = Mocha.blue,
    onSecondary = Mocha.crust,
    tertiary = Mocha.green,
    onTertiary = Mocha.crust,
    background = Mocha.crust,
    onBackground = Mocha.text,
    surface = Mocha.base,
    onSurface = Mocha.text,
    surfaceVariant = Mocha.surface0,
    onSurfaceVariant = Mocha.subtext1,
    surfaceContainer = Mocha.mantle,
    surfaceContainerLow = Mocha.crust,
    surfaceContainerHigh = Mocha.surface0,
    surfaceContainerHighest = Mocha.surface1,
    outline = Mocha.surface2,
    outlineVariant = Mocha.surface0,
    error = Mocha.red,
    onError = Mocha.crust,
)

val LatteColorScheme = lightColorScheme(
    primary = Latte.mauve,
    onPrimary = Latte.base,
    primaryContainer = Latte.surface0,
    onPrimaryContainer = Latte.mauve,
    secondary = Latte.blue,
    onSecondary = Latte.base,
    tertiary = Latte.green,
    onTertiary = Latte.base,
    background = Latte.crust,
    onBackground = Latte.text,
    surface = Latte.base,
    onSurface = Latte.text,
    surfaceVariant = Latte.surface0,
    onSurfaceVariant = Latte.subtext1,
    surfaceContainer = Latte.base,
    surfaceContainerLow = Latte.crust,
    surfaceContainerHigh = Latte.surface0,
    surfaceContainerHighest = Latte.surface1,
    outline = Latte.surface1,
    outlineVariant = Latte.surface0,
    error = Latte.red,
    onError = Latte.base,
)

/** Agent status → herdr's semantic color, theme-aware. */
fun statusColor(status: String?, dark: Boolean): Color = when (status) {
    "blocked" -> if (dark) Mocha.red else Latte.red
    "working" -> if (dark) Mocha.yellow else Latte.yellow
    "done" -> if (dark) Mocha.green else Latte.green
    "idle" -> if (dark) Mocha.overlay2 else Latte.overlay0
    else -> if (dark) Mocha.overlay0 else Latte.overlay0 // unknown / agentless
}

/** Static status glyph in herdr's terminal vocabulary (working spins separately). */
fun statusGlyph(status: String?): String = when (status) {
    "blocked" -> "●"   // ● solid — needs attention
    "working" -> "*"   // static fallback (overridden by the animated spinner)
    "done" -> "✓"      // ✓ check
    "idle" -> "○"      // ○ hollow
    else -> "·"        // · dot — unknown / shell
}

/**
 * Frames of the classic ASCII terminal spinner for a working agent. herdr's TUI
 * uses a braille spinner, but braille (U+2800 block) is absent from the system
 * monospace font AND the bundled JetBrains Mono, so it renders as tofu on device;
 * the ASCII "| / - \" spinner renders identically everywhere.
 */
val SpinnerFrames = listOf("|", "/", "-", "\\")

/** Dark ink for monogram text on a bright avatar accent (both themes). */
val AvatarInk = Color(0xFF11111B)

/** Stable, non-negative index into a palette of [size] for [seed]. */
fun colorIndexFor(seed: String, size: Int): Int {
    if (size <= 0) return 0
    var h = 0
    for (c in seed) h = h * 31 + c.code
    return ((h % size) + size) % size
}

private val avatarAccentsDark = listOf(Mocha.mauve, Mocha.blue, Mocha.green, Mocha.yellow, Mocha.peach, Mocha.red)
private val avatarAccentsLight = listOf(Latte.mauve, Latte.blue, Latte.green, Latte.yellow, Latte.peach, Latte.red)

/** Deterministic avatar background color for [seed], theme-aware. */
fun avatarColor(seed: String, dark: Boolean): Color {
    val palette = if (dark) avatarAccentsDark else avatarAccentsLight
    return palette[colorIndexFor(seed, palette.size)]
}
