package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDialogThemeGuardTest {
    @Test
    fun playbackSourceSheetIsRemovedPerUserRuling() {
        // ACCEPT4 重钉（用户 2026-11-04 裁定）：旧「播放来源」候选列表弹窗整套删除、不作任何回退，
        // 出处只保留歌曲菜单内一行「播放来源」身份直达。本守卫由「钉住旧弹层样式」改为「钉住删除
        // 现实」——MainActivity 不得再出现该弹层的任何残留（状态/覆盖层/标题），严格度不降。
        val source = mainActivitySource()
        assertFalse(source.contains("showSourcePl"))
        assertFalse(source.contains("sourcePlSongs"))
        assertFalse(source.contains("// ===== 歌单来源 ====="))
        assertFalse(source.contains("onSourcePlaylist"))
    }

    @Test
    fun createPlaylistDialogUsesThemeAwareFieldAndPrimaryAction() {
        val source = mainActivitySource()
        val dialog = source.substringAfter("if (showNewPlDlg) {")
            .substringBefore("// ===== 关于 APP 底部 =====")

        val shared = File("src/main/java/xyz/cncnlp/soundwave/ui/common/PlaylistDialogs.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()
        assertTrue(dialog.contains("CreatePlaylistDialog("))
        assertTrue(dialog.contains("name = newPlName"))
        assertTrue(dialog.contains("onNameChange = { newPlName = it }"))
        assertTrue(dialog.contains("newPlName.trim().ifEmpty { \"新建歌单\" }"))
        assertTrue(shared.contains("DesignSheetDialog(onDismiss = onDismiss, scrollContent = true)"))
        // 对话框清扫（SW-12-08）：输入框与按钮行收敛为家族共用件 SheetInputField / SheetActionButtons，
        // 字段内部仍是主题感知 OutlinedTextField（填充/边框/文字色逐项钉住），比旧 SettingsSheetButtons 更严。
        assertTrue(shared.contains("SheetInputField("))
        assertTrue(shared.contains("OutlinedTextField("))
        assertTrue(shared.contains("focusedTextColor = colors.onSurface"))
        assertTrue(shared.contains("unfocusedBorderColor = designLineColor()"))
        assertTrue(shared.contains("SheetActionButtons("))
        // 收尾棒 J8 句柄（WAVE-MERGE11-TAIL 登记）：次钮由直传 onCancel 改为「onCancel(); dismiss()」
        // 配对形态，等严改钉——仍逐字钉次钮文案与 onCancel 被调用，并新增关闭句柄配对在场要求。
        assertTrue(shared.contains("secondary = \"取消\", onSecondary = { onCancel(); dismiss() }"))
        assertTrue(shared.contains("primary = confirmLabel"))
        assertTrue(shared.contains("onCancel = onCancel"))
        assertFalse(shared.contains("AlertDialog("))
        assertTrue(dialog.contains("userBundle.addOrReplacePlaylist(np)"))
        assertTrue(dialog.contains("persistBundle()"))
        assertFalse(dialog.contains("Color.White"))
        assertFalse(dialog.contains("Color.Black"))
    }

    @Test
    fun urlScriptConfirmationKeepsSecurityWarningAndThemeAwareActions() {
        val source = mainActivitySource()
        val dialog = source.substringAfter("pendingUrlScript?.let { fetched ->")
            .substringBefore("if (showNewPlDlg) {")

        // 对话框清扫（SW-12-08）：内联 AlertDialog 白框迁统一确认弹层 DesignConfirmSheet。
        // 安全不变量逐字不变：来源域名 + SHA-256 展示、风险文案、确认后才调 importScript；
        // 新增 busy 门控钉（导入中两键禁用 + 不可关）严于旧形态；主题感知下沉到共用组件逐项钉住。
        // 唯一呈现收敛：旧形态正文风险段的 error 色随家族正文样式统一（文案本体不动，见 WAVE-DIALOGS）。
        assertTrue(dialog.contains("DesignConfirmSheet("))
        assertTrue(dialog.contains("title = \"确认导入脚本\""))
        assertTrue(dialog.contains("fetched.host.ifBlank"))
        assertTrue(dialog.contains("fetched.sha256.ifBlank"))
        assertTrue(dialog.contains("第三方脚本可访问网络并执行其代码，仅从可信来源导入。"))
        assertTrue(dialog.contains("confirmLabel = if (urlScriptConfirming) \"导入中…\" else \"确认导入\""))
        assertTrue(dialog.contains("buttonsEnabled = !urlScriptConfirming"))
        // ACCEPT5（退出动画普查 M5）：完成点改 closeSignal 触发退场、onDismiss 退场后仍清
        // pendingUrlScript（不变量不变），并复位信号——钉子按新字面等严改钉。
        assertTrue(dialog.contains("onDismiss = { pendingUrlScript = null; urlScriptCloseSignal = false }"))
        assertTrue(dialog.contains("closeSignal = urlScriptCloseSignal"))
        assertTrue(dialog.contains("ScriptStorage.importScript(sprefs, mm, fetched.code)"))
        assertFalse(dialog.contains("AlertDialog("))
        assertFalse(dialog.contains("Color.White"))
        assertFalse(dialog.contains("Color.Black"))

        val sheet = File("src/main/java/xyz/cncnlp/soundwave/ui/common/DesignConfirmSheet.kt")
            .let { if (it.isFile) it else File("app/${it.path}") }.readText()
        assertTrue(sheet.contains("DesignSheetDialog(onDismiss = onDismiss"))
        assertTrue(sheet.contains("color = colors.onSurface.copy(alpha = 0.78f)"))
        assertTrue(sheet.contains(".border(1.dp, designLineColor(), shape)"))
        // dialog-polish 波（用户「整体对话框样式完善」）：非危险主钮由墨色对齐设计稿珊瑚口径；
        // 等严改钉：精确字面改为珊瑚形态，危险主钮仍唯一 SoundwaveDanger（同行钉住）。
        assertTrue(sheet.contains(".background(if (danger) SoundwaveDanger else SoundwaveDesignPalette.Accent)"))
        assertFalse(sheet.contains("AlertDialog("))
    }

    private fun mainActivitySource(): String {
        val relativePath = "src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate MainActivity.kt for the main dialog theme guard")
    }
}
