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
source = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
repository = (base / 'PlaylistFeedRepository.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

compact = lambda text: ' '.join(re.sub(r'//[^\n]*', '', text).split())
main_compact = compact(main)
gate = '!ProviderRegistry.capability(src0, prefs).playlistDetail -> emptyList()'
start = main.find(gate)
end_token = 'fullDetail?.songs.orEmpty()'
end = main.find(end_token, start)
route = compact(main[start:end + len(end_token)]) if start >= 0 and end >= 0 else ''
expected_route = compact('''
    !ProviderRegistry.capability(src0, prefs).playlistDetail -> emptyList()
    else -> {
        fullDetail = if (src0 in MusicSourceRegistry.platformIds) {
            MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)
        } else {
            ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)
        }
        fullDetail?.songs.orEmpty()
''')
home_start = main.find('val canRemote = pl.id !in setOf("liked", "local", "history")')
home_end_token = 'else null'
home_end = main.find(home_end_token, home_start)
home_route = compact(main[home_start:home_end + len(home_end_token)]) if home_start >= 0 and home_end >= 0 else ''
expected_home_route = compact('''
    val canRemote = pl.id !in setOf("liked", "local", "history") &&
        ProviderRegistry.capability(src0, prefs).playlistDetail &&
        (pl.id.all { it.isDigit() } || pl.id.length >= 6)
    if (canRemote) {
        if (src0 in MusicSourceRegistry.platformIds) {
            MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)
        } else {
            ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)
        }
    } else null
''')
search_start_token = 'val songs = if (initialSongs.size >= 20 && src0 != "tx") initialSongs else withContext(Dispatchers.IO) {'
search_end_token = 'fullDetail?.songs.orEmpty().ifEmpty { initialSongs }'
search_start = main.find(search_start_token)
search_end = main.find(search_end_token, search_start)
search_route = compact(main[search_start:search_end + len(search_end_token)]) if search_start >= 0 and search_end >= 0 else ''
expected_search_route = compact('''
    val songs = if (initialSongs.size >= 20 && src0 != "tx") initialSongs else withContext(Dispatchers.IO) {
        fullDetail = if (src0 in MusicSourceRegistry.platformIds) {
            MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(pl.id, pl)
        } else {
            ApiClient.playlistDetailSnapshot(pl.id, src0, pl)
        }
        fullDetail?.songs.orEmpty().ifEmpty { initialSongs }
''')
rank_start = main.find('pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> {')
rank_end = main.find('pl.id.startsWith("hot_")', rank_start)
rank_route = compact(main[rank_start:rank_end]) if rank_start >= 0 and rank_end >= 0 else ''
expected_rank_route = compact('''
    pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> {
        val rid = pl.id.removePrefix("rank_")
        if (src0 in MusicSourceRegistry.platformIds) {
            MusicSourceRegistry.platformSource(src0).rank(rid).ifEmpty {
                MusicSourceRegistry.platformSource(src0).playlistDetail(rid)
            }
        } else {
            ApiClient.rankDetail(rid, src0).ifEmpty { ApiClient.playlistDetail(rid, src0) }
        }
    }
''')
ids = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids.group(1)) if ids else []
api_start = api.find('suspend fun playlistDetailSnapshot(id: String, source: String, seed: Playlist): Playlist? {')
api_end = api.find('\n    /** 批17 前', api_start)
api_method = api[api_start:api_end] if api_start >= 0 and api_end >= 0 else ''
playlist_api_start = api.find('suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {')
playlist_api_end = api.find('\n    /** Same-provider metadata', playlist_api_start)
playlist_api_method = api[playlist_api_start:playlist_api_end] if playlist_api_start >= 0 and playlist_api_end >= 0 else ''
expected_playlist_api_method = compact('''
    suspend fun playlistDetail(id: String, source: String = "wy"): List<Song> {
        runDirectFallback { fetchPlaylistDetailDirect(source, id.removePrefix("reco_").trim()) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return playlistDetailServer(id, source)
    }
''')

checks = {
    'MusicSource adds a default legacy List route without requiring other adapters':
        'suspend fun playlistDetail(id: String): List<Song> = emptyList()' in source,
    'PlatformSourceAdapter forwards the original ID and fixed platform to ApiClient':
        'api.playlistDetail(id, source = platform)' in adapter,
    'ApiClient legacy List route keeps direct-first and the same server fallback':
        bool(playlist_api_method) and compact(playlist_api_method) == expected_playlist_api_method,
    'ApiClient snapshot direct-first, server fallback, and empty-result semantics remain intact':
        bool(api_method) and all(token in api_method for token in (
            'runDirectFallback { fetchPlaylistDetailPayloadDirect(source, rawId) }',
            'direct?.songs?.takeIf { it.isNotEmpty() } ?: playlistDetailServer(id, source)',
            'if (songs.isEmpty()) return null',
            'return seed.copy(',
        )),
    'only the gated ordinary detail route uses Registry for the five exact platform IDs':
        route == expected_route and platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'seeded home detail enrichment preserves its canRemote and ID-shape gate':
        home_route == expected_home_route and platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'search detail preserves its cache/seed threshold and exact platform routing':
        search_route == expected_search_route and platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg']
        and 'val src0 = pl.source.ifEmpty { "wy" }' in main_compact,
    'rank detail preserves the ProviderRegistry gate and raw ID/source while delegating only its List fallback':
        rank_route == expected_rank_route and 'val src0 = pl.source.ifEmpty { homePlatform }' in main_compact,
    'rank_/hot_ routes, homeFeed.multiHot[src0] state source, cache keys, cache reads/writes, merge, empty-list and loading boundaries remain present':
        all(token in main_compact for token in (
            'pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> {',
            'ApiClient.rankDetail(rid, src0).ifEmpty { ApiClient.playlistDetail(rid, src0) }',
            'pl.id.startsWith("hot_") -> homeFeed.multiHot[src0].orEmpty().ifEmpty {',
            'ApiClient.hot(src0).take(40)',
            'val remotePlaylistId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }',
            'HomeFeedCache.loadDetailSnapshotOnIo(context, remotePlaylistId, src0)',
            'ttlMs = detailTtl',
            'forceRefresh = pl.id.startsWith("hot_")',
            'HomeFeedCache.saveDetail(context, remotePlaylistId, src0, songs)',
            'val base = fullDetail ?: displayPlaylist',
            'if (songs.isNotEmpty()) {',
            'if (plDetail?.id == remotePlaylistId) plDetailLoading = false',
        )),
    'PlaylistFeedRepository remains on category/feed work without a playlist-detail request':
        not re.search(r'\b(?:playlistDetail|playlistDetailSnapshot)\s*\(', repository),
    'home/search snapshot call counts and legacy API fallbacks remain stable':
        main.count('ApiClient.playlistDetailSnapshot(') == 3
        and main.count('MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)') == 2
        and main.count('MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(pl.id, pl)') == 1,
    'matrix records the exact List and snapshot delegates plus noncanonical fallback semantics':
        all(token in matrix for token in (
            'ApiClient.playlistDetail(id, source = platform)',
            'platformSource(src0).playlistDetail(rid)',
            'ApiClient.playlistDetail(rid, src0)',
            'api.playlistDetailSnapshot(id, source = platform, seed = fallback)',
            'ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)',
            '服务端也无歌曲时返回 `null`',
        )),
    'version declarations remain synchronized':
        ('versionName "' + version + '"' in build)
        and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('MainActivity playlist-detail Registry contract guard passed')
PY
