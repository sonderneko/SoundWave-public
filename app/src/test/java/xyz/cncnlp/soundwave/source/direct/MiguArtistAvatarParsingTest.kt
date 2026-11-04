package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.ArtistAvatarRepository

/** Synthetic provider-schema contracts, not live upstream response captures. */
class MiguArtistAvatarParsingTest {
    @Test fun singerEntityImageReachesVerifiedAvatarRepository() {
        val url = "https://d.musicapp.migu.cn/data/oss/resource/artist-portrait"
        val raw = """{"code":"000000","singerResultData":{"result":[{"id":"123","name":"林","imgItems":[{"img":"https://img.migu.cn/portrait.jp"},{"img":"$url"}]}]}}"""
        for (type in listOf("artist", "singer")) {
            val result = parseOfficialEntities("mg", raw, "林", type)
            assertTrue(result.available)
            assertEquals(url, result.songs.single().cover)
            val avatar = ArtistAvatarRepository.match("mg", "林", result.songs)
            assertEquals("123", avatar?.artistId)
            assertEquals(url, avatar?.url)
        }
    }

    @Test fun missingArtistImageNeverBorrowsSongOrAlbumImage() {
        val raw = """{"code":"000000","singerResultData":{"result":[{"id":"123","name":"林"}]},"songResultData":{"result":[{"id":"song","name":"林","imgItems":[{"img":"https://img.migu.cn/song.jpg"}]}]},"albumResultData":{"result":[{"id":"album","name":"林","imgItems":[{"img":"https://img.migu.cn/album.jpg"}]}]}}"""
        val result = parseOfficialEntities("mg", raw, "林", "artist")
        assertTrue(result.available)
        assertEquals("", result.songs.single().cover)
        assertNull(ArtistAvatarRepository.match("mg", "林", result.songs))
    }

    @Test fun truncatedArtistImageIsNotPassedToImageLoader() {
        val raw = """{"code":"000000","singerResultData":{"result":[{"id":"123","name":"林","imgItems":[{"img":"https://img.migu.cn/portrait.jp"},{"img":"https://img.migu.cn/portrait.web"}]}]}}"""
        val result = parseOfficialEntities("mg", raw, "林", "artist")
        assertTrue(result.available)
        assertEquals("", result.songs.single().cover)
        assertNull(ArtistAvatarRepository.match("mg", "林", result.songs))
    }
}
