package xyz.cncnlp.soundwave

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** accept6-singercover：专辑封面兜底回补的资格判定与缓存语义（取封面以注入假缝验证）。 */
class AlbumCoverBackfillTest {

    @Before fun reset() = AlbumCoverBackfill.resetForTest()

    private fun song(
        cover: String = "",
        albumId: String = "A1",
        source: String = "kw",
    ) = Song(id = "s1", name = "歌", singer = "歌手", source = source, cover = cover, albumId = albumId)

    @Test fun targetRequiresBlankCoverRealAlbumAndPlatformSource() {
        assertEquals("A1", AlbumCoverBackfill.targetAlbumId(song()))
        assertNull(AlbumCoverBackfill.targetAlbumId(song(cover = "https://x/y.jpg")))
        assertNull(AlbumCoverBackfill.targetAlbumId(song(albumId = "")))
        assertNull(AlbumCoverBackfill.targetAlbumId(song(albumId = "0")))
        assertNull(AlbumCoverBackfill.targetAlbumId(song(source = "local")))
        assertNull(AlbumCoverBackfill.targetAlbumId(song(source = "script_x")))
        // 五平台均可回补
        for (src in listOf("wy", "tx", "kg", "kw", "mg")) {
            assertEquals("A1", AlbumCoverBackfill.targetAlbumId(song(source = src)))
        }
    }

    @Test fun positiveResultIsCachedPerAlbum() = runBlocking {
        var calls = 0
        val fetcher: suspend (Song, String) -> String? = { _, _ -> calls++; "https://img.example.com/a.jpg" }
        val first = AlbumCoverBackfill.coverFor(song(), fetcher)
        val second = AlbumCoverBackfill.coverFor(song().copy(id = "s2"), fetcher)
        assertEquals("https://img.example.com/a.jpg", first)
        assertEquals(first, second)
        assertEquals(1, calls)
    }

    @Test fun failureIsNegativeCachedWithinTtl() = runBlocking {
        var calls = 0
        val fetcher: suspend (Song, String) -> String? = { _, _ -> calls++; null }
        assertNull(AlbumCoverBackfill.coverFor(song(), fetcher))
        assertNull(AlbumCoverBackfill.coverFor(song(), fetcher))
        assertEquals(1, calls)
    }

    @Test fun blankFetchResultCountsAsFailure() = runBlocking {
        val fetcher: suspend (Song, String) -> String? = { _, _ -> "  " }
        assertNull(AlbumCoverBackfill.coverFor(song(), fetcher))
    }

    @Test fun ineligibleSongNeverFetches() = runBlocking {
        var calls = 0
        val fetcher: suspend (Song, String) -> String? = { _, _ -> calls++; "https://img.example.com/a.jpg" }
        assertNull(AlbumCoverBackfill.coverFor(song(cover = "https://x/y.jpg"), fetcher))
        assertEquals(0, calls)
    }
}
