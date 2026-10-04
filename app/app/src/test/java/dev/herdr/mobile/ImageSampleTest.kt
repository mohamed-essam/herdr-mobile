package dev.herdr.mobile

import dev.herdr.mobile.ui.INLINE_MAX_PIXELS
import dev.herdr.mobile.ui.VIEWER_MAX_PIXELS
import dev.herdr.mobile.ui.maxPan
import dev.herdr.mobile.ui.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSampleTest {
    // Inline box: a 1080 px wide item, 240dp tall at density 2.75.
    private fun inline(w: Int, h: Int) = sampleSizeFor(w, h, 1080, 660, INLINE_MAX_PIXELS)

    @Test fun smallImagesAreNotDownsampled() {
        assertEquals(1, inline(800, 600))
        assertEquals(1, inline(1080, 660))
    }

    @Test fun downsamplesWhileStillCoveringTheShownSize() {
        assertEquals(2, inline(2160, 1320))
        assertEquals(2, inline(4000, 1320))
        assertEquals(4, inline(4320, 2640))
    }

    @Test fun tallImageIsBoundedByTheBoxHeight() {
        // Shown at 660 px tall, so the decode can drop to ~1/300 of the height.
        val n = inline(1080, 200_000)!!
        assertEquals(256, n)
        assertTrue(200_000 / n >= 660)
    }

    @Test fun squareBombStaysWithinThePixelBudget() {
        val inlineN = inline(30_000, 30_000)!!
        assertTrue((30_000L / inlineN) * (30_000L / inlineN) <= INLINE_MAX_PIXELS)
        val viewerN = sampleSizeFor(30_000, 30_000, 2160, 4800, VIEWER_MAX_PIXELS)!!
        assertTrue((30_000L / viewerN) * (30_000L / viewerN) <= VIEWER_MAX_PIXELS)
    }

    @Test fun pixelBudgetAppliesEvenWhenTheBoxIsLarge() {
        // Fits the box at full size, but 12 MP is over the 4 MP inline budget.
        assertEquals(2, sampleSizeFor(4000, 3000, 4000, 3000, INLINE_MAX_PIXELS))
    }

    @Test fun panStopsAtTheScaledImageEdges() {
        assertEquals(0f, maxPan(1000f, 1000f, 1f), 0f)
        assertEquals(500f, maxPan(1000f, 1000f, 2f), 0f)
        // Narrower than the box even when zoomed: no sideways pan.
        assertEquals(0f, maxPan(300f, 1000f, 3f), 0f)
    }

    @Test fun unknownSizesAreNotDecoded() {
        assertNull(inline(0, 1080))
        assertNull(inline(4000, 0))
        assertNull(inline(-1, -1))
    }
}
