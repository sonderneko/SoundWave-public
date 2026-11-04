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
controller = (base / 'library/PlaylistCountRefreshController.kt').read_text()
source = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
compact = lambda text: ' '.join(re.sub(r'//[^\n]*', '', text).split())

callback_start = main.find('onRefreshPlaylistCounts = {')
callback_end = main.find('onHeartMode = { pool -> startHeartMode(pool) }', callback_start)
callback = main[callback_start:callback_end] if callback_start >= 0 and callback_end >= 0 else ''
construction_start = main.find('val playlistCountRefresh = remember(scope) {')
construction_end = main.find('var localScanRunning', construction_start)
construction = main[construction_start:construction_end] if construction_start >= 0 and construction_end >= 0 else ''
ids = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids.group(1)) if ids else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)
checks = {
    'the refresh callback delegates to the extracted controller with the live bundle':
        'playlistCountRefresh.refresh(userBundle.playlists)' in callback
        and 'userBundle = applyPlaylistCountUpdates(userBundle, refreshed)' in callback
        and 'persistBundle(notify = true)' in callback
        and main.count('searchPlaylistsServer(') == 1,
    'the controller keeps the existing target filter (local, special IDs, blank source excluded)':
        '!it.local && it.id !in SPECIAL_PLAYLIST_IDS && it.source.isNotBlank()' in controller
        and 'SPECIAL_PLAYLIST_IDS = setOf("liked", "local", "history")' in controller,
    'the controller propagates cancellation and gates commits on a current owner/route context':
        'catch (cancelled: CancellationException)' in controller
        and 'throw cancelled' in controller
        and 'requestContext == currentContext()' in controller
        and 'playlistCountRefresh.invalidate()' in construction,
    'canonical-only dispatch preserves the raw ApiClient fallback and adds no source normalization':
        construction.count('MusicSourceRegistry.platformSource(source).searchPlaylistsServer(name, 20)') == 1
        and construction.count('ApiClient.searchPlaylists(name, source, 20)') == 1
        and 'source in MusicSourceRegistry.platformIds' in construction
        and all(token not in construction for token in ('.lowercase()', '.uppercase()', '.trim()', 'canonicalSourceId(')),
    'the interface exposes a server-only lookup without changing other adapters':
        'suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> = emptyList()' in source,
    'platform adapter forwards the same keyword, fixed source, and limit to ApiClient':
        'override suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> =\n'
        '        api.searchPlaylistsCancellable(keyword, source = platform, limit = limit)' in adapter,
    'ApiClient keeps its original server URL, blank-keyword fallback, parser, and List return path':
        'fun searchPlaylists(keyword: String, source: String = "", limit: Int = 100): List<Playlist>' in api
        and 'keyword.ifBlank { "推荐" }' in api
        and '&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}' in api
        and 'getScoped("/music/playlist?action=search&keyword=$q$srcQ&limit=$limit", calls = calls)' in api
        and 'return parsePlaylistsPayload(raw, source)' in api,
    'cancellable server lookup preserves cancellation rather than converting it to an empty result':
        'internal suspend fun searchPlaylistsCancellable' in api
        and 'catch (cancelled: kotlinx.coroutines.CancellationException)' in api
        and 'throw cancelled' in api,
    'the canonical platform boundary remains the exact five IDs':
        platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'matrix records why the direct-first playlist method is not used':
        '## MainActivity 本地歌单播放量刷新 Source Registry 迁移点' in matrix
        and '通用 `searchPlaylists()` 会先增加平台直连候选' in matrix,
    'VERSION and Gradle declarations remain synchronized':
        version_match is not None and gradle_name is not None and gradle_code is not None
        and gradle_name.group(1) == version
        and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('MainActivity playlist-count refresh Source Registry contract guard passed')
PY
