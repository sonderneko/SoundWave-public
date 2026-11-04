#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
root = Path('app/src/main/java/xyz/cncnlp/soundwave')
hub = (root / 'SettingsHub.kt').read_text()
me = (root / 'MePage.kt').read_text()
page = (root / 'SettingsNotificationsPage.kt').read_text()
release = (root / 'GitHubReleases.kt').read_text()
cache = (root / 'ScriptProbeCache.kt').read_text()
def require(condition, message):
    if not condition:
        raise SystemExit('[settings-notifications-guard] FAIL: ' + message)
require('"notifications" -> SettingsNotificationsPage(' in hub and '"notifications" -> "通知中心"' in hub,
        'notification route must render the notification page with its own title')
require('SettingsNotificationButton { onSettingsSub("notifications") }' in hub and
        'SettingsNotificationButton { onSettingsSub("notifications") }' in me,
        'both settings header paths must use the real notification route')
require('showAvatar = true' not in me and 'showAvatar = true' not in hub,
        'settings headers must not retain the replaced avatar')
# Account-removal wave: the settings card is a local profile card; the avatar
# action (profile sheet: change avatar / rename) stays reachable via the same
# badge, with loggedIn pinned to the local-profile literal.
require('UserAvatarBadge(loggedIn = true, avatarPath = avatarPath, onClick = onLoginClick' in me,
        'profile-card avatar action must remain reachable')
require('"切换浅色或夜间主题"' in me, 'root theme switch must remain')
require('Icons.Outlined.Notifications, "查看通知"' in page, 'bell needs an accessible action name')
require('checkState.collectAsState()' not in page and '应用更新' not in page and 'reportRevision.collectAsState()' in page,
        'message center must not present update cards (update checks live on the About page); report results must be observed')
require('ScriptProbeCache.latestReport(it.id)' in page and 'ScriptProbeCache.isReportCurrent(report, script)' in page,
        'report display must reuse existing evidence and expiry rules')
require(all(token not in page for token in ('probeScriptScored(', 'onProbeAll', 'ScriptProbeScheduler.')),
        'viewing notifications must not initiate probes or background work')
# 验收二轮 E3（用户裁定）：打开通知中心时须同步清理落盘收件箱里过期的脚本检测卡（30 分钟
# 保留窗）——这是用户点名的数据清洗，不是探测/后台任务。原守卫对 LaunchedEffect 的一刀切
# 禁令与该裁定冲突，现按等严口径收窄：页面只许有恰好一处 LaunchedEffect，且必须正是这行
# 清洗调用（正向钉死 + 计数钉死，比原禁令更精确，见 WAVE-E.md E3 守卫登记）。
# 计数范围限定在页面组合体本身（同文件的铃铛角标动画 LaunchedEffect 是 E10 的纯视觉
# 弹入/ping，不属于「查看通知页发起的工作」，不计入）。
page_body = page[page.index('fun SettingsNotificationsPage('):page.index('fun SettingsNotificationsContent(')]
require('LaunchedEffect(Unit) { AppNotice.pruneExpiredScriptNotices() }' in page_body and page_body.count('LaunchedEffect(') == 1,
        'the only page effect allowed is the mandated expired-script-notice prune on open')
require('val checkState = mutableCheckState.asStateFlow()' in release and 'release = checkedRelease' in release,
        'update state must retain the actual checked release, independently from playback status')
require('registerOnSharedPreferenceChangeListener(reportListener)' in cache,
        'open notification page must refresh on persisted report changes')
print('[settings-notifications-guard] PASS: routes, bell accessibility, retained account/theme actions, real observable sources, expiry, and no automatic probes')
PY
