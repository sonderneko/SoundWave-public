#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
source_path = root / "app/src/main/java/xyz/cncnlp/soundwave/migration/LegacyUnassignedSnapshot.kt"
application_path = root / "app/src/main/java/xyz/cncnlp/soundwave/CrashGuard.kt"
controller_path = root / "app/src/main/java/xyz/cncnlp/soundwave/CloudSyncController.kt"
activity_path = root / "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
store_path = root / "app/src/main/java/xyz/cncnlp/soundwave/LocalStore.kt"
copy_ui_path = root / "app/src/main/java/xyz/cncnlp/soundwave/LocalProfileBackupImportAction.kt"
copy_domains_path = root / "app/src/main/java/xyz/cncnlp/soundwave/SelectedDomainCopy.kt"
autosync_path = root / "app/src/main/java/xyz/cncnlp/soundwave/AccountWebDavAutoSync.kt"
source = source_path.read_text(encoding="utf-8")
application = application_path.read_text(encoding="utf-8")
controller = controller_path.read_text(encoding="utf-8")
activity = activity_path.read_text(encoding="utf-8")
store = store_path.read_text(encoding="utf-8")
copy_ui = copy_ui_path.read_text(encoding="utf-8")
copy_domains = copy_domains_path.read_text(encoding="utf-8")
autosync = autosync_path.read_text(encoding="utf-8")
forbidden = (
    "ApiClient", "CloudSyncController", "WebDav", "OkHttp", "HttpURLConnection",
    "java.net", "URL(", "LocalStore.save", "UserDataSaveClient", "CoroutineScope",
)
found = [token for token in forbidden if token in source]
if found:
    raise SystemExit(f"FAIL: local legacy snapshot must have no network/sync path: {found}")
required = (
    'SOURCE_PREFERENCES = "soundwave_userdata"',
    'USER_DATA_KEY = "user_data_v1"',
    'PLAY_HISTORY_KEY = "play_history_v1"',
    'SNAPSHOT_KIND = "legacy-unassigned"',
    '.commit()',
    'storage.readSnapshot()',
    'SYNC_GATE_STATE_KEY = "account_data_sync_gate_v1"',
    'fun accountSyncGateState(context: Context)',
    'capturePersistenceUnavailable = !persisted',
)
for token in required:
    if token not in source:
        raise SystemExit(f"FAIL: snapshot/gate contract is missing: {token}")
call = "LegacyUnassignedSnapshot.capture(this)"
if application.count(call) != 1:
    raise SystemExit("FAIL: Application must invoke the snapshot copier exactly once")
startup = application[application.index("override fun onCreate()") :]
call_index = startup.index(call)
# CrashGuard.flushPending was retired from this ordering list when the crash
# upload machinery was deleted (user ruling 2026-11-04, deletion contract):
# crash reports are on-device only, so there is no startup upload step left.
for token in (
    "StartupTempFileCleaner.cleanup(", "CrashGuard.install(this)",
    "LegacyPlatformAuthCleanup.clearLegacyThirdPartyMusicAuth(this)",
    "SmartSourceStore.init(this)",
):
    if startup.index(token) < call_index:
        raise SystemExit(f"FAIL: snapshot must run before startup operation: {token}")
# Account-removal wave: the SoundWave account sync gate (canSyncAccountData,
# consent key, mergeCloudUser, authenticated login apply, gated uploads) was
# deleted wholesale. Its protected-path checks are retired and replaced by
# absence pins; the LegacyUnassignedSnapshot provenance contract above and
# the local-backup copy-domain checks below are retained unchanged.
for token in (
    "canSyncAccountData", "syncAccountDataExplicitly", "ACCOUNT_SYNC_CONSENT_USER_KEY",
    "fun mergeCloudUser(", "applyAuthenticatedLoginCloudUser", "prepareAuthenticatedAccountSession",
    "saveUserDataRequest(", "syncFromSettings",
):
    if token in controller or token in activity:
        raise SystemExit(f"FAIL: retired account sync gate reappeared: {token}")
if "ApiClient.loadUserData(" in activity or "ApiClient.saveUserData(" in activity:
    raise SystemExit("FAIL: MainActivity must not bypass the gated account data controller")
# The WebDAV binding keeps the data-provenance gate (fail-closed for
# unassigned legacy snapshots); it now lives in AccountWebDavAutoSync.
if "LegacyUnassignedSnapshot.canExplicitlyEnableAccountSync" not in autosync:
    raise SystemExit("FAIL: WebDAV account-data binding must keep the provenance gate")
avatar_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/AvatarStoreReadOnlyPathTest.kt").read_text(encoding="utf-8")
if "authenticatedAvatarRestorePublishesNewFileAndClearsTombstoneWithoutOverwritingStaleBytes" not in avatar_test:
    raise SystemExit("FAIL: account-specific avatar restore regression test is missing")
if (
    "showLegacyCopyOptions" not in copy_ui
    or "domain.displayName" not in copy_ui
    or "domain.name}" in copy_ui
    or "附加本机隔离副本" in copy_ui
    or any(label not in copy_domains for label in ("播放与界面设置", "收听统计", "不喜欢的歌曲", "脚本数据", "音源偏好", "列表排序", "列表位置"))
):
    raise SystemExit("FAIL: optional legacy-copy domains must be collapsed and described in plain Chinese")
print("Legacy unassigned snapshot guard passed: snapshot stays local-only and network-free; the account sync gate is retired (absence pinned); the WebDAV binding keeps the provenance gate; legacy copy domains stay collapsed and plainly described.")
PY
