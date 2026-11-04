package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectParsingTest {

    // ------------------------------------------------------------------ cleanSpace

    @Test
    fun `cleanSpace folds whitespace and strips gaps between CJK characters`() {
        assertEquals("ABC DEF", cleanSpace("  ABC   DEF\t\n"))
        assertEquals("你好世界", cleanSpace("你 好\u3000世 界"))
        assertEquals("周杰伦晴天", cleanSpace("周杰伦 晴天"))
    }

    @Test
    fun `cleanSpace unescapes entities before folding`() {
        assertEquals("A &amp;amp; B", cleanSpace("A &amp;amp;amp; B"))
        assertEquals("<b>" + "x" + "</b> y", cleanSpace("&lt;b&gt;x&lt;/b&gt; y"))
        assertEquals("\" quoted\"", cleanSpace("&quot; quoted&quot;"))
    }

    @Test
    fun `cleanSpace keeps latin single spaces and handles empty input`() {
        assertEquals("", cleanSpace(""))
        assertEquals("", cleanSpace("   "))
        assertEquals("Hello World", cleanSpace("Hello World"))
    }

    // ------------------------------------------------------------------ unescapeHtml

    @Test
    fun `unescapeHtml resolves decimal and hex entities`() {
        assertEquals("A", unescapeHtml("&#65;"))
        assertEquals("A", unescapeHtml("&#x41;"))
        assertEquals("中", unescapeHtml("&#x4E2D;"))
    }

    @Test
    fun `unescapeHtml decodes named entities with amp last`() {
        assertEquals("&", unescapeHtml("&amp;"))
        assertEquals("&amp;", unescapeHtml("&amp;amp;"))
        assertEquals("\"<>'", unescapeHtml("&quot;&lt;&gt;&apos;"))
    }

    @Test
    fun `unescapeHtml leaves malformed entities untouched`() {
        assertEquals("&#xZZ;", unescapeHtml("&#xZZ;"))
        assertEquals("&#;&#x110000;", unescapeHtml("&#;&#x110000;"))
        assertEquals("", unescapeHtml(""))
    }

    // ------------------------------------------------------------------ shrinkCover

    @Test
    fun `shrinkCover upgrades netease param and appends param when missing`() {
        assertEquals(
            "https://p1.music.126.net/abc.jpg?param=1080y1080",
            shrinkCover("https://p1.music.126.net/abc.jpg?param=200y200"),
        )
        assertEquals(
            "https://p2.music.126.net/x.jpg?param=1080y1080",
            shrinkCover("http://p2.music.126.net/x.jpg"),
        )
    }

    @Test
    fun `shrinkCover preserves unverified kuwo size and upgrades verified kugou stdmusic`() {
        assertEquals(
            "https://img4.kuwo.cn/star/albumcover/120/95/6604395.jpg",
            shrinkCover("https://img4.kuwo.cn/star/albumcover/120/95/6604395.jpg"),
        )
        assertEquals(
            "https://imge.kugou.com/stdmusic/1080/20200601/abc.jpg",
            shrinkCover("//imge.kugou.com/stdmusic/150/20200601/abc.jpg"),
        )
    }

    @Test
    fun `shrinkCover replaces explicit Kugou placeholder and preserves unknown placeholders`() {
        assertEquals(
            "https://imge.kugou.com/stdmusic/1080/20210101/abc.jpg",
            shrinkCover("https://imge.kugou.com/stdmusic/{SIZE}/20210101/abc.jpg"),
        )
        assertEquals("https://s.com/a_{SIZE}x{size}.jpg", shrinkCover("https://s.com/a_{SIZE}x{size}.jpg"))
        assertEquals("https://cdn.example.com/pic/300.jpg", shrinkCover("https://cdn.example.com/pic/300.jpg"))
        assertEquals("", shrinkCover(""))
        assertEquals("", shrinkCover("   "))
    }

    // ------------------------------------------------------------------ scoreSong

    @Test
    fun `scoreSong exact name match beats containment`() {
        assertEquals(160, scoreSong("晴天", "晴天", "路人"))
        assertEquals(30, scoreSong("晴天", "晴天特别纪念版", "路人"))
        assertEquals(30, scoreSong("晴", "晴天", "周杰伦"))
    }

    @Test
    fun `scoreSong cover penalties on name`() {
        assertEquals(30 - 120, scoreSong("晴天", "晴天DJ版", "路人"))
        assertEquals(30 - 120 - 40, scoreSong("晴天", "晴天翻唱", "DJ小强"))
    }

    @Test
    fun `scoreSong parenthesis penalty and stripped base bonus`() {
        assertEquals(30 + 40 - 50, scoreSong("晴天", "晴天（限定版）", "路人"))
        assertEquals(35, scoreSong("晴天", "晴天（钢琴版）", "晴天"))
    }

    @Test
    fun `scoreSong known original gets 200 plus 80 bonus when name is clean`() {
        assertEquals(120 + 200 + 80 + 40, scoreSong("剥离之茧", "剥离之茧", "添儿呗"))
    }

    @Test
    fun `scoreSong known original loses bonus when name looks like a cover`() {
        assertEquals(30 - 120, scoreSong("剥离之茧", "剥离之茧DJ版", "添儿呗"))
    }

    @Test
    fun `scoreSong empty query scores only base line`() {
        assertEquals(0, scoreSong("", "晴天", "周杰伦"))
        assertEquals(-50 - 120, scoreSong("", "晴天 (Live)", "周杰伦"))
    }
}
