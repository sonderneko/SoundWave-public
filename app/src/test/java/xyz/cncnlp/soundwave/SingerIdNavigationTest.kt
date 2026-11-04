package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S 波（ISSUE-SINGER）：「查看歌手」ID 优先导航的纯函数面——
 * Song.singerId 的 JSON 往返/旧数据兼容、主歌手可检索名归一化、按名兜底命中挑选。
 */
class SingerIdNavigationTest {

    // ---------- Song.singerId JSON ----------

    @Test
    fun `toJson writes singerId in full and compact modes and fromJson reads it back`() {
        val song = Song(id = "1", name = "晴天", singer = "周杰伦", source = "wy", singerId = "6452")
        val full = song.toJson()
        assertEquals("6452", full.getString("singerId"))
        assertEquals("6452", Song.fromJson(full).singerId)
        val compact = song.toJson(compact = true)
        assertEquals("6452", compact.getString("singerId"))
        assertEquals("6452", Song.fromJson(compact).singerId)
    }

    @Test
    fun `compact toJson omits empty singerId`() {
        val song = Song(id = "1", name = "x", singer = "y", source = "wy")
        assertEquals(false, song.toJson(compact = true).has("singerId"))
    }

    @Test
    fun `fromJson legacy payload without singerId defaults to empty`() {
        val legacy = JSONObject("""{"id":"1","name":"晴天","singer":"周杰伦","src":"wy"}""")
        assertEquals("", Song.fromJson(legacy).singerId)
    }

    @Test
    fun `fromJson reads wy ar first artist id`() {
        val raw = JSONObject(
            """{"id":186016,"name":"晴天","ar":[{"id":6452,"name":"周杰伦"},{"id":9,"name":"合唱团"}],"src":"wy"}"""
        )
        val song = Song.fromJson(raw)
        assertEquals("周杰伦/合唱团", song.singer)
        assertEquals("6452", song.singerId)
    }

    @Test
    fun `fromJson reads platform singer id key variants`() {
        assertEquals(
            "7788",
            Song.fromJson(JSONObject("""{"id":"h","name":"n","singer":"s","singerid":"7788","src":"kg"}""")).singerId
        )
        assertEquals(
            "12345",
            Song.fromJson(JSONObject("""{"id":"r","name":"n","singer":"s","ARTISTID":"12345","src":"kw"}""")).singerId
        )
        assertEquals(
            "midABC",
            Song.fromJson(JSONObject("""{"id":"m","name":"n","singer":"s","singerMid":"midABC","src":"tx"}""")).singerId
        )
        assertEquals(
            "112",
            Song.fromJson(JSONObject("""{"id":"c","name":"n","singer":"s","singerId":"112","src":"mg"}""")).singerId
        )
    }

    // ---------- primary singer searchable name ----------

    @Test
    fun `primarySingerName splits multi-artist and takes the first`() {
        assertEquals("周杰伦", SongMatcher.primarySingerName("周杰伦 / 合唱团"))
        assertEquals("A", SongMatcher.primarySingerName("A、B"))
        assertEquals("A", SongMatcher.primarySingerName("A & B"))
        assertEquals("周杰伦", TrackNavigation.primaryArtist("周杰伦,合唱团"))
    }

    @Test
    fun `primarySingerName drops featuring collaborators`() {
        assertEquals("周杰伦", SongMatcher.primarySingerName("周杰伦 feat. 林俊杰"))
        assertEquals("周杰伦", SongMatcher.primarySingerName("周杰伦 featuring 林俊杰"))
        assertEquals("A", SongMatcher.primarySingerName("A ft B"))
    }

    @Test
    fun `primarySingerName strips trailing bracket alias`() {
        assertEquals("周杰伦", SongMatcher.primarySingerName("周杰伦 (Jay Chou)"))
        assertEquals("周杰伦", SongMatcher.primarySingerName("周杰伦（Jay Chou）"))
    }

    @Test
    fun `primarySingerName does not mangle names containing ft inside a word`() {
        assertEquals("Daft Punk", SongMatcher.primarySingerName("Daft Punk"))
    }

    // ---------- pickArtistHit ----------

    private fun artistRow(id: String, name: String) = Song(id = id, name = name, singer = name, source = "wy")

    @Test
    fun `pickArtistHit accepts exact name row with id`() {
        val rows = listOf(artistRow("1", "别人"), artistRow("6452", "周杰伦"))
        assertEquals("6452", TrackNavigation.pickArtistHit(rows, "周杰伦")?.id)
    }

    @Test
    fun `pickArtistHit matches through normalization`() {
        val rows = listOf(artistRow("6452", "周杰伦 (Jay Chou)"))
        assertEquals("6452", TrackNavigation.pickArtistHit(rows, "周杰伦")?.id)
    }

    @Test
    fun `pickArtistHit never blind-grabs the first row`() {
        // 旧实现的行为：无精确同名时抓第一条（可能是歌曲行/无关歌手）。现在必须返回 null 诚实报未找到。
        val rows = listOf(artistRow("song-id-1", "晴天"), artistRow("song-id-2", "稻香"))
        assertNull(TrackNavigation.pickArtistHit(rows, "周杰伦"))
    }

    @Test
    fun `pickArtistHit ignores rows without id even when the name matches`() {
        val rows = listOf(artistRow("", "周杰伦"))
        assertNull(TrackNavigation.pickArtistHit(rows, "周杰伦"))
    }

    @Test
    fun `pickArtistHit returns null on empty rows`() {
        assertNull(TrackNavigation.pickArtistHit(emptyList(), "周杰伦"))
    }
}
