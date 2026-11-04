package xyz.cncnlp.soundwave.ui.common

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDialogsThemeGuardTest {
    @Test
    fun onlineImportDialogUsesThemeRolesAndPreservesInputValidationAndCallbacks() {
        val dialog = section("internal fun OnlineImportDialog(", "/** 关于 SoundWave")

        assertTrue(dialog.contains("PlaylistInputDialog("))
        assertTrue(dialog.contains("title = \"导入在线歌单\""))
        assertTrue(dialog.contains("label = \"歌单链接 / ID\""))
        assertTrue(dialog.contains("confirmEnabled = plUrl.isNotBlank()"))
        assertTrue(dialog.contains("val raw = plUrl.trim()"))
        assertTrue(dialog.contains("if (raw.isNotEmpty()) onImport(raw)"))
        assertTrue(dialog.contains("onDismiss = onDismiss"))
        assertTrue(dialog.contains("支持五平台长链接、短链及分享文案"))
        val shared = projectSource("src/main/java/xyz/cncnlp/soundwave/ui/common/PlaylistDialogs.kt", "Missing playlist presentation")
        assertTrue(shared.contains("singleLine = true"))
        assertTrue(shared.contains("focusedContainerColor = colors.surface"))
        assertTrue(shared.contains("focusedBorderColor = colors.primary"))
        assertTrue(shared.contains("unfocusedBorderColor = designLineColor()"))
        assertTrue(shared.contains("focusedTextColor = colors.onSurface"))
        assertTrue(shared.contains("secondary = \"取消\""))
        assertTrue(shared.contains("primaryEnabled = confirmEnabled"))
        assertFalse(dialog.contains("Color.White"))
        assertFalse(dialog.contains("Color.Black"))
    }

    @Test
    fun aboutDialogUsesThemeRolesAndPreservesCopyAndUpdateLinkBehavior() {
        val source = appDialogsSource()
        val start = source.indexOf("internal fun AboutDialog(")
        val end = source.indexOf("\n}", start)
        assertTrue("AboutDialog boundary must remain discoverable", start >= 0 && end > start)
        val dialog = source.substring(start, end)

        // 对话框清扫（SW-12-08，用户批准形态变更）：AlertDialog 形态断言等严改钉 DesignSheet 弹层形态——
        // 关闭回调、标题文案仍被逐字钉住，并新增「无 AlertDialog 残留」负断言；行为断言（下方）逐字不变。
        assertTrue(dialog.contains("DesignSheetDialog(onDismiss = onDismiss"))
        assertFalse(dialog.contains("AlertDialog("))
        assertTrue(dialog.contains("DesignSheetHeader(\"About\", \"关于 SoundWave\")"))
        assertTrue(dialog.contains("SoundWave · \${BuildConfig.VERSION_NAME}"))
        assertTrue(dialog.contains("APP 简介"))
        assertTrue(dialog.contains("Text(\"SoundWave 是面向个人使用的音乐播放器，支持本地音乐管理、播放与歌词显示；在线音源由用户自行配置。\", color = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(dialog.contains("Text(\"免责声明：仅供个人学习研究，请自备合法音源并遵守相关法规。\", color = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(dialog.contains("正在检测更新…"))
        assertTrue(dialog.contains("val url = xyz.cncnlp.soundwave.GitHubReleases.downloadPage()"))
        assertTrue(dialog.contains("xyz.cncnlp.soundwave.GitHubReleases.check(BuildConfig.VERSION_NAME)"))
        assertFalse(dialog.contains("/releases/SoundWave-latest.apk"))
        assertTrue(dialog.contains("if (url.startsWith(\"http\"))"))
        assertTrue(dialog.contains("Intent(Intent.ACTION_VIEW, Uri.parse(url))"))
        assertTrue(dialog.contains("DesignSheetRow(g.Switch, \"检测更新\""))
        assertTrue(dialog.contains("DesignSheetRow(g.Download, \"前往下载\""))
        // 收尾棒 J8 句柄（WAVE-MERGE11-TAIL 登记）：「关闭」由直传 onDismiss 改为关闭句柄 dismiss
        // （退场结束才触发 onDismiss），等严改钉——关闭路径仍被逐字钉住，仅触发时机按 J8 纪律后移。
        assertTrue(dialog.contains("secondary = \"关闭\", onSecondary = dismiss"))
        assertFalse(dialog.contains("Color.White"))
        assertFalse(dialog.contains("Color.Black"))
    }

    private fun section(startMarker: String, endMarker: String): String {
        val source = appDialogsSource()
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("Dialog source boundary must remain discoverable", start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun appDialogsSource(): String {
        val relativePath = "src/main/java/xyz/cncnlp/soundwave/ui/common/AppDialogs.kt"
        return projectSource(relativePath, "Unable to locate AppDialogs.kt for the common dialog theme guard")
    }

    private fun sharedFieldColorsSource(): String {
        val source = projectSource(
            "src/main/java/xyz/cncnlp/soundwave/SettingsSheets.kt",
            "Unable to locate SettingsSheets.kt for the shared field theme guard"
        )
        val start = source.indexOf("internal fun fieldColors()")
        val end = source.indexOf("\n)", start)
        assertTrue("Shared fieldColors boundary must remain discoverable", start >= 0 && end > start)
        return source.substring(start, end + 2)
    }

    private fun projectSource(relativePath: String, errorMessage: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error(errorMessage)
    }
}
