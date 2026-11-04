package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.*
import org.junit.Test

class Round5AlbumCatalogTest {
    private fun fixture(name: String) = javaClass.getResource("/round5/$name.json")!!.readText()
    @Test fun kugouShopUsesRealDigitalAlbumIdAndDetailParser() {
        val cards=parseKgDigitalAlbums(fixture("kg-digital"),12)
        assertEquals(12,cards.size)
        assertEquals("211408559",cards.first().id)
        assertTrue(cards.all { it.kind=="digital" && it.source=="kg" })
        val detail=parseKgAlbumDetail(fixture("kg-digital-meta"),fixture("kg-digital-songs"),cards.first().id)
        assertEquals(cards.first().name,detail.name)
        assertTrue(detail.songs.isNotEmpty())
        assertTrue(detail.songs.all { it.source=="kg" })
        assertTrue(parseKgDigitalAlbums("{}",12).isEmpty())
    }
    @Test fun kuwoArtistAlbumsAreNotSilentlyUnsupported() {
        val albums = parseKwArtistAlbums(fixture("kw-artist"))
        assertTrue(albums.isNotEmpty())
        assertEquals("87758985", albums.first().id)
        assertTrue(albums.all { it.source == "kw" && it.kind == "album" })
    }
    @Test fun miguArtistCatalogPreservesOrdinaryAndDigitalRoutes() {
        val albums = parseMgArtistAlbums(fixture("mg-artist"))
        assertTrue(albums.any { it.id == "600927015009001083" && it.kind == "digital" })
        assertTrue(albums.any { it.id == "1141456456" && it.kind == "album" })
        assertTrue(albums.all { it.source == "mg" })
    }
    @Test fun qqDigitalCatalogUsesAlbumMidAndKeepsPlatform() {
        val cards = parseTxDigitalAlbums(fixture("qq-digital"), 12)
        assertEquals(12, cards.size)
        assertEquals("001PwPDF0qexPG", cards.first().id)
        assertTrue(cards.all { it.kind == "digital" && it.source == "tx" && it.songId.isEmpty() })
    }
    @Test fun miguNewCdIsNotMislabelledAsDigitalOrNewSong() {
        val cards = parseMgNewAlbums(fixture("mg-newcd-data"), 12)
        assertEquals(12, cards.size)
        assertEquals("1144645820", cards.first().id)
        assertTrue(cards.all { it.kind == "new_album" && it.source == "mg" })
    }
    @Test fun originalThreeArtistPlatformsStillParse() {
        assertTrue(parseTxArtistAlbums(fixture("qq-artist")).isNotEmpty())
        assertTrue(parseWyArtistAlbums(fixture("wy-artist")).isNotEmpty())
        assertTrue(parseKgArtistAlbums(fixture("kg-artist")).isNotEmpty())
    }
    @Test fun mgAlbumAndDigitalSongEnvelopesRetainIdentityAndDurationMs() {
        val ordinary = parseMgPlaylistDetail(fixture("mg-album-songs"))
        val digital = parseMgPlaylistDetail(fixture("mg-digital-songs"))
        assertEquals("1110097201", ordinary.first().albumId)
        assertEquals(245000L, ordinary.first().duration)
        assertEquals("6902739Z0BT", digital.first().copyrightId)
        assertEquals(297000L, digital.first().duration)
    }
    @Test fun artistSecondPagesContainNewAlbums() {
        val kw = parseKwArtistAlbums(fixture("kw-artist")).map { it.id }.toSet()
        assertTrue(parseKwArtistAlbums(fixture("kw-artist-page2")).any { it.id !in kw })
        val mg = parseMgArtistAlbums(fixture("mg-artist")).map { it.id }.toSet()
        assertTrue(parseMgArtistAlbums(fixture("mg-artist-page2")).any { it.id !in mg })
    }
    @Test fun digitalCatalogCardsOpenRealAlbumSongData() {
        assertTrue(parseTxAlbumDetail(fixture("qq-digital-detail"), "001PwPDF0qexPG").songs.isNotEmpty())
        val mg = parseMgAlbumDetail(fixture("mg-digital-meta"), fixture("mg-digital-songs"))
        assertEquals("太阳之子", mg.name)
        assertTrue(mg.cover.isNotBlank())
        assertTrue(mg.songs.isNotEmpty())
    }
    @Test fun malformedCatalogsProduceNoInventedRecords() {
        assertTrue(parseMgArtistAlbums("{}").isEmpty())
        assertTrue(parseKwArtistAlbums("{}").isEmpty())
        assertTrue(parseTxDigitalAlbums("{}", 12).isEmpty())
        assertTrue(parseMgNewAlbums("{}", 12).isEmpty())
    }
}
