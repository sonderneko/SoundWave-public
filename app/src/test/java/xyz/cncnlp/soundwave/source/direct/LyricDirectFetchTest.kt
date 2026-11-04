package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.cncnlp.soundwave.Song
import java.util.Base64

/** 批14 直连纯解析单测：对齐服务端 fetch_lyric（music_server.py:1192）各平台提取语义。 */
class LyricDirectFetchTest {

    private fun base64Of(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `wy requests romanization on both yrc and lrc paths`() {
        assertEquals(
            "https://music.163.com/api/song/lyric?id=536622304&lv=1&kv=1&tv=-1&rv=-1&yv=1&ytv=1",
            wyLyricUrl("536622304", includeYrc = true)
        )
        assertEquals(
            "https://music.163.com/api/song/lyric?id=536622304&lv=1&kv=1&tv=-1&rv=-1",
            wyLyricUrl("536622304", includeYrc = false)
        )
    }

    @Test
    fun `wy prefers word level yrc then falls back to plain lrc`() {
        val raw = """{"yrc":{"lyric":"[0,100](100)Hello"},"lrc":{"lyric":"[00:00.00]Hello"}}"""
        assertEquals("[0,100](100)Hello", parseWyLyric(raw, allowYrc = true))
        assertEquals("[00:00.00]Hello", parseWyLyric(raw, allowYrc = false))
    }

    @Test
    fun `wy falls back to lrc when yrc lacks word markers`() {
        val raw = """{"yrc":{"lyric":"no markers"},"lrc":{"lyric":"[00:01.00]hi"}}"""
        assertEquals("[00:01.00]hi", parseWyLyric(raw, allowYrc = true))
    }

    @Test
    fun `wy rejects blank malformed and non string payloads`() {
        assertNull(parseWyLyric("", allowYrc = true))
        assertNull(parseWyLyric("not-json", allowYrc = true))
        assertNull(parseWyLyric("""{"lrc":{"lyric":"  "}}""", allowYrc = true))
        assertNull(parseWyLyric("""{"lrc":{"lyric":{"nested":true}}}""", allowYrc = true))
    }

    @Test
    fun `wy ignores absent empty malformed and non string romalrc without losing main lyric`() {
        val main = "[00:01.00]こんにちは"
        listOf(
            """{"lrc":{"lyric":"$main"}}""",
            """{"lrc":{"lyric":"$main"},"romalrc":{"lyric":""}}""",
            """{"lrc":{"lyric":"$main"},"romalrc":{"lyric":{"nested":true}}}""",
            """{"lrc":{"lyric":"$main"},"romalrc":"invalid"}"""
        ).forEach { raw ->
            val payload = parseWyLyricPayload(raw, allowYrc = false)
            assertEquals(main, payload?.main)
            assertEquals("", payload?.romanization)
        }
    }

    @Test
    fun `kuwo rid strips prefix and non digits`() {
        assertEquals("12345", kuwoRid("MUSIC_12345"))
        assertEquals("67890", kuwoRid("kw_67a890"))
        assertEquals("", kuwoRid("MUSIC_"))
    }

    @Test
    fun `kw lrclist converts time seconds to mm ss and prefers lineLyric`() {
        val lrclist = org.json.JSONArray()
        lrclist.put(org.json.JSONObject().put("time", 0.0).put("lineLyric", "开场").put("line", "备用"))
        lrclist.put(org.json.JSONObject().put("time", 65.5).put("lineLyric", "一分五秒"))
        lrclist.put(org.json.JSONObject().put("time", 125.0).put("line", "只有 line"))
        lrclist.put(org.json.JSONObject()) // 空对象跳过
        val out = kwLrclistToLrc(lrclist, includeLineFallback = true)
        val lines = out.split("\n")
        assertEquals(3, lines.size)
        assertEquals("[00:00.00]开场", lines[0])
        assertEquals("[01:05.50]一分五秒", lines[1])
        assertEquals("[02:05.00]只有 line", lines[2])
    }

    @Test
    fun `kw lrclist without fallback keeps empty text for line only rows`() {
        val lrclist = org.json.JSONArray()
        lrclist.put(org.json.JSONObject().put("time", 10.0).put("lineLyric", "正词").put("line", "备用"))
        lrclist.put(org.json.JSONObject().put("time", 20.0).put("line", "仅备用"))
        val out = kwLrclistToLrc(lrclist, includeLineFallback = false)
        assertEquals(2, out.split("\n").size)
        assertEquals("[00:10.00]正词", out.split("\n")[0])
        assertEquals("[00:20.00]", out.split("\n")[1])
    }

    @Test
    fun `kw lrclist handles empty and malformed input`() {
        assertEquals("", kwLrclistToLrc(org.json.JSONArray(), includeLineFallback = true))
    }

    @Test
    fun `kw wapi lrclist renders timed lines with line fallback`() {
        val raw = """{"data":{"lrclist":[
            {"time":"12.5","lineLyric":"你好"},
            {"time":"65.25","lineLyric":"","line":"world"}
        ]}}"""
        assertEquals("[00:12.50]你好\n[01:05.25]world", parseKwLyric(raw))
    }

    @Test
    fun `kw wapi plain lyric text passes through`() {
        assertEquals("[00:01.00]text", parseKwLyric("""{"data":{"lyric":"[00:01.00]text"}}"""))
        assertEquals("[00:01.00]alt", parseKwLyric("""{"data":{"lrc":"[00:01.00]alt"}}"""))
        assertNull(parseKwLyric("""{"data":{}}"""))
    }

    @Test
    fun `kw mobile only accepts lrclist without line fallback`() {
        val raw = """{"data":{"lrclist":[
            {"time":"3","lineLyric":"hi"},
            {"time":"4","lineLyric":"","line":"IGNORED"}
        ]}}"""
        assertEquals("[00:03.00]hi\n[00:04.00]", parseKwMobileLyric(raw))
        assertNull(parseKwMobileLyric("""{"data":{"lyric":"[00:01.00]x"}}"""))
        assertNull(parseKwMobileLyric("""{"data":{"lrclist":[]}}"""))
    }

    @Test
    fun `kg two step parses candidate and base64 content`() {
        assertEquals("12345" to "ak", parseKgSearchCandidate("""{"candidates":[{"id":12345,"accesskey":"ak"}]}"""))
        assertNull(parseKgSearchCandidate("""{"candidates":[]}"""))
        val lrc = "[00:01.00]晴天"
        assertEquals(lrc, parseKgDownload("""{"content":"${base64Of(lrc)}"}"""))
        assertNull(parseKgDownload("""{"content":""}"""))
    }

    @Test
    fun `mg accepts bracketed strings in data lyric lrc order`() {
        val long = "[00:01.00]" + "长".repeat(20)
        assertEquals(long, parseMgLyric("""{"data":"$long"}"""))
        assertEquals(long, parseMgLyric("""{"lyric":"$long"}"""))
        assertEquals(long, parseMgLyric("""{"lrc":"$long"}"""))
        assertEquals("[00:01.00]nested lyric line here!", parseMgLyric("""{"data":{"content":"[00:01.00]nested lyric line here!"}}"""))
        assertNull(parseMgLyric("""{"data":"[00:01.00]x"}"""))
        assertNull(parseMgLyric("""{"data":[]}"""))
    }

    @Test
    fun `tx decodes jsonp wrapped base64 lyric`() {
        val lrc = "[00:01.00]七里香"
        assertEquals(lrc, parseTxLyric("""/* PGA4 */{"lyric":"${base64Of(lrc)}","trans":""}"""))
        assertNull(parseTxLyric("""{"lyric":""}"""))
        assertNull(parseTxLyric("garbage-without-json"))
    }

    @Test
    fun `base64 decoder is mime tolerant and empty safe`() {
        assertEquals("hello", decodeBase64Text("aGVsbG8="))
        assertNull(decodeBase64Text("!!!"))
    }

    @Test
    fun `dispatch returns null for unknown source blank id and non digit wy id`() = runBlocking {
        assertNull(fetchLyricDirect("xyz", Song(id = "1", name = "n", singer = "s")))
        assertNull(fetchLyricDirect("wy", Song(id = "", name = "n", singer = "s")))
        assertNull(fetchLyricDirect("wy", Song(id = "abc", name = "n", singer = "s")))
    }
}
