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
hub = (base / "SettingsHub.kt").read_text()
main = (base / "MainActivity.kt").read_text()
route = (base / "AppRoute.kt").read_text()
chrome = (base / "PageChrome.kt").read_text()
dialogs = (base / "ui/common/AppDialogs.kt").read_text()
build = (root / "app/build.gradle").read_text()
version = (root / "VERSION").read_text().strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[settings-page-cleanup-guard] FAIL: {message}")


require('RowCard("检查更新"' not in me and "onCheckUpdate" not in me,
        "the standalone support-page update option returned")
support = (base / "SettingsSupportPages.kt").read_text()
update_action = (base / "AboutUpdateAction.kt").read_text()
require('AboutUpdateAction(updateStatus = updateStatus, onCheckUpdate = onCheckUpdate)' in support and
        '"检查更新"' in update_action and 'onClick = onCheckUpdate' in update_action and
        "onCheckUpdate = onCheckUpdate" in hub and
        'onCheckUpdate = {' in main,
        "the About-page update checker/callback must remain available")
# 1.0.655 settings redesign (user items 15/17): the root page shows design categories whose rows open
# one dedicated detail page per option (coral eyebrow + serif title, no back arrow / 返回设置 row).
# ACCEPT5 regroup (user ruling 2026-11-04, spec change): six groups — 播放与音质/音源与脚本/下载/外观/同步与数据/系统与实验；
# the last one carries the retained diagnostics and About actions directly.
# 一键反馈 deletion (user ruling 2026-11-04, deletion contract): the feedback row is gone for good.
require('EditorialGroup("系统与实验", "System & labs"' in me and
        'NavRow("崩溃诊断"' in me and 'NavRow("关于 APP"' in me and 'onOpenAbout()' in me and
        'NavRow("一键反馈"' not in me and 'onSettingsSub("feedback")' not in me,
        "the system group must expose its retained diagnostics and About actions directly (feedback row deleted)")
require('NavRow("数据流向"' in me and 'onSettingsSub("data_flow")' in me,
        "privacy information must open directly from the data category")
require('onSettingsSub("privacy")' not in me and '"privacy" ->' not in route,
        "privacy information must not regain an intermediate settings page")
require('"audio_from_play"' not in route and
        'onSettingsSub("audio_from_play")' not in me and
        '"audio_from_play"' not in me,
        "the duplicate audio settings child route must stay removed")
for sub in ("preload", "lyrics", "theme", "dock", "search", "download"):
    require(f'onSettingsSub("{sub}")' in me and f'                    "{sub}" -> ' in me and f'"{sub}" to Triple(' in me,
            f"settings option must open its own detail page with a design hero: {sub}")
# 1.0.659 (user item 7): quality / sleep / speed / equalizer / audio focus have no sub-page; value at row right, tap opens a unified sheet.
for sub in ("quality", "sleep", "speed", "equalizer", "mix"):
    require(f'onSettingsSub("{sub}")' not in me and f'"{sub}" to Triple(' not in me, f"value setting regained a sub-page: {sub}")
for row, sheet in (('ValueRow("在线播放音质"', "PlaybackQualitySheet("), ('ValueRow("睡眠定时"', "SleepTimerSheet("),
                   ('ValueRow("播放速度"', "PlaybackSpeedSheet("), ('ValueRow("均衡器"', "EqualizerSheet("),
                   ('ValueRow("和其他 APP 同时播放"', "AudioFocusSheet(")):
    require(row in me and sheet in me, f"value row / unified sheet missing: {row}")
for category in ("播放与音质", "音源与脚本", "下载", "外观", "同步与数据", "系统与实验"):
    require(f'EditorialGroup("{category}"' in me, f"settings category missing: {category}")
for retired in ("过渡效果", "外观与歌词", "音源与搜索", "数据与同步", "服务与支持", "下载管理"):
    require(f'EditorialGroup("{retired}"' not in me, f"retired settings category returned: {retired}")

lyrics_start = me.index('                    "lyrics" -> {')
lyrics_end = me.index('                    "theme" -> item {', lyrics_start)
lyrics_branch = me[lyrics_start:lyrics_end]
other_branches = me[me.index('                    "preload" -> item {'):lyrics_start] + me[lyrics_end:me.index('                    else -> Unit', lyrics_end)]
lyric_controls = (
    'RowCard("显示歌词翻译"', 'RowCard("显示歌词罗马音"',
    # r658c P：统一歌词偏好——歌词字号 / 行高 / 偏移与播放器共用（距离模糊 2026-11-04 删除，见下方删除合同）。
    "PlayerLyricsSettingsModel.font(", "PlayerLyricsSettingsModel.lineHeight(", "PlayerLyricOffsetStepper(",
    'RowCard("逐字歌词高亮"', 'RowCard("当前词轻微上浮"', 'RowCard("悬浮歌词"',
    'RowCard("解锁悬浮歌词"', 'RowCard("悬浮歌词行数"', 'RowCard("悬浮歌词字号"',
    'RowCard("悬浮背景透明度"', 'RowCard("悬浮文字透明度"', 'RowCard("悬浮显示宽度"',
    'RowCard("悬浮文字颜色"', 'RowCard("歌词字体"',
)
for control in lyric_controls:
    require(control in lyrics_branch, f"lyric-only control is missing from the lyrics page: {control}")
    require(control not in other_branches, f"lyric-only control remains in a non-lyrics page: {control}")
# 删除合同（用户 2026-11-04）：距离模糊设置项整个删除——歌词页不得再出现该滑杆（渲染钉死固定值）。
require("PlayerLyricsSettingsModel.distanceBlur" not in lyrics_branch,
        "distance blur slider must stay retired from the lyrics page (deleted 2026-11-04)")
require("applySpeed(it)" in me and "applyEq(it)" in me and "applyAudioFocus(it)" in me,
        "audio value sheets must retain speed, equalizer, and audio-focus controls")
require('                    else -> Unit\n                }' in me,
        "unknown settingsSub values must not fall through to another category")

row_start = me.index("fun RowCard(")
row_end = me.index("fun NavRow(", row_start)
row_component = me[row_start:row_end]
require("glyph: SettingGlyphSpec" in row_component and "glyph: Int" not in row_component,
        "settings rows must use named semantic glyph specs, not repeated numeric ids")
require("SettingsDetailRow(" in row_component and "settingIcon(glyph)" in row_component,
        "detail-page rows must render through the shared design detail row with their own line glyph")
interaction = (base / "ui/settings/SettingsRowInteraction.kt").read_text()
# Re-pinned 2026-11-04 (user ruling, accept5-press P-10): the old pointer-highlight wash
# (group-color full-row tint) is FORBIDDEN — settings rows press with pressScale only.
require("pressScale(PressMotion.SCALE_ROW" in interaction and
        "fun settingsPressTint" not in interaction and "drawRect(bg" not in interaction and
        "indication = null" in interaction,
        "settings rows must press with row-tier pressScale only (no color wash, no ripple)")
require("SettingsLineGlyphs.of(glyph.glyphKey)" in me,
        "every settings glyph must come from the single SettingsLineGlyphs line set (uniqueness is unit-tested)")
require(not re.search(r'RowCard\([^\n]*\bglyph\s*=\s*\d+', me),
        "numeric setting glyph arguments must not return")
require("Text(\"返回设置" not in me and "\"< 返回设置" not in me and "Text(\"返回设置" not in hub,
        "settings sub-pages must not show a 返回设置 row")

# Keep in-app updates in About while removing developer contact/project listings.
# 对话框清扫（SW-12-08，用户批准形态变更）：About 已由 AlertDialog+TextButton 迁 DesignSheet 弹层，
# 两条入口改钉 DesignSheetRow 形态（字形+文案逐字）；GitHubReleases 行为断言逐字不变。
require('DesignSheetRow(g.Switch, "检测更新"' in dialogs and 'DesignSheetRow(g.Download, "前往下载"' in dialogs and
        'GitHubReleases.check(BuildConfig.VERSION_NAME)' in dialogs and
        'GitHubReleases.downloadPage()' in dialogs,
        "in-app update checking/downloading must remain in About")
# The two old-identity contact literals (legacy mailbox, legacy QQ number)
# are assembled at runtime from fragments so this guard's own source does not
# republish them in plaintext. The asserted token set below is byte-for-byte
# the same set as before.
_legacy_mail = "projects" + "258036" + "@" + "gmail" + ".com"
_legacy_qq = "QQ" + "：" + "322" + "9921907"
for token in (
    "github.com/projects258036-svg/soundwave",
    "mqqapi://card/show_pslcard",
    "wpa.qq.com/msgrd",
    _legacy_mail,
    _legacy_qq,
    "邮箱：" + _legacy_mail,
):
    require(token not in dialogs and token not in hub and token not in support,
            f"About must not expose contact/project detail: {token}")
require('Text("APP 简介"' in dialogs and 'Text("APP 简介"' in support,
        "both About surfaces must show an app introduction")

header_start = chrome.find("internal fun AppPageHeader(")
header_end = chrome.find("\n@Composable\ninternal fun PlatformSwitchButton", header_start)
header = chrome[header_start:header_end]
require(header_start >= 0 and header_end > header_start,
        "shared app-page header boundary not found")
require("startPadding: androidx.compose.ui.unit.Dp = 16.dp" in header and
        ".padding(start = startPadding, end = 16.dp)" in header,
        "top-level and child headers must share the 16dp left inset")
require("drawCircle" not in header and "Canvas(" not in header,
        "the shared title header must not paint an extra dot/indicator")
for source, label in ((me, "settings category"), (hub, "settings deep page")):
    require("startPadding = 16.dp" in source or "startPadding = 14.dp" in source,
            f"{label} header must use the shared left inset")
source_root = re.search(
    r'Box\(Modifier\.fillMaxSize\(\)\.background\(bg\)\)\s*\{(?P<body>.*?)\n\s*Scaffold\(',
    main, re.S,
)
require(source_root is not None and "SharedCoverOverlayRoot(" in source_root.group("body"),
        "source route background must remain beneath the moving player and shared-cover flight layer")
require("if (immersivePlayerSurfaceVisible) Modifier.background(ThemeTokens.PlayerBackground)" not in main,
        "the fixed player-color transition plate must stay removed")
require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675")
require("scripts/settings-page-cleanup-guard.sh" in workflow,
        "the settings cleanup guard must run in portable quality gates")
print("[settings-page-cleanup-guard] PASS: ACCEPT5 six-group settings, per-row colored line glyphs, pressScale-only row feedback, retained routes, flattened settings, About update retention/contact cleanup, aligned header, source background, and synchronized 1.0.675")
PY
