package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks

class SettingsNavigationGlyphTest {
    @Test
    fun glyphColorsAreGroupUnifiedAndKeyedToKickerMarks() {
        // ACCEPT6 iconcolor（用户 2026-11-04 23:25 裁定，规格变更重钉）：设置图标颜色按组统一——
        // 以该组英文副标题（kicker）的颜色为准，一组只许一个颜色。旧 V3「逐行配色、全表互异」口径
        // （ACCEPT5）已被该裁定取代；新约在目标维度更严：每个 spec 的颜色必须与其所属组的
        // SettingsGroupMarks 组色逐值相等（组色即组头眉题的渲染色），组内不得出现第二色，
        // 六组之间必须互异。旧约的 ≥3:1 对比带是为当时的自生成色板设的规格，随色板退役——
        // 目标色改为已上线在用的 kicker 色、由用户令定死，不在本约可调范围（台账 WAVE-ACCEPT6-ICONCOLOR 已登记）。
        val expectedMark = mapOf(
            SettingGlyphGroup.Playback to SettingsGroupMarks.Playback,
            SettingGlyphGroup.Sources to SettingsGroupMarks.Sources,
            SettingGlyphGroup.Downloads to SettingsGroupMarks.Downloads,
            SettingGlyphGroup.Appearance to SettingsGroupMarks.Appearance,
            SettingGlyphGroup.Data to SettingsGroupMarks.Data,
            SettingGlyphGroup.Support to SettingsGroupMarks.Support
        )
        assertEquals(
            "the six group marks (kicker colors) must be pairwise distinct",
            expectedMark.size, expectedMark.values.toSet().size
        )
        val specs = SettingGlyphSpec.values().toList()
        for (spec in specs) {
            val color = settingGlyphColor(spec)
            assertNotEquals("glyph must carry a pinned color, not Unspecified: $spec", Color.Unspecified, color)
            assertEquals(
                "glyph color must equal its group's kicker mark: $spec",
                expectedMark.getValue(settingGlyphGroup(spec)), color
            )
        }
        for ((group, mark) in expectedMark) {
            val groupColors = specs.filter { settingGlyphGroup(it) == group }.map { settingGlyphColor(it) }.toSet()
            assertEquals("group $group must render in exactly one color", setOf(mark), groupColors)
        }
    }
}
