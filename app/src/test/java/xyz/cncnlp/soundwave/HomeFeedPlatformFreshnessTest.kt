package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedPlatformFreshnessTest {
    private val t0 = 1_800_000_000_000L
    private val t1 = t0 + HomeFeedCache.ResourceTtl.RANK_CATALOG_MS + 1L

    @Test
    fun refreshingAndPersistingPlatformBDoesNotRenewExpiredPlatformA() {
        val initial = completeSnapshot(mapOf("a" to sourceState("a", t0), "b" to sourceState("b", t0)), savedAt = t0)
            .copy(keywords = mapOf("a" to emptyList<String>(), "b" to emptyList<String>()))
        val oldB = initial.sourceStates.getValue("b")
        val refreshedB = oldB.copy(
            fetchedAt = t1,
            refreshedAt = t1,
            hotSongsFetchedAt = successfulTimestampAfterRequest(oldB.hotSongsFetchedAt, succeeded = true, fetchedNow = t1),
            rankBoardsFetchedAt = successfulTimestampAfterRequest(oldB.rankBoardsFetchedAt, succeeded = true, fetchedNow = t1),
            featuredPlaylistFetchedAt = successfulTimestampAfterRequest(oldB.featuredPlaylistFetchedAt, succeeded = true, fetchedNow = t1),
            hotKeywordsFetchedAt = successfulTimestampAfterRequest(oldB.hotKeywordsFetchedAt, succeeded = true, fetchedNow = t1)
        )
        val afterBRefresh = initial.copy(sourceStates = initial.sourceStates + ("b" to refreshedB))

        // A platform-B save rewrites the whole map and the global persistence ts, as production does.
        val restored = HomeFeedCache.decodeSnapshot(HomeFeedCache.encodeSnapshot(afterBRefresh, savedAt = t1))

        assertEquals(t1, restored.savedAt)
        listOf(
            HomeFeedCache.Resource.HOT,
            HomeFeedCache.Resource.RANK,
            HomeFeedCache.Resource.PLAYLIST,
            HomeFeedCache.Resource.KEYWORDS
        ).forEach { resource ->
            assertFalse("platform A $resource must retain its T0 age", restored.isResourceFresh(resource, "a", t1))
            assertTrue("platform B $resource must use its T1 success time", restored.isResourceFresh(resource, "b", t1))
        }
        assertTrue(restored.isPlatformFeedStale("a", t1))
        assertFalse(restored.isPlatformFeedStale("b", t1))
        assertFalse("platform A must not short-circuit after B is saved", shouldShortCircuitHomeFeed(restored, "a", false, t1))
        assertTrue("complete, fresh platform B may still short-circuit", shouldShortCircuitHomeFeed(restored, "b", false, t1))
    }

    @Test
    fun partialRefreshAdvancesOnlySuccessfulResourceTimesAndNotTheFullRefreshTime() {
        val previous = sourceState("a", t0)
        val hotFetchedAt = successfulTimestampAfterRequest(previous.hotSongsFetchedAt, succeeded = true, fetchedNow = t1)
        val rankFetchedAt = successfulTimestampAfterRequest(previous.rankBoardsFetchedAt, succeeded = false, fetchedNow = t1)
        // Empty featured playlists and keywords are valid successful negative-cache responses.
        val playlistFetchedAt = successfulTimestampAfterRequest(previous.featuredPlaylistFetchedAt, succeeded = true, fetchedNow = t1)
        val keywordsFetchedAt = successfulTimestampAfterRequest(previous.hotKeywordsFetchedAt, succeeded = true, fetchedNow = t1)
        val refreshedAt = successfulTimestampAfterRequest(previous.refreshedAt, succeeded = false, fetchedNow = t1)

        assertEquals(t1, hotFetchedAt)
        assertEquals(t0, rankFetchedAt)
        assertEquals(t1, playlistFetchedAt)
        assertEquals(t1, keywordsFetchedAt)
        assertEquals("a failed rank request must keep full-refresh time unchanged", t0, refreshedAt)
    }

    @Test
    fun missingResourceTimestampsInLegacyJsonAreStaleEvenWhenGlobalTsIsRecent() {
        val current = completeSnapshot(mapOf("a" to sourceState("a", t1)), savedAt = t1)
        val legacyJson = JSONObject(HomeFeedCache.encodeSnapshot(current, savedAt = t1))
        val legacyState = legacyJson.getJSONObject("state").getJSONObject("a")
        val twentyKeywords = org.json.JSONArray().apply { repeat(20) { put("keyword-$it") } }
        legacyJson.put("kws", JSONObject().put("a", twentyKeywords))
        legacyState.remove("hotSongsFetchedAt")
        legacyState.remove("rankBoardsFetchedAt")
        legacyState.remove("hotKeywordsFetchedAt")
        legacyState.remove("featuredPlaylistFetchedAt")

        val restored = HomeFeedCache.decodeSnapshot(legacyJson.toString())

        assertEquals(t1, restored.savedAt)
        assertEquals(0L, restored.sourceStates.getValue("a").hotSongsFetchedAt)
        assertEquals(0L, restored.sourceStates.getValue("a").rankBoardsFetchedAt)
        assertEquals(0L, restored.sourceStates.getValue("a").hotKeywordsFetchedAt)
        listOf(
            HomeFeedCache.Resource.HOT,
            HomeFeedCache.Resource.RANK,
            HomeFeedCache.Resource.PLAYLIST,
            HomeFeedCache.Resource.KEYWORDS
        ).forEach { resource -> assertFalse(restored.isResourceFresh(resource, "a", t1)) }
        assertFalse("old data with no per-resource time must not short-circuit", shouldShortCircuitHomeFeed(restored, "a", false, t1))
    }

    @Test
    fun twentyKeywordsAndSuccessfulEmptyKeywordsBothRequireTheirOwnFreshTimestamp() {
        val ttl = HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS
        val full = mapOf("a" to List(20) { "keyword-$it" })
        val empty = mapOf("a" to emptyList<String>())

        assertTrue(hasUsableHomeKeywordSnapshot(full, "a", t0, t0 + ttl))
        assertFalse(hasUsableHomeKeywordSnapshot(full, "a", t0, t0 + ttl + 1L))
        assertFalse("20 items cannot bypass a missing timestamp", hasUsableHomeKeywordSnapshot(full, "a", 0L, t0))
        assertFalse("incomplete non-empty lists remain unusable", hasUsableHomeKeywordSnapshot(mapOf("a" to listOf("partial")), "a", t0, t0))

        assertTrue("fresh successful empty response is a valid negative cache", hasUsableHomeKeywordSnapshot(empty, "a", t0, t0 + ttl))
        assertFalse(hasUsableHomeKeywordSnapshot(empty, "a", t0, t0 + ttl + 1L))
        assertFalse("empty response without a timestamp is not a cache hit", hasUsableHomeKeywordSnapshot(empty, "a", 0L, t0))
    }

    @Test
    fun nonEmptyAndEmptyFeaturedPlaylistSnapshotsBothExpireAtTheSameTtlBoundary() {
        val ttl = HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS
        val playlist = listOf(Playlist(id = "a-1", name = "A playlist", source = "a"))

        assertTrue(hasCurrentFeaturedPlaylistSnapshot("a", playlist, true, t0, t0 + ttl))
        assertFalse("a non-empty playlist cannot bypass expiry", hasCurrentFeaturedPlaylistSnapshot("a", playlist, true, t0, t0 + ttl + 1L))
        assertFalse("a non-empty playlist cannot bypass a missing timestamp", hasCurrentFeaturedPlaylistSnapshot("a", playlist, true, 0L, t0))
        assertFalse(hasCurrentFeaturedPlaylistSnapshot("a", playlist, false, t0, t0))

        assertTrue("fresh successful empty response is a valid negative cache", hasCurrentFeaturedPlaylistSnapshot("a", emptyList(), true, t0, t0 + ttl))
        assertFalse(hasCurrentFeaturedPlaylistSnapshot("a", emptyList(), true, t0, t0 + ttl + 1L))
        assertFalse(hasCurrentFeaturedPlaylistSnapshot("a", emptyList(), true, 0L, t0))
    }

    private fun completeSnapshot(states: Map<String, FeedSourceState>, savedAt: Long): HomeFeedCache.Snapshot {
        val platforms = states.keys
        return HomeFeedCache.Snapshot(
            hot = platforms.associateWith { platform -> listOf(Song("hot-$platform", "Hot $platform", "Singer", platform)) },
            rank = platforms.associateWith { platform -> listOf(RankBoard("Rank $platform", source = platform)) },
            pl = platforms.associateWith { platform -> listOf(Playlist("$platform-1", "Playlist $platform", source = platform)) },
            keywords = platforms.associateWith { platform -> List(20) { "$platform-keyword-$it" } },
            sourceStates = states,
            savedAt = savedAt
        )
    }

    private fun sourceState(platform: String, at: Long) = FeedSourceState(
        source = platform,
        fetchedAt = at,
        refreshedAt = at,
        hotCount = 1,
        rankCount = 1,
        playlistCount = 1,
        featuredPlaylistFetchedAt = at,
        hotSongsFetchedAt = at,
        rankBoardsFetchedAt = at,
        hotKeywordsFetchedAt = at
    )
}
