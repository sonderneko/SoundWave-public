package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 第三轮 b：详情「沉浸」样式的纯逻辑（样式循环、取色带可读性、封面高度）。 */
class R657R3bImmersiveDetailTest {
    @Test
    fun restoredImmersiveHeroParticipatesInStyleCycle() {
        // 用户后续明确恢复沉浸封面；覆盖 r658h 的移除要求，不改变可读性/高度测试。
        assertEquals(DetailCoverStyles.IMMERSIVE_HERO, DetailCoverStyles.normalize("immersive_hero"))
        assertEquals(DetailCoverStyles.IMMERSIVE_HERO, DetailCoverStyles.normalize(DetailCoverStyles.IMMERSIVE))
        assertEquals(DetailCoverStyles.DEFAULT, DetailCoverStyles.normalize(null))
        assertEquals(DetailCoverStyles.STANDARD, DetailCoverStyles.next(DetailCoverStyles.DEFAULT))
        assertEquals(DetailCoverStyles.IMMERSIVE_HERO, DetailCoverStyles.next(DetailCoverStyles.STANDARD))
        assertEquals(DetailCoverStyles.DEFAULT, DetailCoverStyles.next(DetailCoverStyles.IMMERSIVE_HERO))
        assertEquals(3, DetailCoverStyles.CHOICES.size)
    }

    @Test
    fun bandColorAlwaysKeepsWhiteTextReadable() {
        for (tint in listOf(Color.White, Color(0xFFFFEB3B), Color(0xFF7EE0FF), Color(0xFFFF5C45), Color(0xFF6B5A4E), Color.Black)) {
            val band = immersiveDetailBandColor(tint)
            assertTrue("band for $tint too bright: ${band.luminance()}", band.luminance() <= 0.17f)
            assertEquals(Color.White, detailAdaptiveTextForeground(band))
            assertEquals(1f, band.alpha, 0.0001f)
        }
        assertEquals(Color.Black, immersiveDetailBandColor(Color.Black))
    }

    @Test
    fun coverHeightTracksWidthWithinBounds() {
        assertEquals(360, immersiveDetailCoverHeightDp(360))
        assertEquals(411, immersiveDetailCoverHeightDp(411))
        assertEquals(320, immersiveDetailCoverHeightDp(280))
        assertEquals(480, immersiveDetailCoverHeightDp(800))
    }
}
