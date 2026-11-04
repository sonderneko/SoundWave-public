package xyz.cncnlp.soundwave.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import java.time.ZoneId
import java.time.ZonedDateTime

class StyleRecommendationTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun song(id: String, singer: String = "Artist $id") = Song(id, "Song $id", singer, source = "wy")

    // ── 换一批：每个北京时间自然日一次 ──
    @Test fun refreshGateAllowsOncePerShanghaiDay() {
        val morning = at(2026, 10, 16, 9)
        val day = StylePreferenceStore.shanghaiDay(morning)
        assertEquals("2026-10-16", day)
        assertTrue(StylePreferenceStore.canRefresh("", morning))
        assertTrue(StylePreferenceStore.canRefresh("2026-10-15", morning))
        assertFalse(StylePreferenceStore.canRefresh(day, morning))
        assertFalse(StylePreferenceStore.canRefresh(day, at(2026, 10, 16, 23, 59)))
        assertTrue(StylePreferenceStore.canRefresh(day, at(2026, 10, 17, 0, 0)))
    }

    @Test fun refreshGateUsesShanghaiNotUtcDay() {
        // 2026-10-16 23:30 UTC = 2026-10-17 07:30 北京时间
        val utcLate = ZonedDateTime.of(2026, 10, 16, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-10-17", StylePreferenceStore.shanghaiDay(utcLate))
        assertTrue(StylePreferenceStore.canRefresh("2026-10-16", utcLate))
    }

    // ── 风格标签最多 3 个 ──
    @Test fun toggleCapsAtThreeTags() {
        var tags = emptyList<String>()
        tags = StylePreferenceStore.toggle(tags, "华语")
        tags = StylePreferenceStore.toggle(tags, "摇滚")
        tags = StylePreferenceStore.toggle(tags, "民谣")
        assertEquals(listOf("华语", "摇滚", "民谣"), tags)
        assertTrue(StylePreferenceStore.isAtCap(tags))
        assertEquals(tags, StylePreferenceStore.toggle(tags, "电子"))
        tags = StylePreferenceStore.toggle(tags, "摇滚")
        assertEquals(listOf("华语", "民谣"), tags)
        assertEquals(listOf("华语", "民谣", "电子"), StylePreferenceStore.toggle(tags, "电子"))
        assertEquals(tags, StylePreferenceStore.toggle(tags, "  "))
    }

    @Test fun normalizeAndDecodeEnforceCap() {
        assertEquals(listOf("a", "b", "c"), StylePreferenceStore.normalize(listOf(" a", "b", "a", "", "c", "d")))
        assertEquals(listOf("a", "b", "c"), StylePreferenceStore.decodeTags("[\"a\",\"b\",\"c\",\"d\"]"))
        assertEquals(emptyList<String>(), StylePreferenceStore.decodeTags("not json"))
        assertEquals("[\"x\"]", StylePreferenceStore.encodeTags(listOf("x", "x")))
        assertEquals(StylePreferenceStore.tagsKey("WY"), StylePreferenceStore.tagsKey("wy"))
    }

    // ── 加权混合 / 去重 ──
    private val id: (Song) -> String = { it.id }

    @Test fun mixGivesTagsAboutFiftyFivePercent() {
        val taste = (1..40).map { song("t$it") }
        val pools = listOf((1..20).map { song("a$it") }, (1..20).map { song("b$it") })
        val out = StyleWeightedRecommendation.mix(taste, pools, 18, identity = id)
        assertEquals(18, out.size)
        val tagCount = out.count { !it.id.startsWith("t") }
        assertEquals(10, tagCount) // ceil(18 × 0.55)
        assertTrue(tagCount / 18.0 in 0.5..0.6)
        // 两个标签都有代表，且标签曲目不扎堆在列表前半段。
        assertTrue(out.any { it.id.startsWith("a") } && out.any { it.id.startsWith("b") })
        assertTrue(out.take(9).count { !it.id.startsWith("t") } in 4..6)
    }

    @Test fun mixDedupesAcrossPoolsAndExcluded() {
        val taste = listOf(song("x"), song("y"), song("z"), song("w"))
        val pools = listOf(listOf(song("x"), song("p"), song("q")), listOf(song("p"), song("r")))
        val out = StyleWeightedRecommendation.mix(taste, pools, 10, excluded = setOf("w"), identity = id)
        assertEquals(out.map { it.id }.distinct(), out.map { it.id })
        assertFalse(out.any { it.id == "w" })
        assertEquals(setOf("x", "p", "q", "r", "y", "z"), out.map { it.id }.toSet())
    }

    @Test fun mixDedupesByCrossPlatformIdentity() {
        val a = Song("1", "Same", "Singer", source = "wy")
        val b = Song("999", "Same", "Singer", source = "kg")
        val out = StyleWeightedRecommendation.mix(listOf(a), listOf(listOf(b)), 5)
        assertEquals(1, out.size)
    }

    @Test fun mixFillsFromOtherSideWhenShort() {
        val taste = (1..30).map { song("t$it") }
        val out = StyleWeightedRecommendation.mix(taste, listOf(listOf(song("a1"), song("a2"))), 18, identity = id)
        assertEquals(18, out.size)
        assertEquals(2, out.count { it.id.startsWith("a") })
        val tagOnly = StyleWeightedRecommendation.mix(emptyList(), listOf((1..30).map { song("a$it") }), 18, identity = id)
        assertEquals(18, tagOnly.size)
        val tasteOnly = StyleWeightedRecommendation.mix(taste, emptyList(), 18, identity = id)
        assertEquals(taste.take(18), tasteOnly)
    }

    @Test fun roundRobinAlternatesTags() {
        val out = StyleWeightedRecommendation.roundRobin(listOf(listOf(song("a1"), song("a2")), listOf(song("b1")), listOf(song("c1"), song("c2"))))
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2"), out.map { it.id })
    }

    @Test fun pickPlaylistsIsStableForSeedAndBounded() {
        val pls = (1..12).map { Playlist("p$it", "P$it") }
        val first = StyleWeightedRecommendation.pickPlaylists(pls, 42L, 2)
        assertEquals(first, StyleWeightedRecommendation.pickPlaylists(pls, 42L, 2))
        assertEquals(2, first.size)
        assertTrue(first.all { it.id.removePrefix("p").toInt() <= 8 })
    }

    @Test fun catalogSanitizeDropsPlaceholdersAndDuplicates() {
        val groups = StyleTagCatalog.sanitize(listOf("语种" to listOf("全部", "华语", "欧美"), "风格" to listOf("华语", "摇滚"), "空" to listOf("热门")))
        assertEquals(listOf(StyleTagCatalog.Group("语种", listOf("华语", "欧美")), StyleTagCatalog.Group("风格", listOf("摇滚"))), groups)
    }
}
