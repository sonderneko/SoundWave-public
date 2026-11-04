#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
main = (base / 'MainActivity.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start = main.find('pl.id.startsWith("hot_") ->')
end = main.find('seed.isNotEmpty() -> seed', start)
route = ' '.join(main[start:end].split()) if start >= 0 and end >= 0 else ''
expected = (
    'pl.id.startsWith("hot_") -> homeFeed.multiHot[src0].orEmpty().ifEmpty { '
    'if (src0 in MusicSourceRegistry.platformIds) { '
    'MusicSourceRegistry.platformSource(src0).hotSongs().take(40) '
    '} else { ApiClient.hot(src0).take(40) } }'
)
ids_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids_match.group(1)) if ids_match else []

checks = {
    'src0 remains the raw playlist source with the existing home fallback': 'val src0 = pl.source.ifEmpty { homePlatform }' in main,
    'hot detail uses the exact homeFeed cache-first path, strict canonical routing, and the exact legacy fallback': route == expected and platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'Registry hotSongs tries the injected direct hot provider, then the server-only catalog port': 'directHotSongs?.let { fetch ->' in adapter and 'api.hotServer(source = platform)' in adapter and 'api.hot(source = platform)' not in adapter,
    'matrix records the active MainActivity detail call and preserved boundaries': '## MainActivity 热门详情的 Source Registry 迁移点' in matrix and 'multiHot[src0].orEmpty()' in matrix and 'platformSource(src0).hotSongs().take(40)' in matrix and 'ApiClient.hot(src0).take(40)' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('MainActivity hot-detail Registry contract guard passed')
PY
