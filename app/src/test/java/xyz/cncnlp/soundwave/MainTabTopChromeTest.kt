package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * accept6-topbars：主页滚动联动顶栏进度（与详情页同一纯函数合成方式）。
 * 区间以 px 给定：状态区间 48px（24dp@2x）、标题区间 192px（96dp@2x），与 dp 数值同比例。
 */
class MainTabTopChromeTest {

    @Test
    fun atTopBothProgressZero() {
        val (bg, title) = mainTabChromeProgress(0, 0, 48f, 192f)
        assertEquals(0f, bg, 0f)
        assertEquals(0f, title, 0f)
    }

    @Test
    fun backgroundCompletesBeforeTitle() {
        // 滚过状态区间（48px）：背景已满，标题只走了 1/4。
        val (bg, title) = mainTabChromeProgress(0, 48, 48f, 192f)
        assertEquals(1f, bg, 0f)
        assertEquals(0.25f, title, 0.001f)
    }

    @Test
    fun titleCompletesAtHeroRange() {
        val (bg, title) = mainTabChromeProgress(0, 192, 48f, 192f)
        assertEquals(1f, bg, 0f)
        assertEquals(1f, title, 0f)
    }

    @Test
    fun pastFirstItemBothFull() {
        val (bg, title) = mainTabChromeProgress(2, 0, 48f, 192f)
        assertEquals(1f, bg, 0f)
        assertEquals(1f, title, 0f)
    }

    @Test
    fun backgroundNeverBehindTitleAndMonotonic() {
        var prevBg = 0f
        var prevTitle = 0f
        for (offset in 0..240 step 8) {
            val (bg, title) = mainTabChromeProgress(0, offset, 48f, 192f)
            assertTrue(bg >= title)
            assertTrue(bg >= prevBg && title >= prevTitle)
            prevBg = bg
            prevTitle = title
        }
    }
}
