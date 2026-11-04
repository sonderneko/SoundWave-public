package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import xyz.cncnlp.soundwave.DiscoverCard

class FeedDirectFetchTest {

    @Test
    fun `three discover sections run concurrently and retain order when one source fails`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = Channel<Int>(Channel.UNLIMITED)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val requests: List<suspend () -> List<DiscoverCard>> = (0..2).map { index ->
            suspend {
                val running = active.incrementAndGet()
                maximum.updateAndGet { old -> maxOf(old, running) }
                started.send(index)
                try {
                    release.await()
                    if (index == 1) error("one endpoint failed")
                    listOf(DiscoverCard(id = "$index", name = "section-$index"))
                } finally {
                    active.decrementAndGet()
                }
            }
        }

        val result = async { fetchDiscoverSectionsConcurrently(requests) }
        val initiallyStarted = withTimeout(2000) { setOf(started.receive(), started.receive(), started.receive()) }
        assertEquals(3, initiallyStarted.size)
        release.complete(Unit)
        val sections = result.await()
        assertEquals(3, maximum.get())
        assertEquals("section-0", sections[0].single().name)
        assertTrue(sections[1].isEmpty())
        assertEquals("section-2", sections[2].single().name)
    }

    @Test
    fun `normalizeDiscoverCards groups by kind each capped at limit with digital first`() {
        val cards = listOf(
            DiscoverCard(id = "1", name = "A"),
            DiscoverCard(id = "", name = "无id"),
            DiscoverCard(id = "3", name = ""),
            DiscoverCard(id = "4", name = "B", kind = "digital", title = "数字专辑"),
            DiscoverCard(id = "5", name = "C"),
            DiscoverCard(id = "6", name = "D", kind = "new_album", title = "新碟首发"),
        )
        val out = normalizeDiscoverCards(cards, "wy", 4)
        // 按 digital → new_album → new_song 顺序拼接，无 id/空名过滤
        assertEquals(4, out.size)
        assertEquals("digital", out[0].kind)
        assertEquals("4", out[0].songId)
        assertEquals("new_album", out[1].kind)
        assertEquals("6", out[1].songId)
        assertEquals("new_song", out[2].kind)
        assertEquals("1", out[2].songId)
        assertEquals("new_song", out[3].kind)
        assertEquals("5", out[3].songId)
        assertEquals("wy", out[0].source)
        assertEquals("新碟首发", out[1].title)
        assertEquals("新歌速递", out[2].title)
    }

    @Test
    fun `normalizeDiscoverCards keeps new_album when digital fills the global limit`() {
        // 回归：全局 take(limit) 会让 digital 占满名额、new_album（新碟首发）被切掉。
        val cards = (1..20).map { DiscoverCard(id = "$it", name = "D$it", kind = "digital", title = "数字专辑") } +
            DiscoverCard(id = "21", name = "新碟A", kind = "new_album", title = "新碟首发") +
            DiscoverCard(id = "22", name = "新歌A", kind = "new_song", title = "新歌速递")
        val out = normalizeDiscoverCards(cards, "wy", 20)
        // digital 20 + new_album 1 + new_song 1 全部保留
        assertEquals(22, out.size)
        assertTrue(out.any { it.kind == "new_album" && it.id == "21" })
        assertTrue(out.any { it.kind == "new_song" && it.id == "22" })
    }

    @Test
    fun `parseWyDigitalAlbums maps vipmall products with shrink cover and artist join`() {
        val raw = """{"products":[
            {"albumId":"88","albumName":" 数字专辑A ","coverUrl":"http://p1.music.126.net/x.jpg?param=100y100","artistName":[{"name":"张三"},{"name":"李四"}]},
            {"albumName":"无id"},
            {"albumId":"89","albumName":"B","artistName":"王五"}
        ]}""".trimIndent()
        val out = parseWyDigitalAlbums(raw, 10)
        assertEquals(2, out.size)
        assertEquals("88", out[0].id)
        assertEquals("数字专辑A", out[0].name)
        assertTrue(out[0].cover.startsWith("https://"))
        assertTrue(out[0].cover.contains("param=400y400")) // MERGE7 对齐（J1）：发现面封面 400px，不再放大 1080
        assertEquals("张三 / 李四", out[0].artist)
        assertEquals("digital", out[0].kind)
        assertEquals("数字专辑", out[0].title)
        assertEquals("王五", out[1].artist)
    }

    @Test
    fun `parseWyNewAlbums maps album new list with blur fallback`() {
        val raw = """{"albums":[
            {"id":"501","name":"新碟","picUrl":"http://p3.music.126.net/b.jpg","artists":[{"name":"丙"}]},
            {"name":"无id"}
        ]}""".trimIndent()
        val out = parseWyNewAlbums(raw, 10)
        assertEquals(1, out.size)
        assertEquals("501", out[0].id)
        assertEquals("新碟", out[0].name)
        assertTrue(out[0].cover.contains("param=400y400")) // MERGE7 对齐（J1）：发现面封面 400px，不再放大 1080
        assertEquals("丙", out[0].artist)
        assertEquals("new_album", out[0].kind)
        assertEquals("新碟首发", out[0].title)
    }

    @Test
    fun `parseWyNewSongs maps card id to album and keeps songId`() {
        val raw = """{"result":[
            {"song":{"id":"111","name":"新歌一","album":{"id":"222","name":"专辑一","picUrl":"http://p2.music.126.net/a.jpg"},"artists":[{"name":"甲"}]}},
            {"id":"333","name":"裸歌曲"},
            {"song":{"id":"444","name":"无专辑"}}
        ]}""".trimIndent()
        val out = parseWyNewSongs(raw, 10)
        assertEquals(3, out.size)
        assertEquals("222", out[0].id)
        assertEquals("111", out[0].songId)
        assertEquals("专辑一", out[0].album)
        assertEquals("甲", out[0].artist)
        assertTrue(out[0].cover.contains("param=400y400")) // MERGE7 对齐（J1）：发现面封面 400px，不再放大 1080
        assertEquals("333", out[1].id)
        assertEquals("333", out[1].songId)
        assertEquals("444", out[2].id)
        assertEquals("", out[2].album)
    }

    @Test
    fun `parseTxDiscoverCards requires songname and albummid with fixed cover template`() {
        val raw = """{"songlist":[
            {"data":{"id":"9001","name":"QQ新歌","album":{"mid":"abc123","name":"QQ专辑"},"singer":[{"name":"甲"},{"name":"乙"}]}},
            {"data":{"id":"9002","name":"无专辑mid"}},
            {"data":{"id":"9003","album":{"mid":"zzz"}}}
        ]}""".trimIndent()
        val out = parseTxDiscoverCards(raw, 10)
        assertEquals(1, out.size)
        assertEquals("9001", out[0].id)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R800x800M000abc123.jpg", out[0].cover)
        assertEquals("甲 / 乙", out[0].artist)
        assertEquals("QQ专辑", out[0].album)
        assertEquals("新歌首发", out[0].title)
    }

    @Test
    fun `parseKwHotWords takes string entries only`() {
        val raw = """{"data":["周杰伦","林俊杰",{"dict":1},null]}"""
        assertEquals(listOf("周杰伦", "林俊杰"), parseKwHotWords(raw))
        assertTrue(parseKwHotWords("").isEmpty())
    }

    @Test
    fun `parseTxHotKeys reads data hotkey with k query word fallbacks`() {
        val raw = """{"data":{"hotkey":[{"k":"热词一","n":9},{"query":"备选"},{"word":"备选二"},{}]},"hotkey":[{"k":"顶层"}]}"""
        assertEquals(listOf("热词一", "备选", "备选二"), parseTxHotKeys(raw))
    }

    @Test
    fun `parseKgHotWords reads info keyword with word fallback`() {
        val raw = """{"data":{"info":[{"keyword":"酷狗词"},{"word":"备选"},{}]}}"""
        assertEquals(listOf("酷狗词", "备选"), parseKgHotWords(raw))
    }
}
