package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLyricSeekTest {
    @Test
    fun appliesOffsetAndClampsTargetToTrackBounds() {
        assertEquals(0.25f, playerLyricSeekProgress(15_000L, 60f, 0L)!!, 0.0001f)
        assertEquals(0.25f, playerLyricSeekProgress(20_000L, 60f, 5_000L)!!, 0.0001f)
        assertEquals(25f / 60f, playerLyricSeekProgress(20_000L, 60f, -5_000L)!!, 0.0001f)
        assertEquals(0f, playerLyricSeekProgress(1_000L, 60f, 2_000L)!!, 0f)
        assertEquals(1f, playerLyricSeekProgress(59_000L, 60f, -5_000L)!!, 0f)
        assertEquals(1f, playerLyricSeekProgress(60_000L, 60f, 0L)!!, 0f)
    }

    @Test
    fun rejectsInvalidTimestampsAndDurations() {
        assertNull(playerLyricSeekProgress(-1L, 60f, 0L))
        assertNull(playerLyricSeekProgress(60_001L, 60f, 0L))
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0.0009f).forEach { duration ->
            assertNull(playerLyricSeekProgress(0L, duration, 0L))
        }
    }

    @Test
    fun formatsTimedRowsAndLeavesPlainTextWithoutASeekTimestamp() {
        assertEquals("00:00", playerLyricTimeLabel(0L))
        assertEquals("02:07", playerLyricTimeLabel(127_999L))
        assertNull(playerLyricTimeLabel(-1L))
        assertNull(playerLyricSeekProgress(-1L, 180f, 0L))
    }

}
