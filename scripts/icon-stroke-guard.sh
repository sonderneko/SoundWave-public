#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
from collections import Counter
import re
import sys

root = Path(sys.argv[1])
main_src = root / 'app/src/main/java'
drawable_dir = root / 'app/src/main/res/drawable'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[icon-stroke-guard] FAIL: {message}')


# PB-B1 icon spec (DesignScale.kt IconSpec): the only baseline stroke for
# self-drawn glyphs is 1.8 (Round cap/join). Two solid-button variants are
# filed as documented exceptions in the spec text; the remaining off-baseline
# values were pre-existing stock that PB-B3 (wave 2) normalised to 1.8; the
# ratchet list is empty now, so any off-baseline stroke outside the two
# documented exceptions fails. (Ratchet mechanics are kept for future stock:
# entries are keyed by (file, value) with a max count; removing one is always
# fine, and a fix must delete its ratchet entry in the same change.)
BASELINE = 1.8

# Documented exceptions (spec-filed, location-bound).
EXCEPTIONS = {
    # PauseBold: solid-button variant, thick pause bars.
    ('app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignChromeGlyphs.kt', 3.4): 1,
    # PlaySolid: solid triangle, 1.0 stroke only rounds the joins.
    ('app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignChromeGlyphs.kt', 1.0): 1,
}

# Ratchet: pre-existing stock pending normalisation (not exceptions).
# PB-B3 (wave 2) normalised every ratcheted value to 1.8 and the entries were
# deleted in the same change; the list is empty by design now -- any new
# off-baseline stroke fails.
RATCHET = {}

ALLOWLIST = dict(EXCEPTIONS)
ALLOWLIST.update(RATCHET)

# The spec itself must stay on file: the guard and the spec are one contract.
scale = main_src / 'xyz/cncnlp/soundwave/ui/common/DesignScale.kt'
require(scale.exists(), 'DesignScale.kt missing (PB-B1 spec home)')
scale_text = scale.read_text(encoding='utf-8', errors='replace')
require('PB-B1' in scale_text and 'object IconSpec' in scale_text,
        'PB-B1 icon spec (IconSpec) missing from DesignScale.kt')
require('PauseBold' in scale_text and 'PlaySolid' in scale_text,
        'PB-B1 documented exceptions (PauseBold / PlaySolid) missing from DesignScale.kt')

observed = Counter()

# Kotlin ImageVector glyphs: literal strokeLineWidth assignments.
kotlin_literal = re.compile(r'strokeLineWidth\s*=\s*(\d+(?:\.\d+)?)f?\b')
# The two glyph files whose stroke() helper takes a width argument: call-site
# overrides (width = Xf) and the helper default (width: Float = Xf) are glyph
# stroke values too. Scoped to these files so layout/Canvas widths elsewhere
# are never mistaken for glyph strokes.
helper_files = {
    'app/src/main/java/xyz/cncnlp/soundwave/ui/common/DesignChromeGlyphs.kt',
    'app/src/main/java/xyz/cncnlp/soundwave/ui/player/DefaultPlayerGlyphs.kt',
}
helper_call = re.compile(r'(?<!Line)width\s*=\s*(\d+(?:\.\d+)?)f\b')
helper_default = re.compile(r'width:\s*Float\s*=\s*(\d+(?:\.\d+)?)f\b')

for kt in sorted(main_src.rglob('*.kt')):
    rel = kt.relative_to(root).as_posix()
    text = kt.read_text(encoding='utf-8', errors='replace')
    for m in kotlin_literal.finditer(text):
        observed[(rel, float(m.group(1)))] += 1
    if rel in helper_files:
        for m in helper_call.finditer(text):
            observed[(rel, float(m.group(1)))] += 1
        for m in helper_default.finditer(text):
            observed[(rel, float(m.group(1)))] += 1

# Vector drawables in the 24x24 glyph viewport (108-canvas brand marks are a
# different calibre and out of the PB-B1 spec scope).
xml_stroke = re.compile(r'android:strokeWidth="(\d+(?:\.\d+)?)"')
for xml in sorted(drawable_dir.glob('*.xml')):
    text = xml.read_text(encoding='utf-8', errors='replace')
    if 'android:viewportWidth="24"' not in text or 'android:viewportHeight="24"' not in text:
        continue
    rel = xml.relative_to(root).as_posix()
    for m in xml_stroke.finditer(text):
        observed[(rel, float(m.group(1)))] += 1

offenders = []
for (rel, value), count in sorted(observed.items()):
    if value == BASELINE:
        continue
    allowed = ALLOWLIST.get((rel, value))
    if allowed is None:
        offenders.append(f'{rel}: stroke {value} not in PB-B1 whitelist (baseline 1.8 + spec-filed exceptions)')
    elif count > allowed:
        offenders.append(f'{rel}: stroke {value} occurs {count}x, allowlist max is {allowed} (ratchet must not grow)')
require(not offenders, 'off-baseline glyph stroke(s): ' + '; '.join(offenders))

print('[icon-stroke-guard] PASS')
PY
