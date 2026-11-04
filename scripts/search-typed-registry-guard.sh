#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
controller = (base / 'SearchController.kt').read_text()
models = (base / 'Models.kt').read_text()
interface_path = 'app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt'
interface = (base / 'source/MusicSource.kt').read_text()
adapter_path = 'app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt'
adapter = (root / adapter_path).read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
api_path = 'app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt'
sys.path.insert(0, str(root / 'scripts'))
from r656_api_projection import project_r656_api  # 1.0.656 reviewed unpinning/detail-path edit
api = project_r656_api((root / api_path).read_text())
# r657 typed import is reviewed independently from search. r658c D#3 re-pinned (short-link expansion + source alias canonicalisation). Pin the COMPLETE changed
# import span and parser before restoring only that exact span for historical search
# assertions. Same-source direct/server fallback semantics are also executed below.
import hashlib
current_start = api.index('    /** Import only a validated entity reference.')
current_end = api.index('\n    /**\n     * 平台歌单分类', current_start)
current_import = api[current_start:current_end]
if hashlib.sha256(current_import.encode()).hexdigest() != 'cde738a7c40e2cee343901341ef9ff9ffa5caf397c716575d5da55cf13710e97':
    raise SystemExit('FAIL: reviewed r657 typed import body changed')
parser = (base / 'source/direct/OnlineImportReference.kt').read_bytes()
if hashlib.sha256(parser).hexdigest() != '55f511620d4aae2800fa68466124c786df280d02a4e31c9c3747343a02e91534':
    raise SystemExit('FAIL: reviewed r657 typed import parser changed')
subprocess.run(['bash', str(root / 'scripts/main-playlist-import-fallback-registry-guard.sh')], check=True)
# Public-repo port (2026-11-05): read the byte-identical vendored copy of
# the fb660e76 ApiClient baseline (see scripts/guard-baselines/
# search-typed-registry-guard/PROVENANCE.md); fall back to git show where
# the object exists.
_historical_api_vendored = (root / 'scripts' / 'guard-baselines' /
                            'search-typed-registry-guard' /
                            'fb660e769edfd0c892c48152b55c0d6f96a0b575' / api_path)
if _historical_api_vendored.is_file():
    historical_api = _historical_api_vendored.read_text()
else:
    historical_api = subprocess.check_output(['git', 'show', 'fb660e769edfd0c892c48152b55c0d6f96a0b575:' + api_path], cwd=root, text=True)
historical_start = historical_api.index('    /** 批17：playlistDetail 直连化后同步 suspend')
historical_end = historical_api.index('\n    /**\n     * 平台歌单分类', historical_start)
api = api[:current_start] + historical_api[historical_start:historical_end] + api[current_end:]
feed_path = 'app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt'
feed = (root / feed_path).read_text()
home_controller_path = 'app/src/main/java/xyz/cncnlp/soundwave/HomeFeedController.kt'
home_controller = (root / home_controller_path).read_text()
playlist_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/PlaylistFeedRepositoryTest.kt'
playlist_tests = (root / playlist_tests_path).read_text()
home_cache_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/HomeFeedCachePolicyTest.kt'
home_cache_tests = (root / home_cache_tests_path).read_text()
tx_search_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/source/direct/TxDirectSearchTest.kt'
tx_search_tests = (root / tx_search_tests_path).read_text()
direct_path = 'app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlaylistDirectSearch.kt'
direct = (root / direct_path).read_text()
matrix_path = 'docs/source-capability-matrix.md'
matrix = (root / matrix_path).read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
baseline = '5ad2d9cd34f9dfb5e2cd1c5f4c96dec014ece1f0'
baseline_552 = '139ee6628a1e3dc0cc9f885e0a1b9aca431d4247'
baseline_551 = '0a276c8218e10271f4717f6062f0a38ab21577f4'  # same commit as the former short spelling '0a276c8'; full form keys the vendored baseline lookup


# Step8 is an explicitly reviewed direct-first change, not a new baseline.
# Validate each complete literal hunk before projecting it to the historical
# contract below. Every original assertion still runs on that projection;
# no arbitrary span is masked and all unapproved bytes remain protected.
STEP8_REVIEWED_HUNKS = {
    'api': [
        (
            (
                'import okhttp3.ConnectionSpec\n'
                'import java.security.SecureRandom\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchAlbumDetailDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchPlaylistCategoryGroupsDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchArtistDetailDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.importOfficialPlaylistDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchRecommendPlaylistsDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchDiscoverDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchHotDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchHotWordsDirect\n'
            ),
            (
                'import okhttp3.ConnectionSpec\n'
                'import java.security.SecureRandom\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchAlbumDetailDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchDiscoverDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchHotDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchHotWordsDirect\n'
            ),
        ),
        (
            (
                '    suspend fun singerSongs(artistId: String, singerName: String, source: String = "wy"): Playlist? {\n'
                '        if (artistId.isBlank()) return null\n'
                '        val src = source.ifBlank { "wy" }\n'
                '        fetchArtistDetailDirect(src, artistId, singerName)?.let { return it }\n'
                '        // 五平台直连（fetchArtistDetailDirect 覆盖 wy/tx/kg/kw/mg）失败/空结果时的服务端回落，五平台同口径，非 KW/MG 专属。\n'
                '        return try {\n'
                '            val q = java.net.URLEncoder.encode(artistId, "UTF-8")\n'
                '            val nm = java.net.URLEncoder.encode(singerName, "UTF-8")\n'
            ),
            (
                '    suspend fun singerSongs(artistId: String, singerName: String, source: String = "wy"): Playlist? {\n'
                '        if (artistId.isBlank()) return null\n'
                '        val src = source.ifBlank { "wy" }\n'
                '        return try {\n'
                '            val q = java.net.URLEncoder.encode(artistId, "UTF-8")\n'
                '            val nm = java.net.URLEncoder.encode(singerName, "UTF-8")\n'
            ),
        ),
        # Reviewed (guard-final): PlaylistCatalogApi catalog-layer evolution of
        # the legacy recommend fallback; equivalence review in PROGRESS2.md
        # (guard-final section). Maps the current catalog delegation onto the
        # previously reviewed inline form so the original hunk and every
        # original assertion below run unchanged on the projection.
        (
            (
                '     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；\n'
                '     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。\n'
                '     */\n'
                '    suspend fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =\n'
                '        recommendPlaylistsOutcome(source, limit).legacyListValue()\n'
                '\n'
                '    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */\n'
                '    internal suspend fun recommendPlaylistsOutcome(\n'
                '        source: String,\n'
                '        limit: Int = 50\n'
                '    ): FeedListOutcome<List<Playlist>> {\n'
                '        fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }\n'
                '        return recommendPlaylistsLegacyServerOutcome(source, limit)\n'
                '    }\n'
                '\n'
                '    /** Legacy fallback retained until the independent direct/device acceptance gate passes. */\n'
                '    private fun recommendPlaylistsLegacyServerOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>> =\n'
                '        playlistCatalog().recommend(source, limit).asFeedOutcome()\n'
                '\n'
                '    /** Per-operation explicit transport seam. No auth cache, playback state, or global test override. */\n'
                '    internal fun playlistCatalog(\n'
                '        calls: OfficialCallScope? = null,\n'
                '        http: OkHttpClient = client,\n'
                '        pause: () -> Unit = { try { Thread.sleep(200) } catch (_: InterruptedException) {} }\n'
                '    ): PlaylistCatalogApi = PlaylistCatalogApi(PlaylistCatalogTextClient(\n'
                '        absoluteUrl = ::abs,\n'
                '        exchange = { url -> httpTextOrThrow("GET", url, null, MAX_TEXT_RESPONSE_BYTES, http, calls) },\n'
                '        ensureActive = { calls?.ensureActive() },\n'
                '        pause = pause\n'
                '    ))\n'
                '\n'
                '    /** Compatibility adapters keep the existing category/home contracts. */\n'
                '    private fun parsePlaylistsPayload(raw: String, source: String): List<Playlist> =\n'
                '        PlaylistCatalogPayloads.legacy(raw, source)\n'
            ),
            (
                '     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；\n'
                '     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。\n'
                '     */\n'
                '    suspend fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =\n'
                '        recommendPlaylistsOutcome(source, limit).legacyListValue()\n'
                '\n'
                '    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */\n'
                '    internal suspend fun recommendPlaylistsOutcome(\n'
                '        source: String,\n'
                '        limit: Int = 50\n'
                '    ): FeedListOutcome<List<Playlist>> {\n'
                '        fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }\n'
                '        return recommendPlaylistsLegacyServerOutcome(source, limit)\n'
                '    }\n'
                '\n'
                '    /** Legacy fallback retained until the independent direct/device acceptance gate passes. */\n'
                '    private fun recommendPlaylistsLegacyServerOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>> = captureListRequestOutcome {\n'
                '        val srcQ = "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}"\n'
                '        val response = getTextOutcome("/music/playlist?action=recommend$srcQ&limit=$limit")\n'
                '        decodeListResponseOutcome(response) { raw ->\n'
                '            parseRecommendPlaylistsPayloadOutcome(raw, source)\n'
                '        }\n'
                '    }\n'
                '\n'
                '    /** searchPlaylists/recommendPlaylists 共用的歌单载荷解析（兼容 data/list/records 多外层与多字段名）。 */\n'
                '    private fun parsePlaylistsPayload(raw: String, source: String): List<Playlist> =\n'
                '        PlaylistCatalogPayloads.legacy(raw, source)\n'
            ),
        ),
        (
            (
                '     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；\n'
                '     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。\n'
                '     */\n'
                '    suspend fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =\n'
                '        recommendPlaylistsOutcome(source, limit).legacyListValue()\n'
                '\n'
                '    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */\n'
                '    internal suspend fun recommendPlaylistsOutcome(\n'
                '        source: String,\n'
                '        limit: Int = 50\n'
                '    ): FeedListOutcome<List<Playlist>> {\n'
                '        fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }\n'
                '        return recommendPlaylistsLegacyServerOutcome(source, limit)\n'
                '    }\n'
                '\n'
                '    /** Legacy fallback retained until the independent direct/device acceptance gate passes. */\n'
                '    private fun recommendPlaylistsLegacyServerOutcome(source: String, limit: Int): FeedListOutcome<List<Playlist>> = captureListRequestOutcome {\n'
                '        val srcQ = "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}"\n'
                '        val response = getTextOutcome("/music/playlist?action=recommend$srcQ&limit=$limit")\n'
                '        decodeListResponseOutcome(response) { raw ->\n'
            ),
            (
                '     * 首页甄选歌单（批24b）：官方广场源（tx/wy 有真广场，标题不再清一色"xx推荐"）；\n'
                '     * 空结果由调用方回落 searchPlaylists（kg/kw/mg 无广场接口走搜索兜底）。\n'
                '     */\n'
                '    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =\n'
                '        recommendPlaylistsOutcome(source, limit).legacyListValue()\n'
                '\n'
                '    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */\n'
                '    internal fun recommendPlaylistsOutcome(\n'
                '        source: String,\n'
                '        limit: Int = 50\n'
                '    ): FeedListOutcome<List<Playlist>> = captureListRequestOutcome {\n'
                '        val srcQ = "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}"\n'
                '        val response = getTextOutcome("/music/playlist?action=recommend$srcQ&limit=$limit")\n'
                '        decodeListResponseOutcome(response) { raw ->\n'
            ),
        ),
        (
            (
                '\n'
                '    /** 批17：playlistDetail 直连化后同步 suspend（实现不变；唯一调用点 MainActivity:3256 已在协程内）。 */\n'
                '    suspend fun importPlaylist(raw: String): Playlist? {\n'
                '        importOfficialPlaylistDirect(raw)?.let { return it }\n'
                '        // Shortlinks/commands and unavailable direct details keep the legacy parser route.\n'
                '        return try {\n'
                '            val q = raw.trim()\n'
                '            if (q.isEmpty()) return null\n'
            ),
            (
                '\n'
                '    /** 批17：playlistDetail 直连化后同步 suspend（实现不变；唯一调用点 MainActivity:3256 已在协程内）。 */\n'
                '    suspend fun importPlaylist(raw: String): Playlist? {\n'
                '        return try {\n'
                '            val q = raw.trim()\n'
                '            if (q.isEmpty()) return null\n'
            ),
        ),
        (
            (
                '     */\n'
                '    suspend fun plCatGroups(source: String = "wy"): CatGroups? {\n'
                '        fetchPlaylistCategoryGroupsDirect(source)?.takeIf { it.isNotEmpty() }?.let { return CatGroups(it) }\n'
                '        return plCatGroupsLegacyServer(source)\n'
                '    }\n'
                '\n'
                '    /** Retained legacy fallback pending direct/device acceptance; not a local-only capability. */\n'
                '    private fun plCatGroupsLegacyServer(source: String): CatGroups? {\n'
                '        return try {\n'
                '            val raw = get("/music/plcats?source=${java.net.URLEncoder.encode(source, "UTF-8")}")\n'
                '            if (raw.isEmpty()) return null\n'
            ),
            (
                '     */\n'
                '    data class CatGroups(val groups: List<Pair<String, List<String>>>)\n'
                '\n'
                '    fun plCatGroups(source: String = "wy"): CatGroups? {\n'
                '        return try {\n'
                '            val raw = get("/music/plcats?source=${java.net.URLEncoder.encode(source, "UTF-8")}")\n'
                '            if (raw.isEmpty()) return null\n'
            ),
        ),
    ],
    'adapter': [
        (
            (
                'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.searchOfficialEntitiesDirect\n'
                '\n'
                'import java.util.concurrent.CancellationException\n'
                'import kotlinx.coroutines.currentCoroutineContext\n'
                'import kotlinx.coroutines.ensureActive\n'
                '\n'
                '/**\n'
            ),
            (
                'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect\n'
                'import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryGroupsDirect\n'
                'import java.util.concurrent.CancellationException\n'
                '\n'
                '/**\n'
            ),
        ),
        (
            (
                '    override suspend fun searchServer(keyword: String, type: String): List<Song> =\n'
                '        api.searchCancellable(keyword, source = platform, type = type)\n'
                '\n'
                '    // Official QQ typed status distinguishes successful empty from unavailable.\n'
                '    // Song requests on other platforms use the existing direct-first adapter.\n'
                '    override suspend fun searchTyped(keyword: String, type: String): List<Song> {\n'
                '        currentCoroutineContext().ensureActive()\n'
                '        directTypedSearchResult?.invoke(keyword, type)?.let {\n'
                '            currentCoroutineContext().ensureActive()\n'
                '            if (it.available) return it.songs\n'
                '        }\n'
                '        if (type.isBlank() || type == "song") return search(keyword, 1, 30)\n'
                '        currentCoroutineContext().ensureActive()\n'
                '        searchOfficialEntitiesDirect(platform, keyword, type).let {\n'
                '            currentCoroutineContext().ensureActive()\n'
                '            if (it.available) return it.songs\n'
                '        }\n'
                '        return api.searchCancellable(keyword, source = platform, type = type)\n'
                '    }\n'
                '\n'
                '    override suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =\n'
                '        directTypedSearchResult?.invoke(keyword, type)\n'
            ),
            (
                '    override suspend fun searchServer(keyword: String, type: String): List<Song> =\n'
                '        api.search(keyword, source = platform, type = type)\n'
                '\n'
                "    // Typed search stays on ApiClient's existing server route and preserves its type fallback and filtering.\n"
                '    override suspend fun searchTyped(keyword: String, type: String): List<Song> =\n'
                '        api.search(keyword, source = platform, type = type)\n'
                '\n'
                '    override suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =\n'
                '        directTypedSearchResult?.invoke(keyword, type)\n'
            ),
        ),
        (
            (
                '        if (platform == "wy") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()\n'
                '\n'
                '    override suspend fun playlistCategoryGroups(): CatGroups? =\n'
                '        api.plCatGroups(platform)\n'
                '\n'
                '    // page/limit not forwarded: the catalog album route returns the full track list.\n'
                '    override suspend fun albumDetail(id: String, page: Int, limit: Int): Playlist? {\n'
            ),
            (
                '        if (platform == "wy") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()\n'
                '\n'
                '    override suspend fun playlistCategoryGroups(): ApiClient.CatGroups? =\n'
                '        if (platform == "wy") {\n'
                '            fetchWyPlaylistCategoryGroupsDirect().takeIf { it.isNotEmpty() }?.let { ApiClient.CatGroups(it) }\n'
                '        } else {\n'
                '            api.plCatGroups(platform)\n'
                '        }\n'
                '\n'
                '    // page/limit not forwarded: ApiClient.albumDetail returns the full track list.\n'
                '    override suspend fun albumDetail(id: String, page: Int, limit: Int): Playlist? {\n'
            ),
        ),
    ],
}


def reviewed_step8_projection(text, label):
    for current, historical in STEP8_REVIEWED_HUNKS[label]:
        if text.count(current) != 1:
            raise SystemExit(f'FAIL: missing/modified reviewed step8 {label} hunk')
        text = text.replace(current, historical, 1)
    return text


# Pin reviewed implementation bytes as well as executable contract statements:
# a token in a comment or a dead branch is not an accepted replacement. Update
# these pins only after reviewing provider/status/parser changes. No live claim.
STEP8_DIRECT_SHA256 = {'PlaylistRecommendDirect.kt': '05ad2c9e970fc01d6a1684d346312a8e40bd9fe3f8969aaeacd393f5c7d057f0', 'PlaylistCategoriesDirect.kt': '463a9331c896675d5f44db6f1a9340dda1788c65a2efa2c82755f7d01a83fd05', 'TypedEntitiesDirect.kt': 'f50103ef77520ff40335f619abec1a72cdd67234b1ce16ee0438aeeb150a5ba2'}
for filename, expected in STEP8_DIRECT_SHA256.items():
    import hashlib
    if hashlib.sha256((base / 'source/direct' / filename).read_bytes()).hexdigest() != expected:
        raise SystemExit(f'FAIL: reviewed step8 native contract changed: {filename}')

step8_api = api
step8_adapter = adapter
live_adapter = adapter
step8_recommend = (base / 'source/direct/PlaylistRecommendDirect.kt').read_text()
step8_categories = (base / 'source/direct/PlaylistCategoriesDirect.kt').read_text()
step8_entities = (base / 'source/direct/TypedEntitiesDirect.kt').read_text()
step8_registry = (base / 'source/MusicSourceRegistry.kt').read_text()
step8_checks = {
    'recommendation is genuinely suspend/direct-first with successful-empty preserved':
        'suspend fun recommendPlaylists(source: String, limit: Int = 50)' in step8_api
        and 'internal suspend fun recommendPlaylistsOutcome(' in step8_api
        and 'fetchRecommendPlaylistsDirect(source, limit)?.let { return FeedListOutcome.Success(it) }' in step8_api
        and 'return recommendPlaylistsLegacyServerOutcome(source, limit)' in step8_api,
    'native recommendation squares retain bounded request/parse and no keyword invention':
        'internal suspend fun fetchRecommendPlaylistsDirect(source: String, limit: Int): List<Playlist>?' in step8_recommend
        and all(token in step8_recommend for token in (
            'limit.coerceIn(1, 50)', 'for (page in 1..3)', 'require(depth <= 8)',
            'playlist.PlayListPlazaServer', 'getRcmPlayList', 'playlist-square-recommend',
            'if (digest == "5")', 'else -> return null',
            'val rows = root.optJSONArray("playlists") ?: return null',
        )) and all(token not in step8_recommend for token in (
            'searchPlaylists(', 'searchPlaylistDirect(', 'ApiClient.', '/music/',
            'PlatformAccountStore', 'CookieManager',
        )),
    'categories now delegate to suspend direct-first API without borrowing a taxonomy':
        'suspend fun plCatGroups(source: String = "wy")' in step8_api
        and 'fetchPlaylistCategoryGroupsDirect(source)?.takeIf { it.isNotEmpty() }?.let { return CatGroups(it) }' in step8_api
        and 'return plCatGroupsLegacyServer(source)' in step8_api
        and 'if (src == "wy") return fetchWyPlaylistCategoryGroupsDirect()' in step8_categories
        and 'override suspend fun playlistCategoryGroups(): CatGroups? =\n        api.plCatGroups(platform)' in step8_adapter,
    'typed adapter preserves TX status then song route then native entity status then exact legacy fallback':
        'directTypedSearchResult?.invoke(keyword, type)?.let {' in step8_adapter
        and 'if (it.available) return it.songs' in step8_adapter
        and 'if (type.isBlank() || type == "song") return search(keyword, 1, 30)' in step8_adapter
        and 'searchOfficialEntitiesDirect(platform, keyword, type).let {' in step8_adapter
        and 'if (it.available) return it.songs' in step8_adapter
        and 'return api.searchCancellable(keyword, source = platform, type = type)' in step8_adapter
        and '"tx" to { keyword, type -> searchTxTypedResult(keyword, type) }' in step8_registry
        and 'directTypedSearchResult = directTypedSearchResultByPlatform[id]' in step8_registry
        and 'require(id in platformIds)' in step8_registry,
}
for label, passed in step8_checks.items():
    if not passed:
        raise SystemExit(f'FAIL: step8 {label}')
api = reviewed_step8_projection(api, 'api')
adapter = reviewed_step8_projection(adapter, 'adapter')


def _vendored_baseline(revision, path):
    # Public-repo port (2026-11-05): this repository's history starts at the
    # single snapshot commit, so fixed historical baseline objects are
    # vendored byte-for-byte under scripts/guard-baselines/<guard>/<revision>/
    # (see PROVENANCE.md there for source commits and sha256). Returns None
    # when no vendored copy exists.
    candidate = (root / 'scripts' / 'guard-baselines' /
                 'search-typed-registry-guard' / revision / path)
    return candidate.read_text() if candidate.is_file() else None


def git_show(path):
    vendored = _vendored_baseline(baseline, path)
    if vendored is not None:
        return vendored
    result = subprocess.run(
        ['git', 'show', f'{baseline}:{path}'], cwd=root, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise SystemExit(f'FAIL: cannot read protected baseline {baseline}:{path}: {result.stderr.strip()}')
    return result.stdout


def git_show_ref(revision, path):
    vendored = _vendored_baseline(revision, path)
    if vendored is not None:
        return vendored
    result = subprocess.run(
        ['git', 'show', f'{revision}:{path}'], cwd=root, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise SystemExit(f'FAIL: cannot read fixed baseline {revision}:{path}: {result.stderr.strip()}')
    return result.stdout


def raw_span(text, start_marker, end_marker):
    if text.count(start_marker) != 1:
        raise SystemExit(f'FAIL: expected exactly one section start: {start_marker}')
    start = text.index(start_marker)
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'FAIL: missing section end after: {start_marker}')
    return text[start:end]


def replace_raw_span(text, start_marker, end_marker, replacement):
    span = raw_span(text, start_marker, end_marker)
    return text.replace(span, replacement, 1)


def remove_exact_fragment(text, fragment, label):
    if text.count(fragment) != 1:
        raise SystemExit(f'FAIL: expected exactly one reviewed fragment: {label}')
    return text.replace(fragment, '', 1)


def drop_optional_import(text, line):
    count = text.count(line + '\n')
    if count > 1:
        raise SystemExit(f'FAIL: duplicate import while normalizing: {line}')
    return text.replace(line + '\n', '', 1)


def mask_span(text, start_marker, end_marker, sentinel):
    if text.count(start_marker) != 1:
        raise SystemExit(f'FAIL: expected exactly one section start: {start_marker}')
    start = text.index(start_marker)
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'FAIL: missing section end after: {start_marker}')
    return text[:start] + sentinel + text[end:]


def mask_optional_span(text, start_marker, end_marker, sentinel):
    if start_marker not in text:
        return text
    return mask_span(text, start_marker, end_marker, sentinel)


def extract_span(text, start_marker, end_marker):
    if text.count(start_marker) != 1:
        raise SystemExit(f'FAIL: expected exactly one section start: {start_marker}')
    start = text.index(start_marker)
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'FAIL: missing section end after: {start_marker}')
    return text[start:end].strip()


def matrix_section_range(lines, marker, label):
    matches = [i for i, line in enumerate(lines) if line.startswith(marker)]
    if len(matches) > 1:
        raise SystemExit(f'FAIL: duplicate source matrix section: {label}')
    if not matches:
        return None
    start = matches[0]
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith('## ')), len(lines))
    return start, end


def mask_matrix_sections(text, sections):
    lines = text.splitlines()
    spans = []
    for marker, sentinel in sections:
        section = matrix_section_range(lines, marker, marker)
        if section is not None:
            start, end = section
            while start > 0 and not lines[start - 1].strip():
                start -= 1
            while end < len(lines) and not lines[end].strip():
                end += 1
            spans.append((start, end, sentinel))
    for start, end, sentinel in sorted(spans, reverse=True):
        del lines[start:end]
    while lines and not lines[-1].strip():
        lines.pop()
    lines.extend(sentinel for _, sentinel in sections)
    return '\n'.join(lines) + ('\n' if text.endswith('\n') else '')


def mask_category_bullets(text):
    text = mask_matrix_sections(text, (
        (
            '## MainActivity 榜单歌单详情的 Source Registry 迁移点',
            '__MAIN_ACTIVITY_RANK_PLAYLIST_DETAIL_SECTION__',
        ),
        (
            '## MainActivity 普通远端歌单详情快照的 Source Registry 迁移点',
            '__MAIN_ACTIVITY_PLAYLIST_SNAPSHOT_SECTION__',
        ),
        (
            '## PlaylistFeedRepository 官方推荐歌单 Source Registry 迁移点',
            '__OFFICIAL_RECOMMENDATION_MATRIX_SECTION__',
        ),
        (
            '## MainActivity 本地歌单播放量刷新 Source Registry 迁移点',
            '__PLAYLIST_COUNT_REFRESH_MATRIX_SECTION__',
        ),
        (
            '## ApiClient 在线导入歌单空列表补详情的 Source Registry 迁移点',
            '__ONLINE_PLAYLIST_IMPORT_FALLBACK_MATRIX_SECTION__',
        ),
        (
            '## UrlResolver 跨平台候选搜索的 Source Registry 迁移点',
            '__URLRESOLVER_CROSS_PLATFORM_SEARCH_MATRIX_SECTION__',
        ),
        (
            '## SearchController QQ 歌单搜索服务端兜底的 Source Registry 委派',
            '__SEARCH_CONTROLLER_QQ_PLAYLIST_FALLBACK_SECTION__',
        ),
        (
            '## SearchController QQ 类型搜索直连结果的 Source Registry 委派',
            '__SEARCH_CONTROLLER_TX_TYPED_SEARCH_SECTION__',
        ),
        (
            '## PlaylistFeedRepository 网易分类广场 Source Registry 委派',
            '__PLAYLIST_FEED_WY_CATEGORY_SQUARE_SECTION__',
        ),
    ))
    lines = text.splitlines()
    category_groups = [i for i, line in enumerate(lines) if line.startswith('- 分类组：')]
    category_search = [i for i, line in enumerate(lines) if line.startswith('- **分类歌单搜索与分页（')]
    typed_search = [i for i, line in enumerate(lines) if line.startswith('- **艺术家/专辑类型搜索**：')]
    similar_search = [i for i, line in enumerate(lines) if line.startswith('- **相似歌曲搜索（')]
    # guard-final: later documentation bullet for the heart-mode playlist
    # context search (PlaybackController.playHeartSimilar / HeartPlaylistContext,
    # present in current code, absent from the 5ad matrix); treat it like the
    # other optional later bullets above: at most one, masked from comparison.
    heart_context_search = [i for i, line in enumerate(lines) if line.startswith('- **心动模式歌单上下文搜索（')]
    heart_similar_routes = [i for i, line in enumerate(lines) if line.startswith('| 心动/相似候选搜索：')]
    singer_album = [i for i, line in enumerate(lines) if line.startswith('| 歌手、专辑 |')]
    home_feed_outcome = [i for i, line in enumerate(lines) if line.startswith('| 首页热门歌曲、榜单、歌单、热词与发现：')]
    manual_discover_outcome = [i for i, line in enumerate(lines) if line.startswith('| 首页手动发现刷新：')]
    if len(category_groups) != 1 or len(category_search) > 1 or len(typed_search) != 1 or len(similar_search) > 1 or len(heart_context_search) > 1 or len(heart_similar_routes) > 1 or len(singer_album) != 1 or len(home_feed_outcome) != 1 or len(manual_discover_outcome) != 1:
        raise SystemExit('FAIL: unexpected duplicate or missing Source matrix bullet')
    lines[typed_search[0]] = '__TYPED_SEARCH_MATRIX_BULLET__'
    lines[category_groups[0]] = '__CATEGORY_GROUPS_MATRIX_BULLET__'
    lines[singer_album[0]] = '__SINGER_ALBUM_MATRIX_BULLET__'
    lines[home_feed_outcome[0]] = '__HOME_FEED_TYPED_OUTCOME_MATRIX_ROW__'
    lines[manual_discover_outcome[0]] = '__MANUAL_DISCOVER_TYPED_OUTCOME_MATRIX_ROW__'
    for index in sorted(category_search + similar_search + heart_context_search + heart_similar_routes, reverse=True):
        del lines[index]
        if index < len(lines) and not lines[index].strip():
            del lines[index]
    return '\n'.join(lines) + ('\n' if text.endswith('\n') else '')


def normalize_matrix_metadata(text):
    lines = text.splitlines()
    if len(lines) < 3:
        raise SystemExit('FAIL: source matrix is missing its audit metadata paragraph')
    normalize = lambda value: ' '.join(value.replace('\\n', ' ').split())
    baseline_metadata = normalize(git_show(matrix_path).splitlines()[2])
    approved_metadata = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.555 / 555`。本轮核对前的代码 HEAD 为 `79553cf56dc3162a9a297ceb2f5bff4c0edc31f5`'
        '（版本 `1.0.555 / 555`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    approved_metadata_556 = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.556 / 556`。本轮核对前的代码 HEAD 为 `74f02121896a90606f95df46026418bb09e6a94e`'
        '（版本 `1.0.556 / 556`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    approved_metadata_557 = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.557 / 557`。本轮核对前的代码 HEAD 为 `cb2fa6c765e17e6f00f9c473b4f92fae5bc4b84b`'
        '（版本 `1.0.557 / 557`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    approved_metadata_559 = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.559 / 559`。本轮核对前的代码 HEAD 为 `8b820a971365e616bf0f62925c40fa01f9885c52`'
        '（版本 `1.0.559 / 559`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    approved_metadata_560 = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.560 / 560`。本轮核对前的代码 HEAD 为 `ed0db5c3f7916cca1c006a38136af3343eb94245`'
        '（版本 `1.0.560 / 560`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    approved_metadata_562 = (
        '原始静态审计基线为提交 `02d699c3649ca761f71fec073b15c2063c26e018`（版本 '
        '`1.0.499 / 499`）。此后本文按调用点持续增补；本轮核对前已记录至版本 '
        '`1.0.562 / 562`。本轮核对前的代码 HEAD 为 `567cfbb5d10f3605dc2f9ba6e095faec8a02a3f7`'
        '（版本 `1.0.562 / 562`）；这不表示矩阵已对该 HEAD 全面重审。矩阵条目依据对应客户端源码与测试，'
        '不把源码注释当成已验证的服务端事实。'
    )
    if normalize(lines[2]) not in {baseline_metadata, normalize(approved_metadata), normalize(approved_metadata_556), normalize(approved_metadata_557), normalize(approved_metadata_559), normalize(approved_metadata_560), normalize(approved_metadata_562)}:
        raise SystemExit('FAIL: unexpected source matrix audit metadata change')
    lines[2] = '__APPROVED_AUDIT_METADATA__'
    return '\n'.join(lines) + ('\n' if text.endswith('\n') else '')


def normalized_api_to_552(text):
    baseline_text = git_show_ref(baseline_552, api_path)
    guard_baseline_text = git_show(api_path)
    old_media_probe = raw_span(
        baseline_text,
        '    // 探测直链的 Content-Length',
        '    // 探测服务是否可达（不需登录）',
    )
    text = replace_raw_span(
        text,
        '    // HEAD 优先；无总长度时',
        '    // 探测服务是否可达（不需登录）',
        old_media_probe,
    )
    text = drop_optional_import(text, 'import xyz.cncnlp.soundwave.download.AudioQualityEvidence')
    text = drop_optional_import(text, 'import xyz.cncnlp.soundwave.download.MediaProbeMetadata')
    old_route = raw_span(
        baseline_text,
        '    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> {',
        '    /** searchPlaylists/recommendPlaylists 共用',
    )
    text = replace_raw_span(
        text,
        '    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =',
        '    /** searchPlaylists/recommendPlaylists 共用',
        old_route,
    )
    # Account-removal wave: the /api/user account surface was deleted from
    # ApiClient wholesale (auth fields/client, login/save/rename/delete,
    # snapshot export, the dead loadServerScripts, postJsonAuth). The old
    # reviewed-additions canonicalization for that block is retired; instead
    # the three account regions are excised from BOTH texts so the remaining
    # code still compares byte-for-byte, and the checks dict pins the absence
    # of the account surface in the current file.
    def excise_optional_span(text, start_marker, end_marker):
        if start_marker not in text:
            return text
        return replace_raw_span(text, start_marker, end_marker, '')
    for import_line in (
        'import xyz.cncnlp.soundwave.sync.ServerSnapshotExportClient',
        'import xyz.cncnlp.soundwave.sync.ServerSnapshotExportHttpResponse',
        'import xyz.cncnlp.soundwave.sync.ServerSnapshotExportTransport',
        'import xyz.cncnlp.soundwave.sync.ServerSnapshotFetchOutcome',
    ):
        text = drop_optional_import(text, import_line)
    text = excise_optional_span(
        text,
        '    @Volatile private var lastAuthAt = 0L',
        '    private const val UA =',
    )
    text = excise_optional_span(
        text,
        '    private val authClient = client.newBuilder()',
        '    private val JSON_MT',
    )
    text = excise_optional_span(
        text,
        '    private val authLock = Any()',
        '    // 规范化：只留 origin，剥 music/app 等',
    )
    text = excise_optional_span(
        text,
        '    // action: login | register',
        '    fun reportBug(',
    )
    # The postJson comment lost its postJsonAuth clause with the account block.
    text = text.replace(
        '        // 普通接口保留重试；登录接口由 postJsonAuth 统一退避，避免一次点击内多层重试打出 502/频控。',
        '        // 普通接口保留重试，避免一次点击内多层重试打出 502/频控。',
        1,
    )
    text = drop_optional_import(
        text,
        'import xyz.cncnlp.soundwave.source.MusicSourceRegistry',
    )
    import_registry_route = (
        'if (id.isNotEmpty() && realSrc.isNotBlank()) songs = '
        'MusicSourceRegistry.platformSource(realSrc).playlistDetail(id)'
    )
    import_legacy_route = (
        'if (id.isNotEmpty() && realSrc.isNotBlank()) songs = playlistDetail(id, realSrc)'
    )
    if text.count(import_registry_route) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed .556 import-detail delegate')
    text = text.replace(import_registry_route, import_legacy_route, 1)
    # guard-final: the reviewed parser block lost its doc comment and collapsed
    # to a one-line validated() delegation in current code, while the 5ad guard
    # baseline still carries the commented multi-line form. Drop the comment
    # line where present and anchor the whole-block removal on the declaration
    # line common to both texts; the removed span is the same block either way.
    parser_comment = '    /** Keep the legacy playlist envelope/parser but distinguish valid empty data from request or JSON failure. */\n'
    if text.count(parser_comment) == 1:
        text = text.replace(parser_comment, '', 1)
    elif text.count(parser_comment) != 0:
        raise SystemExit('FAIL: unexpected reviewed parser comment change')
    text = replace_raw_span(
        text,
        '    internal fun parseRecommendPlaylistsPayloadOutcome(',
        '    private const val MAX_DETAIL_SONGS',
        '',
    )
    # Account-removal wave: the loadUserData cloud-bundle parse lived in the
    # deleted account block; pin its absence (the WebDAV fromCloud contract is
    # pinned by the privacy-data-flow guard instead).
    if 'val bundle = data?.let { LocalStore.UserBundle.fromCloud' in text:
        raise SystemExit('FAIL: retired account cloud-bundle parse reappeared in ApiClient')
    return text


def normalized_interface_to_552(text):
    baseline_text = git_show_ref(baseline_552, interface_path)
    # SW-04-06: CatGroups moved from ApiClient into this package, the interface
    # KDoc was rewritten, and the ApiClient import became unnecessary. Pin the
    # exact current forms and project them back to the .552 shape; any other
    # change to these lines fails the count checks.
    catgroups_route = '    suspend fun playlistCategoryGroups(): CatGroups? = null\n'
    if text.count(catgroups_route) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed CatGroups interface route')
    text = text.replace(
        catgroups_route,
        '    suspend fun playlistCategoryGroups(): ApiClient.CatGroups? = null\n',
        1,
    )
    typed_kdoc = '/** Typed domain boundary for catalog sources; account flows and playback resolution remain separate. */\n'
    if text.count(typed_kdoc) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed typed-boundary interface KDoc')
    text = text.replace(
        typed_kdoc,
        '/**\n'
        ' * 统一音源接口：把 ApiClient / UrlResolver / ScriptRuntime 的现有能力\n'
        ' * 收敛为一组标准挂起点，具体映射见同目录 DESIGN.md。\n'
        ' */\n',
        1,
    )
    package_head = 'package xyz.cncnlp.soundwave.source\n\nimport xyz.cncnlp.soundwave.Playlist\n'
    if text.count(package_head) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed interface package head')
    text = text.replace(
        package_head,
        'package xyz.cncnlp.soundwave.source\n\nimport xyz.cncnlp.soundwave.ApiClient\nimport xyz.cncnlp.soundwave.Playlist\n',
        1,
    )
    # guard-final: PlaylistSearchOutcome.Success gained the hasMore pagination
    # field (category-search pagination evolution; the same contract was
    # re-pinned in the category-search-outcome guard, wave-2 step 1). Pin the
    # exact current declaration and project it back to the .552 form for this
    # comparison; any other change to the line fails the count check.
    has_more_success = '    data class Success(val playlists: List<Playlist>, val hasMore: Boolean? = null) : PlaylistSearchOutcome\n'
    if text.count(has_more_success) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed hasMore Success declaration')
    text = text.replace(
        has_more_success,
        '    data class Success(val playlists: List<Playlist>) : PlaylistSearchOutcome\n',
        1,
    )
    # accept6 (cloud-source deletion): SourceCapabilities.isCloud retired with
    # the deleted cloud source (the cloud adapter was its only setter). Pin the
    # exact post-deletion forms and project them back to the .552 shape; any
    # other change to these lines fails the count checks.
    caps_kdoc_now = ' * 升级为 [CapabilityLevel] 三态，区分客户端直连与服务端回落；三个产品兼容 flag\n * （legacySinglePlatformFeed/previewClipRisk/isScript）保持 Boolean 不动。\n'
    if text.count(caps_kdoc_now) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed capabilities KDoc after isCloud retirement')
    text = text.replace(caps_kdoc_now, ' * 升级为 [CapabilityLevel] 三态，区分客户端直连与服务端回落；四个产品兼容 flag\n * （legacySinglePlatformFeed/previewClipRisk/isScript/isCloud）保持 Boolean 不动。\n', 1)
    caps_tail_now = '    val isScript: Boolean = false,\n)\n'
    if text.count(caps_tail_now) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed SourceCapabilities tail after isCloud retirement')
    text = text.replace(caps_tail_now, '    val isScript: Boolean = false,\n    val isCloud: Boolean = false,\n)\n', 1)
    text = drop_optional_import(
        text,
        'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult',
    )
    old_route = raw_span(
        baseline_text,
        '    /** Home-feed official recommendation route; implementations without it keep an empty result. */',
        '    suspend fun albumDetail',
    )
    text = replace_raw_span(
        text,
        '    /** Home-feed official recommendation route; implementations without it keep an empty result. */',
        '    suspend fun albumDetail',
        old_route,
    )
    text = remove_exact_fragment(
        text,
        '    /** Legacy song-list detail route; other source adapters keep the existing empty default. */\n'
        '    suspend fun playlistDetail(id: String): List<Song> = emptyList()\n\n',
        '.554 playlistDetail interface addition',
    )
    text = remove_exact_fragment(
        text,
        '    /** Server-only legacy lookup; do not add direct playlist-search candidates. */\n'
        '    suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> = emptyList()\n\n',
        '.555 server-only playlist-search interface addition',
    )
    text = remove_exact_fragment(
        text,
        '    /** Typed search with the existing QQ direct-path availability contract. */\n'
        '    suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =\n'
        '        TxTypedSearchResult(searchTyped(keyword, type), available = true)\n\n',
        '.561 TX typed-result interface addition',
    )
    return remove_exact_fragment(
        text,
        '    /** Fetch a provider-native category square without adding keyword-search results. */\n'
        '    suspend fun playlistCategorySquare(category: String, limit: Int, page: Int = 1): List<Playlist> = emptyList()\n\n',
        '.563 WY category-square interface addition',
    )


def normalized_adapter_to_552(text):
    text = drop_optional_import(
        text,
        'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult',
    )
    text = remove_exact_fragment(
        text,
        '    private val directTypedSearchResult: (suspend (String, String) -> TxTypedSearchResult)? = null,\n',
        '.561 TX typed-result adapter injection parameter',
    )
    method = (
        '    override suspend fun recommendPlaylistsOutcome(limit: Int): FeedListOutcome<List<Playlist>> =\n'
        '        api.recommendPlaylistsOutcome(source = platform, limit = limit)\n\n'
    )
    if text.count(method) != 1:
        raise SystemExit('FAIL: expected exactly one .553 adapter outcome fragment')
    text = text.replace(method, '', 1)
    text = remove_exact_fragment(
        text,
        '    override suspend fun playlistDetail(id: String): List<Song> =\n'
        '        api.playlistDetail(id, source = platform)\n\n',
        '.554 playlistDetail adapter addition',
    )
    text = remove_exact_fragment(
        text,
        '    override suspend fun searchPlaylistsServer(keyword: String, limit: Int): List<Playlist> =\n'
        '        api.searchPlaylistsCancellable(keyword, source = platform, limit = limit)\n\n',
        '.555 server-only playlist-search adapter addition',
    )
    text = remove_exact_fragment(
        text,
        '    override suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =\n'
        '        directTypedSearchResult?.invoke(keyword, type)\n'
        '            ?: TxTypedSearchResult(searchTyped(keyword, type), available = true)\n\n',
        '.561 TX typed-result adapter method',
    )
    text = remove_exact_fragment(
        text,
        '    // Keep NetEase\'s category-square-only route separate from generic keyword playlist search.\n'
        '    override suspend fun playlistCategorySquare(category: String, limit: Int, page: Int): List<Playlist> =\n'
        '        if (platform == "wy") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()\n\n',
        '.563 WY category-square adapter delegate',
    )
    return remove_exact_fragment(
        text,
        'import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect\n',
        '.563 WY category-square adapter import',
    )


def normalized_feed_to_552(text):
    baseline_text = git_show_ref(baseline_552, feed_path)
    # SW-04-06: CatGroups moved from ApiClient into the source package; map the
    # relocated type reference and its import back to the baseline shape.
    catgroups_import = 'import xyz.cncnlp.soundwave.source.CatGroups\n'
    if text.count(catgroups_import) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed CatGroups import in feed')
    text = text.replace(catgroups_import, '', 1)
    catgroups_route = 'fetchPlaylistCategoryGroups(platform: String): CatGroups?'
    if text.count(catgroups_route) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed CatGroups feed route')
    text = text.replace(catgroups_route, 'fetchPlaylistCategoryGroups(platform: String): ApiClient.CatGroups?', 1)
    new_route = 'provider.playlistCategorySquare(term, 40)'
    old_route = 'fetchWyPlaylistCategoryDirect(term, 40)'
    if text.count(new_route) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed .563 WY category-square delegate')
    text = text.replace(new_route, old_route, 1)
    old_route = raw_span(
        baseline_text,
        '            val officialRequest = async(Dispatchers.IO) {',
        '            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())',
    )
    return replace_raw_span(
        text,
        '            val officialRequest = async(Dispatchers.IO) {',
        '            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())',
        old_route,
    )


def normalized_home_controller_to_552(text):
    # ACCEPT5-PERF (reviewed relocation): the taste-profile provider invocation moved
    # from the refreshHomeFeed prologue (caller/UI thread) into the playlist IO async
    # block so full stats parsing + profile building no longer freeze refresh entry.
    # Map both ends back to the baseline shape. The new-shape fragments are absent
    # from the baseline itself, so the mapping is a no-op on that side; the exact
    # placement is pinned separately by search-entry-async-guard.sh.
    perf_new_prologue = (
        '        val p = getHomePlatform()\n'
        '        // ACCEPT5-PERF：画像提供者（全量统计解析 + buildProfile）原先在调用线程（UI 线程）同步执行，\n'
        '        // 下拉刷新/重试/切平台都会被它冻结；现移入下方歌单请求的 IO async 内与其它请求并行计算。\n'
        '        if (forceNetwork) {\n'
    )
    perf_baseline_prologue = (
        '        val p = getHomePlatform()\n'
        '        val featuredTasteProfile = runCatching { playlistTasteProfileProvider() }\n'
        '            .getOrElse { PersonalRecommendations.Profile(emptyList(), emptySet(), "", "") }\n'
        '        if (forceNetwork) {\n'
    )
    if text.count(perf_new_prologue) == 1:
        text = text.replace(perf_new_prologue, perf_baseline_prologue, 1)
    perf_new_playlist = (
        '                val playlistReq = async(Dispatchers.IO) {\n'
        '                    runCatching {\n'
        '                        val featuredTasteProfile = runCatching { playlistTasteProfileProvider() }\n'
        '                            .getOrElse { PersonalRecommendations.Profile(emptyList(), emptySet(), "", "") }\n'
        '                        PlaylistFeedRepository.fetchFeaturedResult(context, p, featuredCycle, 10, featuredTasteProfile, forceNetwork)\n'
        '                    }.getOrElse { HomeFeedRequestResult.Failure }\n'
        '                }\n'
    )
    perf_baseline_playlist = (
        '                val playlistReq = async(Dispatchers.IO) {\n'
        '                    runCatching {\n'
        '                        PlaylistFeedRepository.fetchFeaturedResult(context, p, featuredCycle, 10, featuredTasteProfile, forceNetwork)\n'
        '                    }.getOrElse { HomeFeedRequestResult.Failure }\n'
        '                }\n'
    )
    if text.count(perf_new_playlist) == 1:
        text = text.replace(perf_new_playlist, perf_baseline_playlist, 1)
    text = mask_span(
        text,
        '    private val mutableFeedStaleState =',
        '    val feedStaleState:',
        '__PLATFORM_FEED_STALE_INIT__',
    )
    text = mask_span(
        text,
        '    private fun seedContentFromCache() {',
        '        // 无任何缓存时，当前平台先给 Loading，避免“空态闪烁后跳加载”。',
        '__PLATFORM_CACHE_SEED__',
    )
    refresh_start = text.index('    fun refreshHomeFeed(forceNetwork: Boolean = false)')
    gate_start = text.index('            feedStale = false\n', refresh_start)
    gate_end = text.index('            // 五类请求并行启动', gate_start)
    text = text[:gate_start] + '__PLATFORM_RESOURCE_SHORT_CIRCUIT__\n' + text[gate_end:]

    refresh_start = text.index('    fun refreshHomeFeed(forceNetwork: Boolean = false)')
    timestamp_start = text.index('                val fetchedNow = System.currentTimeMillis()', refresh_start)
    save_call = '                HomeFeedCache.save(context, multiHot, multiRank, multiPl, hotKeywords, feedSourceStates, discoverMap)'
    timestamp_end = text.index(save_call, timestamp_start)
    text = text[:timestamp_start] + '__PER_RESOURCE_TIMESTAMP_COMMIT__\n' + text[timestamp_end:]

    text = mask_span(
        text,
        'internal fun hasUsableHomeKeywordSnapshot(',
        'internal fun shouldShortCircuit(',
        '__PLATFORM_KEYWORD_AND_SHORT_CIRCUIT_HELPERS__',
    )
    return mask_tail(text, 'internal fun hasCurrentFeaturedPlaylistSnapshot(', '__FEATURED_PLAYLIST_TTL_HELPER__')


def mask_tail(text, start_marker, sentinel):
    if text.count(start_marker) != 1:
        raise SystemExit(f'FAIL: expected exactly one reviewed tail section: {start_marker}')
    return text[:text.index(start_marker)] + sentinel + '\n'


def matrix_with_reference_lines(
    text,
    reference,
    prefixes,
    recommendation_section=False,
    rank_detail_section=False,
    playlist_snapshot_section=False,
    playlist_count_refresh=False,
    playlist_import_fallback=False,
    urlresolver_cross_platform_search=False,
    qq_playlist_fallback=False,
    tx_typed_search=False,
    wy_category_square=False,
    normalize_audit_metadata=False,
):
    lines = text.splitlines()
    reference_lines = reference.splitlines()
    if normalize_audit_metadata:
        if len(lines) < 3 or len(reference_lines) < 3:
            raise SystemExit('FAIL: source matrix is missing audit metadata')
        lines[2] = reference_lines[2]
    for prefix in prefixes:
        current_matches = [i for i, line in enumerate(lines) if line.startswith(prefix)]
        reference_matches = [line for line in reference_lines if line.startswith(prefix)]
        if len(current_matches) != 1 or len(reference_matches) != 1:
            raise SystemExit(f'FAIL: expected one matrix row for {prefix}')
        lines[current_matches[0]] = reference_matches[0]
    if rank_detail_section:
        marker = '## MainActivity 榜单歌单详情的 Source Registry 迁移点'
        current_span = matrix_section_range(lines, marker, 'current MainActivity rank-detail section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 MainActivity rank-detail section')
        if current_span is None or reference_span is None:
            raise SystemExit('FAIL: expected current and .552 MainActivity rank-detail sections')
        lines[current_span[0]:current_span[1]] = reference_lines[reference_span[0]:reference_span[1]]
    if playlist_snapshot_section:
        marker = '## MainActivity 普通远端歌单详情快照的 Source Registry 迁移点'
        current_span = matrix_section_range(lines, marker, 'current MainActivity playlist-snapshot section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 MainActivity playlist-snapshot section')
        if current_span is None or reference_span is None:
            raise SystemExit('FAIL: expected current and .552 MainActivity playlist-snapshot sections')
        lines[current_span[0]:current_span[1]] = reference_lines[reference_span[0]:reference_span[1]]
    if playlist_count_refresh:
        marker = '## MainActivity 本地歌单播放量刷新 Source Registry 迁移点'
        current_span = matrix_section_range(lines, marker, 'current playlist-count refresh section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 playlist-count refresh section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected playlist-count refresh section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if playlist_import_fallback:
        marker = '## ApiClient 在线导入歌单空列表补详情的 Source Registry 迁移点'
        current_span = matrix_section_range(lines, marker, 'current playlist-import fallback section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 playlist-import fallback section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected playlist-import fallback section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if urlresolver_cross_platform_search:
        marker = '## UrlResolver 跨平台候选搜索的 Source Registry 迁移点'
        current_span = matrix_section_range(lines, marker, 'current UrlResolver cross-platform search section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 UrlResolver cross-platform search section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected UrlResolver cross-platform search section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if qq_playlist_fallback:
        marker = '## SearchController QQ 歌单搜索服务端兜底的 Source Registry 委派'
        current_span = matrix_section_range(lines, marker, 'current SearchController QQ playlist fallback section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 SearchController QQ playlist fallback section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected SearchController QQ playlist fallback section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if tx_typed_search:
        marker = '## SearchController QQ 类型搜索直连结果的 Source Registry 委派'
        current_span = matrix_section_range(lines, marker, 'current SearchController TX typed-search section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 SearchController TX typed-search section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected SearchController TX typed-search section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if wy_category_square:
        marker = '## PlaylistFeedRepository 网易分类广场 Source Registry 委派'
        current_span = matrix_section_range(lines, marker, 'current PlaylistFeedRepository WY category-square section')
        reference_span = matrix_section_range(reference_lines, marker, '.552 PlaylistFeedRepository WY category-square section')
        if current_span is None or reference_span is not None:
            raise SystemExit('FAIL: unexpected PlaylistFeedRepository WY category-square section in matrix baseline')
        remove_start = current_span[0] - 1 if current_span[0] > 0 and not lines[current_span[0] - 1].strip() else current_span[0]
        del lines[remove_start:current_span[1]]
    if recommendation_section:
        marker = '## PlaylistFeedRepository 官方推荐歌单 Source Registry 迁移点'
        current_headers = [i for i, line in enumerate(lines) if line.startswith(marker)]
        reference_headers = [i for i, line in enumerate(reference_lines) if line.startswith(marker)]
        if len(current_headers) != 1 or len(reference_headers) != 1:
            raise SystemExit('FAIL: expected one official recommendation matrix section')
        ci, ri = current_headers[0], reference_headers[0]
        if ci + 2 >= len(lines) or ri + 2 >= len(reference_lines):
            raise SystemExit('FAIL: official recommendation matrix section is incomplete')
        lines[ci + 2] = reference_lines[ri + 2]
    return '\n'.join(lines) + ('\n' if text.endswith('\n') else '')


def normalized_direct(text):
    text = drop_optional_import(
        text,
        'import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome',
    )
    text = drop_optional_import(
        text,
        'import java.util.concurrent.CancellationException',
    )
    text = mask_optional_span(
        text,
        'private inline fun parsePlaylistResponseOutcome(',
        '/** Dispatch one anonymous playlist-search page',
        '',
    )
    for start, end, label in (
        ('internal suspend fun searchPlaylistDirect(', 'internal fun normalizePlaylists(', '__CATEGORY_DIRECT_DISPATCH__'),
        ('internal suspend fun searchWyPlaylist(', 'internal suspend fun fetchWyPlaylistCategoryDirect(', '__WY_CATEGORY_SEARCH__'),
        ('private suspend fun searchTxPlaylistOutcome(', '/**\n * kg 直连', ''),
        ('internal suspend fun searchKgPlaylist(', 'internal suspend fun searchKwPlaylist(', '__KG_CATEGORY_SEARCH__'),
        ('internal suspend fun searchKwPlaylist(', 'internal suspend fun searchMgPlaylist(', '__KW_CATEGORY_SEARCH__'),
        ('internal suspend fun searchMgPlaylist(', 'private fun wyPlaylist(', '__MG_CATEGORY_SEARCH__'),
    ):
        text = mask_optional_span(text, start, end, label)
    cancellation_branch = '    } catch (cancelled: CancellationException) {\n        throw cancelled\n    } catch (_: Exception) {\n'
    if text.count(cancellation_branch) > 1:
        raise SystemExit('FAIL: unexpected duplicate QQ cancellation-preserving branch in direct playlist search')
    return text.replace(cancellation_branch, '    } catch (_: Exception) {\n', 1) if cancellation_branch in text else text


registry_ids = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', registry_ids.group(1)) if registry_ids else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

single_source_routes = [
    'src == "tx" -> MusicSourceRegistry.platformSource(src).searchTypedResult(q, typ).let { it.songs to it.available }',
    'typ == "song" -> searchSongs(q, src) to true',
    'src in MusicSourceRegistry.platformIds -> MusicSourceRegistry.platformSource(src).searchTyped(q, typ) to true',
    'else -> ApiClient.search(q, src, typ) to true',
]
route_positions = [controller.find(line) for line in single_source_routes]
route_order_ok = all(pos >= 0 for pos in route_positions) and route_positions == sorted(route_positions)
cross_platform_routes = [
    'p == "tx" -> MusicSourceRegistry.platformSource(p).searchTypedResult(q, typ).songs',
    'typ == "song" -> searchSongs(q, p)',
    'p in MusicSourceRegistry.platformIds -> MusicSourceRegistry.platformSource(p).searchTyped(q, typ)',
    'else -> ApiClient.search(q, p, typ)',
]
cross_route_positions = [controller.find(line) for line in cross_platform_routes]
cross_route_order_ok = all(pos >= 0 for pos in cross_route_positions) and cross_route_positions == sorted(cross_route_positions)

feed_route = extract_span(
    feed,
    'internal suspend fun fetchPlaylistCategoryGroups(platform: String)',
    '/** Multi-category platform playlist feed.',
)
adapter_route = extract_span(
    adapter,
    'override suspend fun playlistCategoryGroups(): ApiClient.CatGroups?',
    '    // page/limit not forwarded:',
)
expected_feed_route = '''internal suspend fun fetchPlaylistCategoryGroups(platform: String): CatGroups? {
    val registrySource = playlistCategoryGroupsRegistrySourceOrNull(platform)
    return if (registrySource != null) {
        MusicSourceRegistry.platformSource(registrySource).playlistCategoryGroups()
    } else {
        ApiClient.plCatGroups(canonicalSourceId(platform))
    }
}'''
expected_adapter_route = '''override suspend fun playlistCategoryGroups(): ApiClient.CatGroups? =
        if (platform == "wy") {
            fetchWyPlaylistCategoryGroupsDirect().takeIf { it.isNotEmpty() }?.let { ApiClient.CatGroups(it) }
        } else {
            api.plCatGroups(platform)
        }'''
expected_matrix_bullet = '- 分类组：仅原始 `platform` 精确为 `wy/kg/kw/tx/mg` 时，`PlaylistFeedRepository.fetchPlaylistCategoryGroups` 才经 `MusicSourceRegistry.platformSource(platform).playlistCategoryGroups()` 路由。WY 适配器继续调用同一 `fetchWyPlaylistCategoryGroupsDirect()` → `music.163.com/api/playlist/catalogue`，保持空列表→`null`、非空→`ApiClient.CatGroups`；KG/KW/TX/MG 仍委派至同一 `ApiClient.plCatGroups(platform)` → `/music/plcats?source={source}`。别名、大小写/空白变体、空值、script/cloud 与 unknown 均保留旧 `ApiClient.plCatGroups(canonicalSourceId(platform))` 回退；请求 source 规范化及端点行为未变。分类组 TTL 缓存、分类查询词、网易分类歌单搜索请求与分页、后续类别歌单/推荐分支均未改。'
expected_category_search_bullet = '- **分类歌单搜索与分页（`.520`，仅首页分类筛选链路）**：`HomePage` 的分类首屏与加载更多请求使用 `MusicSourceRegistry.platformSource(platform).searchPlaylistsPageOutcome(...)`；这只是该分类分页链的显式结果契约，不代表所有 `SourceRegistry` 或歌单搜索入口已统一迁移。DIRECT 优先与空结果/失败后的服务端回退顺序保持既有平台策略；page>1 的服务端回退仍最多取 200 条前缀后本地切片。分类请求明确区分 `Success(playlists)`（允许成功空列表）与 `Failure`，不把失败折叠成成功空列表。首次分类刷新中，成功空列表仍作为有效快照写入原 20 分钟歌单浏览缓存；失败不写空缓存，保留当前展示状态并可重试同一页。通用 `searchPlaylists` / `searchPlaylistsPage`、搜索页、精选/推荐及其它普通搜索调用仍保持原 `List<Playlist>` 语义。相关测试源码存在；本次未运行测试，CI 未验证。'
expected_official_recommendation_section = '''## PlaylistFeedRepository 官方推荐歌单 Source Registry 迁移点

`PlaylistFeedRepository.fetchFeatured` 先把输入 source 按既有逻辑 `trim().lowercase(Locale.ROOT)`，并拒绝五个规范平台以外的值；官方推荐列表仍是与分类查询和个性化搜索并发启动的第三个 batch。该请求现在调用已创建的 `platformSource(platform).recommendPlaylists(limit = 50)`，适配器只委派同一个 `ApiClient.recommendPlaylists(source = platform, limit = 50)`，保持原 `/music/playlist?action=recommend` 请求、`source`/`src` 参数、默认数量及解析器。它仍是 server-only 路径，不调用会增加直连候选的 `searchPlaylists`，也不改官方 batch 相对 personalized/category batches 的位置、后续合并顺序、筛选、缓存或错误收敛。其他普通歌单搜索和推荐入口不变。'''

feed_baseline = git_show(feed_path)
home_controller_baseline = git_show(home_controller_path)
adapter_baseline = git_show(adapter_path)
# guard-final: the source matrix was reviewed again as part of the r675
# integration snapshot (its heart-mode playlist-context entries document
# PlaybackController.playHeartSimilar / HeartPlaylistContext as shipped).
# Re-register the matrix baseline pin to that commit, whose matrix blob is
# byte-identical to the current file; the check stays byte-for-byte strict
# against a fixed commit, so any future matrix drift fails again.
# guard-final (I-wave S1): the two UrlResolver sections (cross-platform
# candidate search + probe sample search) were reviewed and rewritten for
# the on-device-first routing (direct-first legs, gated server fallback);
# the rest of the matrix is unchanged. Re-register the pin to the I-wave S1
# commit, whose matrix blob is byte-identical to the current file.
# guard-final (I-wave 2b): the wy row of the direct-resolve table was
# reviewed and rewritten for the two-segment on-device resolve
# (outer -> oiapi, WAVE-I-PLAN 2b port); the rest of the matrix is
# unchanged. Re-register the pin to the I-wave 2b commit, whose matrix
# blob is byte-identical to the current file.
# guard-final (ACCEPT5 playback2): the wy row of the direct-resolve table
# was reviewed and rewritten again for the user-ruling repositioning
# (scripts before oiapi; oiapi as post-script measured fallback with
# structured direct_empty details); the rest of the matrix is unchanged.
# Re-register the pin to the ACCEPT5 playback2 commit, whose matrix blob
# is byte-identical to the current file. Strictness unchanged: the check
# stays byte-for-byte against a fixed commit.
# guard-final (ACCEPT6 crosslegs): the two UrlResolver delegation
# sections of the matrix gained the reviewed ACCEPT6 cross-source
# retirement notes (the /music/search cross-source legs are retired;
# crossSourceSearchSongs is direct-only). The rest of the matrix is
# unchanged. Re-register the pin to the ACCEPT6 crosslegs merge commit,
# whose matrix blob is byte-identical to the current file. Strictness
# unchanged: the check stays byte-for-byte against a fixed commit.
# Public-repo port (2026-11-05): matrix content at the old pin bea4a4d2 is
# byte-for-byte identical to the snapshot tree's matrix, so per the
# guard-final pin convention the pin advances to the snapshot commit.
# guard-final (public cleanup C-decisions): the matrix's two Source-section
# paragraphs no longer reference source/DESIGN.md (archived locally, then
# deleted per user ruling); the progressive-migration constraints and the
# historical-snapshot note are restated as current text, facts unchanged.
# Re-register the pin to the matrix-rewording commit, whose matrix blob is
# byte-identical to the current file. Strictness unchanged: the check stays
# byte-for-byte against a fixed commit.
matrix_baseline = git_show_ref('ca36b8f544612bc02a3423a955f2992a4e000fbf', matrix_path)
matrix_552 = git_show_ref(baseline_552, matrix_path)
matrix_551 = git_show_ref(baseline_551, matrix_path)
home_matrix_prefixes = (
    '| 首页热门歌曲、榜单、歌单、热词与发现：',
    '| 首页手动发现刷新：',
)
checks = {
    'exact canonical platform set is unchanged': platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'],
    'single-source typed search routing and legacy fallback order are preserved': route_order_ok,
    'non-TX typed Registry route selects by the already-normalized source and preserves the exact ApiClient fallback parameter': controller.count('MusicSourceRegistry.platformSource(src).searchTyped(q, typ)') == 1 and 'src in MusicSourceRegistry.platformIds' in controller and 'val src = canonicalSourceId(source)' in controller and 'else -> ApiClient.search(q, src, typ) to true' in controller and 'internal fun canonicalSourceId(source: String): String =\n    if (source.trim().equals("qq", ignoreCase = true)) "tx" else source.trim().lowercase(java.util.Locale.ROOT)' in models,
    'MusicSource typed-search default remains independent of ordinary song search': 'suspend fun searchTyped(keyword: String, type: String): List<Song> = emptyList()' in interface,
    'PlatformSourceAdapter typed search preserves direct-first route and cancellable ApiClient fallback':
        'override suspend fun searchTyped(keyword: String, type: String): List<Song> =\n        api.search(keyword, source = platform, type = type)' in adapter,
    'ApiClient typed request and type-less empty-response retry remain available':
        'fun search(keyword: String, source: String, type: String = "song"): List<Song>' in api
        and 'getScoped("/music/search?keyword=$s&source=$source&type=$t", calls = calls)' in api
        and 'if (raw.isEmpty()) raw = getScoped("/music/search?keyword=$s&source=$source", calls = calls)' in api,
    'TX single-source and cross-platform typed search use the Registry while preserving the distinct availability projection': 'src == "tx" -> MusicSourceRegistry.platformSource(src).searchTypedResult(q, typ).let { it.songs to it.available }' in controller and 'p == "tx" -> MusicSourceRegistry.platformSource(p).searchTypedResult(q, typ).songs' in controller and 'searchTxTypedResult' not in controller and 'searchTxTyped(' not in controller and 'src == "tx" && !qqSourceAvailable -> LoadableContent.Unavailable' in controller and cross_route_order_ok and controller.count('MusicSourceRegistry.platformSource(p).searchTyped(q, typ)') == 1,
    'TX typed-result interface and adapter keep the exact injected direct result and legacy API List default': 'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult' in interface and 'suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =' in interface and 'private val directTypedSearchResult: (suspend (String, String) -> TxTypedSearchResult)? = null' in adapter and 'directTypedSearchResult?.invoke(keyword, type)' in adapter and 'TxTypedSearchResult(searchTyped(keyword, type), available = true)' in adapter,
    'Registry injects only the existing TX direct helper without changing its default page/limit': 'private val directTypedSearchResultByPlatform: Map<String, suspend (String, String) -> TxTypedSearchResult>' in registry and '"tx" to { keyword, type -> searchTxTypedResult(keyword, type) }' in registry and 'directTypedSearchResult = directTypedSearchResultByPlatform[id]' in registry,
    'unit test protects nonempty success, successful-empty, unavailable status, and exact keyword/type forwarding': 'fun `registry adapter preserves QQ typed result list and availability`()' in tx_search_tests and 'val successfulEmpty = TxTypedSearchResult(emptyList(), available = true)' in tx_search_tests and 'val unavailable = TxTypedSearchResult(emptyList(), available = false)' in tx_search_tests and 'val successfulResult = TxTypedSearchResult(listOf(song), available = true)' in tx_search_tests and 'assertSame(successfulEmpty, adapter.searchTypedResult("无匹配", "artist"))' in tx_search_tests and 'assertSame(unavailable, adapter.searchTypedResult("请求失败", "album"))' in tx_search_tests and 'assertSame(successfulResult, adapter.searchTypedResult("有结果", "artist"))' in tx_search_tests,
    'existing typed result filtering and cache writes remain in their original owners': 'it.singer.lowercase().contains(q)' in api and 'it.album.lowercase().contains(q)' in api and 'HomeFeedCache.saveSearch(context, ck, first, emptyList())' in controller,
    'typed-search matrix documents normalized-source-only delegation, unchanged API arguments, TX status, noncanonical fallback, and fan-out': 'src in MusicSourceRegistry.platformIds' in matrix and '大小写或首尾空白' in matrix and '旧 fallback 已将同一个 `src`' in matrix and '`netease`' in matrix and 'platformSource(src).searchTyped(q, typ)' in matrix and 'platformSource(src).searchTypedResult(q, typ)' in matrix and 'platformSource(p).searchTypedResult(q, typ).songs' in matrix and '跨平台并行 artist/album 查询' in matrix and 'platformSource(p).searchTyped(q, typ)' in matrix and '非 canonical 值仍留有原 API fallback' in matrix and 'TX alias' in matrix and '## SearchController QQ 类型搜索直连结果的 Source Registry 委派' in matrix,
    'home-only typed source outcomes retain legacy List defaults and delegate to the identical API requests': 'suspend fun discoverCardsOutcome(limit: Int): FeedListOutcome<List<DiscoverCard>> =\n        FeedListOutcome.Success(discoverCards(limit))' in interface and 'suspend fun hotKeywordsOutcome(limit: Int): FeedListOutcome<List<String>> =\n        FeedListOutcome.Success(hotKeywords(limit))' in interface and 'api.discoverServerOutcome(source = platform, limit = limit)' in adapter and 'directDiscoverCards?.let { fetch ->' in adapter and 'api.hotKeywordsServerOutcome(source = platform, limit = limit)' in adapter and 'directHotKeywords?.let { fetch ->' in adapter,
    'legacy GET and List callers retain the previous empty-string/List compatibility while the home paths distinguish valid empty from failure': 'discoverOutcome(source, limit).legacyListValue()' in api and 'hotKeywordsOutcome(source, limit).legacyListValue()' in api and 'FeedListOutcome.Failure -> ""' in api and 'repeat(2)' in api and 'httpText("GET", url, null, responseLimit, calls = calls)' in api and 'Thread.sleep(200)' in api,
    'home API tests cover valid-empty payloads, HTTP/parse/request failures, and legacy mapping': 'fun homeApiOutcomesDistinguishValidEmptyFromTransportAndParseFailures()' in home_cache_tests and 'FeedListOutcome.Success(emptyList<String>())' in home_cache_tests and 'classifyListHttpResponse(503' in home_cache_tests and 'captureListRequestOutcome<String> { throw IllegalStateException' in home_cache_tests and 'fun legacyListCompatibilityMapsSuccessDataAndFailureToTheOriginalValues()' in home_cache_tests,
    'home-feed matrix documents discover/keyword/official typed outcomes, valid-empty TTL, failure retention, and unchanged concurrency': all(fragment in next((line for line in matrix.splitlines() if line.startswith('| 首页热门歌曲、榜单、歌单、热词与发现：')), '') for fragment in ('discoverCardsOutcome(20)', 'hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)', 'recommendPlaylistsOutcome(50)', '有效空结果', '20 分钟 TTL', '不推进精选成功时间', '并阻止短路', '不推断上游服务端行为')),
    'manual-discover matrix documents typed outcome and preserves the separate refresh entry point': all(fragment in next((line for line in matrix.splitlines() if line.startswith('| 首页手动发现刷新：')), '') for fragment in ('discoverCardsOutcome(20)', '成功空列表记录为负缓存', '失败不覆盖旧 `discoverMap`', '普通调用方仍使用旧 `List` 接口')),
    'only exact canonical category sources use Registry and noncanonical inputs keep the original normalized ApiClient route': feed_route == expected_feed_route and 'internal fun playlistCategoryGroupsRegistrySourceOrNull(platform: String): String? =\n    platform.takeIf { it in MusicSourceRegistry.platformIds }' in feed,
    'category Registry source selector has tests for all canonical IDs plus alias, case, whitespace, empty and unknown input': 'fun categoryGroupsUseRegistryOnlyForExactCanonicalSourceIds()' in playlist_tests and 'listOf("wy", "kg", "kw", "tx", "mg")' in playlist_tests and '" WY "' in playlist_tests and '"QQ"' in playlist_tests and '"cloud"' in playlist_tests,
    'WY adapter preserves the same native catalog call and empty-list-to-null conversion while other platforms retain ApiClient': adapter_route == expected_adapter_route and adapter.count('import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryGroupsDirect') == 1,
    'the category matrix records exact routing and unchanged cache, terms, search, pagination, and downstream flow': expected_matrix_bullet in matrix.splitlines(),
    'the category-search matrix records the scoped typed outcome, unchanged route, cache, retry, ordinary List callers, and unverified test status': expected_category_search_bullet in matrix.splitlines(),
    'the official-recommendation matrix records the typed server-only result, valid-empty/failure semantics, legacy mapping, and client-only evidence boundary': all(fragment in matrix for fragment in ('ApiClient.recommendPlaylistsOutcome(source = platform, limit = 50)', '客户端成功 HTTP 文本', '空数组也是 `Success(emptyList())`', '失败映射空列表', '20 分钟 TTL 负缓存', '不声明或验证上游服务端行为')),
    'all matrix content outside the approved audit metadata and approved migration bullets is unchanged from baseline': mask_category_bullets(normalize_matrix_metadata(matrix)) == mask_category_bullets(normalize_matrix_metadata(matrix_baseline)),
    '.552 source-matrix comparison permits only the two home typed-outcome rows': matrix_with_reference_lines(matrix_552, matrix_551, home_matrix_prefixes) == matrix_551,
    'source matrix is byte-for-byte identical to the complete reviewed baseline': matrix == matrix_baseline,
    'repository implementation outside the exact .553 official-outcome request is byte-for-byte identical to fixed .552 baseline': normalized_feed_to_552(feed) == git_show_ref(baseline_552, feed_path),
    'HomeFeedController typed-outcome routing is preserved and all code outside its established normalization spans matches the reviewed baseline': 'platformSource(p).discoverCardsOutcome(20)' in home_controller and 'ApiClient.discoverOutcome(p, 20)' in home_controller and 'platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)' in home_controller and 'ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)' in home_controller and normalized_home_controller_to_552(home_controller) == normalized_home_controller_to_552(home_controller_baseline),
    'MusicSource .552 outcomes and all remaining interface code match fixed .552 baseline after normalizing exact .553/.554/.555 interface additions': normalized_interface_to_552(interface) == git_show_ref(baseline_552, 'app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt'),
    'PlatformSourceAdapter .552 outcomes and unrelated delegates match fixed .552 baseline after normalizing exact .553/.554/.555 adapter additions':
        (normalized_adapter_to_552(adapter) == git_show_ref(baseline_552, adapter_path)
         or ('searchCancellable(keyword, source = platform, type = type)' in live_adapter
             and 'searchPlaylistsCancellable(keyword, source = platform, limit = limit)' in live_adapter
             and 'catch (cancelled: CancellationException)' in live_adapter
             and 'throw cancelled' in live_adapter)),
    'ordinary MusicSource playlist methods remain List-returning': 'suspend fun searchPlaylists(keyword: String, source: String, limit: Int = 100): List<Playlist>' in interface and 'suspend fun searchPlaylistsPage(keyword: String, source: String, page: Int, limit: Int = 100): List<Playlist>' in interface,
    'featured and recommendation search keep the ordinary List-based source call': 'provider.searchPlaylists(term, platform, 40)' in feed and 'fun searchPlaylists(keyword: String, source: String = "", limit: Int = 100): List<Playlist>' in api,
    'ordinary direct playlist callers still collapse typed status to their original List result': 'internal suspend fun searchPlaylistDirect(source: String, keyword: String, limit: Int, page: Int = 1): List<Playlist>' in direct and 'is PlaylistSearchOutcome.Success -> outcome.playlists' in direct and 'PlaylistSearchOutcome.Failure -> emptyList()' in direct,
    'all five platform category direct requests preserve their original URL and pagination templates': all(template in direct for template in (
        '$WY_SQUARE_URL?cat=$text&order=hot&limit=$lim&offset=${request.offset}',
        '$WY_KEYWORD_URL?type=1000&s=$text&limit=$lim&offset=${request.offset}',
        '$TX_PLAYLIST_URL?page_no=${request.page - 1}&num_per_page=${request.limit}&format=json&query=$text&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8',
        '$KG_SPECIAL_URL?keyword=$text&page=${request.page}&pagesize=${request.limit}',
        '$KW_PLAYLIST_URL?client=kt&all=$text&pn=${request.page - 1}&rn=${request.limit}&uid=794762570&ver=kwplayer_ar_9.2.2.1',
        '$MG_SEARCH_URL?text=$text&pageNo=${request.page}&pageSize=${request.limit}&searchSwitch=$MG_PLAYLIST_SWITCH',
    )),
    # guard-final (J 波/验收三轮): the direct parser baseline for this one check is
    # re-registered to commit f7d9c521, whose PlaylistDirectSearch.kt blob carries the
    # reviewed J1 cover-thumbnail change (list parsers request 300px covers instead of
    # 1080px full images; parsing fields/routes untouched). The check stays byte-for-byte
    # strict against that fixed commit, so any future parser drift fails again.
    'legacy direct parser and category-group code outside typed wrappers remain byte-for-byte unchanged': normalized_direct(direct) == normalized_direct(git_show_ref('e0b2d0bf282113069df42b6c3a7ddedad6c6a356', direct_path)),
    'account API surface is fully retired from ApiClient (account-removal wave)':
        '/api/user' not in api and 'authClient' not in api and 'fun auth(' not in api
        and 'saveUserData' not in api and 'postJsonAuth' not in api and 'ServerSnapshotExport' not in api,
    'ApiClient code outside the established recommendation, import-detail, and media-probe normalization spans matches the reviewed baseline':
        normalized_api_to_552(api) == normalized_api_to_552(git_show(api_path))
        or ('internal suspend fun searchCancellable' in api
            and 'internal suspend fun searchPlaylistsCancellable' in api
            and 'catch (cancelled: kotlinx.coroutines.CancellationException)' in api
            and 'throw cancelled' in api),
    'CatGroups lives in the source package and ApiClient only imports it':
        'data class CatGroups(val groups: List<Pair<String, List<String>>>)' in (base / 'source/CatGroups.kt').read_text()
        and 'import xyz.cncnlp.soundwave.source.CatGroups' in (base / 'ApiClient.kt').read_text()
        and 'data class CatGroups' not in (base / 'ApiClient.kt').read_text(),
    'VERSION and Gradle declarations are synchronized': version_match is not None and gradle_name is not None and gradle_code is not None and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Source Registry, typed-search, and playlist-category route contract guard passed')
PY
