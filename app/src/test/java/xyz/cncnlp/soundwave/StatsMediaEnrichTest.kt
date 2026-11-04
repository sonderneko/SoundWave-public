package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import xyz.cncnlp.soundwave.source.direct.HotArtist

/** ACCEPT6-STATSMEDIA：统计页展示层补图的纯逻辑——回填只填空封面、三级匹配、图表头像精确匹配。 */
class StatsMediaEnrichTest {

    private fun stat(key: String, name: String, singer: String, cover: String = "", cnt: Int = 3) =
        ListenStats.SongStat(key, name, singer, cover, 600L, cnt)

    @Test
    fun backfillByExactSongKey() {
        val top = listOf(stat("mg:12345", "I miss you", "DJ Vital Force"))
        val candidates = listOf(
            Song(id = "12345", name = "I miss you", singer = "DJ Vital Force", source = "mg", cover = "https://cdn.example.com/a.jpg")
        )
        val out = StatsMediaEnrich.backfillSongCovers(top, candidates)
        assertEquals("https://cdn.example.com/a.jpg", out.single().cover)
        assertEquals(3, out.single().cnt)
        assertEquals(600L, out.single().sec)
    }

    @Test
    fun backfillMetaKeyBySourceNameSinger() {
        // 统计键是无 id 的 meta 键；候选是有 id 的同源同名同歌手条目。
        val top = listOf(stat("wy:meta:あなたを想いたい:勝又隆一:0", "あなたを想いたい", "勝又隆一"))
        val candidates = listOf(
            Song(id = "998", name = "あなたを想いたい", singer = "勝又隆一", source = "wy", cover = "https://cdn.example.com/b.jpg")
        )
        val out = StatsMediaEnrich.backfillSongCovers(top, candidates)
        assertEquals("https://cdn.example.com/b.jpg", out.single().cover)
    }

    @Test
    fun backfillCrossSourceByNameSingerAsLastResort() {
        val top = listOf(stat("local:meta:songx:artisty:0", "Song X", "Artist Y"))
        val candidates = listOf(
            Song(id = "7", name = "Song X", singer = "Artist Y", source = "wy", cover = "https://cdn.example.com/c.jpg")
        )
        val out = StatsMediaEnrich.backfillSongCovers(top, candidates)
        assertEquals("https://cdn.example.com/c.jpg", out.single().cover)
    }

    @Test
    fun neverOverwritesExistingCoverAndSkipsBlankCandidates() {
        val top = listOf(
            stat("wy:1", "A", "S1", cover = "https://cdn.example.com/keep.jpg"),
            stat("wy:2", "B", "S2")
        )
        val candidates = listOf(
            Song(id = "1", name = "A", singer = "S1", source = "wy", cover = "https://cdn.example.com/other.jpg"),
            Song(id = "2", name = "B", singer = "S2", source = "wy", cover = "")
        )
        val out = StatsMediaEnrich.backfillSongCovers(top, candidates)
        assertEquals("https://cdn.example.com/keep.jpg", out[0].cover)
        assertEquals("", out[1].cover)
        assertEquals(listOf("wy:1", "wy:2"), out.map { it.key })
    }

    @Test
    fun candidatePriorityFirstNonBlankWins() {
        val top = listOf(stat("kg:9", "C", "S3"))
        val candidates = listOf(
            Song(id = "9", name = "C", singer = "S3", source = "kg", cover = ""),
            Song(id = "9", name = "C", singer = "S3", source = "kg", cover = "https://cdn.example.com/first.jpg"),
            Song(id = "9", name = "C", singer = "S3", source = "kg", cover = "https://cdn.example.com/second.jpg")
        )
        val out = StatsMediaEnrich.backfillSongCovers(top, candidates)
        assertEquals("https://cdn.example.com/first.jpg", out.single().cover)
    }

    @Test
    fun noBlankCoversOrNoCandidatesReturnsSameList() {
        val filled = listOf(stat("wy:1", "A", "S1", cover = "https://cdn.example.com/x.jpg"))
        assertSame(filled, StatsMediaEnrich.backfillSongCovers(filled, listOf(Song(id = "1", name = "A", singer = "S1", source = "wy", cover = "https://cdn.example.com/y.jpg"))))
        val blank = listOf(stat("wy:1", "A", "S1"))
        assertSame(blank, StatsMediaEnrich.backfillSongCovers(blank, emptyList()))
    }

    @Test
    fun chartAvatarExactNormalizedMatchOnly() {
        val chart = listOf(
            HotArtist(name = "昼夜", avatar = "https://cdn.example.com/day.jpg", id = "11"),
            HotArtist(name = "No Cover", avatar = "", id = "12"),
            HotArtist(name = "Other", avatar = "https://cdn.example.com/other.jpg", id = "13")
        )
        assertEquals("https://cdn.example.com/day.jpg", StatsMediaEnrich.chartAvatarFor(chart, " 昼夜 "))
        assertEquals("", StatsMediaEnrich.chartAvatarFor(chart, "No Cover"))
        assertEquals("", StatsMediaEnrich.chartAvatarFor(chart, "昼"))
        assertEquals("", StatsMediaEnrich.chartAvatarFor(emptyList(), "昼夜"))
    }
}
