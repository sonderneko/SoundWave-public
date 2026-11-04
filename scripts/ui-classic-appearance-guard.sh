#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() {
  echo "UI appearance guard failed: $*" >&2
  exit 1
}

# r657 修复：用户要求底栏 / 迷你条真实磨砂（「加磨砂和模糊度，不要让背部内容透传过来」），haze 只允许出现在
# ui/common/Glass.kt（以及 build.gradle 的依赖行）；旧的 Dock 外观偏好 / 透明度通道仍然禁止。
if grep -R -n -E -- 'HazeState|HazeTint|hazeChild|\.haze\(|dev\.chrisbanes\.haze' app/src/main app/src/test | grep -v '^app/src/main/java/xyz/cncnlp/soundwave/ui/common/Glass.kt:'; then
  fail "haze may only be used inside ui/common/Glass.kt"
fi
FORBIDDEN='DockHazeSourceBox|LocalDockAppearance|rememberDockAppearance|DOCK_STYLE_|dock_appearance|frostedGlassSurface|frostedDockSurface|dockControlsUseFrostedAppearance'
if grep -R -n -E -i -- "$FORBIDDEN" app/src/main app/src/test app/build.gradle | grep -v '^app/src/main/java/xyz/cncnlp/soundwave/ui/common/Glass.kt:'; then
  fail "frosted-glass implementation, state, preference, or dependency remains"
fi

for obsolete in \
  app/src/main/java/xyz/cncnlp/soundwave/ui/DockAppearance.kt \
  app/src/test/java/xyz/cncnlp/soundwave/DockAppearanceTest.kt \
  scripts/ui-glass-guard.sh; do
  [[ ! -e "$obsolete" ]] || fail "obsolete frost-only file remains: $obsolete"
done

python3 - <<'PY'
from pathlib import Path
root = Path('.')
src = root / 'app/src/main/java/xyz/cncnlp/soundwave'
main_activity = (src / 'MainActivity.kt').read_text()
settings = (src / 'MePage.kt').read_text()
settings_sheets = (src / 'SettingsSheets.kt').read_text()
theme = (src / 'Theme.kt').read_text()

legacy_keys = (
    'theme_background_kind', 'theme_background_start', 'theme_background_middle',
    'theme_background_end', 'theme_accent', 'theme_mask_color', 'glass_bg_opacity',
    'glass_card_opacity', 'glass_mask_opacity', 'glass_bg_blur',
    'mini_bar_opacity', 'dock_bar_opacity',
)
all_main = '\n'.join(p.read_text() for p in (root / 'app/src/main/java').rglob('*.kt'))
for key in legacy_keys:
    if key in all_main:
        raise SystemExit(f'UI appearance guard failed: removed preference is still consumed/written: {key}')

removed_settings = (
    '卡片透明度', '背景遮罩', '背景模糊', '迷你条透明度', '底栏透明度',
    '背景类型', '背景开始颜色', '背景中间颜色', '背景结束颜色', '强调色',
    '遮罩颜色', '恢复本主题默认色', '琉璃鸢蓝', '开始 / 中间 / 结束三色渐变',
)
for label in removed_settings:
    if label in settings:
        raise SystemExit(f'UI appearance guard failed: removed setting still visible: {label}')

if 'themeMode == "glass"' in main_activity or 'glassTheme' in theme:
    raise SystemExit('UI appearance guard failed: legacy theme rendering branch remains')
if '"auto", "glass" -> "auto"' not in theme:
    raise SystemExit('UI appearance guard failed: saved glass mode must normalize to system auto')
if '.blur(bgBlur' in main_activity:
    raise SystemExit('UI appearance guard failed: global background blur remains')
if 'Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))' not in main_activity:
    raise SystemExit('UI appearance guard failed: app background must use the fixed Material role')
if 'val menuAlpha:' not in theme or 'val menuIconWhite:' not in theme:
    raise SystemExit('UI appearance guard failed: supported menu appearance preferences were removed')

# ACCEPT5（退出动画普查 S1）改钉：SongActionSheet 壳内补进/退场动画后，遮罩 alpha 随
# enterAnim 在 0→0.45 间同步淡变（静止态仍为固定 Material scrim 0.45）；钉子更新为新字面，
# 仍钉死 scrim 来源与 0.45 基准 alpha 不变。
if 'MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f * enterAnim.value)' not in (src / 'SongRow.kt').read_text():
    raise SystemExit('UI appearance guard failed: fixed Material scrim missing from SongRow.kt')
# dialog-polish 波（用户「整体对话框样式完善」批准口径）：MainActivity 歌单来源弹层（dialog-sweep 有据
# 保留的手工弹层）遮罩浓度由固定 0.45 改对齐 DesignSheet 家族外壳令牌 Sheet.ScrimAlpha（0.38）。
# 等严改钉：该弹层必须以令牌形态引用家族浓度（精确字面钉，强度不变）；SongRow 的固定 0.45 经典
# scrim 非对话框家族、不在本波范围，原钉保留（上方）。
# ACCEPT4 改钉（用户 2026-11-04 裁定，严格度不降）：该「播放来源」候选列表弹层已整套删除、不作任何
# 回退（出处只保留歌曲菜单内一行身份直达）。原钉所保护的对象不复存在，改钉删除契约——弹层状态与
# 入口不得在 MainActivity 复活；若未来重做来源弹层，必须重新过本守卫的令牌钉。
if 'showSourcePl' in main_activity or 'onSourcePlaylist' in main_activity:
    raise SystemExit('UI appearance guard failed: removed source-playlist sheet must not be resurrected')

# 对话框清扫（SW-12-08）与账号系统去除（account-removal）两方钉的并集（合并列车第 5 棒）：
# 本机资料弹层必须走共享 DesignSheetDialog 外壳、遮罩由 DesignSheet 的 SheetMotion.SCRIM_ALPHA
# 公式统一提供，且 AccountUi.kt 不得再引入原生 window.Dialog。
account_ui = (src / 'ui/common/AccountUi.kt').read_text()
design_sheet = (src / 'ui/common/DesignSheet.kt').read_text()
if 'DesignSheetDialog(' not in account_ui or 'androidx.compose.ui.window.Dialog' in account_ui or 'Color.Black.copy(alpha = SheetMotion.SCRIM_ALPHA' not in design_sheet:
    raise SystemExit('UI appearance guard failed: profile sheets must use the shared DesignSheetDialog scrim')

# r656：播放设备抽屉改用统一设计抽屉外壳（DesignSheetDialog 自带 40% 黑色遮罩）。
if 'DesignSheetDialog(onDismiss = onDismissRequest)' not in (src / 'ui/player/PlayerDeviceSheet.kt').read_text():
    raise SystemExit('UI appearance guard failed: device sheet must use the shared DesignSheetDialog scrim')

def require(condition, message):
    if not condition:
        raise SystemExit(f'UI appearance guard failed: {message}')


def section(source, start_marker, end_marker, label):
    start = source.find(start_marker)
    end = source.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    require(start >= 0 and end > start, f'{label} component boundary not found')
    return source[start:end]


# 1.0.655 (user item 12): on/off values render as the new text-free, outline-free SettingsSwitch; other values are
# rounded chips without the old underline. Every toggle still receives its live state.
right_button = section(settings, '    fun RightBtn(', '\n    @Composable\n    fun SettingGlyph(', 'RightBtn')
require('SettingsSwitch(checked = selected' in right_button and 'SettingsValueChip(' in right_button,
        'RightBtn must render on/off values as the new switch and other values as design chips')
for state in (
    'selected = prefetchEnabled',
    'selected = lyricWordKaraoke', 'selected = lyricWordLiftEnabled',
    'selected = lyricFloatEnabled', 'selected = !floatLocked.value',
    # r657 r3 (user 10-18): swipe-hide / auto-hide dock settings were removed from Settings by request.
    'selected = downloadMultiThread', 'selected = saveExtCover', 'selected = saveExtLyric',
    'selected = crossPlatformSearchUi', 'selected = scriptHttpCredentialGuardEnabled',
):
    require(state in settings, f'RightBtn must receive the current active state: {state}')
require('EditorialToggleRow("启动 APP 自动播放"' in settings and 'autoPlayOnStart, mark' in settings,
        'auto-play toggle must receive its live state')

setting_card = section(settings_sheets, 'internal fun SettingCard(',
                       'internal fun SleepTimerBarDialog(', 'SettingCard')
sleep_sheet = section(settings_sheets, 'internal fun SleepTimerBarDialog(',
                      'internal fun AddPlaylistCoverSheet(', 'SleepTimerBarDialog')
cover_sheet = section(settings_sheets, 'internal fun AddPlaylistCoverSheet(',
                       'internal fun ChoiceSheet(', 'AddPlaylistCoverSheet')
choice_styles = section(settings_sheets, 'internal enum class ChoiceOptionColorRole',
                        '@Composable\ninternal fun ChoiceSheet(', 'ChoiceOptionStyle')
choice_sheet = section(settings_sheets, 'internal fun ChoiceSheet(',
                       'internal fun fieldColors()', 'ChoiceSheet')
field_colors_start = settings_sheets.find('internal fun fieldColors()')
require(field_colors_start >= 0, 'fieldColors component boundary not found')
field_colors = settings_sheets[field_colors_start:]

require('color = MaterialTheme.colorScheme.onSurface' in setting_card and
        'color = MaterialTheme.colorScheme.onSurfaceVariant' in setting_card and
        'color = Muted' not in setting_card,
        'setting card text must use theme-aware primary and secondary text roles')
require('SleepTimerSheet(remainMs = remainMs, onApply = onMinutes, onDismiss = onDismiss)' in sleep_sheet,
        'legacy sleep entry must delegate to the shared theme-aware Settings picker')
sleep_shared = (root / 'app/src/main/java/xyz/cncnlp/soundwave/ui/settings/SettingsValueSheets.kt').read_text()
sleep_panel = (root / 'app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignSheet.kt').read_text()
require('DesignSheetDialog(onDismiss = onDismiss, scrollContent = true)' in sleep_shared and
        'color = colors.onSurface' in sleep_shared and
        '.sheetPanelGlass(shape, colors.surface)' in sleep_panel,
        'active sleep picker must use shared theme-aware surface/content roles')
# 对话框清扫（SW-12-08，用户批准形态变更）：封面选择器已由悬浮 Dialog 白卡迁 DesignSheet 弹层，
# surface 角色改由家族外壳面板提供（DesignSheet.kt，与睡眠选择器同源）；
# ACCEPT6 dialogfrost（用户 2026-11-04 批准形态变更）等严改钉：面板绘制由 frostedSheetFill 纯填充
# 改为 sheetPanelGlass 磨砂玻璃（仍以 colors.surface 为罩色源、页内独立渲染分支保留 frostedSheetFill）；
# 改钉口径：走 DesignSheetDialog ∧ 外壳面板以 colors.surface 驱动 sheetPanelGlass ∧ 本体 onSurface 内容角色不变。
require('DesignSheetDialog(' in cover_sheet and
        '.sheetPanelGlass(shape, colors.surface)' in sleep_panel and
        'val on = MaterialTheme.colorScheme.onSurface' in cover_sheet,
        'playlist cover picker must use Material surface and content roles')
for token in (
    'val colors = MaterialTheme.colorScheme',
    'val sheetBg = colors.surface',
    'color = colors.onSurface',
    'color = colors.onSurfaceVariant',
):
    require(token in choice_sheet, f'ChoiceSheet theme-aware role missing: {token}')
for token in (
    'ChoiceOptionColorRole.PRIMARY -> colors.primary',
    'ChoiceOptionColorRole.SECONDARY -> colors.secondary',
    'ChoiceOptionColorRole.TERTIARY -> colors.tertiary',
    'ChoiceOptionColorRole.ERROR -> colors.error',
):
    require(token in choice_styles, f'ChoiceSheet semantic tint role missing: {token}')
# 设计稿 v2：选项图标单色（ink），当前项用珊瑚设计勾。
for token in ('val optionTint = colors.onSurface', '.heightIn(min = 54.dp)',
              'DesignLineGlyphs.Check, "当前选项"'):
    require(token in choice_sheet, f'ChoiceSheet transparent row behavior missing: {token}')
require('.background(colors.surfaceVariant)' not in choice_sheet,
        'ChoiceSheet option rows must remain transparent')
for token in ('focusedBorderColor = MaterialTheme.colorScheme.primary',
              'unfocusedBorderColor = MaterialTheme.colorScheme.outline',
              'focusedTextColor = MaterialTheme.colorScheme.onSurface',
              'unfocusedTextColor = MaterialTheme.colorScheme.onSurface',
              'cursorColor = MaterialTheme.colorScheme.primary',
              'focusedLabelColor = MaterialTheme.colorScheme.primary',
              'unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant',
              'focusedContainerColor = MaterialTheme.colorScheme.surface',
              'unfocusedContainerColor = MaterialTheme.colorScheme.surface'):
    require(token in field_colors, f'fieldColors theme-aware role missing: {token}')
for literal in ('Color.White', 'Color.Black', 'Color.Blue', 'Color(0x'):
    require(literal not in choice_sheet and literal not in field_colors,
            f'generic settings options/inputs contain a hard-coded color: {literal}')

if '.blur(if (fullBleedArtwork)' not in (src / 'FullPlayer.kt').read_text():
    raise SystemExit('UI appearance guard failed: existing cover-art background presentation was removed')
PY

require_match() {
  local file="$1" pattern="$2"
  grep -E -q -- "$pattern" "$file" || fail "expected classic/non-frost behavior missing from $file"
}
require_match app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerTransitionBackdrop.kt '\.blur\(IMMERSIVE_PLAYER_BACKDROP_BLUR_DP'
require_match app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt '\.blur\(if \(adaptiveDetailStyle\) IMMERSIVE_PLAYER_BACKDROP_BLUR_DP'
require_match app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt 'fadeArtworkToBackdrop'
# 2026-11-05 删除合同重钉（public-cleanup）：BottomNav 的 neumorphicSurface 断言随 SearchDockBar（其最后消费者）删除而退役，Theme.kt 的 neumorphicSurface 助手已作二阶死代码删除；其余经典/非磨砂断言逐字不变。
require_match app/src/main/java/xyz/cncnlp/soundwave/MiniPlayer.kt 'dockGlass\(cardShape'  # r657 修复：迷你条表面统一走 dockGlass（默认实色）
# 1.0.655: the About row now carries the version inside its theme-coloured NavRow subtitle.
if ! grep -F -q 'NavRow("关于 APP", "版本 ${BuildConfig.VERSION_NAME}' app/src/main/java/xyz/cncnlp/soundwave/MePage.kt; then
  fail "About APP row must show the current version"
fi
python3 - <<'PY'
from pathlib import Path

source = Path('app/src/main/java/xyz/cncnlp/soundwave/SettingsSupportPages.kt').read_text()  # r656: About page moved
action = Path('app/src/main/java/xyz/cncnlp/soundwave/AboutUpdateAction.kt').read_text()  # r675: button extracted into AboutUpdateAction
# r657 r3b: About page follows v5 pAbout -- a coral gradient pill (not a Material Button), still white foreground.
if 'AboutUpdateAction(updateStatus = updateStatus, onCheckUpdate = onCheckUpdate)' not in source:
    raise SystemExit('UI appearance guard failed: About page must host the extracted check-update action')
start = action.find('internal fun AboutUpdateAction(')
end = action.find('updateStatus.ifEmpty', start)
if start < 0 or end < 0:
    raise SystemExit('UI appearance guard failed: check-update button boundary missing')
button = action[start:end]
if ('"检查更新"' not in button or 'color = Color.White' not in button or
        'MaterialTheme.colorScheme.onPrimary' in button or
        'role = Role.Button' not in button or 'onClick = onCheckUpdate' not in button):
    raise SystemExit('UI appearance guard failed: check-update button must use the APK white foreground')
print('[ui-classic-appearance-guard] PASS: check-update button uses the APK white foreground')
PY

echo "UI appearance guard passed: custom theme controls removed, legacy mode normalized, Material scrims and independent cover effects retained."
