#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
page = (base / "SmartSourcePage.kt").read_text(encoding="utf-8")
store = (base / "SmartSourceStore.kt").read_text(encoding="utf-8")
model = (base / "SmartSourcePresentation.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/SmartSourcePresentationTest.kt").read_text(encoding="utf-8")
chain_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/SmartSourcePlatformChainOrderTest.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[smart-source-page-redesign-guard] FAIL: {message}")


# 1.0.656 (user item 8): the detection-report area, 激活脚本 and 跨脚本备用顺序 are removed from the page.
for gone in ('"最近检测结果"', '"自动选择门槛"', '"激活脚本"', '"跨脚本备用顺序"', 'SmartSourceStore.setActiveScriptId', 'SmartSourceStore.saveBackupOrder'):
    require(gone not in page, f"removed smart-source section returned: {gone}")
require('"按平台脚本链路"' in page and "SmartSourcePlatformChainCard(" in page and "SmartSourceChainSheet(" in page,
        "per-platform script chain area must be the redesigned card + chain sheet")
# Resolution no longer depends on active/backup: order derives from per-platform chains + platform order + script page order.
ordered = store[store.index("fun orderedScripts("):store.index("internal fun orderScriptsByPlatformChains(")]
require("activeScriptId" not in ordered and "backupOrder" not in ordered and "orderScriptsByPlatformChains(" in ordered
        and "enabledPlatformsInOrder()" in ordered, "orderedScripts must derive only from platform chains")
require("sourceChainFirstThenOtherPlatformChainsThenScriptPageOrder" in chain_test, "chain-derived order regression test missing")

# Existing settings mutations stay wired; no probes are started from this page.
require("onAutoConfigure(); refresh()" in page and "onReResolveNow" in page and "probeScript" not in page and "onProbe" not in page,
        "one-click/re-resolve must remain without page-level network probes")
for token in (
    "SmartSourceStore.setSmartEnabled(it)", "SmartSourceStore.setFallback128k(it)",
    "SmartSourceStore.setRememberCrossPlat(it)", "SmartSourceStore.savePlatformOrder(plats)",
    "SmartSourceStore.platformScriptOrder(platform.code)", "SmartSourceStore.moveStage(id, -1)",
    "SmartSourceStore.savePlatformScriptOrder(platform, next)", "SmartSourceStore.clearRemembered()",
):
    require(token in page, f"existing setting mutation/callback is missing: {token}")
require('"清空记忆"' in page and '"立即重新解析"' in page and "SmartSourcePillButton(" in page,
        "换源记忆 actions must use the redesigned pill buttons")
# Quality sheet: unified design sheet, raw values persisted unchanged.
require("DesignSheetDialog(" in page and "AlertDialog(" not in page, "dialogs must use the unified design sheet")
require("selection.persist(platform, SmartSourceStore::setPlatformPlayQuality, SmartSourceStore::setPlatformDownloadQuality)" in page
        and "writePlayback(platform, playbackQuality)" in model and "writeDownload(platform, downloadQuality)" in model,
        "each selected playback/download value must be written unchanged to its original store setter")
for token in ("snapshotWithoutSavedReportShowsNoInventedAttemptsOrCapabilities",
              "snapshotUsesOnlyCacheValidatedSuccessesForQualityAndMeasuredLatency",
              "staleReportRemainsMarkedHistoricalRatherThanBeingPresentedAsCurrent",
              "qualityDialogPersistsExactlyTheTwoSelectedValues"):
    require(token in test, f"snapshot/modal regression is missing: {token}")
require("MaterialTheme.colorScheme.onSurfaceVariant" in page and "HorizontalDivider(" in page,
        "page must use theme-semantic colors and compact separators")
print("[smart-source-page-redesign-guard] PASS: report/active/backup sections removed, chain-derived ordering, redesigned platform chains, unchanged mutations, unified quality sheet")
PY
