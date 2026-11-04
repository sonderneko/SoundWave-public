package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import xyz.cncnlp.soundwave.source.ApiClientCatalogGateway
import xyz.cncnlp.soundwave.source.PlatformSourceAdapter

/** AUTHORED, NOT EXECUTED. Fixtures are exact repository parser-test samples, not live
 * API captures. Envelope transformations below are explicitly synthetic contract cases.
 */
class Step8DirectRegressionTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream("step8/$name.json"))
        .bufferedReader().use { it.readText() }

    @Test fun reusedKuwoRowsParseAsAlbumWithoutGuessingCover() {
        val root = JSONObject(fixture("kw-playlist-reused"))
        root.put("name", root.getString("title")) // Explicit envelope adaptation, no live API claim.
        val detail = parseKwAlbumDetail(root.toString(), "123456")
        assertEquals("流行", detail.name)
        assertEquals("6623032", detail.songs.single().id)
        assertEquals("", detail.songs.single().cover) // albumpic is not the LX album `pic` field.
        assertEquals("123456", detail.songs.single().albumId)
        val metadata = parseAdditionalPlaylistMetadata("kw", fixture("kw-playlist-reused"))
        assertEquals("流行", metadata.name)
        assertEquals("", metadata.author)
        assertTrue(metadata.cover.contains("177.jpg"))
    }

    @Test fun reusedKugouRowsKeepHashAndNeverInventAlbumOrArtistCover() {
        val raw = fixture("kg-playlist-reused")
        val album = parseKgAlbumDetail("", raw, "205415784")
        assertEquals("ABC123", album.songs.single().hash)
        assertEquals("205415784", album.songs.single().albumId)
        assertEquals("", album.cover)
        assertEquals("", album.songs.single().cover)
        val artist = checkNotNull(parseKgArtistDetail("", raw, "9", "歌手A"))
        assertEquals("", artist.cover)
        assertEquals("", artist.description)
        assertEquals("ABC123", artist.songs.single().id)
    }

    @Test fun reusedMiguRowsKeepSongAndContentIdentityAndOssCover() {
        val detail = parseMgAlbumDetail("", fixture("mg-playlist-reused"))
        assertEquals("6845", detail.songs.first().id)
        assertEquals("情非得已", detail.songs.first().name)
        assertEquals("https://d.musicapp.migu.cn/data/oss/resource/00/5y/yp/bbd337cb0e4743e29724d876", detail.songs.first().cover)
        assertEquals("", detail.cover)
    }

    @Test fun reusedQqSongInfoDoesNotBecomeSingerAvatar() {
        val rows = JSONObject(fixture("tx-playlist-reused")).getJSONObject("detail").getJSONObject("data").getJSONArray("songlist")
        val songList = JSONArray()
        for (i in 0 until rows.length()) songList.put(JSONObject().put("songInfo", rows.getJSONObject(i)))
        val root = JSONObject().put("code", 0).put("req_0", JSONObject().put("code", 0)
            .put("data", JSONObject().put("songList", songList)))
        val detail = checkNotNull(parseTxArtistDetail(root.toString(), "9", "周杰伦"))
        assertEquals("003", detail.songs.first().id)
        assertTrue(detail.songs.first().cover.contains("T002"))
        assertEquals("", detail.cover)
        assertEquals("", detail.description)
    }

    @Test fun officialShareExamplesFromLocalLxPreservePlatformAndRemoteId() {
        // Exact comment examples in local LX songList.js, not newly obtained network evidence.
        val examples = mapOf(
            "https://www.kugou.com/yy/special/single/1067062.html" to OfficialPlaylistReference("kg", "1067062"),
            "http://www.kuwo.cn/playlist_detail/2886046289" to OfficialPlaylistReference("kw", "2886046289"),
            "https://m.kuwo.cn/h5app/playlist/2736267853?t=qqfriend" to OfficialPlaylistReference("kw", "2736267853"),
            "https://y.qq.com/n/yqq/playlist/7217720898.html" to OfficialPlaylistReference("tx", "7217720898"),
            "https://music.migu.cn/v5/#/playlist?playlistId=221573417" to OfficialPlaylistReference("mg", "221573417"),
            "https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=184187437&channel=0146921" to OfficialPlaylistReference("mg", "184187437")
        )
        examples.forEach { (input, expected) -> assertEquals(expected, parseOfficialPlaylistReference(input)) }
        assertNull(parseOfficialPlaylistReference("https://evil.invalid/playlist/7217720898"))
        assertNull(parseOfficialPlaylistReference("https://y.qq.com@evil.invalid/playlist/7217720898"))
        assertNull(parseOfficialPlaylistReference("https://music.163.com/album?id=123456"))
        assertNull(parseOfficialPlaylistReference("https://music.163.com/playlist?id=1&id=2"))
        assertNull(parseOfficialPlaylistReference("歌单 7217720898 未给平台链接"))
    }

    @Test fun categoryAndSquareFailuresNeverBecomeSuccessfulEmpty() {
        for (source in listOf("tx", "kw", "kg", "mg")) {
            assertNull(parsePlaylistCategoryGroups(source, "not json"))
            assertNull(parsePlaylistCategoryGroups(source, "{}"))
            assertNull(parseRecommendPlaylistSquare(source, "{}"))
        }
        // Explicit artificial empty envelopes verify status, not real upstream behavior.
        assertEquals(emptyList<Pair<String, List<String>>>(), parsePlaylistCategoryGroups("mg", "{\"code\":\"000000\",\"data\":[]}"))
        assertEquals(emptyList<xyz.cncnlp.soundwave.Playlist>(), parseRecommendPlaylistSquare("kg", "{\"status\":1,\"special_db\":[]}"))
        for (source in listOf("wy", "kw", "kg", "mg")) {
            assertEquals(false, parseOfficialEntities(source, "{}", "", "artist").available)
        }
        assertEquals(true, parseOfficialEntities("wy", "{\"code\":200,\"result\":{\"artists\":[]}}", "", "artist").available)
    }

    @Test fun directQqTypedValidEmptyDoesNotCallLegacyApi() = runBlocking {
        val adapter = PlatformSourceAdapter(ApiClientCatalogGateway, "tx", directTypedSearchResult = { _, _ -> TxTypedSearchResult(emptyList(), true) })
        assertTrue(adapter.searchTyped("无匹配", "artist").isEmpty())
    }
}
