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

/** One Catppuccin flavor, the subset the app uses. */
data class HerdrPalette(
    val crust: Color,
    val mantle: Color,
    val base: Color,
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val overlay0: Color,
    val overlay2: Color,
    val subtext1: Color,
    val text: Color,
    val mauve: Color,
    val blue: Color,
    val green: Color,
    val yellow: Color,
    val peach: Color,
    val red: Color,
)

// ── Catppuccin Mocha (dark) ───────────────────────────────────────────────
val MochaPalette = HerdrPalette(
    crust = Color(0xFF11111B),
    mantle = Color(0xFF181825),
    base = Color(0xFF1E1E2E),
    surface0 = Color(0xFF313244),
    surface1 = Color(0xFF45475A),
    surface2 = Color(0xFF585B70),
    overlay0 = Color(0xFF6C7086),
    overlay2 = Color(0xFF9399B2),
    subtext1 = Color(0xFFBAC2DE),
    text = Color(0xFFCDD6F4),
    mauve = Color(0xFFCBA6F7),
    blue = Color(0xFF89B4FA),
    green = Color(0xFFA6E3A1),
    yellow = Color(0xFFF9E2AF),
    peach = Color(0xFFFAB387),
    red = Color(0xFFF38BA8),
)

// ── Catppuccin Latte (light) ──────────────────────────────────────────────
val LattePalette = HerdrPalette(
    crust = Color(0xFFDCE0E8),
    mantle = Color(0xFFE6E9EF),
    base = Color(0xFFEFF1F5),
    surface0 = Color(0xFFCCD0DA),
    surface1 = Color(0xFFBCC0CC),
    surface2 = Color(0xFFACB0BE),
    overlay0 = Color(0xFF9CA0B0),
    overlay2 = Color(0xFF7C7F93),
    subtext1 = Color(0xFF5C5F77),
    text = Color(0xFF4C4F69),
    mauve = Color(0xFF8839EF),
    blue = Color(0xFF1E66F5),
    green = Color(0xFF40A02B),
    yellow = Color(0xFFDF8E1D),
    peach = Color(0xFFFE640B),
    red = Color(0xFFD20F39),
)

private val Mocha = MochaPalette
private val Latte = LattePalette

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

/**
 * A workspace's badge color, cycled by its herdr number (blue, green, peach,
 * mauve) so the same workspace keeps its color on every screen.
 */
fun badgeColor(number: Int, dark: Boolean): Color {
    val p = if (dark) Mocha else Latte
    val cycle = listOf(p.blue, p.green, p.peach, p.mauve)
    return cycle[((number - 1) % cycle.size + cycle.size) % cycle.size]
}
