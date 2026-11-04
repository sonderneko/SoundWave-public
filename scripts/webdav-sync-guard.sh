#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
client = (base / "sync/WebDavSync.kt").read_text()
sheet = (base / "ui/settings/WebDavLoginSheet.kt").read_text()
shell = (base / "ui/common/DesignSheet.kt").read_text()
overlay = (base / "ui/settings/WebDavSyncOverlay.kt").read_text()
page = (base / "ExtraPages.kt").read_text()
hub = (root / "app/src/main/java/xyz/cncnlp/soundwave/SettingsHub.kt").read_text()
main = (base / "MainActivity.kt").read_text()
controller = (base / "CloudSyncController.kt").read_text()
tests = (root / "app/src/test/java/xyz/cncnlp/soundwave/WebDavSyncTest.kt").read_text()
tests += (root / "app/src/test/java/xyz/cncnlp/soundwave/WebDavServerCompatTest.kt").read_text()
tests += (root / "app/src/test/java/xyz/cncnlp/soundwave/WebDavProfileDiscoveryAndHttpTest.kt").read_text()
policy = (base / "sync/WebDavHttpPolicy.kt").read_text()
crypto = (base / "sync/WebDavProfileNameCrypto.kt").read_text()
build = (root / "app/build.gradle").read_text()
version = (root / "VERSION").read_text().strip()
credential_sync_start = client.index("    fun syncWithCredentials(\n        local:")
credential_sync = client[credential_sync_start:client.index("    @Suppress(\"UNUSED_PARAMETER\")", credential_sync_start)]
direct_sync_start = client.index("    fun sync(\n        url: String,")
direct_sync = client[direct_sync_start:client.index("    private fun legacyDataSyncGateFailure", direct_sync_start)]
credential_test_start = client.index("    fun testConnectionWithCredentials(\n        credentialProvider:")
credential_test = client[credential_test_start:client.index("    @Suppress(\"UNUSED_PARAMETER\")", credential_test_start)]
test_connection_start = client.index("    fun testConnection(\n        url: String,")
test_connection = client[test_connection_start:client.index("    fun syncWithCredentials(\n        local:", test_connection_start)]

def without_kotlin_comments(source):
    """Remove line and nested block comments without treating quoted text as comments."""
    out = []
    i = 0
    while i < len(source):
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end == -1:
                out.append(" " * (len(source) - i))
                break
            out.append(" " * (end - i))
            i = end
        elif source.startswith("/*", i):
            start = i
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            comment = source[start:i]
            out.append("".join("\n" if char == "\n" else " " for char in comment))
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = len(source) if end == -1 else end + 3
            out.append(source[i:end])
            i = end
        elif source[i] in "\"'":
            quote = source[i]
            start = i
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            out.append(source[start:i])
        else:
            out.append(source[i])
            i += 1
    return "".join(out)

def matching_paren_end(source, open_index):
    """Return the matching close-paren index, honoring nested calls and strings."""
    depth = 0
    i = open_index
    while i < len(source):
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end == -1 else end + 3
        elif source[i] in "\"'":
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
        else:
            if source[i] == "(":
                depth += 1
            elif source[i] == ")":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
    return None

def has_webdav_client_sync_invocation(source):
    """Require an actual WebDavSyncClient constructor followed by its .sync call."""
    code = without_kotlin_comments(source)
    for constructor in re.finditer(r"\bWebDavSyncClient\s*\(", code):
        constructor_open = code.find("(", constructor.start())
        constructor_end = matching_paren_end(code, constructor_open)
        if constructor_end is None:
            continue
        call = re.match(r"\s*\.\s*sync\s*\(", code[constructor_end + 1:])
        if call is None:
            continue
        sync_open = constructor_end + 1 + call.end() - 1
        if matching_paren_end(code, sync_open) is not None:
            return True
    return False

checks = {
    # Account-removal wave: WebDAV is now the only sync action; the SoundWave
    # account sync entry points are retired and their absence is pinned.
    "WebDAV is the distinct (and only) sync action; SoundWave account sync is retired": (
        '"WebDAV 登录同步"' in page and 'showWebDavSheet = true' in page
        and "webDavBundle: LocalStore.UserBundle" in hub
        and "onWebDavBundleApplied = onWebDavBundleApplied" in hub
        and "applyWebDavBundleLocally" in controller
        and "syncFromSettings" not in main and "syncAccountDataExplicitly" not in controller
        and "onSync" not in hub
    ),
    "dialog masks the password and handles the IME/navigation safe areas": (
        "PasswordVisualTransformation()" in sheet and "DesignSheetDialog(" in sheet
        and "scrollContent = true" in sheet and ".imePadding()" not in sheet
        and ".imePadding()" in shell and ".navigationBarsPadding()" in shell and "verticalScroll" in shell
        and '"登录并同步"' in sheet
    ),
    "credentials are isolated and only persisted after AndroidKeyStore encryption": (
        'private const val PREFS = "soundwave_webdav"' in client
        and "SecretStore.encrypt(context, password)" in client
        and "SecretStore.decrypt(context, encrypted)" in client
        and "password_encrypted" in client
    ),
    "WebDAV redirects are not followed and diagnostic errors do not expose request data": (
        ".followRedirects(false)" in client and ".followSslRedirects(false)" in client
        and "Never include exception text" in client
        and "response.body" not in client[client.find("private fun connectionFailure"):]
    ),
    # 2026-11-03 patch (user-approved): default remains HTTPS-only; cleartext is
    # test-only loopback OR a per-server user-confirmed exception (see next check).
    "production WebDAV requires HTTPS by default; cleartext is test-only loopback or a confirmed per-server exception": (
        "internal fun normalizeWebDavBaseUrl" in client
        and "private val allowLoopbackHttpForTests: Boolean = false" in client
        and 'scheme != "https" && !testLoopbackHttp' in client
        and "normalizeWebDavBaseUrl(url) == null" in sheet
        and "仅支持 HTTPS" in sheet
        and "productionWebDavRequiresHttpsBeforeSendingAnyCredential" in tests
    ),
    "http exception is per-server (scheme+host+port), revocable, never global; profile name is encrypted-only in payloads": (
        "fun exceptionApplies" in policy
        and "internal object WebDavHttpExceptionStore" in policy
        and '"http|$host|$port"' in policy
        and "httpExceptions: Set<String> = emptySet()" in client
        and "WebDavHttpPolicy.exceptionApplies(value, httpExceptions)" in client
        and "WebDavProfileNameCrypto.encrypt" in client
        and '"profileNameEnc"' in client
        and "fun discoverProfiles" in client
        and "parseWebDavAccountFileNames" in client
        and "PBKDF2-HMAC-SHA256" in crypto and "AES/GCM/NoPadding" in crypto
        and "资料名仅以加密形式" in (base / "sync/WebDavProfileNameCrypto.kt").read_text()
        and all(name in tests for name in (
            "pbkdf2MatchesRfc7914Vectors",
            "profileNameCryptoRoundTripsAndFailsClosed",
            "syncWritesEncryptedProfileNameAndDiscoveryFindsIt",
            "discoverySkipsLegacyPayloadWithoutEncryptedName",
            "httpExceptionKeysArePerServerHostAndPort",
            "normalizeAcceptsHttpOnlyWithMatchingException",
            "httpSyncIsRefusedBeforeAnyRequestWithoutException",
            "autoSyncBindingAcceptsHttpOnlyViaException"
        ))
    ),
    "sync reuses tombstone/order/platform-play-count/song-stat serialization without personal counts": (
        "includeAvatar = false" in client and "includeHistory = true" in client
        and "includeDailyListenStats = true" in client
        and "includePlayCountCounters = true" not in client and "includeLocalUris = false" in client
        and "CloudUserMerge.merge(localSnapshot, remote)" in client
        and '"_swDeletedFavorites"' not in client
        and "remoteFileCreated" in client
        and all(token in (base / "LocalStore.kt").read_text() for token in (
            'put("playCount", pl.playCount)', 'put("_swLibOrder", pl.syncOrder)',
            'CLOUD_DELETED_FAVORITES', 'CLOUD_DELETED_PLAYLISTS', 'CLOUD_DELETED_PLAYLIST_SONGS'
        ))
        and 'userPlayCount' not in (base / "LocalStore.kt").read_text()
        and 'pl.id == "local"' in (base / "LocalStore.kt").read_text()
    ),
    "overlay runs network work off the UI thread and applies only the merged local snapshot": (
        has_webdav_client_sync_invocation(overlay)
        and "accountDataKey = accountSync.accountKey, accountDataCurrent = accountSync.isCurrent" in overlay
        and "ListenStatsSync.webDavSnapshot(context, latestBundle.value, statsOwner)" in overlay
        and overlay.index("WebDavOperations.transaction(lease)") < overlay.index("ListenStatsSync.webDavSnapshot")
        and "lease.commit {" in overlay
        and "withContext(Dispatchers.IO)" in overlay
        and "onBundleApplied(result.bundle)" in overlay
        and "if (avatarSync?.isCurrent?.invoke() == true) avatarSync.apply(result.avatarB64)" in overlay
        and "onPrepareWebDavAvatarSync = { account.prepareWebDavAvatarSync() }" in main
        and "fun prepareWebDavAvatarSync()" in controller
        and "identityGeneration == identity && avatarMutationGeneration == mutation" in controller
        and 'incoming.copy(avatarB64 = "")' in controller
        and "fun applyWebDavBundleLocally" in controller
        and "ListenStats.mergeAccountDailyListenStatsForAccount(context, user, merged.dailyListenStatsJson)" in controller
        and "persistBundle()" not in controller[controller.find("fun applyWebDavBundleLocally"):]
    ),
    "data sync is gated but credential-only PROPFIND is testable and manual sync uses its explicit gate": (
        "WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE" in client
        and "legacyDataSyncGateFailure(gateStateProvider)" in client
        and "state == LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN" in client
        and credential_sync.index("legacyDataSyncGateFailure(gateStateProvider)") < credential_sync.index("val credentials = try {")
        and direct_sync.index("legacyDataSyncGateFailure(gateStateProvider)") < direct_sync.index("normalizeWebDavBaseUrl(url, allowLoopbackHttpForTests, httpExceptions)")
        and direct_sync.index("legacyDataSyncGateFailure(gateStateProvider)") < direct_sync.index("execute(propfind(collectionUrl(base), username, password))")
        and "legacyDataSyncGateFailure(gateStateProvider)" not in credential_test
        and "legacyDataSyncGateFailure(gateStateProvider)" not in test_connection
        and test_connection.index("normalizeWebDavBaseUrl(url, allowLoopbackHttpForTests, httpExceptions)") < test_connection.index("execute(propfind(collectionUrl(base), username, password))")
        and overlay.index("val gateState = remember") < overlay.index("val credentialLoad = remember")
        and "canExplicitlyEnableAccountSync(context)" in overlay
        and "explicitManualSyncGateState(context)" in overlay
        and "WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE" in overlay
        # Account-removal wave: the account consent key is deleted with the
        # account system; the provenance gate above remains the only gate.
        and "ACCOUNT_SYNC_CONSENT_USER_KEY" not in controller
    ),
    "loopback mock tests assert conditional headers, conflict refetch/merge and auth isolation": all(
        name in tests for name in (
            "unknownSnapshotBlocksBeforeCredentialReadsAndAnyWebDavRequest",
            "unknownDataProvenanceDoesNotBlockCredentialTestAnd401IsClassifiedAtPropfind",
            "legacyUnassignedSnapshotAllowsManualWebDavMerge",
            "verifiedEmptySnapshotKeepsManualWebDavSyncAndRemoteMerge",
            "firstSyncCreatesOneWebDavDocumentWithoutAccountCredentials",
            "existingFileUpdateUsesExactEtagReturnedByGet",
            "conflictResponsesRefetchMergeAndRetryWithRefreshedEtag",
            'assertEquals("*", put.ifNoneMatch)',
            'assertEquals("\\\"etag-before-race\\\"", puts[0].ifMatch)',
            "syncMergesRemoteFavoritesAndPreservesLocalTombstonesOrderAndPlatformPlayCount",
            "webDavRoundTripsRequestedUserDataWithoutSharingDeviceLocalUris",
            "invalidAddressAndAuthenticationFailureAreSafe"
        )
    ),
    "first sync treats a missing file as create-on-explicit-action, not a directory failure": (
        'if (response.code == 404) return RemoteRead.Missing' in client
        and 'RemoteRead.Missing -> "If-None-Match" to "*"' in client
        and 'connectionTestDistinguishesMissingBaseDirectoryFromPermissionDenied' in tests
        and 'firstSyncCreatesOneWebDavDocumentWithoutAccountCredentials' in tests
    ),
    "WebDAV path, permission, HTTP, and transport failures are distinct and sanitized": all(
        token in client for token in (
            'WebDAV 目录检查失败（PROPFIND，HTTP 404）',
            'WEB_DAV_APP_DIRECTORY = "SoundWave"',
            '"MKCOL" -> builder.method("MKCOL", null)',
            '405 -> null',
            'WebDAV 目录地址（不是网页入口',
            '权限不足（HTTP 403）',
            'WebDavTransportFailure',
            'UnknownHostException',
            '上传响应未确认'
        )
    ) and all(name in tests for name in (
        'putPermissionFailureIsReportedWithoutChangingLocalOrMockRemoteData',
        'transportExceptionIsClassifiedWithoutLeakingUrlOrCredentials',
        'existingRemoteWithoutEtagIsNotOverwritten',
        'conflictRefetchWithoutEtagDoesNotRetryWrite',
        'jianguoyunRootPut404IsAvoidedByCreatingAppDirectory'
    )),
    "overlay displays progress and keeps success/failure status visible without swallowing callback errors": (
        'finally {' in overlay and 'busy = false' in overlay
        and '正在检查目录并同步当前资料的 v2 文件' in overlay  # account-removal wave: copy de-accounted （账号→资料）
        and 'status = listOfNotNull(completion, credentialWarning).joinToString(" ")' in overlay
        and '连接成功（可读写' in overlay
        and '写入并删除临时文件' in sheet
        and 'statusIsError = false' in overlay
        and 'if (status.isNotEmpty())' in sheet
        # ACCEPT6（规格重钉）：状态面配色收编至 DesignSheet 的 designStatus* helper（非错误态
        # 不再走根主题未定义的 primaryContainer 薰衣草紫）——错误色对随 helper 在 shell 中钉。
        and 'designStatusContainerColor(statusIsError)' in sheet
        and 'MaterialTheme.colorScheme.errorContainer' in shell
    ),
    "VERSION and Gradle declarations agree": (
        re.fullmatch(r"1\.0\.[0-9]+", version) is not None
        and f'versionName "{version}"' in build
        and re.search(r"versionCode\s+" + str(int(version.rsplit(".", 1)[-1])) + r"\b", build) is not None
    ),
}
for label, ok in checks.items():
    print(f"[webdav-sync-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
