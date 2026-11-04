#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
settings = (base / "SettingsHub.kt").read_text(encoding="utf-8")
page = (base / "DownloadsPage.kt").read_text(encoding="utf-8")
me = (base / "MePage.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[download-manager-theme-guard] FAIL: {message}")


# 1.0.656 (user item 9): 查看下载 page redesigned into DownloadsPage.kt; the settings hub only routes to it.
require('"downloads" -> DownloadsPage(' in settings, "downloads route must render the redesigned DownloadsPage")
for cb in ("onMoveDownload", "onPauseDownload", "onResumeDownload", "onDeleteDownloads", "onDeleteDownloadRecords",
           "onPauseDownloads", "onResumeDownloads", "onRedownloads", "onPlayDownload", "onOpenDownloadDir",
           "onPickDownloadDir", "onClearDownloadHistory", "onRedownload"):
    require(f"{cb} = {cb}" in settings, f"download callback not wired: {cb}")
# 查看下载 entry lives only on the settings root, not inside 下载设置 (item 9).
require('RowCard("查看下载"' not in me and 'NavRow("查看下载"' in me, "查看下载 must be removed from 下载设置 and kept on the settings root")
# Preserved behaviour: status copy, real quality/size, failure grouping, batch actions, queue reorder of unfinished tasks only.
for token in ('DownloadTaskState.QUEUED -> "等待下载…"', 'DownloadTaskState.RESOLVING -> "取链中…"',
              'DownloadTaskState.DOWNLOADING -> "下载中 ${(d.progress * 100).toInt()}%"',
              'AudioQualityEvidence.measuredFileQuality(d.path, d.size, d.song.duration)', 'LocalStore.groupDownloadFailures(downloads)',
              'onRedownloads(group)', 'contentDescription = "下载歌曲封面"', 'BackHandler(enabled = batchMode)',
              'if (!batchMode && d.state() != DownloadTaskState.COMPLETED)', '"上移下载"', '"下移下载"', '"暂停下载"', '"继续下载"', '"取消下载"'):
    require(token in page, f"download behaviour/label missing: {token}")
# Dialogs use the unified design sheet; delete offers file+record vs record-only; clear-history is reachable.
require(page.count("DesignSheetDialog(") >= 3 and "AlertDialog(" not in page, "download dialogs must use the unified DesignSheetDialog")
require('"删除文件与记录"' in page and '"仅删除记录"' in page and 'onClearDownloadHistory(true)' in page and 'onClearDownloadHistory(false)' in page,
        "delete scope choices and clear-history choices must remain")
require("Color.Black" not in page, "download page must not hard-code black text (dark theme)")
require('.heightIn(min = 36.dp)' in page or 'Modifier.size(36.dp)' in page, "download actions must keep comfortable touch targets")

about_page = (root / "app/src/main/java/xyz/cncnlp/soundwave/SettingsSupportPages.kt").read_text(encoding="utf-8")
about_action = (base / "AboutUpdateAction.kt").read_text(encoding="utf-8")
require('AboutUpdateAction(updateStatus = updateStatus, onCheckUpdate = onCheckUpdate)' in about_page and
        'Text(if (checking) "检查中…" else "检查更新", color = Color.White' in about_action,
        "About-page action button should keep the APK-era white label")
require("scripts/download-manager-theme-guard.sh" in workflow,
        "the download-manager visual and behavior guard must remain wired into the manual quality gates")
print("[download-manager-theme-guard] PASS: redesigned DownloadsPage, preserved flows/labels, unified sheets, 查看下载 only on settings root")
PY
