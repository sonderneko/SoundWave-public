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
        raise SystemExit(f'[motion-adoption-guard] FAIL: {message}')


def strip_line_comments(text: str) -> str:
    # Call-site checks must not trip on prose mentions in // comments.
    return '\n'.join(line.split('//', 1)[0] for line in text.splitlines())


def read(rel: str) -> str:
    p = base / rel
    require(p.exists(), f'missing file {rel}')
    return strip_line_comments(p.read_text(encoding='utf-8', errors='replace'))


# Shared pieces exist in the one shared file (ACCEPT6-motiondraft, v8 稿采纳基建).
shared = read('ui/common/SharedMotion.kt')
require('fun AnimatedCheckMark' in shared, 'AnimatedCheckMark missing from SharedMotion.kt')
require('fun Modifier.popOnBecomeTrue' in shared, 'popOnBecomeTrue missing from SharedMotion.kt')
require('fun Modifier.dragLift' in shared, 'dragLift missing from SharedMotion.kt')

# M1: default-player seek bar grows thumb/track while scrubbing.
parts = read('ui/player/DefaultPlayerParts.kt')
require('seekDragging = true' in parts, 'DefaultPlayerSeekBar lost its drag state')
require('1f + 0.4f * dragT' in parts, 'DefaultPlayerSeekBar lost thumb drag growth')

# M1b: legacy progress + volume sizes animate instead of hard-switching.
controls = read('PlayerControls.kt')
require(controls.count('animateDpAsState') >= 4,
        'PlayerControls legacy seek/volume size animation regressed')

# M2: in-page selection check marks pop via the shared composable.
song_row = read('SongRow.kt')
require(song_row.count('AnimatedCheckMark(') >= 2, 'SongRow selection checks regressed')
require(read('DownloadsPage.kt').count('AnimatedCheckMark(') >= 1,
        'DownloadsPage selection check regressed')
require(read('DetailDesignPages.kt').count('AnimatedCheckMark(') >= 2,
        'DetailDesignPages selection checks regressed')

# M3: like buttons pop their body on becoming liked.
require('popOnBecomeTrue(state.liked)' in read('ui/player/DefaultPlayerMainPage.kt'),
        'player like button lost its pop')
require('popOnBecomeTrue(liked)' in song_row, 'SongRow like button lost its pop')

# M4: settings segmented control crossfades its selection colors.
kit = read('ui/settings/SettingsPageKit.kt')
require(kit.count('animateColorAsState') >= 2, 'SettingsSegmented color crossfade regressed')

# M5: reorder rows lift with a soft shadow while dragged.
require('dragLift(dragging' in read('LibraryDesignRow.kt'), 'LibraryDesignRow drag lift regressed')
require('dragLift(draggingStage' in read('SmartSourcePage.kt'), 'SmartSourcePage drag lift regressed')
require('dragLift(selected' in read('ScriptsPage.kt'), 'ScriptsPage drag lift regressed')

print('[motion-adoption-guard] PASS')
PY
