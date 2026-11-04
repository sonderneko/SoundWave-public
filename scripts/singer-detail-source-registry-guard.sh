#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
INTERFACE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
SCRIPT="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/ScriptSourceAdapter.kt"
API="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"
BUILD="$ROOT/app/build.gradle"
VERSION="$ROOT/VERSION"
python3 - "$MAIN" "$INTERFACE" "$REGISTRY" "$ADAPTER" "$SCRIPT" "$API" "$MATRIX" "$BUILD" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

(main_path, interface_path, registry_path, adapter_path, script_path,
 api_path, matrix_path, build_path, version_path) = map(Path, sys.argv[1:])
main = main_path.read_text()
interface = interface_path.read_text()
registry = registry_path.read_text()
adapter = adapter_path.read_text()
script = script_path.read_text()
api = api_path.read_text()
matrix = matrix_path.read_text()
build = build_path.read_text()
version = version_path.read_text().strip()
start = main.find('val openSingerCb: (Song) -> Unit = { s ->')
end = main.find('@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)', start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: openSingerCb boundary is missing')
block = main[start:end]
checks = {
    'source selection remains exact and unnormalized': 'val src0 = s.source.ifBlank { searchPlatform }' in block and 'if (src0 in MusicSourceRegistry.platformIds)' in block and all(token not in block for token in ('.lowercase()', '.uppercase()', '.trim()')),
    'canonical platform route calls only artistDetail': 'MusicSourceRegistry.platformSource(src0).artistDetail(s.id, s.name)' in block,
    'noncanonical route preserves exact legacy API arguments': 'ApiClient.singerSongs(s.id, s.name, src0)' in block,
    'placeholder Playlist and empty initial songs remain': 'val singerId = "singer_${src0}_${s.id}"' in block and 'plDetailSongs = emptyList()' in block,
    'stale-navigation guard and empty-state behavior remain': 'if (plDetail?.id != singerId) return@launch' in block and 'AppNotice.toast("暂无歌手歌曲")' in block,
    'interface expresses artist detail with an unsupported null default': 'suspend fun artistDetail(artistId: String, singerName: String): Playlist? = null' in interface,
    'platform adapter delegates directly with canonical source': 'return api.singerSongs(artistId, singerName, source = platform)' in adapter,
    'Script explicitly declares artist unsupported': 'supportsArtist = CapabilityLevel.UNSUPPORTED' in script and 'override suspend fun artistDetail(artistId: String, singerName: String): Playlist? = null' in script,
    'old empty-source server default remains untouched': 'source: String = "wy"' in api and 'val src = source.ifBlank { "wy" }' in api,
    'matrix records precise singer entry and source fallback': 'MainActivity.openSingerCb' in matrix and 'alias' in matrix and '仍原样走旧 `ApiClient.singerSongs(s.id, s.name, src0)`' in matrix,
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
    raise SystemExit(f'FAIL: platform ids are not the exact canonical set: {platform_ids!r}')
for value in expected_ids:
    if value not in platform_ids:
        raise SystemExit(f'FAIL: canonical id rejected: {value}')
for value in ('netease', 'kugou', 'qq', 'tencent', 'METing', 'meting', 'script:demo', 'cloud', '', 'WY', 'Wy'):
    if value in platform_ids:
        raise SystemExit(f'FAIL: noncanonical value unexpectedly routed through adapter: {value!r}')

if block.count('MusicSourceRegistry.platformSource(src0).artistDetail(s.id, s.name)') != 1:
    raise SystemExit('FAIL: expected exactly one canonical artistDetail call')
if block.count('ApiClient.singerSongs(s.id, s.name, src0)') != 1:
    raise SystemExit('FAIL: expected exactly one legacy singerSongs fallback')
if main.count('ApiClient.singerSongs(') != 1:
    raise SystemExit('FAIL: unexpected additional MainActivity singerSongs route')
print('Singer-detail Source Registry contract guard passed')
PY
