package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.cncnlp.soundwave.ui.home.loadSelectedRankDetail

class SelectedRankDetailLoaderTest {
    @Test
    fun `no selected board performs no cache or network work`() = runTest {
        var ioCalls = 0
        val songs = loadSelectedRankDetail(
            platform = "wy",
            board = null,
            loadCached = { _, _ -> ioCalls++; DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> ioCalls++; emptyList() },
            saveDetail = { _, _, _ -> ioCalls++ },
        )
        assertTrue(songs.isEmpty())
        assertEquals(0, ioCalls)
    }

    @Test
    fun `fresh cache is returned without a network request or cache write`() = runTest {
        val cached = listOf(song("cached"))
        val now = 1_000L
        var fetchCalls = 0
        var saveCalls = 0
        val songs = loadSelectedRankDetail(
            platform = "wy",
            board = board(id = "chart", preview = listOf(song("preview"))),
            loadCached = { id, source ->
                assertEquals("chart", id)
                assertEquals("wy", source)
                DetailCacheSnapshot(cached, now)
            },
            fetchDetail = { _, _ -> fetchCalls++; error("cache hit must skip network") },
            saveDetail = { _, _, _ -> saveCalls++ },
            now = now,
        )
        assertEquals(cached, songs)
        assertEquals(0, fetchCalls)
        assertEquals(0, saveCalls)
    }

    @Test
    fun `cache miss replaces preview with full response and caches it`() = runTest {
        val preview = listOf(song("preview"))
        val complete = listOf(song("one"), song("two"))
        var saved: List<Song> = emptyList()
        val songs = loadSelectedRankDetail(
            platform = "wy",
            board = board(id = "chart", source = "", preview = preview),
            loadCached = { id, source ->
                assertEquals("chart", id)
                assertEquals("wy", source)
                DetailCacheSnapshot(emptyList(), 0L)
            },
            fetchDetail = { id, source ->
                assertEquals("chart", id)
                assertEquals("wy", source)
                complete
            },
            saveDetail = { id, source, detail ->
                assertEquals("chart", id)
                assertEquals("wy", source)
                saved = detail
            },
        )
        assertEquals(complete, songs)
        assertEquals(complete, saved)
    }

    @Test
    fun `stale cached songs publish before refresh and remain when refresh is empty`() = runTest {
        val stale = listOf(song("stale"))
        val published = mutableListOf<List<Song>>()
        var fetchCalls = 0
        val now = 1_000L + HomeFeedCache.ResourceTtl.RANK_DETAIL_MS + 1L
        val songs = loadSelectedRankDetail(
            platform = "wy",
            board = board(id = "chart", preview = listOf(song("preview"))),
            loadCached = { _, _ -> DetailCacheSnapshot(stale, savedAt = 1_000L) },
            fetchDetail = { _, _ ->
                fetchCalls++
                assertEquals(stale, published.last())
                emptyList()
            },
            saveDetail = { _, _, _ -> fail("empty response must not be cached") },
            now = now,
            publishCached = published::add,
        )
        assertEquals(stale, songs)
        assertEquals(1, fetchCalls)
        assertEquals(listOf(stale), published)
    }
    @Test
    fun `explicit refresh bypasses a fresh selected-rank cache`() = runTest {
        val cached = listOf(song("cached"))
        val refreshed = listOf(song("network"))
        var fetchCalls = 0
        val now = 1_000L
        val songs = loadSelectedRankDetail(
            platform = "wy",
            board = board(id = "chart", preview = emptyList()),
            loadCached = { _, _ -> DetailCacheSnapshot(cached, now) },
            fetchDetail = { _, _ -> fetchCalls++; refreshed },
            saveDetail = { _, _, _ -> },
            forceRefresh = true,
            now = now,
        )
        assertEquals(refreshed, songs)
        assertEquals(1, fetchCalls)
    }
    @Test
    fun `empty or failed fetch retains preview and never caches an empty failure`() = runTest {
        val previews = listOf(listOf(song("empty-preview")), listOf(song("failed-preview")))
        val fetches: List<suspend (String, String) -> List<Song>> = listOf(
            { _, _ -> emptyList() },
            { _, _ -> throw IllegalStateException("offline") },
        )
        previews.zip(fetches).forEachIndexed { index, (preview, fetch) ->
            var saveCalls = 0
            val songs = loadSelectedRankDetail(
                platform = "wy",
                board = board(id = "chart-$index", preview = preview),
                loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
                fetchDetail = fetch,
                saveDetail = { _, _, _ -> saveCalls++ },
            )
            assertEquals(preview, songs)
            assertEquals(0, saveCalls)
        }
    }

    @Test
    fun `switching platform and board cannot reuse the previous result`() = runTest {
        val previousBoard = board(id = "old", source = "", preview = listOf(song("old-preview")))
        val previousDetail = listOf(song("old-detail"))
        val previous = loadSelectedRankDetail(
            platform = "wy",
            board = previousBoard,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { id, source ->
                assertEquals("old", id)
                assertEquals("wy", source)
                previousDetail
            },
            saveDetail = { _, _, _ -> },
        )
        assertEquals(previousDetail, previous)

        val nextPreview = listOf(song("next-preview"))
        val nextBoard = board(id = "next", source = "", preview = nextPreview)
        val next = loadSelectedRankDetail(
            platform = "tx",
            board = nextBoard,
            loadCached = { id, source ->
                assertEquals("next", id)
                assertEquals("tx", source)
                DetailCacheSnapshot(emptyList(), 0L)
            },
            fetchDetail = { id, source ->
                assertEquals("next", id)
                assertEquals("tx", source)
                emptyList()
            },
            saveDetail = { _, _, _ -> fail("empty response must not be cached") },
        )
        assertEquals(nextPreview, next)
        assertFalse(next.any { it in previousDetail })
    }
    @Test
    fun `cancellation is propagated rather than converted into an empty result`() = runTest {
        val cancelled = CancellationException("selection effect cancelled")
        try {
            loadSelectedRankDetail(
                platform = "wy",
                board = board(id = "chart", preview = listOf(song("preview"))),
                loadCached = { _, _ -> throw cancelled },
                fetchDetail = { _, _ -> error("cancelled load must not fetch") },
                saveDetail = { _, _, _ -> fail("cancelled load must not save") },
            )
            fail("expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    private fun board(id: String, source: String = "wy", preview: List<Song>) =
        RankBoard(name = id, source = source, id = id, songs = preview)

    private fun song(id: String) = Song(id = id, name = id, singer = "artist", source = "wy")
}
