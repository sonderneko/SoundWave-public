package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** R 波一期：buildProfile 的衰减画像接入与中层冷启动门槛。 */
class PersonalRecommendationsV2Test {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dailyJson(plays: List<Triple<Song, Int, LocalDate>>): String {
        var json = ""
        for ((song, seconds, day) in plays) {
            json = ListenStatsDailyStore.record(json, song, "dev1", seconds.toLong(), true, at(day))
        }
        return json
    }

    @Test
    fun midTierStaysOffBelowFivePlays() {
        val today = LocalDate.of(2026, 6, 15)
        val songs = (1..3).map { Song("m$it", "Mid Song $it", "Mid Artist", source = "wy") }
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = dailyJson(songs.map { Triple(it, 200, today) }), nowMs = at(today)
        )
        // 画像存在（归一口味层已建），但播放样本不足 5 次时不产生任何统计型查询。
        assertTrue(profile.taste != null)
        assertEquals(3, profile.taste!!.totalPlays)
        assertTrue(profile.queries.isEmpty())
    }

    @Test
    fun midTierActivatesAtFivePlaysWithoutFavorites() {
        val today = LocalDate.of(2026, 6, 15)
        val songs = (1..5).map { Song("m$it", "Mid Song $it", "Mid Artist", source = "wy") }
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = dailyJson(songs.map { Triple(it, 200, today) }), nowMs = at(today)
        )
        assertEquals(5, profile.taste!!.totalPlays)
        assertTrue(profile.queries.any { it.kind == PersonalRecommendations.QueryKind.ARTIST && it.term == "Mid Artist" })
        assertTrue(profile.queries.any { it.kind == PersonalRecommendations.QueryKind.TRACK })
    }

    @Test
    fun decayedEvidenceReordersArtistQueries() {
        val today = LocalDate.of(2026, 6, 15)
        val oldSongs = (1..20).map { Song("old$it", "Old Track $it", "Old Star", source = "wy") }
        val newSongs = (1..6).map { Song("new$it", "New Track $it", "New Star", source = "wy") }
        val json = dailyJson(
            oldSongs.map { Triple(it, 600, today.minusDays(300)) } + newSongs.map { Triple(it, 600, today) }
        )
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = emptyList(), listenedArtists = emptyList(),
            dailyListenJson = json, nowMs = at(today)
        )
        // 生涯口径 Old Star 占优（20 次 vs 6 次），但 300 天前的播放衰减到约 1%，
        // 衰减画像里 New Star 必须排在第一位。
        val firstArtist = profile.queries.first { it.kind == PersonalRecommendations.QueryKind.ARTIST }
        assertEquals("New Star", firstArtist.term)
        assertTrue(profile.taste!!.affinityOf("New Star") > profile.taste!!.affinityOf("Old Star"))
    }

    @Test
    fun demotedArtistAndOwnedIdentitiesFlowIntoProfile() {
        val today = LocalDate.of(2026, 6, 15)
        val disliked = listOf(Song("d1", "Hated One", "Hated Artist", source = "wy"), Song("d2", "Hated Two", "Hated Artist", source = "wy"))
        val librarySong = Song("lib1", "Kept Song", "Kept Artist", source = "wy")
        val plays = (1..5).map { Song("p$it", "Play $it", "Played Artist", source = "wy") }
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = emptyList(), listenedArtists = emptyList(),
            librarySongs = listOf(librarySong) + disliked,
            dailyListenJson = dailyJson(plays.map { Triple(it, 200, today) }),
            dislikedSongKeys = disliked.mapTo(mutableSetOf()) { it.key() },
            nowMs = at(today)
        )
        assertTrue(profile.taste!!.ownedIdentities.contains(PersonalRecommendations.songIdentity(librarySong)))
        assertTrue("Hated Artist 应被软降权", profile.taste!!.demotedArtists.contains(
            xyz.cncnlp.soundwave.recommend.TasteProfileV2.artistKey("Hated Artist")
        ))
        // 降权后亲和 = 在库 2 首 * 3 * 0.2 = 1.2
        assertEquals(1.2, profile.taste!!.affinityOf("Hated Artist"), 1e-9)
    }

    @Test
    fun legacyProfileWithoutDailyDataIsUnchanged() {
        val song = Song("song-1", "Song One", "Artist A", source = "wy")
        val stat = ListenStats.SongStat("wy:song-1", "Song One", "Artist A", "", 3600L, 10)
        val profile = PersonalRecommendations.buildProfile(
            history = emptyList(),
            likedSongs = listOf(song),
            listenedArtists = listOf("Artist A" to 3600L),
            listenedSongs = listOf(stat),
            nowMs = 1_000L
        )
        assertEquals(null, profile.taste)
        assertFalse(profile.queries.isEmpty())
        val withoutDaily = PersonalRecommendations.buildProfile(
            history = emptyList(), likedSongs = listOf(song), listenedArtists = listOf("Artist A" to 3600L),
            listenedSongs = listOf(stat), nowMs = 1_000L
        )
        assertEquals(withoutDaily.queries, profile.queries)
        assertEquals(withoutDaily.activitySignature, profile.activitySignature)
    }
}
