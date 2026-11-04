package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.player.PLAYER_COVER_FADE_TAIL
import xyz.cncnlp.soundwave.ui.player.playerCoverBridgeAlpha
import xyz.cncnlp.soundwave.ui.player.playerCoverFadeAlpha
import java.io.File

/** r674：右上角阴影 / 详情搜索栏 / 歌词页过渡 / Apple 顶部闪烁 / 沉浸封面渐隐。 */
class R674ChromeAndPlayerTest {
    private fun src(rel: String) = listOf(File("src/main/java/xyz/cncnlp/soundwave/$rel"), File("app/src/main/java/xyz/cncnlp/soundwave/$rel")).first { it.exists() }.readText()

    @Test fun headerControlsAndAvatarShareOuterShadow() {
        val shadow = src("ui/common/SectionCardShadow.kt")
        assertTrue(shadow.contains("fun Modifier.headerControlShadow(shape: Shape)") && shadow.contains("excludeInterior = true"))
        val buttons = src("ui/common/DesignCircleIconButton.kt")
        assertEquals(2, Regex("\\.headerControlShadow\\(CircleShape\\)").findAll(buttons).count())
        // 阴影必须在 clip / 磨砂背板之前，否则被自身裁切。
        assertTrue(buttons.indexOf(".headerControlShadow(CircleShape)\n            .frostedCircleBackdrop(") > 0)
        val avatar = src("AccountUi.kt")
        assertTrue(avatar.contains(".then(if (elevated) Modifier.headerControlShadow(CircleShape) else Modifier)\n                .clip(CircleShape)"))
        assertFalse(avatar.contains("elevated && !designDefault"))
        assertTrue(src("HomeDesignSections.kt").contains(".headerControlShadow(CircleShape) // r674"))
    }

    @Test fun detailSearchFieldAlignsToPageMarginWithEdgeShadow() {
        val header = src("TodayRecommendationDesign.kt")
        val i = header.indexOf("if (searchField != null) {")
        assertFalse(header.substring(i, header.indexOf("searchField()", i)).contains("Spacer(Modifier.width(10.dp))"))
        assertTrue(header.contains(".padding(start = 14.dp, end = 14.dp, top = 12.dp)"))
        val field = src("DetailDesignPages.kt")
        val f = field.indexOf("internal fun DetailCompactSearchField(")
        val body = field.substring(f, field.indexOf("BasicTextField", f))
        assertTrue(body.indexOf(".cssShadow(") in 1 until body.indexOf(".clip("))
        assertTrue(body.contains("excludeInterior = true"))
    }

    @Test fun lyricsPageTransitionDirectionsAndReduceMotion() {
        val shift = 100f
        // 进入歌词：旧封面页向上离开，新歌词页自下浮入；alpha=1 时归位。
        assertEquals(-50f, playerPageShiftPx(0.5f, incoming = false, dir = 1f, shiftPx = shift), 0.01f)
        assertEquals(50f, playerPageShiftPx(0.5f, incoming = true, dir = 1f, shiftPx = shift), 0.01f)
        assertEquals(0f, playerPageShiftPx(1f, incoming = true, dir = -1f, shiftPx = shift), 0.01f)
        assertEquals(-100f, playerPageShiftPx(0f, incoming = true, dir = -1f, shiftPx = shift), 0.01f)
        val full = src("FullPlayer.kt")
        assertTrue(full.contains("val pageMotion = !defaultLayout"))
        assertTrue(full.contains("if (!pageMotion || reducePageMotion) { appleShownLyrics = lyricLayout; pageIncoming = true; applePageAlpha.snapTo(1f)"))
        assertTrue(full.contains("val pageLyrics = if (pageMotion) appleShownLyrics else lyricLayout"))
        assertEquals(140, PLAYER_PAGE_EXIT_MS); assertEquals(260, PLAYER_PAGE_ENTER_MS)
    }

    @Test fun appleTopChromeDoesNotFlashOnPageSwitch() {
        val full = src("FullPlayer.kt")
        // 拖动条不再位于随页面淡出淡入的列内。
        assertTrue(full.contains("if (!defaultLayout) Spacer(Modifier.fillMaxWidth().height(PLAYER_DRAG_HANDLE_SLOT_DP.dp))"))
        assertTrue(full.contains("if (!defaultLayout) PlayerDragHandle(Modifier.align(Alignment.TopCenter).statusBarsPadding())"))
        assertFalse(full.contains(".width(40.dp)\n                        .height(5.dp)"))
        // Hero 状态栏压暗随过渡渐变。
        assertTrue(full.contains("statusScrimAlpha = { if (appleMode) (if (pageLyrics) applePageAlpha.value else 0f) else 1f }"))
        assertTrue(src("ui/player/PlayerImmersiveCoverHero.kt").contains("alpha = statusScrimAlpha().coerceIn(0f, 1f)"))
    }

    @Test fun playerCoverFadeIsSmoothAndEndsBeforeLayerEdge() {
        listOf(0.34f, 0.40f, 0.42f).forEach { f ->
            assertEquals(1f, playerCoverFadeAlpha(1f - f, f), 1e-4f)
            assertEquals(0f, playerCoverFadeAlpha(1f - PLAYER_COVER_FADE_TAIL, f), 1e-4f)
            assertEquals(0f, playerCoverFadeAlpha(1f, f), 1e-6f)
            var prev = 1f; var prevSlope = 0f; var maxSlope = 0f; var maxCurv = 0f
            val n = 2000
            for (i in 1..n) {
                val p = i / n.toFloat(); val a = playerCoverFadeAlpha(p, f)
                assertTrue(a <= prev + 1e-6f)
                val slope = (prev - a) * n
                maxSlope = maxOf(maxSlope, slope); maxCurv = maxOf(maxCurv, kotlin.math.abs(slope - prevSlope))
                prev = a; prevSlope = slope
            }
            // 起止斜率为 0（smootherstep），末端接近 0 时不再有斜率突变。
            val end = 1f - PLAYER_COVER_FADE_TAIL
            val nearEnd = (playerCoverFadeAlpha(end - 0.002f, f) - playerCoverFadeAlpha(end, f)) / 0.002f
            assertTrue("slope at fade end should vanish: $nearEnd", nearEnd < 0.05f)
            assertEquals(0f, playerCoverBridgeAlpha(1f - f, f), 1e-4f)
            assertEquals(0f, playerCoverBridgeAlpha(end, f), 1e-4f)
        }
    }
}
