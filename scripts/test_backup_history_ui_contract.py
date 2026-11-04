"""Source-contract checks only; not Android/Compose execution or compile proof."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/xyz/cncnlp/soundwave"


def block_after(text, marker):
    """Read a balanced Kotlin block, ignoring comments and quoted strings."""
    start = text.index("{", text.index(marker))
    tokens = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', re.S)
    depth = 0
    for token in tokens.finditer(text, start):
        if token.group() == "{":
            depth += 1
        elif token.group() == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:token.start()]
    raise AssertionError(f"Unbalanced block: {marker}")


class BackupMigrationUiContract(unittest.TestCase):
    def test_low_frequency_actions_live_inside_default_collapsed_section(self):
        text = (SRC / "LocalProfileBackupImportAction.kt").read_text()
        self.assertIn('var showMigrationOptions by remember { mutableStateOf(false) }', text)
        self.assertIn("if (showMigrationOptions) {", text)
        section = block_after(text, "if (showMigrationOptions) {")
        self.assertIn("旧版资料迁移", text[:text.index("if (showMigrationOptions) {")])
        self.assertIn("picker.launch", section)
        self.assertIn("showLegacyCopyOptions = !showLegacyCopyOptions", section)
        self.assertIn("LegacyCopyMigration.preview", section)
        self.assertIn("普通 JSON 备份", section)
        self.assertIn("不覆盖", section)
        # Hiding the section must never hide an in-flight preview or its independent confirmation.
        self.assertNotIn("prepared?.let", section)
        self.assertIn("prepared?.let", text[text.index("status?.let"):])

    def test_import_routes_keep_distinct_validation_and_confirmation(self):
        text = (SRC / "LocalProfileBackupImportAction.kt").read_text()
        page = (SRC / "ExtraPages.kt").read_text()
        self.assertIn("input.use(LocalProfileBackupImporter::read)", text)
        self.assertIn("LegacyCopyMigration.preview(context, selectedDomains.toSet())", text)
        self.assertIn("LegacyCopyMigration.copyConfirmed(context, generated, selection)", text)
        self.assertIn("LocalStore.importLocalBackupToUnactivatedProfile(context, generated, pending)", text)
        self.assertIn('ProfileId.of("localbackup_${UUID.randomUUID()', text)
        self.assertIn("确认并创建新的未启用资料", text)
        self.assertIn("PendingBackupImport(root, BackupStore.preview(root))", page)
        self.assertIn('pick.launch("application/json")', page)
        self.assertIn("BackupStore.restore(ctx, pending.root)", page)
        for forbidden in ("activateProfile(", "switchProfile(", "BackupStore.restore("):
            self.assertNotIn(forbidden, text)


class HistoryInteractionContract(unittest.TestCase):
    def test_history_has_no_long_press_selection_or_haptics(self):
        page = (SRC / "ExtraPages.kt").read_text()
        history = page[page.index("fun PlayHistoryPage("):page.index("/** r657：原型播放历史顶部")]
        for removed in ("combinedClickable", "onLongClick", "multiMode", "selectedKeys", "showDelDlg", "BackHandler", "Haptic", "performHapticFeedback", "onDeleteBatch(sel"):
            self.assertNotIn(removed, history, f"History still exposes {removed}")
        self.assertIn(".clickable { onPlay(s) }", history)
        self.assertIn("enabled = true", history)
        self.assertIn("onDelete = { onDelete(s) }", history)
        self.assertIn("historySubtitle(s)", history)
        self.assertIn("time = clock(s.playedAt)", history)
        self.assertIn('PlaySourceScope("播放历史")', history)
        self.assertIn('"all" to "全部", "online" to "在线", "local" to "本地"', history)
        self.assertIn("showClearDlg = false; onClear()", history)
        self.assertIn("听歌统计、收藏与下载文件不受影响", history)
        # Leave the source-compatible callback in place for MainActivity's independently owned call.
        self.assertIn("onDeleteBatch: (List<Song>, Boolean) -> Unit", history)

    def test_shared_song_rows_still_support_long_press(self):
        row = (SRC / "SongRow.kt").read_text()
        self.assertIn("onLongClick", row)
        self.assertIn("combinedClickable", row)


if __name__ == "__main__":
    unittest.main()
