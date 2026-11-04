#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[press-haptics-coverage-guard] FAIL: {message}')


kt_files = [p for p in base.rglob('*.kt')]


def strip_line_comments(text: str) -> str:
    # Call-site checks must not trip on prose mentions in // comments.
    return '\n'.join(line.split('//', 1)[0] for line in text.splitlines())


texts = {p: strip_line_comments(p.read_text(encoding='utf-8', errors='replace')) for p in kt_files}


def count_sites(needle: str, exclude_names=('AppHaptics.kt',)) -> int:
    return sum(t.count(needle) for p, t in texts.items() if p.name not in exclude_names)


# --- ACCEPT5-press contract (WAVE-ACCEPT5-PRESS) ---
# 1. Press-by-press haptics are gone from call sites: PressHaptic.Light (fires on pointer
#    down) may only remain in its definition file; feedback fires on confirmed state change.
light_sites = [str(p.relative_to(base)) for p, t in texts.items()
               if p.name != 'PressFeedback.kt' and 'PressHaptic.Light' in t]
require(not light_sites, f'PressHaptic.Light must have zero call sites outside PressFeedback.kt, found: {light_sites}')

# 2. Draft haptic classes are wired, not just defined (AppHaptics definitions excluded).
require(count_sites('AppHaptics.longPress(') >= 15,
        f"long-press haptics coverage regressed: {count_sites('AppHaptics.longPress(')} sites (< 15)")
require(count_sites('AppHaptics.dragTick(') >= 10,
        f"drag-cell haptics coverage regressed: {count_sites('AppHaptics.dragTick(')} sites (< 10)")
require(count_sites('AppHaptics.segmentTick(') >= 1,
        'slider detent haptics (segmentTick) must stay wired in PlayerLyricsSettingSlider')

# 3. Row-tier press scale on the two rows that used to eat the global Card tier.
queue = texts[base / 'ui/player/DefaultPlayerQueuePage.kt']
require('pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_ROW)' in queue,
        'queue rows must keep row-tier pressScale')
downloads = texts[base / 'DownloadsPage.kt']
require('PressMotion.SCALE_ROW' in downloads, 'download rows must keep row-tier pressScale')

# 4. Quick tiles use the draft card tier (0.965 family), not the row tier.
tiles = texts[base / 'ui/common/DesignQuickTiles.kt']
require('pressScale(PressMotion.SCALE_CARD)' in tiles, 'quick tiles must keep card-tier pressScale')

# 5. Like = confirm on light-up / tap on unlike, in both wired like surfaces.
main_page = texts[base / 'ui/player/DefaultPlayerMainPage.kt']
require('AppHaptics.confirm(hapticView)' in main_page, 'player like must keep confirm-on-light-up haptics')
sheet = texts[base / 'TrackActionsSheet.kt']
require('AppHaptics.confirm(hapticView)' in sheet, 'song-menu like tile must keep confirm-on-light-up haptics')

# 6. Home pull-to-refresh threshold haptic stays in the shared indicator.
views = texts[base / 'ui/home/HomeFeedStateViews.kt']
indicator = views[views.index('fun HomePullRefreshIndicator'):]
require('AppHaptics.thresholdCrossed' in indicator,
        'HomePullRefreshIndicator must keep the refresh-threshold haptic')

# 7. Settings rows (user ruling 2026-11-04, screenshot-named): press feedback is pressScale
#    ONLY — no full-row color wash. The retired group-color tint must not come back.
settings_rows = texts[base / 'ui/settings/SettingsRowInteraction.kt']
require('fun settingsPressTint' not in settings_rows and 'drawRect(bg' not in settings_rows,
        'settings rows must not paint a press color wash (settingsPressTint retired by the 2026-11-04 ruling)')
design_row = settings_rows[settings_rows.index('fun SettingsDesignRow'):]
require('pressScale(PressMotion.SCALE_ROW' in design_row.split('fun SettingsOuterToggle')[0],
        'SettingsDesignRow must keep row-tier pressScale')
feedback = settings_rows[settings_rows.index('fun Modifier.settingsPressFeedback'):]
require('pressScale(PressMotion.SCALE_ROW)' in feedback,
        'settingsPressFeedback (subpage rows) must stay pressScale-only')

print('[press-haptics-coverage-guard] PASS: press haptics fire on state change; long/drag/detent/threshold coverage wired; row/card tiers pinned')
PY
