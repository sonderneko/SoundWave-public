package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic unit tests for isolation between separate foreground (8, 16) and probe (1, 4)
 * scheduler instances. These test scheduler admission/cancellation only; they are not end-to-end
 * tests of real playback or download behavior.
 */
class ScriptSchedulerIsolationTest {
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun size(): Int = tasks.size

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun foreground(executor: Executor) = ScriptRequestScheduler(8, 16, executor)

    private fun probe(executor: Executor) = ScriptRequestScheduler(1, 4, executor)

    private fun submit(
        scheduler: ScriptRequestScheduler,
        owner: Any,
        cid: String,
        onRejected: () -> Unit = {},
        onCancelled: () -> Unit = {},
        work: (ScriptRequestScheduler.RequestControl) -> (() -> Unit)
    ): Boolean = scheduler.submit(
        owner = owner,
        cid = cid,
        onRejected = onRejected,
        onCancelled = onCancelled,
        onFailure = { error -> { throw error } },
        work = work
    )

    @Test
    fun saturatedProbeDoesNotPreventForegroundAdmissionOrExecution() {
        val foregroundExecutor = ManualExecutor()
        val probeExecutor = ManualExecutor()
        val foreground = foreground(foregroundExecutor)
        val probe = probe(probeExecutor)
        val foregroundOwner = Any()
        val probeOwner = Any()
        val foregroundCompleted = AtomicInteger()
        val probeCompleted = AtomicInteger()
        val probeRejected = AtomicInteger()
        val foregroundRejected = AtomicInteger()

        repeat(5) { index ->
            assertTrue(submit(probe, probeOwner, "probe-$index") {
                val done: () -> Unit = { probeCompleted.incrementAndGet() }
                done
            })
        }
        assertEquals(ScriptRequestScheduler.Snapshot(1, 4, 5), probe.snapshot())
        assertFalse(submit(probe, probeOwner, "probe-overflow", onRejected = { probeRejected.incrementAndGet() }) {
            error("a rejected probe must not run")
        })
        assertEquals(1, probeRejected.get())

        repeat(24) { index ->
            assertTrue(submit(foreground, foregroundOwner, "foreground-$index") {
                val done: () -> Unit = { foregroundCompleted.incrementAndGet() }
                done
            })
        }
        assertEquals(ScriptRequestScheduler.Snapshot(8, 16, 24), foreground.snapshot())
        assertFalse(submit(foreground, foregroundOwner, "foreground-overflow", onRejected = { foregroundRejected.incrementAndGet() }) {
            error("a rejected foreground request must not run")
        })
        assertEquals(1, foregroundRejected.get())

        foregroundExecutor.runAll()
        probeExecutor.runAll()
        assertEquals(24, foregroundCompleted.get())
        assertEquals(5, probeCompleted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), foreground.snapshot())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), probe.snapshot())
    }

    @Test
    fun probeCancelOwnerDoesNotCancelForegroundWithSameOwnerAndCid() {
        val foregroundExecutor = ManualExecutor()
        val probeExecutor = ManualExecutor()
        val foreground = foreground(foregroundExecutor)
        val probe = probe(probeExecutor)
        val sharedOwner = Any()
        val sharedCid = "detect-and-play-same-cid"
        val foregroundCompleted = AtomicInteger()
        val probeCompleted = AtomicInteger()
        val probeCancelled = AtomicInteger()
        val probeStarted = AtomicInteger()

        // Deliberately use the very same owner object and cid for detection and playback.
        assertTrue(submit(foreground, sharedOwner, sharedCid) {
            val done: () -> Unit = { foregroundCompleted.incrementAndGet() }
            done
        })
        assertTrue(submit(probe, sharedOwner, sharedCid, onCancelled = { probeCancelled.incrementAndGet() }) {
            probeStarted.incrementAndGet()
            val done: () -> Unit = { probeCompleted.incrementAndGet() }
            done
        })
        assertEquals(ScriptRequestScheduler.Snapshot(1, 0, 1), foreground.snapshot())
        assertEquals(ScriptRequestScheduler.Snapshot(1, 0, 1), probe.snapshot())

        probe.cancelOwner(sharedOwner)
        assertEquals(1, probeCancelled.get())
        assertEquals("canceling in the probe instance leaves foreground state intact", 1, foreground.snapshot().tracked)
        // A dispatched wrapper retains its slot until it observes cancellation and exits.
        assertEquals(ScriptRequestScheduler.Snapshot(1, 0, 1), probe.snapshot())

        probeExecutor.runAll()
        foregroundExecutor.runAll()
        assertEquals(0, probeStarted.get())
        assertEquals(0, probeCompleted.get())
        assertEquals(1, foregroundCompleted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), foreground.snapshot())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), probe.snapshot())
    }

    @Test
    fun fullForegroundDoesNotPreventIndependentProbeFromRunning() {
        val foregroundExecutor = ManualExecutor()
        val probeExecutor = ManualExecutor()
        val foreground = foreground(foregroundExecutor)
        val probe = probe(probeExecutor)
        val foregroundOwner = Any()
        val probeOwner = Any()
        val foregroundCompleted = AtomicInteger()
        val probeCompleted = AtomicInteger()
        val rejected = AtomicInteger()

        repeat(24) { index ->
            assertTrue(submit(foreground, foregroundOwner, "busy-$index") {
                val done: () -> Unit = { foregroundCompleted.incrementAndGet() }
                done
            })
        }
        assertFalse(submit(foreground, foregroundOwner, "busy-overflow", onRejected = { rejected.incrementAndGet() }) {
            error("foreground overflow must not run")
        })
        assertEquals(ScriptRequestScheduler.Snapshot(8, 16, 24), foreground.snapshot())
        assertEquals(1, rejected.get())

        assertTrue(submit(probe, probeOwner, "independent-probe") {
            val done: () -> Unit = { probeCompleted.incrementAndGet() }
            done
        })
        assertEquals(1, probeExecutor.size())
        probeExecutor.runAll()
        assertEquals(1, probeCompleted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), probe.snapshot())
        assertEquals("the foreground queue was untouched by probe execution", 24, foreground.snapshot().tracked)

        foregroundExecutor.runAll()
        assertEquals(24, foregroundCompleted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), foreground.snapshot())
    }

    @Test
    fun queuedCancellationAndCompletionAreIndependentAndLeaveNoTrackedWork() {
        val foregroundExecutor = ManualExecutor()
        val probeExecutor = ManualExecutor()
        val foreground = foreground(foregroundExecutor)
        val probe = probe(probeExecutor)
        val foregroundOwner = Any()
        val probeOwner = Any()
        val foregroundCompleted = AtomicInteger()
        val probeCompleted = AtomicInteger()
        val foregroundCancelled = AtomicInteger()
        val probeCancelled = AtomicInteger()

        repeat(8) { index ->
            assertTrue(submit(foreground, foregroundOwner, "foreground-running-$index") {
                val done: () -> Unit = { foregroundCompleted.incrementAndGet() }
                done
            })
        }
        assertTrue(submit(foreground, foregroundOwner, "foreground-queued", onCancelled = { foregroundCancelled.incrementAndGet() }) {
            error("cancelled foreground queue entry must not run")
        })
        assertTrue(submit(probe, probeOwner, "probe-running") {
            val done: () -> Unit = { probeCompleted.incrementAndGet() }
            done
        })
        assertTrue(submit(probe, probeOwner, "probe-queued", onCancelled = { probeCancelled.incrementAndGet() }) {
            error("cancelled probe queue entry must not run")
        })

        assertEquals(ScriptRequestScheduler.Snapshot(8, 1, 9), foreground.snapshot())
        assertEquals(ScriptRequestScheduler.Snapshot(1, 1, 2), probe.snapshot())
        assertTrue(foreground.cancel(foregroundOwner, "foreground-queued"))
        assertTrue(probe.cancel(probeOwner, "probe-queued"))
        assertEquals(1, foregroundCancelled.get())
        assertEquals(1, probeCancelled.get())
        foregroundExecutor.runAll()
        probeExecutor.runAll()

        assertEquals(8, foregroundCompleted.get())
        assertEquals(1, probeCompleted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), foreground.snapshot())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), probe.snapshot())
    }

    @Test
    fun executorRejectionReleasesSchedulerStateAndLaterSubmissionCanRun() {
        val acceptedExecutor = ManualExecutor()
        val rejectNext = AtomicInteger(1)
        val executor = Executor { command ->
            if (rejectNext.getAndSet(0) == 1) throw IllegalStateException("synthetic rejection")
            acceptedExecutor.execute(command)
        }
        val scheduler = foreground(executor)
        val owner = Any()
        val failures = AtomicInteger()
        val completed = AtomicInteger()

        assertTrue(scheduler.submit(
            owner = owner,
            cid = "rejected-dispatch",
            onRejected = {},
            onFailure = { error ->
                assertTrue(error is IllegalStateException)
                val delivery: () -> Unit = { failures.incrementAndGet() }
                delivery
            },
            work = { error("executor-rejected task must not run") }
        ))
        assertEquals(1, failures.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())

        assertTrue(submit(scheduler, owner, "after-rejection") {
            val done: () -> Unit = { completed.incrementAndGet() }
            done
        })
        acceptedExecutor.runAll()
        assertEquals(1, completed.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun cancellingRunningEntryDeliversCancellationOnlyOnceAndSuppressesLateCompletion() {
        val executor = ManualExecutor()
        val scheduler = foreground(executor)
        val owner = Any()
        val cancelled = AtomicInteger()
        val started = AtomicInteger()
        val completed = AtomicInteger()

        assertTrue(submit(scheduler, owner, "cancel-once", onCancelled = { cancelled.incrementAndGet() }) {
            started.incrementAndGet()
            val done: () -> Unit = { completed.incrementAndGet() }
            done
        })
        assertEquals(1, executor.size())
        assertTrue(scheduler.cancel(owner, "cancel-once"))
        assertFalse(scheduler.cancel(owner, "cancel-once"))
        assertEquals(1, cancelled.get())

        // The already-dispatched wrapper observes cancellation and cannot run or deliver completion.
        executor.runAll()
        assertEquals(0, started.get())
        assertEquals(0, completed.get())
        assertEquals(1, cancelled.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }
}
