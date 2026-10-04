package dev.herdr.mobile

import dev.herdr.mobile.ui.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSampleTest {
    @Test fun smallImagesAreNotDownsampled() {
        assertEquals(1, sampleSizeFor(800, 1080))
        assertEquals(1, sampleSizeFor(1080, 1080))
    }

    @Test fun largestPowerOfTwoKeepingAtLeastTheTargetWidth() {
        assertEquals(2, sampleSizeFor(2160, 1080))
        assertEquals(2, sampleSizeFor(4000, 1080))
        assertEquals(4, sampleSizeFor(4320, 1080))
    }

    @Test fun unknownSizesDecodeAsIs() {
        assertEquals(1, sampleSizeFor(0, 1080))
        assertEquals(1, sampleSizeFor(4000, 0))
    }
}
