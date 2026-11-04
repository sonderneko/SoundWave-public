package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryThemeGuardTest {
    @Test
    fun historyActionsUseSemanticRolesInLightAndNightThemes() {
        val lightScheme = lightScheme()
        val nightScheme = darkScheme()
        val light = historyPageThemeColors(lightScheme)
        val night = historyPageThemeColors(nightScheme)

        for ((scheme, colors) in listOf(lightScheme to light, nightScheme to night)) {
            assertEquals(scheme.onSurface, colors.onSurface)
            assertEquals(scheme.onSurfaceVariant, colors.onSurfaceVariant)
            assertEquals(scheme.primary, colors.primary)
            assertEquals(scheme.error, colors.error)
            assertEquals(scheme.onError, colors.onError)
            assertTrue(contrastRatio(colors.onSurface, scheme.surface) >= 4.5)
            assertTrue(contrastRatio(colors.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
            assertTrue(contrastRatio(colors.onError, colors.error) >= 4.5)
        }

        assertNotEquals(light.onSurface, night.onSurface)
        assertNotEquals(light.onSurfaceVariant, night.onSurfaceVariant)
        assertNotEquals(light.primary, night.primary)
        assertNotEquals(light.error, night.error)
    }

    @Test
    fun historyThemeGuardPreservesSwipeDeleteAndConfirmationContract() {
        val source = extraPagesSource()
        val swipe = source.substringAfter("private fun SwipeToDeleteHistoryRow(")
            .substringBefore("@OptIn(ExperimentalFoundationApi::class)")
        val history = source.substringAfter("fun PlayHistoryPage(")

        // r656 item 12：整卡左滑删除，右侧不再出现「删除」文字或图标。
        assertTrue(swipe.contains("HistoryTimelinePolicy.clampSwipeOffset(offset.value, delta, widthPx)"))
        assertTrue(swipe.contains("enabled = enabled && !deleting"))
        assertTrue(swipe.contains("HistoryTimelinePolicy.shouldDelete(offset.value, widthPx, velocity)"))
        assertTrue(swipe.contains("onDelete()"))
        // MERGE7 对齐（J7，用户裁定）：滑动揭示底色改中性 onSurface 渐变，禁用 error 粉红；并反向钉死旧形态。
        assertTrue(swipe.contains("themeColors.onSurface.copy(alpha = 0.04f + 0.06f * progress)"))
        assertFalse(swipe.contains("themeColors.error.copy(alpha ="))
        assertFalse(swipe.contains("Text(\"删除\""))
        assertFalse(swipe.contains("Icon("))

        assertTrue(history.contains("SwipeToDeleteHistoryRow("))
        assertTrue(history.contains("enabled = true"))
        assertTrue(history.contains("onDelete = { onDelete(s) }"))
        assertTrue(history.contains("Modifier.fillMaxSize().clickable { onPlay(s) }"))
        assertFalse(history.contains("onLongClick ="))
        assertFalse(history.contains("combinedClickable("))
        assertTrue(history.contains("onDismissRequest = { showClearDlg = false }"))
        assertTrue(history.contains("清空播放历史？"))
        assertTrue(history.contains("onClear()"))
        assertTrue(history.contains("SettingsConfirmSheet("))
        assertFalse(history.contains("Color.Black"))
        assertFalse(history.contains("Color(0xFFB3261E"))
    }

    private fun extraPagesSource(): String {
        val relativePath = "src/main/java/xyz/cncnlp/soundwave/ExtraPages.kt"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate ExtraPages.kt for the playback history guard")
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = foreground.luminance().toDouble()
        val backgroundLuminance = background.luminance().toDouble()
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
