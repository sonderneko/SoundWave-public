package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.656 (user item 9): 查看下载 page moved to DownloadsPage.kt and its directory dialog now uses the
 * unified design sheet (eyebrow + serif title + DesignSheetRow). Behaviour is unchanged: 查看目录 then
 * 选择目录, each invoking its callback and closing the sheet.
 */
class DownloadDirectorySheetStyleGuardTest {
    @Test
    fun directoryActionsMatchApkTextRowsAndPreserveCallbacks() {
        val actions = section(
            directorySheetSource(),
            "if (showDirSheet) {",
            "if (showClearSheet) {"
        )

        assertTrue(actions.contains("DesignSheetDialog(onDismiss = { showDirSheet = false })"))
        assertTrue(actions.contains("DesignSheetHeader(\"Download folder\", \"下载目录\")"))
        assertTrue(actions.contains("\"查看目录\""))
        assertTrue(actions.contains("\"选择目录\""))
        // ACCEPT4 重钉（等同或更高严格度）：回调保留，关闭改走 J8 退场句柄 dismiss()
        //（showDirSheet = false 归退场完成后的 onDismiss），不再同步清状态掐断退场动画。
        assertTrue(actions.contains("onOpenDownloadDir(); dismiss()"))
        assertTrue(actions.contains("onPickDownloadDir(); dismiss()"))
        assertTrue(actions.contains("rememberDesignSheetDismiss { showDirSheet = false }"))
        assertTrue(actions.indexOf("\"查看目录\"") < actions.indexOf("\"选择目录\""))
        assertFalse(actions.contains("downloadColors"))
        assertFalse(actions.contains("AlertDialog("))
        assertFalse(actions.contains("Color.Black"))
    }

    private fun section(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("Download directory action source boundary must remain discoverable", start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun directorySheetSource(): String {
        val relativePath = "src/main/java/xyz/cncnlp/soundwave/DownloadsPage.kt"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate DownloadsPage.kt for the download directory sheet guard")
    }
}
