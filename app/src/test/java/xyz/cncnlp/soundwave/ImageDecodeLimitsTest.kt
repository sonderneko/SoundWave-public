package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.image.ImageDecodeLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodeLimitsTest {
    @Test
    fun boundsPixelWorkAndSamplesDownToRequestedMaximum() {
        assertTrue(ImageDecodeLimits.hasAllowedDimensions(4096, 1024))
        assertFalse(ImageDecodeLimits.hasAllowedDimensions(2049, 2049))
        assertEquals(16L * 1024 * 1024, ImageDecodeLimits.MAX_ARGB_BITMAP_BYTES)
        assertEquals(8, ImageDecodeLimits.sampleSize(4096, 1024, 512))
        assertEquals(512, (4096 + 8 - 1) / 8)
        assertEquals(0, ImageDecodeLimits.sampleSize(2049, 2049, 512))
    }
}
