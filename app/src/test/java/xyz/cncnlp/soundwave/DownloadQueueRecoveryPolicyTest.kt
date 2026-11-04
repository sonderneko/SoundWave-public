package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueRecoveryPolicyTest {
    @Test fun missingOrStaleQueuedWorkIsReenqueuedIdempotently() {
        assertEquals(DownloadQueueRecoveryAction.ENQUEUE,
            DownloadQueueRecoveryPolicy.decide("queued", DownloadWorkSnapshot.MISSING))
        assertEquals(DownloadQueueRecoveryAction.ENQUEUE,
            DownloadQueueRecoveryPolicy.decide("queued", DownloadWorkSnapshot.STALE_ACTIVE))
        assertEquals(DownloadQueueRecoveryAction.ENQUEUE,
            DownloadQueueRecoveryPolicy.decide("queued", DownloadWorkSnapshot.STALE_FINISHED))
    }

    @Test fun currentActiveWorkIsKeptAndCurrentFinishedWorkIsNotGivenANewRetryBudget() {
        assertEquals(DownloadQueueRecoveryAction.KEEP,
            DownloadQueueRecoveryPolicy.decide("queued", DownloadWorkSnapshot.CURRENT_ACTIVE))
        assertEquals(DownloadQueueRecoveryAction.MARK_FAILED,
            DownloadQueueRecoveryPolicy.decide("queued", DownloadWorkSnapshot.CURRENT_FINISHED))
        assertEquals(DownloadQueueRecoveryAction.KEEP,
            DownloadQueueRecoveryPolicy.decide("retry_wait", DownloadWorkSnapshot.CURRENT_ACTIVE))
        assertEquals(DownloadQueueRecoveryAction.MARK_FAILED,
            DownloadQueueRecoveryPolicy.decide("retry_wait", DownloadWorkSnapshot.MISSING))
        listOf("authorizing", "downloading", "verifying", "retry_wait").forEach { status ->
            assertEquals("$status with current work is retained", DownloadQueueRecoveryAction.KEEP,
                DownloadQueueRecoveryPolicy.decide(status, DownloadWorkSnapshot.CURRENT_ACTIVE))
            assertEquals("$status without work is not given an unbounded retry", DownloadQueueRecoveryAction.MARK_FAILED,
                DownloadQueueRecoveryPolicy.decide(status, DownloadWorkSnapshot.MISSING))
        }
    }

    @Test fun terminalRowsAreNeverAutomaticallyRequeued() {
        listOf("paused", "cancelled", "failed", "done").forEach { status ->
            DownloadWorkSnapshot.values().forEach { snapshot ->
                assertEquals(status, DownloadQueueRecoveryAction.KEEP,
                    DownloadQueueRecoveryPolicy.decide(status, snapshot))
            }
        }
    }

    @Test fun legacyResolvingAliasIsReconciledAndCompletedAliasRemainsTerminal() {
        assertEquals(DownloadQueueRecoveryAction.KEEP,
            DownloadQueueRecoveryPolicy.decide("resolving", DownloadWorkSnapshot.CURRENT_ACTIVE))
        assertEquals(DownloadQueueRecoveryAction.MARK_FAILED,
            DownloadQueueRecoveryPolicy.decide("resolving", DownloadWorkSnapshot.MISSING))
        DownloadWorkSnapshot.values().forEach { snapshot ->
            assertEquals(DownloadQueueRecoveryAction.KEEP,
                DownloadQueueRecoveryPolicy.decide("completed", snapshot))
        }
    }
}
