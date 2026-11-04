#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
source = (base / "ExtraPages.kt").read_text(encoding="utf-8")
sheet = (base / "ui/settings/WebDavLoginSheet.kt").read_text(encoding="utf-8")
page_start = source.index("@Composable\nfun BackupRestorePage(")
helper_start = source.index("@Composable\ninternal fun BackupActionRow(", page_start)
helper_end = source.index("\n@Composable\nprivate fun SwipeToDeleteHistoryRow(", helper_start)
page = source[page_start:helper_start]
helper = source[helper_start:helper_end]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[backup-restore-actions-theme-guard] FAIL: {message}")


# r656 item 13：备份页按设计稿重做——操作行改为设置详情行语言（单色线性图标 + chevron + 1dp 线），
# 不再要求 Material 图标与分色；仍要求每个动作图标唯一、回调不变、整行可点。
expected = {
    "导出备份": ("SettingsPageGlyphs.Export", "onClick = { export() }"),
    "导入备份": ("SettingsPageGlyphs.Import", 'onClick = { pick.launch("application/json") }'),
    "WebDAV 登录同步": ("SettingsPageGlyphs.Server", "onClick = { showWebDavSheet = true }"),
}
rows = re.findall(r"BackupActionRow\((.*?)\n\s+\)", page, flags=re.S)
require(len(rows) == 3, "backup page must retain its three actions (export, import, WebDAV)")
for title, (icon, callback) in expected.items():
    row = next((row for row in rows if f'title = "{title}"' in row), None)
    require(row is not None, f"action row missing: {title}")
    require(icon in row and callback in row,
            f"{title} must keep its unique line icon and existing callback")
icons = [icon for icon, _ in expected.values()]
require(len(icons) == len(set(icons)), "all left-side action icons must be unique")

require(".fillMaxWidth()\n                .heightIn(min = 64.dp)" in helper,
        "each button must retain full-row width and a comfortable (>=48dp) touch height")
require("settingsPressFeedback(interaction)" in helper and
        "indication = null" in helper and
        "role = Role.Button" in helper and
        "ripple(" not in helper,
        "actions must expose button semantics and use the shared settings press tint (no Material ripple)")
require(".background(" not in helper and "Surface(" not in helper and
        all(token not in helper for token in ("Color.Black", "Color.White", "Color(0x")),
        "action rows must remain transparent and use no fixed light/dark or random colors")
require("MaterialTheme.colorScheme.onSurface" in helper and
        "MaterialTheme.colorScheme.onSurfaceVariant" in helper,
        "action title and description must use theme-adaptive text roles")

for copy in (
    "本机资料独立 v2 文件；自动同步需单独开启，仅本次应用会话有效；重启后需重新开启",
):
    require(copy in page, f"existing action description must remain unchanged: {copy}")
require('SettingsDetailHero("同步与数据", "备份与恢复"' in page,
        "the backup page must open with the settings-detail design hero (ACCEPT5 regroup: hero kicker follows the renamed group)")
require('val file = withContext(Dispatchers.IO)' in page and "BackupStore.export(ctx)" in page and
        'Intent.createChooser(send, "导出备份")' in page,
        "export must preserve the existing local backup and share flow")
# r657：预览回调抽成 previewBackup，供“恢复备份”选择器与外部打开的备份 JSON 共用。
picker_start = page.index("val previewBackup: (Uri?) -> Unit = previewBackup@{")
export_start = page.index("fun export()", picker_start)
picker = page[picker_start:export_start]
require("BackupStore.preview(root)" in picker and "pendingBackupImport = pending" in picker,
        "the selected JSON must be validated and previewed before a confirmation is shown")
require("BackupStore.restore(" not in picker,
        "selecting a JSON file must never restore or mutate local data")
confirm_start = page.index("pendingBackupImport?.let { pending ->")
webdav_start = page.index("if (showWebDavSheet)", confirm_start)
confirm = page[confirm_start:webdav_start]
restore_call = "BackupStore.restore(ctx, pending.root)"
require(confirm.count(restore_call) == 1 and confirm.index("confirmButton =") < confirm.index(restore_call),
        "the restore call must exist exactly once, inside the explicit confirmation button")
require(confirm.index(restore_call) < confirm.index('Text("恢复并替换")'),
        "the destructive action must be labeled clearly as 恢复并替换")
dismiss_start = confirm.index("dismissButton =")
require("pendingBackupImport = null" in confirm[dismiss_start:] and
        "BackupStore.restore(" not in confirm[dismiss_start:],
        "canceling confirmation must only discard the pending import")
require("onDismissRequest = { pendingBackupImport = null }" in confirm and
        "BackupStore.restore(" not in confirm.split("onDismissRequest =", 1)[1].split("confirmButton =", 1)[0],
        "dismissing confirmation must not restore or write any state")
for copy in (
    "现有收藏、歌单等用户数据将被备份内容替换",
    "偏好设置和用户脚本配置会按备份内容更新",
    "备份不包含已下载音频文件",
    "仅恢复本机已有脚本的顺序与启用配置",
    "不包含或恢复脚本代码",
    "WebDAV 凭据与诊断上传授权保留本机值",
):
    require(copy in confirm, f"confirmation must clearly disclose: {copy}")
require("onRestored(bundle)" in confirm and 'pick.launch("application/json")' in page,
        "import must preserve the existing JSON picker and post-restore callback flow")
require("WebDavSyncOverlay(" in page and "onBundleApplied = onWebDavBundleApplied" in page and
        "onDismiss = { showWebDavSheet = false }" in page,
        "WebDAV must keep using its existing login/sync panel and apply callback")

sensitive_terms = ("password", "credential", "secret", "token")
require(not any(term in (page + helper).lower() for term in sensitive_terms),
        "backup action rows and visible page copy must not expose credential fields")
require("PasswordVisualTransformation()" in sheet,
        "the WebDAV login password input must remain visually masked")

print("[backup-restore-actions-theme-guard] PASS: three unique line-icon actions (export, staged import, WebDAV), transparent ripple rows, retained callbacks, design hero, and masked credentials")
PY
