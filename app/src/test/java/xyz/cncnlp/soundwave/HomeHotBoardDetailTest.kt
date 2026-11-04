package xyz.cncnlp.soundwave

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ui.home.HomeHotBoardDetailState
import xyz.cncnlp.soundwave.ui.home.homeHotBoardDetailKey
import xyz.cncnlp.soundwave.ui.home.loadHomeHotBoardDetail

class HomeHotBoardDetailTest {
    @Test
    fun `successful request replaces preview and caches the full result`() = runTest {
        val board = RankBoard(name = "热歌榜", source = "wy", id = "hot", songs = listOf(song("preview")))
        val fullDetail = listOf(song("one"), song("two"))
        val states = mutableListOf<HomeHotBoardDetailState>()
        var saved: List<Song> = emptyList()
        loadHomeHotBoardDetail(
            platform = "wy",
            board = board,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> fullDetail },
            saveDetail = { _, _, songs -> saved = songs },
            publish = states::add,
        )
        assertEquals(fullDetail, states.last().songs)
        assertEquals(fullDetail, saved)
        assertTrue(states.last().error.isEmpty())
        assertEquals(false, states.last().loading)
    }

    @Test
    fun `fresh rank cache is displayed without an automatic network request`() = runTest {
        val cached = listOf(song("cached"))
        val now = 1_000L
        val states = mutableListOf<HomeHotBoardDetailState>()
        var fetchCalls = 0
        loadHomeHotBoardDetail(
            platform = "wy",
            board = RankBoard(name = "热歌榜", source = "wy", id = "cached-board", songs = emptyList()),
            loadCached = { _, _ -> DetailCacheSnapshot(cached, now) },
            fetchDetail = { _, _ -> fetchCalls++; error("fresh cache must skip network") },
            saveDetail = { _, _, _ -> error("cache hit must not rewrite TTL") },
            publish = states::add,
            now = now,
        )
        assertEquals(cached, states.last().songs)
        assertEquals(0, fetchCalls)
        assertEquals(false, states.last().loading)
    }

    @Test
    fun `empty refresh retains stale cached songs before falling back to board songs`() = runTest {
        val stale = listOf(song("stale"))
        val boardPreview = listOf(song("preview"))
        val states = mutableListOf<HomeHotBoardDetailState>()
        val board = RankBoard(name = "热歌榜", source = "wy", id = "cached-board", songs = boardPreview)
        val now = 1_000L + HomeFeedCache.ResourceTtl.RANK_DETAIL_MS + 1L
        loadHomeHotBoardDetail(
            platform = "wy",
            board = board,
            loadCached = { _, _ -> DetailCacheSnapshot(stale, savedAt = 1_000L) },
            fetchDetail = { _, _ ->
                assertEquals(stale, states.last().songs)
                emptyList()
            },
            saveDetail = { _, _, _ -> error("empty response must not be cached") },
            publish = states::add,
            now = now,
        )
        assertEquals(stale, states.last().songs)
        assertTrue(states.last().error.isEmpty())

        val previewStates = mutableListOf<HomeHotBoardDetailState>()
        val previewBoard = board.copy(id = "preview-board")
        loadHomeHotBoardDetail(
            platform = "wy",
            board = previewBoard,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> emptyList() },
            saveDetail = { _, _, _ -> error("empty response must not be cached") },
            publish = previewStates::add,
        )
        assertEquals(boardPreview, previewStates.last().songs)
        assertTrue(previewStates.last().error.isEmpty())
    }

    @Test
    fun `explicit retry refreshes a fresh cache`() = runTest {
        val cached = listOf(song("cached"))
        val refreshed = listOf(song("refreshed"))
        val now = 1_000L
        var fetchCalls = 0
        val states = mutableListOf<HomeHotBoardDetailState>()
        loadHomeHotBoardDetail(
            platform = "wy",
            board = RankBoard(name = "热歌榜", source = "wy", id = "retry-board", songs = emptyList()),
            loadCached = { _, _ -> DetailCacheSnapshot(cached, now) },
            fetchDetail = { _, _ -> fetchCalls++; refreshed },
            saveDetail = { _, _, _ -> },
            publish = states::add,
            forceRefresh = true,
            now = now,
        )
        assertEquals(refreshed, states.last().songs)
        assertEquals(1, fetchCalls)
    }

    @Test
    fun `fully empty request produces the existing error state`() = runTest {
        val states = mutableListOf<HomeHotBoardDetailState>()
        val board = RankBoard(name = "热歌榜", source = "wy", id = "empty")
        loadHomeHotBoardDetail(
            platform = "wy",
            board = board,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> emptyList() },
            saveDetail = { _, _, _ -> error("empty response must not be cached") },
            publish = states::add,
        )
        assertEquals(
            HomeHotBoardDetailState(emptyList(), loading = false, error = "暂未获取到当前平台热歌榜歌曲"),
            states.last(),
        )
    }

    @Test
    fun `changing platform or board starts from that selection instead of previous songs`() = runTest {
        val original = RankBoard(name = "热歌榜", id = "same", songs = emptyList())
        val oldRemote = listOf(song("old-wy"))
        val platformRemote = listOf(song("new-tx"))
        val wyKey = homeHotBoardDetailKey("wy", original)
        val txKey = homeHotBoardDetailKey("tx", original)
        assertNotEquals(wyKey, txKey)

        var current = HomeHotBoardDetailState(emptyList(), loading = true)
        loadHomeHotBoardDetail(
            platform = "wy", board = original,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> oldRemote }, saveDetail = { _, _, _ -> }, publish = { current = it },
        )
        assertEquals(oldRemote, current.songs)

        loadHomeHotBoardDetail(
            platform = "tx", board = original,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> platformRemote }, saveDetail = { _, _, _ -> }, publish = { current = it },
        )
        assertEquals(platformRemote, current.songs)

        val nextBoard = original.copy(id = "next", songs = listOf(song("next-preview")))
        assertNotEquals(txKey, homeHotBoardDetailKey("tx", nextBoard))
        loadHomeHotBoardDetail(
            platform = "tx", board = nextBoard,
            loadCached = { _, _ -> DetailCacheSnapshot(emptyList(), 0L) },
            fetchDetail = { _, _ -> emptyList() }, saveDetail = { _, _, _ -> }, publish = { current = it },
        )
        assertEquals(nextBoard.songs, current.songs)
        assertTrue(current.songs.none { it.id == "new-tx" || it.id == "old-wy" })
    }

    private fun song(id: String) = Song(id = id, name = id, singer = "artist", source = "wy")
}
