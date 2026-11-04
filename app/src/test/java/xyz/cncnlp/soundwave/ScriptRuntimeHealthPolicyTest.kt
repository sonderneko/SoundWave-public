package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeHealthPolicyTest {
    @Test fun initializationRetriesOnlyWithinBound() {
        assertEquals(1, ScriptRuntimeHealthPolicy.nextInitAttempt(0))
        assertEquals(3, ScriptRuntimeHealthPolicy.nextInitAttempt(2))
        assertEquals(null, ScriptRuntimeHealthPolicy.nextInitAttempt(3))
    }

    @Test fun onlyUnreturnedNonDownloadHandlerIsRebuildCandidate() {
        assertTrue(ScriptRuntimeHealthPolicy.shouldRebuildAfterTimeout(handlerReturned = false, isDownload = false))
        assertFalse(ScriptRuntimeHealthPolicy.shouldRebuildAfterTimeout(handlerReturned = true, isDownload = false))
        assertFalse(ScriptRuntimeHealthPolicy.shouldRebuildAfterTimeout(handlerReturned = false, isDownload = true))
    }
}
