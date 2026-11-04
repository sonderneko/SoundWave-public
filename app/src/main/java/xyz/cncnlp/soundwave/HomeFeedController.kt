package xyz.cncnlp.soundwave

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

private const val HOME_FEED_REFRESH_PREFS = "home_feed_refresh_v1"
private const val HOME_FEATURED_PLAYLIST_REFRESH_MS = 20L * 60L * 1000L
private const val HOT_WORDS_FETCH_LIMIT = 20

internal sealed interface HomeFeedRequestResult<out T> {
    data class Success<T>(val value: T) : HomeFeedRequestResult<T>
    object Failure : HomeFeedRequestResult<Nothing>
}

internal data class HomeFeedRequestResolution<T>(val value: T, val failed: Boolean)

internal fun <T> resolveHomeFeedRequestResult(
    result: HomeFeedRequestResult<T>,
    staleValue: T
): HomeFeedRequestResolution<T> = when (result) {
    is HomeFeedRequestResult.Success -> HomeFeedRequestResolution(result.value, failed = false)
    HomeFeedRequestResult.Failure -> HomeFeedRequestResolution(staleValue, failed = true)
}

internal fun <T> HomeFeedRequestResult<List<T>>.homeFeedListOrEmpty(): List<T> = when (this) {
    is HomeFeedRequestResult.Success -> value
    HomeFeedRequestResult.Failure -> emptyList()
}

internal fun <T> FeedListOutcome<T>.toHomeFeedRequestResult(): HomeFeedRequestResult<T> = when (this) {
    is FeedListOutcome.Success -> HomeFeedRequestResult.Success(data)
    FeedListOutcome.Failure -> HomeFeedRequestResult.Failure
}

internal fun <T> updateHomeFeedListCache(
    current: Map<String, List<T>>,
    platform: String,
    result: HomeFeedRequestResult<List<T>>
): Map<String, List<T>> = when (result) {
    is HomeFeedRequestResult.Success -> current + (platform to result.value)
    HomeFeedRequestResult.Failure -> current
}

/** Some legacy feed APIs collapse transport/parse failures to an empty list. */
internal fun <T> isHomeFeedListRequestFailed(
    result: HomeFeedRequestResult<List<T>>,
    emptyIsFailure: Boolean
): Boolean = when (result) {
    HomeFeedRequestResult.Failure -> true
    is HomeFeedRequestResult.Success -> emptyIsFailure && result.value.isEmpty()
}

internal fun successfulTimestampAfterRequest(previous: Long, succeeded: Boolean, fetchedNow: Long): Long =
    if (succeeded) fetchedNow else previous

private data class HomeFeedResponse(
    val hot: List<Song>,
    val ranks: List<RankBoard>,
    val playlists: List<Playlist>,
    val discover: List<DiscoverCard>,
    val keywords: List<String>
)

private fun playlistFeedKey(playlist: Playlist, fallbackPlatform: String): String =
    "${playlist.source.ifBlank { fallbackPlatform }.lowercase()}:${playlist.remoteId.ifBlank { playlist.id }}"

/**
 * 首页 feed 加载单点：从 AppRoot 抽出。
 *
 * 持有 hot/rank/推荐歌单/数字专辑/来源状态等展示态，并统一刷新/重试逻辑。
 * homePlatform（当前平台）为跨搜索共享的 UI 状态，通过 getter 注入，避免控制器反向触碰 AppRoot。
 */
class HomeFeedController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getHomePlatform: () -> String,
    initialSnapshot: HomeFeedCache.Snapshot,
) {
    private var snap: HomeFeedCache.Snapshot = initialSnapshot
    private var initialCacheSeeded = initialSnapshot.savedAt > 0L || initialSnapshot.hot.isNotEmpty() ||
        initialSnapshot.rank.isNotEmpty() || initialSnapshot.pl.isNotEmpty() || initialSnapshot.keywords.isNotEmpty() ||
        initialSnapshot.discover.isNotEmpty() || initialSnapshot.sourceStates.isNotEmpty()
    /** 同一时刻只允许一个首页刷新事务，避免 PTR/重试互相覆盖缓存。 */
    private var refreshJob: Job? = null
    private var playlistTasteProfileProvider: () -> PersonalRecommendations.Profile = {
        PersonalRecommendations.Profile(emptyList(), emptySet(), "", "")
    }

    internal fun setPlaylistTasteProfileProvider(provider: () -> PersonalRecommendations.Profile) {
        playlistTasteProfileProvider = provider
    }

    private val mutableMultiHotState = mutableStateOf(snap.hot)
    val multiHotState: State<Map<String, List<Song>>> = mutableMultiHotState
    private var multiHot: Map<String, List<Song>>
        get() = mutableMultiHotState.value
        set(v) { mutableMultiHotState.value = v }
    private val mutableDiscoverCardsState = mutableStateOf(listOf<DiscoverCard>())
    val discoverCardsState: State<List<DiscoverCard>> = mutableDiscoverCardsState
    private var discoverCards: List<DiscoverCard>
        get() = mutableDiscoverCardsState.value
        set(v) { mutableDiscoverCardsState.value = v }
    private val mutableDiscoverMapState = mutableStateOf(mapOf<String, List<DiscoverCard>>())
    val discoverMapState: State<Map<String, List<DiscoverCard>>> = mutableDiscoverMapState
    private var discoverMap: Map<String, List<DiscoverCard>>
        get() = mutableDiscoverMapState.value
        set(v) { mutableDiscoverMapState.value = v }
    private val mutableDiscoverLoadingState = mutableStateOf(false)
    val discoverLoadingState: State<Boolean> = mutableDiscoverLoadingState
    private var discoverLoading: Boolean
        get() = mutableDiscoverLoadingState.value
        set(v) { mutableDiscoverLoadingState.value = v }
    private val mutableMultiRankState = mutableStateOf(snap.rank)
    val multiRankState: State<Map<String, List<RankBoard>>> = mutableMultiRankState
    private var multiRank: Map<String, List<RankBoard>>
        get() = mutableMultiRankState.value
        set(v) { mutableMultiRankState.value = v }
    private val mutableMultiPlState = mutableStateOf(snap.pl)
    val multiPlState: State<Map<String, List<Playlist>>> = mutableMultiPlState
    private var multiPl: Map<String, List<Playlist>>
        get() = mutableMultiPlState.value
        set(v) { mutableMultiPlState.value = v }
    private val mutableFeedSourceStatesState = mutableStateOf(snap.sourceStates)
    val feedSourceStatesState: State<Map<String, FeedSourceState>> = mutableFeedSourceStatesState
    private var feedSourceStates: Map<String, FeedSourceState>
        get() = mutableFeedSourceStatesState.value
        set(v) { mutableFeedSourceStatesState.value = v }
    private val mutableFeedLoadingState = mutableStateOf(false)
    val feedLoadingState: State<Boolean> = mutableFeedLoadingState
    private var feedLoading: Boolean
        get() = mutableFeedLoadingState.value
        set(v) { mutableFeedLoadingState.value = v }
    private val mutableFeedErrorState = mutableStateOf("")
    val feedErrorState: State<String> = mutableFeedErrorState
    private var feedError: String
        get() = feedErrorState.value
        set(v) { mutableFeedErrorState.value = v }
    private val mutableFeedStaleState = mutableStateOf(snap.isPlatformFeedStale(getHomePlatform()))
    val feedStaleState: State<Boolean> = mutableFeedStaleState
    private var feedStale: Boolean
        get() = mutableFeedStaleState.value
        set(v) { mutableFeedStaleState.value = v }

    // ---- LoadableContent 统一状态（任务 4）：按平台保存热门/榜单/歌单的真实状态 ----
    private val mutableHotContentState = mutableStateOf<Map<String, LoadableContent<List<Song>>>>(emptyMap())
    val hotContentState: State<Map<String, LoadableContent<List<Song>>>> = mutableHotContentState
    private val mutableRankContentState = mutableStateOf<Map<String, LoadableContent<List<RankBoard>>>>(emptyMap())
    val rankContentState: State<Map<String, LoadableContent<List<RankBoard>>>> = mutableRankContentState
    private val mutablePlContentState = mutableStateOf<Map<String, LoadableContent<List<Playlist>>>>(emptyMap())
    val plContentState: State<Map<String, LoadableContent<List<Playlist>>>> = mutablePlContentState

    fun hotContent(platform: String): LoadableContent<List<Song>> =
        hotContentState.value[platform] ?: LoadableContent.Empty()
    fun rankContent(platform: String): LoadableContent<List<RankBoard>> =
        rankContentState.value[platform] ?: LoadableContent.Empty()
    fun plContent(platform: String): LoadableContent<List<Playlist>> =
        plContentState.value[platform] ?: LoadableContent.Empty()

    /** 缓存快照只证明“有过数据”，不证明新鲜；首次渲染统一标记为陈旧可用。 */
    private fun seedContentFromCache() {
        val now = System.currentTimeMillis()
        snap.sourceStates.forEach { (p, st) ->
            val hot = snap.hot[p].orEmpty()
            val rank = snap.rank[p].orEmpty()
            val pl = snap.pl[p].orEmpty()
            // 批33：冷启动 seed discover——缓存含新碟首发(new_album)/数字专辑/新歌卡，
            // 避免冷启动 discoverMap 为空导致新碟首发区块不渲染。
            val disc = snap.discover[p].orEmpty()
            if (p in snap.discover) {
                discoverMap = discoverMap + (p to disc)
                if (p == getHomePlatform() && disc.isNotEmpty()) discoverCards = disc
            }
            val hotFailed = st.lastError.contains("热门歌曲")
            val rankFailed = st.lastError.contains("榜单")
            val plFailed = st.lastError.contains("歌单")
            val hotFresh = !hotFailed && snap.isResourceFresh(HomeFeedCache.Resource.HOT, p, now)
            val rankFresh = !rankFailed && snap.isResourceFresh(HomeFeedCache.Resource.RANK, p, now)
            val plFresh = !plFailed && snap.isResourceFresh(HomeFeedCache.Resource.PLAYLIST, p, now)
            val hotFetchedAt = st.hotSongsFetchedAt
            val rankFetchedAt = st.rankBoardsFetchedAt
            val playlistFetchedAt = st.featuredPlaylistFetchedAt
            if (hot.isNotEmpty()) mutableHotContentState.value = mutableHotContentState.value + (p to (if (hotFresh) LoadableContent.Available(hot, hotFetchedAt) else LoadableContent.Stale(hot, hotFetchedAt, if (hotFailed) "热门歌曲本次未能更新，继续显示缓存" else "缓存已过期")))
            if (rank.isNotEmpty()) mutableRankContentState.value = mutableRankContentState.value + (p to (if (rankFresh) LoadableContent.Available(rank, rankFetchedAt) else LoadableContent.Stale(rank, rankFetchedAt, if (rankFailed) "榜单本次未能更新，继续显示缓存" else "缓存已过期")))
            if (pl.isNotEmpty()) mutablePlContentState.value = mutablePlContentState.value + (p to (if (plFresh) LoadableContent.Available(pl, playlistFetchedAt) else LoadableContent.Stale(pl, playlistFetchedAt, if (plFailed) "歌单本次未能更新，继续显示缓存" else "缓存已过期")))
        }
        // 无任何缓存时，当前平台先给 Loading，避免“空态闪烁后跳加载”。
        val p = getHomePlatform()
        if (snap.hot[p].orEmpty().isEmpty() && snap.rank[p].orEmpty().isEmpty() && snap.pl[p].orEmpty().isEmpty()) {
            mutableHotContentState.value = mutableHotContentState.value + (p to LoadableContent.Loading)
            mutableRankContentState.value = mutableRankContentState.value + (p to LoadableContent.Loading)
            mutablePlContentState.value = mutablePlContentState.value + (p to LoadableContent.Loading)
        }
    }

    private val mutableHotSongsState = mutableStateOf(snap.hot["wy"] ?: emptyList())
    val hotSongsState: State<List<Song>> = mutableHotSongsState
    private var hotSongs: List<Song>
        get() = mutableHotSongsState.value
        set(v) { mutableHotSongsState.value = v }
    private val mutableRanksState = mutableStateOf(snap.rank["wy"] ?: emptyList())
    val ranksState: State<List<RankBoard>> = mutableRanksState
    private var ranks: List<RankBoard>
        get() = mutableRanksState.value
        set(v) { mutableRanksState.value = v }
    private val mutableHotKeywordsState = mutableStateOf(snap.keywords)
    val hotKeywordsState: State<Map<String, List<String>>> = mutableHotKeywordsState
    private var hotKeywords: Map<String, List<String>>
        get() = mutableHotKeywordsState.value
        set(v) { mutableHotKeywordsState.value = v }
    private val mutablePlRecoState = mutableStateOf(snap.pl["wy"] ?: emptyList())
    val plRecoState: State<List<Playlist>> = mutablePlRecoState
    private var plReco: List<Playlist>
        get() = mutablePlRecoState.value
        set(v) { mutablePlRecoState.value = v }

    // 所有属性声明完成后才 seed：Kotlin 按声明顺序初始化，init 若插在属性中间，
    // 执行时后面的属性尚未赋值（hotSongsState 等），存在运行期未初始化访问风险。
    init {
        try {
            seedContentFromCache()
        } catch (_: Exception) {
            // 缓存/快照解析失败不能拖垮首页启动：降级为默认空态，网络刷新会覆盖。
        }
    }

    fun refreshDiscover() {
        if (discoverLoading) return
        discoverLoading = true
        scope.launch {
            try {
                val p0 = getHomePlatform()
                val outcome = withContext(Dispatchers.IO) {
                    if (p0 in MusicSourceRegistry.platformIds) {
                        MusicSourceRegistry.platformSource(p0).discoverCardsOutcome(20)
                    } else {
                        ApiClient.discoverOutcome(p0, 20)
                    }
                }
                when (outcome) {
                    is FeedListOutcome.Success -> {
                        discoverMap = updateHomeFeedListCache(discoverMap, p0, outcome.toHomeFeedRequestResult())
                        discoverCards = outcome.data
                    }
                    FeedListOutcome.Failure -> Unit // Keep the last visible snapshot and retry on the next refresh.
                }
            } catch (_: Exception) {
                // Failure must not replace a previously visible discover snapshot.
            } finally { discoverLoading = false }
        }
    }

    /**
     * 首页下拉刷新必须绕过“切页缓存新鲜”的短路；否则手势看似触发、数据却永远不请求。
     * 非强制刷新仍优先秒显当前平台缓存，且只在当前平台三类数据齐全时才可返回。
     */
    fun refreshOnHomeReturn() {
        val platform = getHomePlatform()
        val prefs = context.getSharedPreferences(HOME_FEED_REFRESH_PREFS, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong("featured_playlists_$platform", 0L)
        val force = System.currentTimeMillis() - lastAttempt >= HOME_FEATURED_PLAYLIST_REFRESH_MS
        refreshHomeFeed(forceNetwork = force)
    }

    fun refreshHomeFeed(forceNetwork: Boolean = false) {
        // 切平台时旧平台事务不能阻塞新平台；取消旧任务后由当前 platform 重建事务。
        refreshJob?.cancel()
        val p = getHomePlatform()
        // ACCEPT5-PERF：画像提供者（全量统计解析 + buildProfile）原先在调用线程（UI 线程）同步执行，
        // 下拉刷新/重试/切平台都会被它冻结；现移入下方歌单请求的 IO async 内与其它请求并行计算。
        if (forceNetwork) {
            context.getSharedPreferences(HOME_FEED_REFRESH_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("featured_playlists_$p", System.currentTimeMillis())
                .apply()
        }
        feedLoading = true
        feedError = ""
        // 批35：有旧数据时保留 Available/Stale 即时显示（后台刷新静默覆盖），仅无内容才 Loading——
        // 否则「缓存秒开」会被转圈顶掉，PTR/切页回到首页会闪烁。进行中反馈由 PTR 动画/顶部指示承担。
        val hasFeedContent = multiHot[p].orEmpty().isNotEmpty() || multiRank[p].orEmpty().isNotEmpty() || multiPl[p].orEmpty().isNotEmpty()
        if (!hasFeedContent) {
            mutableHotContentState.value = mutableHotContentState.value + (p to LoadableContent.Loading)
            mutableRankContentState.value = mutableRankContentState.value + (p to LoadableContent.Loading)
            mutablePlContentState.value = mutablePlContentState.value + (p to LoadableContent.Loading)
        }
        refreshJob = scope.launch {
            try {
            // 先读磁盘缓存立即显示（切页回来不闪），网络结果静默覆盖；异常不得越过刷新事务。
            val cachedSnapshot = try {
                HomeFeedCache.load(context)
            } catch (_: Throwable) {
                HomeFeedCache.Snapshot()
            }
            snap = cachedSnapshot
            if (cachedSnapshot.hot.isNotEmpty()) { multiHot = multiHot + cachedSnapshot.hot.filterValues { it.isNotEmpty() }; hotSongs = multiHot[p].orEmpty().ifEmpty { hotSongs } }
            if (cachedSnapshot.rank.isNotEmpty()) { multiRank = multiRank + cachedSnapshot.rank.filterValues { it.isNotEmpty() }; ranks = multiRank[p].orEmpty().ifEmpty { ranks } }
            if (cachedSnapshot.pl.isNotEmpty()) { multiPl = multiPl + cachedSnapshot.pl.filterValues { it.isNotEmpty() }; plReco = multiPl[p].orEmpty().ifEmpty { plReco } }
            if (cachedSnapshot.keywords.isNotEmpty()) hotKeywords = cachedSnapshot.keywords
            if (cachedSnapshot.sourceStates.isNotEmpty()) feedSourceStates = cachedSnapshot.sourceStates
            if (!initialCacheSeeded) {
                // 首次磁盘读完成后立刻恢复缓存内容；空缓存仍保持 Loading，不闪出空态。
                seedContentFromCache()
                initialCacheSeeded = true
            }
            feedStale = false
            // 仅非强制刷新且当前平台各资源时间戳均在 TTL 内时才短路；保存 B 不会续期 A。
            if (shouldShortCircuitHomeFeed(snap, p, forceNetwork)) {
                feedLoading = false
                return@launch
            }
            // 五类请求并行启动；每个请求完成后立即提交，不能被最慢歌单阻塞。
            coroutineScope {
                val refreshPrefs = context.getSharedPreferences(HOME_FEED_REFRESH_PREFS, Context.MODE_PRIVATE)
                val featuredCycle = refreshPrefs.getInt("featured_playlist_cycle_$p", 0)
                refreshPrefs.edit().putInt("featured_playlist_cycle_$p", featuredCycle + 1).apply()
                val hotReq = async(Dispatchers.IO) {
                    runCatching {
                        if (p in MusicSourceRegistry.platformIds) {
                            MusicSourceRegistry.platformSource(p).hotSongs()
                        } else {
                            ApiClient.hot(p)
                        }
                    }.fold(
                        onSuccess = { HomeFeedRequestResult.Success(it) },
                        onFailure = { HomeFeedRequestResult.Failure }
                    )
                }
                val rankReq = async(Dispatchers.IO) {
                    runCatching {
                        if (p in MusicSourceRegistry.platformIds) {
                            MusicSourceRegistry.platformSource(p).rankBoards()
                        } else {
                            ApiClient.rank(p)
                        }
                    }.fold(
                        onSuccess = { HomeFeedRequestResult.Success(it) },
                        onFailure = { HomeFeedRequestResult.Failure }
                    )
                }
                val playlistReq = async(Dispatchers.IO) {
                    runCatching {
                        val featuredTasteProfile = runCatching { playlistTasteProfileProvider() }
                            .getOrElse { PersonalRecommendations.Profile(emptyList(), emptySet(), "", "") }
                        PlaylistFeedRepository.fetchFeaturedResult(context, p, featuredCycle, 10, featuredTasteProfile, forceNetwork)
                    }.getOrElse { HomeFeedRequestResult.Failure }
                }
                val discoverReq = async(Dispatchers.IO) {
                    runCatching {
                        if (p in MusicSourceRegistry.platformIds) {
                            MusicSourceRegistry.platformSource(p).discoverCardsOutcome(20)
                        } else {
                            ApiClient.discoverOutcome(p, 20)
                        }
                    }.fold(
                        onSuccess = { it.toHomeFeedRequestResult() },
                        onFailure = { HomeFeedRequestResult.Failure }
                    )
                }
                val keywordReq = async(Dispatchers.IO) {
                    runCatching {
                        if (p in MusicSourceRegistry.platformIds) {
                            MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)
                        } else {
                            ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)
                        }
                    }.fold(
                        onSuccess = { it.toHomeFeedRequestResult() },
                        onFailure = { HomeFeedRequestResult.Failure }
                    )
                }

                var h = emptyList<Song>()
                var r = emptyList<RankBoard>()
                var d = emptyList<DiscoverCard>()
                var pl = emptyList<Playlist>()
                var featuredPlaylistUpdated = false
                var hotRequestFailed = false
                var rankRequestFailed = false
                var discoverRequestFailed = false
                var keywordRequestFailed = false
                val previousPlaylistKeys = multiPl[p].orEmpty().mapTo(mutableSetOf()) { playlistFeedKey(it, p) }
                var kws = emptyList<String>()
                val jobs = listOf(
                    launch {
                        val request = hotReq.await()
                        val previous = multiHot[p].orEmpty()
                        val resolved = resolveHomeFeedRequestResult(request, previous)
                        val received = request.homeFeedListOrEmpty()
                        hotRequestFailed = resolved.failed || received.isEmpty()
                        h = if (resolved.failed) resolved.value else received.ifEmpty { previous }
                        if (received.isNotEmpty()) { hotSongs = received; multiHot = multiHot + (p to received) }
                    },
                    launch {
                        val request = rankReq.await()
                        val previous = multiRank[p].orEmpty()
                        val resolved = resolveHomeFeedRequestResult(request, previous)
                        val received = request.homeFeedListOrEmpty()
                        rankRequestFailed = resolved.failed || received.isEmpty()
                        r = if (resolved.failed) resolved.value else received.ifEmpty { previous }
                        if (received.isNotEmpty()) { ranks = received; multiRank = multiRank + (p to received) }
                    },
                    launch {
                        val request = discoverReq.await()
                        val previous = discoverMap[p].orEmpty()
                        val resolved = resolveHomeFeedRequestResult(request, previous)
                        discoverRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)
                        val received = request.homeFeedListOrEmpty()
                        d = if (resolved.failed) resolved.value else received.ifEmpty { previous }
                        discoverMap = updateHomeFeedListCache(discoverMap, p, request)
                        if (!resolved.failed) discoverCards = received
                    },
                    launch {
                        val request = playlistReq.await()
                        val resolved = resolveHomeFeedRequestResult(request, multiPl[p].orEmpty())
                        if (!resolved.failed) {
                            featuredPlaylistUpdated = true
                            val freshPlaylists = resolved.value.distinctBy { playlistFeedKey(it, p) }
                            if (freshPlaylists.isNotEmpty()) {
                                val newlyReturned = freshPlaylists.filter { playlistFeedKey(it, p) !in previousPlaylistKeys }
                                val previouslyShown = freshPlaylists.filter { playlistFeedKey(it, p) in previousPlaylistKeys }
                                // Keep provider order inside each group, but surface newly returned real IDs first.
                                pl = if (newlyReturned.isNotEmpty()) newlyReturned + previouslyShown else freshPlaylists
                            } else {
                                // A successful empty response is an authoritative negative cache, not a failed refresh.
                                pl = emptyList()
                            }
                            plReco = pl
                            multiPl = multiPl + (p to pl)
                        } else {
                            // A failed request keeps the last real platform batch visible and retryable.
                            pl = resolved.value
                            plReco = pl
                        }
                    },
                    launch {
                        val request = keywordReq.await()
                        val previous = hotKeywords[p].orEmpty()
                        val resolved = resolveHomeFeedRequestResult(request, previous)
                        keywordRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)
                        val received = request.homeFeedListOrEmpty()
                        kws = if (resolved.failed) resolved.value else received.ifEmpty { previous }
                        hotKeywords = updateHomeFeedListCache(hotKeywords, p, request)
                    }
                )
                jobs.forEach { it.join() }

                val fetchedNow = System.currentTimeMillis()
                val previousHotGoodAt = feedSourceStates[p]?.hotSongsFetchedAt ?: 0L
                val previousRankGoodAt = feedSourceStates[p]?.rankBoardsFetchedAt ?: 0L
                val previousPlaylistGoodAt = feedSourceStates[p]?.featuredPlaylistFetchedAt ?: 0L
                mutableHotContentState.value = mutableHotContentState.value + (p to when {
                    hotRequestFailed && h.isNotEmpty() -> LoadableContent.Stale(h, previousHotGoodAt, "热门歌曲本次未能获取，仍显示上次数据；点击重试")
                    hotRequestFailed -> LoadableContent.Unavailable("未获取到可用的热门歌曲，请检查网络后重试")
                    h.isNotEmpty() -> LoadableContent.Available(h, fetchedNow)
                    else -> LoadableContent.Empty(fetchedNow)
                })
                mutableRankContentState.value = mutableRankContentState.value + (p to when {
                    rankRequestFailed && r.isNotEmpty() -> LoadableContent.Stale(r, previousRankGoodAt, "榜单本次未能获取，仍显示上次数据；点击重试")
                    rankRequestFailed -> LoadableContent.Unavailable("未获取到可用榜单，请检查网络后重试")
                    r.isNotEmpty() -> LoadableContent.Available(r, fetchedNow)
                    else -> LoadableContent.Empty(fetchedNow)
                })
                mutablePlContentState.value = mutablePlContentState.value + (p to when {
                    !featuredPlaylistUpdated && pl.isNotEmpty() -> LoadableContent.Stale(pl, previousPlaylistGoodAt, "歌单本次未能更新，仍显示上次数据；点击重试")
                    !featuredPlaylistUpdated -> LoadableContent.Unavailable("当前分类暂未获取到歌单，请检查网络后重试")
                    else -> LoadableContent.Available(pl, fetchedNow)
                })
                val failedResources = buildList {
                    if (hotRequestFailed) add("热门歌曲")
                    if (rankRequestFailed) add("榜单")
                    if (!featuredPlaylistUpdated) add("歌单")
                    if (keywordRequestFailed) add("热词")
                    if (discoverRequestFailed) add("发现")
                }
                val sourceError = if (failedResources.isEmpty()) "" else "本次未能更新：${failedResources.joinToString("、")}"
                val lastFullySuccessfulAt = feedSourceStates[p]?.refreshedAt ?: 0L
                val refreshedAt = successfulTimestampAfterRequest(lastFullySuccessfulAt, failedResources.isEmpty(), fetchedNow)
                val previousState = feedSourceStates[p]
                val featuredPlaylistFetchedAt = successfulTimestampAfterRequest(previousState?.featuredPlaylistFetchedAt ?: 0L, featuredPlaylistUpdated, fetchedNow)
                val hotSongsFetchedAt = successfulTimestampAfterRequest(previousState?.hotSongsFetchedAt ?: 0L, !hotRequestFailed, fetchedNow)
                val rankBoardsFetchedAt = successfulTimestampAfterRequest(previousState?.rankBoardsFetchedAt ?: 0L, !rankRequestFailed, fetchedNow)
                val hotKeywordsFetchedAt = successfulTimestampAfterRequest(previousState?.hotKeywordsFetchedAt ?: 0L, !keywordRequestFailed, fetchedNow)
                feedSourceStates = feedSourceStates + (p to FeedSourceState(
                    source = p,
                    fetchedAt = fetchedNow,
                    refreshedAt = refreshedAt,
                    hotCount = h.size,
                    rankCount = r.size,
                    playlistCount = pl.size,
                    lastError = sourceError,
                    featuredPlaylistFetchedAt = featuredPlaylistFetchedAt,
                    hotSongsFetchedAt = hotSongsFetchedAt,
                    rankBoardsFetchedAt = rankBoardsFetchedAt,
                    hotKeywordsFetchedAt = hotKeywordsFetchedAt
                ))
                HomeFeedCache.save(context, multiHot, multiRank, multiPl, hotKeywords, feedSourceStates, discoverMap)
                feedStale = false
                feedLoading = false
            }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 业务异常不能使 PTR 触发宿主崩溃；协程取消仍按正常生命周期传播。
                feedError = "加载失败"
                feedLoading = false
            }
        }
    }
}

/** 首页刷新短路策略——非强制刷新 + 当前平台缓存及有效精选歌单齐全 + 三类资源均按资源级 TTL 新鲜，才允许跳过网络。 */
internal fun hasUsableHomeKeywordSnapshot(
    keywords: Map<String, List<String>>,
    platform: String,
    hotKeywordsFetchedAt: Long,
    now: Long = System.currentTimeMillis()
): Boolean {
    val cached = keywords[platform] ?: return false
    if (!HomeFeedCache.isTimestampFresh(hotKeywordsFetchedAt, HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS, now)) return false
    return cached.isEmpty() || cached.size >= HOT_WORDS_FETCH_LIMIT
}

/** Freshness and cache completeness are always evaluated against the requested platform's own snapshot. */
internal fun shouldShortCircuitHomeFeed(
    snapshot: HomeFeedCache.Snapshot,
    platform: String,
    forceNetwork: Boolean,
    now: Long = System.currentTimeMillis()
): Boolean {
    val hasCurrentCache = snapshot.hot[platform].orEmpty().isNotEmpty() &&
        snapshot.rank[platform].orEmpty().isNotEmpty() &&
        hasUsableHomeKeywordSnapshot(
            snapshot.keywords,
            platform,
            snapshot.sourceStates[platform]?.hotKeywordsFetchedAt ?: 0L,
            now
        )
    val hasCurrentFeaturedPlaylist = hasCurrentFeaturedPlaylistSnapshot(
        platform = platform,
        playlists = snapshot.pl[platform].orEmpty(),
        hasSnapshot = platform in snapshot.pl,
        featuredPlaylistFetchedAt = snapshot.sourceStates[platform]?.featuredPlaylistFetchedAt ?: 0L,
        now = now
    )
    return shouldShortCircuit(
        forceNetwork = forceNetwork,
        hasCurrentCache = hasCurrentCache,
        hasCurrentFeaturedPlaylist = hasCurrentFeaturedPlaylist,
        hotFresh = snapshot.isResourceFresh(HomeFeedCache.Resource.HOT, platform, now),
        rankFresh = snapshot.isResourceFresh(HomeFeedCache.Resource.RANK, platform, now),
        plFresh = snapshot.isResourceFresh(HomeFeedCache.Resource.PLAYLIST, platform, now),
        hasFailedResources = !snapshot.sourceStates[platform]?.lastError.isNullOrBlank()
    )
}

internal fun shouldShortCircuit(
    forceNetwork: Boolean,
    hasCurrentCache: Boolean,
    hasCurrentFeaturedPlaylist: Boolean,
    hotFresh: Boolean,
    rankFresh: Boolean,
    plFresh: Boolean,
    hasFailedResources: Boolean = false,
): Boolean = !forceNetwork && !hasFailedResources && hasCurrentCache && hasCurrentFeaturedPlaylist && hotFresh && rankFresh && plFresh

/** The platform-keyed cache must contain a real, non-local playlist for that same provider. */
internal fun hasUsableFeaturedPlaylist(platform: String, playlists: List<Playlist>): Boolean {
    val expected = platform.trim().lowercase(Locale.ROOT)
    if (expected.isBlank()) return false
    return playlists.any { playlist ->
        val source = playlist.source.trim().lowercase(Locale.ROOT).ifBlank { expected }
        !playlist.local && playlist.id.isNotBlank() && source == expected
    }
}

/** A successful empty featured response is cacheable for the existing playlist TTL. */
internal fun hasCurrentFeaturedPlaylistSnapshot(
    platform: String,
    playlists: List<Playlist>,
    hasSnapshot: Boolean,
    featuredPlaylistFetchedAt: Long,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!hasSnapshot || !HomeFeedCache.isTimestampFresh(featuredPlaylistFetchedAt, HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS, now)) return false
    return playlists.isEmpty() || hasUsableFeaturedPlaylist(platform, playlists)
}
