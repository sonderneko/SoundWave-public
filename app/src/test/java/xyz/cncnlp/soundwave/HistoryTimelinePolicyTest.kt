package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTimelinePolicyTest {
    @Test
    fun onlyLeftSwipeMovesTheCardAndOffsetIsBounded() {
        val width = 100f
        assertEquals(-80f, HistoryTimelinePolicy.clampSwipeOffset(0f, -80f, width), 0.001f)
        assertEquals(0f, HistoryTimelinePolicy.clampSwipeOffset(0f, 24f, width), 0.001f)
        assertEquals(-width, HistoryTimelinePolicy.clampSwipeOffset(-80f, -80f, width), 0.001f)
        assertEquals(0f, HistoryTimelinePolicy.clampSwipeOffset(0f, -80f, 0f), 0.001f)
    }

    @Test
    fun swipeLeftPastThresholdOrFastFlingDeletes() {
        val width = 100f
        assertFalse(HistoryTimelinePolicy.shouldDelete(-34f, width))
        assertTrue(HistoryTimelinePolicy.shouldDelete(-35f, width))
        assertFalse(HistoryTimelinePolicy.shouldDelete(-100f, 0f))
        // fast fling with a little travel deletes; a tap-like jitter does not
        assertTrue(HistoryTimelinePolicy.shouldDelete(-10f, width, velocityPx = -2400f))
        assertFalse(HistoryTimelinePolicy.shouldDelete(-3f, width, velocityPx = -2400f))
        assertFalse(HistoryTimelinePolicy.shouldDelete(-20f, width, velocityPx = 2400f))
    }

    @Test
    fun timeColumnSitsLeftOfTheRail() {
        assertEquals(
            HistoryTimelinePolicy.TIME_COLUMN_WIDTH_DP + HistoryTimelinePolicy.RAIL_WIDTH_DP,
            HistoryTimelinePolicy.TIME_RAIL_WIDTH_DP, 0f
        )
        assertEquals(0f, HistoryTimelinePolicy.swipeProgress(0f, 100f), 0f)
        assertEquals(1f, HistoryTimelinePolicy.swipeProgress(-60f, 100f), 0f)
    }
}
