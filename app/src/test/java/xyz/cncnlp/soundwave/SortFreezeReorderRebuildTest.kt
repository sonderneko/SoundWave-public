package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * accept6-sortfreeze 回归：歌单排序落盘会使根组合内 rememberRecommendationLibrary 的输入
 * 列表顺序变化，触发推荐画像在主线程重建。修复要求：
 * 1) token/songIdentity 纯函数备忘——相同字段的 Song 实例之间 identity 只计算一次（同一实例返回）；
 * 2) buildProfile 对相同输入（含真实结构的日粒度统计 JSON）产出逐项一致的画像，备忘不得污染结果；
 * 3) 大曲库重建保持等价（输出签名一致），耗时仅打印留证，不做脆弱的时间断言。
 */
class SortFreezeReorderRebuildTest {

    private fun song(i: Int, src: String): Song = Song(
        id = "id-$src-$i", name = "排序回归歌曲 $i", singer = "歌手 ${i % 97}", source = src,
        cover = "https://example.com/c$i.jpg", album = "专辑 ${i % 211}", albumId = "alb-$i",
        singerId = "art-${i % 97}", duration = 180_000L + i, syncAddedAt = 1_700_000_000_000L + i
    )

    /** 真实日统计结构：根对象按 BASIC_ISO_DATE 日键，songs 为按 key 的对象，计数在 devices 下。 */
    private fun dailyJson(dayKeys: List<String>, songCount: Int): String {
        val root = JSONObject()
        dayKeys.forEachIndexed { d, dayKey ->
            val songs = JSONObject()
            repeat(songCount) { s ->
                val idx = (d * songCount + s) % 5000
                songs.put(
                    "wy:id-wy-$idx",
                    JSONObject()
                        .put("name", "排序回归歌曲 $idx")
                        .put("singer", "歌手 ${idx % 97}")
                        .put("devices", JSONObject().put("dev", JSONObject().put("sec", 150L).put("cnt", 2L)))
                )
            }
            root.put(dayKey, JSONObject().put("songs", songs))
        }
        return root.toString()
    }

    @Test
    fun tokenIsMemoized() {
        val first = PersonalRecommendations.token("sortfreeze|token-probe")
        val second = PersonalRecommendations.token("sortfreeze|token-probe")
        assertEquals(64, first.length)
        assertSame("token 纯函数应备忘，第二次调用须返回同一实例", first, second)
    }

    @Test
    fun songIdentityMemoizedAcrossEqualFieldInstances() {
        val a = song(424242, "tx").copy(cover = "https://a.example/x.jpg", album = "甲专辑")
        val b = song(424242, "tx").copy(cover = "https://b.example/y.jpg", album = "乙专辑")
        val first = PersonalRecommendations.songIdentity(a)
        val second = PersonalRecommendations.songIdentity(b)
        assertEquals(first, second)
        assertSame("identity 只依赖 source/id/name/singer/duration，封面/专辑不同不应重算", first, second)
        // 回退分支（id 为空 → Song.key() 路径）同样备忘。
        val c = song(434343, "kg").copy(id = "")
        val d = song(434343, "kg").copy(id = "", cover = "https://c.example/z.jpg")
        assertSame(PersonalRecommendations.songIdentity(c), PersonalRecommendations.songIdentity(d))
        // 关键字段不同 → identity 必须不同（备忘键不得吞掉区分度）。
        val other = song(424243, "tx")
        assertTrue(PersonalRecommendations.songIdentity(other) != first)
    }

    @Test
    fun buildProfileDeterministicWithDailyStats() {
        val nowMs = 1_736_899_200_000L // 2025-01-15T12:00:00Z 附近，固定以免衰减因子漂移
        val playlists = (0 until 6).map { p ->
            Playlist(
                id = "imp_wy_playlist_${2000 + p}", name = "回归歌单 $p",
                songs = MutableList(40) { song(p * 1000 + it, "wy") },
                source = "wy", remoteId = "${2000 + p}", count = 40, favorited = p == 0
            )
        }
        val history = List(30) { song(950_000 + it, "tx").copy(playedAt = nowMs - 3_600_000L) }
        val liked = List(25) { song(900_000 + it, "wy") }
        val daily = dailyJson(listOf("20250113", "20250114", "20250115"), 12)
        fun build() = PersonalRecommendations.buildProfile(
            history, liked,
            listenedArtists = (0 until 10).map { "歌手 $it" to 3600L },
            nowMs = nowMs,
            profileOwner = "local",
            favoritePlaylists = playlists.filter { it.favorited },
            importedPlaylists = playlists.filter { !it.favorited },
            librarySongs = playlists.asSequence().flatMap { it.songs.asSequence() }
                .distinctBy { PersonalRecommendations.songIdentity(it) }.take(200).toList(),
            dailyListenJson = daily
        )
        val first = build()
        val second = build()
        assertNotNull("真实结构日统计在场时应构建 V2 画像", first.taste)
        assertEquals(first.queries, second.queries)
        assertEquals(first.signature, second.signature)
        assertEquals(first.activitySignature, second.activitySignature)
        assertEquals(first.excludedSongKeys, second.excludedSongKeys)
        assertEquals(first.taste, second.taste)
    }

    @Test
    fun largeLibraryRebuildEquivalent() {
        val sources = listOf("wy", "tx", "kg", "kw", "mg")
        var n = 0
        val playlists = (0 until 60).map { p ->
            Playlist(
                id = "imp_${sources[p % 5]}_playlist_${3000 + p}", name = "大库歌单 $p",
                songs = MutableList(500) { song(n++, sources[p % 5]) },
                source = sources[p % 5], remoteId = "${3000 + p}", count = 500,
                favorited = p % 7 == 0
            )
        }
        val nowMs = 1_736_899_200_000L
        val history = List(300) { song(950_000 + it, "tx").copy(playedAt = nowMs - 7_200_000L) }
        val liked = List(400) { song(900_000 + it, "wy") }
        val daily = dailyJson((5..14).map { "202501%02d".format(it) }, 12)
        fun build(): PersonalRecommendations.Profile {
            val librarySongs = playlists.asSequence().flatMap { it.songs.asSequence() }
                .distinctBy { PersonalRecommendations.songIdentity(it) }.take(200).toList()
            return PersonalRecommendations.buildProfile(
                history, liked,
                listenedArtists = (0 until 20).map { "歌手 $it" to 3600L },
                nowMs = nowMs,
                profileOwner = "local",
                favoritePlaylists = playlists.filter { it.favorited },
                importedPlaylists = playlists.filter { !it.favorited },
                librarySongs = librarySongs,
                dailyListenJson = daily
            )
        }
        lateinit var first: PersonalRecommendations.Profile
        val coldMs = measureNanoTime { first = build() } / 1e6
        lateinit var second: PersonalRecommendations.Profile
        val warmMs = measureNanoTime { second = build() } / 1e6
        println("SORTFREEZE large rebuild cold=${coldMs}ms warm=${warmMs}ms (30400 songs)")
        assertEquals(first.queries, second.queries)
        assertEquals(first.signature, second.signature)
        assertEquals(first.activitySignature, second.activitySignature)
        assertEquals(first.taste, second.taste)
    }
}
