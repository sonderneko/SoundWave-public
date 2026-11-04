package xyz.cncnlp.soundwave.source.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** AUTHORED, NOT EXECUTED. All JSON here is synthetic parser-contract input, NOT live payload evidence. */
class Step8FollowupRegressionTest {
    private fun square(source: String, rows: JSONArray): String = when (source) {
        "wy" -> JSONObject().put("code", 200).put("playlists", rows)
        "tx" -> JSONObject().put("code", 0).put("playlist", JSONObject().put("code", 0)
            .put("data", JSONObject().put("v_playlist", rows)))
        "kg" -> JSONObject().put("status", 1).put("special_db", rows)
        "kw" -> JSONObject().put("code", 200).put("data", JSONObject().put("data", rows))
        else -> JSONObject().put("code", "000000").put("data", JSONObject().put("contents", rows))
    }.toString()

    @Test fun malformedNonemptyRowsAreUnavailableNotNegativeCacheable() {
        for (source in listOf("wy", "tx", "kg", "kw", "mg")) {
            for (row in listOf<Any>(JSONObject.NULL, "not an object", JSONObject())) {
                assertNull(source, parseRecommendPlaylistSquare(source, square(source, JSONArray().put(row))))
            }
            assertEquals(source, emptyList<xyz.cncnlp.soundwave.Playlist>(),
                parseRecommendPlaylistSquare(source, square(source, JSONArray())))
        }
        val missingName = JSONObject().put("specialid", "123")
        assertNull(parseRecommendPlaylistSquare("kg", square("kg", JSONArray().put(missingName))))
        val blankAfterCleaning = JSONObject().put("specialid", "123").put("specialname", "&nbsp;")
        assertNull(parseRecommendPlaylistSquare("kg", square("kg", JSONArray().put(blankAfterCleaning))))
        val valid = JSONObject().put("specialid", "123").put("specialname", "name")
        assertNull(parseRecommendPlaylistSquare("kg", square("kg", JSONArray().put(valid).put(missingName))))
        assertEquals("123", parseRecommendPlaylistSquare("kg", square("kg", JSONArray().put(valid)))?.single()?.remoteId)
    }

    @Test fun knownNonPlaylistRowsRemainFilteredRatherThanMalformed() {
        assertEquals(emptyList<xyz.cncnlp.soundwave.Playlist>(), parseRecommendPlaylistSquare("kw",
            square("kw", JSONArray().put(JSONObject().put("digest", "8")))))
        assertEquals(emptyList<xyz.cncnlp.soundwave.Playlist>(), parseRecommendPlaylistSquare("mg",
            square("mg", JSONArray().put(JSONObject().put("resType", "other")))))
        assertNull(parseRecommendPlaylistSquare("kw", square("kw", JSONArray().put(JSONObject().put("digest", "5")))))
        assertNull(parseRecommendPlaylistSquare("mg", square("mg", JSONArray().put(JSONObject().put("resType", "2021")))))
        var nested = JSONArray().put(JSONObject().put("resType", "2021").put("resId", "123").put("txt", "name"))
        repeat(9) { nested = JSONArray().put(JSONObject().put("contents", nested)) }
        assertNull(parseRecommendPlaylistSquare("mg", square("mg", nested)))
        val legacyRows = JSONObject().put("code", "000000").put("data", JSONObject().put("contentItemList",
            JSONArray().put(JSONObject()).put(JSONObject().put("itemList", JSONArray().put(JSONObject())))))
        assertNull(parseRecommendPlaylistSquare("mg", legacyRows.toString()))
    }

    @Test fun kuwoNodeTranslationUsesFirstChildSourceIdNotNodeId() = runBlocking {
        val raw = square("kw", JSONArray().put(JSONObject().put("digest", "5").put("id", "node-id").put("name", "name")))
        val rows = checkNotNull(parseRecommendPlaylistSquare("kw", raw))
        val requested = mutableListOf<String>()
        val resolved = checkNotNull(resolveKwRecommendationNodes(rows) { node ->
            requested.add(node)
            parseKwRecommendationSourceId("{\"child\":[{\"sourceid\":\"detail-id\"},{\"sourceid\":\"ignored\"}]}")
        })
        assertEquals(listOf("node-id"), requested)
        assertEquals("detail-id", resolved.single().id)
        assertEquals("detail-id", resolved.single().remoteId)
        assertEquals("name", resolved.single().name)
        assertEquals("kw", resolved.single().source)
        assertNull(resolveKwRecommendationNodes(rows) { null })
        assertEquals(emptyList<xyz.cncnlp.soundwave.Playlist>(), resolveKwRecommendationNodes(emptyList()) { error("no node request for empty") })
        for (invalid in listOf("", "{}", "{\"child\":[]}", "{\"child\":[{}]}", "{\"child\":[{\"sourceid\":null}]}", "{\"child\":[{\"sourceid\":{}}]}")) {
            assertNull(parseKwRecommendationSourceId(invalid))
        }
    }

    @Test fun transportCancellationEscapesWithoutFallbackSentinel() = runBlocking {
        val expected = CancellationException("cancelled transport")
        var reachedFallback = false
        try {
            platformHttpRequest { throw expected }
            reachedFallback = true
            fail("cancellation must escape")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        assertFalse(reachedFallback)
        assertEquals("", platformHttpRequest { throw IOException("unavailable") })
        assertEquals("payload", platformHttpRequest { "payload" })
    }

    @Test fun kuwoResolutionCancellationEscapesWithoutPartialSuccess() = runBlocking {
        val rows = checkNotNull(parseRecommendPlaylistSquare("kw", square("kw", JSONArray()
            .put(JSONObject().put("digest", "5").put("id", "node").put("name", "name")))))
        val expected = CancellationException("cancelled node lookup")
        try {
            resolveKwRecommendationNodes(rows) { throw expected }
            fail("cancellation must escape")
        } catch (actual: CancellationException) { assertSame(expected, actual) }
    }
}
