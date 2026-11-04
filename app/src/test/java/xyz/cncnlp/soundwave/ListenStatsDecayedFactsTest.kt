package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** R 波一期：45 天半衰期衰减事实读取（decayedFacts）的钉点测试。 */
class ListenStatsDecayedFactsTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun record(json: String, song: Song, seconds: Int, nowMs: Long): String =
        ListenStatsDailyStore.record(json, song, "dev1", seconds.toLong(), true, nowMs)

    @Test
    fun fortyFiveDayOldPlayCountsHalf() {
        val today = LocalDate.of(2026, 6, 15)
        val song = Song("s1", "Half Life", "Singer", source = "wy")
        // Chronological order: record() prunes days after its own nowMs, so oldest first.
        var json = record("", song, 300, at(today.minusDays(90)))
        json = record(json, song, 300, at(today.minusDays(45)))
        json = record(json, song, 300, at(today))
        val facts = ListenStatsDailyStore.decayedFacts(json, at(today))
        assertEquals(1, facts.songs.size)
        // 300 * (1 + 0.5 + 0.25) = 525 decayed seconds; counts decay identically.
        assertEquals(525.0, facts.songs.single().seconds, 0.01)
        assertEquals(1.75, facts.songs.single().count, 0.01)
        // totalPlays stays raw (3 plays); totalSeconds is the decayed total (525).
        assertEquals(3L, facts.totalPlays)
        assertEquals(525.0, facts.totalSeconds, 0.01)
    }

    @Test
    fun emptyJsonYieldsEmptyFacts() {
        val facts = ListenStatsDailyStore.decayedFacts("", at(LocalDate.of(2026, 6, 15)))
        assertTrue(facts.songs.isEmpty())
        assertEquals(0L, facts.totalPlays)
    }

    @Test
    fun decayIsMonotonicWithAge() {
        val today = LocalDate.of(2026, 6, 15)
        val recent = Song("r1", "Recent", "Singer", source = "wy")
        val old = Song("o1", "Old", "Singer", source = "wy")
        var json = record("", old, 600, at(today.minusDays(180)))
        json = record(json, recent, 600, at(today))
        val byId = ListenStatsDailyStore.decayedFacts(json, at(today)).songs.associateBy { it.name }
        assertEquals(600.0, byId.getValue("Recent").seconds, 0.01)
        // 180 days = 4 half-lives -> 600 / 16 = 37.5
        assertEquals(37.5, byId.getValue("Old").seconds, 0.01)
    }
}
