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
