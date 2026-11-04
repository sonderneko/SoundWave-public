#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
local_store = (root / "app/src/main/java/xyz/cncnlp/soundwave/LocalStore.kt").read_text()
merge = (root / "app/src/main/java/xyz/cncnlp/soundwave/sync/CloudUserMerge.kt").read_text()
controller = (root / "app/src/main/java/xyz/cncnlp/soundwave/CloudSyncController.kt").read_text()
main = (root / "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt").read_text()
api = (root / "app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt").read_text()
tests = (root / "app/src/test/java/xyz/cncnlp/soundwave/CloudUserMergeTest.kt").read_text()
gradle = (root / "app/build.gradle").read_text()
version = (root / "VERSION").read_text().strip()

checks = {
    "favorite tombstones are serialized inside favs elements": "favArr.put(JSONObject().apply { putTimeMap(this, CLOUD_DELETED_FAVORITES, deletedFavoriteKeys) })" in local_store,
    "playlist and playlist-song tombstones are serialized inside pls elements": "plsArr.put(JSONObject().apply {" in local_store and "putTimeMap(this, CLOUD_DELETED_PLAYLISTS, deletedPlaylistIds)" in local_store and "put(CLOUD_DELETED_PLAYLIST_SONGS" in local_store,
    "play-history deletion tombstones round-trip in the history cloud projection": "putTimeMap(this, CLOUD_DELETED_HISTORY, historyDeletionTombstones)" in local_store and "data.optJSONObject(CLOUD_DELETED_HISTORY)" in local_store,
    "legacy input reads missing tombstone fields as empty maps": "CLOUD_DELETED_FAVORITES" in local_store and "CLOUD_DELETED_PLAYLIST_SONGS" in local_store and "optLong(\"_swAddedAt\", 0L)" in (root / "app/src/main/java/xyz/cncnlp/soundwave/Models.kt").read_text(),
    "local controller applies WebDAV merges through the shared merge with tombstone-aware history reconciliation": (
        "CloudUserMerge.merge(currentBundle, incoming.copy(" in controller
        and "WebDavHistoryReconciler.reconcile" in controller
        and "deletedHistoryEventKeys = merged.historyDeletionTombstones.keys" in controller
        and "fun applyWebDavBundleLocally(" in controller
    ),
    "history swipe deletes create tombstones and WebDAV merge observes them": "deleteHistoryRecords(listOf(s))" in main and "deletedHistoryEventKeys = merged.historyDeletionTombstones.keys" in controller,
    "account cloud merge entry points are retired and never return": "mergeCloudUser" not in main and "/api/user" not in main and "saveUserData" not in controller,
    "old duplicate count-based startup merge is absent": "if (lp.songs.size >= c.songs.size)" not in main and "cloud.favs + local.favs" not in main,
    "regression tests cover delete, additions, legacy JSON, and history tombstone round-trip": all(name in tests for name in ("deletedFavoriteDoesNotReturnFromOldCloudSnapshot", "ordinaryFavoriteAdditionsFromBothDevicesArePreserved", "deletedPlaylistSongIsNotBackfilledFromLargerCloudSnapshot", "legacyJsonWithoutTombstonesLoadsNormally", "tombstonesRoundTripInsideAcceptedArrayElements", "singleHistoryDeletionWinsAcrossDevicesAndSurvivesCloudJsonRoundTrip")),
    "VERSION and Gradle declarations stay synchronized": f'versionName "{version}"' in gradle and re.search(r"versionCode\s+" + re.escape(version.rsplit('.', 1)[-1]) + r"\b", gradle) is not None,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[cloud-sync-tombstone-guard] {'PASS' if ok else 'FAIL'}: {name}")
if failed:
    raise SystemExit(1)
PY
