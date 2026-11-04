package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * accept6-kwfix evidence tests: fixtures are live responses recorded 2026-11-04
 * from two exits (this VM's egress + instabox agent-5, byte-identical sizes)
 * for artist 周杰伦 (kw artistid 336) and album 太阳之子 (kw albumid 87758985).
 * Album rows were trimmed to the first 4 entries; envelopes and field shapes
 * are the upstream originals.
 */
class KwFixLiveEvidenceTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/kwfix-20261104/$name")!!.readText()

    @Test fun kwAlbumSearchParsesLiveResponse() {
        val result = parseOfficialEntities("kw", fixture("album-search.txt"), "周杰伦", "album")
        assertTrue(result.available)
        assertEquals(4, result.songs.size)
        val first = result.songs.first { it.albumId == "87758985" }
        assertEquals("太阳之子", first.album)
        assertTrue("cover must be an https kuwo.cn URL: ${first.cover}",
            first.cover.startsWith("https://") && first.cover.contains("kuwo.cn") && !first.cover.contains("sycdn"))
    }

    @Test fun kwArtistAlbumCoversAreReachableHttps() {
        val albums = parseKwArtistAlbums(fixture("artist-albums.json"))
        assertEquals(4, albums.size)
        assertEquals("87758985", albums.first().id)
        for (album in albums) {
            assertTrue("cover must be https and not the TLS-dead sycdn host: ${album.cover}",
                album.cover.startsWith("https://") && !album.cover.contains("sycdn"))
        }
    }

    @Test fun kwArtistAlbumCountReadsMusiccnt() {
        val albums = parseKwArtistAlbums(fixture("artist-albums.json"))
        assertEquals(13, albums.first().count)
    }

    @Test fun kwAlbumDetailParsesAndCoverIsReachableHttps() {
        val detail = parseKwAlbumDetail(fixture("album-info.txt"), "87758985")
        assertEquals("太阳之子", detail.name)
        assertEquals(4, detail.songs.size)
        assertTrue("album cover must be https and not sycdn: ${detail.cover}",
            detail.cover.startsWith("https://") && !detail.cover.contains("sycdn"))
    }
}
