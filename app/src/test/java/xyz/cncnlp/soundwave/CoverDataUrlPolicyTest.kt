package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverDataUrlPolicyTest {
    @Test
    fun acceptsInlineCoverPayloadsWithinLimit() {
        assertTrue(isCoverDataUrlSizeAllowed(0))
        assertTrue(isCoverDataUrlSizeAllowed(1))
        assertTrue(isCoverDataUrlSizeAllowed(MAX_COVER_DATA_URL_CHARS))
    }

    @Test
    fun rejectsNegativeAndOversizedInlineCoverPayloads() {
        assertFalse(isCoverDataUrlSizeAllowed(-1))
        assertFalse(isCoverDataUrlSizeAllowed(MAX_COVER_DATA_URL_CHARS + 1))
    }
}
