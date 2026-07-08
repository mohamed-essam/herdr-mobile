package dev.herdr.mobile

import dev.herdr.mobile.ui.FontBounds
import dev.herdr.mobile.ui.fontBounds
import dev.herdr.mobile.ui.steppedFontSize
import org.junit.Assert.*
import org.junit.Test

class TerminalFontTest {
    private val b = FontBounds(default = 32, min = 16, max = 64, step = 4)

    @Test fun boundsScaleWithDensity() {
        val f = fontBounds(2.0f)
        assertEquals(32, f.default) // 16*2
        assertEquals(16, f.min)     // 8*2
        assertEquals(64, f.max)     // 32*2
        assertEquals(4, f.step)     // 2*2
    }

    @Test fun subThresholdScaleReturnsNull() {
        assertNull(steppedFontSize(32, 1.0f, b))
        assertNull(steppedFontSize(32, 1.05f, b))
        assertNull(steppedFontSize(32, 0.95f, b))
    }

    @Test fun zoomInStepsUpAndClamps() {
        assertEquals(36, steppedFontSize(32, 1.2f, b)) // +step
        assertEquals(64, steppedFontSize(64, 1.2f, b)) // already max -> stays max
    }

    @Test fun zoomOutStepsDownAndClamps() {
        assertEquals(28, steppedFontSize(32, 0.8f, b)) // -step
        assertEquals(16, steppedFontSize(16, 0.8f, b)) // already min -> stays min
    }
}
