package xyz.cncnlp.soundwave.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentWorkDiagnosticsTest {
    @After
    fun clearDiagnostics() {
        ConcurrentWorkDiagnostics.clearForTest()
    }

    @Test
    fun `identifiers are fingerprinted and event contains only safe structured values`() {
        val rawTaskId = "task-id-user-password-and-token"
        val rawIdentity = "https://user:password@example.invalid/media?token=secret"
        ConcurrentWorkDiagnostics.record(
            kind = ConcurrentWorkDiagnostics.WorkKind.DOWNLOAD,
            phase = ConcurrentWorkDiagnostics.Phase.EXECUTION,
            outcome = ConcurrentWorkDiagnostics.Outcome.RETRY_SCHEDULED,
            taskId = rawTaskId,
            identity = rawIdentity,
            queueVersion = 9L,
            cancelReason = ConcurrentWorkDiagnostics.CancelReason.WORKER_STOPPED,
            failureKind = ConcurrentWorkDiagnostics.FailureKind.NETWORK,
            cacheHit = false,
            queuedCount = 3,
            activeCount = 2,
            diskUsedBytes = 1024L,
            diskAvailableBytes = 4096L
        )

        val event = ConcurrentWorkDiagnostics.recent().single()
        val safeLine = event.safeLogLine()
        assertNotNull(event.taskFingerprint)
        assertNotNull(event.identityFingerprint)
        assertNotEquals(rawTaskId, event.taskFingerprint)
        assertNotEquals(rawIdentity, event.identityFingerprint)
        listOf(rawTaskId, rawIdentity, "https://", "password", "token=secret").forEach {
            assertFalse("diagnostics leaked '$it'", safeLine.contains(it, ignoreCase = true))
        }
        assertTrue(safeLine.contains("queueVersion=9"))
        assertTrue(safeLine.contains("result=RETRY_SCHEDULED"))
        assertTrue(safeLine.contains("queued=3"))
        assertTrue(safeLine.contains("diskUsedBytes=1024"))
    }

    @Test
    fun `ring buffer is bounded and preserves the newest ordered events`() {
        ConcurrentWorkDiagnostics.clearForTest()
        repeat(ConcurrentWorkDiagnostics.MAX_EVENTS + 7) { index ->
            ConcurrentWorkDiagnostics.record(
                kind = ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
                phase = ConcurrentWorkDiagnostics.Phase.QUEUE,
                outcome = ConcurrentWorkDiagnostics.Outcome.ACCEPTED,
                taskId = "prefetch-$index",
                queueVersion = index.toLong()
            )
        }

        val events = ConcurrentWorkDiagnostics.recent()
        assertEquals(ConcurrentWorkDiagnostics.MAX_EVENTS, events.size)
        assertEquals(8L, events.first().sequence)
        assertEquals((ConcurrentWorkDiagnostics.MAX_EVENTS + 7).toLong(), events.last().sequence)
        assertEquals(ConcurrentWorkDiagnostics.MAX_EVENTS, ConcurrentWorkDiagnostics.recent(500).size)
    }
}
