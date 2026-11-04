package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import androidx.work.Configuration
import androidx.work.WorkManager
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.migration.ProfileSessionCoordinator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Authored only; actual production summary/preferences and scheduler boundaries, no network. */
@RunWith(RobolectricTestRunner::class)
class ReviewProbeEpochRegressionTest {
    private fun app() = RuntimeEnvironment.getApplication()
    @Test fun staleEmptyAndNormalSummariesAndNotificationAreRejected() {
        val app = app()
        val owner = ProfileWorkIdentity.persistentWork()
        val epoch = ProfileWorkIdentity.cancellationEpoch(app)
        val prefs = app.getSharedPreferences("soundwave", 0)
        prefs.edit().putString("pending_script_probe_done", "keep").commit()
        ProfileWorkIdentity.cancelPersistentWork(app)
        var notified = false
        assertFalse(PersistentProbeEffects.commitSummary(app, owner, epoch, "empty"))
        assertFalse(PersistentProbeEffects.commitSummary(app, owner, epoch, "normal") { notified = true })
        assertEquals("keep", prefs.getString("pending_script_probe_done", null))
        assertFalse(notified)
        val fresh = ProfileWorkIdentity.cancellationEpoch(app)
        assertTrue(PersistentProbeEffects.commitSummary(app, owner, fresh, "fresh") { notified = true })
        assertEquals("fresh", prefs.getString("pending_script_probe_done", null))
        assertTrue(notified)
    }

    @Test fun epochRevocationCannotSplitSummaryAndNotificationTransaction() {
        val app = app()
        val owner = ProfileWorkIdentity.persistentWork()
        val epoch = ProfileWorkIdentity.cancellationEpoch(app)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revokeAttempt = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val commit = pool.submit<Boolean> {
                PersistentProbeEffects.commitSummary(app, owner, epoch, "normal") {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val revoke = pool.submit<Long> { revokeAttempt.countDown(); ProfileWorkIdentity.cancelPersistentWork(app) }
            assertTrue(revokeAttempt.await(2, TimeUnit.SECONDS))
            assertFalse(revoke.isDone)
            release.countDown()
            assertTrue(commit.get(2, TimeUnit.SECONDS))
            assertEquals(epoch + 1L, revoke.get(2, TimeUnit.SECONDS).toLong())
            assertFalse(PersistentProbeEffects.commitSummary(app, owner, epoch, "late"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun domainAndTaskCancellationDoNotExpireUnrelatedWork() {
        val app = app()
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        // Require initialization, not a fake cancellation implementation.
        WorkManager.getInstance(app)
        val owner = ProfileWorkIdentity.persistentWork()
        val epoch = ProfileWorkIdentity.cancellationEpoch(app)
        val generation = app.getSharedPreferences("soundwave_download_scheduler", 0).getLong("clear_generation", 0L)
        val item = DownloadItem(song = Song(id = "task", name = "task", singer = "s", source = "wy"),
            size = 0L, time = 1L, path = "pending:task", taskId = "task")
        DownloadScheduler.pause(app, item)
        DownloadScheduler.cancel(app, item)
        assertEquals(epoch, ProfileWorkIdentity.cancellationEpoch(app))
        assertTrue(PersistentProbeEffects.commitSummary(app, owner, epoch, "after task cancellation"))
        assertTrue(DownloadScheduler.clearDownloads(app, false))
        assertEquals(epoch, ProfileWorkIdentity.cancellationEpoch(app))
        assertTrue(PersistentProbeEffects.commitSummary(app, owner, epoch, "after download clear"))
        ScriptProbeScheduler.setEnabled(app, false)
        assertEquals(epoch, ProfileWorkIdentity.cancellationEpoch(app))
        assertEquals(true, ProfileWorkIdentity.commitPersistentWork(app, owner, epoch) {
            DownloadScheduler.update(app, item.copy(taskId = "unrelated"), generation + 1L)
        })
    }

    @Test fun productionDefaultRejectsMainThreadRotation() {
        val sessions = ProfileSessionCoordinator(7L)
        val owner = sessions.capture()
        assertThrows(IllegalStateException::class.java) { sessions.rotateLegacy() }
        assertTrue(sessions.isCurrent(owner))
        assertEquals(true, sessions.commit(owner) { true })
    }
}
