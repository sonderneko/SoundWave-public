package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test

class HeartRecommendationPolicyTest {
    private fun song(id: String, name: String, singer: String, source: String = "wy") =
        Song(id = id, name = name, singer = singer, source = source)

    @Test fun semanticIdentityDeduplicatesSourcesButNeverDifferentArtists() {
        val favorite = song("fav", "晴天", "周杰伦")
        val wy = song("1", "夜 曲", "周杰伦")
        val tx = song("2", "夜曲", "周杰伦", "tx")
        val impersonator = song("3", "晴天", "周杰伦翻唱团")
        val excluded = song("4", "搁浅", "周杰伦", "kg")
        val alias = song("5", "搁浅", "周杰伦")
        val result = HeartRecommendationPolicy.candidates(HeartRecommendationPolicy.profile(listOf(favorite)),
            listOf(wy, tx, impersonator, alias, favorite.copy(id = "cross", source = "kg")), listOf(excluded), emptyList(), "tx")
        assertEquals(listOf(tx), result)
        val artists = HeartRecommendationPolicy.profile(listOf(song("f2", "合唱", "A / B")))
        assertEquals(listOf("a", "b"), artists.artists)
        assertEquals(HeartRecommendationPolicy.identity(song("a", "Ｎｉｇｈｔ！", "B & A")),
            HeartRecommendationPolicy.identity(song("b", "night", "A/B", "tx")))
        assertNotEquals(HeartRecommendationPolicy.identity(wy), HeartRecommendationPolicy.identity(wy.copy(name = "夜曲 (Live)")))
    }

    @Test fun unseenTracksWinBeforeBoundedHistoryRecyclesOldest() {
        val profile = HeartRecommendationPolicy.profile(listOf(song("f", "最爱", "A")))
        val old = song("old", "旧推荐", "A")
        val fresh = song("new", "新推荐", "A")
        val seen = listOfNotNull(HeartRecommendationPolicy.identity(old))
        assertEquals(listOf(fresh), HeartRecommendationPolicy.candidates(profile, listOf(old, fresh), emptyList(), seen, "wy"))
        assertEquals(listOf(old, fresh), HeartRecommendationPolicy.candidates(profile, listOf(fresh, old), emptyList(),
            seen + listOfNotNull(HeartRecommendationPolicy.identity(fresh)), "wy"))
    }

    @Test fun historyRoundTripsAndRemainsBoundedInRecencyOrder() {
        var history = emptyList<String>()
        repeat(600) { history = HeartRecommendationPolicy.remember(history, song("$it", "曲$it", "A")) }
        assertEquals(HeartRecommendationPolicy.SEEN_LIMIT, history.size)
        val last = song("599", "曲599", "A")
        assertEquals(history, HeartRecommendationPolicy.decodeSeen(HeartRecommendationPolicy.encodeSeen(history)))
        history = HeartRecommendationPolicy.remember(history, last)
        assertEquals(HeartRecommendationPolicy.SEEN_LIMIT, history.size)
        assertEquals(HeartRecommendationPolicy.identity(last), history.last())
    }

    @Test fun favoritesAreTheOnlyPreferenceSeeds() {
        val favorite = song("fav", "喜欢的歌", "周杰伦")
        val profile = HeartRecommendationPolicy.profile(listOf(favorite))
        val queueOnly = song("queued", "队列风格", "陌生歌手")
        val unrelated = song("bad", "搜索噪声", "陌生歌手")
        val related = song("good", "新歌", "周杰伦")
        assertEquals(listOf(related), HeartRecommendationPolicy.candidates(
            profile, listOf(unrelated, related), listOf(queueOnly), emptyList(), "wy"))
        assertTrue(HeartRecommendationPolicy.candidates(
            HeartRecommendationPolicy.profile(emptyList()), listOf(related), emptyList(), emptyList(), "wy").isEmpty())
    }
}
