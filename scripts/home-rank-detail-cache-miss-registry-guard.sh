#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_PAGE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt"
HOT_DETAIL="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/HomeHotBoardDetail.kt"
SELECTED_DETAIL="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/SelectedRankDetailLoader.kt"
SELECTED_DETAIL_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/SelectedRankDetailLoaderTest.kt"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
API="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"
BUILD="$ROOT/app/build.gradle"
VERSION="$ROOT/VERSION"
python3 - "$HOME_PAGE" "$HOT_DETAIL" "$SELECTED_DETAIL" "$SELECTED_DETAIL_TEST" "$REGISTRY" "$ADAPTER" "$API" "$MATRIX" "$BUILD" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

home_path, hot_detail_path, selected_detail_path, selected_detail_test_path, registry_path, adapter_path, api_path, matrix_path, build_path, version_path = map(Path, sys.argv[1:])
home = home_path.read_text()
hot_detail = hot_detail_path.read_text()
selected_detail = selected_detail_path.read_text()
selected_detail_test = selected_detail_test_path.read_text()
registry = registry_path.read_text()
adapter = adapter_path.read_text()
api = api_path.read_text()
policy = (selected_detail_path.parents[2] / 'DetailCachePolicy.kt').read_text()
matrix = matrix_path.read_text()
build = build_path.read_text()
version = version_path.read_text().strip()
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

start_marker = 'LaunchedEffect(platform, selectedBoard?.id) {'
end_marker = 'val uniqueRankSongs = remember(rankSongs)'
start = home.find(start_marker)
end = home.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: rank-detail effect boundary is missing')
effect = home[start:end]

match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
if match is None:
    raise SystemExit('FAIL: canonical platform ID declaration is missing')
platform_ids = re.findall(r'"([^"]+)"', match.group(1))
if platform_ids != ['wy', 'kg', 'kw', 'tx', 'mg']:
    raise SystemExit(f'FAIL: canonical platform IDs differ: {platform_ids!r}')

expected_route = [
    'if (source in MusicSourceRegistry.platformIds) {',
    'MusicSourceRegistry.platformSource(source).rank(id)',
    '} else {',
    'ApiClient.rankDetail(id, source)',
    '}',
]
normalized = [line.strip() for line in effect.splitlines()]
route_at = next((i for i in range(len(normalized) - len(expected_route) + 1)
                 if normalized[i:i + len(expected_route)] == expected_route), -1)
cache_hit_at = policy.find('if (cachedSongs.isNotEmpty() && cache.isFresh(ttlMs, now) && !forceRefresh)')
fetch_at = policy.find('return DetailCacheResolution.Refreshed(cachedSongs, fetchDetail())')
save_at = selected_detail.find('saveDetail(board.id, source, detail)')
if route_at < 0 or cache_hit_at < 0 or fetch_at < 0 or save_at < 0 or not cache_hit_at < fetch_at:
    raise SystemExit('FAIL: fresh cache must return before injected fetch; canonical source route remains the miss/force path')

checks = {
    'the helper source remains board.source with only its original blank fallback': 'val source = board.source.ifBlank { platform }' in selected_detail,
    'cache lookup and write keep rankContext, board.id, raw source, and the original 20-minute TTL': 'HomeFeedCache.loadDetailSnapshot(rankContext, id, source)' in effect and 'ttlMs = HomeFeedCache.ResourceTtl.RANK_DETAIL_MS' in selected_detail and 'HomeFeedCache.saveDetail(rankContext, id, source, songs)' in effect,
    'the exact source route appears only once and is not normalized': effect.count('MusicSourceRegistry.platformSource(source).rank(id)') == 1 and effect.count('ApiClient.rankDetail(id, source)') == 1 and all(token not in effect + selected_detail for token in ('.lowercase()', '.uppercase()', '.trim()', 'canonicalSourceId(')),
    'fresh hits skip network; stale entries publish before refresh; empty failures retain cached songs or preview': cache_hit_at < fetch_at and 'publishCached = publishCached' in selected_detail and 'resolution.cachedSongs.ifEmpty { board.songs }' in selected_detail and 'return detail' in selected_detail,
    'no board avoids I/O, preview seeds display, loading settles, and existing deduplicated display remains': 'if (board == null) return emptyList()' in selected_detail and 'rankSongs = board?.songs.orEmpty()' in effect and 'rankLoading = board != null' in effect and 'rankLoading = false' in effect and 'rankSongs.distinctBy { it.key() }' in home[end:end + 120],
    'the helper and its dedicated tests remain small, separate, and non-Compose policy units': len(selected_detail.splitlines()) < 200 and '@Composable' not in selected_detail and len(selected_detail_test.splitlines()) < 200 and 'loadSelectedRankDetail' in selected_detail_test,
    'deterministic JVM tests cover no-board, cache hit, cache-miss save, empty/exception fallback, switch isolation, and cancellation': all(label in selected_detail_test for label in ('no selected board', 'fresh cache', 'cache miss', 'empty or failed fetch', 'switching platform and board', 'cancellation is propagated')),
    'Registry passes the exact canonical id to the same ApiClient rank implementation': 'platform = id' in registry and 'return api.rankDetail(id, source = platform)' in adapter,
    'ApiClient rankDetail retains the same direct-first and server-fallback returns': 'fetchRankDirect(source.ifEmpty { "wy" }, id)' in api and '?.takeIf { it.isNotEmpty() }' in api and 'return rankDetailServer(id, source)' in api,
    'hot-board raw fallback remains separate from selected-rank policy and new-song Registry call remains': 'ApiClient.rankDetail(id, source)' not in selected_detail and hot_detail.count('ApiClient.rankDetail(id, source)') == 1 and hot_detail.count('MusicSourceRegistry.platformSource(source).rank(id)') == 1 and 'loadHomeNewSongExpress(nb)' in home and 'MusicSourceRegistry.platformSource(platform).rank(boardId)' in home,
    'the matrix records this exact cache-miss entry and preserves the existing hot-board entry': '榜单详情页：缓存 miss 后刷新完整歌曲' in matrix and 'LaunchedEffect(platform, selectedBoard?.id)' in matrix and 'HomePage` 缓存后刷新' in matrix,
    'all version declarations remain synchronized': version_match is not None and gradle_name is not None and gradle_code is not None and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Home rank-detail cache-miss Registry contract guard passed')
PY
