package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.common.DOCK_FROSTED_MIN_ALPHA
import xyz.cncnlp.soundwave.ui.common.dockChromeFill
import xyz.cncnlp.soundwave.ui.common.spiderThreadLengthDp
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerState
import xyz.cncnlp.soundwave.ui.player.lyricKaraokeText
import xyz.cncnlp.soundwave.ui.search.searchGenreLiftDp

/** r657 纯函数：底栏磨砂填充、热门歌曲 TOP N、蜘蛛下拉刷新、探索声音起伏、歌词逐字、默认播放器标签与 UP NEXT。 */
class R657DesignHelpersTest {
    private val white = Color(0xFFFFFFFF)
    private val night = Color(0xFF191C19)

    @Test fun dockFillIsOpaqueWhenSwitchOff() {
        // 回归：v4 胶囊组完全透明。关闭时永远是不透明实色（浅色纯白 / 深色 App 表面色）。
        assertEquals(1f, dockChromeFill(white, dockFrosted = false).alpha, 0f)
        assertEquals(1f, dockChromeFill(night, dockFrosted = false, dark = true).alpha, 0f)
        assertEquals(1f, dockChromeFill(white.copy(alpha = 0f), dockFrosted = false).alpha, 0f)
        assertEquals(white, dockChromeFill(white, dockFrosted = false))
    }

    @Test fun dockFillIsFrostedButNeverTransparentWhenSwitchOn() {
        for (dark in listOf(false, true)) for (tint in listOf(null, Color(0xFF2877FF))) {
            val fill = dockChromeFill(if (dark) night else white, dockFrosted = true, tint = tint, dark = dark)
            assertTrue("frosted dock must stay readable (alpha=${fill.alpha})", fill.alpha >= DOCK_FROSTED_MIN_ALPHA)
            assertTrue("frosted dock must be translucent", fill.alpha < 1f)
        }
    }

    @Test fun hotTopCountFollowsPlatformCaps() {
        assertEquals(200, homeHotTopCount("wy", 200, 30))
        assertEquals(80, homeHotTopCount("tx", 300, 30))
        assertEquals(80, homeHotTopCount("kg", 500, 30))
        assertEquals(100, homeHotTopCount("kw", 300, 30))
        assertEquals(100, homeHotTopCount("mg", 0, 100))
        assertEquals(30, homeHotTopCount("wy", 0, 30))
    }

    @Test fun spiderThreadGrowsWithPull() {
        // 原型丝长 52dp：下拉到阈值正好 52，过阈值后橡皮筋式缓慢再伸长、上限 66。
        assertEquals(4f, spiderThreadLengthDp(0f), 0.001f)
        assertEquals(52f, spiderThreadLengthDp(1f), 0.001f)
        assertTrue(spiderThreadLengthDp(1.5f) in 52f..66f)
        assertTrue(spiderThreadLengthDp(9f) <= 66f)
        assertTrue(spiderThreadLengthDp(0.5f) in 4f..52f)
    }

    @Test fun genreLiftFollowsV29Formula() {
        // 原型 v2-9 updateGenreWave：lift = sin(i·1.05 − scrollLeft/95)·11px；连续滚动无跳变。
        assertEquals(0f, searchGenreLiftDp(0, 0f), 1e-4f)
        assertEquals((kotlin.math.sin(1.05) * 11).toFloat(), searchGenreLiftDp(1, 0f), 1e-3f)
        assertEquals((kotlin.math.sin(2 * 1.05 - 190.0 / 95) * 11).toFloat(), searchGenreLiftDp(2, 190f), 1e-3f)
        var prev = searchGenreLiftDp(3, 0f)
        var x = 0f
        while (x < 400f) {
            x += 1f
            val v = searchGenreLiftDp(3, x)
            assertTrue(kotlin.math.abs(v - prev) < 0.2f)
            prev = v
        }
    }

    @Test fun karaokeHighlightsProgressively() {
        val line = LyricLine(timeMs = 1000L, text = "abcd")
        val ink = Color.Black
        val half = lyricKaraokeText(line, 5000L, 3000L, ink)
        assertEquals("abcd", half.text)
        val sungChars = half.spanStyles.filter { it.item.color == ink }.sumOf { it.end - it.start }
        assertEquals(2, sungChars)
        val words = LyricLine(1000L, "ab cd", words = listOf(LyricWord(1000L, 2000L, "ab "), LyricWord(2000L, 3000L, "cd")))
        val w = lyricKaraokeText(words, null, 2500L, ink)
        assertEquals("ab cd", w.text)
        assertEquals(4, w.spanStyles.filter { it.item.color == ink }.sumOf { it.end - it.start })
    }

    @Test fun coverLabelAndUpNextPair() {
        val songs = (0 until 5).map { Song("s$it", "Song $it", "A", "wy") }
        fun st(mode: PlayMode, idx: Int = 1, source: String = "") = DefaultPlayerState(
            songs[idx], songs, idx, true, false, "", false, mode, 0f, 0f, 1f, "", 0.5f, false, emptyList(), 0L, false, sourceLabel = source
        )
        assertEquals("✦ 基于你的喜欢智能续播", st(PlayMode.HEART).coverLabel)
        assertEquals("来自 今日推荐", st(PlayMode.SEQUENCE, source = "今日推荐").coverLabel)
        assertEquals("", st(PlayMode.SEQUENCE).coverLabel) // r657 第三轮：无来源不显示胶囊
        assertEquals(listOf("s2", "s3"), st(PlayMode.SEQUENCE).upNextPair.map { it.id })
        assertEquals(1, st(PlayMode.SEQUENCE, idx = 3).upNextPair.size)
        assertTrue(!st(PlayMode.SEQUENCE).showVolume)
    }
}
