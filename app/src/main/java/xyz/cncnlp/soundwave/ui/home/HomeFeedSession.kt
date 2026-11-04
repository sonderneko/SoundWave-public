package xyz.cncnlp.soundwave.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import xyz.cncnlp.soundwave.AlbumFavoriteMarks
import xyz.cncnlp.soundwave.ContextData
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.DiscoverCard
import xyz.cncnlp.soundwave.FeedSourceState
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.HomeFeedController
import xyz.cncnlp.soundwave.HomeFeedRenderState
import xyz.cncnlp.soundwave.ListenStats
import xyz.cncnlp.soundwave.PersonalRecommendations
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.PlaylistImportChecker
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song

/** Snapshot-backed UI session for the home feed and its refresh lifecycle. */
internal data class HomeFeedSession(
    val controller: HomeFeedController,
    val multiHot: Map<String, List<Song>>,
    val discoverCards: List<DiscoverCard>,
    val discoverMap: Map<String, List<DiscoverCard>>,
    val discoverLoading: Boolean,
    val multiRank: Map<String, List<RankBoard>>,
    val multiPl: Map<String, List<Playlist>>,
    val feedSourceStates: Map<String, FeedSourceState>,
    val feedLoading: Boolean,
    val feedError: String,
    val feedStale: Boolean,
    val hotSongs: List<Song>,
    val ranks: List<RankBoard>,
    val hotKeywords: Map<String, List<String>>,
    val plReco: List<Playlist>,
    val isGestureRefreshing: Boolean,
    private val setGestureRefreshing: (Boolean) -> Unit,
) {
    fun updateTasteProfile(
        context: Context,
        history: List<Song>,
        likedSongs: List<Song>,
        profileOwner: String,
        favoritePlaylists: List<Playlist> = emptyList(),
        favoriteAlbums: List<Playlist> = emptyList(),
        importedPlaylists: List<Playlist> = emptyList(),
        librarySongs: List<Song> = emptyList(),
    ) {
        controller.setPlaylistTasteProfileProvider {
            PersonalRecommendations.buildProfile(
                history = history,
                likedSongs = likedSongs,
                listenedArtists = xyz.cncnlp.soundwave.ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).topArtists(20).map { it.singer to it.sec },
                listenedSongs = xyz.cncnlp.soundwave.ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).topSongs(60),
                profileOwner = profileOwner,
                favoritePlaylists = favoritePlaylists,
                favoriteAlbums = favoriteAlbums,
                importedPlaylists = importedPlaylists,
                librarySongs = librarySongs,
                dailyListenJson = xyz.cncnlp.soundwave.ListenStats.localDailyListenStats(context),
                dislikedSongKeys = xyz.cncnlp.soundwave.DislikeStore.keys(context),
                dislikedIdentities = xyz.cncnlp.soundwave.DislikeStore.identities(context),
            )
        }
    }

    fun refreshHomeFeed(forceNetwork: Boolean = false) = controller.refreshHomeFeed(forceNetwork)

    fun selectPlatform(platform: String, currentPlatform: String, onSelected: (String) -> Unit) {
        if (currentPlatform != platform) {
            onSelected(platform)
            controller.refreshHomeFeed(forceNetwork = true)
        }
    }

    fun onPullRefresh() {
        setGestureRefreshing(true)
        controller.refreshHomeFeed(forceNetwork = true)
    }

    fun renderState(platform: String): HomeFeedRenderState = HomeFeedRenderState(
        hot = hotSongs,
        ranks = ranks,
        plReco = plReco,
        multiHot = multiHot,
        multiRank = multiRank,
        multiPl = multiPl,
        feedLoading = feedLoading,
        isGestureRefreshing = isGestureRefreshing,
        feedStale = feedStale,
        hotContent = controller.hotContent(platform),
        rankContent = controller.rankContent(platform),
        plContent = controller.plContent(platform),
        discover = discoverMap[platform].orEmpty(),
    )
}

@Composable
internal fun rememberHomeFeedSession(
    context: Context,
    scope: CoroutineScope,
    getHomePlatform: () -> String,
): HomeFeedSession {
    val controller = remember {
        PlaylistImportChecker.init(context)
        HomeFeedController(context, scope, getHomePlatform, HomeFeedCache.Snapshot())
    }
    val multiHot by controller.multiHotState
    val discoverCards by controller.discoverCardsState
    val discoverMap by controller.discoverMapState
    val discoverLoading by controller.discoverLoadingState
    val multiRank by controller.multiRankState
    val multiPl by controller.multiPlState
    val feedSourceStates by controller.feedSourceStatesState
    val feedLoading by controller.feedLoadingState
    val feedError by controller.feedErrorState
    val feedStale by controller.feedStaleState
    val hotSongs by controller.hotSongsState
    val ranks by controller.ranksState
    val hotKeywords by controller.hotKeywordsState
    val plReco by controller.plRecoState
    var gestureRefreshing by remember(controller) { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refreshOnHomeReturn() }
    LaunchedEffect(feedLoading, gestureRefreshing) {
        if (gestureRefreshing && !feedLoading) gestureRefreshing = false
    }

    return HomeFeedSession(
        controller = controller,
        multiHot = multiHot,
        discoverCards = discoverCards,
        discoverMap = discoverMap,
        discoverLoading = discoverLoading,
        multiRank = multiRank,
        multiPl = multiPl,
        feedSourceStates = feedSourceStates,
        feedLoading = feedLoading,
        feedError = feedError,
        feedStale = feedStale,
        hotSongs = hotSongs,
        ranks = ranks,
        hotKeywords = hotKeywords,
        plReco = plReco,
        isGestureRefreshing = gestureRefreshing,
        setGestureRefreshing = { gestureRefreshing = it },
    )
}

// BEGIN JVM-TESTABLE TODAY FRONTEND
/** One foreground publication transaction; caller supplies the live Compose lease. */
internal object TodayRecommendationFrontend {
    fun acceptsUpdate(
        context: Context, platform: String, profile: PersonalRecommendations.Profile,
        update: xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.Update
    ): Boolean = platform.equals(update.platform, ignoreCase = true) &&
        update.fingerprint == xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation.fingerprint(context, platform, profile)

    suspend fun load(
        context: Context, platform: String, profile: PersonalRecommendations.Profile, slot: String,
        isCurrent: () -> Boolean
    ): List<Song>? {
        val weighted = xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation
        val scheduler = xyz.cncnlp.soundwave.TodayRecommendationScheduler
        val slots = xyz.cncnlp.soundwave.TodayRecommendationSlot
        val state = xyz.cncnlp.soundwave.HomePageStateStore
        val fingerprint = weighted.fingerprint(context, platform, profile)
        scheduler.schedule(context, platform, profile) // Empty profiles replace the previous owner's taste too.
        val request = scheduler.capture(context, platform)
        fun current() = isCurrent() && scheduler.isCurrent(context, request) &&
            weighted.fingerprint(context, platform, profile) == fingerprint &&
            slots.slotKey(System.currentTimeMillis()) == slot
        if (!current()) return null
        val cached = HomeFeedCache.loadDetailSnapshot(context, slots.cacheId(platform), platform).songs
        if (state.getString(context, slots.slotStateKey(platform)) == slot &&
            state.getString(context, slots.fingerprintStateKey(platform)) == fingerprint && cached.isNotEmpty()) return cached
        // saveDetail refuses empty songs: invalidate BEFORE generating; old raw bytes may never gain a new fingerprint.
        state.putStrings(context, mapOf(slots.slotStateKey(platform) to "", slots.fingerprintStateKey(platform) to ""))
        val songs = weighted.generate(context, platform, profile, limit = 18)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!current()) return null
        if (songs.isEmpty()) return emptyList()
        HomeFeedCache.saveDetail(context, slots.cacheId(platform), platform, songs)
        if (!current()) return null
        // The legacy cache swallows storage exceptions. Read back the exact target before certifying publication.
        val persisted = HomeFeedCache.loadDetailSnapshot(context, slots.cacheId(platform), platform).songs
        if (persisted.map(PersonalRecommendations::songIdentity) != songs.map(PersonalRecommendations::songIdentity)) return emptyList()
        state.putStrings(context, mapOf(slots.slotStateKey(platform) to slot, slots.fingerprintStateKey(platform) to fingerprint))
        weighted.rememberPublished(context, platform, profile, songs)
        return songs
    }
}
// END JVM-TESTABLE TODAY FRONTEND

internal data class RecommendationLibrarySession(
    val owner: String, val favoritePlaylists: List<Playlist>, val favoriteAlbums: List<Playlist>,
    val importedPlaylists: List<Playlist>, val songs: List<Song>, val profile: PersonalRecommendations.Profile
)

/** ACCEPT5-PERF：rememberRecommendationLibrary 的统计输入快照（IO 线程采集，主线程只读）。 */
private data class RecommendationStatsSnapshot(
    val artists: List<Pair<String, Long>>,
    val songs: List<xyz.cncnlp.soundwave.ListenStats.SongStat>,
    val dailyListenJson: String,
    // ACCEPT6-FREEZE：衰减事实在 IO 采集段一并算好；根作用域 remember 内的 buildProfile
    // 不再在主线程全量解析 365 天日统计 JSON（每首新歌入历史都会触发一次重建）。
    val decayedFacts: xyz.cncnlp.soundwave.ListenStatsDailyStore.DecayedFacts? = null,
    val dislikedSongKeys: Set<String>,
    val dislikedIdentities: Set<String>
)

@Composable
internal fun rememberRecommendationLibrary(
    context: Context, user: String, playlists: List<Playlist>, history: List<Song>, likedSongs: List<Song>
): RecommendationLibrarySession {
    val recommendationOwner = "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())}:$user"
    // r657 第三轮 b（卡顿）：本函数在 MainActivity 根作用域每次重组都会执行；偏好读取 / 画像构建（解析收听统计 JSON）
    // 原先每次重组都在主线程重做，改为按输入 remember。
    val recommendationAlbumMarks = remember(context, playlists) { AlbumFavoriteMarks.marks(context) }
    val recommendationEntries = remember(playlists) { playlists.filter { it.id !in setOf("liked", "history", "local") } }
    // Explicit album marks/import entity kind only; never guess genre or album type from playlist names.
    fun recommendationIsAlbum(pl: Playlist) = pl.id in recommendationAlbumMarks ||
        pl.id.startsWith("imp_${pl.source}_album_")
    var recommendationDetails by remember(recommendationOwner) { mutableStateOf<Map<String, List<Song>>>(emptyMap()) }
    val recommendationDetailKey = remember(recommendationEntries, recommendationAlbumMarks) {
        recommendationEntries.map { "${it.id}:${it.source}:${it.remoteId}:${it.songs.size}:${recommendationIsAlbum(it)}" }
    }
    LaunchedEffect(recommendationOwner, recommendationDetailKey) {
        val lease = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
        // Bounded hydration: at most 6 unloaded real collections, 4 seconds each, no recursive paging.
        val loaded = withContext(Dispatchers.IO) {
            val found = mutableMapOf<String, List<Song>>()
            recommendationEntries.filter { it.songs.isEmpty() && it.source in MusicSourceRegistry.platformIds }
                .take(6).forEach { pl ->
                    val songs = try {
                        kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                            val remote = pl.remoteId.ifBlank {
                                when {
                                    pl.id.startsWith("imp_${pl.source}_playlist_") -> pl.id.removePrefix("imp_${pl.source}_playlist_")
                                    pl.id.startsWith("imp_${pl.source}_album_") -> pl.id.removePrefix("imp_${pl.source}_album_")
                                    pl.id.startsWith("fav_${pl.source}_") -> pl.id.removePrefix("fav_${pl.source}_")
                                    else -> pl.id
                                }
                            }
                            val cached = HomeFeedCache.loadDetailSnapshot(context, remote, pl.source).songs
                            cached.ifEmpty {
                                if (recommendationIsAlbum(pl)) MusicSourceRegistry.platformSource(pl.source).albumDetail(remote)?.songs.orEmpty()
                                else MusicSourceRegistry.platformSource(pl.source).playlistDetail(remote)
                            }.filter { it.source == pl.source }.take(80)
                        }.orEmpty()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    if (songs.isNotEmpty()) found[pl.id] = songs
                }
            found
        }
        if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(lease)) recommendationDetails = loaded
    }
    // ACCEPT5-PERF：统计输入（topArtists/topSongs 全量 song_map 解析 + 365 天日统计投影）原先在下方
    // remember 内于根组合主线程同步采集，history/收藏一变就冻结全屏（r657 第三轮只做到 remember 缓解，
    // 计算本身仍在主线程）。现异步快照填入：快照未到时画像先按无统计输入构建，到达后以同一口径重建，
    // 与 recommendationDetails 到达后重建是同一机制；最终画像与旧实现一致。
    var recommendationStats by remember(recommendationOwner) {
        mutableStateOf<RecommendationStatsSnapshot?>(null)
    }
    LaunchedEffect(recommendationOwner, history, likedSongs) {
        recommendationStats = withContext(Dispatchers.IO) {
            runCatching {
                val stats = ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
                val dailyJson = xyz.cncnlp.soundwave.ListenStats.localDailyListenStats(context)
                RecommendationStatsSnapshot(
                    artists = stats.topArtists(20).map { it.singer to it.sec },
                    songs = stats.topSongs(60),
                    dailyListenJson = dailyJson,
                    // ACCEPT6-FREEZE：衰减解析与统计采集同一 IO 段完成（now 口径与采集时刻一致，
                    // 衰减为日粒度，秒级时差无语义影响）。
                    decayedFacts = if (dailyJson.isNotBlank()) {
                        xyz.cncnlp.soundwave.ListenStatsDailyStore.decayedFacts(dailyJson, System.currentTimeMillis())
                    } else null,
                    dislikedSongKeys = xyz.cncnlp.soundwave.DislikeStore.keys(context),
                    dislikedIdentities = xyz.cncnlp.soundwave.DislikeStore.identities(context)
                )
            }.getOrNull()
        }
    }
    return remember(recommendationOwner, recommendationEntries, recommendationDetails, recommendationAlbumMarks, history, likedSongs, recommendationStats) {
        val recommendationLibrary = recommendationEntries.map { pl ->
            if (pl.songs.isEmpty()) pl.copy(songs = recommendationDetails[pl.id].orEmpty().toMutableList()) else pl
        }
        val recommendationFavoritePlaylists = recommendationLibrary.filter { it.favorited && !recommendationIsAlbum(it) }
        val recommendationFavoriteAlbums = recommendationLibrary.filter { recommendationIsAlbum(it) }
        val recommendationImportedPlaylists = recommendationLibrary.filter { !it.favorited && !recommendationIsAlbum(it) }
        // ACCEPT6-sortfreeze：只需前 200 个 distinct identity；惰性序列取满即停，不再整表
        // materialize + 逐首算 identity。此 remember 块在根组合内，歌单排序落盘即触发重建，
        // 原急切版本在大曲库下是主线程数百 ms 级开销（与 buildProfile 内的同类扫描叠加即秒级卡顿）。
        val recommendationLibrarySongs = recommendationLibrary.asSequence()
            .flatMap { it.songs.asSequence() }
            .distinctBy { PersonalRecommendations.songIdentity(it) }.take(200).toList()
        val statsSnapshot = recommendationStats
        val profile = PersonalRecommendations.buildProfile(
            history, likedSongs,
            statsSnapshot?.artists.orEmpty(),
            listenedSongs = statsSnapshot?.songs.orEmpty(),
            profileOwner = recommendationOwner, favoritePlaylists = recommendationFavoritePlaylists,
            favoriteAlbums = recommendationFavoriteAlbums, importedPlaylists = recommendationImportedPlaylists,
            librarySongs = recommendationLibrarySongs,
            dailyListenJson = statsSnapshot?.dailyListenJson.orEmpty(),
            precomputedDecayedFacts = statsSnapshot?.decayedFacts,
            dislikedSongKeys = statsSnapshot?.dislikedSongKeys.orEmpty(),
            dislikedIdentities = statsSnapshot?.dislikedIdentities.orEmpty()
        )
        RecommendationLibrarySession(recommendationOwner, recommendationFavoritePlaylists,
            recommendationFavoriteAlbums, recommendationImportedPlaylists, recommendationLibrarySongs, profile)
    }
}

/** Keep open details blank while the new profile/filter is in flight, including failed/empty refreshes. */
@Composable
internal fun BindTodayRecommendationDetail(
    context: Context, detail: Playlist?, profile: PersonalRecommendations.Profile, onUpdate: (Playlist) -> Unit
) {
    val lease = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
    val latestDetail = androidx.compose.runtime.rememberUpdatedState(detail)
    val latestProfile = androidx.compose.runtime.rememberUpdatedState(profile)
    val latestUpdate = androidx.compose.runtime.rememberUpdatedState(onUpdate)
    var revision by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(context, lease) {
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(context, "home_page_view_state_v1", 0, lease)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("today-style-") == true || key == "today-refresh-batch-seq" || key?.startsWith("today-recommendation-fingerprint:") == true) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val platform = detail?.source.orEmpty()
    val fingerprint = remember(platform, profile, revision) {
        xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation.fingerprint(context, platform, profile)
    }
    LaunchedEffect(platform, fingerprint, detail?.id, revision) {
        if (detail?.id != "today_recommendation") return@LaunchedEffect
        val state = xyz.cncnlp.soundwave.HomePageStateStore
        val slots = xyz.cncnlp.soundwave.TodayRecommendationSlot
        val valid = state.getString(context, slots.fingerprintStateKey(platform)) == fingerprint &&
            state.getString(context, slots.slotStateKey(platform)) == slots.slotKey(System.currentTimeMillis())
        val songs = if (valid) withContext(Dispatchers.IO) {
            HomeFeedCache.loadDetailSnapshot(context, slots.cacheId(platform), platform).songs
        } else emptyList()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // r657 第三轮 b：快照尚未落盘（首页仍在生成）或为空时保留打开时带入的首页列表，不再把详情置空；
        // 生成完成后经 TodayRecommendationRefresher.updates / 下次校验替换。
        if (songs.isEmpty()) return@LaunchedEffect
        if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(lease) && latestDetail.value?.id == detail.id &&
            latestDetail.value?.source == platform && xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation.fingerprint(context, platform, latestProfile.value) == fingerprint) {
            latestUpdate.value(detail.copy(songs = songs.toMutableList(), count = songs.size, cover = xyz.cncnlp.soundwave.todayRecommendationCover(songs)))
        }
    }
    LaunchedEffect(lease) {
        xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.updates.collect { update ->
            if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(lease) &&
                TodayRecommendationFrontend.acceptsUpdate(context, latestDetail.value?.source.orEmpty(), latestProfile.value, update)) {
                xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.applyToDetail(latestDetail.value, update)?.let { latestUpdate.value(it) }
            }
        }
    }
}
