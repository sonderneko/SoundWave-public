package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

/** accept5-listencard 立、ACCEPT6 并入一体卡：听歌区的纯逻辑——七日柱规整与差值胶囊文案（旧卡口径）。 */
class ListeningCardSnapshotTest {

    @Test
    fun dailySecondsPadsLeftWhenFewerThanSevenDays() {
        assertEquals(listOf(0L, 0L, 0L, 0L, 60L, 120L, 180L), listeningCardDailySeconds(listOf(60L, 120L, 180L)))
        assertEquals(List(7) { 0L }, listeningCardDailySeconds(emptyList()))
    }

    @Test
    fun dailySecondsKeepsExactlyLastSevenDays() {
        val raw = (1L..10L).toList()
        assertEquals((4L..10L).toList(), listeningCardDailySeconds(raw))
        assertEquals((1L..7L).toList(), listeningCardDailySeconds((1L..7L).toList()))
    }

    @Test
    fun diffTextSignAndRoundingFollowOldCard() {
        // +1.5h（本周 3h、上周 1.5h）
        assertEquals("↑ 1.5h", listeningCardDiffText(3L * 3600L, 5400L))
        // -0.5h
        assertEquals("↓ 0.5h", listeningCardDiffText(3600L, 5400L))
        // 持平按 ↑ 0.0h（与旧卡 diffHours >= 0 口径一致）
        assertEquals("↑ 0.0h", listeningCardDiffText(0L, 0L))
    }
}
