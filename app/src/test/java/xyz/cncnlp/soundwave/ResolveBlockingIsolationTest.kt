package xyz.cncnlp.soundwave

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production stage, controlled non-cooperative providers; no platform traffic. */
class ResolveBlockingIsolationTest {
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue("worker did not enter", latch.await(5, TimeUnit.SECONDS))
    }

    private suspend fun awaitAdmission(purpose: ResolveTicket.Purpose, permits: Int) {
        val lane = ResolveBlockingStage.javaClass.getDeclaredField(purpose.name.lowercase())
            .apply { isAccessible = true }.get(ResolveBlockingStage)
        val admission = lane.javaClass.getDeclaredField("admission").apply { isAccessible = true }
            .get(lane) as kotlinx.coroutines.sync.Semaphore
        withTimeout(1500) { while (admission.availablePermits != permits) delay(1) }
    }

    private fun CoroutineScope.blockers(
        purpose: ResolveTicket.Purpose, count: Int, entered: CountDownLatch,
        release: CountDownLatch, exited: CountDownLatch
    ) = (1..count).map {
        launch(ResolveBlockingStage.context(purpose), start = CoroutineStart.UNDISPATCHED) {
            ResolveBlockingStage.run {
                entered.countDown()
                try { release.await(); "late" } finally { exited.countDown() }
            }
        }
    }

    @Test fun abandonedBackgroundCannotDelayForegroundOrOtherPurposes() = runBlocking {
        val entered = CountDownLatch(4)
        val exited = CountDownLatch(4)
        val release = CountDownLatch(1)
        val jobs = blockers(ResolveTicket.Purpose.DOWNLOAD, 2, entered, release, exited) +
            blockers(ResolveTicket.Purpose.PROBE, 1, entered, release, exited) +
            blockers(ResolveTicket.Purpose.PREFETCH, 1, entered, release, exited)
        try {
            await(entered)
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            // Caller cancellation has returned, but all four actual workers are still blocked.
            assertEquals(4L, exited.count)
            assertEquals("play", withTimeout(1500) { ResolveBlockingStage.run { "play" } })
        } finally {
            release.countDown(); await(exited)
        }
    }

    @Test fun saturatedDownloadDoesNotBlockProbeOrPrefetch() = runBlocking {
        val entered = CountDownLatch(2); val exited = CountDownLatch(2); val release = CountDownLatch(1)
        val jobs = blockers(ResolveTicket.Purpose.DOWNLOAD, 2, entered, release, exited)
        try {
            await(entered)
            for (purpose in listOf(ResolveTicket.Purpose.PLAY, ResolveTicket.Purpose.PROBE, ResolveTicket.Purpose.PREFETCH)) {
                assertEquals(purpose.name, withTimeout(1500) {
                    withContext(ResolveBlockingStage.context(purpose)) { ResolveBlockingStage.run { purpose.name } }
                })
            }
        } finally { release.countDown(); jobs.joinAll(); await(exited) }
    }

    @Test fun allLanesBoundActiveAndQueuedAndRecoverAfterCancellation() = runBlocking {
        for ((purpose, active, queued) in listOf(
            Triple(ResolveTicket.Purpose.PLAY, 4, 8),
            Triple(ResolveTicket.Purpose.DOWNLOAD, 2, 4),
            Triple(ResolveTicket.Purpose.PREFETCH, 1, 2),
            Triple(ResolveTicket.Purpose.PROBE, 1, 2)
        )) {
            val entered = CountDownLatch(active); val exited = CountDownLatch(active); val release = CountDownLatch(1)
            val jobs = blockers(purpose, active, entered, release, exited)
            val ran = AtomicInteger()
            val waiters = mutableListOf<Job>()
            try {
                await(entered)
                repeat(queued) {
                    waiters += launch(ResolveBlockingStage.context(purpose), start = CoroutineStart.UNDISPATCHED) {
                        ResolveBlockingStage.run { ran.incrementAndGet() }
                    }
                }
                val rejected = withContext(ResolveBlockingStage.context(purpose)) {
                    runCatching { ResolveBlockingStage.run { error("overflow must not execute") } }.exceptionOrNull()
                }
                assertTrue("$purpose must reject overflow", rejected is java.util.concurrent.RejectedExecutionException)
                waiters.forEach { it.cancel() }; waiters.joinAll()
                assertEquals(0, ran.get())
                // Cancelled active callers must NOT create replacement workers.
                jobs.forEach { it.cancel() }; jobs.joinAll()
                assertEquals(active.toLong(), exited.count)
                awaitAdmission(purpose, queued)
                assertNull(withTimeoutOrNull(100) {
                    withContext(ResolveBlockingStage.context(purpose)) { ResolveBlockingStage.run { "too early" } }
                })
            } finally {
                release.countDown(); jobs.joinAll(); waiters.forEach { it.cancel() }; waiters.joinAll(); await(exited)
            }
            awaitAdmission(purpose, active + queued)
            assertEquals("recovered", withTimeout(1500) {
                withContext(ResolveBlockingStage.context(purpose)) { ResolveBlockingStage.run { "recovered" } }
            })
        }
    }

    @Test fun queuedCancellationNeverStartsProvider() = runBlocking {
        val entered = CountDownLatch(1); val exited = CountDownLatch(1); val release = CountDownLatch(1)
        val jobs = blockers(ResolveTicket.Purpose.PROBE, 1, entered, release, exited)
        val ran = AtomicInteger()
        try {
            await(entered)
            repeat(40) {
                val queued = launch(ResolveBlockingStage.context(ResolveTicket.Purpose.PROBE), start = CoroutineStart.UNDISPATCHED) {
                    ResolveBlockingStage.run { ran.incrementAndGet() }
                }
                queued.cancelAndJoin()
                // Worker completion owns admission cleanup, not caller completion.
                awaitAdmission(ResolveTicket.Purpose.PROBE, 2)
            }
            assertEquals(0, ran.get())
        } finally { release.countDown(); jobs.joinAll(); await(exited) }
        assertEquals(1, withTimeout(1500) {
            withContext(ResolveBlockingStage.context(ResolveTicket.Purpose.PROBE)) { ResolveBlockingStage.run { ran.incrementAndGet() } }
        })
    }

    @Test fun cancelledAResponseCannotOverwriteB() = runBlocking {
        val entered = CountDownLatch(1); val exited = CountDownLatch(1); val release = CountDownLatch(1)
        var nowPlaying = ""
        val a = launch(start = CoroutineStart.UNDISPATCHED) {
            nowPlaying = ResolveBlockingStage.run {
                entered.countDown()
                try { release.await(); "A" } finally { exited.countDown() }
            }
        }
        try {
            await(entered); a.cancelAndJoin()
            nowPlaying = withTimeout(1500) { ResolveBlockingStage.run { "B" } }
        } finally { release.countDown(); await(exited) }
        delay(30)
        assertEquals("B", nowPlaying)
    }
}
