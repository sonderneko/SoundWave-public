#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
models = (base / "Models.kt").read_text()
local_store = (base / "LocalStore.kt").read_text()
merge = (base / "sync/CloudUserMerge.kt").read_text()
order_sync = (base / "PlaylistOrderSync.kt").read_text()
main = (base / "MainActivity.kt").read_text()
library = (base / "LibraryPage.kt").read_text()
design_row = (base / "LibraryDesignRow.kt").read_text()
tests = (root / "app/src/test/java/xyz/cncnlp/soundwave/PlaylistOrderSyncTest.kt").read_text()
version = (root / "VERSION").read_text().strip()
gradle = (root / "app/build.gradle").read_text()

start = main.find("fun reorderLibraryPlaylists(order: List<String>)")
end = main.find("    LaunchedEffect(avatarNeedSync, avatarPath)", start)
helper = main[start:end] if start >= 0 and end > start else ""
checks = {
    "playlist order metadata lives on each model, not in a new account-level field": (
        "val syncOrder: Int? = null" in models and "val syncOrderAt: Long = 0L" in models
        and 'put("_swLibOrder", pl.syncOrder)' in local_store
        and 'put("_swLibOrderAt", pl.syncOrderAt)' in local_store
        and 'syncOrder = o.optInt("_swLibOrder", -1)' in local_store
        and "put(\"libOrder\"" not in local_store
    ),
    "merge selects the newest per-playlist order revision with deterministic local tie-break": (
        "mergeOrderMetadata(c, lp)" in merge
        and "syncOrder = order.first" in merge
        and "syncOrderAt = order.second" in merge
        and "local.syncOrderAt > cloud.syncOrderAt" in merge
    ),
    "legacy preferences and missing/deleted/new entries resolve stably": (
        "legacyOrder.forEach" in order_sync
        and "filterNot { it in orderedIds }" in order_sync
        and 'playlist.id.startsWith("reco_")' in order_sync
    ),
    "only two completed reorder callbacks use one guarded save path": (
        main.count("onReorderPlaylists = { order ->") == 2
        and main.count("reorderLibraryPlaylists(order)") == 2
        and "if (restoredOrder == previousOrder) return" in helper
        and helper.find("if (restoredOrder == previousOrder) return") < helper.find("persistBundle()")
        # ACCEPT5（用户 2026-11-04 删除「排序歌单」弹层，守卫随删除契约重钉，严格度不降）：
        # 排序改整行长按拖动（行组件手势在场、旧弹层消失），松手经同一 onReorderPlaylists
        # 回调落盘；草稿拼回完整顺序后 merged != latestOrderIds 才保存（无变化不落盘），
        # payload 仍 filterNot liked/local（收藏不参与在线排序），与旧弹层调用方完全一致。
        and "detectDragGesturesAfterLongPress" in design_row
        and "LibraryPlaylistSortSheet" not in library
        and "librarySpliceReorder(latestOrderIds, draft.toSet(), draft)" in library
        and "if (merged != latestOrderIds)" in library
        and 'onReorderPlaylists(merged.filterNot { it == "liked" || it == "local" })' in library
    ),
    "tests cover serialization, cloud restoration, legacy fallback, hidden/deleted entries, and new playlists": all(
        name in tests for name in (
            "reorderMetadataRoundTripsInsidePlaylistElementsAndRestoresAfterCloudMerge",
            "legacyCloudWithoutOrderFieldsUsesFilteredLocalPreferenceOrder",
            "newerCloudOrderRevisionWinsOnLoginMerge",
            "hiddenAndDeletedEntriesDoNotBreakOrderOrLoseTombstones",
            "newPlaylistsAppendStablyUntilTheNextExplicitReorder",
        )
    ),
    "VERSION and Gradle declarations stay synchronized": (
        f'versionName "{version}"' in gradle
        and re.search(r"versionCode\s+" + re.escape(version.rsplit('.', 1)[-1]) + r"\b", gradle) is not None
    ),
}
for label, ok in checks.items():
    print(f"[playlist-order-cloud-sync-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
