package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KgDirectSearchTest {

    @Test
    fun `parses song_search_v2 lists with hash id cover chain and album`() {
        val raw = """
        {"data":{"lists":[
          {"SongName":"晴天","SingerName":"周杰伦","FileHash":"ABC123",
           "AlbumID":"9901","AlbumName":"叶惠美",
           "AlbumImage":"//imge.kugou.com/stdmusic/150/abc.jpg"},
          {"SongName":"七里香","SingerName":"周杰伦","HQFileHash":"DEF456",
           "AlbumId":"9902","AlbumName":"七里香",
           "Image":"http://imge.kugou.com/stdmusic/240/def.jpg"}
        ]}}
        """.trimIndent()
        val songs = parseKgSearch(raw, "晴天")
        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals("ABC123", first.id)
        assertEquals("晴天", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("kg", first.source)
        assertEquals("叶惠美", first.album)
        assertEquals("9901", first.albumId)
        assertEquals("ABC123", first.hash)
        assertEquals("https://imge.kugou.com/stdmusic/1080/abc.jpg", first.cover)
        assertEquals("DEF456", songs[1].id)
        assertEquals("https://imge.kugou.com/stdmusic/1080/def.jpg", songs[1].cover)
    }

    // ACCEPT5（C）：搜索结果的 Duration/duration（秒）必须进 Song.duration
    // （毫秒）——跨源严配的时长证据；两条解析（v2 lists 与 mobilecdn 兜底）都钉。
    @Test
    fun `kg search Duration seconds become song duration`() {
        val raw = """
        {"data":{"lists":[
          {"SongName":"海阔天空","SingerName":"Beyond","FileHash":"ABC123","Duration":326},
          {"SongName":"无时长","SingerName":"x","FileHash":"DEF456"}
        ]}}
        """.trimIndent()
        val songs = parseKgSearch(raw, "海阔天空")
        assertEquals(2, songs.size)
        assertEquals(326_000L, songs.first { it.id == "ABC123" }.duration)
        assertEquals(0L, songs.first { it.id == "DEF456" }.duration)
    }

    @Test
    fun `kg mobilecdn fallback duration seconds become song duration`() {
        val raw = """
        {"data":{"info":[
          {"songname":"海阔天空","singername":"Beyond","hash":"ABC123","duration":324}
        ]}}
        """.trimIndent()
        val songs = parseKgMobileCdnFallback(raw, "海阔天空")
        assertEquals(1, songs.size)
        assertEquals(324_000L, songs[0].duration)
    }

    @Test
    fun `kg search drops entries without name or hash`() {
        val raw = """
        {"data":{"lists":[
          {"SingerName":"x","FileHash":"no-name"},
          {"SongName":"no hash"},
          {"SongName":"ok","FileHash":"H1"}
        ]}}
        """.trimIndent()
        val songs = parseKgSearch(raw, "ok")
        assertEquals(1, songs.size)
        assertEquals("H1", songs[0].id)
    }

    @Test
    fun `mobilecdn fallback reads info with hash chain and union cover`() {
        val raw = """
        {"data":{"info":[
          {"songname":"晴天","singername":"周杰伦","hash":"abc123","album_id":"66",
           "album_img":"http://imge.kugou.com/stdmusic/120/a.jpg","album_name":"叶惠美"},
          {"songname":"夜曲","singername":"周杰伦","320hash":"deadbeef","album_id":"67",
           "trans_param":{"union_cover":"//imge.kugou.com/stdmusic/150/union.jpg"}},
          {"songname":"无图","singername":"路人","sqhash":"ff00","album_id":"68",
           "trans_param":{"img":"not-a-url"}}
        ]}}
        """.trimIndent()
        val songs = parseKgMobileCdnFallback(raw, "周杰伦")
        assertEquals(3, songs.size)
        val first = songs[0]
        assertEquals("ABC123", first.id)
        assertEquals("https://imge.kugou.com/stdmusic/1080/a.jpg", first.cover)
        assertEquals("66", first.albumId)
        assertEquals("叶惠美", first.album)
        assertEquals("DEADBEEF", songs[1].id)
        assertEquals("https://imge.kugou.com/stdmusic/1080/union.jpg", songs[1].cover)
        assertEquals("FF00", songs[2].id)
        assertEquals("not-a-url", songs[2].cover)
    }

    @Test
    fun `kg blank invalid json and missing data containers yield empty list`() {
        assertTrue(parseKgSearch("", "kw").isEmpty())
        assertTrue(parseKgSearch("{broken", "kw").isEmpty())
        assertTrue(parseKgSearch("{}", "kw").isEmpty())
        assertTrue(parseKgSearch("{" + "\"data\":{}}", "kw").isEmpty())
        assertTrue(parseKgMobileCdnFallback("", "kw").isEmpty())
        assertTrue(parseKgMobileCdnFallback("{broken", "kw").isEmpty())
        assertTrue(parseKgMobileCdnFallback("{}", "kw").isEmpty())
        assertTrue(parseKgMobileCdnFallback("{" + "\"data\":{}}", "kw").isEmpty())
    }

    @Test
    fun `kg backfills singerId when payload carries it and leaves empty otherwise`() {
        val v2 = """{"data":{"lists":[{"SongName":"歌","SingerName":"歌手","SingerID":"7788","FileHash":"ABC123"}]}}"""
        val v2Songs = parseKgSearch(v2, "歌")
        assertEquals(1, v2Songs.size)
        assertEquals("7788", v2Songs[0].singerId)
        val v2NoId = """{"data":{"lists":[{"SongName":"歌","SingerName":"歌手","FileHash":"ABC123"}]}}"""
        assertEquals("", parseKgSearch(v2NoId, "歌")[0].singerId)
        val v3 = """{"data":{"info":[{"songname":"歌","singername":"歌手","singerid":"9900","hash":"def456"}]}}"""
        val v3Songs = parseKgMobileCdnFallback(v3, "歌")
        assertEquals(1, v3Songs.size)
        assertEquals("9900", v3Songs[0].singerId)
    }
}
