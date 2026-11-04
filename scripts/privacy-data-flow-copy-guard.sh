#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
me = (base / "MePage.kt").read_text()
# r656 item 14：数据流向 / 一键反馈改为独立设置详情页（SettingsSupportPages.kt）。
support = (base / "SettingsSupportPages.kt").read_text()
api = (base / "ApiClient.kt").read_text()
models = (base / "Models.kt").read_text()
webdav = (base / "sync/WebDavSync.kt").read_text()
crash = (base / "CrashGuard.kt").read_text()
hub = (base / "SettingsHub.kt").read_text()
auto_path = base / "CrashReportAutoUpload.kt"
install_id_path = base / "InstallId.kt"
local = (base / "LocalStore.kt").read_text()
cloud = (base / "CloudSyncController.kt").read_text()
secret_store = (base / "SecretStore.kt").read_text()
main = (base / "MainActivity.kt").read_text()
context_preferences = (base / "ContextPreferences.kt").read_text()
script = (base / "ScriptRuntime.kt").read_text()
transport = (base / "ScriptFetchTransport.kt").read_text()
policy = (base / "ScriptNetworkPolicy.kt").read_text()
history_policy = (base / "PlaybackHistoryPolicy.kt").read_text()

start = support.find("internal val DATA_FLOW_ROWS")
end = support.find("private fun dataFlowIcon", start)
if start < 0 or end < 0:
    raise SystemExit("FAIL: cannot locate the in-app data-flow rows")
dialog = support[start:end]
rows = dict(re.findall(r'^\s*"([^"\\\n]+)"\s+to\s+"([^"\\\n]*)"\s*,?\s*$', dialog, re.M))

required_copy = {
    "WebDAV 同步": (
        "默认关闭自动同步", "没有定时后台任务",
        "最近 30 天播放历史（最多 300 条）", "最近 30 天真实听歌统计",
        "不上传音乐文件", "WebDAV 登录凭据仅在本机加密保存", "HTTPS WebDAV 服务",
    ),
    "脚本联网": (
        "从本机直连其目标服务器", "不再经过 SoundWave 服务器中转", "只发往目标服务器",
        "请求发出前会校验目标地址", "其余一律拒绝", "白名单过滤",
        "x-token", "x-request-key", "x-card-key",
    ),
    "本机存储": (
        "收藏、歌单及歌曲记录、头像等同步数据", "播放历史和下载记录",
        "本机应用私有 SharedPreferences", "JSON 文本",
        "本机播放历史最多保留 30 天", "每日听歌统计最多保留 365 天",
        "没有对这些用户数据使用 Android Keystore AES-GCM 加密",
        "WebDAV 登录凭据则单独通过 Android Keystore AES-GCM 加密后保存",
    ),
    "崩溃报告": (
        "先脱敏", "只保存在本机", "不会上传到任何服务器",
        "崩溃诊断页查看、复制或清除", "下载目录", "不读取 Android ID",
    ),
    "播放历史与本机清理": (
        "播放历史页删除单条或批量选中的记录", "对应单次播放删除标记",
        "WebDAV 会在下次同步时传播删除标记",
        "其他使用同一同步数据的设备下次同步后会移除该次播放",
        "未开启 WebDAV 同步时标记仅保存在本机",
        "清空全部历史只改本机独立历史记录",
        "歌单页内的“清空历史”", "只更新本机",
        "本机播放历史最多保留最近 30 天",
        "单纯进入歌单库不会单独触发同步", "最近 30 天且最多 300 条",
    ),
}
for label, fragments in required_copy.items():
    text = rows.get(label)
    if text is None:
        raise SystemExit(f"FAIL: missing privacy explanation row: {label}")
    for fragment in fragments:
        if fragment not in text:
            raise SystemExit(f"FAIL: {label} row is missing boundary: {fragment}")

for unsupported in (
    "服务器会永久保存", "服务器不会保存", "服务器立即删除",
    "服务器绝不会留存", "退出登录会删除云端数据",
):
    if unsupported in dialog:
        raise SystemExit(f"FAIL: unsupported server-side privacy claim found: {unsupported}")
if "/music/" in dialog:
    raise SystemExit("FAIL: internal server endpoint path found in user-facing privacy copy")

# Cross-check the explanation against the client paths it describes.
history_start = main.find("onDeleteHistory = { s ->")
history_end = main.find("onBackupRestored = {", history_start)
history_actions = main[history_start:history_end] if history_start >= 0 and history_end > history_start else ""
clear_start = main.find("onClearHistory = {", history_start)
clear_end = main.find("onBackupRestored = {", clear_start)
clear_action = main[clear_start:clear_end] if clear_start >= 0 and clear_end > clear_start else ""
playlist_start = main.find("onOpenPlaylists = {")
playlist_end = main.find("onLogout = {", playlist_start)
playlist_open = main[playlist_start:playlist_end] if playlist_start >= 0 and playlist_end > playlist_start else ""

def braced_body(text, open_brace):
    depth = 0
    state = "code"
    block_depth = 0
    i = open_brace
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if text.startswith('"""', i):
                state = "raw-string"
                i += 3
                continue
            if ch == '"':
                state = "string"
            elif ch == "'":
                state = "char"
            elif ch == "/" and nxt == "/":
                state = "line-comment"
                i += 2
                continue
            elif ch == "/" and nxt == "*":
                state = "block-comment"
                block_depth = 1
                i += 2
                continue
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[open_brace + 1:i]
        elif state == "string" or state == "char":
            if ch == "\\":
                i += 2
                continue
            if (state == "string" and ch == '"') or (state == "char" and ch == "'"):
                state = "code"
        elif state == "raw-string":
            if text.startswith('"""', i):
                state = "code"
                i += 3
                continue
        elif state == "line-comment":
            if ch == "\n":
                state = "code"
        elif state == "block-comment":
            if ch == "/" and nxt == "*":
                block_depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "code"
                continue
        i += 1
    return ""

def without_comments(text):
    # Keep string/char literals intact; comment decoys must never establish a call edge.
    literal = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
    out = []
    i = 0
    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
            out.append(" ")
        elif text.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(text) and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            out.append(" ")
        else:
            match = literal.match(text, i)
            if match:
                out.append(match.group())
                i = match.end()
            else:
                out.append(text[i])
                i += 1
    return "".join(out)


def compact(text):
    return re.sub(r"\s+", "", text)


def function_body(text, name):
    match = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\([^{}]*\)[^{=]*\{", text)
    return braced_body(text, match.end() - 1) if match else ""


# Deletion contract (user ruling 2026-11-04): the /music/bug upload machinery —
# one-click feedback, crash auto-upload and manual send — is deleted for good;
# crash reports are on-device only. The kept local bodies stay pinned verbatim
# (comments ignored), and any resurrection of the upload surface fails here.
crash_code = without_comments(crash)
crash_expected_bodies = {
    'install': '''
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = CrashReportRedactor.redact(buildReport(app, thread, throwable))
                synchronized(privacyLock) {
                    File(app.filesDir, FILE_NAME).writeText(text.take(24_000), Charsets.UTF_8)
                    File(app.filesDir, PENDING_NAME).writeText(text.take(24_000), Charsets.UTF_8)
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    ''',
    'clear': '''
        synchronized(privacyLock) {
            deleteFile(context, FILE_NAME)
        }
    ''',
    'clearPending': '''
        synchronized(privacyLock) {
            deleteFile(context, PENDING_NAME)
        }
    ''',
}
crash_local_only = (
    all(compact(function_body(crash_code, name)) == compact(without_comments(expected))
        for name, expected in crash_expected_bodies.items())
    and all(compact(fragment) in compact(crash_code) for fragment in (
        'private val privacyLock = Any()',
        'fun last(context: Context): String = CrashReportRedactor.redact(readFile(context, FILE_NAME))',
        'fun pending(context: Context): String = CrashReportRedactor.redact(readFile(context, PENDING_NAME))',
    ))
    and all(fragment not in crash_code for fragment in (
        'CrashReportAutoUpload', 'CrashUploadPrefs', '/music/bug', 'reportBug',
        'crash_upload', 'submitNow', 'flushPending', 'AutoUpload',
        'isAutoUploadEnabled', 'setAutoUploadEnabled', 'HttpURLConnection',
    ))
    and not auto_path.exists()
    and 'CrashUploadPrefs' not in (base / "CrashReportRedactor.kt").read_text()
    and 'ANDROID_ID' not in crash_code
    and 'Settings.Secure' not in crash_code
)

# 账号系统去除后，本机唯一的口令类凭据是 WebDAV 登录凭据：仍经 SecretStore
# （Android Keystore AES-GCM）加解密，SecretStore.encrypt 本体逐字钉死，禁止日志回退。
main_code = without_comments(main)
secret_code = without_comments(secret_store)
local_code = without_comments(local)
preferences_code = without_comments(context_preferences)
encrypt_expected = '''
    if (plain.isEmpty()) return ""
    repeat(TRANSIENT_ATTEMPTS) { attempt ->
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return PACKED_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            backoff(attempt)
        }
    }
    return null
'''
credential_consumers_secure = (
    compact(function_body(secret_code, "encrypt")) == compact(encrypt_expected)
    and "SecretStore.encrypt(context, password)" in webdav
    and "SecretStore.decrypt(context, encrypted)" in webdav
    and 'ContextPreferences.open(context, "soundwave", 0,' in main_code
    and 'app.getSharedPreferences(target, mode)' in preferences_code
    and not re.search(r'\b(?:Log\s*\.|println\s*\(|print\s*\(|logger\s*\.)', secret_code)
    # 账号口令的 prefs 键随账号系统退役，禁止回潮。
    and '"pass"' not in main_code and '"pass_owner"' not in main_code
    and '"logged_in"' not in main_code
)

checks = {
    # 数据流向页顶部统计块的数字必须与 PlaybackHistoryPolicy / ListenStatsDailyStore 的
    # 留存口径一致（本机历史 30 天、云端历史 300 条）；ACCEPT5-STATSMONTH 把本机历史
    # 7 天改 30 天时该统计块曾漏改，此处钉死防回潮。
    "data-flow stat tiles match the retention policy (local history 30 days, cloud history 300 items)": (
        'SettingsStatTile("本机历史", "30", "天"' in support
        and 'SettingsStatTile("云端历史", "300", "条"' in support
        and 'SettingsStatTile("本机历史", "7", "天"' not in support
    ),
    # 删除合同（用户 2026-11-04 裁定）：一键反馈整体删除——设置行、反馈页、InstallId 与
    # /music/bug 客户端路径不得回潮；数据流向不得再有「主动反馈」行。
    "one-click feedback stays deleted: no row, page, install id or /music/bug client path": (
        all(fragment not in support for fragment in ("FeedbackPage", "InstallId", "reportBug"))
        and "ANDROID_ID" not in support and "Settings.Secure" not in support
        and "/music/bug" not in api and "reportBug" not in api
        and 'NavRow("一键反馈"' not in me and "BugReport" not in me
        and "FeedbackPage" not in hub and '"feedback"' not in hub
        and "主动反馈" not in rows
        and not install_id_path.exists()
    ),
    "crash reports stay on-device only; the upload machinery stays deleted": crash_local_only,
    "webdav account document keeps the shared snapshot serialization boundaries": all(fragment in local for fragment in (
        "cloudSafeCover(pl.cover, includeLocalUris = false).ifBlank { pl.syncCover }",
        "cloudSafeCover(coverSource, includeLocalUris)",
        "cloudSafeCover(song.cover, includeLocalUris)",
        "filterDeviceLocalReference(raw, includeLocalUris)",
        "PlaybackHistoryPolicy.retainForCloudSync(history, limit = historyLimit)",
    )) and "filterDeviceLocalReference(cover, includeLocalUri)" in models
        and "includeLocalUris = false" in webdav
        and "LocalStore.UserBundle.fromCloud(user, allowLocalUris = false)" in webdav
        and 'remove("enabledScripts")' in webdav and 'remove("scriptOrder")' in webdav
        and "const val CLOUD_RETENTION_MS: Long = 30L * 24L * 60L * 60L * 1000L" in history_policy
        and "const val CLOUD_SYNC_LIMIT: Int = 300" in history_policy
        and "WebDavHistoryReconciler.reconcile" in cloud
        and "historyDeletionTombstones" in cloud
        and "remotePlaylistSongs" not in local and "pl.songs.take(20)" not in local,
    "local records use app-private JSON preferences while WebDAV credentials use Keystore AES-GCM": all(fragment in local_code for fragment in (
        'private const val PREF = "soundwave_userdata"',
        "getSharedPreferences(PREF, 0)",
        "history = PlaybackHistoryPolicy.retainRecent(bundle.history).toMutableList()",
        "putString(KEY_DATA, localSnapshot.toCloudJson().toString())",
        "putString(KEY_HISTORY, arr.toString())",
        "putString(KEY_DOWNLOADS, encodeDownloads(list))",
    )) and all(fragment in secret_code for fragment in (
        'Cipher.getInstance("AES/GCM/NoPadding")',
        'KeyStore.getInstance("AndroidKeyStore")',
        'KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")',
        ".setBlockModes(KeyProperties.BLOCK_MODE_GCM)",
        ".setRandomizedEncryptionRequired(true)",
    )) and credential_consumers_secure,
    "script egress is direct-only (server relay retired) and header filter matches client code": all(fragment in transport for fragment in (
        "ScriptNetworkPolicy.inspectScriptUrl(url)",
        "urlValidation.validatedTarget",
        "urlValidation.systemStackDirect",
    )) and all(fragment not in transport for fragment in (
        "/music/http", "shouldUseProxyFallback", "bypassProxyFallback", 'put("url", url)',
    )) and "shouldUseProxyFallback" not in policy and "proxyFallbackAllowed" not in policy
    and all(fragment in policy for fragment in (
        '"x-request-key"', '"x-token"', '"x-card-key"',
        "fun filterHeaders(raw: Map<String, String>)",
    )),
    "single and batch history deletion write tombstones while clear remains local-only": (
        history_start >= 0 and history_end > history_start
        and "localData.removeHistory(s)" in history_actions
        and "account.deleteHistoryRecords(listOf(s))" in history_actions
        and "account.deleteHistoryRecords(songs)" in history_actions
        and "localData.clearHistory()" in history_actions
        and "persistBundle" not in history_actions
        and "localData.clearHistory()" in clear_action
        and "account.deleteHistoryRecords" not in clear_action
    ),
    # 账号中心面板已不再提供「歌单」快捷入口；若入口存在，打开歌单库仍不得触发 persistBundle。
    "opening the playlist library alone does not call persistBundle": (
        playlist_start < 0
        or (playlist_end > playlist_start and "persistBundle" not in playlist_open)
    ),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f"FAIL: client/source boundary mismatch: {label}")

version = (root / "VERSION").read_text().strip()
build = (root / "app/build.gradle").read_text()
match = re.fullmatch(r"1\.0\.(\d+)", version)
if not match or f'versionName "{version}"' not in build or f'versionCode {match.group(1)}' not in build:
    raise SystemExit("FAIL: VERSION and Gradle version declarations are not synchronized")

print("In-app privacy data-flow copy guard passed")
PY
