package xyz.cncnlp.soundwave

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import xyz.cncnlp.soundwave.diagnostics.ConcurrentWorkDiagnostics
import xyz.cncnlp.soundwave.prefetch.BoundedPrefetchWorkPool

class BoundedPrefetchWorkPoolTest {
    @Test
    fun `admission bounds active and queued work and cancellation returns capacity`() = runBlocking {
        val pool = BoundedPrefetchWorkPool(
            maxActive = 2,
            maxQueued = 2,
            reservedActiveForPlayback = 1,
            reservedAdmissionForPlayback = 1,
        )
        val entered = CountDownLatch(2)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = (1..4).map {
            pool.submit(this, BoundedPrefetchWorkPool.Priority.PLAYBACK) {
                val now = active.incrementAndGet()
                peak.updateAndGet { old -> maxOf(old, now) }
                entered.countDown()
                try {
                    awaitCancellation()
                } finally {
                    active.decrementAndGet()
                }
            }!!
        }

        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            assertEquals(BoundedPrefetchWorkPool.Snapshot(2, 2, 4, 0, 0), pool.snapshot())
            assertNull("overflow is rejected instead of growing the queue", pool.submit(this, BoundedPrefetchWorkPool.Priority.PLAYBACK) {})

            jobs.drop(2).forEach { it.cancelAndJoin() }
            assertEquals(0, pool.snapshot().queued)
            jobs.take(2).forEach { it.cancelAndJoin() }
            assertEquals(0, pool.snapshot().admitted)
            assertEquals(2, peak.get())

            val recovered = CompletableDeferred<Unit>()
            val next = pool.submit(this, BoundedPrefetchWorkPool.Priority.PLAYBACK) { recovered.complete(Unit) }!!
            withTimeout(2_000) { recovered.await() }
            next.join()
            assertEquals(0, pool.snapshot().admitted)
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    @Test
    fun `playback prefetch has a reserved active slot when ui prefetch is saturated`() = runBlocking {
        val pool = BoundedPrefetchWorkPool(
            maxActive = 3,
            maxQueued = 5,
            reservedActiveForPlayback = 1,
            reservedAdmissionForPlayback = 2,
        )
        val uiEntered = CountDownLatch(2)
        val playbackEntered = CompletableDeferred<Unit>()
        val uiJobs = (1..4).map {
            pool.submit(this, BoundedPrefetchWorkPool.Priority.UI) {
                uiEntered.countDown()
                awaitCancellation()
            }!!
        }

        try {
            withContext(Dispatchers.IO) { assertTrue(uiEntered.await(5, TimeUnit.SECONDS)) }
            assertEquals(2, pool.snapshot().uiRunning)
            assertEquals(2, pool.snapshot().queued)

            val playback = pool.submit(this, BoundedPrefetchWorkPool.Priority.PLAYBACK) {
                playbackEntered.complete(Unit)
                awaitCancellation()
            }!!
            withTimeout(2_000) { playbackEntered.await() }
            assertEquals(3, pool.snapshot().running)
            assertEquals(2, pool.snapshot().uiRunning)

            withTimeout(2_000) {
                withContext(ResolveBlockingStage.context(ResolveTicket.Purpose.PLAY)) {
                    assertEquals("foreground", ResolveBlockingStage.run { "foreground" })
                }
            }
            playback.cancelAndJoin()
        } finally {
            uiJobs.forEach { it.cancel() }
            uiJobs.forEach { it.join() }
        }
        assertEquals(0, pool.snapshot().admitted)
    }

    @Test
    fun `pool records queue lifecycle and cancellation without raw song identity`() = runBlocking {
        ConcurrentWorkDiagnostics.clearForTest()
        val pool = BoundedPrefetchWorkPool(
            maxActive = 1,
            maxQueued = 1,
            reservedActiveForPlayback = 0,
            reservedAdmissionForPlayback = 0,
        )
        val entered = CompletableDeferred<Unit>()
        val rawIdentity = "song:private-provider-id"
        val job = pool.submit(
            this,
            BoundedPrefetchWorkPool.Priority.PLAYBACK,
            diagnosticIdentity = rawIdentity,
            cancelReason = ConcurrentWorkDiagnostics.CancelReason.PAGE_LEFT
        ) {
            entered.complete(Unit)
            awaitCancellation()
        }!!

        try {
            withTimeout(2_000) { entered.await() }
            job.cancelAndJoin()
            val events = ConcurrentWorkDiagnostics.recent()
            assertTrue(events.any {
                it.kind == ConcurrentWorkDiagnostics.WorkKind.PREFETCH &&
                    it.phase == ConcurrentWorkDiagnostics.Phase.QUEUE &&
                    it.outcome == ConcurrentWorkDiagnostics.Outcome.ACCEPTED
            })
            assertTrue(events.any { it.outcome == ConcurrentWorkDiagnostics.Outcome.STARTED && it.activeCount == 1 })
            val cancelled = events.first { it.outcome == ConcurrentWorkDiagnostics.Outcome.CANCELLED }
            assertEquals(ConcurrentWorkDiagnostics.CancelReason.PAGE_LEFT, cancelled.cancelReason)
            assertEquals(1L, cancelled.queueVersion)
            assertTrue(events.all { !it.safeLogLine().contains(rawIdentity) })
        } finally {
            job.cancel()
        }
    }
}
