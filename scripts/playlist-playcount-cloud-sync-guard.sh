#!/usr/bin/env bash
set -euo pipefail
# 2026-11-05 删除合同重钉（public-cleanup）：LibraryQuickPager.kt 已删，quick 文本退出 userPlayCount 缺失断言的文本集；其余断言逐字不变。
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
main = (base / "MainActivity.kt").read_text()
controller = (base / "CloudSyncController.kt").read_text()
local_store = (base / "LocalStore.kt").read_text()
models = (base / "Models.kt").read_text()
merge = (base / "sync/CloudUserMerge.kt").read_text()
webdav = (base / "sync/WebDavSync.kt").read_text()
list_page = (base / "LibraryPage.kt").read_text()
row = (base / "LibPage.kt").read_text()
legacy_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/CloudUserMergeTest.kt").read_text()
webdav_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/WebDavSyncTest.kt").read_text()
song_stats_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/CloudSyncControllerPlayCountConsentTest.kt").read_text()
stats_retention_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ListenStatsProfileTest.kt").read_text()
history_policy = (base / "PlaybackHistoryPolicy.kt").read_text()
webdav_apply = controller.split("fun applyWebDavBundleLocally(", 1)[1]
version = (root / "VERSION").read_text().strip()
gradle = (root / "app/build.gradle").read_text()
checks = {
    "personal playlist counts and their increment/sync helpers are removed": (
        all("userPlayCount" not in text for text in (models, main, controller, local_store, merge, webdav, list_page, row))
        and not (base / "playback/PlaylistPlayCountSyncPolicy.kt").exists()
        and not (base / "playback/PlaylistPlaybackCountPolicy.kt").exists()
        and not (root / "app/src/test/java/xyz/cncnlp/soundwave/playback/PlaylistPlaybackCountPolicyTest.kt").exists()
        and "activePlaylistPlaybackId" not in main
        and "sourcePlaylistId" not in main
        and "oldPersonalPlaylistPlayCountIsIgnoredAndNeverReemitted" in legacy_test
        and 'assertFalse(mix.has("userPlayCount"))' in webdav_test
    ),
    "device-local playlists sync cover only, never tracks or a derived track count": (
        'pl.id == "liked" || pl.id == "local"' in local_store
        and 'if (pl.id == "local") 0 else' in local_store
        and "deviceLocalPlaylistSyncsItsCoverButNeitherTracksNorTrackCount" in legacy_test
    ),
    "platform playlist popularity remains a distinct supported field": (
        'put("playCount", pl.playCount)' in local_store
        and 'playCount = o.optLong("playCount", 0L)' in local_store
        and 'playCount = maxOf(c.playCount, lp.playCount)' in merge
        and "loginMergePreservesPlatformPlaylistPopularity" in legacy_test
    ),
    "dated per-song statistics sync while legacy undated counters stay local and history remains bounded": (
        'put("dailyListenStats"' in local_store
        and "ListenStats.accountDailyListenStatsForAccount(" in webdav_apply
        and controller.count("ListenStats.mergeAccountDailyListenStatsForAccount(context, user, merged.dailyListenStatsJson)") == 1
        and "ListenStats.mergeAccountDailyListenStatsForAccount(context, user, merged.dailyListenStatsJson)" in webdav_apply
        and "includeDailyListenStats = true" in webdav
        and "ListenStats.accountPlayCountCounters(context)" not in controller
        and "mergeAccountPlayCountCounters(" not in controller
        and "includePlayCountCounters = true" not in webdav
        and "webDavApplyIgnoresGuestStatsAndUndatedLegacyCounts" in song_stats_test
        and "legacyUndatedPlayCountsMigrateLocallyAndStayOutOfCloudProjection" in stats_retention_test
        and "PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT" in webdav
        and "retainForCloudSync(history, limit = historyLimit)" in local_store
        and "CLOUD_RETENTION_MS" in history_policy
        and 'assertFalse(remoteUser.has("playCountCounters"))' in webdav_test
    ),
    "legacy JSON personal counts are ignored and never re-emitted": (
        '.put("userPlayCount", 12L)' in legacy_test
        and 'assertFalse(encodedPlaylist.has("userPlayCount"))' in legacy_test
    ),
    "version declarations remain synchronized at the current release": (
        f'versionName "{version}"' in gradle
        and re.search(r"versionCode\s+" + re.escape(version.rsplit('.', 1)[-1]) + r"\b", gradle) is not None
    ),
}
for label, ok in checks.items():
    print(f"[playlist-playcount-cloud-sync-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
