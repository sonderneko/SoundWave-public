package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation extraction must not move storage, playlist identity or pending-song ownership. */
class PlaylistDialogWiringTest {
    private fun source(file: String): String {
        val path = "src/main/java/xyz/cncnlp/soundwave/$file"
        return listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { it.isFile }.readText()
    }

    @Test fun creationRetainsDefaultNameDeduplicatedSongsAndPersistence() {
        val dialog = source("MainActivity.kt").substringAfter("if (showNewPlDlg) {")
            .substringBefore("// ===== 关于 APP 底部 =====")
        for (token in listOf(
            "distinctBy { it.key() }", "newPlName.trim().ifEmpty { \"新建歌单\" }",
            "source = \"local\"", "local = true", "songs = songs.toMutableList()",
            "userBundle.addOrReplacePlaylist(np)", "persistBundle()"
        )) assertTrue("Missing create behavior: $token", token in dialog)
    }

    @Test fun creationExplicitCancelClearsPendingSongsButOutsideDismissOnlyCloses() {
        val dialog = source("MainActivity.kt").substringAfter("if (showNewPlDlg) {")
            .substringBefore("// ===== 关于 APP 底部 =====")
        assertTrue("onDismiss = { showNewPlDlg = false }" in dialog)
        val cancel = dialog.substringAfter("onCancel = {")
        // 收尾棒 J8（WAVE-MERGE11-TAIL 登记）：可见性清除统一归 onDismiss（句柄退场后触发），
        // onCancel 只保留「弃置待加歌曲」的即时清除——区别语义不变且更严：新增「cancel 不得碰可见性」负钉。
        assertFalse("showNewPlDlg = false" in cancel)
        assertTrue("addSongTarget = null" in cancel)
        assertTrue("addSongBatch = emptyList()" in cancel)
    }

    @Test fun renameTrimsOnlyWritesNonblankAndAlwaysCloses() {
        val dialog = source("LibraryPage.kt").substringAfter("renamePl?.let { pl ->")
        assertTrue("RenamePlaylistDialog(" in dialog)
        assertTrue("onNameChange = { renameText = it }" in dialog)
        assertTrue("onDismiss = { renamePl = null }" in dialog)
        // 收尾棒 J8（WAVE-MERGE11-TAIL 登记）：onConfirm 尾部的 renamePl = null 清除移交 onDismiss
        // （组件确认后走关闭句柄、退场结束触发上方已钉的 onDismiss 字面）——「必关」性质仍由本测试钉住，
        // 钉点按新契约拆到动作行 + onDismiss 两处，合起来等严。
        assertTrue("val n = renameText.trim()\n                if (n.isNotEmpty()) onRenamePlaylist(pl, n)" in dialog)
        assertTrue("renameText = pl.name; renamePl = pl" in source("LibraryPage.kt"))
    }

    @Test fun importRetainsPrefillResetAndExistingAsyncRoute() {
        val dialog = source("MainActivity.kt").substringAfter("if (showOnlineImportDlg) OnlineImportDialog(")
            .substringBefore("// ===== 添加到歌单")
        assertTrue("initial = onlineImportPrefill" in dialog)
        assertTrue("showOnlineImportDlg = false; onlineImportPrefill = \"\"" in dialog)
        assertTrue("ApiClient.importPlaylist(raw)" in dialog)
        assertTrue("onlineImportLocalPlaylistId(raw, pl)" in dialog)
        assertTrue("persistBundle(notify = true)" in dialog)
    }

    @Test fun editorsUseSharedScrollableInsetsAndDoNotIntroduceAutomaticSubmit() {
        val dialog = source("ui/common/PlaylistDialogs.kt")
        assertTrue("scrollContent = true" in dialog)
        assertTrue("singleLine = true" in dialog)
        assertTrue("KeyboardActions(onDone = { keyboard?.hide() })" in dialog)
        assertTrue("Modifier.semantics { heading() }" in dialog)
        assertFalse(".imePadding()" in dialog)
        assertFalse(".height(" in dialog.substringAfter("OutlinedTextField(").substringBefore("SettingsSheetCopy("))
    }
}
