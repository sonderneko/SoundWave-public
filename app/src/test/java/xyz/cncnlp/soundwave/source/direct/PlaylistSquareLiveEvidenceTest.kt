package xyz.cncnlp.soundwave.source.direct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome

/** Captured anonymous responses, not a claim about current platform-wide catalog sizes. */
class PlaylistSquareLiveEvidenceTest {
    private fun raw(name: String) = javaClass.getResource("/playlist-square-20261023/$name.json")!!.readText()
    private fun page(source: String, name: String, number: Int, kind: String = "") =
        parseNativePlaylistSquare(source, raw(name), number, 30, kind) as PlaylistSearchOutcome.Success

    @Test fun qqAndKuwoAllReallyContinuePastThirtyAndSixty() {
        for (source in listOf("tx", "kw")) {
            val pages = (1..3).map { page(source, "$source-all-p$it", it) }
            assertTrue(pages.all { it.hasMore == true })
            assertEquals(listOf(30, 30, 30), pages.map { it.playlists.size })
            assertEquals(90, pages.flatMap { it.playlists }.distinctBy { it.id }.size)
        }
    }

    @Test fun qqNativeCategoryReallyHasDifferentSecondAndThirdPages() {
        val pages = (1..3).map { page("tx", "tx-tag-3317-p$it", it) }
        assertTrue(pages.all { it.hasMore == true })
        assertEquals(90, pages.flatMap { it.playlists }.distinctBy { it.id }.size)
        val finite = page("tx", "tx-tag-3418-p1", 1)
        assertEquals(24, finite.playlists.size)
        assertEquals(false, finite.hasMore)
    }

    @Test fun kuwoShortPagesAreNotTerminalWhenTheProviderTotalHasMore() {
        for (id in listOf("2189", "1265")) {
            for (number in 1..3) {
                val name = "kw-tag-$id-p$number"
                val rows = JSONObject(raw(name)).getJSONObject("data").getJSONArray("data")
                val result = page("kw", name, number, "10000")
                assertEquals(rows.length(), result.playlists.size)
                assertTrue(result.hasMore!!)
            }
        }
        assertEquals(29, page("kw", "kw-tag-2189-p1", 1).playlists.size)
        assertEquals(26, page("kw", "kw-tag-2189-p3", 3).playlists.size)
    }

    @Test fun actualLastPagesAndFollowingEmptyPagesAreTerminal() {
        for ((source, last, count) in listOf(Triple("tx", 388, 7), Triple("kw", 59, 13))) {
            val terminal = page(source, "$source-all-p$last", last)
            assertEquals(count, terminal.playlists.size)
            assertEquals(false, terminal.hasMore)
            val after = page(source, "$source-all-p${last + 1}", last + 1)
            assertTrue(after.playlists.isEmpty())
            assertEquals(false, after.hasMore)
        }
    }

    @Test fun realKuwoZoneArrayRetainsAllAndOnlyActualPlaylists() {
        val text = raw("kw-zone-211")
        val ids = linkedSetOf<String>()
        val albums = linkedSetOf<String>()
        fun visit(rows: JSONArray) {
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                if (item.optString("digest") == "8") ids += item.getString("id")
                if (item.optString("digest") == "13") albums += item.getString("id")
                for (key in listOf("list", "child", "data")) item.optJSONArray(key)?.let(::visit)
            }
        }
        visit(JSONArray(text))
        val result = page("kw", "kw-zone-211", 1, "43")
        assertEquals(17, ids.size)
        assertEquals(ids.toList(), result.playlists.map { it.id })
        assertTrue(albums.isNotEmpty())
        assertTrue(result.playlists.none { it.id in albums })
        assertTrue(result.playlists.all { it.source == "kw" && it.remoteId == it.id })
        assertEquals(false, result.hasMore)
    }

    @Test fun zoneSupportsLegacyWrappersButDoesNotTurnErrorsIntoEmptySuccess() {
        // Re-wrap the real sections: compatibility test only, never runtime fallback data.
        val sections = JSONArray(raw("kw-zone-211"))
        for (key in listOf("data", "child")) {
            val wrapped = JSONObject().put(key, sections).toString()
            val result = parseNativePlaylistSquare("kw", wrapped, 1, 30, "43") as PlaylistSearchOutcome.Success
            assertEquals(17, result.playlists.size)
            assertEquals(false, result.hasMore)
        }
        for (bad in listOf("", "{}", "{\"code\":500}", "<html>Error</html>", "[null]")) {
            assertSame(PlaylistSearchOutcome.Failure, parseNativePlaylistSquare("kw", bad, 1, 30, "43"))
        }
        val empty = parseNativePlaylistSquare("kw", "[]", 1, 30, "43") as PlaylistSearchOutcome.Success
        assertTrue(empty.playlists.isEmpty())
        assertEquals(false, empty.hasMore)
    }
}
