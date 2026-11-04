package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.ui.home.loadHomeNewSongExpress

class HomeNewSongExpressTest {
    @Test
    fun `missing board returns empty without requesting detail`() = runTest {
        var requests = 0
        val songs = loadHomeNewSongExpress(null) {
            requests++
            emptyList()
        }

        assertEquals(emptyList<Song>(), songs)
        assertEquals(0, requests)
    }

    @Test
    fun `blank board id keeps preview and does not request detail`() = runTest {
        val preview = songs("preview", 3)
        var requests = 0
        val result = loadHomeNewSongExpress(RankBoard(name = "新歌榜", songs = preview)) {
            requests++
            error("blank board ID must not be requested")
        }

        assertEquals(preview, result)
        assertEquals(0, requests)
    }

    @Test
    fun `ten or more preview songs return the first ten without requesting detail`() = runTest {
        val preview = songs("preview", 12)
        var requests = 0
        val result = loadHomeNewSongExpress(
            RankBoard(name = "新歌榜", id = "new", songs = preview),
        ) {
            requests++
            error("complete preview must not be requested")
        }

        assertEquals(preview.take(10), result)
        assertEquals(0, requests)
    }

    @Test
    fun `short preview is replaced by successful detail capped at ten`() = runTest {
        val preview = songs("preview", 2)
        val detail = songs("detail", 13)
        var requests = 0
        val result = loadHomeNewSongExpress(
            RankBoard(name = "新歌榜", id = "new", songs = preview),
        ) { boardId ->
            requests++
            assertEquals("new", boardId)
            detail
        }

        assertEquals(detail.take(10), result)
        assertEquals(1, requests)
    }

    @Test
    fun `empty detail falls back to preview`() = runTest {
        val preview = songs("preview", 4)
        var requests = 0
        val result = loadHomeNewSongExpress(
            RankBoard(name = "新歌榜", id = "new", songs = preview),
        ) {
            requests++
            emptyList()
        }

        assertEquals(preview, result)
        assertEquals(1, requests)
    }

    @Test
    fun `detail exception falls back to preview`() = runTest {
        val preview = songs("preview", 4)
        var requests = 0
        val result = loadHomeNewSongExpress(
            RankBoard(name = "新歌榜", id = "new", songs = preview),
        ) {
            requests++
            throw IllegalStateException("detail unavailable")
        }

        assertEquals(preview, result)
        assertEquals(1, requests)
    }

    @Test
    fun `cancellation exception from detail retains legacy fallback behavior`() = runTest {
        val preview = songs("preview", 4)
        var requests = 0
        val result = loadHomeNewSongExpress(
            RankBoard(name = "新歌榜", id = "new", songs = preview),
        ) {
            requests++
            throw CancellationException("legacy request cancellation")
        }

        assertEquals(preview, result)
        assertEquals(1, requests)
    }

    private fun songs(prefix: String, count: Int) =
        List(count) { index -> Song(id = "$prefix-$index", name = "$prefix $index", singer = "artist") }
}
