package xyz.cncnlp.soundwave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.DownloadItem
import xyz.cncnlp.soundwave.Song

class DownloadTaskStateMachineTest {
    @Test
    fun legacyPersistedValuesMapToCanonicalStatesWithoutChangingStorageValues() {
        assertEquals(DownloadTaskState.RESOLVING, DownloadTaskState.fromPersisted("authorizing"))
        assertEquals(DownloadTaskState.RESOLVING, DownloadTaskState.fromPersisted("resolving"))
        assertEquals(DownloadTaskState.COMPLETED, DownloadTaskState.fromPersisted("done"))
        assertEquals(DownloadTaskState.COMPLETED, DownloadTaskState.fromPersisted("completed"))
        assertEquals(DownloadTaskState.CANCELLED, DownloadTaskState.fromPersisted("canceled"))
        assertEquals("authorizing", DownloadTaskState.RESOLVING.persistedValue)
        assertEquals("done", DownloadTaskState.COMPLETED.persistedValue)
    }

    @Test
    fun normalCompletionRequiresResolvingDownloadingAndVerificationStages() {
        assertTrue(DownloadTaskStateMachine.canTransition("queued", "authorizing"))
        assertTrue(DownloadTaskStateMachine.canTransition("authorizing", "downloading"))
        assertTrue(DownloadTaskStateMachine.canTransition("downloading", "verifying"))
        assertTrue(DownloadTaskStateMachine.canTransition("verifying", "done"))
        assertFalse(DownloadTaskStateMachine.canTransition("queued", "done"))
        assertFalse(DownloadTaskStateMachine.canTransition("downloading", "done"))
        assertFalse(DownloadTaskStateMachine.canTransition("verifying", "downloading"))
        assertFalse(DownloadTaskStateMachine.canTransition("done", "failed"))
        assertFalse(DownloadTaskStateMachine.canTransition("future_state", "done"))
        assertTrue(DownloadTaskStateMachine.canTransition("future_state", "failed"))
    }

    @Test
    fun retriesPauseResumeAndExplicitRestartHaveDefinedTransitions() {
        assertTrue(DownloadTaskStateMachine.canTransition("authorizing", "retry_wait"))
        assertTrue(DownloadTaskStateMachine.canTransition("retry_wait", "authorizing"))
        assertTrue(DownloadTaskStateMachine.canTransition("downloading", "paused"))
        assertTrue(DownloadTaskStateMachine.canTransition("paused", "queued"))
        assertTrue(DownloadTaskStateMachine.canTransition("downloading", "cancelled"))
        assertFalse(DownloadTaskStateMachine.canTransition("cancelled", "verifying"))
        assertTrue(DownloadTaskStateMachine.canTransition("failed", "queued"))
        assertTrue(DownloadTaskStateMachine.canTransition("done", "authorizing"))
        assertFalse(DownloadTaskStateMachine.isActive("paused"))
        assertTrue(DownloadTaskStateMachine.isActive("resolving"))
    }

    @Test
    fun notificationsProjectCanonicalStatusAndKeepConcurrentTaskIdsSeparate() {
        assertEquals("正在取链", DownloadNotificationPolicy.presentation("resolving").title)
        assertTrue(DownloadNotificationPolicy.presentation("authorizing").ongoing)
        assertEquals("正在校验", DownloadNotificationPolicy.presentation("verifying").title)
        assertEquals("等待重试", DownloadNotificationPolicy.presentation("retry_wait").title)
        assertFalse(DownloadNotificationPolicy.presentation("done").ongoing)
        assertEquals(
            DownloadNotificationPolicy.notificationId("task-a"),
            DownloadNotificationPolicy.notificationId("task-a")
        )
        assertTrue(
            DownloadNotificationPolicy.notificationId("task-a") !=
                DownloadNotificationPolicy.notificationId("task-b")
        )
    }

    @Test
    fun workerCompletionRequiresDurableMatchingDoneRecordAndVerifiedSizeEvidence() {
        val song = Song(id = "local", name = "fixture", singer = "test", source = "local")
        val returned = DownloadItem(
            song = song, path = "/fixture/audio.mp3", size = 1024L, time = 1L,
            status = "done", progress = 1f, taskId = "task-1"
        )
        assertTrue(DownloadCompletionPolicy.matches(returned, returned.copy(), "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned, null, "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned, returned.copy(path = "/fixture/other.mp3"), "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned, returned.copy(size = 2048L), "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned.copy(status = "downloading"), returned, "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned.copy(path = "pending:task-1"), returned, "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned.copy(size = 256L), returned, "task-1"))
        assertFalse(DownloadCompletionPolicy.matches(returned, returned, "other-task"))
    }
}
