package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMenuPresentationTest {
    @Test
    fun trackActionQuickTilesUseFourSlotsInRequestedOrder() {
        // r658c P：播放器与详情等页面共用同一排快捷钮：收藏 / 下一首 / 下载 / 相似歌曲。
        // M 波规格变更（ISSUE-SONGMENU §5，用户 21:17 指令）：四改五，「不喜欢」恒末位——
        // 两条键位断言同步追加 "dislike"（规格变更非放松，台账 WAVE-MERGE11-TAIL 登记）。
        assertEquals(listOf("like", "next", "download", "similar", "dislike"), trackActionQuickTileKeys(showDownload = true))
        assertEquals(listOf("like", "next", "add", "similar", "dislike"), trackActionQuickTileKeys(showDownload = false))
        assertEquals("本机 · 蓝牙 · DLNA", playerMenuSecondarySubtitle("menu_cast", 0))
        assertEquals("剩余 15 分钟", playerMenuSecondarySubtitle("menu_timer", 15))
        assertEquals("字号 · 行高 · 偏移", playerMenuSecondarySubtitle("menu_lyrics", 0))
    }

    @Test
    fun secondaryActionGlyphsAndSemanticColorsAreUniqueAcrossBothToggleStates() {
        val inactiveGlyphs = listOf(
            "menu_cast", "sparkles_outline", "menu_hide", "menu_timer", "menu_lyrics", "volume_on"
        )
        val activeGlyphs = listOf(
            "menu_cast", "sparkles_filled", "menu_hide", "menu_timer", "menu_lyrics", "volume_off"
        )
        listOf(inactiveGlyphs, activeGlyphs).forEachIndexed { index, glyphs ->
            assertEquals("secondary action group $index repeats a glyph", glyphs.size, glyphs.toSet().size)
            assertEquals(
                "secondary action group $index repeats a semantic color role",
                glyphs.size,
                glyphs.map { playerMenuActionColorRole(it) }.toSet().size
            )
        }
        assertEquals(PlayerMenuActionColorRole.ERROR, playerMenuActionColorRole("menu_delete"))
    }

    @Test
    fun heartModeMenuClearlyReflectsToggleStateAndSecondTapStops() {
        assertEquals("心动模式", heartModeMenuActionSpec(isActive = false).title)
        assertEquals("sparkles_outline", heartModeMenuActionSpec(isActive = false).glyph)
        assertEquals("关闭心动模式", heartModeMenuActionSpec(isActive = true).title)
        assertEquals("sparkles_filled", heartModeMenuActionSpec(isActive = true).glyph)

        var mode = PlayMode.SEQUENCE
        assertEquals(HeartModeToggleAction.START, heartModeToggleAction(mode))
        mode = PlayMode.HEART
        assertEquals(HeartModeToggleAction.STOP, heartModeToggleAction(mode))
        assertEquals(HeartModeToggleAction.START, heartModeToggleAction(PlayMode.SHUFFLE))
    }
}
