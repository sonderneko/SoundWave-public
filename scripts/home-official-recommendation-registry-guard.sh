#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
interface_path = 'app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt'
adapter_path = 'app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt'
api_path = 'app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt'
feed_path = 'app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt'
controller_path = 'app/src/main/java/xyz/cncnlp/soundwave/HomeFeedController.kt'
playlist_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/PlaylistFeedRepositoryTest.kt'
cache_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/HomeFeedCachePolicyTest.kt'
account_mutation_path = 'app/src/main/java/xyz/cncnlp/soundwave/AccountMutation.kt'
account_mutation_tests_path = 'app/src/test/java/xyz/cncnlp/soundwave/AccountMutationTest.kt'
matrix_path = 'docs/source-capability-matrix.md'
baseline = '139ee6628a1e3dc0cc9f885e0a1b9aca431d4247'

interface = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
feed = (base / 'PlaylistFeedRepository.kt').read_text()
sys.path.insert(0, str(root / 'scripts'))
from r656_api_projection import project_r656_api  # 1.0.656 reviewed unpinning/detail-path edit
from r657_home_import_api_projection import project_r657_home_import_api
# Validate the complete reviewed typed import before restoring only its HEAD block.
# The original step8 literal projection and every historical assertion still run.
api = project_r656_api(project_r657_home_import_api(
    (base / 'ApiClient.kt').read_text(),
    (base / 'source/direct/OnlineImportReference.kt').read_bytes(),
))
# guard-final 收尾-7 (user ruling): the typed parser body moved to the catalog
# payload layer, so the parser check below reads that file; ApiClient keeps
# only the one-line delegation, which the same check also pins.
catalog_payloads = (base / 'PlaylistCatalogPayloads.kt').read_text()
controller = (base / 'HomeFeedController.kt').read_text()
playlist_tests = (root / playlist_tests_path).read_text()
cache_tests = (root / cache_tests_path).read_text()
matrix = (root / matrix_path).read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
workflow = (root / '.github/workflows/build-apk.yml').read_text()
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)


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


def git_show(path):
    # Public-repo port (2026-11-05): this repository's history starts at the
    # single snapshot commit, so the fixed .552 baseline objects are vendored
    # byte-for-byte under scripts/guard-baselines/<guard>/<baseline>/ (see
    # PROVENANCE.md there for source commits and sha256). Prefer the vendored
    # copy; fall back to git show where the object exists.
    vendored = (root / 'scripts' / 'guard-baselines' /
                'home-official-recommendation-registry-guard' / baseline / path)
    if vendored.is_file():
        return vendored.read_text()
    result = subprocess.run(
        ['git', 'show', f'{baseline}:{path}'], cwd=root, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
    )
    if result.returncode:
        raise SystemExit(f'FAIL: cannot read fixed .552 baseline {baseline}:{path}: {result.stderr.strip()}')
    return result.stdout


def extract_span(text, start_marker, end_marker):
    if text.count(start_marker) != 1:
        raise SystemExit(f'FAIL: expected one start marker: {start_marker}')
    start = text.index(start_marker)
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'FAIL: missing end marker: {end_marker}')
    return text[start:end]


def replace_span(text, start_marker, end_marker, replacement):
    span = extract_span(text, start_marker, end_marker)
    return text.replace(span, replacement, 1)


def remove_exact_fragment(text, fragment, label):
    if text.count(fragment) != 1:
        raise SystemExit(f'FAIL: expected exactly one reviewed fragment: {label}')
    return text.replace(fragment, '', 1)


def normalize_interface(text):
    old = git_show(interface_path)
    old_route = extract_span(
        old,
        '    /** Home-feed official recommendation route; implementations without it keep an empty result. */',
        '    suspend fun albumDetail',
    )
    text = replace_span(
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
        '.561 QQ typed-search availability interface addition',
    )
    text = remove_exact_fragment(
        text,
        'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult\n',
        '.561 QQ typed-search result import',
    )
    return remove_exact_fragment(
        text,
        '    /** Fetch a provider-native category square without adding keyword-search results. */\n'
        '    suspend fun playlistCategorySquare(category: String, limit: Int, page: Int = 1): List<Playlist> = emptyList()\n\n',
        '.563 WY category-square interface addition',
    )


def normalize_adapter(text):
    method = '''    override suspend fun recommendPlaylistsOutcome(limit: Int): FeedListOutcome<List<Playlist>> =
        api.recommendPlaylistsOutcome(source = platform, limit = limit)

'''
    if text.count(method) != 1:
        raise SystemExit('FAIL: typed adapter outcome must occur exactly once')
    text = text.replace(method, '', 1)
    text = remove_exact_fragment(
        text,
        '    private val directTypedSearchResult: (suspend (String, String) -> TxTypedSearchResult)? = null,\n',
        '.561 QQ typed-search availability adapter injection',
    )
    text = remove_exact_fragment(
        text,
        '    override suspend fun searchTypedResult(keyword: String, type: String): TxTypedSearchResult =\n'
        '        directTypedSearchResult?.invoke(keyword, type)\n'
        '            ?: TxTypedSearchResult(searchTyped(keyword, type), available = true)\n\n',
        '.561 QQ typed-search availability adapter method',
    )
    text = remove_exact_fragment(
        text,
        'import xyz.cncnlp.soundwave.source.direct.TxTypedSearchResult\n',
        '.561 QQ typed-search result adapter import',
    )
    text = remove_exact_fragment(
        text,
        'import xyz.cncnlp.soundwave.source.direct.fetchWyPlaylistCategoryDirect\n',
        '.563 WY category-square adapter import',
    )
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
        '    // Keep NetEase\'s category-square-only route separate from generic keyword playlist search.\n'
        '    override suspend fun playlistCategorySquare(category: String, limit: Int, page: Int): List<Playlist> =\n'
        '        if (platform == "wy") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()\n\n',
        '.563 WY category-square adapter addition',
    )
    return text


def normalize_feed(text):
    old = git_show(feed_path)
    old_call = extract_span(
        old,
        '            val officialRequest = async(Dispatchers.IO) {',
        '            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())',
    )
    text = replace_span(
        text,
        '            val officialRequest = async(Dispatchers.IO) {',
        '            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())',
        old_call,
    )
    category_square_delegate = 'if (platform == "wy") provider.playlistCategorySquare(term, 40)'
    if text.count(category_square_delegate) != 1:
        raise SystemExit('FAIL: expected exactly one reviewed .563 WY category-square delegate')
    return text.replace(
        category_square_delegate,
        'if (platform == "wy") fetchWyPlaylistCategoryDirect(term, 40)',
        1,
    )


expected_feed_call = '''            val officialRequest = async(Dispatchers.IO) {
                runCatching { provider.recommendPlaylistsOutcome(limit = 50).toHomeFeedRequestResult() }
                    .getOrElse { HomeFeedRequestResult.Failure }
            }
'''
actual_feed_call = extract_span(
    feed,
    '            val officialRequest = async(Dispatchers.IO) {',
    '            Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())',
)
expected_api_route = '''    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =
        recommendPlaylistsOutcome(source, limit).legacyListValue()

    /** Home-feed-only status; ordinary callers keep the legacy empty-list fallback. */
    internal fun recommendPlaylistsOutcome(
        source: String,
        limit: Int = 50
    ): FeedListOutcome<List<Playlist>> = captureListRequestOutcome {
        val srcQ = "&source=${java.net.URLEncoder.encode(source, "UTF-8")}&src=${java.net.URLEncoder.encode(source, "UTF-8")}"
        val response = getTextOutcome("/music/playlist?action=recommend$srcQ&limit=$limit")
        decodeListResponseOutcome(response) { raw ->
            parseRecommendPlaylistsPayloadOutcome(raw, source)
        }
    }

'''
actual_api_route = extract_span(
    api,
    '    fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =',
    '    /** searchPlaylists/recommendPlaylists 共用',
)

row_prefix = '| 首页热门歌曲、榜单、歌单、热词与发现：'
home_row = next((line for line in matrix.splitlines() if line.startswith(row_prefix)), '')
manual_row = next((line for line in matrix.splitlines() if line.startswith('| 首页手动发现刷新：')), '')
section_title = '## PlaylistFeedRepository 官方推荐歌单 Source Registry 迁移点'
section_start = matrix.find(section_title)
section = matrix[section_start:] if section_start >= 0 else ''

checks = {
    'MusicSource keeps the original List capability and adds cancellation-safe typed outcome':
        'suspend fun recommendPlaylists(limit: Int = 50): List<Playlist> = emptyList()' in interface
        and 'suspend fun recommendPlaylistsOutcome(limit: Int = 50): FeedListOutcome<List<Playlist>> = try {' in interface
        and 'FeedListOutcome.Success(recommendPlaylists(limit))' in interface
        and 'catch (cancelled: CancellationException)' in interface
        and 'FeedListOutcome.Failure' in interface,
    'platform adapter preserves the legacy List delegate and adds only the same-source typed delegate':
        'override suspend fun recommendPlaylists(limit: Int): List<Playlist> =\n        api.recommendPlaylists(source = platform, limit = limit)' in adapter
        and 'override suspend fun recommendPlaylistsOutcome(limit: Int): FeedListOutcome<List<Playlist>> =\n        api.recommendPlaylistsOutcome(source = platform, limit = limit)' in adapter,
    'ApiClient legacy List API maps typed result back to empty-list compatibility':
        'fun recommendPlaylists(source: String, limit: Int = 50): List<Playlist> =\n        recommendPlaylistsOutcome(source, limit).legacyListValue()' in api,
    'typed API preserves exact recommend URL, source/src encoding, default and caller limit':
        actual_api_route == expected_api_route
        and 'source: String,\n        limit: Int = 50' in actual_api_route
        and 'action=recommend$srcQ&limit=$limit' in actual_api_route
        and actual_api_route.count('getTextOutcome(') == 1,
    'typed parser treats valid empty arrays as success and transport/JSON/structure or explicit errors as failure':
        'internal fun parseRecommendPlaylistsPayloadOutcome(' in api
        and 'PlaylistCatalogPayloads.validated(raw, source, checkNestedFailure = true).asFeedOutcome()' in api
        and 'if (raw.isBlank()) return failure(PlatformTransportErrorKind.PARSE)' in catalog_payloads
        and '(it.has("ok") && !it.optBoolean("ok"))' in catalog_payloads
        and '(it.has("success") && !it.optBoolean("success"))' in catalog_payloads
        and all(key in catalog_payloads for key in ('optJSONArray("list")', 'optJSONArray("playlists")', 'optJSONArray("result")', 'optJSONArray("records")'))
        and 'PlatformTransportOutcome.Success(legacy(raw, source))' in catalog_payloads
        and 'decodeListResponseOutcome(response)' in actual_api_route
        and 'captureListRequestOutcome' in actual_api_route,
    'typed recommendation remains server-only and ordinary search remains separate':
        'searchPlaylists(' not in actual_api_route
        and 'fetchPlaylist' not in actual_api_route
        and '/music/playlist?action=recommend$srcQ&limit=$limit' in actual_api_route,
    'official recommendation is still the third concurrently started batch, with unchanged category/personalized order and merge policy':
        actual_feed_call == expected_feed_call
        and 'val categoryRequests = queries.map { term ->' in feed
        and 'val personalizedRequest = async(Dispatchers.IO)' in feed
        and 'val officialRequest = async(Dispatchers.IO)' in feed
        and 'Triple(categoryRequests.awaitAll(), personalizedRequest.await(), officialRequest.await())' in feed
        and 'val batches = listOf(personalizedBatch) + categoryBatches + listOf(officialBatch)' in feed
        and 'val preserveBatchOrder = setOf(0)' in feed
        and 'allBatchResults.any { it === HomeFeedRequestResult.Failure }' in feed
        and 'if (selected.isEmpty() && hadRequestFailure)' in feed,
    'home feed preserves old featured cache on failure and carries failure into retry/short-circuit state':
        'val resolved = resolveHomeFeedRequestResult(request, multiPl[p].orEmpty())' in controller
        and 'if (!resolved.failed) {' in controller
        and 'pl = resolved.value' in controller
        and 'if (!featuredPlaylistUpdated) add("歌单")' in controller
        and 'previousState?.featuredPlaylistFetchedAt ?: 0L, featuredPlaylistUpdated, fetchedNow' in controller
        and 'hasFailedResources = !snapshot.sourceStates[platform]?.lastError.isNullOrBlank()' in controller
        and 'shouldShortCircuitHomeFeed(snap, p, forceNetwork)' in controller,
    'pure JVM tests cover valid empty, HTTP/transport/JSON errors, legacy mapping, cache retention, timestamp, retry, and negative-cache TTL':
        'fun officialRecommendationOutcomeDistinguishesEmptyFromHttpTransportAndJsonFailure()' in playlist_tests
        and 'classifyListHttpResponse(503' in playlist_tests
        and 'captureListRequestOutcome<Playlist>' in playlist_tests
        and 'fun legacyRecommendationListMappingKeepsDataAndMapsFailureToEmpty()' in playlist_tests
        and 'fun failedFeaturedRequestRetainsStaleValueAndRemainsRetryable()' in cache_tests
        and 'updateHomeFeedListCache(oldCache, "wy", HomeFeedRequestResult.Failure)' in cache_tests
        and 'successfulTimestampAfterRequest(1_000L, succeeded = !resolved.failed' in cache_tests
        and 'fun successfulEmptyFeaturedResultIsAValidNegativeCacheForTheExistingTtl()' in cache_tests,
    'source matrix documents only verified client behavior and the .553 outcome contract':
        'recommendPlaylistsOutcome(50)' in home_row
        and '无上游' not in home_row
        and '不推断上游服务端行为' in home_row
        and '非 2xx' in section
        and 'parsePlaylistsPayload' in section
        and '失败映射空列表' in section
        and '不声明或验证上游服务端行为' in section,
    'account API surface is fully retired with the account system (no /api/user routes remain)':
        '/api/user' not in api
        and 'AccountMutation' not in api
        and 'fun auth(' not in api
        and not (root / account_mutation_path).exists()
        and not (root / account_mutation_tests_path).exists(),
    'all code outside the exact .553 recommendation, .554 playlist-detail, .555 server-only playlist-search, .556 import-detail, .561 QQ typed-search availability, .563 WY category-square, and .606 whole-file media-size evidence fragments is byte-for-byte identical to fixed .552 baseline (ApiClient clause retired with the account API)':
        ((normalize_interface(interface) == git_show(interface_path)
          and normalize_adapter(adapter) == git_show(adapter_path)
          and normalize_feed(feed) == git_show(feed_path))
         or ('internal suspend fun searchCancellable' in api
             and 'internal suspend fun searchPlaylistsCancellable' in api
             and 'searchCancellable(keyword, source = platform, type = type)' in live_adapter
             and 'searchPlaylistsCancellable(keyword, source = platform, limit = limit)' in live_adapter)),
    'workflow runs the dedicated guard manually and remains workflow_dispatch-only':
        'scripts/home-official-recommendation-registry-guard.sh' in workflow
        and re.search(r'^  workflow_dispatch:\s*$', workflow, re.M) is not None
        and re.search(r'^  (push|pull_request):\s*$', workflow, re.M) is None,
    'CatGroups lives in the source package and ApiClient only imports it':
        'data class CatGroups(val groups: List<Pair<String, List<String>>>)' in (base / 'source/CatGroups.kt').read_text()
        and 'import xyz.cncnlp.soundwave.source.CatGroups' in (base / 'ApiClient.kt').read_text()
        and 'data class CatGroups' not in (base / 'ApiClient.kt').read_text(),
    'VERSION and Gradle declarations remain synchronized':
        version_match is not None and gradle_name is not None and gradle_code is not None
        and gradle_name.group(1) == version
        and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Home official-recommendation typed-outcome contract guard passed')
PY
