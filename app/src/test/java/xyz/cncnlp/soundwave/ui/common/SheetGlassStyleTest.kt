package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ThemeTokens

/**
 * BATCH2 反馈 F2 钉：对话框磨砂在任何偏移/滚动进度下不许翻成平板色卡。
 * 定因——haze 对采不到模糊源的 texel 用 HazeStyle.backgroundColor / fallbackTint 顶替：
 * 顶栏带旧 backgroundColor 为不透明 surface，局部源（滚动列）的透明 texel 被整片填白；
 * 面板旧 fallbackTint 0.94 与静止 tint 0.82 并存，进场/拖拽越界处翻出近不透明白块。
 */
class SheetGlassStyleTest {

    private val surface = Color(0xFFFFFFFF)
    private val paper = Color(0xFFF4F4F0)

    @Test
    fun panelFallbackMatchesTintAlpha() {
        val style = sheetPanelGlassStyle(surface, ThemeTokens.SheetFrost.GlassTintAlpha)
        assertEquals(
            "panel fallback alpha must equal the resting tint alpha (no opaque slab while dragging)",
            style.tints.single().color.alpha, style.fallbackTint.color.alpha, 0.001f
        )
        assertEquals(ThemeTokens.SheetFrost.GlassTintAlpha, style.fallbackTint.color.alpha, 0.001f)
        assertEquals("panel background stays opaque surface (global source is the full page)", 1f, style.backgroundColor.alpha, 0.001f)
    }

    @Test
    fun headerBandBackgroundIsTransparent() {
        val style = sheetHeaderGlassStyle(surface)
        assertEquals(
            "pinned header background must be transparent: its local source is mostly transparent texels",
            0f, style.backgroundColor.alpha, 0.001f
        )
        assertTrue("header background must be fully transparent", style.backgroundColor == Color.Transparent)
        assertEquals(style.tints.single().color.alpha, style.fallbackTint.color.alpha, 0.001f)
        assertEquals(ThemeTokens.SheetFrost.GlassTintAlpha, style.fallbackTint.color.alpha, 0.001f)
    }

    @Test
    fun scrolledHeaderBackgroundIsTransparent() {
        val style = scrolledHeaderGlassStyle(paper)
        assertEquals(0f, style.backgroundColor.alpha, 0.001f)
        assertEquals("scrolled header tint stays the pinned paper 64%", 0.64f, style.tints.single().color.alpha, 0.001f)
        assertEquals(style.tints.single().color.alpha, style.fallbackTint.color.alpha, 0.001f)
    }
}
