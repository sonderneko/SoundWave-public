#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAIN="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
API="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt"
python3 - "$MAIN" "$MATRIX" "$ADAPTER" "$API" <<'PY'
from pathlib import Path
import sys
main_path, matrix_path, adapter_path, api_path = map(Path, sys.argv[1:])
main = main_path.read_text()
matrix = matrix_path.read_text()
adapter = adapter_path.read_text()
api = api_path.read_text()
start_marker = 'val pl = if (directSong != null) {'
end_marker = '// 批30-1：切走守卫'
start = main.find(start_marker)
end = main.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: discovery detail request boundary is missing')
block = main[start:end]
api_start = api.find('suspend fun albumDetail(id: String, source: String = "wy"): Playlist? {')
api_end = api.find('\n    }', api_start)
api_block = api[api_start:api_end] if api_start >= 0 and api_end > api_start else ''
checks = {
    'fresh nonempty cache uses the shared TTL policy before the album request': 'HomeFeedCache.loadDetailSnapshotOnIo(context, card.id, src)' in block and 'resolveDetailCache(' in block and block.find('loadDetailSnapshotOnIo') < block.find('fetchDetail = {'),
    'all card kinds use Registry only for exact canonical IDs': 'if (src in MusicSourceRegistry.platformIds)' in block and '!card.kind.equals("new_song", true)' not in block,
    'adapter forwards the same album ID and source to ApiClient': 'api.albumDetail(id, source = platform)' in adapter,
    'ApiClient retains the same direct-first and server-fallback path': 'fetchAlbumDetailDirect(source, id)' in api_block and 'return albumDetailServer(id, source)' in api_block,
    'other sources retain the old ApiClient route': 'ApiClient.albumDetail(card.id, src)' in block,
    'cache hit does not rewrite the TTL and stale cache can survive an empty response': 'usedFreshCache = true' in block and 'if (songs.isNotEmpty() && !usedFreshCache)' in main[end:] and 'ifEmpty { cachedDetailSongs }' in main[end:],
    'navigation-away guard remains after request': 'if (plDetail?.id != card.id) return@launch' in main[end:end + 220],
    'detail fill still preserves directSong and returned songs': 'val songs = directSong?.let { listOf(it) } ?: pl?.songs.orEmpty().ifEmpty { cachedDetailSongs }' in main[end:end + 1_000],
    'matrix records all-kind canonical routing and the unchanged request semantics': '`MainActivity.onOpenDiscover`' in matrix and '`new_song`' in matrix and '该原始 `src` 精确命中 canonical 五平台 ID' in matrix and 'directSong' in matrix,
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
if block.count('MusicSourceRegistry.platformSource(src).albumDetail(card.id)') != 1:
    raise SystemExit('FAIL: expected exactly one Registry album-detail call in the discovery request')
if block.count('ApiClient.albumDetail(card.id, src)') != 1:
    raise SystemExit('FAIL: expected exactly one legacy fallback call in the discovery request')
print('Home album-detail Registry contract guard passed')
PY
