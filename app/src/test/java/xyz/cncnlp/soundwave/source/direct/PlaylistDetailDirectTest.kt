package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Song

class PlaylistDetailDirectTest {

    @Test
    fun `parseKwPlaylistDetail maps pl svc fields with cover shrink and fallback keys`() {
        val raw = """
            {"title":"流行","pic":"http://img1.kuwo.cn/star/userpl2015/52/22/177.jpg",
             "musiclist":[
               {"id":"6623032","name":"有个爱你的人不容易","artist":"那英","album":"有个爱你的人不容易",
                "albumpic":"http://img3.kuwo.cn/star/albumcover/120/74/99/284034966.jpg"},
               {"rid":"777","songname":"尾数字 2018","FARTIST":"群星","FALBUM":"合辑","pic":"http://img4.kuwo.cn/a.jpg"},
               {"name":"无id跳过"},
               {"id":"888","FSONGNAME":"文件名兜底","artist":"歌手B","albumid":"123456"}
             ]}
        """.trimIndent()
        val out = parseKwPlaylistDetail(raw)
        assertEquals(3, out.size)
        assertEquals("6623032", out[0].id)
        assertEquals("有个爱你的人不容易", out[0].name)
        assertEquals("那英", out[0].singer)
        assertEquals("有个爱你的人不容易", out[0].album)
        assertEquals("kw", out[0].source)
        assertEquals("https://img3.kuwo.cn/star/albumcover/120/74/99/284034966.jpg", out[0].cover)
        assertEquals("777", out[1].id)
        assertEquals("尾数字 2018", out[1].name)
        assertEquals("群星", out[1].singer)
        assertEquals("合辑", out[1].album)
        assertEquals("888", out[2].id)
        assertEquals("文件名兜底", out[2].name)
        assertEquals("", out[2].cover)
    }

    @Test
    fun `parseTxAiDissInfo maps detail songlist and joins singers with slash separator`() {
        val raw = """
            {"detail":{"data":{"songlist":[
              {"mid":"003","title":"晴天","singer":[{"name":"周杰伦"},{"name":"群星"}],"album":{"mid":"002e","name":"叶惠美"}},
              {"songmid":"004","name":"备选名","singer":[{"name":"A"},{"name":""}],"albummid":"","albumname":""},
              {"title":"无mid跳过"}
            ]}}}
        """.trimIndent()
        val out = parseTxPlaylistDetail(raw)
        assertEquals(2, out.size)
        assertEquals("003", out[0].id)
        assertEquals("晴天", out[0].name)
        assertEquals("周杰伦 / 群星", out[0].singer)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000002e.jpg", out[0].cover)
        assertEquals("叶惠美", out[0].album)
        assertEquals("004", out[1].id)
        assertEquals("备选名", out[1].name)
        assertEquals("A / ", out[1].singer)
        assertEquals("", out[1].cover)
    }

    @Test
    fun `parseMgPlaylistDetail prefers songId and prefixes relative covers`() {
        val raw = """
            {"data":{"songList":[
              {"songId":"6845","contentId":"600902000002560500","songName":"情非得已",
               "singerList":[{"name":"庾澄庆"}],"img1":"/data/oss/resource/00/5y/yp/bbd337cb0e4743e29724d876","album":"海啸"},
              {"contentId":"600902000002560501","songName":"无songId回退","singerList":[],"img2":"/x.jpg"},
              {"songName":"无id跳过"}
            ]}}
        """.trimIndent()
        val out = parseMgPlaylistDetail(raw)
        assertEquals(2, out.size)
        assertEquals("6845", out[0].id)
        assertEquals("情非得已", out[0].name)
        assertEquals("庾澄庆", out[0].singer)
        assertEquals("https://d.musicapp.migu.cn/data/oss/resource/00/5y/yp/bbd337cb0e4743e29724d876", out[0].cover)
        assertEquals("海啸", out[0].album)
        assertEquals("600902000002560501", out[1].id)
        assertEquals("https://d.musicapp.migu.cn/x.jpg", out[1].cover)
    }

    @Test
    fun `parseKgPlaylistDetail splits filename and uppercases hash`() {
        val raw = """
            {"data":{"info":[
              {"hash":"abc123","filename":"歌手A - 歌名B - 副标题","album_id":"205415784"},
              {"hash":"def456","filename":"无分隔符"},
              {"filename":"无hash跳过"}
            ]}}
        """.trimIndent()
        val out = parseKgPlaylistDetail(raw)
        assertEquals(1, out.size)
        assertEquals("ABC123", out[0].id)
        assertEquals("ABC123", out[0].hash)
        assertEquals("歌手A", out[0].singer)
        assertEquals("歌名B - 副标题", out[0].name)
        assertEquals("205415784", out[0].albumId)
        assertEquals("kg", out[0].source)
    }

    @Test
    fun `normalizeDetailSongs dedups by id source and trims trailing digits`() {
        val songs = listOf(
            Song("1", "歌名 12", "a", "wy"),
            Song("1", "歌名 34", "a", "wy"),
            Song("2", "纯数字名尾 2018", "b", "kw"),
            Song("", "无id丢弃", "c", "tx"),
            Song("3", "字母结尾 no", "d", "mg"),
            Song("4", "1989", "e", "wy"),
        )
        val out = normalizeDetailSongs(songs)
        assertEquals(4, out.size)
        assertEquals("歌名", out[0].name)
        assertEquals("纯数字名尾", out[1].name)
        assertEquals("字母结尾 no", out[2].name)
        assertEquals("1989", out[3].name)
    }

    @Test
    fun `parsers return empty on blank or malformed input`() {
        assertEquals(0, parseKwPlaylistDetail("").size)
        assertEquals(0, parseTxPlaylistDetail("not json").size)
        assertEquals(0, parseMgPlaylistDetail("{\"data\":{}}").size)
        assertEquals(0, parseKgPlaylistDetail("{}").size)
    }

    @Test
    fun `tx playlist detail url embeds dissid and url-encodes data json`() {
        val url = txPlaylistDetailUrl("8662293298")
        assertTrue(url.startsWith("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="))
        assertTrue(url.contains("uniform_get_Dissinfo"))
        assertTrue(url.contains("8662293298"))
        assertTrue(url.contains("%7B")) // 编码后的 {
        // 解码后必须含完整 dissid
        val decoded = java.net.URLDecoder.decode(url.substringAfter("data="), "UTF-8")
        assertTrue(decoded.contains("\"disstid\":8662293298"))
        assertTrue(decoded.contains("\"song_num\":500"))
    }

    @Test
    fun `tx playlist detail url handles pid with special chars`() {
        val url = txPlaylistDetailUrl("abc123")
        val decoded = java.net.URLDecoder.decode(url.substringAfter("data="), "UTF-8")
        assertTrue(decoded.contains("\"disstid\":abc123"))
    }

    @Test
    fun `tx playlist metadata uses same source full description and real directory cover`() {
        val raw = """{"code":0,"cdlist":[{"disstid":"3805603854","dissname":"QQ&amp;#160;歌单","nick":"作者&nbsp;甲","songnum":223,"logo":"http://y.gtimg.cn/music/photo_new/T002R300x300M000X.jpg","desc":"第一段<br>完整第二段"}]}"""
        val detail = parseTxPlaylistMetadata(raw, "3805603854")
        assertEquals("QQ 歌单", detail.name)
        assertEquals("作者 甲", detail.author)
        assertEquals("第一段\n完整第二段", detail.description)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000X.jpg", detail.cover)
        assertEquals(223, detail.count)
        assertTrue(detail.songs.isEmpty()) // the legacy response is metadata only
        assertTrue(parseTxPlaylistMetadata(raw, "wrong-id").description.isEmpty())
        assertTrue(txPlaylistMetadataUrl("3805603854").contains("onlysonglist=0&disstid=3805603854"))
        assertTrue(txPlaylistMetadataUrl("3805603854").contains("song_begin=0&song_num=1"))
    }

    @Test
    fun `playlist details backfill singerId from first singer entry`() {
        val txRaw = """
            {"detail":{"data":{"songlist":[
              {"mid":"003","title":"晴天","singer":[{"mid":"001fanJL3oe1wi","name":"周杰伦"},{"mid":"x","name":"群星"}],"album":{"mid":"002e","name":"叶惠美"}}
            ]}}}
        """.trimIndent()
        val txOut = parseTxPlaylistDetail(txRaw)
        assertEquals(1, txOut.size)
        assertEquals("001fanJL3oe1wi", txOut[0].singerId)
        val mgRaw = """
            {"data":{"songList":[
              {"songId":"6845","songName":"情非得已","singerList":[{"id":"112","name":"庾澄庆"}]}
            ]}}
        """.trimIndent()
        val mgOut = parseMgPlaylistDetail(mgRaw)
        assertEquals(1, mgOut.size)
        assertEquals("112", mgOut[0].singerId)
        val kwRaw = """
            {"musiclist":[
              {"id":"1575792","name":"晴天","artist":"周杰伦","artistid":"336391","albumid":"533483"}
            ]}
        """.trimIndent()
        val kwOut = parseKwPlaylistDetail(kwRaw)
        assertEquals(1, kwOut.size)
        assertEquals("336391", kwOut[0].singerId)
        // pl.svc 的 albumid 此前未读，空封面条目够不到专辑封面回补资格。
        assertEquals("533483", kwOut[0].albumId)
    }
}
