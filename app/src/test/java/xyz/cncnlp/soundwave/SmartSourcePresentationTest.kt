package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSourcePresentationTest {
    private val script = Script(id = "script-a", name = "A", code = "lx.on('musicUrl', () => {})")

    @Test
    fun snapshotWithoutSavedReportShowsNoInventedAttemptsOrCapabilities() {
        val snapshot = SmartSourceScriptProbeSnapshot.from(script, null)

        assertNull(snapshot.report)
        assertFalse(snapshot.current)
        assertEquals(0, snapshot.attemptCount)
        assertEquals(0, snapshot.validAttemptCount)
        assertTrue(snapshot.platforms.isEmpty())
    }

    @Test
    fun snapshotUsesOnlyCacheValidatedSuccessesForQualityAndMeasuredLatency() {
        val report = ScriptProbeCache.Report(
            scriptId = script.id,
            scriptName = script.name,
            attempts = listOf(
                verified("wy", "320k", resolveMs = 200L, firstByteMs = 50L),
                verified("wy", "flac", resolveMs = 300L, firstByteMs = 100L),
                ScriptProbeCache.Attempt("wy", "128k", ScriptProbeCache.Outcome.FAILED),
                ScriptProbeCache.Attempt("wy", "320k", ScriptProbeCache.Outcome.NO_MATCH),
                ScriptProbeCache.Attempt("wy", "master", ScriptProbeCache.Outcome.UNSUPPORTED)
            ),
            checkedAt = 1_700_000_000_000L,
            scriptHash = ScriptProbeCache.scriptHash(script.code),
            runtimeAbi = ScriptProbeCache.RUNTIME_ABI,
            type = ScriptProbeCache.ReportType.FULL,
            complete = true
        )

        val snapshot = SmartSourceScriptProbeSnapshot.from(script, report)

        assertTrue(snapshot.current)
        assertEquals(report, snapshot.report)
        assertEquals(5, snapshot.attemptCount)
        assertEquals(2, snapshot.validAttemptCount)
        val platform = snapshot.platforms.single()
        assertEquals("wy", platform.code)
        assertEquals(5, platform.attemptCount)
        assertEquals(listOf("320k", "flac"), platform.verifiedQualities)
        assertEquals(325L, platform.averageLatencyMs)
        assertEquals(1, platform.outcomeCounts[ScriptProbeCache.Outcome.FAILED])
        assertEquals(1, platform.outcomeCounts[ScriptProbeCache.Outcome.NO_MATCH])
        assertEquals(1, platform.outcomeCounts[ScriptProbeCache.Outcome.UNSUPPORTED])
    }

    @Test
    fun staleReportRemainsMarkedHistoricalRatherThanBeingPresentedAsCurrent() {
        val stale = ScriptProbeCache.Report(
            scriptId = script.id,
            scriptName = script.name,
            attempts = listOf(verified("kw", "320k", resolveMs = 100L, firstByteMs = 30L)),
            checkedAt = 1_700_000_000_000L,
            scriptHash = "old-script-hash",
            runtimeAbi = ScriptProbeCache.RUNTIME_ABI,
            type = ScriptProbeCache.ReportType.QUICK,
            complete = false
        )

        val snapshot = SmartSourceScriptProbeSnapshot.from(script, stale)

        assertFalse(snapshot.current)
        assertEquals(stale, snapshot.report)
        assertEquals(listOf("320k"), snapshot.platforms.single().verifiedQualities)
    }

    @Test
    fun qualityDialogPersistsExactlyTheTwoSelectedValues() {
        val writes = mutableListOf<Pair<String, String>>()
        val selected = SmartSourceQualitySelection("320k", "320k")
            .selectPlayback("flac24bit")
            .selectDownload("128k")

        selected.persist(
            platform = "wy",
            writePlayback = { platform, quality -> writes += "play:$platform" to quality },
            writeDownload = { platform, quality -> writes += "download:$platform" to quality }
        )

        assertEquals(listOf("play:wy" to "flac24bit", "download:wy" to "128k"), writes)
        assertEquals(listOf("master", "hires", "flac24bit", "flac", "320k", "128k"), SMART_SOURCE_QUALITY_OPTIONS)
        assertEquals("hires", normalizeUserSelectableQuality("atmos"))
        assertEquals("hires", normalizeUserSelectableQuality("Atmos"))
    }

    @Test
    fun platformBindingSummaryDropsDisabledAndDeletedNamesAndRenumbersImmediately() {
        val first = Script(id = "first", name = "First", code = "first()")
        val second = Script(id = "second", name = "Second", code = "second()")
        val third = Script(id = "third", name = "Third", code = "third()")
        val importedDisabled = Script(id = "disabled", name = "Disabled import", code = "off()", enabled = false)
        val selectedIds = listOf(first.id, second.id, third.id, importedDisabled.id)

        assertEquals("1. First → 2. Second → 3. Third", platformScriptBindingSummary(
            selectedIds, listOf(first, second, third, importedDisabled)
        ))
        assertEquals("1. Second → 2. Third", platformScriptBindingSummary(
            selectedIds, listOf(first.copy(enabled = false), second, third, importedDisabled)
        ))
        assertEquals("1. Third", platformScriptBindingSummary(selectedIds, listOf(third)))
        assertEquals("未指定（使用默认）", platformScriptBindingSummary(selectedIds, listOf(importedDisabled)))
    }

    private fun verified(platform: String, quality: String, resolveMs: Long, firstByteMs: Long) =
        ScriptProbeCache.Attempt(
            platform = platform,
            quality = quality,
            outcome = ScriptProbeCache.Outcome.OK,
            resolveMs = resolveMs,
            firstByteMs = firstByteMs,
            firstByteReceived = true,
            bytes = 12_000L,
            playable = true
        )
}
