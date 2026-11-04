package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome

class PlaylistDirectSearchTest {

    @Test
    fun `parses wy square playlists with trackCount playCount cover shrink and creator`() {
        val raw = """
        {"playlists":[
          {"name":"华语&amp;#160;速爆新歌","id":123,"trackCount":88,"playCount":123456,
           "coverImgUrl":"http://p1.music.126.net/abc.jpg",
           "creator":{"nickname":"云音乐&nbsp;官方"},"description":"第一段<br>第二段"},
          {"name":"无封面","id":456,"trackCount":10,"playcount":77}
        ]}
        """.trimIndent()
        val lists = parseWyPlaylistSquare(raw)
        assertEquals(2, lists.size)
        val first = lists[0]
        assertEquals("123", first.id)
        assertEquals("华语 速爆新歌", first.name)
        assertEquals("wy", first.source)
        assertEquals(88, first.count)
        assertEquals(123456L, first.playCount)
        assertEquals("云音乐 官方", first.author)
        assertEquals("第一段\n第二段", first.description)
        // MERGE7 对齐（J1）：歌单面封面 300px，不再放大 1080。
        assertEquals("https://p1.music.126.net/abc.jpg?param=300y300", first.cover)
        assertEquals(77L, lists[1].playCount)
        assertEquals("", lists[1].cover)
    }

    @Test
    fun `parses only provider supplied wy catalogue groups and category names`() {
        val raw = """{"code":200,"categories":{"0":"语种","1":"风格","2":"场景","3":"情感","4":"主题"},"sub":[{"name":"华语","category":0},{"name":"欧美","category":0},{"name":"摇滚","category":1},{"name":"夜晚","category":2},{"name":"治愈","category":3},{"name":"影视原声","category":4},{"name":"摇滚","category":1}]}"""
        assertEquals(
            listOf(
                "语种" to listOf("华语", "欧美"),
                "风格" to listOf("摇滚"),
                "场景" to listOf("夜晚"),
                "情感" to listOf("治愈"),
                "主题" to listOf("影视原声")
            ),
            parseWyPlaylistCategoryGroups(raw)
        )
        assertTrue(parseWyPlaylistCategoryGroups("{\"code\":404,\"sub\":[]}").isEmpty())
    }

    @Test
    fun `parses wy keyword playlists with id dedup`() {
        val raw = """
        {"result":{"playlists":[
          {"name":"深夜学习","id":1,"trackCount":30},
          {"name":"深夜学习2","id":1,"trackCount":30},
          {"name":"空id","id":"","trackCount":5}
        ]}}
        """.trimIndent()
        val lists = parseWyPlaylistKeyword(raw)
        assertEquals(1, lists.size)
        assertEquals("深夜学习", lists[0].name)
    }

    @Test
    fun `parses tx jsonp and plain json with title fallback and raw cover`() {
        val jsonp = """callback({"data":{"playlist":{"list":[
          {"title":"热歌榜","id":9001,"total_song_count":30,
           "cover":{"imgurl":"http://y.gtimg.cn/music/photo_new/T003R500x500M000001.jpg"},
           "creator":{"name":"QQ音乐"}}
        ]}}});"""
        val first = parseTxPlaylistSearch(jsonp)
        assertEquals(1, first.size)
        assertEquals("9001", first[0].id)
        assertEquals("热歌榜", first[0].name)
        assertEquals("tx", first[0].source)
        assertEquals(30, first[0].count)
        assertEquals("QQ音乐", first[0].author)
        // 服务端 qq_search_playlist 对 tx 封面原样保留（不 shrink_cover）：http 方案不得被升级
        assertEquals("http://y.gtimg.cn/music/photo_new/T003R500x500M000001.jpg", first[0].cover)

        val plain = """{"data":{"playlist":{"list":[
          {"playlist_name":"老字段","playlist_id":9002,"playlist_cover":"http://y.gtimg.cn/legacy.jpg"}
        ]}}}"""
        val second = parseTxPlaylistSearch(plain)
        assertEquals(1, second.size)
        assertEquals("9002", second[0].id)
        assertEquals("老字段", second[0].name)
        assertEquals("http://y.gtimg.cn/legacy.jpg", second[0].cover)
        assertEquals("", second[0].author)
    }

    @Test
    fun `decodes qq playlist title and author html entities exactly once`() {
        val raw = """{"data":{"list":[{"dissid":"1234","dissname":"精选&#32;&#124;&#32;R&#x26;B &amp; Pop","creator":{"name":"A&#32;B &quot;主理人&quot;"},"desc":"&#x4F18;&#x9009;"}]}}"""
        val playlist = parseTxPlaylistSearch(raw).single()
        assertEquals("精选 | R&B & Pop", playlist.name)
        assertEquals("A B \"主理人\"", playlist.author)
        assertEquals("优选", playlist.description)
    }

    @Test
    fun `maps qq live playlist introduction entities without borrowing another field`() {
        val raw = """{"data":{"list":[{"dissid":"3805603854","dissname":"精选&#32;歌单","imgurl":"https://p.qpic.cn/music_cover/real/600","creator":{"name":"A&#32;B"},"introduction":"真实&#32;出处 &amp; 主题","song_count":223}]}}"""
        val playlist = parseTxPlaylistSearch(raw).single()
        assertEquals("3805603854", playlist.id)
        assertEquals("精选 歌单", playlist.name)
        assertEquals("A B", playlist.author)
        assertEquals("真实 出处 & 主题", playlist.description)
        assertEquals(223, playlist.count)
        assertEquals("https://p.qpic.cn/music_cover/real/600", playlist.cover)
    }

    @Test
    fun `qq playlist status distinguishes valid empty list from malformed response`() {
        val empty = parseTxPlaylistSearchStatus("""{"data":{"list":[]}}""")
        assertTrue(empty.available)
        assertTrue(empty.playlists.isEmpty())
        val error = parseTxPlaylistSearchStatus("not-json")
        assertFalse(error.available)
        assertTrue(error.playlists.isEmpty())
    }

    @Test
    fun `category direct parsers distinguish valid empty provider lists from malformed responses`() {
        val validEmpty = listOf(
            parseWyPlaylistSquareOutcome("""{"code":200,"playlists":[]}"""),
            parseWyPlaylistKeywordOutcome("""{"code":200,"result":{"playlists":[]}}"""),
            parseKgPlaylistSearchOutcome("""{"data":{"info":[]}}"""),
            parseKwPlaylistSearchOutcome("""{"abslist":[]}"""),
            parseMgPlaylistSearchOutcome("""{"songListResultData":{"result":[]}}""")
        )
        val malformed = listOf(
            parseWyPlaylistSquareOutcome("not-json"),
            parseWyPlaylistKeywordOutcome("not-json"),
            parseKgPlaylistSearchOutcome("not-json"),
            parseKwPlaylistSearchOutcome("not-json"),
            parseMgPlaylistSearchOutcome("not-json")
        )

        assertTrue(validEmpty.all { it is PlaylistSearchOutcome.Success && it.playlists.isEmpty() })
        assertTrue(malformed.all { it == PlaylistSearchOutcome.Failure })
    }

    @Test
    fun `parses kg special with size placeholder and songcount`() {
        val raw = """
        {"data":{"info":[
          {"specialname":"网络&#32;红歌","specialid":5566,"songcount":66,
           "imgurl":"http://imge.kugou.com/stdmusic/{size}/20210101/abc.jpg","nickname":"酷狗&nbsp;精选","description":"歌单&amp;#160;介绍"}
        ]}}
        """.trimIndent()
        val lists = parseKgPlaylistSearch(raw)
        assertEquals(1, lists.size)
        assertEquals("5566", lists[0].id)
        assertEquals("网络红歌", lists[0].name)
        assertEquals("kg", lists[0].source)
        assertEquals(66, lists[0].count)
        assertEquals("酷狗 精选", lists[0].author)
        assertEquals("歌单 介绍", lists[0].description)
        assertEquals("https://imge.kugou.com/stdmusic/1080/20210101/abc.jpg", lists[0].cover)
    }

    @Test
    fun `parses kw abslist with DC_TARGETID fallback string songnum and cover shrink`() {
        val raw = """
        {"abslist":[
          {"name":"华语新歌","DC_TARGETID":"777","songnum":"18",
           "hts_pic":"http://img4.kuwo.cn/star/albumcover/150/45/low.jpg",
           "pic":"http://img4.kuwo.cn/star/albumcover/240/46/playlist.jpg","nickname":"酷我音乐"},
          {"name":"无pid","songnum":"0"}
        ]}
        """.trimIndent()
        val lists = parseKwPlaylistSearch(raw)
        assertEquals(1, lists.size)
        assertEquals("777", lists[0].id)
        assertEquals(18, lists[0].count)
        assertEquals("kw", lists[0].source)
        assertEquals("酷我音乐", lists[0].author)
        assertEquals("https://img4.kuwo.cn/star/albumcover/240/46/playlist.jpg", lists[0].cover)
    }

    @Test
    fun `kuwo playlist cover upgrades only verified user playlist path and decodes full intro`() {
        val raw = """{"abslist":[{"playlistid":"888","name":"真实&amp;#160;歌单","songnum":"7","pic":"http://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_240.jpg","description":"第一段&amp;lt;br&amp;gt;第二段"}]}"""
        val playlist = parseKwPlaylistSearch(raw).single()
        assertEquals("真实 歌单", playlist.name)
        assertEquals("第一段\n第二段", playlist.description)
        assertEquals("http://img1.kwcdn.kuwo.cn/star/userpl2015/81/23/1568684821020_182253281_500.jpg", playlist.cover)
    }

    @Test
    fun `native page range uses bounded one based page and offset`() {
        assertEquals(PlaylistSearchPage(page = 1, limit = 20, offset = 0), playlistSearchPage(page = 0, limit = 20))
        assertEquals(PlaylistSearchPage(page = 2, limit = 20, offset = 20), playlistSearchPage(page = 2, limit = 20))
        assertEquals(PlaylistSearchPage(page = 10_000, limit = 200, offset = 1_999_800), playlistSearchPage(page = Int.MAX_VALUE, limit = 500))
    }

    @Test
    fun `kuwo playlist albumcover 320 variant upgrades only same modern resource path`() {
        val raw = """{"abslist":[{"playlistid":"3658058270","name":"实测歌单","pic":"http://img1.kwcdn.kuwo.cn/star/albumcover/320/s4s49/71/370021123.jpg"}]}"""
        val playlist = parseKwPlaylistSearch(raw).single()
        assertEquals("3658058270", playlist.id)
        // MERGE7 对齐（J1）：目标 300<500 时取已验证的 500 档（normalizeCoverUrl 档位规则），不再升 1080。
        assertEquals("http://img1.kwcdn.kuwo.cn/star/albumcover/500/s4s49/71/370021123.jpg", playlist.cover)
    }

    @Test
    fun `parses mg playlist result with musicNum and creator empty`() {
        val raw = """
        {"songListResultData":{"result":[
          {"name":"咪咕首选","id":"m100","musicNum":42,"description":"咪咕&amp;#160;来源简介",
           "musicListPicUrl":"http://img.cmvideo.cn/pics/abc.jpg"}
        ]}}
        """.trimIndent()
        val lists = parseMgPlaylistSearch(raw)
        assertEquals(1, lists.size)
        assertEquals("m100", lists[0].id)
        assertEquals("咪咕首选", lists[0].name)
        assertEquals("mg", lists[0].source)
        assertEquals(42, lists[0].count)
        assertEquals("", lists[0].author)
        assertEquals("咪咕 来源简介", lists[0].description)
        assertEquals("https://img.cmvideo.cn/pics/abc.jpg", lists[0].cover)
    }

    @Test
    fun `normalize strips trailing digits with fallback and dedups by id-src`() {
        val lists = listOf(
            Playlist(id = "1", name = "华语热歌 3", source = "wy", count = 30, playCount = 9L),
            Playlist(id = "1", name = "华语热歌 3", source = "wy", count = 30, playCount = 9L),
            Playlist(id = "1", name = "同名跨平台不去重", source = "kw"),
            Playlist(id = "2", name = "2024", source = "kw"),
            Playlist(id = "", name = "无名", source = "mg"),
        )
        val out = normalizePlaylists(lists)
        assertEquals(3, out.size)
        assertEquals("华语热歌", out[0].name)
        assertEquals(30, out[0].count)
        assertEquals(9L, out[0].playCount)
        assertEquals("同名跨平台不去重", out[1].name)
        // 尾数字清洗后为空 → 回原名（服务端 `or p.get('name')`）
        assertEquals("2024", out[2].name)
    }

    @Test
    fun `parsers degrade to empty list on malformed input`() {
        assertTrue(parseWyPlaylistSquare("").isEmpty())
        assertTrue(parseWyPlaylistKeyword("not json").isEmpty())
        assertTrue(parseTxPlaylistSearch("callback(broken").isEmpty())
        assertTrue(parseKgPlaylistSearch("{").isEmpty())
        assertTrue(parseKwPlaylistSearch("[]").isEmpty())
        assertTrue(parseMgPlaylistSearch("{\"data\":1}").isEmpty())
    }
}
