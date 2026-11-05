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
        raise SystemExit(f'[drag-lift-visual-guard] FAIL: {message}')


def strip_line_comments(text: str) -> str:
    # Call-site checks must not trip on prose mentions in // comments.
    return '\n'.join(line.split('//', 1)[0] for line in text.splitlines())


def read(rel: str) -> str:
    p = base / rel
    require(p.exists(), f'missing file {rel}')
    return strip_line_comments(p.read_text(encoding='utf-8', errors='replace'))


# Drag-lift visual contract (user feedback 2026-11-05): the shared lift shadow stays
# restrained (6dp). The original 18dp elevation approximation of the draft's CSS
# shadow, paired with translucent drag tints, bled through the row body and spilled
# past the row bounds as one oversized pink plate.
shared = read('ui/common/SharedMotion.kt')
lift = shared[shared.index('fun Modifier.dragLift'):]
require('if (active) 6.dp' in lift, 'dragLift elevation must stay restrained at 6.dp')
require('18.dp' not in lift, 'dragLift must not regress to the 18.dp oversized shadow')

# Every dragLift surface must lay an opaque page base under its drag tint, so the
# lift shadow can only halo outside the row, never tint the row body into a plate.
require(
    'Modifier.background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp))' in read('LibraryDesignRow.kt'),
    'LibraryDesignRow dragging row lost its opaque page base under the drag tint',
)
require(
    'background(if (draggingStage == id) MaterialTheme.colorScheme.background' in read('SmartSourcePage.kt'),
    'SmartSourcePage stage row lost its opaque page base under the drag tint',
)
require(
    'background(if (selected) MaterialTheme.colorScheme.background' in read('ScriptsPage.kt'),
    'ScriptsPage script row lost its opaque page base under the drag tint',
)

print('[drag-lift-visual-guard] PASS')
PY
