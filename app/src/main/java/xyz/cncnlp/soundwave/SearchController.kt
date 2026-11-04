package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.direct.searchTxPlaylistResult

internal suspend fun <T> preserveCancellationOrDefault(fallback: T, block: suspend () -> T): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fallback
    }

private data class SearchBatch<T>(val rows: List<T>, val available: Boolean, val failed: Boolean)
private data class SearchAllBatch(
    val songs: List<SearchBatch<Song>>,
    val playlists: List<SearchBatch<Playlist>>,
    val albums: List<SearchBatch<Song>>,
    val artists: List<SearchBatch<Song>>
)

/**
 * 搜索单点：从 AppRoot 抽出。持有搜索结果/关键词/历史/歌单结果与跨平台开关，统一搜索与缓存逻辑。
 */
class SearchController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
) {
    val searchResultsState = mutableStateOf(listOf<Song>())
    var searchResults: List<Song>
        get() = searchResultsState.value
        set(v) { searchResultsState.value = v }
    val searchKwState = mutableStateOf("")
    var searchKw: String
        get() = searchKwState.value
        set(v) { searchKwState.value = v }
    val searchResultState = mutableStateOf<AppRoute.SearchResult?>(null)
    var searchResult: AppRoute.SearchResult?
        get() = searchResultState.value
        set(v) { searchResultState.value = v }
    val searchHistoryState = mutableStateOf(listOf<String>())
    var searchHistory: List<String>
        get() = searchHistoryState.value
        set(v) { searchHistoryState.value = v }
    val searchPlResultsState = mutableStateOf(listOf<Playlist>())
    var searchPlResults: List<Playlist>
        get() = searchPlResultsState.value
        set(v) { searchPlResultsState.value = v }
    val artistResultsState = mutableStateOf(listOf<Song>())
    var artistResults: List<Song>
        get() = artistResultsState.value
        set(v) { artistResultsState.value = v }
    val albumResultsState = mutableStateOf(listOf<Song>())
    var albumResults: List<Song>
        get() = albumResultsState.value
        set(v) { albumResultsState.value = v }
    val crossPlatformSearchState = mutableStateOf(prefs.getBoolean("cross_search", false))
    var crossPlatformSearch: Boolean
        get() = crossPlatformSearchState.value
        set(v) { crossPlatformSearchState.value = v }

    // ---- LoadableContent 统一搜索状态（任务 6）：区分真实空、缓存、网络失败 ----
    val searchContentState = mutableStateOf<LoadableContent<List<Song>>>(LoadableContent.Empty())
    val plContentState = mutableStateOf<LoadableContent<List<Playlist>>>(LoadableContent.Empty())
    var searchContent: LoadableContent<List<Song>>
        get() = searchContentState.value
        set(v) { searchContentState.value = v }
    var plContent: LoadableContent<List<Playlist>>
        get() = plContentState.value
        set(v) { plContentState.value = v }
    val artistContentState = mutableStateOf<LoadableContent<List<Song>>>(LoadableContent.Empty())
    var artistContent: LoadableContent<List<Song>>
        get() = artistContentState.value
        set(v) { artistContentState.value = v }
    val albumContentState = mutableStateOf<LoadableContent<List<Song>>>(LoadableContent.Empty())
    var albumContent: LoadableContent<List<Song>>
        get() = albumContentState.value
        set(v) { albumContentState.value = v }

    val recommendationSongsState = mutableStateOf(listOf<Song>())
    var recommendationSongs: List<Song>
        get() = recommendationSongsState.value
        private set(v) { recommendationSongsState.value = v }
    val recommendationLoadingState = mutableStateOf(false)
    val recommendationMessageState = mutableStateOf("")

    private var requestGeneration = 0L
    private var recommendationGeneration = 0L
    private var activeRecommendationRequest = ""
    private fun isCurrent(generation: Long): Boolean = generation == requestGeneration

    fun clearSearch() {
        requestGeneration++
        searchKw = ""
        searchResult = null
        searchResults = emptyList()
        searchPlResults = emptyList()
        artistResults = emptyList()
        albumResults = emptyList()
        searchContent = LoadableContent.Empty()
        plContent = LoadableContent.Empty()
        artistContent = LoadableContent.Empty()
        albumContent = LoadableContent.Empty()
    }

    /** Return from the standalone results route to its search form without discarding the query. */
    fun returnToSearch() {
        requestGeneration++
        searchResult = null
        searchResults = emptyList()
        searchPlResults = emptyList()
        searchContent = LoadableContent.Empty()
        plContent = LoadableContent.Empty()
    }

    private suspend fun searchSongs(q: String, pf: String): List<Song> =
        MusicSourceRegistry.platformSource(pf).search(q, 1, 30)

    private suspend fun searchTypedSource(q: String, source: String, type: String): Pair<List<Song>, Boolean> =
        withContext(Dispatchers.IO) {
            when {
                type == "song" -> searchSongs(q, source) to true
                source == "tx" -> MusicSourceRegistry.platformSource(source).searchTypedResult(q, type).let { it.songs to it.available }
                source in MusicSourceRegistry.platformIds -> MusicSourceRegistry.platformSource(source).searchTyped(q, type) to true
                else -> ApiClient.search(q, source, type) to true
            }
        }

    private suspend fun searchPlaylistSource(q: String, source: String): Pair<List<Playlist>, Boolean> =
        withContext(Dispatchers.IO) {
            if (source != "tx") return@withContext MusicSourceRegistry.platformSource(source).searchPlaylists(q, source) to true
            val direct = searchTxPlaylistResult(q, 100)
            if (direct.playlists.isNotEmpty()) return@withContext direct.playlists to true
            val fallback = preserveCancellationOrDefault(emptyList()) {
                MusicSourceRegistry.platformSource("tx").searchPlaylistsServer(q, 100)
            }
            if (fallback.isNotEmpty()) fallback to true else direct.playlists to direct.available
        }

    private suspend fun <T> searchAcrossSources(
        sources: List<String>,
        query: suspend (String) -> Pair<List<T>, Boolean>
    ): List<SearchBatch<T>> = coroutineScope {
        sources.map { source ->
            async {
                try {
                    val (rows, available) = query(source)
                    SearchBatch(rows, available, failed = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SearchBatch(emptyList(), available = false, failed = true)
                }
            }
        }.awaitAll()
    }

    private fun <T> batchContent(batch: List<SearchBatch<T>>, unavailable: String): LoadableContent<List<T>> {
        val rows = batch.flatMap { it.rows }
        return when {
            rows.isNotEmpty() -> LoadableContent.Available(rows)
            batch.isNotEmpty() && batch.all { it.failed || !it.available } -> LoadableContent.Unavailable(unavailable)
            else -> LoadableContent.Empty()
        }
    }

    /** Load a genuinely personalized group from the selected platform; force is used by the visible refresh control. */
    fun refreshPersonalRecommendations(
        platform: String,
        history: List<Song>,
        likedSongs: List<Song>,
        force: Boolean = false
    ) {
        // ACCEPT5-PERF：统计采集（全量 song_map 解析 ×2 + 365 天日统计投影）与画像构建
        // （decayedFacts 再解析）原先在调用线程（主线程）同步执行，切到搜索页时冻结约 2 秒。
        // 现全部移入 IO：本函数立即返回，页面先出静态框，推荐结果异步填入；
        // 请求键去重 / 代际防迟到 / 文案口径与旧实现一致（去重判定改在采集完成后、落状态前）。
        val source = platform.lowercase()
        val generation = ++recommendationGeneration
        recommendationLoadingState.value = true
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching {
                    val stats = xyz.cncnlp.soundwave.ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
                    val listenedArtists = stats.topArtists(20).map { it.singer to it.sec }
                    val listenedSongs = stats.topSongs(60)
                    PersonalRecommendations.buildProfile(
                        history, likedSongs, listenedArtists, listenedSongs = listenedSongs,
                        dailyListenJson = xyz.cncnlp.soundwave.ListenStats.localDailyListenStats(context),
                        dislikedSongKeys = xyz.cncnlp.soundwave.DislikeStore.keys(context),
                        dislikedIdentities = xyz.cncnlp.soundwave.DislikeStore.identities(context)
                    )
                }.getOrNull()
            }
            if (generation != recommendationGeneration) return@launch
            if (profile == null) {
                // 采集失败（如资料身份已过期）：旧实现会把异常抛进 LaunchedEffect；
                // 现降级为与搜索失败相同的文案，不再让切页路径有崩溃面。
                recommendationSongs = emptyList()
                recommendationLoadingState.value = false
                recommendationMessageState.value = "暂时无法获取不同的推荐歌曲，请稍后重试"
                return@launch
            }
            val requestKey = "$source:${profile.signature}:${profile.activitySignature}"
            if (!force && requestKey == activeRecommendationRequest && recommendationSongs.isNotEmpty()) {
                recommendationLoadingState.value = false
                return@launch
            }
            activeRecommendationRequest = requestKey
            recommendationSongs = emptyList()
            recommendationMessageState.value = ""
            if (profile.queries.isEmpty()) {
                recommendationLoadingState.value = false
                recommendationMessageState.value = "播放或收藏几首歌后，这里会按真实艺人和专辑信息推荐"
                return@launch
            }
            try {
                val alreadyShown = withContext(Dispatchers.IO) {
                    PersonalRecommendations.loadSeen(context, "song", source, profile.signature)
                }
                val excluded = profile.excludedSongKeys + alreadyShown
                val candidates = PersonalRecommendations.searchSongs(source, profile, excluded)
                if (generation != recommendationGeneration) return@launch
                val group = candidates.take(4)
                recommendationSongs = group
                if (group.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        PersonalRecommendations.rememberSeen(
                            context,
                            "song",
                            source,
                            profile.signature,
                            group.map(PersonalRecommendations::songIdentity)
                        )
                    }
                    recommendationMessageState.value = "按最近播放、收藏与常听歌手，在${platformName(source)}的真实搜索结果中挑选"
                } else {
                    recommendationMessageState.value = "当前平台没有返回更多不同的相似歌曲；可切换平台或增加播放/收藏记录"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == recommendationGeneration) {
                    recommendationSongs = emptyList()
                    recommendationMessageState.value = "暂时无法获取不同的推荐歌曲，请稍后重试"
                }
            } finally {
                if (generation == recommendationGeneration) recommendationLoadingState.value = false
            }
        }
    }

    fun runSearch(kw: String, source: String, mode: String) {
        val src = canonicalSourceId(source)
        val q = kw.trim()
        if (q.isEmpty()) return
        searchResult = AppRoute.SearchResult(q, mode, src)
        searchKw = q
        val generation = ++requestGeneration
        // LoadableContent：请求开始即 Loading（保留旧值由 UI 决定是否显示，这里只表达进行中）。
        when (mode) {
            "pl" -> plContent = LoadableContent.Loading
            "all" -> {
                searchContent = LoadableContent.Loading
                plContent = LoadableContent.Loading
                artistContent = LoadableContent.Loading
                albumContent = LoadableContent.Loading
            }
            else -> searchContent = LoadableContent.Loading
        }
        scope.launch {
            val h = (listOf(q) + searchHistory.filter { it != q }).take(10)
            searchHistory = h
            prefs.edit().putString("search_history_v1", h.joinToString("||")).apply()
            val ck = HomeFeedCache.searchKey(q, mode, src, crossPlatformSearch)
            // ACCEPT5-PERF：搜索缓存为非 suspend 的偏好读 + JSON 解析，原在 Main 限定的 launch 内执行，移入 IO。
            val cached = withContext(Dispatchers.IO) { try { HomeFeedCache.loadSearch(context, ck, HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS) } catch (_: Exception) { null } }
            if (cached != null && isCurrent(generation)) {
                searchResults = cached.first
                searchPlResults = cached.second
                if (mode == "pl") plContent = LoadableContent.Available(cached.second)
                else searchContent = LoadableContent.Available(cached.first)
            }
            if (mode == "all") {
                if (!isCurrent(generation)) return@launch
                if (cached == null) {
                    searchResults = emptyList()
                    searchPlResults = emptyList()
                }
                artistResults = emptyList()
                albumResults = emptyList()
                val sources = if (crossPlatformSearch) listOf("wy", "kg", "kw", "tx", "mg") else listOf(src)
                val all = coroutineScope {
                    val songs = async { searchAcrossSources(sources) { searchTypedSource(q, it, "song") } }
                    val playlists = async { searchAcrossSources(sources) { searchPlaylistSource(q, it) } }
                    val albums = async { searchAcrossSources(sources) { searchTypedSource(q, it, "album") } }
                    val artists = async { searchAcrossSources(sources) { searchTypedSource(q, it, "artist") } }
                    SearchAllBatch(songs.await(), playlists.await(), albums.await(), artists.await())
                }
                if (!isCurrent(generation)) return@launch
                val songs = all.songs.flatMap { it.rows }.distinctBy { "${it.key()}|${it.source}" }
                val playlists = all.playlists.flatMap { it.rows }.distinctBy { "${it.source}|${it.id}" }
                val albums = all.albums.flatMap { it.rows }.distinctBy { "${it.source}|${it.albumId.ifBlank { it.album }}" }
                val artists = all.artists.flatMap { it.rows }.distinctBy { "${it.source}|${it.name}" }
                searchResults = songs
                searchPlResults = playlists
                albumResults = albums
                artistResults = artists
                searchContent = batchContent(all.songs, "歌曲搜索暂不可用；可以重试")
                plContent = batchContent(all.playlists, "歌单搜索暂不可用；可以重试")
                albumContent = batchContent(all.albums, "专辑搜索暂不可用；可以重试")
                artistContent = batchContent(all.artists, "歌手搜索暂不可用；可以重试")
                withContext(Dispatchers.IO) { try { HomeFeedCache.saveSearch(context, ck, songs, playlists) } catch (_: Exception) {} }
                return@launch
            }
            if (mode == "pl") {
                if (!isCurrent(generation)) return@launch
                searchResults = emptyList()
                try {
                    // 默认展示当前平台；开启全平台搜索时并发合并五个平台真实歌单，绝不跨平台伪造。
                    // Local-first 批次补全：歌单搜索改走 Source Layer（与歌曲搜索同路径，行为等价直连）。
                    val (first, qqPlaylistAvailable) = withContext(Dispatchers.IO) {
                        if (src != "tx") {
                            MusicSourceRegistry.platformSource(src).searchPlaylists(q, src) to true
                        } else {
                            val direct = searchTxPlaylistResult(q, 100)
                            if (direct.playlists.isNotEmpty()) direct.playlists to true
                            else {
                                val fallback = preserveCancellationOrDefault(emptyList()) {
                                    MusicSourceRegistry.platformSource("tx").searchPlaylistsServer(q, 100)
                                }
                                if (fallback.isNotEmpty()) fallback to true else direct.playlists to direct.available
                            }
                        }
                    }
                    var playlistResults = first
                    if (crossPlatformSearch && isCurrent(generation)) {
                        val others = coroutineScope {
                            listOf("wy", "kg", "kw", "tx", "mg").filterNot { it == src }.map { p ->
                                async { withContext(Dispatchers.IO) { MusicSourceRegistry.platformSource(p).searchPlaylists(q, p) } }
                            }.awaitAll().flatten()
                        }
                        val seen = linkedSetOf<String>()
                        playlistResults = (first + others).filter { seen.add("${it.source}|${it.id}") }
                    }
                    if (isCurrent(generation)) plContent = when {
                        playlistResults.isNotEmpty() -> LoadableContent.Available(playlistResults)
                        src == "tx" && !qqPlaylistAvailable -> LoadableContent.Unavailable("QQ 音乐歌单搜索服务暂不可用；可以重试")
                        else -> LoadableContent.Empty()
                    }
                    if (playlistResults.isNotEmpty() && isCurrent(generation)) {
                        searchPlResults = playlistResults
                        withContext(Dispatchers.IO) { try { HomeFeedCache.saveSearch(context, ck, emptyList(), playlistResults) } catch (_: Exception) {} }
                    }
                    // 当前平台结果严格隔离，禁止跨平台伪造。
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (isCurrent(generation)) {
                        plContent = if (searchPlResults.isNotEmpty()) LoadableContent.Stale(searchPlResults, 0L, "搜索失败")
                        else LoadableContent.Unavailable("搜索失败")
                    }
                }
            } else {
                if (!isCurrent(generation)) return@launch
                searchResults = emptyList()
                searchPlResults = emptyList()
                artistResults = emptyList()
                albumResults = emptyList()
                artistContent = LoadableContent.Empty()
                albumContent = LoadableContent.Empty()
                val typ = when (mode) {
                    "artist", "singer" -> "artist"
                    "album" -> "album"
                    else -> "song"
                }
                try {
                    val (first, qqSourceAvailable) = withContext(Dispatchers.IO) {
                        when {
                            src == "tx" -> MusicSourceRegistry.platformSource(src).searchTypedResult(q, typ).let { it.songs to it.available }
                            typ == "song" -> searchSongs(q, src) to true
                            src in MusicSourceRegistry.platformIds -> MusicSourceRegistry.platformSource(src).searchTyped(q, typ) to true
                            else -> ApiClient.search(q, src, typ) to true
                        }
                    }
                    if (isCurrent(generation)) searchContent = when {
                        first.isNotEmpty() -> LoadableContent.Available(first)
                        src == "tx" && !qqSourceAvailable -> LoadableContent.Unavailable("QQ 音乐搜索服务暂不可用；请稍后重试")
                        else -> LoadableContent.Empty()
                    }
                    if (first.isNotEmpty() && isCurrent(generation)) {
                        searchResults = first
                        withContext(Dispatchers.IO) { try { HomeFeedCache.saveSearch(context, ck, first, emptyList()) } catch (_: Exception) {} }
                    }
                    if (crossPlatformSearch && isCurrent(generation)) {
                        val acc = linkedMapOf<String, Song>()
                        first.forEach { song ->
                            val fixed = if (song.source.isBlank()) song.copy(source = src) else song
                            acc.putIfAbsent(fixed.key() + "|" + src, fixed)
                        }
                        val results = coroutineScope {
                            listOf("wy", "kg", "kw", "tx", "mg").filterNot { it == src }.map { p ->
                                async {
                                    p to withContext(Dispatchers.IO) {
                                        when {
                                            p == "tx" -> MusicSourceRegistry.platformSource(p).searchTypedResult(q, typ).songs
                                            typ == "song" -> searchSongs(q, p)
                                            p in MusicSourceRegistry.platformIds -> MusicSourceRegistry.platformSource(p).searchTyped(q, typ)
                                            else -> ApiClient.search(q, p, typ)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                        results.forEach { (p, more) ->
                            more.forEach { song ->
                                val fixed = if (song.source.isBlank()) song.copy(source = p) else song
                                acc.putIfAbsent(fixed.key() + "|" + p, fixed)
                            }
                        }
                        if (isCurrent(generation)) {
                            searchResults = acc.values.toList()
                            searchContent = when {
                                searchResults.isNotEmpty() -> LoadableContent.Available(searchResults)
                                src == "tx" && !qqSourceAvailable -> LoadableContent.Unavailable("QQ 音乐搜索服务暂不可用；请稍后重试")
                                else -> LoadableContent.Empty()
                            }
                        }
                        if (isCurrent(generation)) {
                            withContext(Dispatchers.IO) { try { HomeFeedCache.saveSearch(context, ck, searchResults, emptyList()) } catch (_: Exception) {} }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (isCurrent(generation)) {
                        searchContent = if (searchResults.isNotEmpty()) LoadableContent.Stale(searchResults, 0L, "搜索失败")
                        else LoadableContent.Unavailable("搜索失败")
                    }
                }
            }
        }
    }
}
