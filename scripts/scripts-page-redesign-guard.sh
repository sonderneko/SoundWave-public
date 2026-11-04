#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
page = (root / "app/src/main/java/xyz/cncnlp/soundwave/ScriptsPage.kt").read_text(encoding="utf-8")
parts = (root / "app/src/main/java/xyz/cncnlp/soundwave/ScriptsPageParts.kt").read_text(encoding="utf-8")
schedule = (root / "app/src/main/java/xyz/cncnlp/soundwave/ScriptIntervalSheet.kt").read_text(encoding="utf-8")
scheduler = (root / "app/src/main/java/xyz/cncnlp/soundwave/ScriptProbeScheduler.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[scripts-page-redesign-guard] FAIL: {message}")


# Sorting keeps both the current-mode apply action and the next-mode cycle behavior.
require('onSmartSort(sortMode)' in page and 'sortMode = nextMode' in page and 'onSmartSort(nextMode)' in page,
        "both sorting actions and the mode cycle must remain wired")
require('onRefresh({ frac ->' in page and 'onProbe(sc) { report ->' in page,
        "batch and single-script detection callbacks must remain reachable")
require('detectDragGesturesAfterLongPress' in page and 'val pitch = size.height + 10.dp.toPx()' in page
        and 'val thr = pitch * 0.27f' in page
        and 'onMove(sc.id, -1)' in page and 'onMove(sc.id, +1)' in page,
        "long-press continuous reordering must keep its pitch-ratio movement threshold (pitch x 0.27, density-independent)")

# Import entry points keep their existing local picker, URL dialog and code dialog callbacks.
# 1.0.656 (user item 9): import tiles redone as one strip; no duplicate 音源脚本 hero title; reorder arrows left of 检测.
require('ScriptImportStrip(onFile = onImportFile, onLink = { showImportUrl = true }, onCode = { showImportCode = true })' in page,
        "all three import entry points must remain available")
require('SettingsDetailHero("音源与搜索", "音源脚本"' not in page, "the duplicate 音源脚本 hero title must stay removed")
# 验收二轮 E7（用户裁定）：排序箭头整体去除，统一为长按整行拖拽（上方拖拽钉点即其契约）；
# 原「箭头须在检测左侧」的形态钉按等严口径改为负向钉：箭头组件不得回潮。
require('ScriptReorderButtons(' not in page and 'ScriptReorderButtons' not in parts,
        "reorder arrows must stay removed; long-press drag is the only reorder interaction")
require('ScriptScheduleCustomSheet(' in page and 'ScriptBatchSheet(' in page
        and 'ScriptCheckSheet(' in page and 'ScriptDetailSheet(' in page and parts.count('DesignSheetDialog(') >= 5,
        "batch/scheduled/check/detail/import dialogs must be unified design sheets")
require('DesignSheetDialog(onDismiss = onDismiss' in schedule
        and 'ScriptProbeScheduler.MAX_INTERVAL_MINUTES.toFloat()' in schedule
        and 'MAX_INTERVAL_MINUTES = 24 * 60' in scheduler
        and 'onSave(scheduled, value)' in schedule
        and 'ScriptProbeScheduler.normalizeIntervalMinutes(minutes)' in schedule
        and 'MAX 24H · 自定义' not in schedule and '自定义检测间隔' not in schedule,
        "active schedule must preserve unified sheet, slider through 1440, legacy value normalization, no custom entry, and save callback")
require('onImportCode(importCode)' in page and 'onImportUrl(importUrl.trim())' in page,
        "code and URL import callbacks must preserve their existing input handling")
require('if (!sc.enabled && sc.code.contains("http://"))' in page
        and 'F-06，仅提示不阻断' in page,
        "enabling a cleartext-endpoint script must retain the existing warning without blocking")

# Detection details remain cache-only and validity-aware; no redesign may trigger probes from smart sort.
require('SmartSourceScriptProbeSnapshot.load(script)' in parts
        and 'ScriptProbeCache.latestReport(script.id)' in parts
        and 'ScriptProbeCache.summaryLine(sc)' in page,
        "script status/details must continue using the existing probe cache and current-report check")
require('智能排序/一键配置只使用与当前脚本代码和运行时匹配的完整检测结果；旧版快速抽检记录不参与排序。' in parts,
        "the full-report-only sorting/configuration explanation must remain visible")

# Timer preferences and script state mutations stay on their established callbacks.
require('ScriptProbeScheduler.setIntervalMinutes(scriptContext, normalized)' in page
        and 'ScriptProbeScheduler.setEnabled(scriptContext, on)' in page,
        "scheduled detection settings must keep their current persistence calls")
require('onChange(scripts.map { if (it.id == sc.id) it.copy(enabled = !it.enabled) else it })' in page
        and 'onChange(scripts.filter { it.id != deleteId })' in page,
        "enable/disable and delete mutations must remain unchanged")

# The destructive confirmation uses the unified design confirm sheet (danger variant); copy and close timing stay intact.
# Guard revision 2026-11-03, explicitly approved by the user ("批准：删除脚本确认框改成弹层样式（守卫只改外观断言，行为断言保留）"):
# the previous revision pinned the legacy Material AlertDialog form token-by-token (an intentional exception at the
# time). Only the appearance/form spellings were re-anchored to the DesignConfirmSheet form; every behavior assertion
# (title/body copy, confirm/cancel labels, delete callback, dismissal clearing deleteId, close timing) is preserved.
delete_start = page.find('if (deleteId != null) {')
require(delete_start >= 0, "the existing delete confirmation dialog must remain present")
delete_dialog = page[delete_start:page.index('\n}\n', delete_start) + 3]
require('DesignConfirmSheet(' in delete_dialog
        and 'danger = true' in delete_dialog
        and 'AlertDialog(' not in delete_dialog
        and 'Color(0xFF2A5CAA.toInt())' not in delete_dialog,
        "the destructive confirmation must use the unified design confirm sheet in its danger variant, not the legacy AlertDialog or hard-coded old blue")
require('title = "删除脚本"' in delete_dialog
        and 'body = "确定删除该脚本？"' in delete_dialog
        and 'confirmLabel = "删除"' in delete_dialog
        and 'cancelLabel = "取消"' in delete_dialog
        # ACCEPT5（退出动画普查 T27）改钉：删除为同步动作，确认后须走退场动画（用户站规）——
        # onConfirm 只执行删除、deleteId 改由退场后的 onDismiss 清（下方钉不变、且成唯一清理点）；
        # 删除回调本体与文案/标签断言逐字保留，时序断言按新契约等严更新。
        and 'dismissOnConfirm = true' in delete_dialog
        and 'onChange(scripts.filter { it.id != deleteId })' in delete_dialog
        and 'onDismiss = { deleteId = null }' in delete_dialog,
        "delete warning text, confirmation/cancel flow, callback, and dismissal timing must remain unchanged")

# Visual guardrails: adaptive colors, accessible targets, no repeated filled cards or page-level blur.
# 1.0.655: the user asked for a brand-new 音源脚本 page (design ignored); IconButton / HorizontalDivider are no longer required,
# the page draws its own rounded design rows, but keeps adaptive colours and 48dp targets.
for token in ('MaterialTheme.colorScheme.onSurfaceVariant', 'MaterialTheme.colorScheme.error',
              '.heightIn(min = 48.dp)', 'Color.Transparent'):
    require(token in page, f"theme-aware compact page treatment missing: {token}")
require('.blur(' not in page and 'Color.Black' not in page,
        "the script page must not add frosted blur or a hard-coded delete tint")
require('.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))' not in page,
        "the import/action strip must not restore a large repeated filled background")
print("[scripts-page-redesign-guard] PASS: page-only actions, import/security behavior, cache-based test status, full-report semantics, and theme-aware accessible controls")
PY
