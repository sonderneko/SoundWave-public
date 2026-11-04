#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "Theme/navigation/mini-player guard failed: $*" >&2; exit 1; }
require_string() {
  local file="$1" expected="$2"
  grep -F -q -- "$expected" "$file" || fail "expected theme-aware control color missing from $file: $expected"
}
reject_string() {
  local file="$1" forbidden="$2"
  if grep -F -n -- "$forbidden" "$file"; then
    fail "hard-coded ordinary control color remains in $file: $forbidden"
  fi
}
NAV=app/src/main/java/xyz/cncnlp/soundwave/BottomNav.kt
MINI=app/src/main/java/xyz/cncnlp/soundwave/MiniPlayer.kt
for file in "$NAV" "$MINI"; do
  [[ -f "$file" ]] || fail "target component missing: $file"
done

# accept6-switchfix deletion contract (supersedes the former 1.0.655 AppSwitch color-role pins at
# this spot, which covered only that file): AppSwitch.kt — the r654 Material3 Switch wrapper — had zero call sites and is
# deleted. The single switch implementation app-wide is SettingsSwitch
# (ui/settings/SettingsDetailKit.kt); AppSwitch must not return, and no raw Material3 Switch import may
# re-enter main sources (call sites migrate to SettingsSwitch instead).
[[ ! -f app/src/main/java/xyz/cncnlp/soundwave/AppSwitch.kt ]] || fail "AppSwitch.kt is back: SettingsSwitch is the single switch implementation"
if grep -rnE 'import androidx\.compose\.material3\.Switch$' app/src/main --include='*.kt'; then
  fail "raw Material3 Switch import in main sources: use SettingsSwitch"
fi

# Navigation surfaces are fixed, opaque Material surfaces with no opacity preference path.
require_string "$NAV" 'val designScheme = soundwaveDesignScheme(dark)'
require_string "$NAV" 'val selectedTint = designScheme.primary'
require_string "$NAV" 'designScheme.onSurfaceVariant'
# r657 修复: dock surfaces go through dockGlass (solid surface by default; the separate 底栏与迷你条磨砂 switch turns on a
# real backdrop blur with an 82% surface tint, or a ≥94% solid fallback on API ≤ 31 / without a blur source).
# The page frosted-background switch never reaches them.
require_string "$NAV" '.dockGlass(pillShape, designScheme.surface, dark, outline)'
require_string "$NAV" '.dockGlass(CircleShape, designScheme.surface, dark, outline)'
require_string "$NAV" '.dockGlass(androidx.compose.ui.graphics.RectangleShape, designScheme.surface, dark, Color.Transparent)'
# 2026-11-05 删除合同重钉（public-cleanup）：原锚定 DockedMiniChrome/SearchDockBar 的两条断言（navScheme 版 dockGlass、dockSurface 定义）随这两个零调用死代码面及其二阶死助手一并退役；活体底栏的 dockGlass 主题断言（pillShape/CircleShape/RectangleShape 三条）逐字不变。
GLASS=app/src/main/java/xyz/cncnlp/soundwave/ui/common/Glass.kt
require_string "$GLASS" '!dockFrosted -> false to 1f'
require_string "$GLASS" 'const val GLASS_FALLBACK_ALPHA = 0.94f'
require_string "$GLASS" 'if (alpha >= 1f) return clipped.background(opaque, shape)'
if grep -n 'pageFrosted' "$GLASS" | grep -n 'dockGlassPlan'; then fail "dockGlass must not read the page frosted switch"; fi
FROST=app/src/main/java/xyz/cncnlp/soundwave/ui/common/FrostedSurfaces.kt
require_string "$FROST" 'const val DOCK_FROSTED_MIN_ALPHA = 0.80f'
require_string "$FROST" 'if (!dockFrosted) return opaque'
if grep -n 'pageFrosted' "$FROST" | grep -n 'fun dockChromeFill'; then fail "dockChromeFill must not read the page frosted switch"; fi
if grep -E -n 'dockOpacity|pillAlpha' "$NAV"; then
  fail "bottom navigation still exposes or consumes a dynamic opacity value"
fi

# Mini-player copy, buttons, progress and artwork use semantic theme roles.
require_string "$MINI" 'val designScheme = soundwaveDesignScheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)'
require_string "$MINI" 'val miniText = MiniTokens.ink(designScheme)'
require_string "$MINI" 'val primary = MiniTokens.progress(designScheme)'
require_string "$MINI" 'val progressTrack = MiniTokens.progressTrack(designScheme)'
require_string "$MINI" '.dockGlass(cardShape, MiniTokens.surface(designScheme), isDarkMini, line)'
# r657 (v3 #14): no progress line; progress is a ring around the play button.
require_string "$MINI" 'drawArc(progressTrack'
require_string "$MINI" 'drawArc(primary'
require_string "$MINI" 'AsyncImage'
require_string "$MINI" 'CacheManager.coverModel'
if grep -E -n 'opacity' "$MINI"; then
  fail "mini-player still exposes or consumes a dynamic opacity value"
fi

for file in "$NAV" "$MINI"; do
  for forbidden in 'Color.White' 'Color.Black' 'Color(0x' 'ThemeTokens.PrimaryBlue' 'ThemeTokens.ProgressBlue'; do
    reject_string "$file" "$forbidden"
  done
done

echo "Theme/navigation/mini-player guard passed: controls use fixed Material surfaces; album artwork is retained."
