package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.captureListRequestOutcome
import xyz.cncnlp.soundwave.source.classifyListHttpResponse
import xyz.cncnlp.soundwave.source.decodeListResponseOutcome
import xyz.cncnlp.soundwave.source.legacyListValue
import xyz.cncnlp.soundwave.source.chooseCategoryPlaylistSearchResult
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome

class PlaylistFeedRepositoryTest {
    private fun playlist(id: String, title: String, plays: Long) =
        Playlist(id = id, remoteId = id, source = "wy", name = title, author = "author-$id", playCount = plays)

    @Test
    fun categoryGroupsUseRegistryOnlyForExactCanonicalSourceIds() {
        val canonicalIds = listOf("wy", "kg", "kw", "tx", "mg")
        canonicalIds.forEach { source ->
            assertEquals(source, playlistCategoryGroupsRegistrySourceOrNull(source))
        }

        listOf("", " ", " WY ", "WY", "QQ", "netease", "script", "cloud").forEach { source ->
            assertNull(playlistCategoryGroupsRegistrySourceOrNull(source))
        }
    }

    @Test
    fun categoryQueriesRoundRobinAcrossProviderGroups() {
        val queries = PlaylistCategoryCatalog.queryTerms(
            source = "tx",
            groups = listOf(
                "风格" to listOf("流行", "摇滚", "民谣"),
                "主题" to listOf("综艺", "影视原声", "校园")
            )
        )
        assertEquals(listOf("流行", "综艺", "摇滚", "影视原声", "民谣", "校园"), queries)
    }

    @Test
    fun categoryWindowRotatesAcrossRealCatalogAndMissingCatalogDoesNotInventTerms() {
        val groups = listOf("风格" to listOf("流行", "摇滚", "民谣"), "主题" to listOf("综艺", "影视原声", "校园"))
        val queries = PlaylistCategoryCatalog.queryTerms("tx", groups)
        assertEquals(listOf("流行", "综艺"), PlaylistCategoryCatalog.rotatingWindow(queries, cycle = 0, limit = 2))
        assertEquals(listOf("摇滚", "影视原声"), PlaylistCategoryCatalog.rotatingWindow(queries, cycle = 1, limit = 2))
        assertTrue(PlaylistCategoryCatalog.queryTerms("tx", emptyList()).isEmpty())
        assertTrue(PlaylistCategoryCatalog.queryTerms("wy", emptyList()).isEmpty())
    }

    @Test
    fun categoryPageMergeRemovesSameSourceDuplicatesButKeepsCrossPlatformIds() {
        val first = Playlist(id = "same-id", source = "wy", name = "网易云歌单")
        val repeated = first.copy(name = "重复的网易云歌单")
        val otherSource = Playlist(id = "same-id", source = "kw", name = "酷我同号歌单")
        val blankId = Playlist(id = "", source = "wy", name = "无效歌单")

        val merged = mergeCategoryPlaylistPages(listOf(first), listOf(repeated, otherSource, blankId), "wy")

        assertEquals(listOf("wy:same-id", "kw:same-id"), merged.map { "${it.source}:${it.id}" })
        assertEquals("网易云歌单", merged.first().name)
    }

    @Test
    fun categoryRefreshCachesSuccessfulEmptyButLeavesFailedRequestUncachedAndRetryable() {
        val fallback = listOf(Playlist(id = "cached", source = "wy", name = "旧分类结果"))
        val failed = resolveCategoryPlaylistRefresh(
            outcome = PlaylistSearchOutcome.Failure,
            cachedPlaylists = fallback,
            titleMatches = emptyList(),
            platform = "wy"
        )
        assertTrue(failed.failed)
        assertEquals(fallback, failed.displayedPlaylists)
        assertEquals(null, failed.cacheSnapshot)

        val successfulEmpty = resolveCategoryPlaylistRefresh(
            outcome = PlaylistSearchOutcome.Success(emptyList()),
            cachedPlaylists = null,
            titleMatches = emptyList(),
            platform = "wy"
        )
        assertFalse(successfulEmpty.failed)
        assertTrue(successfulEmpty.displayedPlaylists.isEmpty())
        assertEquals(emptyList<Playlist>(), successfulEmpty.cacheSnapshot)
    }

    @Test
    fun categoryServerPayloadDistinguishesValidEmptyFromMalformedOrExplicitFailure() {
        val validEmpty = ApiClient.parseCategoryPlaylistPayload("""{"data":{"list":[]}}""", "wy")
        val malformed = ApiClient.parseCategoryPlaylistPayload("not-json", "wy")
        val explicitFailure = ApiClient.parseCategoryPlaylistPayload("""{"ok":false,"list":[]}""", "wy")

        assertTrue(validEmpty is PlaylistSearchOutcome.Success && validEmpty.playlists.isEmpty())
        assertEquals(PlaylistSearchOutcome.Failure, malformed)
        assertEquals(PlaylistSearchOutcome.Failure, explicitFailure)
    }

    @Test
    fun officialRecommendationOutcomeDistinguishesEmptyFromHttpTransportAndJsonFailure() {
        val validEmpty = ApiClient.parseRecommendPlaylistsPayloadOutcome("""{"list":[]}""", "wy")
        val validItems = ApiClient.parseRecommendPlaylistsPayloadOutcome(
            """{"list":[{"id":"p1","src":"wy","name":"精选歌单"}]}""",
            "wy"
        )
        assertEquals(FeedListOutcome.Success(emptyList<Playlist>()), validEmpty)
        assertTrue(validItems is FeedListOutcome.Success && validItems.data.single().id == "p1")
        assertEquals(FeedListOutcome.Failure, ApiClient.parseRecommendPlaylistsPayloadOutcome("not-json", "wy"))
        assertEquals(FeedListOutcome.Failure, ApiClient.parseRecommendPlaylistsPayloadOutcome("""{"ok":false,"list":[]}""", "wy"))
        assertEquals(FeedListOutcome.Failure, ApiClient.parseRecommendPlaylistsPayloadOutcome("""{"message":"unavailable"}""", "wy"))

        val httpFailure = decodeListResponseOutcome(classifyListHttpResponse(503, """{"list":[]}""")) {
            ApiClient.parseRecommendPlaylistsPayloadOutcome(it, "wy")
        }
        val emptyHttpBody = decodeListResponseOutcome(classifyListHttpResponse(200, "")) {
            ApiClient.parseRecommendPlaylistsPayloadOutcome(it, "wy")
        }
        val transportFailure = captureListRequestOutcome<Playlist> {
            throw IllegalStateException("request failed")
        }
        assertEquals(FeedListOutcome.Failure, httpFailure)
        assertEquals(FeedListOutcome.Failure, emptyHttpBody)
        assertEquals(FeedListOutcome.Failure, transportFailure)
    }

    @Test
    fun legacyRecommendationListMappingKeepsDataAndMapsFailureToEmpty() {
        val playlists = listOf(Playlist(id = "p1", source = "wy", name = "精选歌单"))
        assertEquals(playlists, FeedListOutcome.Success(playlists).legacyListValue())
        assertEquals(emptyList<Playlist>(), FeedListOutcome.Success(emptyList<Playlist>()).legacyListValue())
        assertEquals(emptyList<Playlist>(), FeedListOutcome.Failure.legacyListValue<Playlist>())
    }

    @Test
    fun categoryFallbackKeepsOnlyAnAuthoritativeDirectEmptyWhenServerAlsoFails() {
        val directEmpty = PlaylistSearchOutcome.Success(emptyList())
        assertEquals(directEmpty, chooseCategoryPlaylistSearchResult(directEmpty, PlaylistSearchOutcome.Failure))
        assertEquals(PlaylistSearchOutcome.Failure, chooseCategoryPlaylistSearchResult(PlaylistSearchOutcome.Failure, PlaylistSearchOutcome.Failure))
        assertEquals(directEmpty, chooseCategoryPlaylistSearchResult(PlaylistSearchOutcome.Failure, directEmpty))
    }

    @Test
    fun emptyOrFailedCategoryPagePreservesRetryStateAndReleasesLoading() {
        val originalPlaylists = listOf(Playlist(id = "page-2", source = "wy", name = "当前列表"))
        val loading = CategoryPlaylistPageState(
            page = 2,
            playlists = originalPlaylists,
            hasMore = true,
            loadingMore = true
        )

        val emptyResult = finishCategoryPlaylistPageLoad(loading, emptyList(), "wy", nextPage = 3)
        val failedResult = finishCategoryPlaylistPageLoad(loading, null, "wy", nextPage = 3)

        listOf(emptyResult, failedResult).forEach { result ->
            assertEquals(2, result.page)
            assertEquals(originalPlaylists, result.playlists)
            assertTrue(result.hasMore)
            assertFalse(result.loadingMore)
        }
    }

    @Test
    fun nonEmptyDuplicateCategoryPageKeepsNextPageAvailable() {
        val original = playlist("page-2", "当前列表", 0)
        val current = CategoryPlaylistPageState(
            page = 2,
            playlists = listOf(original),
            hasMore = true,
            loadingMore = true
        )

        val result = finishCategoryPlaylistPageLoad(
            current = current,
            incoming = listOf(original.copy(name = "跨页重复项")),
            platform = "wy",
            nextPage = 3
        )

        assertEquals(3, result.page)
        assertEquals(listOf(original), result.playlists)
        assertTrue(result.hasMore)
        assertFalse(result.loadingMore)
    }

    @Test
    fun featuredSelectionInterleavesCategoryBatchesAndUsesRealPlayCounts() {
        val result = selectDiverseFeaturedPlaylists(
            batches = listOf(
                listOf(playlist("a-low", "A low", 5), playlist("a-high", "A high", 200)),
                listOf(playlist("b-high", "B high", 150)),
                listOf(playlist("c-high", "C high", 100))
            ),
            fallbackPlatform = "wy",
            limit = 4
        )
        assertEquals(listOf("a-high", "b-high", "c-high", "a-low"), result.map { it.id })
    }

    @Test
    fun personalizedBatchPreservesTasteRelevanceOrderBeforeProviderPlayCountSort() {
        val result = selectDiverseFeaturedPlaylists(
            batches = listOf(
                listOf(playlist("taste-match", "Taste match", 1), playlist("generic-popular", "Popular", 10_000)),
                listOf(playlist("category", "Category result", 100))
            ),
            fallbackPlatform = "wy",
            limit = 3,
            preserveBatchOrder = setOf(0)
        )
        assertEquals(listOf("taste-match", "category", "generic-popular"), result.map { it.id })
    }

    @Test
    fun featuredSelectionAvoidsRecentIdsAndLimitsRepeatedAuthorsBeforeBackfill() {
        val repeated = listOf(
            playlist("a1", "A1", 100).copy(author = "Creator A"),
            playlist("a2", "A2", 90).copy(author = "Creator A"),
            playlist("a3", "A3", 80).copy(author = "Creator A")
        )
        val other = listOf(playlist("b1", "B1", 75).copy(author = "Creator B"))
        val excluded = PersonalRecommendations.playlistIdentity(repeated.first(), "wy")
        val result = selectDiverseFeaturedPlaylists(
            batches = listOf(repeated, other),
            fallbackPlatform = "wy",
            limit = 3,
            excludedPlaylistKeys = setOf(excluded)
        )
        assertEquals(listOf("b1", "a2", "a3"), result.map { it.id })
    }
}
