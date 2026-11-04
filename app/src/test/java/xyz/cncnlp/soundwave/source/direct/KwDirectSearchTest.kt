package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.AlbumCoverBackfill

class KwDirectSearchTest {

    @Test
    fun `parses r-s fields and uses only the explicit album cover`() {
        val raw = """
        {"abslist":[
          {"SONGNAME":"晴天","ARTIST":"周杰伦","MUSICRID":"MUSIC_186016",
           "ALBUM":"叶惠美","DURATION":"240",
           "web_albumpic_short":"http://img4.kuwo.cn/star/albumcover/120/95/1.jpg"},
          {"SONGNAME":"七里香","ARTIST":"周杰伦","DC_TARGETID":"77",
           "ALBUM":"七里香","DURATION":"0","hts_MVPIC":"/300/2/2.jpg",
           "web_artistpic_short":"https://img1.kuwo.cn/star/artist.jpg"},
          {"NAME":"编号曲","ARTIST":"路人","rid":"55","PICPATH":"pic-not-slash",
           "ALBUMID":"6604395"}
        ]}
        """.trimIndent()
        val songs = parseKwSearch(raw, "晴天")
        assertEquals(3, songs.size)
        val first = songs[0]
        assertEquals("186016", first.id)
        assertEquals("晴天", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("kw", first.source)
        assertEquals("叶惠美", first.album)
        assertEquals(240000L, first.duration)
        assertEquals("http://img4.kuwo.cn/star/albumcover/120/95/1.jpg", first.cover)
        assertEquals("77", songs[1].id)
        assertEquals("", songs[1].cover)
        assertEquals("55", songs[2].id)
        assertEquals("", songs[2].cover)
        // ALBUMID 只进元数据：无 ALBUMID 的条目 albumId 为空，有值的原样带出。
        assertEquals("", songs[0].albumId)
        assertEquals("", songs[1].albumId)
        assertEquals("6604395", songs[2].albumId)
    }

    @Test
    fun `r-s strips dirty rid without guessing a cover from album id`() {
        val raw = """
        {"abslist":[
          {"SONGNAME":"月光","ARTIST":"秦岚","rid":"MUSIC_12ab34","ALBUMID":"6604395"}
        ]}
        """.trimIndent()
        val songs = parseKwSearch(raw, "月光")
        assertEquals(1, songs.size)
        assertEquals("1234", songs[0].id)
        assertEquals("", songs[0].cover)
        assertEquals(0L, songs[0].duration)
        // 封面仍不许据 ALBUMID 猜造，但 ID 本身要带出——空封面+真实专辑 ID
        // 恰好是专辑封面回补的资格组合（此前 ID 被丢弃，回补永远够不到 kw 搜索来源）。
        assertEquals("6604395", songs[0].albumId)
        assertEquals("6604395", AlbumCoverBackfill.targetAlbumId(songs[0]))
    }

    @Test
    fun `r-s treats zero album id as missing`() {
        val raw = """
        {"abslist":[
          {"SONGNAME":"单曲","ARTIST":"路人","rid":"88","ALBUMID":"0"}
        ]}
        """.trimIndent()
        val songs = parseKwSearch(raw, "单曲")
        assertEquals(1, songs.size)
        assertEquals("", songs[0].albumId)
    }

    @Test
    fun `r-s drops empty names ids and duplicate rids keeps first`() {
        val raw = """
        {"abslist":[
          {"ARTIST":"x","DC_TARGETID":"1"},
          {"SONGNAME":"no id"},
          {"SONGNAME":"first","DC_TARGETID":"9"},
          {"SONGNAME":"second","DC_TARGETID":"9"}
        ]}
        """.trimIndent()
        val songs = parseKwSearch(raw, "x")
        assertEquals(1, songs.size)
        assertEquals("first", songs[0].name)
        assertEquals("9", songs[0].id)
    }

    @Test
    fun `oiapi reads data-list with or chains and raw cover`() {
        val raw = """
        {"data":{"list":[
          {"song":"晴天","singer":"周杰伦","rid":"MUSIC_186016",
           "picture":"http://img.kuwo.cn/raw.jpg","time":"240","album":"叶惠美"},
          {"name":"七里香","artist":"周杰伦","id":"77","cover":"//img.kuwo.cn/x.jpg","duration":"0"}
        ]}}
        """.trimIndent()
        val songs = parseKwOiapiSearch(raw, "晴天")
        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals("186016", first.id)
        assertEquals("晴天", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("叶惠美", first.album)
        assertEquals(240000L, first.duration)
        assertEquals("http://img.kuwo.cn/raw.jpg", first.cover)
        assertEquals("77", songs[1].id)
        assertEquals("//img.kuwo.cn/x.jpg", songs[1].cover)
    }

    @Test
    fun `oiapi falls back to abslist and accepts bare array data`() {
        val abslist = "{" + "\"data\":{\"abslist\":[{\"song\":\"歌A\",\"rid\":\"1\"}]}}"
        assertEquals(listOf("歌A"), parseKwOiapiSearch(abslist, "歌A").map { it.name })
        val bareData = "{" + "\"data\":[{\"song\":\"歌B\",\"rid\":\"2\"}]}"
        assertEquals(listOf("歌B"), parseKwOiapiSearch(bareData, "歌B").map { it.name })
        assertEquals(0L, parseKwOiapiSearch(bareData, "歌B")[0].duration)
    }

    @Test
    fun `kw blank invalid json and missing data yield empty list`() {
        assertTrue(parseKwSearch("", "kw").isEmpty())
        assertTrue(parseKwSearch("{broken", "kw").isEmpty())
        assertTrue(parseKwSearch("{}", "kw").isEmpty())
        assertTrue(parseKwSearch("{" + "\"abslist\":[]}", "kw").isEmpty())
        assertTrue(parseKwOiapiSearch("", "kw").isEmpty())
        assertTrue(parseKwOiapiSearch("{broken", "kw").isEmpty())
        assertTrue(parseKwOiapiSearch("{}", "kw").isEmpty())
        assertTrue(parseKwOiapiSearch("{" + "\"data\":{}}", "kw").isEmpty())
    }

    @Test
    fun `kw backfills singerId from ARTISTID and treats zero as missing`() {
        val raw = """{"abslist":[{"SONGNAME":"晴天","ARTIST":"周杰伦","ARTISTID":"336391","MUSICRID":"MUSIC_1575792","ALBUM":"叶惠美"}]}"""
        val songs = parseKwSearch(raw, "晴天")
        assertEquals(1, songs.size)
        assertEquals("336391", songs[0].singerId)
        val zero = """{"abslist":[{"SONGNAME":"晴天","ARTIST":"佚名","ARTISTID":"0","MUSICRID":"MUSIC_1575793"}]}"""
        assertEquals("", parseKwSearch(zero, "晴天")[0].singerId)
    }
}
