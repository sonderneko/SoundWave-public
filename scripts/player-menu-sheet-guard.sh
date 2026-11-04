#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
source_root = root / "app/src/main/java/xyz/cncnlp/soundwave"
full_path = source_root / "FullPlayer.kt"
sheet_path = source_root / "PlayerMenuSheet.kt"
menu_path = source_root / "PlayerMenu.kt"
presentation_path = source_root / "PlayerMenuPresentation.kt"
preview_path = source_root / "ui/player/PlayerLyricsStylePreview.kt"
footer_path = source_root / "ui/player/FullPlayerFooterRenderer.kt"
settings_path = source_root / "MePage.kt"
lyrics_model_path = source_root / "PlayerLyricsSettingsModel.kt"
full = full_path.read_text(encoding="utf-8") if full_path.is_file() else ""
sheet = sheet_path.read_text(encoding="utf-8") if sheet_path.is_file() else ""
menu = menu_path.read_text(encoding="utf-8") if menu_path.is_file() else ""
presentation = presentation_path.read_text(encoding="utf-8") if presentation_path.is_file() else ""
preview = preview_path.read_text(encoding="utf-8") if preview_path.is_file() else ""
footer = footer_path.read_text(encoding="utf-8") if footer_path.is_file() else ""
settings = settings_path.read_text(encoding="utf-8") if settings_path.is_file() else ""
lyrics_model = lyrics_model_path.read_text(encoding="utf-8") if lyrics_model_path.is_file() else ""
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-menu-sheet-guard] FAIL: {message}")


require(bool(full), "FullPlayer.kt is missing")
require(bool(sheet), "the extracted PlayerMenuSheet.kt is missing")
require(bool(menu), "the player menu action components are missing")
require(bool(presentation), "the player menu action presentation model is missing")
require(bool(preview), "the extracted Lyrics Style preview component is missing")
require(bool(footer), "the extracted player footer renderer is missing")
require(len(sheet.splitlines()) < 300, "PlayerMenuSheet.kt must remain below 300 lines")
require(len(preview.splitlines()) < 250 and len(footer.splitlines()) < 250,
        "the extracted lyric preview and footer components must each remain below 250 lines")
require(len(full.splitlines()) < 1200, "FullPlayer.kt must remain below 1200 lines after extraction")
require(re.search(r"@Composable\s+internal fun FullPlayerMenuSheet\s*\(", sheet) is not None,
        "FullPlayerMenuSheet must remain an internal Compose presentation component")

def composable_block(source: str, signature: str) -> str:
    start = source.find(signature)
    if start < 0:
        return ""
    end = source.find("\n@Composable", start + len(signature))
    return source[start:] if end < 0 else source[start:end]

primary_card = composable_block(menu, "internal fun PlayerMenuPrimaryActionCard(")
secondary_card = composable_block(menu, "internal fun PlayerMenuActionCard(")
require(bool(primary_card) and bool(secondary_card), "both player action card components must remain")
for card, label, height in ((primary_card, "legacy primary", ".height(62.dp)"),
                            (secondary_card, "action row", ".height(54.dp)")):
    require(".background(" not in card, f"{label} action cards must have transparent backgrounds")
    require(".clip(" in card and ".clickable" in card,
            f"{label} action cards must retain clipped ripple interaction")
    require(height in card, f"{label} action card dimensions must remain unchanged")
# 1.0.655: the player sheet uses its own reference-style row (icon + bold title + right subtitle): 58dp.
sheet_row = composable_block(menu, "internal fun PlayerMenuSheetRow(")
require(bool(sheet_row) and ".height(58.dp)" in sheet_row and ".background(" not in sheet_row
        and ".clip(" in sheet_row and ".clickable" in sheet_row,
        "the player sheet row must be a transparent 58dp clipped ripple row")
# r658c P（用户 10-20）：⋯ 抽屉 = 公共 DesignSheetDialog 外壳（贴屏幕底边、底部滑入，不走平台 Dialog 缩放 / 淡入）+
# 全 App 共用的 TrackActionsSheetContent（封面头 + 快捷钮 + 列表）；播放器专属项作为列表行插入。
# M 波（ISSUE-SONGMENU §5）：快捷钮四改五（不喜欢恒末位、原「不喜欢」列表行删除），下方键位 require 已按规格变更改钉。
track_sheet_path = source_root / "TrackActionsSheet.kt"
track_sheet = track_sheet_path.read_text(encoding="utf-8") if track_sheet_path.is_file() else ""
require(bool(track_sheet), "the shared TrackActionsSheet.kt component is missing")
# M 波规格变更（ISSUE-SONGMENU §4.1）：面板不再自滚——封顶 0.70 + 内容内滚、ScrollState hoist 给面板拖拽关闭连接；仍是同一共享外壳、同一组否定项。
require("DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, maxHeightFraction = TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION, contentScrollState = menuScrollState)" in sheet
        and "DialogProperties" not in sheet and "androidx.compose.ui.window.Dialog" not in sheet
        and "BoxWithConstraints" not in sheet and "Color.Black.copy" not in sheet,
        "the player menu must use the shared bottom-anchored DesignSheetDialog shell (no own Dialog / scrim / platform animation)")
require("TrackActionsSheetContent(" in sheet and "onLike = onLike" in sheet and "onAdd = onAddToPlaylist" in sheet,
        "level 0 must reuse the shared track-actions layout (收藏 = like, 添加到歌单 in the list)")
require('fun trackActionQuickTileKeys(showDownload: Boolean): List<String> =' in track_sheet
        and 'listOf("like", "next", if (showDownload) "download" else "add", "similar", "dislike")' in track_sheet
        and "DesignQuickTileRow(" in track_sheet and "extraRows()" in track_sheet
        and track_sheet.index("extraRows()") < track_sheet.index('"从歌单删除", "仅移除曲目"')
        and "DesignSheetRow(g.Dislike" not in track_sheet,
        "the shared sheet keeps five quick tiles (dislike last, M wave spec change) and inserts caller rows before the danger zone; no duplicate dislike list row")
detail_pages = (source_root / "DetailDesignPages.kt").read_text(encoding="utf-8")
# M 波：共用内容改为 ColumnScope 扩展，委托层函数体由表达式改为 Column 承接 receiver 的块体；委托关系本身照旧钉死。
require("TrackActionsSheetContent(" in detail_pages and "TrackActionsSheet(song, liked" in detail_pages,
        "detail / today / search track sheets must delegate to the same shared component")
require("actions.forEach" in sheet and "actions.chunked(2)" not in sheet
        and "DesignSheetRow(DefaultPlayerGlyphs.forMenuKey(glyph), title, playerMenuSecondarySubtitle(glyph, sleepMinutes)" in sheet,
        "player-specific actions must be vertically stacked design-sheet rows with line icons and right-side subtitles")
require("DefaultPlayerGlyphs.forMenuKey(glyph)" in sheet_row and "WhiteGlyph(" not in sheet_row
        and "MaterialTheme.colorScheme.outline" in sheet_row,
        "action rows must use the new same-style line icons and outline-coloured dividers")
header = composable_block(menu, "internal fun PlayerMenuSheetHeader(")
require("DesignFonts.DmMono" in header and "designAccentTextColor()" in header,
        "the sheet eyebrow must be coral DM Mono")
for glyph in ("menu_cast", "menu_timer", "menu_lyrics", "volume_on", "volume_off"):
    require(f'"{glyph}"' in sheet, f"secondary action glyph must remain explicit: {glyph}")
require('glyph = if (isActive) "sparkles_filled" else "sparkles_outline"' in presentation,
        "heart mode must use explicit distinct inactive/active glyphs")
require('"menu_delete"' not in sheet and "onDislike = onToggleDislike" in sheet and "disliked = disliked" in sheet
        and 'if (disliked) "取消不喜欢" else "不喜欢"' in track_sheet,
        "the reversible dislike toggle must stay a toggle (取消不喜欢), not a destructive delete action")
require(full.count("FullPlayerMenuSheet(") == 1 and "if (showMenu)" in full,
        "FullPlayer must host and conditionally show exactly one extracted menu")
require(full.count("PlayerLyricsStylePreview(") == 1 and "if (lyricsStyleLayout)" in full
        and "FullPlayerFooterRenderer(" in full,
        "FullPlayer must preserve the conditional Lyrics Style preview and host one extracted footer")
require("internal fun ColumnScope.PlayerLyricsStylePreview(" in preview
        and "PlayerLyricRow(" in preview and "onCompactPreviewTap" in preview,
        "the lyric preview remains a pure shared-row renderer with explicit caller-owned callbacks")
require("controls: @Composable () -> Unit" in footer and "controls()" in footer
        and "onLike()" in footer and "onMore()" in footer,
        "the footer must forward existing controls and like/menu actions without owning playback state")
for component, label in ((preview, "lyric preview"), (footer, "footer renderer")):
    for token in ("PlayerService", "CoroutineScope", "LaunchedEffect", "mutableStateOf", "mutableIntStateOf"):
        require(token not in component, f"the {label} must not own playback state or effects ({token})")

# UI-local scrolling is allowed; menu routing and all player/business state stay in FullPlayer.
for token in (
    "DislikeStore", "Toast", "showSleepBar", "showMenu", "showVolumeInMenu =",
    "SharedPreferences", "DataStore", "PlayerService", "CoroutineScope", "delay(",
    "remember { mutableStateOf", "remember { mutableIntStateOf",
):
    require(token not in sheet, f"the menu sheet must not own state or side effects ({token})")

for state in (
    "var showMenu by remember { mutableStateOf(false) }",
    "var showSleepBar by remember { mutableStateOf(false) }",
    "var menuLevel by remember { mutableIntStateOf(0) }",
    "var showVolumeInMenu by remember(presentationPrefs, presentationMode) { mutableStateOf(presentation.volumeVisible(presentationMode)) }",  # r675: per-mode persisted preference (default layout defaults off), still owned by FullPlayer
    "presentation.setVolumeVisible(presentationMode, showVolumeInMenu)",  # toggle write-back stays in FullPlayer, not the sheet
    "var lyricOffset by remember(lyricOffsetMs) { mutableIntStateOf(lyricOffsetMs) }",
):
    require(state in full, f"FullPlayer must retain state ownership: {state}")

for callback in (
    "onDismiss", "onSetMenuLevel", "onLike", "onAddToPlaylist", "onAddNext", "onDownload",
    "onOpenDevice", "onPlaybackLink", "onSimilar", "onHeartMode", "onToggleDislike", "onOpenSleepTimer",
    "onToggleVolume", "onSleepSelected",
    "onLyricGap", "onLyricBlur", "onLyricOffset", "onLyricFont", "onSelectPlayerLayout",
):
    require(callback in sheet and f"{callback} =" in full,
            f"menu action must route explicitly through FullPlayer: {callback}")

require("PlayerLyricsSettingsModel.menuSettings(" in menu and
        "PlayerLyricsSettingSlider(" in menu and
        "PlayerLyricsSettingsCore(" in sheet and
        'Triple("音量条"' in sheet,
        "the menu must expose the current lyric slider values (shared PlayerLyricsSettingsCore) and a clear volume-bar toggle")
for retired in ("歌词粗细", "打开/关闭歌词页", "歌词来源优先级"):
    require(retired not in sheet and retired not in settings,
            f"a retired lyric setting is still visible: {retired}")
# r658c P：歌词设置统一——去掉沉浸歌词字号 / 行高，「普通歌词字号 / 行高」改名「歌词字号 / 行高」，所有样式共用。
require('"歌词字号"' in lyrics_model and '"歌词行高"' in lyrics_model
        and '"普通歌词' not in lyrics_model and '"沉浸歌词字号"' not in lyrics_model and '"沉浸歌词行高"' not in lyrics_model
        and "IMMERSIVE_FONT" not in sheet and "onLyricImmersive" not in sheet and "onLyricImmersive" not in full
        and "PlayerLyricOffsetStepper(" in menu
        and "val lyricImmersiveSp = PlayerLyricsSettingsModel.immersiveFontFor(lyricFontSp)" in full
        and "lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricBlurDp = lyricBlurDp" in full,
        "one unified lyric preference set (font / line height / offset; distance blur retired 2026-11-04) must drive every player mode")

for token in (
    "ContextData(ctx, menuOwner).disliked(song)", "ContextData(ctx, menuOwner).toggleDislike(song)",
    "toast(if (now)", "onLyricOffset(lyricOffset)",
    "onPlayerLayout(key)",
):
    require(token in full, f"FullPlayer must retain the existing side-effect/persistence owner: {token}")

# Verify the active consumer, not the historical sleep-dialog identifier.
# ACCEPT5（退出动画普查 P13）改钉：onMinutes 不再同步清 showSleepBar（C 类截断——内容钮
# 已接退场句柄、宿主同步清状态把退场掐断）；选择仍经 FullPlayer 落 onSleep、清理由同行
# onDismiss 在退场后承担（钉不变）。本 require 意图（经 FullPlayer 路由）不变，字面等严更新。
sleep_match = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\(remainMs = sleepRemain, onMinutes = \{ onSleep\(it\) \}, onDismiss = \{ showSleepBar = false \}\)", full)
require(sleep_match is not None and "if (showSleepBar)" in full,
        "sleep dialog must consume remaining time and route selection/dismissal through FullPlayer")
sleep_source = (source_root / f"{sleep_match.group(1)}.kt").read_text()
sleep_sheet = (source_root / "ui/settings/SettingsValueSheets.kt").read_text()
sleep_model = (source_root / "ui/settings/SleepTimerSelection.kt").read_text()
require("SleepTimerSheet(remainMs = remainMs, onApply = onMinutes, onDismiss = onDismiss)" in sleep_source
        and "remember" not in sleep_source,
        "active player sleep entry must delegate to the shared Settings picker, not own a second draft")
require("SLEEP_MAX_MINUTES = 1440" in sleep_model and "it in 1..SLEEP_MAX_MINUTES" in sleep_model
        # r658c C：睡眠定时抽屉不再有「自定义」入口；旧存值仍夹到 1–1440 照常生效。
        and "自定义睡眠定时" not in sleep_sheet and "showCustom" not in sleep_sheet
        and "initial.coerceIn(SLEEP_MIN_MINUTES, SLEEP_MAX_MINUTES)" in sleep_sheet
        and "onApply(if (afterCurrent) -1 else snapped)" in sleep_sheet
        and "coerceIn(5, SLEEP_MAX_MINUTES)" in sleep_sheet
        and '"MAX 24H"' in sleep_sheet and 'SettingsSheetOption("1440", "24 小时")' in sleep_sheet and "scrollContent = true" in sleep_sheet,
        "shared sleep picker must retain 24h (preset + slider), no custom entry (legacy exact minutes clamped 1..1440), finish-current, and bounded scrolling")
require("SleepTimerSheetContent(" in sheet and "sleepMinutesFromRemain(sleepRemain)" in sheet,
        "legacy menu sleep level and status must consume the shared picker/model")
require("onPlaybackLink = onPlaybackLink" in sheet and '"播放取链链路"' in track_sheet
        and "onPlaybackLink: () -> Unit," in sheet
        # ACCEPT5（退出动画普查 P2）改钉：宿主回调不再同步清菜单显隐（退出动画站规），
        # 只落目标表面状态——本 require 的意图（开真取链层、非 no-op）不变，字面等严更新。
        and "onPlaybackLink = { showPlaybackLink = true }" in full
        and "if (showPlaybackLink)" in full
        and "PlayerPlaybackLinkSheet(song = song, onDismiss = { showPlaybackLink = false })" in full,
        "real player menu must open the existing current-song link-report sheet, never a no-op")
glyphs = (source_root / "ui/player/DefaultPlayerGlyphs.kt").read_text()
require('"menu_link" -> PlaybackLink' in glyphs,
        "link report action must have a real glyph and explicit semantic tint without unknown-key crashes")

require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, versionName, and versionCode must match 1.0.675 / 675")
require("scripts/player-menu-sheet-guard.sh" in workflow,
        "the player menu sheet guard must be registered in portable quality gates")
print(f"[player-menu-sheet-guard] PASS: menu sheet {len(sheet.splitlines())} LOC, lyric preview {len(preview.splitlines())} LOC, footer {len(footer.splitlines())} LOC, host {len(full.splitlines())} LOC, parent-owned state/effects, explicit callbacks, and synchronized {version}/658 markers")
PY
