package xyz.cncnlp.soundwave.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.DetailCacheResolution
import xyz.cncnlp.soundwave.DetailCacheSnapshot
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.resolveDetailCache
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

internal data class HomeHotBoardDetailState(
    val songs: List<Song>,
    val loading: Boolean,
    val error: String = "",
)

/** Inputs that identify one hot-board result; changing platform or board resets Compose state. */
internal data class HomeHotBoardDetailKey(
    val platform: String,
    val boardId: String,
    val source: String,
    val boardSongs: List<Song>,
)

internal fun homeHotBoardDetailKey(platform: String, board: RankBoard?): HomeHotBoardDetailKey =
    HomeHotBoardDetailKey(
        platform = platform,
        boardId = board?.id.orEmpty(),
        source = board?.source?.ifBlank { platform }.orEmpty(),
        boardSongs = board?.songs.orEmpty(),
    )

internal fun initialHomeHotBoardDetailState(board: RankBoard?): HomeHotBoardDetailState =
    HomeHotBoardDetailState(
        songs = board?.songs.orEmpty(),
        loading = board != null && board.songs.isEmpty(),
    )

/** Cache-first display followed by a refresh, with injectable I/O for deterministic policy tests. */
internal suspend fun loadHomeHotBoardDetail(
    platform: String,
    board: RankBoard?,
    loadCached: suspend (id: String, source: String) -> DetailCacheSnapshot,
    fetchDetail: suspend (id: String, source: String) -> List<Song>,
    saveDetail: suspend (id: String, source: String, songs: List<Song>) -> Unit,
    publish: (HomeHotBoardDetailState) -> Unit,
    forceRefresh: Boolean = false,
    now: Long = System.currentTimeMillis(),
) {
    if (board == null || board.id.isBlank()) {
        publish(HomeHotBoardDetailState(emptyList(), loading = false))
        return
    }

    val source = board.source.ifBlank { platform }
    var visibleSongs = board.songs
    publish(HomeHotBoardDetailState(visibleSongs, loading = true))

    val cached = runCatching { loadCached(board.id, source) }
        .getOrDefault(DetailCacheSnapshot(emptyList(), 0L))
    val resolution = resolveDetailCache(
        cache = cached,
        ttlMs = HomeFeedCache.ResourceTtl.RANK_DETAIL_MS,
        now = now,
        forceRefresh = forceRefresh,
        publishCached = { songs ->
            visibleSongs = songs
            publish(HomeHotBoardDetailState(visibleSongs, loading = true))
        },
    ) { runCatching { fetchDetail(board.id, source) }.getOrDefault(emptyList()) }
    val detail = when (resolution) {
        is DetailCacheResolution.CacheHit -> emptyList()
        is DetailCacheResolution.Refreshed -> resolution.value
    }
    if (detail.isNotEmpty()) {
        visibleSongs = detail
        publish(HomeHotBoardDetailState(visibleSongs, loading = true))
        runCatching { saveDetail(board.id, source, detail) }
    }

    val error = if (detail.isEmpty() && cached.songs.isEmpty() && board.songs.isEmpty()) {
        "暂未获取到当前平台热歌榜歌曲"
    } else {
        ""
    }
    publish(HomeHotBoardDetailState(visibleSongs, loading = false, error = error))
}

@Composable
internal fun rememberHomeHotBoardDetail(
    platform: String,
    board: RankBoard?,
    retryTick: Int,
): HomeHotBoardDetailState {
    val context = LocalContext.current
    val requestKey = homeHotBoardDetailKey(platform, board)
    var state by remember(requestKey) { mutableStateOf(initialHomeHotBoardDetailState(board)) }
    var lastRetryTick by remember { mutableStateOf(retryTick) }

    LaunchedEffect(requestKey, retryTick) {
        val forceRefresh = retryTick != lastRetryTick
        lastRetryTick = retryTick
        loadHomeHotBoardDetail(
            platform = platform,
            board = board,
            loadCached = { id, source ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        HomeFeedCache.loadDetailSnapshot(context, id, source)
                    }.getOrDefault(DetailCacheSnapshot(emptyList(), 0L))
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
                    runCatching { HomeFeedCache.saveDetail(context, id, source, songs) }
                }
            },
            publish = { state = it },
            forceRefresh = forceRefresh,
        )
    }
    return state
}
