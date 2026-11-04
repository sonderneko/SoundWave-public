#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
API="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"
BUILD="$ROOT/app/build.gradle"
VERSION="$ROOT/VERSION"
python3 - "$MAIN" "$REGISTRY" "$ADAPTER" "$API" "$MATRIX" "$BUILD" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

main_path, registry_path, adapter_path, api_path, matrix_path, build_path, version_path = map(Path, sys.argv[1:])
main = main_path.read_text()
registry = registry_path.read_text()
adapter = adapter_path.read_text()
api = api_path.read_text()
matrix = matrix_path.read_text()
build = build_path.read_text()
version = version_path.read_text().strip()
start = main.find('onOpenAlbum = { song ->')
end = main.find('onOpenPl = { pl ->', start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: search onOpenAlbum boundary is missing')
block = main[start:end]
checks = {
    'only exact canonical song.source values route through Registry': 'if (song.source in MusicSourceRegistry.platformIds)' in block and 'MusicSourceRegistry.platformSource(song.source).albumDetail(song.albumId)' in block,
    'blank, alias, case-mismatched and other sources keep the exact legacy fallback': 'ApiClient.albumDetail(song.albumId, song.source.ifBlank { "wy" })' in block,
    'blank albumId still skips the detail request': 'val album = if (song.albumId.isNotBlank()) {' in block and '} else null' in block,
    'existing detail acceptance and same-album search fallback remain': 'val resolvedAlbum = album?.takeIf { albumSongs.isNotEmpty() }' in block and 'searchResults.filter {' in block and 'it.source == song.source && it.album.isNotBlank() && it.album == song.album' in block,
    'existing no-result message and search navigation origin remain': 'detailOriginTab = Tab.SEARCH' in block and 'AppNotice.toast("当前平台暂无专辑详情")' in block,
    'platform adapter delegates to the same ApiClient method with the fixed canonical source': 'return api.albumDetail(id, source = platform)' in adapter,
    'ApiClient album direct/server fallback implementation remains the unchanged return path': 'runDirectFallback { fetchAlbumDetailDirect(source, id) }?.let { return it }' in api and 'return albumDetailServer(id, source)' in api,
    'matrix records this exact route and fallback boundary': 'MainActivity.onOpenAlbum' in matrix and 'song.source.ifBlank { "wy" }' in matrix and '同专辑搜索结果回退' in matrix,
    'audit baseline matches the Source matrix recorded at migration time': '02d699c3649ca761f71fec073b15c2063c26e018' in matrix and '1.0.499 / 499' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')

match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
if match is None:
    raise SystemExit('FAIL: canonical platform id declaration is missing')
platform_ids = re.findall(r'"([^"]+)"', match.group(1))
expected_ids = ['wy', 'kg', 'kw', 'tx', 'mg']
if platform_ids != expected_ids:
    raise SystemExit(f'FAIL: canonical platform ids differ: {platform_ids!r}')
for value in ('', 'netease', 'kugou', 'qq', 'tencent', 'WY', 'Wy', 'METing', 'Meting', 'script:demo', 'cloud', 'unknown'):
    if value in platform_ids:
        raise SystemExit(f'FAIL: noncanonical source unexpectedly routes through Registry: {value!r}')

registry_call = 'MusicSourceRegistry.platformSource(song.source).albumDetail(song.albumId)'
legacy_call = 'ApiClient.albumDetail(song.albumId, song.source.ifBlank { "wy" })'
if block.count(registry_call) != 1 or block.count(legacy_call) != 1:
    raise SystemExit('FAIL: expected exactly one canonical route and one legacy fallback in onOpenAlbum')
print('Search album-detail Registry contract guard passed')
PY
