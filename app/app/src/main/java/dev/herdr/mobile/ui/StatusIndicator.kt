package dev.herdr.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.ui.theme.SpinnerFrames
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph

/**
 * A herdr-style status readout: a colored glyph + the status word in mono.
 * A working agent gets the animated ASCII spinner (| / - \) — braille tofus on device.
 */
@Composable
fun StatusIndicator(status: String?, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val color = statusColor(status, dark)
    val glyph = if (status == "working") spinnerFrame() else statusGlyph(status)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = color, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Text(
            status ?: "unknown",
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun spinnerFrame(): String {
    val t = rememberInfiniteTransition(label = "spinner")
    val f by t.animateFloat(
        initialValue = 0f,
        targetValue = SpinnerFrames.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "frame",
    )
    return SpinnerFrames[f.toInt() % SpinnerFrames.size]
}
