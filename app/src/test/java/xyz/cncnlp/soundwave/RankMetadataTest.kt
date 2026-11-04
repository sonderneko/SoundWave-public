package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.source.direct.parseWyPlaylistDetail

class RankMetadataTest {
    @Test
    fun explicitRankChangeAcceptsOnlyNonzeroNumericSignedDelta() {
        assertEquals(5, explicitRankChange(JSONObject("""{"rankChange":5}""")))
        assertEquals(-3, explicitRankChange(JSONObject("""{"rankChange":-3}""")))
        assertNull(explicitRankChange(JSONObject("""{"rankChange":0}""")))
        assertNull(explicitRankChange(JSONObject("""{"rankChange":"5"}""")))
        assertNull(explicitRankChange(JSONObject("""{"rankChange":"bad"}""")))
        assertNull(explicitRankChange(JSONObject("""{"rankType":1,"rankValue":5}""")))
        assertNull(explicitRankChange(JSONObject("""{"rankChange":1.5}""")))
    }

    @Test
    fun providerUpdateTimeParsesSecondsMillisecondsAndLocalChinaDateOnlyWhenValid() {
        assertEquals(1_704_038_400_000L, providerUpdateTime(JSONObject("""{"updateTime":1704038400}""")))
        assertEquals(1_704_038_400_000L, providerUpdateTime(JSONObject("""{"updateTime":"1704038400000"}""")))
        assertEquals(1_704_038_400_000L, providerUpdateTime(JSONObject("""{"updateTime":"2024-01-01 00:00:00"}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":0}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":"0"}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":"yesterday"}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":"2024-01-01"}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":12345678901}""")))
        assertNull(providerUpdateTime(JSONObject("""{"updateTime":"2024-02-30 12:00:00"}""")))
        assertNull(providerUpdateTime(JSONObject("""{"fetchTime":1704038400}""")))
    }

    @Test
    fun wyPlaylistParserMapsOnlyItsActualUpdateTimeAndExplicitTrackChange() {
        val parsed = parseWyPlaylistDetail(
            """{"result":{"updateTime":1704038400000,"trackCount":1,"tracks":[{"id":"1","name":"song","rankChange":2},{"id":"2","name":"unknown","rankChange":"2"}]}}""",
            10,
        )
        assertEquals(1_704_038_400_000L, parsed.updatedAt)
        assertEquals(2, parsed.songs[0].rankChange)
        assertNull(parsed.songs[1].rankChange)
        assertNull(parseWyPlaylistDetail("""{"result":{"updateTime":0,"tracks":[]}}""", 10).updatedAt)
    }

    @Test
    fun updateLabelUsesActualTimestampInChinaTimeAndUnknownIsBlank() {
        val board = RankBoard("热歌榜", source = "wy", updatedAt = 1_704_038_400_000L)
        assertEquals("更新于 2024-01-01 00:00（北京时间）", board.officialUpdateLabel())
        assertEquals("", RankBoard("热歌榜", source = "wy").officialUpdateLabel())
        assertFalse(board.officialUpdateLabel().contains("刚刚更新"))
    }

    @Test
    fun serverRankDetailRetainsMovementWhileGenericSongJsonDoesNot() {
        val songs = ApiClient.parseRankDetailPayload(
            """{"data":{"songs":[{"id":"s1","name":"Song","singer":"Artist","rankChange":-7},{"id":"s2","name":"Other","singer":"Artist"}]}}""", "wy")
        assertEquals(listOf("s1", "s2"), songs.map { it.id })
        assertEquals(-7, songs[0].rankChange)
        assertNull(songs[1].rankChange)
        assertFalse(songs[0].toJson().has("rankChange"))
    }
    @Test
    fun chartCacheRoundTripsMetadataWithoutPollutingGenericSongJson() {
        val song = Song("song-1", "Song", "Artist", source = "wy", rankChange = -4)
        assertFalse(song.toJson().has("rankChange"))
        val now = 1_704_038_400_000L
        val snapshot = HomeFeedCache.Snapshot(
            hot = mapOf("wy" to listOf(song)),
            rank = mapOf("wy" to listOf(RankBoard("榜单", "wy", listOf(song), "rank-1", updatedAt = now))),
        )
        val encoded = HomeFeedCache.encodeSnapshot(snapshot, savedAt = now)
        val decoded = HomeFeedCache.decodeSnapshot(encoded)
        val restored = decoded.rank.getValue("wy").single()
        assertEquals(now, restored.updatedAt)
        assertEquals(-4, restored.songs.single().rankChange)
        assertNull(decoded.hot.getValue("wy").single().rankChange)
        val json = JSONObject(encoded)
        assertFalse(json.getJSONObject("hot").getJSONArray("wy").getJSONObject(0).has("rankChange"))
        assertTrue(json.getJSONObject("rank").getJSONArray("wy").getJSONObject(0).getJSONArray("songs")
            .getJSONObject(0).has("rankChange"))
    }
}
