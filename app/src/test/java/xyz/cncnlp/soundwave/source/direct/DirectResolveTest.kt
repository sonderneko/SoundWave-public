package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectResolveTest {

    // ------------------------------------------------------- normalizeKgMediaUrl

    @Test
    fun `kg url normalization upgrades scheme protocol and strips trailing punctuation`() {
        assertEquals("https://a.b/c.mp3", normalizeKgMediaUrl("http://a.b/c.mp3"))
        assertEquals("https://a.b/c.mp3", normalizeKgMediaUrl("//a.b/c.mp3"))
        assertEquals("https://a.b/c.mp3", normalizeKgMediaUrl("  https://a.b/c.mp3  "))
        assertEquals("https://a.b/c.mp3", normalizeKgMediaUrl("https://a.b/c.mp3\",\""))
        assertEquals("", normalizeKgMediaUrl(""))
        assertEquals("", normalizeKgMediaUrl("   "))
    }

    // ------------------------------------------------------------- parseKgResolve

    @Test
    fun `kg resolve returns url with http upgraded`() {
        assertEquals("https://cdn.kugou.com/a.mp3", parseKgResolve("{" + "\"url\":\"http://cdn.kugou.com/a.mp3\"}"))
    }

    @Test
    fun `kg resolve falls back to backup url first element`() {
        val raw = "{" + "\"backup_url\":[\"http://kgbackup.com/b.mp3\",\"https://x.com/c.mp3\"]}"
        assertEquals("https://kgbackup.com/b.mp3", parseKgResolve(raw))
    }

    @Test
    fun `kg resolve url array takes first and empty array falls through`() {
        assertEquals("https://a.b/first.mp3", parseKgResolve("{" + "\"url\":[\"https://a.b/first.mp3\",\"https://a.b/second.mp3\"]}"))
        assertEquals("https://backup.b/x.mp3", parseKgResolve("{" + "\"url\":[],\"backup_url\":[\"https://backup.b/x.mp3\"]}"))
    }

    @Test
    fun `kg resolve null blank invalid json and missing urls yield null`() {
        assertNull(parseKgResolve("{" + "\"url\":null}"))
        assertNull(parseKgResolve("{}"))
        assertNull(parseKgResolve("{" + "\"url\":\"\",\"backup_url\":\"\"}"))
        assertNull(parseKgResolve("{broken"))
        assertNull(parseKgResolve(""))
    }

    // ------------------------------------------------------------- parseMgResolve

    @Test
    fun `mg resolve reads resource first listenUrl and upgrades http`() {
        val raw = "{" + "\"resource\":[{\"listenUrl\":\"http://mgcdn.cn/a.mp3\"}]}"
        assertEquals("https://mgcdn.cn/a.mp3", parseMgResolve(raw))
    }

    @Test
    fun `mg resolve falls back to playUrl and unwraps url list`() {
        assertEquals(
            "https://mg.b/fallback.mp3",
            parseMgResolve("{" + "\"resource\":[{\"listenUrl\":\"\",\"playUrl\":\"http://mg.b/fallback.mp3\"}]}"),
        )
        assertEquals(
            "https://mg.b/listed.mp3",
            parseMgResolve("{" + "\"resource\":[{\"listenUrl\":[\"http://mg.b/listed.mp3\"]}]}"),
        )
    }

    @Test
    fun `mg resolve accepts dict resource and rejects missing or empty resource`() {
        assertEquals(
            "https://mg.b/direct.mp3",
            parseMgResolve("{" + "\"resource\":{\"listenUrl\":\"http://mg.b/direct.mp3\"}}"),
        )
        assertNull(parseMgResolve("{" + "\"resource\":[]}"))
        assertNull(parseMgResolve("{}"))
        assertNull(parseMgResolve("{" + "\"resource\":[{}]}"))
        assertNull(parseMgResolve("not json"))
        assertNull(parseMgResolve(""))
    }

    // ------------------------------------------------------------ extractKuwoPlayUrl

    @Test
    fun `kuwo extract reads bare url json data url and music_url`() {
        assertEquals("https://cdn.kuwo.cn/a.mp3", extractKuwoPlayUrl("https://cdn.kuwo.cn/a.mp3"))
        assertEquals("https://cdn.kuwo.cn/b.mp3", extractKuwoPlayUrl("{" + "\"data\":{\"url\":\"https://cdn.kuwo.cn/b.mp3\"}}"))
        assertEquals("https://cdn.kuwo.cn/c.mp3", extractKuwoPlayUrl("{" + "\"data\":{\"music_url\":\"https://cdn.kuwo.cn/c.mp3\"}}"))
    }

    @Test
    fun `kuwo extract falls back to root url and message text and regex`() {
        assertEquals(
            "https://cdn.kuwo.cn/root.mp3",
            extractKuwoPlayUrl("{" + "\"url\":\"https://cdn.kuwo.cn/root.mp3\"}"),
        )
        assertEquals(
            "https://cdn.kuwo.cn/msg.mp3",
            extractKuwoPlayUrl("{" + "\"message\":\"音乐链接：https://cdn.kuwo.cn/msg.mp3\"}"),
        )
        assertEquals(
            "https://cdn.kuwo.cn/loose.mp3",
            extractKuwoPlayUrl("noise https://cdn.kuwo.cn/loose.mp3 trailing"),
        )
    }

    @Test
    fun `kuwo extract blank no url and malformed json yield empty string`() {
        assertEquals("", extractKuwoPlayUrl(""))
        assertEquals("", extractKuwoPlayUrl("{" + "\"data\":{}}"))
        assertEquals("", extractKuwoPlayUrl("{" + "\"data\":{\"url\":\"\"}}"))
        assertEquals("", extractKuwoPlayUrl("no url here"))
    }

    // --------------------------------------------------------- parseWyOiapiResolve

    @Test
    fun `wy oiapi resolve reads first data item url and upgrades http`() {
        // 形状与 oiapi.net/api/Music_163 实测响应一致（code/data[url,picurl,id]）。
        val raw = "{" + "\"code\":0,\"data\":[{\"id\":347230,\"url\":\"http://m801.music.126.net/x/a.mp3\",\"picurl\":\"https://p1.music.126.net/b.jpg\"}]}"
        assertEquals("https://m801.music.126.net/x/a.mp3", parseWyOiapiResolve(raw))
    }

    @Test
    fun `wy oiapi resolve takes first data item only`() {
        val raw = "{" + "\"data\":[{\"url\":\"https://a.b/first.mp3\"},{\"url\":\"https://a.b/second.mp3\"}]}"
        assertEquals("https://a.b/first.mp3", parseWyOiapiResolve(raw))
    }

    @Test
    fun `wy oiapi resolve empty missing data and blank url yield null`() {
        assertNull(parseWyOiapiResolve("{" + "\"code\":0,\"data\":[]}"))
        assertNull(parseWyOiapiResolve("{}"))
        assertNull(parseWyOiapiResolve("{" + "\"data\":null}"))
        assertNull(parseWyOiapiResolve("{" + "\"data\":[{\"url\":\"\"}]}"))
        assertNull(parseWyOiapiResolve("{" + "\"data\":[{\"url\":null}]}"))
        assertNull(parseWyOiapiResolve("{" + "\"data\":[{}]}"))
    }

    @Test
    fun `wy oiapi resolve invalid payloads yield null`() {
        assertNull(parseWyOiapiResolve("{broken"))
        assertNull(parseWyOiapiResolve(""))
        // data 非数组、数组元素非对象：服务端 `d['data'][0]` 口径下同样无链。
        assertNull(parseWyOiapiResolve("{" + "\"data\":{}}"))
        assertNull(parseWyOiapiResolve("{" + "\"data\":[\"x\"]}"))
    }

    // --------------------------------------------------------------- kuwoRid

    @Test
    fun `kuwo rid strips prefix and keeps digits`() {
        assertEquals("186016", kuwoRid("MUSIC_186016"))
        assertEquals("186016", kuwoRid("  music_186016  "))
        assertEquals("1234", kuwoRid("MUSIC_12ab34"))
        assertEquals("", kuwoRid("MUSIC_"))
        assertEquals("", kuwoRid("abc"))
        assertEquals("", kuwoRid(""))
    }

}
