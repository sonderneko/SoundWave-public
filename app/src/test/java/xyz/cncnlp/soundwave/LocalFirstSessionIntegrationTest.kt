package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.migration.*

/** Fakes model delayed consumers, not network/device acceptance. Authored, not JVM executed. */
class LocalFirstSessionIntegrationTest {
    private class Sink {
        val rows = mutableMapOf<String, MutableList<String>>()
        fun write(token: ProfileContext, value: String) {
            rows.getOrPut(ProfileWorkIdentity.encodeOwner(token)) { mutableListOf() }.add(value)
        }
    }
    @Test fun delayedHistoryProbeAndHelperCannotCommitAfterRotation() {
        val sessions = ProfileSessionCoordinator(5L, requireRotationIo = {})
        val sink = Sink()
        val old = sessions.capture()
        val callbacks = listOf("history", "probe", "helper", "lyric", "cover", "ended")
            .map { value -> { sessions.commit(old) { sink.write(old, value) } } }
        val next = sessions.rotateLegacy()
        callbacks.forEach { it() }
        assertTrue(sink.rows.isEmpty())
        sessions.commit(next) { sink.write(next, "new") }
        assertEquals(listOf("new"), sink.rows[ProfileWorkIdentity.LEGACY_OWNER])
    }
    @Test fun unknownWorkNeverRebindsToCurrentProfile() {
        assertNull(ProfileWorkIdentity.decode("profile:other", 8L))
        assertNull(ProfileWorkIdentity.decode("broken", 8L))
        assertNull(ProfileWorkIdentity.decode(null, -1L))
        val legacy = ProfileWorkIdentity.decode(null, 0L)!!
        assertEquals(ProfileOwner.LegacyUnassigned, legacy.owner)
        assertEquals(0L, legacy.generation)
        assertTrue(ProfileWorkIdentity.isWork(legacy))
        assertFalse(ProfileWorkIdentity.isRuntime(legacy))
    }
    @Test fun readinessFailureOccursBeforeCredentialProvider() {
        var reads = 0
        var requests = 0
        val sessions = ProfileSessionCoordinator(7L, requireRotationIo = {})
        val token = ProfileContext.recorded(ProfileId.of("inactive"), 7L)
        if (sessions.remoteReady(token)) { reads++; requests++ }
        assertEquals(0, reads)
        assertEquals(0, requests)
    }
}
