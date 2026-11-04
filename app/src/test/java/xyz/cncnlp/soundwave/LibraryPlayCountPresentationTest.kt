package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPlayCountPresentationTest {
    @Test
    fun zeroAndNegativePlayCountsAreHidden() {
        assertNull(formatVisiblePlayCount(0L))
        assertNull(formatVisiblePlayCount(-1L))
    }

    @Test
    fun positivePlayCountsUseTheExistingCompactFormat() {
        assertEquals("7", formatVisiblePlayCount(7L))
        assertEquals("1.5k", formatVisiblePlayCount(1_500L))
    }
}
