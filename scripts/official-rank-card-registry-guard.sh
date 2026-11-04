#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
card_path = base / 'OfficialRankCard.kt'
registry_path = base / 'source/MusicSourceRegistry.kt'
adapter_path = base / 'source/PlatformSourceAdapter.kt'
api_path = base / 'ApiClient.kt'
matrix_path = root / 'docs/source-capability-matrix.md'
build_path = root / 'app/build.gradle'
version_path = root / 'VERSION'
card = card_path.read_text()
registry = registry_path.read_text()
adapter = adapter_path.read_text()
api = api_path.read_text()
matrix = matrix_path.read_text()
build = build_path.read_text()
version = version_path.read_text().strip()

start_marker = 'LaunchedEffect(board.id, board.source) {'
end_marker = 'val title = MaterialTheme.colorScheme.onBackground'
start = card.find(start_marker)
end = card.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: OfficialRankCard detail effect boundary is missing')
effect = card[start:end]
normalized = [line.strip() for line in effect.splitlines()]
expected_route = [
    'if (board.source in MusicSourceRegistry.platformIds) {',
    'MusicSourceRegistry.platformSource(board.source).rank(board.id)',
    '} else {',
    'ApiClient.rankDetail(board.id, board.source)',
    '}',
]
route_at = next((i for i in range(len(normalized) - len(expected_route) + 1)
                 if normalized[i:i + len(expected_route)] == expected_route), -1)
registry_ids = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', registry_ids.group(1)) if registry_ids else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

checks = {
    'detail request remains conditional on fewer than three songs and a usable board ID': 'if (board.songs.size < 3 && board.id.isNotBlank())' in effect,
    'only exact canonical source IDs use Registry and all others preserve the raw ApiClient call': route_at >= 0 and effect.count('MusicSourceRegistry.platformSource(board.source).rank(board.id)') == 1 and effect.count('ApiClient.rankDetail(board.id, board.source)') == 1 and all(token not in effect for token in ('.lowercase()', '.uppercase()', '.trim()', 'canonicalSourceId(')),
    'fetched result, top-three cap and loading completion are unchanged': 'songs = fetched.take(3)' in effect and 'loading = false' in effect,
    'canonical platform set remains the exact five IDs': platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'Registry rank delegates to the same ApiClient rankDetail call': 'return api.rankDetail(id, source = platform)' in adapter and 'suspend fun rank(id: String): List<Song>' in (base / 'source/MusicSource.kt').read_text(),
    'ApiClient rankDetail keeps its existing direct-first/server-fallback behavior': 'suspend fun rankDetail(id: String, source: String = "wy"): List<Song>' in api and 'return rankDetailServer(id, source)' in api,
    'matrix records this precise OfficialRankCard entry': '## OfficialRankCard 缺曲补拉的 Source Registry 迁移点' in matrix and '`board.source` 精确属于 `MusicSourceRegistry.platformIds`' in matrix,
    'VERSION and Gradle declarations stay synchronized': version_match is not None and gradle_name is not None and gradle_code is not None and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('OfficialRankCard Source Registry contract guard passed')
PY
