package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * ACCEPT5 页面背景统一（WAVE-ACCEPT5-NOTICE E，用户 2026-11-04 终局定版）：
 * 全 app 页面背景以内容区偏白为标准——设计主题与根主题的 background 必须同值，
 * 顶栏（AppPageHeader 自画 background）、详情页整页（自画 background）与内容区
 * （透出根底板）因此恒同色、无接缝；夜间同理。
 */
class PageBackgroundUnificationTest {

    @Test
    fun designAndRootBackgroundsAreIdenticalInBothThemes() {
        assertEquals(lightScheme().background, soundwaveDesignScheme(isDark = false).background)
        assertEquals(darkScheme().background, soundwaveDesignScheme(isDark = true).background)
    }

    @Test
    fun lightStandardIsTheWhiterContentBackgroundNotWarmPaper() {
        val designLight = soundwaveDesignScheme(isDark = false)
        assertEquals(Color(0xFFFAFAFA), designLight.background)
        assertNotEquals(SoundwaveDesignPalette.Paper, designLight.background)
    }

    @Test
    fun unifiedBackgroundsKeepTextContrast() {
        val light = soundwaveDesignScheme(isDark = false)
        val night = soundwaveDesignScheme(isDark = true)
        assertTrue(contrastRatio(light.onBackground, light.background) >= 7.0)
        assertTrue(contrastRatio(night.onBackground, night.background) >= 7.0)
    }

    /** 与 ThemePaletteTest 同口径（compose luminance 近似比值，只作下限断言）。 */
    private fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = max(foreground.luminance().toDouble(), background.luminance().toDouble())
        val l2 = min(foreground.luminance().toDouble(), background.luminance().toDouble())
        return (l1 + 0.05) / (l2 + 0.05)
    }
}
