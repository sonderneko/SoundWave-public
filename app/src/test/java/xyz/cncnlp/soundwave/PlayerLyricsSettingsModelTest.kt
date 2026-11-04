package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsSettingsModelTest {
    @Test fun tenSpIsSelectableAndRenderedWithoutFlooringToFourteen() {
        assertEquals(10, PlayerLyricsSettingsModel.font(10).valueAt(0f))
        assertEquals(10, PlayerLyricsSettingsModel.snapFont(10))
        assertEquals(10f, xyz.cncnlp.soundwave.ui.player.playerLyricRenderFontSp(false,10,30,1f),0f)
        assertEquals(18f, xyz.cncnlp.soundwave.ui.player.playerLyricRenderFontSp(false,18,30,1f),0f)
        assertEquals(30f, xyz.cncnlp.soundwave.ui.player.playerLyricRenderFontSp(true,18,30,1f),0f)
        // 全页统一（2026-11-04）：默认页字号 = 统一字号原值（旧 +9 已摘除，10sp 不再抬到 19）。
        assertEquals(10, PlayerLyricsSettingsModel.defaultPageFontSp(10))
    }

    @Test
    fun menuSlidersExposeOneUnifiedLyricPreferenceSet() {
        val settings = PlayerLyricsSettingsModel.menuSettings(fontSp = 18, lineHeightDp = 9)

        // r658c P：去掉「沉浸歌词字号 / 行高」，「普通歌词字号 / 行高」改名「歌词字号 / 行高」；
        // 统一偏好集为 字号 / 行高 两滑杆（2026-11-04 距离模糊设置项删除，渲染钉死固定值）。
        assertEquals(listOf("歌词字号", "歌词行高"), settings.map { it.title })
        assertEquals(listOf("18sp · 10–32", "9dp · 6–36"), settings.map { it.valueLabel })
        assertEquals(listOf(10, 6), settings.map { it.values.first() })
        assertEquals(listOf(32, 36), settings.map { it.values.last() })
        assertEquals(listOf(4, 1), settings.map { java.lang.Math.round(it.sliderValue) })
        assertFalse(settings.any { it.title.contains("沉浸") || it.title.contains("普通") || it.title.contains("距离模糊") })
    }

    @Test
    fun sliderPositionsSnapToTheOriginalStoredValueSteps() {
        val font = PlayerLyricsSettingsModel.font(18)
        val lineHeight = PlayerLyricsSettingsModel.lineHeight(9)

        assertEquals(10, font.valueAt(-1f))
        assertEquals(20, font.valueAt(5f))
        assertEquals(36, lineHeight.valueAt(20f))
    }

    @Test
    fun everyPlayerSurfaceUsesTheUnifiedFontAndGapVerbatim() {
        // 全页统一（2026-11-04）：三样式有效字号/行高 = 统一值原值——沉浸与默认页均 18sp / 9dp（旧换算
        // 沉浸 30sp/14dp、默认页 27sp/10dp 已摘除）；距离模糊渲染公式不变（输入钉死固定值 2：每行 0.75dp、上限 2.6）。
        assertEquals(PlayerLyricsSettingsModel.defaultPageFontSp(18), PlayerLyricsSettingsModel.immersiveFontFor(18))
        assertEquals(9, PlayerLyricsSettingsModel.immersiveLineHeightFor(9))
        assertEquals(18, PlayerLyricsSettingsModel.defaultPageFontSp(18))
        assertEquals(9f, PlayerLyricsSettingsModel.defaultPageRowPaddingDp(9), 0.001f)
        assertEquals(0.75f, PlayerLyricsSettingsModel.defaultPageBlurDp(1, 2), 0.001f)
        assertEquals(2.6f, PlayerLyricsSettingsModel.defaultPageBlurDp(9, 2), 0.001f)
        assertEquals(0f, PlayerLyricsSettingsModel.defaultPageBlurDp(3, 0), 0.001f)
        // 调大统一字号，所有模式同步变大。
        assertTrue(PlayerLyricsSettingsModel.immersiveFontFor(24) > PlayerLyricsSettingsModel.immersiveFontFor(18))
        assertTrue(PlayerLyricsSettingsModel.defaultPageFontSp(24) > PlayerLyricsSettingsModel.defaultPageFontSp(18))
        assertEquals(PlayerLyricsSettingsModel.defaultPageFontSp(99), PlayerLyricsSettingsModel.immersiveFontFor(99))
    }

    @Test
    fun offsetStepsAreBoundedAndShared() {
        assertEquals(500, PlayerLyricsSettingsModel.stepOffset(0, PlayerLyricsSettingsModel.OFFSET_STEP_MS))
        assertEquals(-30_000, PlayerLyricsSettingsModel.stepOffset(-29_800, -500))
        assertEquals(0, PlayerLyricsSettingsModel.stepOffset(1_500, -1_500))
        assertEquals("0ms · 不偏移", lyricOffsetLabel(0))
        assertEquals("+500ms", lyricOffsetLabel(500))
        assertEquals("-1000ms", lyricOffsetLabel(-1000))
    }

    @Test
    fun legacyImmersivePreferencesMigrateOnlyWhenUnifiedValuesWereNeverSet() {
        // 只调过沉浸值：按比例换算到统一值并吸附到步进。
        assertEquals(24 to 18, PlayerLyricsSettingsModel.migrateLegacyImmersive(null, null, 40, 28))
        // 普通值已存在：以普通值为准，不覆盖。
        assertEquals(null to null, PlayerLyricsSettingsModel.migrateLegacyImmersive(20, 12, 40, 28))
        // 沉浸值等于旧默认或不存在：无需写入。
        val (font, gap) = PlayerLyricsSettingsModel.migrateLegacyImmersive(null, null, 30, null)
        assertNull(font)
        assertNull(gap)
    }
}
