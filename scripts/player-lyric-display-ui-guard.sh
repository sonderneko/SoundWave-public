#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
actions = (base / "ui/player/PlayerLyricActionsSheet.kt").read_text()
full = (base / "FullPlayer.kt").read_text()
settings = (base / "MePage.kt").read_text()
main = (base / "MainActivity.kt").read_text()
preference = (base / "ui/player/LyricDisplayPreferences.kt").read_text()
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/LyricDisplayPreferencesTest.kt").read_text()

def require(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit(f"[player-lyric-display-ui-guard] FAIL: {message}")

require(
    # r658c P：长按歌词抽屉改用公共 DesignSheetDialog（贴屏幕底边、底部滑入，全局抽屉表面）；不再用 Material3 ModalBottomSheet。
    "DesignSheetDialog(" in actions
    and "ModalBottomSheet(" not in actions
    and 'title = "歌词纠错"' in actions
    and 'title = "翻译"' in actions
    and 'title = "罗马音"' in actions
    and 'title = "悬浮歌词"' in actions
    and actions.count('title = "') == 4
    # 统一歌词偏好（2026-11-04 合并）：字号 / 行高 / 偏移核心段与 ⋯ 菜单共用 PlayerLyricsSettingsCore，本表只调共享核心。
    and "PlayerLyricsSettingsCore(" in actions
    and "字号 A−" not in actions
    and "LazyColumn(" not in actions
    and "fillMaxSize()" not in actions,
    "long press must open the shared bottom sheet with the lyric actions and the unified lyric preference controls",
)
require(
    "ThemeTokens.PlayerBackground" not in actions
    and "selectedLine?.translation.isNullOrBlank()" in actions
    and "selectedLine?.romanization.isNullOrBlank()" in actions
    and "enabled = hasTranslation" in actions
    and "enabled = hasRomanization" in actions
    and "onClick = onToggleTranslation" in actions
    and "onClick = onToggleRomanization" in actions,
    "translation and romanization actions must use the existing independent display settings and require an existing track",
)
candidate_start = actions.index("correctionCandidates.forEachIndexed { index, candidate ->")
candidate_end = actions.index("\n                if (hasLyricCorrection)", candidate_start)
candidate_rows = actions[candidate_start:candidate_end]
require(
    # 对话框清扫（SW-12-08，用户批准形态变更）：纠错对话框已由 AlertDialog 迁 DesignSheet 弹层，
    # 形态断言等严反转——钉「无 AlertDialog 残留 ∧ 有 DesignSheetDialog」，候选行断言逐字不变。
    "AlertDialog(" not in actions
    and "DesignSheetDialog(" in actions
    and ".background(" not in candidate_rows
    and "MaterialTheme.colorScheme.onSurface" in candidate_rows
    and "MaterialTheme.colorScheme.onSurfaceVariant" in candidate_rows
    and "MaterialTheme.colorScheme.primary" in candidate_rows
    and "fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal" in candidate_rows,
    "candidate rows must have no fill, use light/dark theme colors, retain selected emphasis, and leave dialog/sheet surfaces intact",
)
require(
    "onLyricShowTranslation(!lyricShowTranslation)" in full
    and "onLyricShowRomanization(!lyricShowRomanization)" in full
    and "playerLyricCloseSelection(lyricBrowseState)" in full,
    "display actions must close the sheet without seeking or changing the selected player mode",
)
require(
    "PlayerLyricCorrectionDialog(" in full
    and "PlayerLyricCorrectionDialog(" in actions
    and "onSearchLyricCorrection()" in actions
    and "onApplyLyricCorrection(candidate)" in actions
    and "onClearLyricCorrection()" in actions
    and "similarity" in actions,
    "the compact menu must retain explicit correction candidate confirmation, similarity and undo",
)
require(
    'RowCard("显示歌词翻译"' in settings
    # r655：开关改为 SettingsSwitch（经 RightBtn 开/关值渲染，无文字无描边）。
    and 'RightBtn(if (lyricShowTranslation) "开" else "关", selected = lyricShowTranslation) { onLyricShowTranslation(!lyricShowTranslation) }' in settings
    and 'RowCard("显示歌词罗马音"' in settings
    and 'RightBtn(if (lyricShowRomanization) "开" else "关", selected = lyricShowRomanization) { onLyricShowRomanization(!lyricShowRomanization) }' in settings,
    "settings page must expose independent translation and romanization switches",
)
require(
    "resolveLyricDisplayPreferences(" in main
    and '"lyric_show_translation"' in main
    and '"lyric_show_romanization"' in main
    and "storedTranslation ?: false" in preference
    and "storedRomanization ?: false" in preference,
    "missing stored values default off while explicit saved booleans are loaded separately",
)
require(
    "missingPreferencesDefaultBothDisplayOptionsToOff" in test
    and "explicitlySavedValuesArePreservedIndependently" in test,
    "JVM tests must cover off defaults and preserving independent explicit choices",
)
print("[player-lyric-display-ui-guard] PASS: compact three-action sheet, existing translation/romanization tracks, correction confirmation, and preference persistence")
PY
