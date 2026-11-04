package xyz.cncnlp.soundwave

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailCachePolicyTest {
    @Test
    fun `detail cache TTL accepts exact boundary and rejects expired or missing timestamps`() {
        val ttl = HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS
        val entry = DetailCacheSnapshot(listOf(song("cached")), savedAt = 1_000L)

        assertTrue(entry.isFresh(ttl, now = 1_000L + ttl))
        assertFalse(entry.isFresh(ttl, now = 1_001L + ttl))
        assertFalse(DetailCacheSnapshot(listOf(song("legacy")), savedAt = 0L).isFresh(ttl, now = 1_000L))
        assertTrue(entry.isFresh(ttl, now = 999L))
    }

    @Test
    fun `fresh nonempty detail cache is published and skips network`() = runTest {
        val cached = listOf(song("cached"))
        var fetchCalls = 0
        var published: List<Song> = emptyList()

        val result = resolveDetailCache(
            cache = DetailCacheSnapshot(cached, savedAt = 900L),
            ttlMs = HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS,
            now = 1_000L,
            publishCached = { published = it },
            fetchDetail = { fetchCalls++; error("fresh cache must skip network") },
        )

        assertEquals(DetailCacheResolution.CacheHit(cached), result)
        assertEquals(cached, published)
        assertEquals(0, fetchCalls)
    }

    @Test
    fun `missing and expired details fetch exactly once while expired songs remain visible`() = runTest {
        val stale = listOf(song("stale"))
        listOf(
            DetailCacheSnapshot(emptyList(), savedAt = 0L),
            DetailCacheSnapshot(stale, savedAt = 1L),
        ).forEachIndexed { index, cache ->
            var fetchCalls = 0
            var published: List<Song> = emptyList()
            val fresh = listOf(song("network-$index"))

            val result = resolveDetailCache(
                cache = cache,
                ttlMs = 10L,
                now = 100L,
                publishCached = { published = it },
                fetchDetail = { fetchCalls++; fresh },
            )

            assertEquals(1, fetchCalls)
            assertEquals(if (index == 0) emptyList<Song>() else stale, published)
            assertEquals(DetailCacheResolution.Refreshed(cache.songs, fresh), result)
        }
    }

    @Test
    fun `explicit refresh bypasses a fresh detail cache but still publishes it`() = runTest {
        val cached = listOf(song("cached"))
        val fetched = listOf(song("refreshed"))
        var fetchCalls = 0
        var published: List<Song> = emptyList()

        val result = resolveDetailCache(
            cache = DetailCacheSnapshot(cached, savedAt = 900L),
            ttlMs = HomeFeedCache.ResourceTtl.RANK_DETAIL_MS,
            now = 1_000L,
            forceRefresh = true,
            publishCached = { published = it },
            fetchDetail = { fetchCalls++; fetched },
        )

        assertEquals(DetailCacheResolution.Refreshed(cached, fetched), result)
        assertEquals(cached, published)
        assertEquals(1, fetchCalls)
    }

    private fun song(id: String) = Song(id = id, name = id, singer = "artist", source = "wy")
}
