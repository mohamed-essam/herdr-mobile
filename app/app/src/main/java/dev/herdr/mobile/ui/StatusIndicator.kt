package dev.herdr.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.herdr.mobile.ui.theme.SpinnerFrames

/** The current frame of the ASCII working spinner (| / - \), animated. */
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
