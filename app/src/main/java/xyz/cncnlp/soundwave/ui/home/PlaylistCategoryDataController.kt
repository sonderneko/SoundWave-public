package xyz.cncnlp.soundwave.ui.home

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.HomePageStateStore
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.CategoryPlaylistPageState
import xyz.cncnlp.soundwave.finishCategoryPlaylistPageLoad
import xyz.cncnlp.soundwave.resolveCategoryPlaylistRefresh
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.CatGroups
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.fetchPlaylistCategoryGroups

/** Persistence boundary kept injectable so category cache policy can be tested without Android storage. */
internal interface PlaylistCategoryDataCache {
    fun loadGroups(platform: String): HomePageStateStore.CategoryGroupsSnapshot?
    fun saveGroups(platform: String, groups: CatGroups?)
    fun loadPlaylists(key: String): HomeFeedCache.BrowsePlaylistSnapshot?
    fun savePlaylists(key: String, playlists: List<Playlist>, hasMore: Boolean?)
}

internal data class CategoryPlaylistRefreshState(
    val playlists: List<Playlist>,
    val hasMore: Boolean,
    val failed: Boolean,
    val fromFreshCache: Boolean
)

/** Coordinates playlist-category persistence and page requests; Compose state and presentation remain in HomePage. */
internal class PlaylistCategoryDataController(
    private val cache: PlaylistCategoryDataCache,
    private val fetchGroups: suspend (String) -> CatGroups?,
    private val fetchPage: suspend (String, String, Int, Int) -> PlaylistSearchOutcome,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetchSquarePage: (suspend (String, String, String, Int, Int) -> PlaylistSearchOutcome)? = null
) {
    companion object {
        internal const val GROUP_CACHE_TTL_MS = HomePageStateStore.CATEGORY_GROUP_CACHE_TTL_MS
        internal const val EMPTY_GROUP_CACHE_TTL_MS = HomePageStateStore.EMPTY_CATEGORY_GROUP_CACHE_TTL_MS
        private const val CATEGORY_PAGE_SIZE = 100

        internal fun categoryGroupCacheTtl(groups: CatGroups?): Long =
            HomePageStateStore.categoryGroupCacheTtl(groups)

        internal fun categoryCacheKey(platform: String, group: String, category: String): String =
            "category-v3|$platform|$group|$category"

        fun forContext(context: Context): PlaylistCategoryDataController {
            val appContext = context.applicationContext
            return PlaylistCategoryDataController(
                cache = object : PlaylistCategoryDataCache {
                    override fun loadGroups(platform: String) = HomePageStateStore.loadCategoryGroups(appContext, platform)
                    override fun saveGroups(platform: String, groups: CatGroups?) =
                        HomePageStateStore.saveCategoryGroups(appContext, platform, groups)
                    override fun loadPlaylists(key: String) = HomeFeedCache.loadBrowsePlaylists(appContext, key)
                    override fun savePlaylists(key: String, playlists: List<Playlist>, hasMore: Boolean?) =
                        HomeFeedCache.saveBrowsePlaylists(appContext, key, playlists, hasMore)
                },
                fetchGroups = ::fetchPlaylistCategoryGroups,
                fetchSquarePage = { platform, group, category, page, limit ->
                    xyz.cncnlp.soundwave.source.direct.fetchNativePlaylistSquare(platform, group, category, page)
                        ?: MusicSourceRegistry.platformSource(platform).searchPlaylistsPageOutcome(category, platform, page, limit)
                },
                fetchPage = { platform, category, page, limit ->
                    MusicSourceRegistry.platformSource(platform)
                        .searchPlaylistsPageOutcome(category, platform, page = page, limit = limit)
                }
            )
        }
    }

    fun loadCachedGroups(platform: String): HomePageStateStore.CategoryGroupsSnapshot? = cache.loadGroups(platform)

    fun loadCachedPlaylists(platform: String, group: String, category: String): HomeFeedCache.BrowsePlaylistSnapshot? =
        cache.loadPlaylists(categoryCacheKey(platform, group, category))

    fun isCachedPlaylistSnapshotFresh(snapshot: HomeFeedCache.BrowsePlaylistSnapshot?): Boolean =
        snapshot != null && !HomeFeedCache.browsePlaylistCacheNeedsRefresh(snapshot, now())

    suspend fun loadCategoryGroups(platform: String): HomePageStateStore.CategoryGroupsSnapshot? {
        val cached = withContext(ioDispatcher) { cache.loadGroups(platform) }
        val ttl = categoryGroupCacheTtl(cached?.groups)
        if (cached != null && HomeFeedCache.isTimestampFresh(cached.savedAt, ttl, now())) return cached

        // Match the previous category-group effect: a missing first snapshot is persisted as an empty result.
        val fetched = withContext(ioDispatcher) {
            try {
                fetchGroups(platform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        if (fetched != null) {
            withContext(ioDispatcher) { cache.saveGroups(platform, fetched) }
            return HomePageStateStore.CategoryGroupsSnapshot(fetched, now())
        }
        if (cached == null) withContext(ioDispatcher) { cache.saveGroups(platform, null) }
        return cached
    }

    suspend fun refreshCategoryPlaylists(
        platform: String,
        group: String,
        category: String,
        titleMatches: List<Playlist>
    ): CategoryPlaylistRefreshState {
        val key = categoryCacheKey(platform, group, category)
        val cached = withContext(ioDispatcher) { cache.loadPlaylists(key) }
        if (cached != null && isCachedPlaylistSnapshotFresh(cached)) {
            return CategoryPlaylistRefreshState(
                playlists = cached.playlists,
                hasMore = cached.hasMore ?: cached.playlists.isNotEmpty(),
                failed = false,
                fromFreshCache = true
            )
        }

        val outcome = try {
            withContext(ioDispatcher) { fetchSquarePage?.invoke(platform, group, category, 1, CATEGORY_PAGE_SIZE) ?: fetchPage(platform, category, 1, CATEGORY_PAGE_SIZE) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlaylistSearchOutcome.Failure
        }
        val refresh = resolveCategoryPlaylistRefresh(outcome, cached?.playlists, titleMatches, platform)
        val hasMore = when (outcome) {
            is PlaylistSearchOutcome.Success -> outcome.hasMore ?: (outcome.playlists.isNotEmpty() || cached?.playlists?.isNotEmpty() == true)
            PlaylistSearchOutcome.Failure -> cached?.hasMore ?: (cached?.playlists?.isNotEmpty() == true)
        }
        refresh.cacheSnapshot?.let { snapshot ->
            // This cache contains page 1 only. Never attach a later page's terminal flag to it.
            withContext(ioDispatcher) { cache.savePlaylists(key, snapshot, (outcome as? PlaylistSearchOutcome.Success)?.hasMore) }
        }
        return CategoryPlaylistRefreshState(refresh.displayedPlaylists, hasMore, refresh.failed, fromFreshCache = false)
    }

    suspend fun loadMoreCategoryPlaylists(
        current: CategoryPlaylistPageState,
        platform: String,
        category: String,
        group: String = "全部"
    ): CategoryPlaylistPageState {
        val nextPage = current.page + 1
        return try {
            val outcome = withContext(ioDispatcher) { fetchSquarePage?.invoke(platform, group, category, nextPage, CATEGORY_PAGE_SIZE) ?: fetchPage(platform, category, nextPage, CATEGORY_PAGE_SIZE) }
            val incoming = (outcome as? PlaylistSearchOutcome.Success)?.playlists
            finishCategoryPlaylistPageLoad(current, incoming, platform, nextPage).let { completed ->
                if (outcome is PlaylistSearchOutcome.Success && outcome.hasMore != null)
                    completed.copy(page = nextPage, hasMore = outcome.hasMore) else completed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finishCategoryPlaylistPageLoad(current, null, platform, nextPage)
        }
    }
}
