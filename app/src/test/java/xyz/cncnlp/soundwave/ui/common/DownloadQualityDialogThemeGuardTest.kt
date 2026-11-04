package xyz.cncnlp.soundwave.ui.common

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQualityDialogThemeGuardTest {
    @Test
    fun ordinaryDialogColorsFollowThemeWithoutChangingQualitySemanticsOrActions() {
        val source = dialogSource()
        val dialog = section(source, "internal fun DownloadQualityDialog(", "\n@Composable\nprivate fun QualityGroupHeader")
        val helpers = section(source, "private fun QualityGroupHeader(", "internal fun downloadQualityAccent(")

        // r658b 第 13 项：外壳改为统一 DesignSheetDialog（面板 frostedSheetFill(surface) 在 DesignSheetPanel 内）。
        assertTrue(dialog.contains("DesignSheetDialog(onDismiss = onDismiss"))
        assertTrue(dialog.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(dialog.contains("designLineColor()")) // r658f：取消按钮描边 = 设计线框色
        assertTrue(dialog.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(dialog.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(dialog.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        // r658b 第 13 项：下载按钮 = 设计强调色珊瑚红（不再取 primary，部分主题下为蓝色）。
        assertTrue(dialog.contains("SoundwaveDesignPalette.Accent"))
        assertTrue(dialog.contains("DownloadConfirmInk"))
        assertFalse(dialog.contains("Color.White"))
        listOf(
            "Color(0xFFBDBDC5.toInt())",
            "Color(0xFFEEEEF2.toInt())",
            "Color(0xFF1A1A22.toInt())",
            "Color(0xFF6B6B78.toInt())",
            "Color(0xFFD5DAE2.toInt())",
            "Color(0xFF3F4652.toInt())",
            "Color(0xFF2458A6.toInt())",
            "Color(0xFFE4E8EF.toInt())",
            "Color(0xFF77808E.toInt())"
        ).forEach { assertFalse("Legacy light-only color remains: $it", dialog.contains(it)) }

        assertTrue(dialog.contains("DownloadQualityPresentation.selectRequestedQuality(quality, onQualityChange)"))
        assertTrue(dialog.contains("onConfirm(selectedQuality)"))
        // MERGE7 对齐（J8，与 download-quality-evidence 壳守卫的已登记重对齐同款）：取消钮走动画关闭句柄。
        assertTrue(dialog.contains("rememberDesignSheetDismiss(onDismiss)"))
        assertTrue(dialog.contains("onClick = dismiss"))
        assertTrue(helpers.contains("else colors.onSurface")) // r658f：选项行改为设计抽屉行
        assertTrue(helpers.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(helpers.contains("val warningColor = Color(0xFFB85B13.toInt())"))
        assertTrue(helpers.contains("FrostDivider(designLineColor())"))
    }

    private fun section(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("Download quality dialog source boundary must remain discoverable", start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun dialogSource(): String {
        val relativePath = "src/main/java/xyz/cncnlp/soundwave/ui/common/DownloadQualityDialog.kt"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate DownloadQualityDialog.kt for the theme guard")
    }
}
