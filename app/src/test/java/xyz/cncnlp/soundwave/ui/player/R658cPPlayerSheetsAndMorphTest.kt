package xyz.cncnlp.soundwave.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r658c P：播放器抽屉统一走公共 DesignSheet；默认样式封面 ⇄ 歌词连续过渡的几何换算。 */
class R658cPPlayerSheetsAndMorphTest {
    private fun src(rel: String): String = listOf("src/main/java/xyz/cncnlp/soundwave/$rel", "app/src/main/java/xyz/cncnlp/soundwave/$rel")
        .map(::File).first { it.exists() }.readText()

    @Test
    fun playerSheetsUseTheSharedBottomAnchoredSheet() {
        val dir = listOf("src/main/java/xyz/cncnlp/soundwave/ui/player", "app/src/main/java/xyz/cncnlp/soundwave/ui/player").map(::File).first { it.isDirectory }
        dir.listFiles { f -> f.extension == "kt" }!!.forEach { f ->
            assertFalse("${f.name} still uses Material3 ModalBottomSheet", f.readText().contains("ModalBottomSheet("))
        }
        val menu = src("PlayerMenuSheet.kt")
        // M 波规格变更（ISSUE-SONGMENU §4.1）：面板不再自滚——封顶 0.70 + 内容内滚、ScrollState hoist 给面板拖拽关闭连接。
        assertTrue(menu.contains("DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, maxHeightFraction = TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION, contentScrollState = menuScrollState)"))
        assertTrue(menu.contains("TrackActionsSheetContent("))
        assertFalse(menu.contains("DialogProperties"))
        assertTrue(src("ui/player/PlayerLyricActionsSheet.kt").contains("DesignSheetDialog(onDismiss = onDismiss, scrollContent = true)"))
        assertFalse(src("ui/player/DefaultPlayerQueuePage.kt").contains("来源歌单\", colors"))
    }

    @Test
    fun coverMorphStartsOnTheBreathingCoverAndLandsOnTheMiniSlot() {
        val main = Rect(Offset(28f, 100f), Size(300f, 300f))
        val mini = Rect(Offset(22f, 40f), Size(52f, 52f))
        val start = playerCoverMorphFrame(main, mini, 0f, baseScale = 0.92f)
        assertEquals(0.92f, start.scale, 1e-4f)
        assertEquals(300f * 0.04f, start.translationX, 1e-3f) // 以中心缩放 0.92 → 左上角右移 4%
        val end = playerCoverMorphFrame(main, mini, 1f, baseScale = 0.92f)
        assertEquals(52f / 300f, end.scale, 1e-4f)
        assertEquals(mini.left - main.left, end.translationX, 1e-3f)
        assertEquals(mini.top - main.top, end.translationY, 1e-3f)
    }

    @Test
    fun visibleCornerReachesMiniRadiusContinuouslyWithoutFinalShapeJump() {
        val main = Rect(Offset(28f, 100f), Size(300f, 300f))
        val mini = Rect(Offset(22f, 40f), Size(52f, 52f))
        var previous = 28f * 0.92f
        for (i in 0..100) {
            val p = i / 100f
            val frame = playerCoverMorphFrame(main, mini, p, 0.92f)
            val visible = playerCoverMorphCornerDp(p, frame.scale, 0.92f) * frame.scale
            assertTrue(kotlin.math.abs(visible - previous) < 0.2f)
            previous = visible
        }
        assertEquals(12f, previous, 1e-3f)
    }

    @Test
    fun pageAndCoverAlphasCrossFadeWithoutGaps() {
        assertEquals(1f, playerMainPageAlpha(0f), 0f)
        assertEquals(0f, playerMainPageAlpha(0.6f), 0f)
        assertEquals(0f, playerLyricsPageAlpha(0.35f), 0f)
        assertEquals(1f, playerLyricsPageAlpha(1f), 0f)
        for (p in listOf(0f, 0.5f, 0.9f, 0.95f, 1f)) {
            assertEquals(1f, playerMiniCoverAlpha(p, morphReady = true) + playerFlyingCoverAlpha(p), 1e-4f)
        }
        assertEquals(1f, playerMiniCoverAlpha(0f, morphReady = false), 0f)
    }
}
