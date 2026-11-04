package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.captureListRequestOutcome
import xyz.cncnlp.soundwave.source.classifyListHttpResponse
import xyz.cncnlp.soundwave.source.decodeListResponseOutcome
import xyz.cncnlp.soundwave.source.legacyListValue

/**
 * 批35：首页刷新短路策略边界测试。
 * 覆盖 Fresh（全部新鲜短路）/ Stale（任一过期不短路）/ Expired（失效重拉）/ 强制刷新 / 无缓存场景。
 */
class HomeFeedCachePolicyTest {

    @Test
    fun forceNetworkNeverShortCircuits() {
        assertFalse(
            "强制刷新必须总是走网络",
            shouldShortCircuit(forceNetwork = true, hasCurrentCache = true, hasCurrentFeaturedPlaylist = true, hotFresh = true, rankFresh = true, plFresh = true)
        )
    }

    @Test
    fun noCacheNeverShortCircuits() {
        assertFalse(
            "当前平台缓存不齐全时必须联网补齐",
            shouldShortCircuit(forceNetwork = false, hasCurrentCache = false, hasCurrentFeaturedPlaylist = true, hotFresh = true, rankFresh = true, plFresh = true)
        )
    }

    @Test
    fun allFreshWithCompleteCacheShortCircuits() {
        assertTrue(
            "三类资源全部新鲜且缓存齐全 → 直接展示缓存",
            shouldShortCircuit(forceNetwork = false, hasCurrentCache = true, hasCurrentFeaturedPlaylist = true, hotFresh = true, rankFresh = true, plFresh = true)
        )
    }

    @Test
    fun successfulEmptyFeaturedResultIsAValidNegativeCacheForTheExistingTtl() {
        val now = 2_000_000_000_000L
        val resolved = resolveHomeFeedRequestResult(
            HomeFeedRequestResult.Success(emptyList<Playlist>()),
            staleValue = listOf(Playlist(id = "old", name = "old", source = "wy"))
        )
        assertFalse(resolved.failed)
        assertTrue(resolved.value.isEmpty())
        assertEquals(now, successfulTimestampAfterRequest(1_000L, succeeded = !resolved.failed, fetchedNow = now))
        assertTrue(
            hasCurrentFeaturedPlaylistSnapshot("wy", emptyList(), true, now, now + HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS)
        )
        assertFalse(
            hasCurrentFeaturedPlaylistSnapshot("wy", emptyList(), true, now, now + HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS + 1L)
        )
    }

    @Test
    fun failedFeaturedRequestRetainsStaleValueAndRemainsRetryable() {
        val stale = listOf(Playlist(id = "old", name = "old", source = "wy"))
        val resolved = resolveHomeFeedRequestResult(HomeFeedRequestResult.Failure, stale)
        assertTrue(resolved.failed)
        assertEquals(stale, resolved.value)
        val oldCache = mapOf("wy" to stale)
        assertEquals(oldCache, updateHomeFeedListCache(oldCache, "wy", HomeFeedRequestResult.Failure))
        assertEquals(1_000L, successfulTimestampAfterRequest(1_000L, succeeded = !resolved.failed, fetchedNow = 2_000L))
        assertFalse(hasCurrentFeaturedPlaylistSnapshot("wy", emptyList(), false, 1_000L, 1_001L))
        assertFalse(
            shouldShortCircuit(
                forceNetwork = false,
                hasCurrentCache = true,
                hasCurrentFeaturedPlaylist = true,
                hotFresh = true,
                rankFresh = true,
                plFresh = true,
                hasFailedResources = resolved.failed
            )
        )
    }

    @Test
    fun keywordOrDiscoverSuccessEmptyIsNotFailureButFailureBlocksShortCircuit() {
        val successfulEmpty = FeedListOutcome.Success(emptyList<String>()).toHomeFeedRequestResult()
        assertFalse(isHomeFeedListRequestFailed(successfulEmpty, emptyIsFailure = false))

        listOf("热词", "发现").forEach { failedResource ->
            val request = FeedListOutcome.Failure.toHomeFeedRequestResult<List<String>>()
            val failed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)
            assertTrue(failedResource, failed)
            assertFalse(
                failedResource,
                shouldShortCircuit(
                    forceNetwork = false,
                    hasCurrentCache = true,
                    hasCurrentFeaturedPlaylist = true,
                    hotFresh = true,
                    rankFresh = true,
                    plFresh = true,
                    hasFailedResources = failed
                )
            )
        }
        val successfulNonEmpty = HomeFeedRequestResult.Success(listOf("热搜词"))
        val successfulNonEmptyFailed = isHomeFeedListRequestFailed(successfulNonEmpty, emptyIsFailure = true)
        assertFalse(successfulNonEmptyFailed)
        assertEquals(2_000L, successfulTimestampAfterRequest(1_000L, succeeded = !successfulNonEmptyFailed, fetchedNow = 2_000L))
    }

    @Test
    fun successfulEmptyHotKeywordsAreNegativeCachedForTheExistingTtlAndCanShortCircuit() {
        val now = 2_000_000_000_000L
        val negativeCache = mapOf("wy" to emptyList<String>())
        assertTrue(hasUsableHomeKeywordSnapshot(negativeCache, "wy", now, now + HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS))
        assertFalse(hasUsableHomeKeywordSnapshot(negativeCache, "wy", now, now + HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS + 1L))
        assertFalse(hasUsableHomeKeywordSnapshot(emptyMap(), "wy", now, now))
        assertTrue(
            shouldShortCircuit(
                forceNetwork = false,
                hasCurrentCache = hasUsableHomeKeywordSnapshot(negativeCache, "wy", now, now),
                hasCurrentFeaturedPlaylist = true,
                hotFresh = true,
                rankFresh = true,
                plFresh = true
            )
        )
        val previous = mapOf("wy" to listOf("old keyword"))
        val successfulEmpty = FeedListOutcome.Success(emptyList<String>()).toHomeFeedRequestResult()
        assertEquals(mapOf("wy" to emptyList<String>()), updateHomeFeedListCache(previous, "wy", successfulEmpty))
        assertEquals(previous, updateHomeFeedListCache(previous, "wy", HomeFeedRequestResult.Failure))
    }

    @Test
    fun homeApiOutcomesDistinguishValidEmptyFromTransportAndParseFailures() {
        val hotEmpty = ApiClient.parseHotKeywordsServerPayloadOutcome("""{"list":[]}""")
        val discoverEmpty = ApiClient.parseDiscoverServerPayloadOutcome("""{"list":[]}""", "wy")
        assertEquals(FeedListOutcome.Success(emptyList<String>()), hotEmpty)
        assertEquals(FeedListOutcome.Success(emptyList<DiscoverCard>()), discoverEmpty)
        assertEquals(FeedListOutcome.Success("""{"list":[]}"""), classifyListHttpResponse(200, """{"list":[]}"""))
        assertEquals(FeedListOutcome.Failure, classifyListHttpResponse(503, """{"list":[]}"""))
        assertEquals(FeedListOutcome.Failure, classifyListHttpResponse(200, ""))

        assertEquals(FeedListOutcome.Failure, ApiClient.parseHotKeywordsServerPayloadOutcome("not-json"))
        assertEquals(FeedListOutcome.Failure, ApiClient.parseDiscoverServerPayloadOutcome("not-json", "wy"))
        assertEquals(FeedListOutcome.Failure, ApiClient.parseHotKeywordsServerPayloadOutcome("""{"ok":false,"list":[]}"""))
        assertEquals(
            FeedListOutcome.Failure,
            decodeListResponseOutcome(FeedListOutcome.Failure) { ApiClient.parseHotKeywordsServerPayloadOutcome(it) }
        )
        assertEquals(FeedListOutcome.Failure, captureListRequestOutcome<String> { throw IllegalStateException("request failed") })
    }

    @Test
    fun legacyListCompatibilityMapsSuccessDataAndFailureToTheOriginalValues() {
        assertEquals(listOf("词"), FeedListOutcome.Success(listOf("词")).legacyListValue())
        assertTrue(FeedListOutcome.Success(emptyList<String>()).legacyListValue().isEmpty())
        assertTrue(FeedListOutcome.Failure.legacyListValue<String>().isEmpty())
    }

    @Test
    fun hotStaleNeverShortCircuits() {
        assertFalse(
            "热门过期（HOT TTL 20min）→ 必须重拉",
            shouldShortCircuit(forceNetwork = false, hasCurrentCache = true, hasCurrentFeaturedPlaylist = true, hotFresh = false, rankFresh = true, plFresh = true)
        )
    }

    @Test
    fun rankStaleNeverShortCircuits() {
        assertFalse(
            "榜单过期 → 必须重拉",
            shouldShortCircuit(forceNetwork = false, hasCurrentCache = true, hasCurrentFeaturedPlaylist = true, hotFresh = true, rankFresh = false, plFresh = true)
        )
    }

    @Test
    fun playlistStaleNeverShortCircuits() {
        assertFalse(
            "歌单过期 → 必须重拉",
            shouldShortCircuit(forceNetwork = false, hasCurrentCache = true, hasCurrentFeaturedPlaylist = true, hotFresh = true, rankFresh = true, plFresh = false)
        )
    }

    @Test
    fun completeFreshCacheWithoutCurrentPlatformFeaturedPlaylistMustFetch() {
        assertFalse(
            shouldShortCircuit(
                forceNetwork = false,
                hasCurrentCache = true,
                hasCurrentFeaturedPlaylist = false,
                hotFresh = true,
                rankFresh = true,
                plFresh = true
            )
        )
    }

    @Test
    fun onlyARealPlaylistFromTheCurrentPlatformCountsAsFeaturedCache() {
        val valid = Playlist(id = "real-1", name = "real", source = "wy")
        val sourceFallback = Playlist(id = "real-2", name = "legacy", source = "")
        val wrongPlatform = Playlist(id = "real-2", name = "other", source = "kg")
        val local = Playlist(id = "local", name = "local", source = "wy", local = true)
        val missingId = Playlist(id = "", name = "invalid", source = "wy")

        assertTrue(hasUsableFeaturedPlaylist("WY", listOf(valid)))
        assertTrue(hasUsableFeaturedPlaylist("wy", listOf(sourceFallback)))
        assertFalse(hasUsableFeaturedPlaylist("wy", listOf(wrongPlatform, local, missingId)))
        assertFalse(hasUsableFeaturedPlaylist("wy", emptyList()))
    }
    @Test
    fun personalRecommendationCacheKeySurvivesTasteProfileChanges() {
        assertEquals("personal|wy", HomeFeedCache.personalRecommendationCacheKey("wy"))
        assertEquals(
            HomeFeedCache.personalRecommendationCacheKey("wy"),
            HomeFeedCache.personalRecommendationCacheKey("WY")
        )
        // Profile signatures remain inputs to generation, not navigation-time cache identity.
        assertEquals("personal|wy", HomeFeedCache.personalRecommendationCacheKey("wy"))
    }

    @Test
    fun recommendationCacheRefreshesOnlyWhenMissingOrOutsideExistingTtl() {
        val now = 2_000_000_000_000L
        val freshNegativeCache = HomeFeedCache.BrowsePlaylistSnapshot(emptyList(), now)
        val staleCache = HomeFeedCache.BrowsePlaylistSnapshot(emptyList(), now - HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS - 1L)
        assertFalse(HomeFeedCache.browsePlaylistCacheNeedsRefresh(freshNegativeCache, now))
        assertTrue(HomeFeedCache.browsePlaylistCacheNeedsRefresh(staleCache, now))
        assertTrue(HomeFeedCache.browsePlaylistCacheNeedsRefresh(null, now))
    }

    @Test
    fun recommendationSnapshotUsesProductionJsonCodecForPersistentRoundTripAndTtl() {
        val key = HomeFeedCache.personalRecommendationCacheKey("wy")
        val now = 2_000_000_000_000L
        val playlist = Playlist(
            id = "remote-1", name = "Real cached playlist", cover = "https://img.example/cover.jpg",
            source = "wy", author = "Artist", description = "description", count = 42,
            playCount = 1_234L, remoteId = "cloud-1"
        )
        val encoded = HomeFeedCache.encodeBrowsePlaylistSnapshot(key, listOf(playlist), savedAt = now)
        val restored = HomeFeedCache.decodeBrowsePlaylistSnapshot(encoded, key)!!
        assertEquals(listOf(playlist), restored.playlists)
        assertEquals(now, restored.savedAt)
        assertTrue(restored.isFresh(now = now + HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS))
        assertFalse(restored.isFresh(now = now + HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS + 1L))
        assertEquals(null, HomeFeedCache.decodeBrowsePlaylistSnapshot(encoded, "personal|kg"))

        // Empty results are persisted as a negative cache entry rather than refetched on every composition/route return.
        val emptyEncoded = HomeFeedCache.encodeBrowsePlaylistSnapshot(key, emptyList(), savedAt = now)
        val emptyRestored = HomeFeedCache.decodeBrowsePlaylistSnapshot(emptyEncoded, key)!!
        assertTrue(emptyRestored.playlists.isEmpty())
        assertTrue(emptyRestored.isFresh(now = now + 1L))
    }

    @Test
    fun browseCategorySnapshotsRoundTripHasMoreTriStateForEveryPlatform() {
        val now = 2_000_000_000_000L
        listOf("wy", "tx", "kg", "kw", "mg").forEach { platform ->
            val key = "category-v3|$platform|all|all"
            listOf(true, false, null).forEach { expectedHasMore ->
                val encoded = HomeFeedCache.encodeBrowsePlaylistSnapshot(
                    key,
                    emptyList(),
                    savedAt = now,
                    hasMore = expectedHasMore
                )
                val restored = HomeFeedCache.decodeBrowsePlaylistSnapshot(encoded, key)!!
                assertEquals("$platform hasMore=$expectedHasMore", expectedHasMore, restored.hasMore)
                assertEquals(now, restored.savedAt)
            }
        }
    }

    @Test
    fun browseCategorySnapshotsWithoutHasMoreRemainUnknownForEveryPlatform() {
        listOf("wy", "tx", "kg", "kw", "mg").forEach { platform ->
            val key = "category-v3|$platform|all|all"
            val legacyJson = """{"key":"$key","items":[],"ts":2000000000000}"""

            val restored = HomeFeedCache.decodeBrowsePlaylistSnapshot(legacyJson, key)!!
            assertEquals("Legacy $platform snapshot must not infer hasMore=false", null, restored.hasMore)
        }
    }

    @Test
    fun browseCategorySnapshotsTreatInvalidHasMoreJsonValuesAsUnknown() {
        listOf("wy", "tx", "kg", "kw", "mg").forEach { platform ->
            val key = "category-v3|$platform|all|all"
            listOf("\"false\"", "0").forEach { invalidValue ->
                val invalidJson = """{"key":"$key","items":[],"ts":2000000000000,"hasMore":$invalidValue}"""

                val restored = HomeFeedCache.decodeBrowsePlaylistSnapshot(invalidJson, key)!!
                assertEquals("Invalid $platform hasMore=$invalidValue must stay unknown", null, restored.hasMore)
            }
        }
    }
}
