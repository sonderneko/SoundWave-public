package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.*
import org.junit.Test

class MiguDataIdentityTest {
    private val search = """{"code":"000000","songResultData":{"result":[{"contentId":"600902000006889366","copyrightId":"60054701923","id":"3790007","name":"晴天","albums":[{"id":"1234","name":"叶惠美"}]}]}}"""
    private val board = """{"code":"000000","columnInfo":{"columnTitle":"榜单","contentsCount":1,"contents":[{"objectInfo":{"contentId":"600902000006889366","copyrightId":"60054701923","songId":"3790007","songName":"晴天","albumId":"1234"}}]}}"""
    private val playlist = """{"code":"000000","data":{"songList":[{"contentId":"600902000006889366","copyrightId":"60054701923","songId":"3790007","songName":"晴天","albumId":"1234"}]}}"""

    @Test fun searchKeepsStableIdAndSeparateScriptSongId() {
        val song = parseMgSearch(search, "晴天").single()
        assertEquals("600902000006889366", song.id)
        assertEquals("60054701923", song.copyrightId)
        assertEquals("3790007", song.miguSongId)
        assertEquals("1234", song.albumId)
    }
    @Test fun rankKeepsStableIdAndSeparateScriptSongId() {
        val song = parseMgBoard(board, 10).single()
        assertEquals("600902000006889366", song.id)
        assertEquals("3790007", song.miguSongId)
        assertEquals("1234", song.albumId)
    }
    @Test fun playlistKeepsExistingSongIdAndAlbumId() {
        val song = parseMgPlaylistDetail(playlist).single()
        assertEquals("3790007", song.id)
        assertEquals("3790007", song.miguSongId)
        assertEquals("1234", song.albumId)
    }
    @Test fun missingRealSongIdNeverCopiesContentOrCopyrightId() {
        val song = parseMgSearch(search.replace("\"id\":\"3790007\",", ""), "晴天").single()
        assertEquals("600902000006889366", song.id)
        assertEquals("", song.miguSongId)
    }
    @Test fun badCodesCannotBlockFallbackWithStalePayload() {
        assertTrue(parseMgSearch(search.replace("000000", "299996"), "晴天").isEmpty())
        assertTrue(parseMgPlaylistDetail(playlist.replace("000000", "299996")).isEmpty())
        assertTrue(parseMgBoard(board.replace("000000", "302001"), 10).isEmpty())
    }
}
