package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * ACCEPT6-FREEZE：buildProfile 的 precomputedDecayedFacts 通路与按 dailyListenJson
 * 现算必须产出完全一致的画像——该通路只是把 365 天日统计 JSON 的全量解析从根作用域
 * 主线程挪到 HomeFeedSession 的 IO 采集段，口径（同一 nowMs）不变。
 */
class PrecomputedDecayedFactsTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun precomputedFactsProduceIdenticalProfile() {
        val today = LocalDate.of(2026, 6, 15)
        val songs = (1..6).map { Song("p$it", "Pre Song $it", "Pre Artist", source = "wy") }
        var json = ""
        for ((index, song) in songs.withIndex()) {
            json = ListenStatsDailyStore.record(json, song, "dev1", 200L, true, at(today.minusDays(index.toLong())))
        }
        val now = at(today)
        val fromJson = PersonalRecommendations.buildProfile(
            history = songs.take(2), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = json, nowMs = now
        )
        val fromPrecomputed = PersonalRecommendations.buildProfile(
            history = songs.take(2), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = json, nowMs = now,
            precomputedDecayedFacts = ListenStatsDailyStore.decayedFacts(json, now)
        )
        assertEquals(fromJson, fromPrecomputed)
    }

    @Test
    fun blankJsonStillYieldsNoTasteOnPrecomputedPath() {
        val now = at(LocalDate.of(2026, 6, 15))
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = "", nowMs = now, precomputedDecayedFacts = null
        )
        assertEquals(null, profile.taste)
    }
}
