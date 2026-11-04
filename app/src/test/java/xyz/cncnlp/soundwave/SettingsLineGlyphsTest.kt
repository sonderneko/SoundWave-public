package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.settings.SettingsLineGlyphs

/** r655 第 15 项：设置项左侧图标（主页分类行 + 详情页子选项）各不相同，且全部来自同一套线性图标。 */
class SettingsLineGlyphsTest {
    @Test
    fun everySettingsGlyphHasDistinctArtwork() {
        val specs = SettingsLineGlyphs.pathSpecs
        val artworks = specs.values.map { it.joinToString("|") }
        assertEquals("图标路径出现重复", artworks.size, artworks.toSet().size)
    }

    @Test
    fun everySettingsOptionUsesItsOwnGlyph() {
        val keys = settingGlyphKeys()
        assertEquals("设置项之间共用了图标", keys.size, keys.toSet().size)
        keys.forEach { assertTrue("未定义图标：$it", SettingsLineGlyphs.pathSpecs.containsKey(it)) }
    }
}
