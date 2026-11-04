#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_PAGE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt"
DETAIL="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/HomeHotBoardDetail.kt"
SELECTED_DETAIL="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/SelectedRankDetailLoader.kt"
DETAIL_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/HomeHotBoardDetailTest.kt"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"
BUILD="$ROOT/app/build.gradle"
VERSION="$ROOT/VERSION"
python3 - "$HOME_PAGE" "$DETAIL" "$SELECTED_DETAIL" "$DETAIL_TEST" "$REGISTRY" "$ADAPTER" "$MATRIX" "$BUILD" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys
home_path, detail_path, selected_detail_path, test_path, registry_path, adapter_path, matrix_path, build_path, version_path = map(Path, sys.argv[1:])
home = home_path.read_text()
detail = detail_path.read_text()
selected_detail = selected_detail_path.read_text()
tests = test_path.read_text()
registry = registry_path.read_text()
adapter = adapter_path.read_text()
matrix = matrix_path.read_text()
build = build_path.read_text()
version = version_path.read_text().strip()
match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
if match is None:
    raise SystemExit('FAIL: canonical platform ID declaration is missing')
platform_ids = re.findall(r'"([^"]+)"', match.group(1))
expected_ids = ['wy', 'kg', 'kw', 'tx', 'mg']
if platform_ids != expected_ids:
    raise SystemExit(f'FAIL: canonical platform IDs differ: {platform_ids!r}')
normalized = [line.strip() for line in detail.splitlines()]
expected_route = [
    'if (source in MusicSourceRegistry.platformIds) {',
    'MusicSourceRegistry.platformSource(source).rank(id)',
    '} else {',
    'ApiClient.rankDetail(id, source)',
    '}',
]
route_at = next((i for i in range(len(normalized) - len(expected_route) + 1)
                 if normalized[i:i + len(expected_route)] == expected_route), -1)
checks = {
    'the source remains board.source with only the original blank fallback': 'val source = board.source.ifBlank { platform }' in detail,
    'selection remains owned by HomePage and its retry tick remains local': 'val platformHotBoard = remember(pranks, platform) { selectHomeHotBoard(platform, pranks) }' in home and 'var hotChartRetryTick by remember { mutableStateOf(0) }' in home,
    'the isolated effect is keyed by the complete platform/board request and retry': 'LaunchedEffect(requestKey, retryTick)' in detail and 'val requestKey = homeHotBoardDetailKey(platform, board)' in detail and 'remember(requestKey)' in detail and all(token in detail for token in ('val platform: String', 'val boardId: String', 'val source: String', 'val boardSongs: List<Song>')),
    'the selected request key carries platform, board ID, raw-source fallback, and board songs': 'platform = platform' in detail and 'boardId = board?.id.orEmpty()' in detail and 'source = board?.source?.ifBlank { platform }.orEmpty()' in detail and 'boardSongs = board?.songs.orEmpty()' in detail,
    'cache reads and writes keep the original board ID and raw source with the original rank TTL': 'HomeFeedCache.loadDetailSnapshot(context, id, source)' in detail and 'ttlMs = HomeFeedCache.ResourceTtl.RANK_DETAIL_MS' in detail and 'HomeFeedCache.saveDetail(context, id, source, songs)' in detail,
    'cached songs publish before resolveDetailCache and fresh cache hits skip the fetch': detail.find('publishCached = { songs ->') < detail.find('fetchDetail = {', detail.find('val resolution = resolveDetailCache(')) and 'DetailCacheResolution.CacheHit' in detail and 'resolveDetailCache(' in detail,
    'only an exact canonical source uses Registry and other sources keep the raw-source fallback': route_at >= 0 and all(token not in detail for token in ('.lowercase()', '.uppercase()', '.trim()', 'canonicalSourceId(')),
    'the platform adapter delegates to the same API with its canonical platform ID': 'return api.rankDetail(id, source = platform)' in adapter,
    'empty-response and loading/error outcomes remain intact': 'if (detail.isNotEmpty()) {' in detail and 'cached.songs.isEmpty() && board.songs.isEmpty()' in detail and 'loading = false' in detail and '暂未获取到当前平台热歌榜歌曲' in detail,
    'empty/invalid board selection clears its prior state': 'if (board == null || board.id.isBlank()) {' in detail and 'publish(HomeHotBoardDetailState(emptyList(), loading = false))' in detail,
    'deterministic tests cover success, fresh-cache skip, stale fallback, explicit retry, empty error, and isolation': all(token in tests for token in ('successful request replaces preview', 'fresh rank cache is displayed without an automatic network request', 'empty refresh retains stale cached songs', 'explicit retry refreshes a fresh cache', 'fully empty request produces the existing error state', 'changing platform or board starts from that selection')),
    'selection may canonicalize board source without rewriting the selected board': 'canonicalSourceId(board.source.ifBlank { source }) == source' in home,
    'the rank page delegates to its distinct helper with the raw-source fallback and the new-song Registry route stays distinct': 'loadSelectedRankDetail(' in home and 'val source = board.source.ifBlank { platform }' in selected_detail and home.count('ApiClient.rankDetail(id, source)') == 1 and 'loadHomeNewSongExpress(nb)' in home and 'MusicSourceRegistry.platformSource(platform).rank(boardId)' in home,
    'the matrix documents this hot-board route and the independent rank-page cache-miss route': 'HomePage' in matrix and 'retryTick' in matrix and 'board.source.ifBlank { platform }' in matrix and '首页榜单详情页：缓存 miss' in matrix and '有效缓存跳过自动请求' in matrix,
    'the helper remains a small focused component': len(detail.splitlines()) < 300,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
for value in ('netease', 'qq', 'WY', 'Wy', 'unknown', 'cloud', 'script:demo'):
    if value in platform_ids:
        raise SystemExit(f'FAIL: noncanonical source unexpectedly became a Registry ID: {value!r}')
if detail.count('MusicSourceRegistry.platformSource(source).rank(id)') != 1:
    raise SystemExit('FAIL: expected exactly one Registry route for the homepage hot-board detail')
if detail.count('ApiClient.rankDetail(id, source)') != 1:
    raise SystemExit('FAIL: expected exactly one raw-source legacy fallback in the homepage hot-board detail')
print('Home hot-board detail Registry contract guard passed')
PY
