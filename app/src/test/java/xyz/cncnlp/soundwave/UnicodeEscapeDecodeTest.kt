package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.cncnlp.soundwave.source.direct.cleanSpace

/**
 * 批23a #B：详情/搜索页 “\u0026” 字面乱码回归（纯 JVM，不触网）。
 * 数据流：上游把已解码文本二次 JSON 序列化 → org.json 只解一层 → “\u0026” 以字面残留；
 * 修复：decodeProviderText（Song.fromJson 全链路）与 cleanSpace（直连搜索解析）统一还原。
 */
class UnicodeEscapeDecodeTest {

    @Test
    fun `server double escaped json decodes end to end via Song fromJson`() {
        val o = JSONObject("""{"id":"1","name":"T\\u0026T","singer":"S\\u0026M","album":"Al\\u0026bum"}""")
        assertEquals("T\\u0026T", o.optString("name"))
        val song = Song.fromJson(o, "wy")
        assertEquals("T&T", song.name)
        assertEquals("S&M", song.singer)
        assertEquals("Al&bum", song.album)
    }

    @Test
    fun `decodeProviderText resolves literal unicode escapes`() {
        assertEquals("A & B", decodeProviderText("A \\u0026 B"))
        assertEquals("A & B", decodeProviderText("A \\u0026amp; B"))
        assertEquals("A & B", decodeProviderText("A &amp; B"))
        assertEquals("A & B", decodeProviderText("A /u0026 B"))
    }

    @Test
    fun `plain text untouched`() {
        assertEquals("A & B", decodeProviderText("A & B"))
        assertEquals("", decodeProviderText(""))
        assertEquals("C:\\users\\tmp", decodeProviderText("C:\\users\\tmp"))
    }

    @Test
    fun `playlist metadata decodes bounded nested entities and readable html paragraphs`() {
        assertEquals("A B", decodePlaylistMetadata("A&#32;B"))
        assertEquals("©🙂", decodePlaylistMetadata("&#169;&#x1F642;"))
        assertEquals("&\"'<>", decodePlaylistMetadata("&amp;&quot;&apos;&lt;&gt;"))
        assertEquals("©—…", decodePlaylistMetadata("&nbsp;&copy;&mdash;&hellip;"))
        assertEquals("A & B", decodePlaylistMetadata("A & B"))
        assertEquals("QQ 歌单", decodePlaylistMetadata("QQ&amp;#160;歌单"))
        assertEquals("第一段\n第二段", decodePlaylistMetadata("第一段<br>第二段"))
        assertEquals("第一段\n第二段", decodePlaylistMetadata("第一段&amp;lt;br&amp;gt;第二段"))
        assertEquals("&notAnEntity;", decodePlaylistMetadata("&notAnEntity;"))
    }

    @Test
    fun `cleanSpace resolves literal escapes before entity folding`() {
        assertEquals("A & B", cleanSpace("A \\u0026 B"))
        assertEquals("A & B", cleanSpace("A \\u0026amp; B"))
    }
}
