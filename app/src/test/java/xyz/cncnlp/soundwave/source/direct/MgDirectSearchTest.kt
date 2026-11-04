package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MgDirectSearchTest {

    @Test
    fun `parses migu result with id fallback cover tail and sorts by score`() {
        val raw = """
        {"songResultData":{"result":[
          {"name":"晴天 &amp; 蓝天","singers":[{"name":"周杰伦"},{"name":"Guest Singer"}],
           "copyrightId":"c2","albums":[{"name":"叶惠美"}],
           "imgItems":[{"img":"http://img.migu.cn/small.jpg"},{"img":"http://img.migu.cn/big.jpg"}]},
          {"name":"晴天","singers":[{"name":"周杰伦"}],
           "contentId":"c1","albums":[{"name":"寻找周杰伦"}],
           "imgItems":[{"img":"https://img.migu.cn/only.jpg"}]}
        ]}}
        """.trimIndent()
        val songs = parseMgSearch(raw, "晴天")
        assertEquals(2, songs.size)
        assertEquals(listOf("晴天", "晴天 & 蓝天"), songs.map { it.name })
        val first = songs[0]
        assertEquals("c1", first.id)
        assertEquals("周杰伦", first.singer)
        assertEquals("寻找周杰伦", first.album)
        assertEquals("mg", first.source)
        assertEquals("https://img.migu.cn/only.jpg", first.cover)
        val second = songs[1]
        assertEquals("c2", second.id)
        assertEquals("周杰伦,Guest Singer", second.singer)
        assertEquals("https://img.migu.cn/big.jpg", second.cover)
    }

    @Test
    fun `migu covers honor shrinkCover size rules and singers may be absent`() {
        val raw = """
        {"songResultData":{"result":[
          {"name":"孤勇者","singers":[{"name":"陈奕迅"}],"copyrightId":"c9",
           "albums":[{"name":"孤勇者"}],"imgItems":[{"img":"//img.kuwo.cn/star/albumcover/120/1/1.jpg"}]}
        ]}}
        """.trimIndent()
        val songs = parseMgSearch(raw, "孤勇者")
        assertEquals(1, songs.size)
        assertEquals("https://img.kuwo.cn/star/albumcover/120/1/1.jpg", songs[0].cover)
        assertEquals("陈奕迅", songs[0].singer)
    }

    @Test
    fun `migu uses valid webp variant when highest size img is truncated`() {
        val raw = """
        {"songResultData":{"result":[
          {"name":"同源高清","contentId":"webp-1","imgItems":[
            {"img":"https://img.migu.cn/truncated.web","webpImg":"https://img.migu.cn/high.webp","imgSizeType":"03"},
            {"img":"https://img.migu.cn/medium.jpg","imgSizeType":"02"}
          ]}
        ]}}
        """.trimIndent()
        val song = parseMgSearch(raw, "同源高清").single()
        assertEquals("webp-1", song.id)
        assertEquals("https://img.migu.cn/high.webp", song.cover)
    }

    @Test
    fun `migu drops entries without name or id and dedupes nothing`() {
        val raw = """
        {"songResultData":{"result":[
          {"singers":[{"name":"x"}],"copyrightId":"no-name"},
          {"name":"no id"},
          {"name":"null id","contentId":"null"},
          {"name":"keep","copyrightId":"ok"}
        ]}}
        """.trimIndent()
        val songs = parseMgSearch(raw, "keep")
        assertEquals(1, songs.size)
        assertEquals("ok", songs[0].id)
    }

    @Test
    fun `migu respects limit and returns parse order for equal scores`() {
        val raw = """
        {"songResultData":{"result":[
          {"name":"歌一","contentId":"1"},{"name":"歌二","contentId":"2"},
          {"name":"歌三","contentId":"3"}
        ]}}
        """.trimIndent()
        assertEquals(listOf("歌一", "歌二"), parseMgSearch(raw, "不存在", 2).map { it.name })
        assertEquals(2, parseMgSearch(raw, "不存在", 2).size)
    }

    @Test
    fun `migu blank invalid json and empty result yield empty list`() {
        assertTrue(parseMgSearch("", "kw").isEmpty())
        assertTrue(parseMgSearch("   ", "kw").isEmpty())
        assertTrue(parseMgSearch("{broken", "kw").isEmpty())
        assertTrue(parseMgSearch("{}", "kw").isEmpty())
        assertTrue(parseMgSearch("{" + "\"songResultData\":{}}", "kw").isEmpty())
        assertTrue(parseMgSearch("{" + "\"songResultData\":{\"result\":[]}}", "kw").isEmpty())
    }

    @Test
    fun `migu backfills singerId from first singers entry`() {
        val raw = """
        {"songResultData":{"result":[
          {"name":"晴天","singers":[{"id":"112","name":"周杰伦"},{"id":"999","name":"Guest"}],
           "contentId":"c1","albums":[{"name":"叶惠美"}]}
        ]}}
        """.trimIndent()
        val songs = parseMgSearch(raw, "晴天")
        assertEquals(1, songs.size)
        assertEquals("周杰伦,Guest", songs[0].singer)
        assertEquals("112", songs[0].singerId)
    }
}
