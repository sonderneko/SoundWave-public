package xyz.cncnlp.soundwave.ui.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.CategoryPlaylistPageState
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.HomePageStateStore
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.source.CatGroups
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome

class PlaylistCategoryDataControllerTest {
    private class MemoryCache(var now: Long = NOW) : PlaylistCategoryDataCache {
        val groups = mutableMapOf<String, HomePageStateStore.CategoryGroupsSnapshot>()
        val playlists = mutableMapOf<String, HomeFeedCache.BrowsePlaylistSnapshot>()
        var groupWrites = 0
        var playlistWrites = 0

        override fun loadGroups(platform: String) = groups[platform]

        override fun saveGroups(platform: String, groups: CatGroups?) {
            groupWrites++
            this.groups[platform] = HomePageStateStore.CategoryGroupsSnapshot(groups, now)
        }

        override fun loadPlaylists(key: String) = playlists[key]

        override fun savePlaylists(key: String, playlists: List<Playlist>, hasMore: Boolean?) {
            playlistWrites++
            this.playlists[key] = HomeFeedCache.BrowsePlaylistSnapshot(playlists, now, hasMore)
        }
    }

    private fun controller(
        cache: MemoryCache,
        fetchGroups: suspend (String) -> CatGroups? = { null },
        fetchPage: suspend (String, String, Int, Int) -> PlaylistSearchOutcome = { _, _, _, _ -> PlaylistSearchOutcome.Failure }
    ) = PlaylistCategoryDataController(
        cache = cache,
        fetchGroups = fetchGroups,
        fetchPage = fetchPage,
        ioDispatcher = Dispatchers.Unconfined,
        now = { cache.now }
    )

    private fun playlist(id: String, name: String, source: String = "wy") =
        Playlist(id = id, remoteId = id, source = source, name = name, author = "author-$id")

    @Test
    fun categoryGroupsUseSixHourAndTenMinuteTtlsAndKeepExpiredSnapshotOnFailure() = runBlocking {
        val cache = MemoryCache()
        val groups = CatGroups(listOf("风格" to listOf("流行")))
        cache.groups["wy"] = HomePageStateStore.CategoryGroupsSnapshot(
            groups,
            NOW - PlaylistCategoryDataController.GROUP_CACHE_TTL_MS
        )
        cache.groups["kg"] = HomePageStateStore.CategoryGroupsSnapshot(
            null,
            NOW - PlaylistCategoryDataController.EMPTY_GROUP_CACHE_TTL_MS
        )
        var requests = 0
        val controller = controller(cache, fetchGroups = { requests++; null })

        assertEquals(groups, controller.loadCategoryGroups("wy")?.groups)
        assertNull(controller.loadCategoryGroups("kg")?.groups)
        assertEquals(0, requests)

        cache.now++
        val expiredNonEmpty = controller.loadCategoryGroups("wy")
        val expiredEmpty = controller.loadCategoryGroups("kg")
        assertEquals(groups, expiredNonEmpty?.groups)
        assertEquals(NOW - PlaylistCategoryDataController.GROUP_CACHE_TTL_MS, expiredNonEmpty?.savedAt)
        assertNull(expiredEmpty?.groups)
        assertEquals(NOW - PlaylistCategoryDataController.EMPTY_GROUP_CACHE_TTL_MS, expiredEmpty?.savedAt)
        assertEquals(2, requests)
        assertEquals(0, cache.groupWrites)
    }

    @Test
    fun firstCategoryGroupFailurePersistsEmptySnapshotAndAvoidsImmediateRetry() = runBlocking {
        val cache = MemoryCache()
        var requests = 0
        val controller = controller(cache, fetchGroups = { requests++; null })

        assertNull(controller.loadCategoryGroups("wy"))
        assertNull(cache.groups["wy"]?.groups)
        assertEquals(NOW, cache.groups["wy"]?.savedAt)
        assertNull(controller.loadCategoryGroups("wy")?.groups)
        assertEquals(1, requests)
        assertEquals(1, cache.groupWrites)
    }

    @Test
    fun successfulEmptyCategoryResultIsNegativeCachedAndKeysStayIsolated() = runBlocking {
        val cache = MemoryCache()
        val titleFallback = listOf(playlist("title", "流行推荐"))
        var requests = 0
        val controller = controller(cache, fetchPage = { _, _, _, _ ->
            requests++
            PlaylistSearchOutcome.Success(emptyList())
        })

        val first = controller.refreshCategoryPlaylists("wy", "风格", "流行", titleFallback)
        assertEquals(titleFallback, first.playlists)
        assertFalse(first.failed)
        assertFalse(first.hasMore)
        assertEquals(1, cache.playlistWrites)

        val sameKey = controller.refreshCategoryPlaylists("wy", "风格", "流行", titleFallback)
        assertTrue(sameKey.fromFreshCache)
        assertTrue(sameKey.playlists.isEmpty())
        assertEquals(1, requests)

        controller.refreshCategoryPlaylists("wy", "主题", "流行", titleFallback)
        controller.refreshCategoryPlaylists("kg", "风格", "流行", titleFallback)
        assertEquals("category-v3|wy|风格|流行", PlaylistCategoryDataController.categoryCacheKey("wy", "风格", "流行"))
        assertEquals(3, cache.playlists.size)
        assertEquals(3, requests)
    }

    @Test
    fun firstPageFailureFallsBackToCachedResultsThenMatchingHomeTitlesWithoutCachingFailure() = runBlocking {
        val cache = MemoryCache()
        val cached = listOf(playlist("cached", "旧分类歌单"))
        val staleKey = PlaylistCategoryDataController.categoryCacheKey("wy", "风格", "流行")
        cache.playlists[staleKey] = HomeFeedCache.BrowsePlaylistSnapshot(
            cached,
            NOW - HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS - 1L
        )
        val titleMatches = listOf(playlist("title", "流行标题匹配"))
        val controller = controller(cache)

        val cachedFallback = controller.refreshCategoryPlaylists("wy", "风格", "流行", titleMatches)
        assertEquals(cached, cachedFallback.playlists)
        assertTrue(cachedFallback.failed)
        assertTrue(cachedFallback.hasMore)
        assertEquals(0, cache.playlistWrites)

        val titleOnlyFallback = controller.refreshCategoryPlaylists("wy", "风格", "摇滚", titleMatches)
        assertEquals(titleMatches, titleOnlyFallback.playlists)
        assertTrue(titleOnlyFallback.failed)
        assertFalse(titleOnlyFallback.hasMore)
        assertEquals(0, cache.playlistWrites)
    }

    @Test
    fun emptyAndFailedPagesRetrySamePageWhileDuplicateNonEmptyPageAdvances() = runBlocking {
        val cache = MemoryCache()
        val existing = playlist("same", "保留原歌单")
        val outcomes = listOf(
            PlaylistSearchOutcome.Success(emptyList()),
            PlaylistSearchOutcome.Failure,
            PlaylistSearchOutcome.Success(listOf(existing.copy(name = "重复条目")))
        )
        val requestedPages = mutableListOf<Int>()
        var responseIndex = 0
        val controller = controller(cache, fetchPage = { _, _, page, _ ->
            requestedPages += page
            outcomes[responseIndex++]
        })
        val loading = CategoryPlaylistPageState(2, listOf(existing), hasMore = true, loadingMore = true)

        val emptyPage = controller.loadMoreCategoryPlaylists(loading, "wy", "流行")
        assertEquals(2, emptyPage.page)
        assertEquals(listOf(existing), emptyPage.playlists)
        assertTrue(emptyPage.hasMore)
        assertFalse(emptyPage.loadingMore)

        val failedPage = controller.loadMoreCategoryPlaylists(emptyPage.copy(loadingMore = true), "wy", "流行")
        assertEquals(2, failedPage.page)
        assertEquals(listOf(existing), failedPage.playlists)
        assertTrue(failedPage.hasMore)
        assertFalse(failedPage.loadingMore)

        val duplicatePage = controller.loadMoreCategoryPlaylists(failedPage.copy(loadingMore = true), "wy", "流行")
        assertEquals(3, duplicatePage.page)
        assertEquals(listOf(existing), duplicatePage.playlists)
        assertTrue(duplicatePage.hasMore)
        assertFalse(duplicatePage.loadingMore)
        assertEquals(listOf(3, 3, 3), requestedPages)
    }

    @Test
    fun firstPageContinuationSurvivesCacheReentryForEveryPlatform() = runBlocking {
        for (platform in listOf("wy", "tx", "kg", "kw", "mg")) {
            for (more in listOf(true, false)) {
                val cache = MemoryCache()
                val items = listOf(playlist("one", "单页也可能有后续", platform))
                var requests = 0
                val controller = controller(cache, fetchPage = { _, _, _, _ ->
                    requests++
                    PlaylistSearchOutcome.Success(items, more)
                })
                val first = controller.refreshCategoryPlaylists(platform, "风格", "流行", emptyList())
                val key = PlaylistCategoryDataController.categoryCacheKey(platform, "风格", "流行")
                assertEquals(more, first.hasMore)
                assertEquals(more, cache.playlists[key]?.hasMore)
                val reentered = controller.refreshCategoryPlaylists(platform, "风格", "流行", emptyList())
                assertTrue(reentered.fromFreshCache)
                assertEquals(items, reentered.playlists)
                assertEquals(more, reentered.hasMore)
                assertEquals(1, requests)
            }
        }
    }

    @Test
    fun unknownContinuationAndLegacySnapshotsStayConservative() = runBlocking {
        val cache = MemoryCache()
        val items = listOf(playlist("old", "旧歌单"))
        val key = PlaylistCategoryDataController.categoryCacheKey("wy", "风格", "流行")
        cache.playlists[key] = HomeFeedCache.BrowsePlaylistSnapshot(items, NOW)
        assertTrue(controller(cache).refreshCategoryPlaylists("wy", "风格", "流行", emptyList()).hasMore)

        cache.playlists.clear()
        val controller = controller(cache, fetchPage = { _, _, _, _ -> PlaylistSearchOutcome.Success(items) })
        assertTrue(controller.refreshCategoryPlaylists("wy", "风格", "流行", emptyList()).hasMore)
        assertTrue(controller.refreshCategoryPlaylists("wy", "风格", "流行", emptyList()).hasMore)
    }

    @Test
    fun failedRefreshPreservesKnownTerminalCacheWithoutOverwritingIt() = runBlocking {
        val cache = MemoryCache()
        val key = PlaylistCategoryDataController.categoryCacheKey("kw", "风格", "流行")
        val stale = HomeFeedCache.BrowsePlaylistSnapshot(
            listOf(playlist("last", "已结束", "kw")),
            NOW - HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS - 1L,
            hasMore = false
        )
        cache.playlists[key] = stale
        val result = controller(cache).refreshCategoryPlaylists("kw", "风格", "流行", emptyList())
        assertTrue(result.failed)
        assertFalse(result.hasMore)
        assertEquals(stale.playlists, result.playlists)
        assertEquals(stale, cache.playlists[key])
        assertEquals(0, cache.playlistWrites)
    }

    @Test
    fun laterTerminalPageDoesNotMarkTheFirstPageCacheAsExhausted() = runBlocking {
        val cache = MemoryCache()
        val firstItems = listOf(playlist("first", "首屏", "tx"))
        val lastItems = listOf(playlist("last", "末屏", "tx"))
        val requests = mutableListOf<Int>()
        val controller = PlaylistCategoryDataController(
            cache = cache,
            fetchGroups = { null },
            fetchPage = { _, _, _, _ -> error("Native square must take precedence") },
            ioDispatcher = Dispatchers.Unconfined,
            now = { cache.now },
            fetchSquarePage = { platform, group, category, page, _ ->
                assertEquals("tx", platform)
                assertEquals("主题", group)
                assertEquals("经典", category)
                requests += page
                PlaylistSearchOutcome.Success(if (page == 1) firstItems else lastItems, hasMore = page == 1)
            }
        )
        val first = controller.refreshCategoryPlaylists("tx", "主题", "经典", emptyList())
        val last = controller.loadMoreCategoryPlaylists(
            CategoryPlaylistPageState(1, first.playlists, first.hasMore, loadingMore = true),
            "tx", "经典", "主题"
        )
        assertEquals(2, last.page)
        assertFalse(last.hasMore)
        assertFalse(last.loadingMore)
        assertEquals(firstItems + lastItems, last.playlists)
        val reentered = controller.refreshCategoryPlaylists("tx", "主题", "经典", emptyList())
        assertEquals(firstItems, reentered.playlists)
        assertTrue(reentered.hasMore)
        assertEquals(listOf(1, 2), requests)
        assertEquals(1, cache.playlistWrites)
    }

    @Test
    fun groupCancellationIsPropagatedWithoutCachingWhilePageCancellationIsPropagated() {
        val groupCache = MemoryCache()
        val cancelled = CancellationException("home page was hidden")
        val groupController = controller(groupCache, fetchGroups = { throw cancelled })
        val groupFailure = assertThrows(CancellationException::class.java) {
            runBlocking { groupController.loadCategoryGroups("wy") }
        }
        assertSame(cancelled, groupFailure)
        assertNull(groupCache.groups["wy"]?.groups)
        assertEquals(0, groupCache.groupWrites)

        val pageController = controller(groupCache, fetchPage = { _, _, _, _ -> throw CancellationException("cancel page") })
        assertThrows(CancellationException::class.java) {
            runBlocking { pageController.refreshCategoryPlaylists("wy", "风格", "流行", emptyList()) }
        }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                pageController.loadMoreCategoryPlaylists(
                    CategoryPlaylistPageState(1, emptyList(), hasMore = true, loadingMore = true),
                    "wy",
                    "流行"
                )
            }
        }
    }

    @Test
    fun everyPlatformRetriesFailedPageAndCanPassDuplicatesAndEmptyContinuingPages() = runBlocking {
        // Injected state-machine records only; not provider catalog evidence or app fallback data.
        for (platform in listOf("wy", "tx", "kg", "kw", "mg")) {
            val cache = MemoryCache()
            val firstItems = listOf(playlist("test-first", "测试首屏", platform))
            val lastItems = listOf(playlist("test-last", "测试末屏", platform))
            val requested = mutableListOf<Int>()
            var secondAttempts = 0
            val controller = PlaylistCategoryDataController(
                cache = cache,
                fetchGroups = { null },
                fetchPage = { _, _, _, _ -> error("Native square must take precedence") },
                ioDispatcher = Dispatchers.Unconfined,
                now = { cache.now },
                fetchSquarePage = { actualPlatform, group, category, page, _ ->
                    assertEquals(platform, actualPlatform)
                    assertEquals("测试组", group)
                    assertEquals("测试分类", category)
                    requested += page
                    when (page) {
                        1 -> PlaylistSearchOutcome.Success(firstItems, true)
                        2 -> if (secondAttempts++ == 0) PlaylistSearchOutcome.Failure
                             else PlaylistSearchOutcome.Success(firstItems, true)
                        3 -> PlaylistSearchOutcome.Success(emptyList(), true)
                        4 -> PlaylistSearchOutcome.Success(lastItems, false)
                        else -> error("Unexpected request")
                    }
                }
            )
            val first = controller.refreshCategoryPlaylists(platform, "测试组", "测试分类", emptyList())
            var state = CategoryPlaylistPageState(1, first.playlists, first.hasMore, true)
            suspend fun more() {
                state = controller.loadMoreCategoryPlaylists(state.copy(loadingMore = true), platform, "测试分类", "测试组")
                assertFalse(state.loadingMore)
            }
            more()
            assertEquals(1, state.page)
            assertEquals(firstItems, state.playlists)
            assertTrue(state.hasMore)
            more()
            assertEquals(2, state.page)
            assertEquals(firstItems, state.playlists)
            assertTrue(state.hasMore)
            more()
            assertEquals(3, state.page)
            assertEquals(firstItems, state.playlists)
            assertTrue(state.hasMore)
            more()
            assertEquals(4, state.page)
            assertEquals(firstItems + lastItems, state.playlists)
            assertFalse(state.hasMore)
            val reentered = controller.refreshCategoryPlaylists(platform, "测试组", "测试分类", emptyList())
            assertEquals(firstItems, reentered.playlists)
            assertTrue(reentered.hasMore)
            assertTrue(reentered.fromFreshCache)
            assertEquals(listOf(1, 2, 2, 3, 4), requested)
            assertEquals(1, cache.playlistWrites)
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
