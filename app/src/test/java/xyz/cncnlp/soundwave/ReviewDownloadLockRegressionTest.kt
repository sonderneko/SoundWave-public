package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Authored only. Exercises the actual helper -> scheduler -> metadata/progress lock boundaries;
 * no network transfer, SAF provider, compiler or JVM execution is claimed. */
@RunWith(RobolectricTestRunner::class)
class ReviewDownloadLockRegressionTest {
    @Test fun finalizationAndSecondWorkerProgressShareOneAuthorityThenClearCompletes() {
        val app = RuntimeEnvironment.getApplication()
        val owner = ProfileWorkIdentity.persistentWork()
        val epoch = ProfileWorkIdentity.cancellationEpoch(app)
        val generation = app.getSharedPreferences("soundwave_download_scheduler", 0).getLong("clear_generation", 0L)
        val data = ContextData(app, owner, epoch)
        val item = DownloadItem(song = Song(id = "lock-final", name = "final", singer = "s", source = "wy"),
            size = 0L, time = 1L, path = "pending:lock-final", taskId = "lock-final", status = "done")
        val inFinalization = CountDownLatch(1)
        val progressAttempt = CountDownLatch(1)
        val progressPermit = CountDownLatch(1)
        // Daemon threads keep an unexpected monitor deadlock from retaining the test process.
        val pool = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable).apply { isDaemon = true } }
        try {
            val finalization = pool.submit<Boolean> {
                DownloadHelper.withCurrentGeneration(app, generation) {
                    inFinalization.countDown()
                    check(progressAttempt.await(2, TimeUnit.SECONDS))
                    // Give B the epoch-permit checkpoint while A holds the finalization barrier.
                    // Before the fix this reaches the opposite nesting and the futures time out;
                    // after consolidation B cannot enter yet, so A proceeds after the bounded wait.
                    progressPermit.await(200, TimeUnit.MILLISECONDS)
                    data.addDownloadDurably(item)
                }
            }
            assertTrue(inFinalization.await(2, TimeUnit.SECONDS))
            val progress = pool.submit<Boolean> {
                progressAttempt.countDown()
                ProfileWorkIdentity.commitPersistentWork(app, owner, epoch) {
                    progressPermit.countDown()
                    DownloadScheduler.update(app, item.copy(taskId = "lock-progress", status = "downloading"), generation)
                } == true
            }
            assertTrue(finalization.get(3, TimeUnit.SECONDS))
            assertTrue(progress.get(3, TimeUnit.SECONDS))
            assertTrue(pool.submit<Boolean> { DownloadScheduler.clearDownloads(app, false) }.get(3, TimeUnit.SECONDS))
            assertTrue(data.downloads().isEmpty())
            assertFalse(DownloadScheduler.update(app, item, generation))
        } finally { pool.shutdownNow() }
    }

    @Test fun clearAndOwnerEpochBothRejectLateMetadataAndProgress() {
        val app = RuntimeEnvironment.getApplication()
        val owner = ProfileWorkIdentity.persistentWork()
        val epoch = ProfileWorkIdentity.cancellationEpoch(app)
        val generation = app.getSharedPreferences("soundwave_download_scheduler", 0).getLong("clear_generation", 0L)
        val item = DownloadItem(song = Song(id = "stale", name = "stale", singer = "s", source = "wy"), size = 0L, time = 1L, path = "pending:stale")
        assertTrue(DownloadScheduler.clearDownloads(app, false))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            DownloadHelper.withCurrentGeneration(app, generation) { ContextData(app, owner, epoch).addDownloadDurably(item) }
        }
        ProfileWorkIdentity.cancelPersistentWork(app)
        assertFalse(ContextData(app, owner, epoch).addDownloadDurably(item))
        assertNull(ProfileWorkIdentity.commitPersistentWork(app, owner, epoch) {
            DownloadScheduler.update(app, item, generation + 1L)
        })
    }

    @Test fun staleTaskEpochCannotWriteIntoResumedDownloadStaging() {
        val app = RuntimeEnvironment.getApplication()
        val generation = DownloadScheduler.currentGeneration(app)
        val taskId = "epoch-fence-local-test"
        app.getSharedPreferences("soundwave_download_scheduler", 0)
            .edit().putLong("task_epoch_$taskId", 2L).commit()
        val staging = File(app.cacheDir, "epoch-fence-local-test.part")
        staging.delete()

        assertThrows(java.util.concurrent.CancellationException::class.java) {
            DownloadHelper.withCurrentTask(app, generation, taskId, 1L) { staging.writeText("stale") }
        }
        assertFalse("stale session must not create or overwrite staging", staging.exists())
        DownloadHelper.withCurrentTask(app, generation, taskId, 2L) { staging.writeText("current") }
        assertEquals("current", staging.readText())
        staging.delete()
    }
}
