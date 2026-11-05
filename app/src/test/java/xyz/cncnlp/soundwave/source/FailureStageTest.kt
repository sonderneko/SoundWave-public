package xyz.cncnlp.soundwave.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.cncnlp.soundwave.PlaybackDiagnostics
import xyz.cncnlp.soundwave.source.direct.PlatformTransportErrorKind
import xyz.cncnlp.soundwave.source.direct.parseOfficialEntities
import xyz.cncnlp.soundwave.source.direct.parseTxMusicuSearchStatus

/** PB-F3：失败分型推广——四型标签、传输错误映射、tx/实体解析分型、诊断计数。 */
class FailureStageTest {

    @Before fun resetDiagnostics() {
        PlaybackDiagnostics.clear()
    }

    @Test
    fun `stage labels are the four canonical spellings`() {
        assertEquals("rejected", FailureStage.REJECTED.label)
        assertEquals("parse", FailureStage.PARSE.label)
        assertEquals("network", FailureStage.NETWORK.label)
        assertEquals("empty", FailureStage.EMPTY.label)
    }

    @Test
    fun `transport error kinds map to stages`() {
        assertEquals(FailureStage.REJECTED, PlatformTransportErrorKind.HTTP_STATUS.toFailureStage())
        assertEquals(FailureStage.REJECTED, PlatformTransportErrorKind.REMOTE_FAILURE.toFailureStage())
        assertEquals(FailureStage.REJECTED, PlatformTransportErrorKind.POLICY.toFailureStage())
        assertEquals(FailureStage.REJECTED, PlatformTransportErrorKind.SSRF.toFailureStage())
        assertEquals(FailureStage.PARSE, PlatformTransportErrorKind.PARSE.toFailureStage())
        assertEquals(FailureStage.NETWORK, PlatformTransportErrorKind.TIMEOUT.toFailureStage())
        assertEquals(FailureStage.NETWORK, PlatformTransportErrorKind.NETWORK.toFailureStage())
        assertEquals(FailureStage.NETWORK, PlatformTransportErrorKind.COOLDOWN.toFailureStage())
    }

    @Test
    fun `tx blank response is network stage and unavailable`() {
        val result = parseTxMusicuSearchStatus("", "keyword", "song")
        assertEquals(false, result.available)
        assertEquals(FailureStage.NETWORK, result.stage)
    }

    @Test
    fun `tx nonzero module code is rejected stage`() {
        val raw = """{"music.search.SearchCgiService":{"code":2001,"data":{}}}"""
        val result = parseTxMusicuSearchStatus(raw, "keyword", "song")
        assertEquals(false, result.available)
        assertEquals(FailureStage.REJECTED, result.stage)
    }

    @Test
    fun `tx broken envelope is parse stage`() {
        val missingBlock = parseTxMusicuSearchStatus("""{"other":{}}""", "keyword", "song")
        assertEquals(false, missingBlock.available)
        assertEquals(FailureStage.PARSE, missingBlock.stage)
        val brokenJson = parseTxMusicuSearchStatus("{broken", "keyword", "song")
        assertEquals(false, brokenJson.available)
        assertEquals(FailureStage.PARSE, brokenJson.stage)
    }

    @Test
    fun `tx successful empty list is true empty not failure`() {
        val raw = """{"music.search.SearchCgiService":{"code":0,"data":{"code":0,"body":{"song":{"list":[]}}}}}"""
        val result = parseTxMusicuSearchStatus(raw, "keyword", "song")
        assertTrue(result.available)
        assertTrue(result.songs.isEmpty())
        assertEquals(FailureStage.EMPTY, result.stage)
    }

    @Test
    fun `official entities stages follow the same taxonomy`() {
        assertEquals(FailureStage.NETWORK, parseOfficialEntities("wy", "", "kw", "artist").stage)
        assertEquals(FailureStage.REJECTED, parseOfficialEntities("wy", """{"code":500}""", "kw", "artist").stage)
        assertEquals(FailureStage.PARSE, parseOfficialEntities("wy", """{"code":200}""", "kw", "artist").stage)
        assertEquals(FailureStage.REJECTED, parseOfficialEntities("wy", "{}", "kw", "song").stage)
        assertNull(parseOfficialEntities("kw", """{"abslist":[{"ARTISTID":"7","ARTIST":"Singer"}]}""", "Singer", "artist").stage)
    }

    @Test
    fun `diagnostics tally counts stages per scope and platform`() {
        PlaybackDiagnostics.noteFailureStage("search", "kg", FailureStage.NETWORK)
        PlaybackDiagnostics.noteFailureStage("search", "kg", FailureStage.NETWORK)
        PlaybackDiagnostics.noteFailureStage("search", "kg", FailureStage.EMPTY)
        PlaybackDiagnostics.noteFailureStage("catalog-playlists", "wy", FailureStage.REJECTED)
        assertEquals(2, PlaybackDiagnostics.failureStageCount("search", "kg", FailureStage.NETWORK))
        assertEquals(1, PlaybackDiagnostics.failureStageCount("search", "kg", FailureStage.EMPTY))
        assertEquals(0, PlaybackDiagnostics.failureStageCount("search", "kg", FailureStage.PARSE))
        assertEquals(1, PlaybackDiagnostics.failureStageCount("catalog-playlists", "wy", FailureStage.REJECTED))
        assertEquals(2, PlaybackDiagnostics.failureStageTallySnapshot()["search|kg|network"])
    }
}
