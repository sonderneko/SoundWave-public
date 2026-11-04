#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
main_src = root / 'app/src/main/java/xyz/cncnlp/soundwave'


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[kw-disc-shelves-removed-guard] FAIL: {message}')


# accept6-kwfix deletion contract (user pre-authorization 2026-11-04): Kuwo's
# disc/shelves shelf held exactly one featured album across recordings
# (2026-10-23 and 2026-11-04), so kw no longer participates in home
# new_album discovery. The endpoint and its parser must not come back
# without a real multi-album upstream catalog. Evidence: WAVE-ACCEPT6-KWFIX.
offenders = []
for kt in main_src.rglob('*.kt'):
    text = kt.read_text(errors='ignore')
    if 'openapi/v1/pc/disc/shelves' in text or 'parseKwNewAlbums' in text:
        offenders.append(str(kt.relative_to(root)))
require(not offenders, f'kw disc/shelves new-album path resurrected in: {offenders}')

feed = (main_src / 'source/direct/FeedDirectFetch.kt').read_text()
require('"kw" -> fetchDiscoverSectionsConcurrently' in feed,
        'kw discover branch must remain (new-song express section is not part of the removal)')
kw_branch = feed.split('"kw" ->')[1].split('else ->')[0]
require('new_album' not in kw_branch, 'kw discover branch must not emit new_album cards')

print('[kw-disc-shelves-removed-guard] PASS')
PY
