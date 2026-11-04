#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
main = (base / 'MainActivity.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
source_interface = (base / 'source/MusicSource.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start_marker = 'pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> {'
end_marker = 'pl.id.startsWith("hot_") ->'
start = main.find(start_marker)
end = main.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: MainActivity rank-detail branch boundary is missing')
branch = main[start:end]
route = re.sub(r'//[^\n]*', '', branch)
normalized = ' '.join(route.split())
detail_start_marker = '    suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {'
detail_end_marker = '    /** Same-provider metadata plus its actual song list'
detail_start = api.find(detail_start_marker)
detail_end = api.find(detail_end_marker, detail_start)
if detail_start < 0 or detail_end < 0:
    raise SystemExit('FAIL: ApiClient playlist-detail route boundary is missing')
playlist_detail_route = api[detail_start:detail_end]
expected_route = (
    'pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> { '
    'val rid = pl.id.removePrefix("rank_") '
    'if (src0 in MusicSourceRegistry.platformIds) { '
    'MusicSourceRegistry.platformSource(src0).rank(rid).ifEmpty { '
    'MusicSourceRegistry.platformSource(src0).playlistDetail(rid) } } else { '
    'ApiClient.rankDetail(rid, src0).ifEmpty { '
    'ApiClient.playlistDetail(rid, src0) } } }'
)

ids_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids_match.group(1)) if ids_match else []

checks = {
    'the original ProviderRegistry rank gate and rid extraction are unchanged': normalized == expected_route,
    'only exact canonical IDs use the Source adapter; all other IDs retain the raw legacy request and fallback': platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'] and 'if (src0 in MusicSourceRegistry.platformIds)' in normalized and 'ApiClient.rankDetail(rid, src0)' in normalized and 'ApiClient.playlistDetail(rid, src0)' in normalized,
    'the adapter and MusicSource contract return the same List<Song> rank result': 'suspend fun rank(id: String): List<Song>' in source_interface and 'override suspend fun rank(id: String): List<Song>' in adapter,
    'canonical playlist-detail fallback delegates through the adapter to the same ApiClient source and List result': 'suspend fun playlistDetail(id: String): List<Song> = emptyList()' in source_interface and 'override suspend fun playlistDetail(id: String): List<Song> =\n        api.playlistDetail(id, source = platform)' in adapter and 'suspend fun playlistDetail(id: String, source: String = "wy"): List<Song>' in api,
    'ApiClient playlist detail keeps its original direct-first request and server fallback': 'fetchPlaylistDetailDirect(source, id.removePrefix("reco_").trim())' in playlist_detail_route and 'return playlistDetailServer(id, source)' in playlist_detail_route,
    'the Registry constructs the adapter with the ApiClient catalog gateway and source ID': 'api = ApiClientCatalogGateway' in registry and 'platform = id' in registry,
    'PlatformSourceAdapter.rank delegates to the identical rankDetail API/source': 'override suspend fun rank(id: String): List<Song> {\n        return api.rankDetail(id, source = platform)\n    }' in adapter,
    'ApiClient.rankDetail has the same suspend List<Song> signature and existing direct/server fallback': 'suspend fun rankDetail(id: String, source: String = "wy"): List<Song>' in api and 'return rankDetailServer(id, source)' in api,
    'raw source selection, remote ID, TTL-aware detail cache read/write, and existing UI result gate remain': 'val src0 = pl.source.ifEmpty { homePlatform }' in main and 'val remotePlaylistId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }' in main and 'HomeFeedCache.loadDetailSnapshotOnIo(context, remotePlaylistId, src0)' in main and 'ttlMs = detailTtl' in main and 'HomeFeedCache.saveDetail(context, remotePlaylistId, src0, songs)' in main and 'if (songs.isNotEmpty()) {' in main and 'if (plDetail?.id != remotePlaylistId) return@launch' in main,
    'the capability matrix documents this exact route and preserved fallback/boundaries': '## MainActivity 榜单歌单详情的 Source Registry 迁移点' in matrix and '非规范 source 保留原样' in matrix and 'ApiClient.playlistDetail(rid, src0)' in matrix and 'loading/空态、导航' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}

for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('MainActivity rank-detail Registry contract guard passed')
PY
