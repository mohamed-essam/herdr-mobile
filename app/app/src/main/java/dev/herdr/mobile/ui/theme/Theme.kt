package dev.herdr.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Terminal-first: monospace across the board, matching herdr's TUI aesthetic.
private val base = Typography()
private val HerdrTypography = Typography(
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Monospace),
    labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Monospace),
    labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
)

// Sharp corners — herdr's panes are rectangular TUI blocks, not pills.
// Truly square (0dp): rounded corners read as soft Material cards and undercut
// the terminal-first identity.
private val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) MochaColorScheme else LatteColorScheme,
        typography = HerdrTypography,
        shapes = HerdrShapes,
        content = content,
    )
}
