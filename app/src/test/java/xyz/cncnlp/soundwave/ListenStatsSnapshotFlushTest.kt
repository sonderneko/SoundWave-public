package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenStatsSnapshotFlushTest {
    private val song = Song("snapshot", "Snapshot", "Artist", "wy")

    @Test
    fun snapshotExportsSubBatchWholeSecondsWithoutResettingThePlayMark() {
        val stats = ListenStatsAccumulator()
        stats.onTick(0, song, true)
        stats.onTick(2_500, song, true)
        assertEquals(2L, stats.flushForSnapshot().single().seconds)
        assertTrue(stats.flushForSnapshot().isEmpty())
        val step = stats.onTick(3_000, song, true)
        assertEquals(null, step.markPlay)
        assertEquals(1L, stats.flushForSnapshot().single().seconds)
    }

    @Test
    fun frequentSnapshotsPreserveFractionsAndNeverDoubleCount() {
        val stats = ListenStatsAccumulator()
        stats.onTick(0, song, true)
        var seconds = 0L
        repeat(30) { index ->
            val step = stats.onTick((index + 1) * 350L, song, true)
            seconds += step.flushes.sumOf { it.seconds }
            seconds += stats.flushForSnapshot().sumOf { it.seconds }
        }
        assertEquals(10L, seconds)
        stats.onTick(11_000, song, true)
        assertEquals(1L, stats.flushForSnapshot().single().seconds)
        assertTrue(stats.flushForSnapshot().isEmpty())
    }

    @Test
    fun snapshotFollowedByNormalFlushOrPauseCountsOnlyRemainingTime() {
        val stats = ListenStatsAccumulator()
        stats.onTick(0, song, true)
        var seconds = 0L
        (1..12).forEach { second ->
            seconds += stats.onTick(second * 1000L, song, true).flushes.sumOf { it.seconds }
            if (second == 3) seconds += stats.flushForSnapshot().sumOf { it.seconds }
        }
        seconds += stats.onTick(12_000, song, false).flushes.sumOf { it.seconds }
        assertEquals(12L, seconds)
        assertTrue(stats.flushForSnapshot().isEmpty())
    }

    @Test
    fun finalFlushAfterSnapshotDoesNotReapplyPreviouslyExportedSeconds() {
        val stats = ListenStatsAccumulator()
        stats.onTick(0, song, true)
        stats.onTick(2_500, song, true)
        assertEquals(2L, stats.flushForSnapshot().single().seconds)
        stats.onTick(3_500, song, true)
        assertEquals(1L, stats.flushAll().single().seconds)
        assertTrue(stats.flushAll().isEmpty())
    }
}
