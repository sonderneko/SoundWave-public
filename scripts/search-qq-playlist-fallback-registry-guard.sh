#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
search = (base / 'SearchController.kt').read_text()
models = (base / 'Models.kt').read_text()
source = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
direct = (base / 'source/direct/PlaylistDirectSearch.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
cache = (base / 'HomeFeedCache.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
compact = lambda text: ' '.join(re.sub(r'//[^\n]*', '', text).split())

start = search.find('    fun runSearch(')
next_methods = [p for p in (search.find('\n    fun ', start + 1),
                             search.find('\n    private fun ', start + 1),
                             search.find('\n    suspend fun ', start + 1)) if p >= 0]
end = min(next_methods) if next_methods else len(search)
run_search = search[start:end] if start >= 0 else ''

fallback_call = 'MusicSourceRegistry.platformSource("tx").searchPlaylistsServer(q, 100)'
old_call = 'ApiClient.searchPlaylists(q, source = "tx", limit = 100)'
search_api_start = api.find('    fun searchPlaylists(keyword: String, source: String = "", limit: Int = 100): List<Playlist> =')
search_api_end = api.find('\n    /** Category-page-only variant', search_api_start)
search_api = api[search_api_start:search_api_end] if search_api_start >= 0 and search_api_end >= 0 else ''
tx_parser_start = direct.find('internal fun parseTxPlaylistSearchStatus(raw: String): TxPlaylistSearchResult {')
tx_parser_end = direct.find('\n/**\n * 纯解析函数：mobilecdnbj', tx_parser_start)
tx_parser = direct[tx_parser_start:tx_parser_end] if tx_parser_start >= 0 and tx_parser_end >= 0 else ''

ids_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids_match.group(1)) if ids_match else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

checks = {
    'SearchController retains canonical source and non-empty trimmed-query gates':
        'val src = canonicalSourceId(source)' in run_search
        and 'val q = kw.trim()' in run_search
        and 'if (q.isEmpty()) return' in run_search
        and 'if (src != "tx")' in run_search
        and 'source.trim().equals("qq", ignoreCase = true)) "tx"' in models,
    'only the empty QQ direct-result branch uses the server-only Registry delegate':
        run_search.count('searchTxPlaylistResult(q, 100)') == 1
        and run_search.count(fallback_call) == 1
        and old_call not in run_search
        and run_search.count('MusicSourceRegistry.platformSource(p).searchPlaylists(q, p)') == 1
        and 'if (direct.playlists.isNotEmpty()) direct.playlists to true' in run_search
        and 'if (fallback.isNotEmpty()) fallback to true else direct.playlists to direct.available' in run_search,
    'source registry is the exact canonical five-platform set and the TX factory uses the shared ApiClient':
        platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg']
        and 'api = ApiClientCatalogGateway' in registry and 'platform = id' in registry,
    'interface and TX adapter preserve a List result and forward the exact keyword, source, and limit':
        'suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> = emptyList()' in source
        and 'override suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> =\n'
            '        api.searchPlaylistsCancellable(keyword, source = platform, limit = limit)' in adapter,
    'the ApiClient server route keeps query encoding, source/src, limit, parsing, and exception-to-empty behavior':
        bool(search_api)
        and 'keyword.ifBlank { "推荐" }' in search_api
        and 'if (source.isNotEmpty())' in search_api
        and '&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}' in search_api
        and 'getScoped("/music/playlist?action=search&keyword=$q$srcQ&limit=$limit", calls = calls)' in search_api
        and 'return parsePlaylistsPayload(raw, source)' in search_api
        and 'catch (cancelled: kotlinx.coroutines.CancellationException)' in search_api
        and 'throw cancelled' in search_api
        and 'catch (_: Exception)' in search_api and 'emptyList()' in search_api,
    'the direct QQ parser preserves unavailable versus valid-empty status and drops empty playlist IDs':
        bool(tx_parser)
        and 'raw.isBlank()' in tx_parser and 'available = false' in tx_parser
        and 'data.optJSONArray("list")' in tx_parser
        and 'id.isEmpty()' in tx_parser and 'continue' in tx_parser
        and 'available = true' in tx_parser,
    'server parser still rejects empty IDs and other-platform records for a TX request':
        'if (id.isEmpty()) continue' in api
        and 'if (requestedSource.isNotEmpty() && src != requestedSource) continue' in api,
    'cache key, read-before-network, generation guard, nonempty-only write, and UI availability state are unchanged':
        'val ck = HomeFeedCache.searchKey(q, mode, src, crossPlatformSearch)' in run_search
        and 'HomeFeedCache.loadSearch(context, ck, HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS)' in run_search
        and run_search.find('HomeFeedCache.loadSearch') < run_search.find('if (mode == "pl") {')
        and 'if (playlistResults.isNotEmpty() && isCurrent(generation))' in run_search
        and 'HomeFeedCache.saveSearch(context, ck, emptyList(), playlistResults)' in run_search
        and 'src == "tx" && !qqPlaylistAvailable' in run_search
        and 'direct.available' in run_search,
    'cache implementation still keys by query/mode/source and stores the same song/playlist lists with timestamp':
        '"${mode}|${if (cross) "all" else src}|${kw.trim().lowercase()}"' in cache
        and 'parseSongs(o.optJSONArray("songs") ?: JSONArray()) to parsePlList(o.optJSONArray("pls") ?: JSONArray())' in cache
        and 'songs.take(40)' in cache and 'pls.forEach' in cache and 'o.put("ts", System.currentTimeMillis())' in cache,
    'matrix documents the separate TX fallback, no identifier input, cache and failure boundaries':
        '## SearchController QQ 歌单搜索服务端兜底的 Source Registry 委派' in matrix
        and 'searchPlaylistsServer(q, 100)' in matrix
        and '没有歌单 ID 输入' in matrix
        and '最终结果非空时写回原搜索缓存' in matrix
        and 'direct.available' in matrix,
    'VERSION and Gradle version declarations are synchronized':
        version_match is not None and gradle_name is not None and gradle_code is not None
        and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('SearchController QQ playlist fallback Source Registry contract guard passed')
PY
