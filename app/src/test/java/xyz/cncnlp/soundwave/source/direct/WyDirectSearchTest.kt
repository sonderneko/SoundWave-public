package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WyDirectSearchTest {

    @Test
    fun `parses cloudsearch songs with ar-al mapping and score order`() {
        val raw = """
        {"result":{"songs":[
          {"name":"晴天","id":186016,"ar":[{"name":"周杰伦"}],
           "al":{"name":"叶惠美","picUrl":"http://p1.music.126.net/abc.jpg"}},
          {"name":"晴天 (Live)","id":999,"ar":[{"name":"周杰伦"},{"name":"合唱团"}],
           "al":{"name":"演唱会","picUrl":"//p2.music.126.net/x.jpg?param=100y100"}}
        ]}}
        """.trimIndent()
        val songs = parseWySearch(raw, "晴天")
        assertEquals(2, songs.size)
        assertEquals(listOf("晴天", "晴天 (Live)"), songs.map { it.name })
        val first = songs[0]
        assertEquals("186016", first.id)
        assertEquals("wy", first.source)
        assertEquals("周杰伦", first.singer)
        assertEquals("叶惠美", first.album)
        assertEquals("https://p1.music.126.net/abc.jpg?param=1080y1080", first.cover)
        val second = songs[1]
        assertEquals("周杰伦,合唱团", second.singer)
        assertEquals("https://p2.music.126.net/x.jpg?param=1080y1080", second.cover)
    }

    // ACCEPT5（C）：cloudsearch 的 dt（毫秒）必须进 Song.duration——跨源严配的
    // 时长证据；缺失时保持 0（匹配器按 duration_missing 拒绝，纪律不变）。
    @Test
    fun `cloudsearch dt becomes song duration in milliseconds`() {
        val raw = """
        {"result":{"songs":[
          {"name":"海阔天空","id":347230,"dt":326000,"ar":[{"name":"Beyond"}],
           "al":{"name":"乐与怒","picUrl":"http://p1.music.126.net/abc.jpg"}},
          {"name":"无时长","id":42,"ar":[{"name":"x"}],
           "al":{"name":"y","picUrl":"http://p1.music.126.net/def.jpg"}}
        ]}}
        """.trimIndent()
        val songs = parseWySearch(raw, "海阔天空")
        assertEquals(2, songs.size)
        assertEquals(326_000L, songs.first { it.id == "347230" }.duration)
        assertEquals(0L, songs.first { it.id == "42" }.duration)
    }

    @Test
    fun `cloudsearch falls back to artists-album legacy keys and blurPicUrl`() {
        val raw = """
        {"result":{"songs":[
          {"name":"稻香","id":185809,
           "artists":[{"name":"周杰伦"}],
           "album":{"name":"魔杰座","blurPicUrl":"http://p3.music.126.net/legacy.jpg"}}
        ]}}
        """.trimIndent()
        val songs = parseWySearch(raw, "稻香")
        assertEquals(1, songs.size)
        assertEquals("周杰伦", songs[0].singer)
        assertEquals("魔杰座", songs[0].album)
        assertEquals("https://p3.music.126.net/legacy.jpg?param=1080y1080", songs[0].cover)
    }

    @Test
    fun `cloudsearch drops entries without name or id`() {
        val raw = """
        {"result":{"songs":[
          {"id":1,"ar":[{"name":"x"}]},
          {"name":"no id"},
          {"name":"ok","id":7}
        ]}}
        """.trimIndent()
        val songs = parseWySearch(raw, "ok")
        assertEquals(1, songs.size)
        assertEquals("7", songs[0].id)
    }

    @Test
    fun `detail covers map uses album picUrl then blurPicUrl and skips coverless songs`() {
        val raw = """
        {"songs":[
          {"id":"10","album":{"picUrl":"http://p1.music.126.net/a.jpg"}},
          {"id":"20","al":{"blurPicUrl":"//p2.music.126.net/b.jpg"}},
          {"id":"30","album":{"picUrl":""}},
          {"id":"35","album":{"picUrl":"http://p3.music.126.net/c.jpg"}},
          {"id":"40"}
        ]}
        """.trimIndent()
        val covers = parseWyDetailCovers(raw)
        assertEquals(mapOf("10" to "https://p1.music.126.net/a.jpg?param=1080y1080"), covers.filterKeys { it == "10" })
        assertEquals("https://p2.music.126.net/b.jpg?param=1080y1080", covers["20"])
        assertEquals("https://p3.music.126.net/c.jpg?param=1080y1080", covers["35"])
        assertEquals(3, covers.size)
    }

    @Test
    fun `detail covers tolerate empty object album by falling back to al`() {
        val raw = """
        {"songs":[
          {"id":"50","album":{},"al":{"picUrl":"http://p4.music.126.net/d.jpg?param=300y300"}}
        ]}
        """.trimIndent()
        val covers = parseWyDetailCovers(raw)
        assertEquals(mapOf("50" to "https://p4.music.126.net/d.jpg?param=1080y1080"), covers)
    }

    @Test
    fun `wy blank invalid json and empty containers yield empty outputs`() {
        assertTrue(parseWySearch("", "kw").isEmpty())
        assertTrue(parseWySearch("{broken", "kw").isEmpty())
        assertTrue(parseWySearch("{}", "kw").isEmpty())
        assertTrue(parseWySearch("{" + "\"result\":{}}", "kw").isEmpty())
        assertTrue(parseWySearch("{" + "\"result\":{\"songs\":[]}}", "kw").isEmpty())
        assertTrue(parseWyDetailCovers("{}").isEmpty())
        assertTrue(parseWyDetailCovers("{" + "\"songs\":[]}").isEmpty())
        assertTrue(parseWyDetailCovers("not json").isEmpty())
    }

    @Test
    fun `cloudsearch backfills singerId from first ar artist only`() {
        val raw = """
        {"result":{"songs":[
          {"name":"晴天","id":186016,"ar":[{"id":6452,"name":"周杰伦"},{"id":9,"name":"合唱团"}],
           "al":{"name":"叶惠美","picUrl":"http://p1.music.126.net/abc.jpg"}},
          {"name":"无id歌手","id":7,"ar":[{"name":"无名氏"}]}
        ]}}
        """.trimIndent()
        val songs = parseWySearch(raw, "晴天")
        assertEquals(2, songs.size)
        val withId = songs.first { it.id == "186016" }
        assertEquals("周杰伦,合唱团", withId.singer)
        assertEquals("6452", withId.singerId)
        assertEquals("", songs.first { it.id == "7" }.singerId)
    }
}
