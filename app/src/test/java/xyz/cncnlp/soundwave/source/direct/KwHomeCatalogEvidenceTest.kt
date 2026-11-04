package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.DiscoverCard

/** Recorded live responses are evidence, not a promise about Kuwo's future catalog size. */
class KwHomeCatalogEvidenceTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/kw-home-20261023/$name.json")!!.readText()

    @Test fun recordedFirstPageRetainsEverySourceAlbumWithoutRelabellingAsDigital() {
        val raw = fixture("shelves-p1-r20-home")
        val rows = JSONObject(raw).getJSONObject("data").getJSONArray("albumList")
        val parsed = parseKwNewAlbums(raw, 20)
        assertEquals(1, rows.length())
        assertEquals(rows.length(), parsed.size)
        assertEquals("4965208", parsed.single().id)
        // Existing cleanSpace strips the source's NBSP; this test checks catalog retention.
        assertEquals("未完待旭罗中旭经典全纪录1995-2017", parsed.single().name)
        assertEquals("kw", parsed.single().source)
        assertEquals("new_album", parsed.single().kind)
        assertEquals(0, parsed.count { it.kind == "digital" })
    }

    @Test fun recordedLargerRequestDoesNotContainHiddenAdditionalAlbums() {
        val normal = parseKwNewAlbums(fixture("shelves-p1-r12-exact"), 30)
        val larger = parseKwNewAlbums(fixture("shelves-p1-r30"), 30)
        assertEquals(normal.map { it.id }, larger.map { it.id })
        assertEquals(1, larger.size)
    }

    @Test fun recordedSecondPageIsEmptyEvenThoughSizeStillReportsOne() {
        val raw = fixture("shelves-p2-r12")
        val data = JSONObject(raw).getJSONObject("data")
        assertEquals(1, data.getInt("size"))
        assertEquals(0, data.getJSONArray("albumList").length())
        assertTrue(parseKwNewAlbums(raw, 18).isEmpty())
    }

    @Test fun parserAndNormalizationDoNotImposeASingleAlbumCap() {
        // Synthetic unit-test records only; never used as application fallback data.
        val rows = JSONArray()
        for (i in 1..4) {
            rows.put(JSONObject().put("albumid", "test-$i").put("album", "Test album $i")
                .put("artist", "Test artist").put("pay", 0))
        }
        val raw = JSONObject().put("code", 200).put("data", JSONObject().put("albumList", rows)).toString()
        val parsed = parseKwNewAlbums(raw, 18)
        assertEquals(4, parsed.size)
        val song = DiscoverCard(id = "test-song", name = "Test song", source = "kw", kind = "new_song")
        val normalized = normalizeDiscoverCards(parsed + song, "kw", 18)
        assertEquals(parsed.map { it.id }, normalized.filter { it.kind == "new_album" }.map { it.id })
        assertEquals(1, normalized.count { it.kind == "new_song" })
        assertTrue(normalized.none { it.kind == "digital" })
        assertEquals(2, parseKwNewAlbums(raw, 2).size)
        assertTrue(parseKwNewAlbums(raw, 0).isEmpty())
    }

    @Test fun duplicateAndInvalidRowsCannotBecomeExtraAlbums() {
        val raw = """{"code":200,"data":{"albumList":[
            {"albumid":"same","album":"Album"},
            {"albumid":"same","album":"Album"},
            {"albumid":"","album":"Missing ID"},
            {"albumid":"missing-name","album":""}
        ]}}"""
        assertEquals(listOf("same"), parseKwNewAlbums(raw, 18).map { it.id })
    }

    @Test fun errorsOrMissingCatalogRemainEmptyNotPlaceholderAlbums() {
        for (raw in listOf("", "<html>500 Internal Server Error</html>", "{}",
            """{"code":500,"data":{"albumList":[{"albumid":"bad","album":"Error"}]}}""",
            """{"code":200,"data":{}}""")) {
            assertTrue(parseKwNewAlbums(raw, 18).isEmpty())
        }
    }
}
