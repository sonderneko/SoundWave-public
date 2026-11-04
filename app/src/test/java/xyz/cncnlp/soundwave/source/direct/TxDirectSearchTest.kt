package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.source.ApiClientCatalogGateway
import xyz.cncnlp.soundwave.source.PlatformSourceAdapter

class TxDirectSearchTest {

    @Test
    fun `zzc sign matches reference vectors`() {
        // Vectors cross-checked against the reference (LX) algorithm implementation.
        assertEquals("zzc656dbd9mmno0ruwk4fjzu9d8zrdsjjpeub1469c9b", txZzcSign("soundwave"))
        assertEquals("zzcf0e03e5gx4qeiq5cfgdyqwu7sdqfsb5fro3aa45053", txZzcSign(""))
        assertEquals("zzc7746109xq501htmv4ipz7c8owlxfpihjm1fd695c7", txZzcSign("{\"a\":1}"))
        // Deterministic and body-sensitive: the gateway rejects unsigned/mismatched bodies.
        assertEquals(txZzcSign("周杰伦"), txZzcSign("周杰伦"))
        assertTrue(txZzcSign("周杰伦") != txZzcSign("周杰伦 "))
        assertTrue(txZzcSign("周杰伦").startsWith("zzc"))
    }

    @Test
    fun `parses client_search list with songmid and album cover build`() {
        val raw = """
        {"data":{"song":{"list":[
          {"songname":"晴天","singer":[{"name":"周杰伦"}],"songmid":"mid001",
           "albummid":"abcd1234","albumname":"叶惠美","albumid":"9901"},
          {"name":"七里香","singer":[{"name":"周杰伦"},{"name":"费玉清"}],"media_mid":"mid002",
           "albummid":"efgh5678","albumname":"七里香","albumid":"9902"}
        ]}}}
        """.trimIndent()
        val songs = parseTxSearch(raw, "晴天")
        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals("mid001", first.id)
        assertEquals("tx", first.source)
        assertEquals("晴天", first.name)
        assertEquals("周杰伦", first.singer)
        assertEquals("叶惠美", first.album)
        assertEquals("9901", first.albumId)
        assertEquals(0L, first.duration)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000abcd1234.jpg", first.cover)
        val second = songs[1]
        assertEquals("mid002", second.id)
        assertEquals("周杰伦,费玉清", second.singer)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000efgh5678.jpg", second.cover)
    }

    @Test
    fun `tx falls back to mid-songid chain and empty albummid leaves cover empty`() {
        val raw = """
        {"data":{"song":{"list":[
          {"songname":"说好的幸福呢","singer":[{"name":"周杰伦"}],"mid":"midxyz","albummid":""},
          {"songname":"搁浅","singer":[],"songid":"315999","albummid":"zzzz"}
        ]}}}
        """.trimIndent()
        val songs = parseTxSearch(raw, "周杰伦")
        assertEquals(2, songs.size)
        assertEquals("midxyz", songs[0].id)
        assertEquals("", songs[0].cover)
        assertEquals("", songs[1].singer)
        assertEquals("315999", songs[1].id)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000zzzz.jpg", songs[1].cover)
    }

    @Test
    fun `tx slices before filtering and keeps parse order`() {
        val raw = """
        {"data":{"song":{"list":[
          {"songname":"missing id"},
          {"songname":"歌二","songmid":"m2"},
          {"songname":"歌三","songmid":"m3"}
        ]}}}
        """.trimIndent()
        val songs = parseTxSearch(raw, "歌", 2)
        assertEquals(listOf("歌二"), songs.map { it.name })
        val all = parseTxSearch(raw, "歌", 3)
        assertEquals(listOf("歌二", "歌三"), all.map { it.name })
    }

    @Test
    fun `tx blank invalid json and empty containers yield empty list`() {
        assertTrue(parseTxSearch("", "kw").isEmpty())
        assertTrue(parseTxSearch("{broken", "kw").isEmpty())
        assertTrue(parseTxSearch("{}", "kw").isEmpty())
        assertTrue(parseTxSearch("{" + "\"data\":{}}", "kw").isEmpty())
        assertTrue(parseTxSearch("{" + "\"data\":{\"song\":{}}}", "kw").isEmpty())
        assertTrue(parseTxSearch("{" + "\"data\":{\"song\":{\"list\":[]}}}", "kw").isEmpty())
    }

    @Test
    fun `musicu song results map real QQ songmid album mid media mid and duration`() {
        val raw = """{"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[{"mid":"songmid1","name":"稻香","singer":[{"mid":"singer01","name":"周杰伦"}],"album":{"mid":"album01","name":"魔杰座"},"file":{"media_mid":"media01"},"interval":180}]}}}}}"""
        val songs = parseTxMusicuSearch(raw, "稻香", "song", 10)
        assertEquals(1, songs.size)
        assertEquals("songmid1", songs[0].id)
        assertEquals("稻香", songs[0].name)
        assertEquals("周杰伦", songs[0].singer)
        assertEquals("魔杰座", songs[0].album)
        assertEquals("album01", songs[0].albumId)
        assertEquals("media01", songs[0].mediaMid)
        assertEquals(180000L, songs[0].duration)
        assertEquals("tx", songs[0].source)
        assertEquals("https://y.qq.com/music/photo_new/T002R800x800M000album01.jpg", songs[0].cover)
    }

    @Test
    fun `musicu singer and album modes only read their matching response arrays`() {
        val singerRaw = """{"music.search.SearchCgiService":{"code":0,"data":{"body":{"singer":{"list":[{"singerMID":"singer01","singerName":"周杰伦","singerPic":"https://y.gtimg.cn/singer.jpg"}]}}}}}"""
        val singer = parseTxMusicuSearch(singerRaw, "周杰伦", "artist").single()
        assertEquals("singer01", singer.id)
        assertEquals("周杰伦", singer.name)
        assertEquals("tx", singer.source)
        assertEquals("https://y.gtimg.cn/singer.jpg", singer.cover)
        assertTrue(parseTxMusicuSearch(singerRaw, "周杰伦", "album").isEmpty())

        val albumRaw = """{"music.search.SearchCgiService":{"code":0,"data":{"body":{"album":{"list":[{"albumMid":"album01","albumName":"叶惠美","singerName":"周杰伦","albumPic":"https://y.qq.com/album.jpg"}]}}}}}"""
        val album = parseTxMusicuSearch(albumRaw, "叶惠美", "album").single()
        assertEquals("album01", album.id)
        assertEquals("album01", album.albumId)
        assertEquals("叶惠美", album.name)
        assertEquals("周杰伦", album.singer)
        assertEquals("https://y.qq.com/album.jpg", album.cover)
    }

    @Test
    fun `musicu nonzero upstream code is not presented as QQ search data`() {
        val error = """{"music.search.SearchCgiService":{"code":2001,"data":{"body":{"song":{"list":[{"mid":"fake","name":"不可展示"}]}}}}}"""
        assertTrue(parseTxMusicuSearch(error, "关键词", "song").isEmpty())
        assertTrue(parseTxMusicuSearch("{}", "关键词", "artist").isEmpty())
    }

    @Test
    fun `musicu distinguishes a valid no-match response from an upstream error`() {
        val noMatches = """{"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[]}}}}}"""
        val success = parseTxMusicuSearchStatus(noMatches, "no-such-song", "song")
        assertTrue(success.available)
        assertTrue(success.songs.isEmpty())
        val failed = parseTxMusicuSearchStatus("{}", "keyword", "song")
        assertFalse(failed.available)
        assertTrue(failed.songs.isEmpty())
    }

    @Test
    fun `registry adapter preserves QQ typed result list and availability`() = runBlocking {
        val song = Song(id = "song-mid", name = "歌曲", singer = "歌手", source = "tx")
        val successfulEmpty = TxTypedSearchResult(emptyList(), available = true)
        val unavailable = TxTypedSearchResult(emptyList(), available = false)
        val successfulResult = TxTypedSearchResult(listOf(song), available = true)
        val observed = mutableListOf<Pair<String, String>>()
        val results = listOf(successfulEmpty, unavailable, successfulResult).iterator()
        val adapter = PlatformSourceAdapter(
            api = ApiClientCatalogGateway,
            platform = "tx",
            directTypedSearchResult = { keyword, type ->
                observed += keyword to type
                results.next()
            },
        )

        assertSame(successfulEmpty, adapter.searchTypedResult("无匹配", "artist"))
        assertSame(unavailable, adapter.searchTypedResult("请求失败", "album"))
        assertSame(successfulResult, adapter.searchTypedResult("有结果", "artist"))
        assertEquals(listOf("无匹配" to "artist", "请求失败" to "album", "有结果" to "artist"), observed)
    }

    @Test
    fun `musicu song backfills singerId from first singer mid`() {
        val raw = """{"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[{"mid":"songmid1","name":"稻香","singer":[{"mid":"001fanJL3oe1wi","name":"周杰伦"},{"mid":"other","name":"合唱团"}],"album":{"mid":"album01","name":"魔杰座"},"file":{"media_mid":"media01"},"interval":180}]}}}}}"""
        val songs = parseTxMusicuSearch(raw, "稻香", "song", 10)
        assertEquals(1, songs.size)
        assertEquals("周杰伦,合唱团", songs[0].singer)
        assertEquals("001fanJL3oe1wi", songs[0].singerId)
    }

    @Test
    fun `legacy client search backfills singerId from singer array mid`() {
        val raw = """{"data":{"song":{"list":[{"songname":"稻香","singer":[{"mid":"001fanJL3oe1wi","name":"周杰伦"}],"songmid":"003OUlho2HcRlP","albummid":"album01","albumname":"魔杰座"}]}}}"""
        val songs = parseTxSearch(raw, "稻香")
        assertEquals(1, songs.size)
        assertEquals("001fanJL3oe1wi", songs[0].singerId)
    }
}
