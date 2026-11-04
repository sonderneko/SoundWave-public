package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationGlyphTest {
    @Test
    fun glyphColorsArePerRowDistinctAndReadable() {
        // ACCEPT5 / V3（用户 2026-11-04 裁定，规格变更重钉）：设置行左图逐行彩色、各行不同色、无底色，
        // 颜色钉在行（glyph spec）上、随行移动不串色。旧「全站单色 ink」口径（v2 稿）已被该裁定取代；
        // 本约更严：每个 spec 必须有色、全表互异、且在浅/夜两套页面底色上均保持 ≥3:1 对比。
        val specs = SettingGlyphSpec.values().toList()
        val colors = specs.map { settingGlyphColor(it) }
        specs.zip(colors).forEach { (spec, color) ->
            assertNotEquals("glyph must carry its own pinned color, not Unspecified: $spec", Color.Unspecified, color)
        }
        assertEquals("every settings glyph spec must own a distinct color", colors.size, colors.toSet().size)
        val lightBackground = Color(0xFFFAFAFA)
        val nightBackground = Color(0xFF101210)
        for ((spec, color) in specs.zip(colors)) {
            assertTrue(
                "glyph color must keep >=3:1 contrast on the light page background: $spec",
                contrastRatio(color, lightBackground) >= 3.0
            )
            assertTrue(
                "glyph color must keep >=3:1 contrast on the night page background: $spec",
                contrastRatio(color, nightBackground) >= 3.0
            )
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = foreground.luminance().toDouble()
        val second = background.luminance().toDouble()
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
