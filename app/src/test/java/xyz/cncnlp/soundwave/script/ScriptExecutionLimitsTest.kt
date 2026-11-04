package xyz.cncnlp.soundwave.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptExecutionLimitsTest {
    @Test fun scriptSourceHasOneMibibyteUtf8Budget() {
        assertTrue(ScriptExecutionLimits.scriptCodeAllowed("x".repeat(ScriptExecutionLimits.MAX_SCRIPT_CODE_BYTES)))
        assertFalse(ScriptExecutionLimits.scriptCodeAllowed("x".repeat(ScriptExecutionLimits.MAX_SCRIPT_CODE_BYTES + 1)))
        assertFalse(ScriptExecutionLimits.scriptCodeAllowed("é".repeat(ScriptExecutionLimits.MAX_SCRIPT_CODE_BYTES / 2 + 1)))
    }

    @Test fun requestBodyHeaderAndUrlBudgetsRejectBeforeTransport() {
        assertTrue(ScriptExecutionLimits.requestAllowed("https://fixture.invalid", "{}", "ok"))
        assertFalse(ScriptExecutionLimits.requestAllowed("x".repeat(ScriptExecutionLimits.MAX_REQUEST_URL_CHARS + 1), "{}", null))
        assertFalse(ScriptExecutionLimits.requestAllowed("https://fixture.invalid", " ".repeat(ScriptExecutionLimits.MAX_REQUEST_HEADERS_BYTES + 1), null))
        assertFalse(ScriptExecutionLimits.requestAllowed("https://fixture.invalid", "{}", "x".repeat(ScriptExecutionLimits.MAX_REQUEST_BODY_BYTES + 1)))
    }

    @Test fun metadataAndSongJsonHaveIndependentUtf8Budgets() {
        assertTrue(ScriptExecutionLimits.scriptMetadataAllowed("m".repeat(ScriptExecutionLimits.MAX_SCRIPT_METADATA_BYTES)))
        assertFalse(ScriptExecutionLimits.scriptMetadataAllowed("m".repeat(ScriptExecutionLimits.MAX_SCRIPT_METADATA_BYTES + 1)))
        assertTrue(ScriptExecutionLimits.musicInfoAllowed("s".repeat(ScriptExecutionLimits.MAX_MUSIC_INFO_BYTES)))
        assertFalse(ScriptExecutionLimits.musicInfoAllowed("s".repeat(ScriptExecutionLimits.MAX_MUSIC_INFO_BYTES + 1)))
    }

    @Test fun scriptUrlCallTotalBudgetCoversWatchdogAndLaneAdmission() {
        assertTrue(ScriptExecutionLimits.SCRIPT_URL_CALL_TOTAL_MS >= 6_000L + 16_000L)
    }
}
