package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ui.player.CoverBackgroundTones

/**
 * 通知取色口径：有封面取色结果时用其 top 色调（与播放器/磨砂面同一套 CoverToneCache 取色），
 * 无结果回落品牌珊瑚 0xFFE8452F（旧写死行为），保证呈现永不差于旧版。
 */
class NotificationTintTest {

    @Test
    fun fallbackIsTheLegacyBrandCoral() {
        assertEquals(0xFFE8452F.toInt(), NotificationTint.FallbackArgb)
    }

    @Test
    fun nullTonesFallBackToBrandCoral() {
        assertEquals(NotificationTint.FallbackArgb, NotificationTint.accentArgb(null))
    }

    @Test
    fun tonesTopBecomesTheAccent() {
        val tones = CoverBackgroundTones(
            top = Color(0xFF3A5F8A.toInt()),
            lower = Color(0xFF10151D.toInt()),
            artworkLuminance = 0.4f,
        )
        assertEquals(0xFF3A5F8A.toInt(), NotificationTint.accentArgb(tones))
    }

    @Test
    fun accentIgnoresLowerTone() {
        val tones = CoverBackgroundTones(
            top = Color(0xFF7A4A21.toInt()),
            lower = Color(0xFF22AA55.toInt()),
            artworkLuminance = 0.6f,
        )
        assertEquals(0xFF7A4A21.toInt(), NotificationTint.accentArgb(tones))
    }
}
