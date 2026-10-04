package dev.herdr.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.R

/**
 * Two faces: JetBrains Mono for identity, numbers and tool output (the
 * terminal-first voice), Geist for anything read as prose. The embedded
 * terminal has its own font and is unaffected.
 */
val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

val SansFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)

/** The spec's type ramp, by role. Sizes are the 360dp-wide design's px. */
object HerdrType {
    /** 28/600 sans: the pane screen's workspace title, empty-state headline. */
    val display = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.01).em)
    /** 22/600 sans: sheet titles. */
    val headline = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    /** 15/600 sans: card and row titles. */
    val title = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
    /** 15/400 sans: message bodies, questions. */
    val body = TextStyle(fontFamily = SansFamily, fontSize = 15.sp, lineHeight = 21.sp)
    /** 13/600 sans: button labels. */
    val button = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    /** 13/400 sans: chips, secondary prose. */
    val small = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, lineHeight = 18.sp)
    /** 11/400 sans: captions under numbers. */
    val caption = TextStyle(fontFamily = SansFamily, fontSize = 11.sp, lineHeight = 15.sp)

    /** 17/600 mono: the "herdr ❯" wordmark in a top bar. */
    val wordmark = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    /** 28/600 mono, tight: big stat numbers. */
    val stat = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 28.sp)
    /** 13/600 mono: workspace badge digits (28dp badge). */
    val badge = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    /** 12/400 mono: tool output, code. */
    val code = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, lineHeight = 18.sp)
    /** 11/400 mono: metadata, timestamps, breadcrumbs. */
    val meta = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 15.sp)
    /** 11/600 mono, tracked: section labels ("● NEEDS YOU"). */
    val section = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em)
}

private val base = Typography()

private val HerdrTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = SansFamily),
    displayMedium = base.displayMedium.copy(fontFamily = SansFamily),
    displaySmall = base.displaySmall.copy(fontFamily = SansFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = SansFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = SansFamily),
    headlineSmall = HerdrType.headline,
    titleLarge = base.titleLarge.copy(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = SansFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = SansFamily),
    bodySmall = base.bodySmall.copy(fontFamily = SansFamily),
    labelLarge = base.labelLarge.copy(fontFamily = SansFamily),
    labelMedium = base.labelMedium.copy(fontFamily = MonoFamily),
    labelSmall = base.labelSmall.copy(fontFamily = MonoFamily),
)

/** The spec's radii: 8, 10, 12, 14, 16, and 24 for sheet tops. */
object HerdrRadius {
    val badge = RoundedCornerShape(8.dp)
    val button = RoundedCornerShape(10.dp)
    val field = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

private val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val LocalHerdrColors = staticCompositionLocalOf { MochaPalette }

/** Access to the raw Catppuccin flavor, for the spec's direct color picks. */
object Herdr {
    val colors: HerdrPalette
        @Composable @ReadOnlyComposable get() = LocalHerdrColors.current
}

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalHerdrColors provides if (dark) MochaPalette else LattePalette) {
        MaterialTheme(
            colorScheme = if (dark) MochaColorScheme else LatteColorScheme,
            typography = HerdrTypography,
            shapes = HerdrShapes,
            content = content,
        )
    }
}
