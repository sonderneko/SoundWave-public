package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** r657 第二轮：历史「来自 <来源>」字段的兼容性与队列来源判定；歌曲抽屉「查看歌手 / 专辑 / 分享」纯函数。 */
@RunWith(RobolectricTestRunner::class)
class R657R2PlaySourceTest {
    private fun song(i: Int, album: String = "", albumId: String = "") = Song(id = "s$i", name = "Song $i", singer = "A$i / B", source = "wy", album = album, albumId = albumId)

    @Before fun reset() = PlaySourceTracker.reset()

    @Test fun oldHistoryJsonWithoutPlaySrcDefaultsToEmpty() {
        val old = JSONObject().put("id", "1").put("name", "n").put("singer", "s").put("src", "wy").put("playedAt", 5L)
        val parsed = Song.fromJson(old)
        assertEquals("", parsed.playSource)
        assertFalse(parsed.toJson().has("playSrc"))
    }

    @Test fun playSourceRoundTripsAndIsBounded() {
        val s = song(1).copy(playSource = "我的喜欢")
        assertEquals("我的喜欢", Song.fromJson(s.toJson()).playSource)
        val long = song(2).copy(playSource = "x".repeat(100))
        assertEquals(Song.PLAY_SOURCE_MAX, long.toJson().getString("playSrc").length)
    }

    @Test fun newQueueTakesTopScreenAndSameQueueKeepsIt() {
        val home = Any(); val detail = Any()
        PlaySourceTracker.push(home, "首页")
        PlaySourceTracker.push(detail, "我的喜欢")
        val q = (1..5).map { song(it) }
        assertEquals("我的喜欢", PlaySourceTracker.noteQueue(q))
        PlaySourceTracker.pop(detail)
        // 同一队列自动下一首 / 追加：来源不变
        assertEquals("我的喜欢", PlaySourceTracker.noteQueue(q + song(9)))
        // 换新队列：取当前栈顶
        assertEquals("首页", PlaySourceTracker.noteQueue(listOf(song(7), song(8))))
        PlaySourceTracker.force("心动模式", q)
        assertEquals("心动模式", PlaySourceTracker.label())
    }

    @Test fun historySubtitleShowsSourceOnlyWhenKnown() {
        assertEquals("A1 / B · 来自 今日推荐", historySubtitle(song(1).copy(playSource = "今日推荐")))
        assertEquals("A1 / B · 专辑", historySubtitle(song(1, album = "专辑")))
    }

    @Test fun trackNavigationHelpers() {
        assertEquals("A1", TrackNavigation.primaryArtist("A1 / B"))
        assertEquals("周杰伦", TrackNavigation.primaryArtist("周杰伦、方文山"))
        assertFalse(TrackNavigation.canOpenAlbum(song(1, album = "X")))
        assertTrue(TrackNavigation.canOpenAlbum(song(1, album = "X", albumId = "99")))
        assertFalse(TrackNavigation.canOpenAlbum(song(1, album = "X", albumId = "99").copy(source = "local")))
        val card = TrackNavigation.albumCard(song(1, album = "X", albumId = "99"))
        assertEquals("99", card.id); assertEquals("album", card.kind); assertEquals("wy", card.source)
        assertTrue(TrackNavigation.shareText(song(1, album = "X")).startsWith("Song 1 - A1 / B"))
        assertEquals("一二三四五六七八九…", TrackNavigation.shortHint("一二三四五六七八九十十一"))
    }
}
