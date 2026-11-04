package xyz.cncnlp.soundwave
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import xyz.cncnlp.soundwave.ui.common.smoothScrollToTop
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding
import xyz.cncnlp.soundwave.ui.common.PlatformLineGlyphs
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable

import xyz.cncnlp.soundwave.ui.HOME_CONTENT_BADGE_STYLE
import xyz.cncnlp.soundwave.ui.notice.AppNotice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import xyz.cncnlp.soundwave.ui.motion.sharedCoverSource
import xyz.cncnlp.soundwave.ui.motion.SharedCoverTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import xyz.cncnlp.soundwave.ui.common.staggerEnter
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.tween
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.zIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.StateBlock
import xyz.cncnlp.soundwave.ui.common.StateLoadingBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ui.HomeSectionNavigation
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.direct.HotArtist
import xyz.cncnlp.soundwave.source.direct.fetchHotArtists
import xyz.cncnlp.soundwave.ui.home.FoldedCoverCard
import xyz.cncnlp.soundwave.ui.home.PlaylistCategoryDataController
import xyz.cncnlp.soundwave.ui.home.FeedEmptySlot
import xyz.cncnlp.soundwave.ui.home.HomePullRefreshIndicator
import xyz.cncnlp.soundwave.ui.home.homePlaylistGridRows
import xyz.cncnlp.soundwave.ui.home.PlaylistPlazaHero
import xyz.cncnlp.soundwave.ui.home.ChartCatalogStrip
import xyz.cncnlp.soundwave.ui.home.ChartHeroCard
import xyz.cncnlp.soundwave.ui.home.ChartPickerRow
import xyz.cncnlp.soundwave.ui.home.ChartPickerGrid
import xyz.cncnlp.soundwave.ui.home.ChartRankRow
import xyz.cncnlp.soundwave.ui.home.ChartListHeader
import xyz.cncnlp.soundwave.ui.home.loadHomeNewSongExpress
import xyz.cncnlp.soundwave.ui.home.loadSelectedRankDetail
import xyz.cncnlp.soundwave.ui.home.rememberHomeHotBoardDetail
import xyz.cncnlp.soundwave.ui.common.rememberScrolledPastTop
internal fun playlistRecommendationKey(playlist: Playlist, fallbackPlatform: String): String =
    "${playlist.source.ifBlank { fallbackPlatform }.lowercase()}:${playlist.remoteId.ifBlank { playlist.id }}"

/** Select only a known hot-chart name from the selected platform's own live ranking list. */
internal fun selectHomeHotBoard(platform: String, boards: List<RankBoard>): RankBoard? {
    val source = canonicalSourceId(platform)
    val names = when (source) {
        "wy" -> setOf("热歌榜", "网易云热歌榜")
        "kw" -> setOf("酷我热歌榜", "酷我音乐热歌榜")
        // Kugou's live official catalog calls its general hot chart TOP500 (rankid=8888).
        "kg" -> setOf("酷狗热歌榜", "酷狗音乐热歌榜", "TOP500")
        "tx" -> setOf("QQ热歌榜", "QQ音乐热歌榜")
        "mg" -> setOf("咪咕热歌榜", "咪咕音乐热歌榜", "全网热歌榜")
        else -> emptySet()
    }
    return boards.firstOrNull { board ->
        canonicalSourceId(board.source.ifBlank { source }) == source &&
            (board.name.trim() in names || source == "tx" && board.id.trim() in setOf("26", "tx_hot"))
    }
}

/** 平台弹层顶部小号说明（DM Mono 10sp、弱化色），保持弹层紧凑。 */
@Composable
private fun HomePopoverCaption(title: String) {
    Text(
        title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun HomeScrollTopButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier,
        contentAlignment = Alignment.Center
    ) { DetailAccentFab(Icons.Outlined.KeyboardArrowUp, "回到顶部", 22.dp, onClick) }
}

data class HomeFeedRenderState(
    val hot: List<Song>,
    val ranks: List<RankBoard>,
    val plReco: List<Playlist>,
    val multiHot: Map<String, List<Song>> = emptyMap(),
    val multiRank: Map<String, List<RankBoard>> = emptyMap(),
    val multiPl: Map<String, List<Playlist>> = emptyMap(),
    val feedLoading: Boolean = false,
    val isGestureRefreshing: Boolean = false,
    val feedStale: Boolean = false,
    val hotContent: LoadableContent<List<Song>> = LoadableContent.Empty(),
    val rankContent: LoadableContent<List<RankBoard>> = LoadableContent.Empty(),
    val plContent: LoadableContent<List<Playlist>> = LoadableContent.Empty(),
    val discover: List<DiscoverCard> = emptyList(),
)

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun HomePage(
    feedState: HomeFeedRenderState,
    history: List<Song>,
    likedSongs: List<Song> = emptyList(),
    /** 真实库曲目仅补充画像，不冒充我的喜欢/心动种子。 */
    tastePoolSongs: List<Song> = emptyList(),
    favoritePlaylists: List<Playlist> = emptyList(),
    favoriteAlbums: List<Playlist> = emptyList(),
    importedPlaylists: List<Playlist> = emptyList(),
    profileOwner: String = "local",
    recommendationProfile: PersonalRecommendations.Profile? = null,
    loggedIn: Boolean,
    user: String,
    avatarPath: String = "",
    platform: String,
    section: String = "hot",
    sectionPage: Int = HomeSectionNavigation.pageForSection(section),
    pagerSyncRevision: Int = 0,
    onSection: (String) -> Unit = {},
    onPlatform: (String) -> Unit,
    onLogin: () -> Unit,
    onAvatarLongClick: () -> Unit = {},
    onPlay: (Song, List<Song>) -> Unit,
    /** r658b #10：打开全屏播放页（与迷你条点开同一转场）。 */
    onOpenPlayer: () -> Unit = {},
    onOpenPl: (Playlist) -> Unit,
    isPlaylistFavorited: (Playlist) -> Boolean = { PlaylistFavorites.isFavorited(it) },
    onTogglePlaylistFavorite: (Playlist) -> Unit = { PlaylistFavorites.toggle(it) },
    onLike: (Song) -> Unit = {},
    onAdd: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {},
    isLiked: (Song) -> Boolean = { false },
    nowPlayingKey: String = "",
    isPlayingNow: Boolean = false,
    visible: Boolean = true,
    currentSong: Song? = null,
    onTogglePlayback: () -> Unit = {},
    onHeartMode: (List<Song>) -> Unit = {},
    /** 心动模式播放中标志（用于首页三宫格播放键状态切换）。 */
    heartActive: Boolean = false,
    onRetryFeed: () -> Unit = {},
    onRefreshHome: () -> Unit = {},
    onOpenDiscover: (DiscoverCard) -> Unit = {},
    /** 热门歌手头像点击：打开歌手详情（name, avatar, platformArtistId 可为空）。 */
    onOpenArtist: (String, String, String) -> Unit = { _, _, _ -> },
    onSearchEntry: () -> Unit = {},
    onPlaylistSearchEntry: () -> Unit = {},
    /** 排行榜多选：批量加入歌单 / 逐首插入队列 / 批量下载；多选态通知宿主隐藏迷你条与底栏。 */
    onAddBatch: (List<Song>) -> Unit = { it.firstOrNull()?.let(onAdd) },
    onAddNext: (Song) -> Unit = {},
    onDownloadBatch: (List<Song>) -> Unit = { it.forEach(onDownload) },
    onSelectionModeChange: (Boolean) -> Unit = {}
) {
    PlaySourceScope("首页") // r657 第二轮：播放来源
    val ranks = feedState.ranks
    val plReco = feedState.plReco
    val multiRank = feedState.multiRank
    val multiPl = feedState.multiPl
    val feedLoading = feedState.feedLoading
    val isGestureRefreshing = feedState.isGestureRefreshing
    val rankContent = feedState.rankContent
    val plContent = feedState.plContent
    val discover = feedState.discover
    val primary = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground
    val titleColor = onBg
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val platforms = listOf("wy" to "网易云", "kg" to "酷狗", "kw" to "酷我", "tx" to "QQ", "mg" to "咪咕")
    val sections = listOf("hot" to "热门", "rank" to "榜单", "pl" to "歌单")
    var platformPickerOpen by remember { mutableStateOf(false) }
    val homeContext = LocalContext.current
    val curatedScrollId = "home_curated_$platform"
    val curatedInitialIndex = remember(platform) { ListScrollStore.index(homeContext, curatedScrollId) }
    val curatedInitialOffset = remember(platform) { ListScrollStore.offset(homeContext, curatedScrollId) }
    val curatedScrollState = rememberLazyListState(curatedInitialIndex, curatedInitialOffset)
    DisposableEffect(platform, curatedScrollState) {
        onDispose {
            ListScrollStore.save(homeContext, curatedScrollId, curatedScrollState.firstVisibleItemIndex, curatedScrollState.firstVisibleItemScrollOffset)
        }
    }
    // 批19 #5（additive）：平台能力查注册表常量矩阵，不再散落字符串比较；
    // legacySinglePlatformFeed 仅 wy 为 true —— 旧版单平台 feed 字段（hot/ranks/plReco）仅由 wy 数据链填充。
    val homeCaps = remember(platform) { MusicSourceRegistry.capabilitiesOf(platform) }
    // 榜单/新歌速递等只认当前平台真实数据：冷启动缓存只有网易云时，不得用 wy 榜单冒充 kw/kg/tx/mg。
    val pranks = multiRank[platform].orEmpty().ifEmpty { if (homeCaps.legacySinglePlatformFeed) ranks else emptyList() }
    val allPls = multiPl[platform].orEmpty().ifEmpty { if (homeCaps.legacySinglePlatformFeed) plReco else emptyList() }
    val profileLease = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
    val playlistPlayScope = rememberCoroutineScope()
    val playlistPlayController = remember(platform, profileLease) { xyz.cncnlp.soundwave.ui.home.HomePlaylistPlayController() }
    val latestPlaylistPlay = androidx.compose.runtime.rememberUpdatedState(onPlay)
    val playPlaylistDirectly: (Playlist) -> Unit = { playlist ->
        playlistPlayScope.launch {
            playlistPlayController.play(playlist, platform,
                load = { source, remoteId -> withContext(Dispatchers.IO) {
                    if (!ProviderRegistry.capability(source, LocalStore.prefs(homeContext)).playlistDetail) emptyList()
                    else if (source in MusicSourceRegistry.platformIds) MusicSourceRegistry.platformSource(source).playlistDetail(remoteId)
                    else ApiClient.playlistDetail(remoteId, source)
                } },
                isCurrent = { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(profileLease) },
                onPlay = { song, songs -> latestPlaylistPlay.value(song, songs) },
                onUnavailable = { AppNotice.toast("暂时无法播放此歌单，请稍后重试", AppNotice.Kind.FAILURE) }
            )
        }
    }
    // ACCEPT5-PERF：统计采集 + 画像构建（全量 song_map 解析 ×2 + 365 天日统计投影 + decayedFacts 再解析）
    // 原先在 remember 内于组合主线程同步执行，visible/history/收藏等键一变就冻结首页；现异步快照填入：
    // 快照未到时先用空画像（下游按无统计口径渲染），到达后以同一输入同一口径重算，最终画像与旧实现一致。
    var homeStatsSongs by remember { mutableStateOf<List<xyz.cncnlp.soundwave.ListenStats.SongStat>>(emptyList()) }
    var homeStatsProfile by remember { mutableStateOf<PersonalRecommendations.Profile?>(null) }
    LaunchedEffect(history, likedSongs, tastePoolSongs, favoritePlaylists, favoriteAlbums, importedPlaylists, profileOwner, profileLease, visible) {
        val gathered = withContext(Dispatchers.IO) {
            runCatching {
                val statsData = ContextData(homeContext, profileLease)
                val statsArtists = statsData.topArtists(20).map { it.singer to it.sec }
                val statsSongs = statsData.topSongs(60)
                statsSongs to PersonalRecommendations.buildProfile(
                    history, likedSongs, statsArtists, listenedSongs = statsSongs, profileOwner = profileOwner,
                    favoritePlaylists = favoritePlaylists, favoriteAlbums = favoriteAlbums,
                    importedPlaylists = importedPlaylists, librarySongs = tastePoolSongs,
                    dailyListenJson = xyz.cncnlp.soundwave.ListenStats.localDailyListenStats(homeContext),
                    dislikedSongKeys = xyz.cncnlp.soundwave.DislikeStore.keys(homeContext),
                    dislikedIdentities = xyz.cncnlp.soundwave.DislikeStore.identities(homeContext)
                )
            }.getOrNull()
        }
        if (gathered != null) {
            homeStatsSongs = gathered.first
            homeStatsProfile = gathered.second
        }
    }
    val listenedSongs = homeStatsSongs
    val tasteProfile = recommendationProfile ?: homeStatsProfile
        ?: PersonalRecommendations.Profile(emptyList(), emptySet(), "", "")
    val profileStateKey = "${tasteProfile.signature}:${tasteProfile.activitySignature}"
    val recommendationHasQueries = tasteProfile.queries.isNotEmpty()
    val recommendationCacheKey = remember(platform) { HomeFeedCache.personalRecommendationCacheKey(platform) }
    val recommendationOffsetKey = remember(platform, profileStateKey) {
        "playlist-recommendation-offset:${PersonalRecommendations.token("$platform|$profileStateKey")}"
    }
    val initialRecommendationCache = remember(recommendationCacheKey) {
        HomeFeedCache.loadBrowsePlaylists(homeContext, recommendationCacheKey)
    }
    val seenPlaylistState = remember(platform, profileStateKey) {
        mutableStateOf(PersonalRecommendations.loadSeen(homeContext, "playlist", platform, profileStateKey))
    }
    var personalizedPool by remember(platform) { mutableStateOf(initialRecommendationCache?.playlists.orEmpty()) }
    var recommendationLoading by remember(platform, recommendationHasQueries) {
        mutableStateOf(recommendationHasQueries && HomeFeedCache.browsePlaylistCacheNeedsRefresh(initialRecommendationCache))
    }
    var recommendationOffset by remember(platform, profileStateKey) {
        mutableStateOf(HomePageStateStore.getString(homeContext, recommendationOffsetKey, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0)
    }
    val homePlaylistKeys = remember(allPls, platform) {
        allPls.mapTo(mutableSetOf()) { PersonalRecommendations.playlistIdentity(it, platform) }
    }
    val latestHomePlaylistKeys = rememberUpdatedState(homePlaylistKeys)
    val latestTasteProfile = rememberUpdatedState(tasteProfile)
    val latestProfileStateKey = rememberUpdatedState(profileStateKey)
    val recommendationScope = rememberCoroutineScope()
    suspend fun generatePersonalRecommendations() {
        recommendationLoading = true
        try {
            val currentKeys = personalizedPool.drop(recommendationOffset).take(3)
                .mapTo(mutableSetOf()) { PersonalRecommendations.playlistIdentity(it, platform) }
            val excluded = latestHomePlaylistKeys.value + seenPlaylistState.value + currentKeys
            val generated = PersonalRecommendations.searchPlaylists(platform, latestTasteProfile.value, excluded)
            if (generated.isNotEmpty()) {
                personalizedPool = generated
                recommendationOffset = 0
                HomePageStateStore.putString(homeContext, recommendationOffsetKey, "0")
                withContext(Dispatchers.IO) { HomeFeedCache.saveBrowsePlaylists(homeContext, recommendationCacheKey, generated) }
                seenPlaylistState.value = PersonalRecommendations.rememberSeen(
                    homeContext, "playlist", platform, latestProfileStateKey.value,
                    generated.take(3).map { PersonalRecommendations.playlistIdentity(it, platform) }
                )
            } else if (personalizedPool.isEmpty()) {
                // Persist an empty result too: route/page recreation must not repeat the same failed searches.
                withContext(Dispatchers.IO) { HomeFeedCache.saveBrowsePlaylists(homeContext, recommendationCacheKey, emptyList()) }
            }
        } finally {
            recommendationLoading = false
        }
    }
    LaunchedEffect(platform, recommendationHasQueries) {
        if (!recommendationHasQueries) {
            recommendationLoading = false
            recommendationOffset = 0
            HomePageStateStore.putString(homeContext, recommendationOffsetKey, "0")
            return@LaunchedEffect
        }
        // HomePage remains composed while other routes are visible, so prewarm this platform's recommendation once here.
        val cached = withContext(Dispatchers.IO) { HomeFeedCache.loadBrowsePlaylists(homeContext, recommendationCacheKey) }
        if (cached != null) personalizedPool = cached.playlists
        if (!HomeFeedCache.browsePlaylistCacheNeedsRefresh(cached)) {
            recommendationLoading = false
            return@LaunchedEffect
        }
        generatePersonalRecommendations()
    }
    val recommendedPlaylists = personalizedPool.drop(recommendationOffset).take(3)
        .ifEmpty { if (recommendationLoading) personalizedPool.take(3) else emptyList() }
    val nextCachedGroup = personalizedPool.drop(recommendationOffset + 3).take(3)
    // 今日推荐：真实多来源画像；slot + 完整分类/平台/owner/画像 fingerprint 匹配才展示缓存。
    var recommendationSlot by remember { mutableStateOf(TodayRecommendationSlot.slotKey(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            delay((TodayRecommendationSlot.nextBoundaryMillis(now) - now).coerceAtLeast(1_000L))
            recommendationSlot = TodayRecommendationSlot.slotKey(System.currentTimeMillis())
        }
    }
    LaunchedEffect(visible) { if (visible) recommendationSlot = TodayRecommendationSlot.slotKey(System.currentTimeMillis()) }
    // Preferences are not Compose state: observe tags/enabled/batch written by the detail sheet/refresher.
    var dailyPreferenceRevision by remember { mutableStateOf(0) }
    DisposableEffect(homeContext, profileLease) {
        val dailyPrefs = ContextPreferences.open(homeContext, "home_page_view_state_v1", 0, profileLease)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("today-style-") == true || key == "today-refresh-batch-seq") dailyPreferenceRevision++
        }
        dailyPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { dailyPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dailyFingerprint = remember(platform, tasteProfile, dailyPreferenceRevision, profileLease) {
        xyz.cncnlp.soundwave.recommend.StyleWeightedRecommendation.fingerprint(homeContext, platform, tasteProfile)
    }
    var dailySongs by remember(platform, dailyFingerprint, recommendationSlot) { mutableStateOf(emptyList<Song>()) }
    var dailyLoading by remember(platform, dailyFingerprint, recommendationSlot) { mutableStateOf(true) }
    val latestDailyPlatform = rememberUpdatedState(platform)
    val latestDailyFingerprint = rememberUpdatedState(dailyFingerprint)
    val latestDailySlot = rememberUpdatedState(recommendationSlot)
    val latestTasteForDaily = rememberUpdatedState(tasteProfile)
    LaunchedEffect(platform, recommendationSlot, dailyFingerprint) {
        val profileSnapshot = tasteProfile
        val fingerprintSnapshot = dailyFingerprint
        val slotSnapshot = recommendationSlot
        dailyLoading = true
        dailySongs = emptyList()
        fun current() = latestDailyPlatform.value == platform && latestDailyFingerprint.value == fingerprintSnapshot &&
            latestDailySlot.value == slotSnapshot && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(profileLease)
        try {
            val songs = withContext(Dispatchers.IO) {
                xyz.cncnlp.soundwave.ui.home.TodayRecommendationFrontend.load(homeContext, platform, profileSnapshot, slotSnapshot) { current() }
            }
            if (current() && songs != null) dailySongs = songs
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never revive another classification/owner after failure.
            if (current()) dailySongs = emptyList()
        } finally {
            if (current()) dailyLoading = false
        }
    }
    LaunchedEffect(platform) {
        xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.updates.collect { update ->
            if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(profileLease) &&
                xyz.cncnlp.soundwave.ui.home.TodayRecommendationFrontend.acceptsUpdate(homeContext, latestDailyPlatform.value, latestTasteForDaily.value, update)) {
                dailySongs = update.songs
                dailyLoading = false
            }
        }
    }
    // 首页热门歌曲与当前平台官方热歌榜共享同一 source/id，禁止用 /music/hot 聚合/搜索列表替代。
    val platformHotBoard = remember(pranks, platform) { selectHomeHotBoard(platform, pranks) }
    val hotBoardSource = platformHotBoard?.source?.ifBlank { platform }.orEmpty()
    // 首页平台歌单来自匿名公开feed；本地Taste推荐仍在下方独立组装。
    val recoBase = if (platform == "wy") multiPl["wy"].orEmpty().ifEmpty { plReco } else multiPl[platform].orEmpty()
    val featuredPlaylists = recoBase.distinctBy { featuredPlaylistStableKey(it, platform) }.take(30)
    val featuredFingerprint = featuredPlaylists.joinToString("\u001f") {
        listOf(it.source, it.remoteId, it.id, it.name, it.author, it.description, it.cover, it.count.toString(), it.playCount.toString(),
            it.songs.joinToString(",") { song -> song.key() }).joinToString("\u001e")
    }
    var featuredRefreshPending by remember(platform) { mutableStateOf(false) }
    var featuredFingerprintAtRefresh by remember(platform) { mutableStateOf(featuredFingerprint) }
    LaunchedEffect(platform, featuredRefreshPending, feedLoading, featuredFingerprint) {
        if (featuredRefreshPending && !feedLoading) {
            if (featuredFingerprint != featuredFingerprintAtRefresh) {
                curatedScrollState.scrollToItem(0)
                ListScrollStore.save(homeContext, curatedScrollId, 0, 0)
            }
            featuredRefreshPending = false
        }
    }
    var hotChartRetryTick by remember { mutableStateOf(0) }
    val hotChartState = rememberHomeHotBoardDetail(platform, platformHotBoard, hotChartRetryTick)
    val platformHotChartSongs = hotChartState.songs
    val hotChartLoading = hotChartState.loading
    val hotChartError = hotChartState.error
    val carouselActive = homeCarouselActive(visible && section == "hot")
    Column(Modifier.fillMaxSize()) {
        // 首页采用 10 月 13 日长截图：标题 + 圆形平台下拉 + 头像，不显示旧版分页标签。
        val sectionIdx = HomeSectionNavigation.externalPage(sectionPage, sections.size)
        // Local tab settlement must not recreate every pager page and its list state.
        val pagerState = key(pagerSyncRevision) {
            rememberPagerState(initialPage = sectionIdx) { sections.size }
        }
        val currentSection = sections.getOrNull(pagerState.currentPage)?.first ?: "hot"
        // accept6-topbars：首页（hot 分区）滚动联动顶栏状态 + 局部玻璃源（与详情页同挂法）。
        val homeChromeState = remember { MainTabTopChromeState() }
        val homeChromeGlass = xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState()
        AppPageHeader(
            title = when (currentSection) { "rank" -> "排行榜"; "pl" -> "歌单广场"; else -> "首页" },
            loggedIn = loggedIn,
            avatarPath = avatarPath,
            onProfileClick = onLogin,
            onAvatarLongClick = onAvatarLongClick,
            showAvatar = true,
            // 设计稿 v2 页头：31sp 衬线标题 + 42dp 白色描边平台按钮 + 42dp 头像。
            avatarSizeDp = 42f,
            titleSizeSp = 26,
            serifTitle = true,
            designTitle = true,
            startPadding = 14.dp,
            // accept6-topbars：仅 hot 分区接滚动联动；排行榜 / 歌单广场顶栏保持现状。
            chromeState = if (currentSection == "hot") homeChromeState else null,
            chromeGlass = homeChromeGlass,
            // 歌单广场与排行榜页顶部只显示标题，不显示左侧返回箭头（返回走系统返回/预测性返回手势，见下方 BackHandler）。
            leading = if (currentSection == "hot" || currentSection == "rank" || currentSection == "pl") null else ({
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { onSection("hot") }, contentAlignment = Alignment.CenterStart) {
                    Icon(Icons.Outlined.KeyboardArrowLeft, "返回首页", tint = onBg, modifier = Modifier.size(22.dp))
                }
            }),
            trailing = {
                // 平台切换：锚定在按钮下方的小型弹层（与搜索页「搜索歌曲/歌单/歌手/专辑」类别弹层同款），单色线性平台标记。
                Box {
                    HomePlatformButton { platformPickerOpen = true }
                    // r658b #19：原型 pick 抽屉「切换音源」（首页 / 排行榜 / 歌单广场共用）。
                    DesignPlatformPopover(
                        expanded = platformPickerOpen, onDismiss = { platformPickerOpen = false },
                        options = platforms.sortedBy { PlatformPickOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i } }, current = platform, title = "切换音源", eyebrow = "Music source",
                        subtitle = "首页推荐、榜单与歌单广场使用的平台。",
                        optionTitle = { key, _ -> xyz.cncnlp.soundwave.ui.common.platformFullName(key) },
                        optionSub = { key -> if (key == platform) "当前音源" else "" }
                    ) { key -> if (key != platform) onPlatform(key) }
                }
            }
        )
        val latestOnSection = rememberUpdatedState(onSection)
        // 排行榜 / 歌单分页支持系统返回手势回到首页。
        androidx.activity.compose.BackHandler(enabled = visible && currentSection != "hot") { onSection("hot") }
        LaunchedEffect(pagerState, sections) {
            // 仅在真实拖动/甩动结束时保存；顶部标签动画和 root 恢复不会误触发回写。
            val userDragEnded = mutableStateOf(false)
            launch {
                pagerState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> userDragEnded.value = false
                        is DragInteraction.Stop, is DragInteraction.Cancel -> userDragEnded.value = true
                    }
                }
            }
            snapshotFlow { Triple(pagerState.isScrollInProgress, pagerState.settledPage, userDragEnded.value) }
                .collect { (scrolling, page, completedUserDrag) ->
                    if (!scrolling && completedUserDrag) {
                        userDragEnded.value = false
                        sections.getOrNull(page)?.first?.let { sectionId ->
                            latestOnSection.value(HomeSectionNavigation.onPagerSettled(sectionId))
                        }
                    }
                }
        }
        LaunchedEffect(sectionPage, pagerSyncRevision) {
            // This is the same synchronization path exercised by the root->HomePage route-sequence test.
            HomeSectionNavigation.syncExternalPager(
                selectedPage = sectionPage,
                pageCount = sections.size,
                currentPage = pagerState.currentPage,
                settledPage = pagerState.settledPage,
                isScrollInProgress = pagerState.isScrollInProgress,
                scrollToPage = { page -> pagerState.scrollToPage(page) }
            )
        }
        // 歌单广场/排行榜子页：系统返回与预测性返回手势回到首页（手势进行中页面随进度轻微右移缩小）。
        var sectionBackProgress by remember { mutableFloatStateOf(0f) }
        val latestOnSectionBack = rememberUpdatedState(onSection)
        PredictiveBackHandler(enabled = visible && currentSection != "hot") { progress ->
            try {
                progress.collect { event -> sectionBackProgress = event.progress }
                sectionBackProgress = 0f
                latestOnSectionBack.value("hot")
            } catch (cancelled: CancellationException) {
                sectionBackProgress = 0f
                throw cancelled
            }
        }
        val backDensity = LocalDensity.current
        // r658d（用户 10-20）：点击进入排行榜 / 歌单广场（以及返回首页）时加入场动画——切页仍是 scrollToPage（不渲染中间页），
        // 新页内容 300ms 淡入 + 上移 28dp + 0.97→1 缩放。减少动态效果时不播。
        val sectionEnter = remember { androidx.compose.animation.core.Animatable(1f) }
        val sectionReduceMotion = MotionPolicy.rememberReduceMotion()
        var sectionEnterPrimed by remember { mutableStateOf(false) }
        LaunchedEffect(sectionPage) {
            if (!sectionEnterPrimed) { sectionEnterPrimed = true; return@LaunchedEffect }
            if (sectionReduceMotion) return@LaunchedEffect
            // r658f（用户 10-20：切回首页偶发白屏、要点两次）：动画被取消（连续切页 / 离开组合）时 alpha 会停在 0，
            // finally 里强制归 1，保证内容永远可见。
            try {
                sectionEnter.snapTo(0f)
                sectionEnter.animateTo(1f, androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { sectionEnter.snapTo(1f) }
            }
        }
        HorizontalPager(state = pagerState, userScrollEnabled = false, modifier = Modifier.fillMaxSize().graphicsLayer {
            val e = sectionEnter.value
            if (e < 1f) {
                alpha = e
                translationY = with(backDensity) { 28.dp.toPx() } * (1f - e)
                val sc = 0.97f + 0.03f * e
                scaleX = sc; scaleY = sc
            }
            val p = sectionBackProgress
            if (p > 0f) {
                translationX = with(backDensity) { 48.dp.toPx() } * p
                scaleX = 1f - 0.06f * p; scaleY = 1f - 0.06f * p
                alpha = 1f - 0.25f * p
            }
        }) { page ->
          val sec = sections[page].first
          Box(Modifier.fillMaxSize()) {
          when (sec) {
            "hot" -> {
                val list = platformHotChartSongs
                val hotStatusHint = hotChartError
                // 专辑横幅：屏幕左右各 15dp，横幅内封面与文案为真实 discover 数据。
                val discH = 224.dp
                // 回顶：翻到底部后往上滑出现、向下滑隐藏、默认隐藏。
                val hotScrollId = "home_hot_$platform"
                val hotListState = key(platform) {
                    rememberLazyListState(
                        ListScrollStore.index(homeContext, hotScrollId),
                        ListScrollStore.offset(homeContext, hotScrollId)
                    )
                }
                DisposableEffect(hotScrollId, hotListState) {
                    onDispose {
                        ListScrollStore.save(homeContext, hotScrollId, hotListState.firstVisibleItemIndex, hotListState.firstVisibleItemScrollOffset)
                    }
                }
                // accept6-topbars：hot 流滚动进度 → 顶栏磨砂带 + 居中「首页」。
                MainTabChromeDriver(hotListState, homeChromeState)
                val hotScope = rememberCoroutineScope()
                var hotShowTop by remember { mutableStateOf(false) }
                var hotLastPos by remember { mutableStateOf(0) }
                // r656-G：snapshotFlow 在效果内观察滚动位置，不再把滚动偏移当作 LaunchedEffect key 在组合期读取（每帧重组整页）。
                LaunchedEffect(hotListState) { androidx.compose.runtime.snapshotFlow { hotListState.firstVisibleItemIndex * 1000 + hotListState.firstVisibleItemScrollOffset }.collect { pos ->
                    if (pos > 500) {
                        if (pos < hotLastPos) hotShowTop = true
                        else if (pos > hotLastPos + 250) hotShowTop = false
                    } else hotShowTop = false
                    hotLastPos = pos
                } }
                val homeRankBoards = remember(pranks, platform, homeCaps.legacySinglePlatformFeed) {
                    val eligible = pranks.filter { it.source.equals(platform, true) || (it.source.isBlank() && homeCaps.legacySinglePlatformFeed) }
                    val preferred = listOf("飙升", "热歌", "新歌").mapNotNull { key -> eligible.firstOrNull { it.name.contains(key) } }
                    (preferred + eligible.filterNot { candidate -> preferred.any { it.id == candidate.id } })
                        .distinctBy { it.source + ":" + it.id }
                }
                Box(Modifier.fillMaxSize()) {
                // 新碟首发仅消费各平台真实 new_album 目录，不能由 new_song 搜索结果冒充。
                // 新歌速递列表单独来自当前平台新歌榜，不复用顶部发现横幅。
                // 1.0.655：初值取进程内缓存，切页返回首帧即显示；后台刷新仅以非空结果替换（不再空→有闪烁跳动）。
                var newSongs by remember(platform) { mutableStateOf(xyz.cncnlp.soundwave.ui.home.HomeSectionMemory.newSongs(platform)) }
                LaunchedEffect(platform, ranks) {
                    val nb = pranks.firstOrNull { it.name.contains("新歌") }
                    val loaded = withContext(Dispatchers.IO) {
                        loadHomeNewSongExpress(nb) { boardId ->
                            MusicSourceRegistry.platformSource(platform).rank(boardId)
                        }
                    }
                    if (loaded.isNotEmpty()) {
                        xyz.cncnlp.soundwave.ui.home.HomeSectionMemory.putNewSongs(platform, loaded)
                        if (loaded != newSongs) newSongs = loaded
                    }
                }
                // 热门歌手：平台当前热门歌手（真实头像）；无平台数据时回落听歌统计。
                var hotArtists by remember(platform) { mutableStateOf(xyz.cncnlp.soundwave.ui.home.HomeSectionMemory.hotArtists(platform)) }
                LaunchedEffect(platform, if (platform == "mg") platformHotChartSongs else emptyList<Song>()) {
                    val loaded = withContext(Dispatchers.IO) { fetchHotArtists(platform, chartSongs = platformHotChartSongs) }
                    if (loaded.isNotEmpty()) {
                        xyz.cncnlp.soundwave.ui.home.HomeSectionMemory.putHotArtists(platform, loaded)
                        if (loaded != hotArtists) hotArtists = loaded
                    }
                }
                val hotRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val hotRefreshing = isGestureRefreshing && feedLoading
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = hotRefreshing,
                    onRefresh = {
                        hotChartRetryTick++
                        featuredFingerprintAtRefresh = featuredFingerprint
                        featuredRefreshPending = true
                        onRefreshHome()
                    },
                    modifier = Modifier.fillMaxSize(),
                    state = hotRefreshState,
                    indicator = {
                        HomePullRefreshIndicator(hotRefreshState, hotRefreshing, primary, Modifier.align(Alignment.TopCenter))
                    }
                ) {
                // r656：今日推荐与顶部间距再缩小 3dp（17→14dp）；详情页封面顶距与之统一（DetailHeroTopGap）。
                // r657 修复（审计第 20 项）：首屏区块 60ms 错峰入场（页面级门，滚回顶部不重播；简洁动效关闭）。
                val homeStagger = xyz.cncnlp.soundwave.ui.common.rememberStaggerGate()
                LazyColumn(Modifier.fillMaxSize().then(xyz.cncnlp.soundwave.ui.common.localGlassSourceModifier(homeChromeGlass)), state = hotListState, contentPadding = PaddingValues(top = DetailHeroTopGap)) {
                    item(key = "daily_signal") {
                        // 严格筛选可为空；热榜不属于当前风格，不能补位或打开成今日推荐。
                        val heroList = dailySongs
                        Box(Modifier.staggerEnter(0, homeStagger)) {
                        HomeDailyHero(
                            heroList, loading = dailyLoading && heroList.isEmpty(),
                            onOpen = {
                                onOpenPl(todayRecommendationDetail(platform, heroList))
                            },
                            // r658b #10：按钮直接从第一首播放今日推荐，并以播放页转场打开播放器。
                            onPlayAll = {
                                heroList.firstOrNull()?.let { first ->
                                    PlaySourceTracker.force("今日推荐", heroList)
                                    onPlay(first, heroList)
                                    onOpenPlayer()
                                }
                            }
                        )
                        }
                    }
                    // 甄选卡片仅使用歌单自身封面；普通歌单/榜单均不借用曲目图或同名榜图伪装。
                    item {
                        Box(Modifier.staggerEnter(1, homeStagger)) { HomeSectionHeader("甄选歌单", link = "View all ›", topGap = 30.dp, onTitle = { onSection("pl") }, onLink = { onSection("pl") }) }
                    }
                    if (featuredPlaylists.isEmpty()) item {
                        val (ld, er, et) = plContent.toEmptySlot("暂无甄选歌单")
                        FeedEmptySlot(ld, er, et, onRetryFeed)
                    } else item {
                        var curatedHeldPlKey by remember { mutableStateOf<String?>(null) }
                        // 一屏恰好 3 张：卡宽 = (屏宽 - 左右 14×2 - 间距 8×2) / 3。
                        val curatedW = ((LocalConfiguration.current.screenWidthDp - 28 - 16) / 3f).dp
                        LazyRow(modifier = Modifier.staggerEnter(2, homeStagger), state = curatedScrollState, contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(featuredPlaylists, key = { featuredPlaylistStableKey(it, platform) }) { pl ->
                                val stableKey = featuredPlaylistStableKey(pl, platform)
                                // 甄选歌单副标题（设计稿 .album-sub 12.5sp / 行高 1.35 / 上距 3）。
                                val curatedSub = listOf(pl.author, if (pl.count > 0) "${pl.count} 首" else "").filter { it.isNotBlank() }.joinToString(" · ")
                                // 悬停 / 按压（含按住滑过）时右下角出现播放按钮；松手后保留 2.5s 便于点按。长按仍可固定显示。
                                val cardHover = remember { MutableInteractionSource() }
                                val cardHovered by cardHover.collectIsHoveredAsState()
                                var cardPressed by remember { mutableStateOf(false) }
                                var cardLinger by remember { mutableStateOf(false) }
                                LaunchedEffect(cardPressed) {
                                    if (cardPressed) cardLinger = true else if (cardLinger) { delay(2500L); cardLinger = false }
                                }
                                val showCardPlay = cardHovered || cardPressed || cardLinger || curatedHeldPlKey == stableKey
                                HomeCuratedCard(
                                    title = pl.name,
                                    subtitle = curatedSub,
                                    coverModifier = Modifier.sharedCoverSource(pl.id).hoverable(cardHover).homeCarouselTouch { cardPressed = it },
                                    cardWidth = curatedW,
                                    cover = { m ->
                                        // 歌单封面只能来自该歌单自己的 provider 字段；缺失时留空，不借用同名榜单图。
                                        Box {
                                            FoldedCoverCard(
                                                pl.cover, curatedW, primary,
                                                source = pl.source.ifBlank { platform },
                                                itemId = pl.remoteId.ifBlank { pl.id },
                                                onClick = { curatedHeldPlKey = null; SharedCoverTransition.arm(pl.id); onOpenPl(pl.copy(remoteId = pl.remoteId.ifBlank { pl.id })) },
                                                // 长按（未进入歌单）右下角出现播放按钮，点击可播放；默认不显示。
                                                onLongClick = { curatedHeldPlKey = if (curatedHeldPlKey == stableKey) null else stableKey }
                                            )
                                            // r657（v3 #2）：左上角毛玻璃「耳机 + 播放量」胶囊。
                                            xyz.cncnlp.soundwave.ui.home.CoverPlayCountPill(pl.playCount, Modifier.align(Alignment.TopStart).padding(7.dp))
                                        }
                                    }
                                ) {
                                    val playAppear by androidx.compose.animation.core.animateFloatAsState(if (showCardPlay) 1f else 0f, androidx.compose.animation.core.tween(180), label = "curatedPlay")
                                    // 设计稿 .cover-play：无底色纯白圆角三角（悬停/按压/长按时出现）。
                                    CoverRevealPlay(playAppear, "播放：${pl.name}", size = 28.dp, padding = 8.dp) {
                                        curatedHeldPlKey = null
                                        playPlaylistDirectly(pl)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        // 与上方数据请求复用同一个当前平台榜单对象，确保展示/更多同 ID。
                        val hotBoard = platformHotBoard
                        val canOpenBoard = hotBoard != null && hotBoard.id.isNotBlank() && hotBoard.name.isNotBlank()
                        Box(Modifier.staggerEnter(3, homeStagger)) { HomeSectionHeader(
                            // r657（v4 #2）：右侧改为「TOP N ›」，N 随首页平台切换（见 homeHotTopCount）。
                            "热门歌曲", link = "TOP ${homeHotTopCount(platform, hotBoard?.songCount() ?: 0, platformHotChartSongs.size)} ›", linkSizeSp = 11f, topGap = 24.dp,
                            onLink = if (!canOpenBoard) null else ({
                                val board = hotBoard!!
                                onOpenPl(
                                    Playlist(
                                        id = "rank_${board.id}",
                                        name = board.name,
                                        cover = board.previewCover,
                                        songs = platformHotChartSongs.toMutableList(),
                                        source = hotBoardSource,
                                        author = "官方榜单",
                                        count = board.songCount()
                                    )
                                )
                            })
                        ) }
                    }
                    if (!hotChartLoading && list.isNotEmpty() && hotStatusHint.isNotBlank()) item {
                        Text(
                            "$hotStatusHint · 点按重试",
                            color = onBg.copy(alpha = 0.62f),
                            fontSize = 10.sp,
                            modifier = Modifier.fillMaxWidth().clickable { hotChartRetryTick++; onRetryFeed() }.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
                        )
                    }
                    if (list.isEmpty()) item {
                        if (platformHotBoard == null) StateBlock(DesignLineGlyphs.Disc, "当前平台暂无热歌榜")
                        else FeedEmptySlot(hotChartLoading, hotChartError, "暂无热歌榜歌曲") { hotChartRetryTick++ }
                    }
                    else item {
                        Box(Modifier.staggerEnter(4, homeStagger)) { HomeHotSongsPager(
                            songs = list,
                            platform = platform,
                            nowPlayingKey = nowPlayingKey,
                            isPlayingNow = isPlayingNow,
                            onTogglePlayback = onTogglePlayback,
                            onPlay = onPlay
                        ) }
                    }
                    item(key = "featured_channels") {
                        // 心动模式推荐种子：我的喜欢 → 播放历史/听歌统计 → 首次无记录时随机（当前平台热歌/新歌）。
                        val heartSeed = remember(likedSongs, history, listenedSongs, platformHotChartSongs, newSongs) {
                            heartModeSeedPool(likedSongs, history, listenedSongs.map { it.name to it.singer }, platformHotChartSongs + newSongs)
                        }
                        HomeFeatureCarousel(
                            active = carouselActive,
                            currentSong = currentSong,
                            playlistCover = featuredPlaylists.firstOrNull()?.cover.orEmpty(),
                            chartCover = platformHotBoard?.previewCover.orEmpty(),
                            playlistCoverBack = featuredPlaylists.getOrNull(1)?.cover.orEmpty(),
                            chartCoverBack = platformHotBoard?.songs?.getOrNull(1)?.cover.orEmpty(),
                            heartActive = heartActive,
                            onPlaylists = { onSection("pl") },
                            onCharts = { onSection("rank") },
                            heartCover = heartSeed.firstOrNull { it.cover.isNotBlank() }?.cover.orEmpty(),
                            heartAvailable = heartSeed.isNotEmpty(),
                            onHeart = { onHeartMode(heartSeed) }
                        )
                    }
                    // 新歌速递由当前平台新歌榜提供，避免与顶部发现横幅重复。
                    if (newSongs.isNotEmpty()) {
                        item {
                            HomeSectionHeader("新歌速递", link = "New releases", topGap = 8.dp, onLink = {
                                val nb0 = pranks.firstOrNull { it.name.contains("新歌") }
                                if (nb0 != null) onOpenPl(Playlist(id = "rank_${nb0.id}", name = nb0.name, cover = nb0.previewCover, songs = nb0.songs.toMutableList(), source = platform, author = "官方榜单", count = nb0.songCount())) else onSection("rank")
                            })
                        }
                        item {
                            HomeNewSongList {
                                // 设计稿 .new-song-row：58dp 封面 + 16sp 歌名，序号在最右，行间 1dp 分隔线。
                                newSongs.take(10).forEachIndexed { idx, s ->
                                    HomeNewSongRow(s, index = idx + 1, platformLabel = platformName(s.source.ifBlank { platform }), nowPlaying = s.key() == nowPlayingKey) { onPlay(s, newSongs) }
                                }
                            }
                        }
                    }
                    val digitalCards = discover.filter { it.kind.equals("digital", true) }
                    if (digitalCards.isNotEmpty()) {
                        item { HomeSectionHeader("数字专辑", link = "SWIPE FOR MORE", linkSizeSp = 10f, linkAccent = true) }
                        item { HomeDigitalAlbumCarousel(digitalCards, platform, carouselActive, onOpenDiscover) }
                    }
                    // 新碟首发：wy 显示真实新碟专辑卡；其他平台展示该平台真实新歌卡。
                    val newAlbumCards = discover.filter {
                        it.kind.equals("new_album", true)
                    }
                    if (newAlbumCards.isNotEmpty()) {
                        item { HomeSectionHeader("新碟首发", link = "New albums", linkAccent = true) }
                        item {
                            // 无限循环：虚拟页数 = 卡片数 × 1000，从中间开始，自动轮播可一直向后滚动。
                            val albumLoop = newAlbumCards.size > 1
                            val albumPageCount = if (albumLoop) newAlbumCards.size * 1000 else newAlbumCards.size
                            val albumPager = rememberPagerState(initialPage = if (albumLoop) newAlbumCards.size * 500 else 0, pageCount = { albumPageCount })
                            var albumHeld by remember { mutableStateOf(false) }
                            val albumInteractionSource = remember { MutableInteractionSource() }
                            HomePagerAutoAdvance(albumPager, carouselActive, albumHeld)
                            Column {
                                // 卡宽 = 屏宽 80%，右侧露出下一张。
                                val debutWidth = (LocalConfiguration.current.screenWidthDp * 0.80f).coerceIn(280f, 361f).dp
                                HorizontalPager(state = albumPager, pageSize = PageSize.Fixed(debutWidth), contentPadding = PaddingValues(start = 14.dp, end = 14.dp), pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth().height(172.dp).homeCarouselTouch { albumHeld = it }.hoverable(albumInteractionSource).focusable(interactionSource = albumInteractionSource)) { page ->
                                    val index = page % newAlbumCards.size
                                    val card = newAlbumCards[index]
                                    // 每页独立取色：key=card.id，封面滚动/切换时不会把上一首的取色背景带到下一首。
                                    val toneState = remember(card.id, card.cover) { mutableStateOf<Color?>(null) }
                                    LaunchedEffect(card.id, card.cover) { toneState.value = coverToneColor(homeContext, card.cover) }
                                    // 切换动画：非当前页缩小并淡出，自动轮播 / 手势切页时平滑过渡。
                                    HomeDebutCard(card, index, toneState.value, Modifier.padding(top = 8.dp, bottom = 14.dp).graphicsLayer {
                                        val off = kotlin.math.abs((albumPager.currentPage - page) + albumPager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                        val sc = 1f - 0.08f * off
                                        scaleX = sc; scaleY = sc; alpha = 1f - 0.35f * off
                                    }) { onOpenDiscover(card) }
                                }
                                HomePagerDots(newAlbumCards.size.coerceAtMost(7), (albumPager.currentPage % newAlbumCards.size).coerceAtMost(6), Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    item { HomeSectionHeader("热门歌手", link = "SWIPE FOR MORE", linkSizeSp = 10f, linkAccent = true) }
                    if (hotArtists.isNotEmpty()) item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(hotArtists, key = { it.id.ifBlank { it.name } }) { artist ->
                                HomeArtistCard(artist.name, artist.avatar) { onOpenArtist(artist.name, artist.avatar, artist.id) }
                            }
                        }
                    } else if (platformHotChartSongs.isNotEmpty()) item {
                        // 官方头像元数据暂不可用时只用当前平台榜单姓名，不借用歌曲封面。
                        val chartArtists = remember(platform, platformHotChartSongs) {
                            platformHotChartSongs.filter { it.source.equals(platform, true) }.map { it.singer.split('/', '、', ',', '&').first().trim() }.filter { it.isNotBlank() }.distinct().take(12)
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chartArtists, key = { it }) { artist -> HomeArtistCard(artist, "") { onOpenArtist(artist, "", "") } }
                        }
                    } else {
                        // listenedArtists aggregates all platforms: it is not this platform's hot-artist feed.
                        item { Text("当前平台暂无热门歌手", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                    }
                    item { Spacer(Modifier.height(dockContentBottomPadding())) }
                }
                }
                if (hotShowTop) {
                    HomeScrollTopButton(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockContentBottomPadding() + 12.dp)) {
                        hotScope.launch { hotListState.smoothScrollToTop() }
                    }
                }
                }
            }
            "hot_rank" -> {
                val hotRankSongs = platformHotChartSongs
                val label = platforms.firstOrNull { it.first == platform }?.second ?: "当前平台"
                val hotRankScrollState = rememberLazyListState()
                val hotRankScope = rememberCoroutineScope()
                val hotRankPullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val hotRankRefreshing = isGestureRefreshing && (feedLoading || hotChartLoading)
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = hotRankRefreshing,
                    onRefresh = { hotChartRetryTick++; onRefreshHome() },
                    modifier = Modifier.fillMaxSize(),
                    state = hotRankPullState,
                    indicator = {
                        HomePullRefreshIndicator(hotRankPullState, hotRankRefreshing, primary, Modifier.align(Alignment.TopCenter))
                    }
                ) {
                    LazyColumn(state = hotRankScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = dockContentBottomPadding())) {
                        item {
                            Text("$label · 实时热门歌曲", color = onBg.copy(alpha = 0.62f), fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
                        }
                        if (hotRankSongs.isEmpty()) item {
                            FeedEmptySlot(hotChartLoading, hotChartError, "暂无热歌榜歌曲") { hotChartRetryTick++ }
                        } else item {
                            HomeSongListShell {
                                hotRankSongs.forEachIndexed { idx, song ->
                                    SongRow(song, index = idx + 1, platformSource = platform, uniformIndexColor = true, showActions = false, showLike = false, flat = true, showDownload = false, liked = isLiked(song), nowPlaying = song.key() == nowPlayingKey, isPlayingNow = isPlayingNow, coverScale = 1f, tight = true, onLike = { onLike(song) }, onAdd = { onAdd(song) }, onDownload = { onDownload(song) }) { onPlay(song, hotRankSongs) }
                                }
                            }
                        }
                    }
                    if (hotRankScrollState.rememberScrolledPastTop()) {
                        HomeScrollTopButton(
                            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockContentBottomPadding() + 12.dp)
                        ) { hotRankScope.launch { hotRankScrollState.smoothScrollToTop() } }
                    }
                }
            }
            "rank" -> {
                // 仅使用当前平台真实榜单；榜单选择不合成或推断周榜数据。
                val allBoards = pranks.filter { it.source.equals(platform, true) || (it.source.isBlank() && homeCaps.legacySinglePlatformFeed) }
                    .distinctBy { it.source + ":" + it.id }
                var selectedRankId by remember(platform) {
                    val stored = HomePageStateStore.getString(homeContext, "rank:$platform")
                    val schemaVersion = HomePageStateStore.getString(homeContext, KW_RANK_ID_SCHEMA_KEY)
                    val initial = if (platform.equals("kw", ignoreCase = true) && schemaVersion != KW_RANK_ID_SCHEMA_VERSION) {
                        migrateLegacyKuwoRankId(stored)
                    } else stored
                    mutableStateOf(initial)
                }
                LaunchedEffect(platform) {
                    if (platform.equals("kw", ignoreCase = true)) {
                        val schemaVersion = HomePageStateStore.getString(homeContext, KW_RANK_ID_SCHEMA_KEY)
                        if (schemaVersion != KW_RANK_ID_SCHEMA_VERSION) {
                            HomePageStateStore.putStrings(
                                homeContext,
                                mapOf(
                                    "rank:$platform" to selectedRankId,
                                    KW_RANK_ID_SCHEMA_KEY to KW_RANK_ID_SCHEMA_VERSION,
                                ),
                            )
                        }
                    }
                }
                var boardPickerOpen by remember(platform) { mutableStateOf(false) }
                val selectedBoard = allBoards.firstOrNull { it.id == selectedRankId } ?: allBoards.firstOrNull()
                val rankContext = LocalContext.current
                val rankScrollId = "home_rank_${platform}_${selectedBoard?.id.orEmpty()}"
                val rankScrollState = rememberLazyListState(
                    ListScrollStore.index(rankContext, rankScrollId),
                    ListScrollStore.offset(rankContext, rankScrollId)
                )
                DisposableEffect(rankScrollId, rankScrollState) {
                    onDispose {
                        ListScrollStore.save(rankContext, rankScrollId, rankScrollState.firstVisibleItemIndex, rankScrollState.firstVisibleItemScrollOffset)
                    }
                }
                var rankSongs by remember(platform) { mutableStateOf(emptyList<Song>()) }
                var rankLoading by remember(platform) { mutableStateOf(true) }
                LaunchedEffect(platform, selectedBoard?.id) {
                    val board = selectedBoard
                    rankSongs = board?.songs.orEmpty()
                    rankLoading = board != null
                    if (board != null) {
                        rankSongs = loadSelectedRankDetail(
                            platform = platform,
                            board = board,
                            loadCached = { id, source ->
                                withContext(Dispatchers.IO) {
                                    HomeFeedCache.loadDetailSnapshot(rankContext, id, source)
                                }
                            },
                            fetchDetail = { id, source ->
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (source in MusicSourceRegistry.platformIds) {
                                            MusicSourceRegistry.platformSource(source).rank(id)
                                        } else {
                                            ApiClient.rankDetail(id, source)
                                        }
                                    }.getOrDefault(emptyList())
                                }
                            },
                            saveDetail = { id, source, songs ->
                                withContext(Dispatchers.IO) {
                                    runCatching { HomeFeedCache.saveDetail(rankContext, id, source, songs) }
                                }
                            },
                            publishCached = { rankSongs = it },
                        )
                    }
                    rankLoading = false
                }
                val uniqueRankSongs = remember(rankSongs) { rankSongs.distinctBy { it.key() } }
                var rankMenuSong by remember(platform, selectedBoard?.id) { mutableStateOf<Song?>(null) }
                var rankMulti by remember(platform, selectedBoard?.id) { mutableStateOf(false) }
                var rankSelected by remember(platform, selectedBoard?.id) { mutableStateOf(setOf<String>()) }
                // r658g（用户 10-20：榜单点多选后迷你条/底栏不隐藏，切走再回来才生效）：榜单页是 Pager 预组合的页面，
                // 闭包里的 currentSection 可能是预组合时的旧值（"hot"），导致 rankMultiVisible 首次为 false。
                // 改为在本作用域直接读 pagerState.currentPage（状态读 → 翻页即重组），并用 LaunchedEffect 按值同步。
                val rankPageCurrent = sections.getOrNull(pagerState.currentPage)?.first == "rank"
                val rankMultiVisible = rankMulti && visible && rankPageCurrent
                LaunchedEffect(rankMultiVisible) { onSelectionModeChange(rankMultiVisible) }
                SideEffect { if (rankMultiVisible) onSelectionModeChange(true) }
                DisposableEffect(Unit) { onDispose { onSelectionModeChange(false) } }
                androidx.activity.compose.BackHandler(enabled = rankMultiVisible) { rankMulti = false; rankSelected = emptySet() }
                val rankScope = rememberCoroutineScope()
                val rankCatalogState = rememberLazyListState()
                val rankPullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val rankRefreshing = isGestureRefreshing && feedLoading
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = rankRefreshing,
                    onRefresh = onRefreshHome,
                    modifier = Modifier.fillMaxSize(),
                    state = rankPullState,
                    indicator = {
                        HomePullRefreshIndicator(rankPullState, rankRefreshing, primary, Modifier.align(Alignment.TopCenter))
                    }
                ) {
                    LazyColumn(state = rankScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dockContentBottomPadding())) {
                        // 设计稿排行榜：榜单目录横滑 + 头卡（右下「切换榜单」打开底部选择）。
                        if (allBoards.size > 1) item(key = "rank_catalog_$platform") {
                            ChartCatalogStrip(
                                boards = allBoards,
                                selectedId = selectedBoard?.id,
                                state = rankCatalogState
                            ) { board ->
                                selectedRankId = board.id
                                HomePageStateStore.putString(homeContext, "rank:$platform", board.id)
                            }
                        }
                        item(key = "rank_title_$platform") {
                            if (selectedBoard != null) {
                                ChartHeroCard(
                                    board = selectedBoard, platformLabel = platformName(selectedBoard.source.ifBlank { platform }),
                                    // r658b 第 5 项（用户 10-19）：头卡去掉右侧「随机播放」，只留 播放全部 + 切换榜单。
                                    onPlayAll = { uniqueRankSongs.firstOrNull()?.let { onPlay(it, uniqueRankSongs) } }
                                ) { boardPickerOpen = true }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("榜单歌曲", color = onBg, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                    Box(Modifier.size(34.dp).clickable { boardPickerOpen = true }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "选择榜单", tint = onBg.copy(alpha = 0.62f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        if (selectedBoard == null) {
                            item(key = "rank_empty_period_$platform") {
                                StateBlock(DesignLineGlyphs.Disc, "当前平台暂无榜单")
                            }
                        } else if (uniqueRankSongs.isEmpty()) {
                            item(key = "rank_loading_$platform") {
                                val (loading, error, empty) = rankContent.toEmptySlot("暂无榜单歌曲")
                                FeedEmptySlot(rankLoading || loading, error, empty, onRetryFeed)
                            }
                        } else {
                            item(key = "rank_list_header_$platform") {
                                val allSel = rankSelected.size == uniqueRankSongs.size
                                ChartListHeader(
                                    multiMode = rankMulti, selectedCount = rankSelected.size, allSelected = allSel,
                                    onEnterSelect = { rankMenuSong = null; rankMulti = true; rankSelected = emptySet() },
                                    onToggleAll = { rankSelected = if (allSel) emptySet() else uniqueRankSongs.map { it.key() }.toSet() },
                                    onDone = { rankMulti = false; rankSelected = emptySet() }
                                )
                            }
                            itemsIndexed(uniqueRankSongs, key = { _, song -> song.key() }) { index, song ->
                                val sel = song.key() in rankSelected
                                ChartRankRow(
                                    song = song,
                                    rank = index + 1,
                                    nowPlaying = song.key() == nowPlayingKey,
                                    multiMode = rankMulti,
                                    selected = sel,
                                    onMore = { rankMenuSong = song }
                                ) {
                                    if (rankMulti) rankSelected = if (sel) rankSelected - song.key() else rankSelected + song.key()
                                    else onPlay(song, uniqueRankSongs)
                                }
                            }
                        }
                    }
                    if (rankMulti) {
                        val picked = uniqueRankSongs.filter { it.key() in rankSelected }
                        val finish = { rankMulti = false; rankSelected = emptySet() }
                        DetailSelectionActionBar(
                            enabled = picked.isNotEmpty(), showDownload = true, showRemove = false,
                            onAddTo = { onAddBatch(picked); finish() },
                            onQueue = { picked.asReversed().forEach(onAddNext); finish() },
                            onDownload = { onDownloadBatch(picked); finish() },
                            onRemove = {},
                            modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f),
                            // r658d（用户 10-20）：多选时迷你条与底栏已隐藏（onSelectionModeChange），操作条回到底部、占底栏位置。
                        )
                    } else if (rankScrollState.rememberScrolledPastTop()) {
                        HomeScrollTopButton(
                            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockContentBottomPadding() + 12.dp)
                        ) { rankScope.launch { rankScrollState.smoothScrollToTop() } }
                    }
                }
                if (!rankMulti) rankMenuSong?.let { song ->
                    SongActionSheet(
                        song = song, liked = isLiked(song), showDownload = true,
                        canDelete = false, canRescan = false,
                        onDismiss = { rankMenuSong = null },
                        onLike = { onLike(song) },
                        onAdd = { onAdd(song) },
                        onAddNext = { onAddNext(song) },
                        onDownload = { onDownload(song) },
                        onDelete = {}, onRescan = {}
                    )
                }
                if (boardPickerOpen) {
                    // 1.0.655：榜单切换抽屉改为统一设计抽屉；一行 3 个（封面 + 下方榜名），去掉「每天更新 · N 首」。
                    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = { boardPickerOpen = false }) {
                        // ACCEPT4（审计 §9 C 类）：选榜先落状态再走退场句柄，boardPickerOpen=false 归 onDismiss。
                        val dismissBoard = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { boardPickerOpen = false }
                        xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("${platformName(platform)} · Charts", "选择榜单")
                        // r673：网格顶部 contentPadding 由 2dp 增至 10dp（给首行封面阴影留空间），这里同步减 8dp，网格位置不变。
                        Spacer(Modifier.height(10.dp))
                        if (allBoards.isEmpty()) {
                            Text("当前平台暂无榜单", color = onBg.copy(alpha = 0.58f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 26.dp))
                        } else {
                            ChartPickerGrid(allBoards, selectedBoard?.id) { board ->
                                selectedRankId = board.id
                                HomePageStateStore.putString(homeContext, "rank:$platform", board.id)
                                dismissBoard()
                            }
                        }
                    }
                }
            }
            else -> {
                val label = platforms.firstOrNull { it.first == platform }?.second ?: "歌单"
                // 各平台分类都按当前 platform 使用现有缓存/API 分类状态，不复用其它平台分类或造数据。
                // 平台特化，勿改：分类体系为 wy 独有产品数据，非能力判断，不迁 capabilitiesOf。
                // 分类数据源为按 platform 缓存/API 加载的 /music/plcats 真实两级分类。
                val categoryController = remember(homeContext) { PlaylistCategoryDataController.forContext(homeContext) }
                val cachedCategoryGroups = remember(platform) { categoryController.loadCachedGroups(platform) }
                val serverGroups = remember(platform) { mutableStateOf(cachedCategoryGroups?.groups) }
                LaunchedEffect(platform) {
                    serverGroups.value = categoryController.loadCategoryGroups(platform)?.groups
                }
                val cg = serverGroups.value
                val catGroups = if (cg != null && cg.groups.isNotEmpty()) {
                    buildMap {
                        put("全部", emptyList())
                        for ((g, items) in cg.groups) if (items.isNotEmpty()) put(g, items)
                    }
                } else mapOf("全部" to emptyList())
                val browseGroups = (listOf("全部") + catGroups.keys).distinct()
                val allSubCats = catGroups.filterKeys { it != "全部" }.values.flatten().distinct()
                var group by remember(platform, browseGroups) {
                    val saved = HomePageStateStore.getString(homeContext, "playlist-group:$platform", "全部")
                    mutableStateOf(saved.takeIf { it in browseGroups } ?: "全部")
                }
                val subCats = if (group == "全部") allSubCats else catGroups[group].orEmpty()
                val validCategories = (subCats + "全部").toSet()
                var cat by remember(platform, group, subCats) {
                    val saved = HomePageStateStore.getString(homeContext, "playlist-category:$platform", "全部")
                    mutableStateOf(saved.takeIf { it in validCategories } ?: "全部")
                }
                val showAllCategory = cat == "全部" && platform !in setOf("wy", "tx", "kw", "mg")
                val playlistScrollId = "home_playlist_${platform}_${group}_${cat}"
                val playlistScrollState = rememberLazyListState(
                    ListScrollStore.index(homeContext, playlistScrollId),
                    ListScrollStore.offset(homeContext, playlistScrollId)
                )
                val playlistScope = rememberCoroutineScope()
                val pageIdentity = "$platform|$group|$cat"
                val activePageIdentity by rememberUpdatedState(pageIdentity)
                DisposableEffect(playlistScrollId, playlistScrollState) {
                    onDispose {
                        ListScrollStore.save(homeContext, playlistScrollId, playlistScrollState.firstVisibleItemIndex, playlistScrollState.firstVisibleItemScrollOffset)
                    }
                }
                val groupScrollId = "home_playlist_groups_$platform"
                val groupScrollState = rememberLazyListState(
                    ListScrollStore.index(homeContext, groupScrollId),
                    ListScrollStore.offset(homeContext, groupScrollId)
                )
                DisposableEffect(groupScrollId, groupScrollState) {
                    onDispose {
                        ListScrollStore.save(homeContext, groupScrollId, groupScrollState.firstVisibleItemIndex, groupScrollState.firstVisibleItemScrollOffset)
                    }
                }
                val subCatScrollId = "home_playlist_subcats_${platform}_$group"
                val subCatScrollState = rememberLazyListState(
                    ListScrollStore.index(homeContext, subCatScrollId),
                    ListScrollStore.offset(homeContext, subCatScrollId)
                )
                DisposableEffect(subCatScrollId, subCatScrollState) {
                    onDispose {
                        ListScrollStore.save(homeContext, subCatScrollId, subCatScrollState.firstVisibleItemIndex, subCatScrollState.firstVisibleItemScrollOffset)
                    }
                }
                var catPls by remember(platform, group, cat) {
                    mutableStateOf(if (showAllCategory) null else categoryController.loadCachedPlaylists(platform, group, cat)?.playlists)
                }
                var catLoading by remember(platform, group, cat) { mutableStateOf(false) }
                var catPage by remember(platform, group, cat) { mutableStateOf(1) }
                var catHasMore by remember(platform, group, cat) { mutableStateOf(false) }
                var catLoadingMore by remember(platform, group, cat) { mutableStateOf(false) }
                var catLoadFailed by remember(platform, group, cat) { mutableStateOf(false) }
                var catRetryNonce by remember(platform, group, cat) { mutableStateOf(0) }
                LaunchedEffect(platform, group, cat, allPls, catRetryNonce) {
                    if (showAllCategory) {
                        catPls = allPls
                        catLoading = false
                        catLoadFailed = false
                        catPage = 1
                        catHasMore = false
                        catLoadingMore = false
                    } else if (cat.isBlank() || (cat == "全部" && platform !in setOf("wy", "tx", "kw", "mg"))) {
                        catPls = allPls
                        catLoading = false
                        catLoadFailed = false
                        catPage = 1
                        catHasMore = false
                        catLoadingMore = false
                    } else {
                        catPage = 1
                        catHasMore = false
                        catLoadingMore = false
                        catLoadFailed = false
                        val cached = categoryController.loadCachedPlaylists(platform, group, cat)
                        if (cached != null) catPls = cached.playlists
                        catHasMore = cached?.hasMore ?: (cached?.playlists?.isNotEmpty() == true)
                        if (categoryController.isCachedPlaylistSnapshotFresh(cached)) {
                            catLoading = false
                            return@LaunchedEffect
                        }
                        catLoading = true
                        try {
                            val refresh = categoryController.refreshCategoryPlaylists(
                                platform = platform,
                                group = group,
                                category = cat,
                                titleMatches = allPls.filter {
                                    it.name.contains(cat, ignoreCase = true) || it.author.contains(cat, ignoreCase = true)
                                }
                            )
                            catPls = refresh.playlists
                            catLoadFailed = refresh.failed
                            catHasMore = refresh.hasMore
                        } finally {
                            catLoading = false
                        }
                    }
                }
                fun loadMoreCategoryPlaylists() {
                    if (showAllCategory || catLoading || catLoadingMore || !catHasMore) return
                    catLoadingMore = true
                    val requestedIdentity = pageIdentity
                    playlistScope.launch {
                        try {
                            val completed = categoryController.loadMoreCategoryPlaylists(
                                current = CategoryPlaylistPageState(
                                    page = catPage,
                                    playlists = catPls.orEmpty(),
                                    hasMore = catHasMore,
                                    loadingMore = catLoadingMore
                                ),
                                platform = platform,
                                category = cat,
                                group = group
                            )
                            if (activePageIdentity != requestedIdentity) return@launch
                            catPls = completed.playlists
                            catPage = completed.page
                            catHasMore = completed.hasMore
                            catLoadingMore = completed.loadingMore
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // 控制器保留当前分类页并释放锁；异常兜底与显式失败使用相同重试页。
                            catLoadingMore = false
                        } finally {
                            catLoadingMore = false
                        }
                    }
                }
                val displayPls = catPls ?: allPls
                val featuredPlaylists = remember(displayPls) { PlaylistPlazaSelection.featured(displayPls) }
                val visiblePlaylists = remember(displayPls, featuredPlaylists) { PlaylistPlazaSelection.remainder(displayPls, featuredPlaylists) }
                // 热门页同级：新歌速递取自榜单名含新歌；推荐横滑
                val newBoards = pranks.filter { it.name.contains("新歌") || it.name.contains("新碟") }
                val playlistPullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                val playlistRefreshing = isGestureRefreshing && feedLoading
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = playlistRefreshing,
                    onRefresh = onRefreshHome,
                    modifier = Modifier.fillMaxSize(),
                    state = playlistPullState,
                    indicator = {
                        HomePullRefreshIndicator(playlistPullState, playlistRefreshing, primary, Modifier.align(Alignment.TopCenter))
                    }
                ) {
                    LazyColumn(state = playlistScrollState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = dockContentBottomPadding())) {
                        if (featuredPlaylists.isNotEmpty()) {
                            item(key = "plaza_hero_$platform") {
                                // 头图默认轮换 3 个推荐歌单；左滑切到下一个、右滑回上一个。「为你推荐」模块已移除。
                                PlaylistPlazaHero(
                                    playlists = featuredPlaylists,
                                    label = label
                                ) { featured -> onOpenPl(featured.copy(remoteId = featured.remoteId.ifBlank { featured.id }, author = featured.author.ifEmpty { label }, description = featured.description)) }
                            }
                        }
                        item(key = "playlist_groups_$platform") {
                            LazyRow(
                                state = groupScrollState,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(browseGroups, contentType = { "playlist_group_chip" }) { g ->
                                    val on = group == g
                                    Box(
                                        Modifier.height(32.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(999.dp))
                                            .background(if (on) onBg else MaterialTheme.colorScheme.surface)
                                            .border(1.dp, if (on) onBg else MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                                            .semantics { selected = on; role = Role.Tab }
                                            .clickable {
                                                group = g
                                                cat = if (g == "全部") "全部" else catGroups[g].orEmpty().firstOrNull() ?: "全部"
                                                HomePageStateStore.putString(homeContext, "playlist-group:$platform", g)
                                                HomePageStateStore.putString(homeContext, "playlist-category:$platform", cat)
                                            }
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(g, color = if (on) MaterialTheme.colorScheme.background else onBg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1)
                                    }
                                }
                            }
                        }
                        if (subCats.isNotEmpty()) {
                            item(key = "playlist_subcats_$platform") {
                                LazyRow(
                                    state = subCatScrollState,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(subCats, contentType = { "playlist_subcat_chip" }) { c ->
                                        val on = cat == c
                                        Box(
                                            Modifier.height(32.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(999.dp))
                                                .background(if (on) onBg else MaterialTheme.colorScheme.surface)
                                                .border(1.dp, if (on) onBg else MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
                                            .semantics { selected = on; role = Role.Tab }
                                                .clickable {
                                                    cat = c
                                                    HomePageStateStore.putString(homeContext, "playlist-category:$platform", c)
                                                }
                                                .padding(horizontal = 14.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(c, color = if (on) MaterialTheme.colorScheme.background else onBg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    // r657（v3 #7）：「探索歌单」标题行删除，分类 Tab 下直接是歌单宽卡 + 宫格。
                    item(key = "popular_playlists_gap") { Spacer(Modifier.height(14.dp)) }
                    if (catLoading && displayPls.isEmpty()) {
                        item { StateLoadingBlock("加载分类歌单…") }
                    } else if (displayPls.isEmpty()) {
                        val (ld, er, et) = plContent.toEmptySlot("暂无歌单")
                        item { FeedEmptySlot(ld, er, et, onRetryFeed) }
                    } else {
                        homePlaylistGridRows(
                            visiblePlaylists = visiblePlaylists,
                            platform = platform,
                            label = label,
                            primary = primary,
                            onBg = onBg,
                            onOpenPlaylist = { pl ->
                                onOpenPl(pl.copy(remoteId = pl.remoteId.ifBlank { pl.id }, author = pl.author.ifEmpty { label }, description = pl.description))
                            },
                            // 666：右下播放按钮加载同平台歌单后走现有播放回调，不再导航详情。
                            onPlayPlaylist = playPlaylistDirectly
                        )
                    }
                    if (!showAllCategory && catLoadFailed) {
                        item(key = "category_retry_${platform}_${group}_${cat}") {
                            Text(
                                "分类加载失败，点击重试第 ${catPage} 页",
                                color = primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !catLoading) { catRetryNonce += 1 }
                                    .padding(horizontal = 14.dp, vertical = 18.dp)
                            )
                        }
                    }
                    if (!showAllCategory && catHasMore && displayPls.isNotEmpty()) {
                        item(key = "category_more_${platform}_${group}_${cat}") {
                            Text(
                                if (catLoadingMore) "正在加载更多歌单…" else "加载更多歌单 · 已显示 ${displayPls.size} 个",
                                color = if (catLoadingMore) onBg.copy(alpha = 0.55f) else primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !catLoadingMore && !catLoading) { loadMoreCategoryPlaylists() }
                                    .padding(horizontal = 14.dp, vertical = 18.dp)
                            )
                        }
                    }
                }
                if (playlistScrollState.rememberScrolledPastTop()) {
                    HomeScrollTopButton(
                        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockContentBottomPadding() + 12.dp)
                    ) { playlistScope.launch { playlistScrollState.smoothScrollToTop() } }
                }
                }
            }
          }
          }
        }
    }
}



@Composable
private fun RankSongLine(song: Song, index: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover = normalizeCoverUrl(song.cover)
    val coverModel = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
    val onBg = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(76.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(11.dp)).background(primary.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是 MusicNote 图标、加载时只剩 primary 8% 底）。
                DefaultCoverPlaceholder(Modifier.fillMaxSize())
                if (cover.isNotBlank()) AsyncImage(model = coverModel, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.name, color = onBg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.singer, color = onBg.copy(alpha = 0.58f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "%02d".format(index),
                color = if (index <= 3) primary else onBg.copy(alpha = 0.46f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp).wrapContentWidth(Alignment.End)
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 76.dp, end = 14.dp).height(0.7.dp).background(onBg.copy(alpha = 0.07f)))
    }
}

/**
 * 主页热歌/新歌速递行列表外壳：把 SongRow 自带 16dp 左内距（6 外 + 10 内）拉回 14dp，
 * 使封面左缘与区块标题（start=14dp）严格对齐；右侧保持原 28dp 距边。
 */
@Composable
internal fun HomeSongListShell(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // Compose 禁止负 padding；视觉左移只能用 offset，不能让热歌列表在数据到达时崩溃。
            .offset(x = (-2).dp)
            .padding(vertical = 4.dp)
    ) { content() }
}

/**
 * 心动模式推荐种子池：
 * 1) 有「我的喜欢」→ 以我的喜欢为种子；
 * 2) 否则用播放历史，按听歌统计（name+singer 命中次序）优先排序；
 * 3) 首次打开、两者皆空 → 从当前平台可用歌曲中随机挑选。
 */
internal fun heartModeSeedPool(
    liked: List<Song>,
    history: List<Song>,
    statOrder: List<Pair<String, String>>,
    fallback: List<Song>,
    randomCount: Int = 20,
): List<Song> {
    // 历史/热榜不是用户明确喜欢；空喜欢保持空态，不偷换种子来源。
    return liked.filter { it.name.isNotBlank() && it.singer.isNotBlank() }
        .distinctBy(PersonalRecommendations::songIdentity)
}

internal const val KW_RANK_ID_SCHEMA_KEY = "rank_id_schema:kw"
internal const val KW_RANK_ID_SCHEMA_VERSION = "official-sourceid-v1"

/** Upgrade persisted selections written against the pre-correction Kuwo IDs exactly once. */
internal fun migrateLegacyKuwoRankId(id: String, schemaVersion: String = ""): String {
    if (schemaVersion == KW_RANK_ID_SCHEMA_VERSION) return id
    return when (id) {
        "kw_17" -> "kw_16" // Old label: 热歌榜; live official sourceid 16.
        "kw_16" -> "kw_17" // Old label: 新歌榜; live official sourceid 17.
        "kw_158" -> "kw_104" // Old label: 华语榜; 158 is actually 短视频酷我排行榜.
        "kw_12" -> "kw_22" // Old label: 欧美榜; 12 is actually 美国 The Billboard.
        "kw_22" -> "kw_64" // Old label: 影视金曲; current official sourceid 64.
        "kw_184" -> "kw_145" // Old label: 会员畅听榜; current official sourceid 145.
        "kw_145" -> "kw_16" // Old label: 网络热歌; nearest current official equivalent is 酷我热歌榜.
        else -> id
    }
}


/** r658b #19：原型平台选项顺序（网易云、QQ、酷狗、酷我、咪咕）。 */
internal val PlatformPickOrder = listOf("", "wy", "tx", "kg", "kw", "mg")

/** r675：首页数字专辑循环轮播（独立非 inline 宿主，保持 HomePage 方法体 / dex 门禁形状）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeDigitalAlbumCarousel(digitalCards: List<DiscoverCard>, platform: String, carouselActive: Boolean, onOpenDiscover: (DiscoverCard) -> Unit) {
    val homeContext = LocalContext.current
    // r675（用户 10-22：数字专辑移动时没动画、应自动滚动）：此前是设计稿 .rail 直译的自由滑动 LazyRow，
    // 既无吸附翻页动画，也未接 HomePagerAutoAdvance（只有新碟首发接了）。改为与新碟首发同款循环 HorizontalPager：
    // 自动轮播（按住 / 拖动 / 悬停 / 聚焦暂停，离开首页停），非当前页缩小淡出，减少动画时直接切页。
    val digitalShown = digitalCards.take(12)
    val digitalLoop = digitalShown.size > 1
    val digitalPageCount = if (digitalLoop) digitalShown.size * 1000 else digitalShown.size
    val digitalPager = rememberPagerState(initialPage = if (digitalLoop) digitalShown.size * 500 else 0, pageCount = { digitalPageCount })
    // E18（验收二轮）：自动滚动整段去除——不再调用 HomePagerAutoAdvance，停在用户停的位置；
    // 为自动推进暂停而存在的 held 追踪（homeCarouselTouch / hoverable / focusable）一并移除。
    // 手动吸附打磨：默认 fling 的 0.5 位置阈值对 80% 宽露边卡偏高（短滑回弹、长滑顿挫），
    // 改走 MotionTokens 时长/曲线 + 0.3 阈值；相邻页预组合 1 页，消除滑动中邻页现组合的顿挫。
    val digitalFling = PagerDefaults.flingBehavior(
        state = digitalPager,
        snapAnimationSpec = tween(durationMillis = MotionTokens.Duration.Medium, easing = MotionTokens.Easing.EmphasizedDecelerate),
        snapPositionalThreshold = 0.3f
    )
    val digitalWidth = homeDigitalAlbumCardWidth(LocalConfiguration.current.screenWidthDp)
    // 高度 = 卡 116 + 上下 2 + 顶 8 + 底 14（整卡外缘阴影留白，Pager 自身会裁切边界）。
    HorizontalPager(state = digitalPager, pageSize = PageSize.Fixed(digitalWidth), contentPadding = PaddingValues(start = 14.dp, end = 14.dp), pageSpacing = 14.dp, flingBehavior = digitalFling, beyondViewportPageCount = 1, key = { digitalShown[it % digitalShown.size].id + "#" + it }, modifier = Modifier.fillMaxWidth().height(142.dp)) { page ->
        val card = digitalShown[page % digitalShown.size]
            // 背景随封面取色（每卡独立，不串色）。
            val toneState = remember(card.id, card.cover) { mutableStateOf<Color?>(null) }
            LaunchedEffect(card.id, card.cover) { toneState.value = coverToneColor(homeContext, card.cover) }
            // 数字专辑列表接口不含曲目数：先读详情缓存，缺失时取一次专辑详情并写回缓存（点进去也秒开）。
            var digitalCount by remember(card.id) { mutableStateOf(card.count) }
            LaunchedEffect(card.id) {
                if (digitalCount > 0) return@LaunchedEffect
                val src = card.source.ifBlank { platform }
                digitalCount = withContext(Dispatchers.IO) {
                    HomeFeedCache.loadDetail(homeContext, card.id, src).size.takeIf { it > 0 } ?: runCatching {
                        val songs = MusicSourceRegistry.platformSource(src).albumDetail(card.id)?.songs.orEmpty()
                        if (songs.isNotEmpty()) HomeFeedCache.saveDetail(homeContext, card.id, src, songs)
                        songs.size
                    }.getOrDefault(0)
                }
            }
            Box(Modifier.padding(top = 8.dp, bottom = 14.dp).graphicsLayer {
                val off = kotlin.math.abs((digitalPager.currentPage - page) + digitalPager.currentPageOffsetFraction).coerceIn(0f, 1f)
                val sc = 1f - 0.06f * off
                scaleX = sc; scaleY = sc; alpha = 1f - 0.30f * off
            }) { HomeDigitalAlbumCard(card, toneState.value, digitalCount) { onOpenDiscover(card) } }
    }
}
