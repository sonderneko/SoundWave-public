package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScriptRequestSchedulerTest {
    private class ManualExecutor : Executor {
        private val lock = Any()
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(lock) { tasks.addLast(command) }
        }

        fun takeAll(): List<Runnable> = synchronized(lock) {
            buildList { while (tasks.isNotEmpty()) add(tasks.removeFirst()) }
        }

        fun takeOne(): Runnable = synchronized(lock) { tasks.removeFirst() }

        fun size(): Int = synchronized(lock) { tasks.size }
    }

    private class FakeCall {
        val cancelled = AtomicInteger()
        val cancelSignal = CountDownLatch(1)
        fun cancel() {
            cancelled.incrementAndGet()
            cancelSignal.countDown()
        }
    }

    private fun scheduler(executor: ManualExecutor) = ScriptRequestScheduler(8, 16, executor)

    private fun submit(
        scheduler: ScriptRequestScheduler,
        owner: Any,
        cid: String,
        onRejected: () -> Unit = {},
        priority: ScriptRequestScheduler.Priority = ScriptRequestScheduler.Priority.PLAY,
        work: (ScriptRequestScheduler.RequestControl) -> (() -> Unit)
    ): Boolean = scheduler.submit(
        owner = owner,
        cid = cid,
        onRejected = onRejected,
        onFailure = { error -> { throw error } },
        work = work,
        priority = priority
    )

    @Test
    fun admitsEightRunsSixteenQueuedAndRejectsTheTwentyFifthImmediately() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val releaseFirstWave = CountDownLatch(1)
        val firstWaveStarted = CountDownLatch(8)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val transportsStarted = AtomicInteger()
        val callbacks = AtomicInteger()

        repeat(24) { index ->
            val id = index + 1
            assertTrue(submit(scheduler, owner, "burst-$id") {
                    transportsStarted.incrementAndGet()
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { old -> maxOf(old, now) }
                    if (id <= 8) {
                        firstWaveStarted.countDown()
                        check(releaseFirstWave.await(5, TimeUnit.SECONDS))
                    }
                    active.decrementAndGet()
                    val completion: () -> Unit = { callbacks.incrementAndGet() }
                    completion
                })
        }
        assertEquals("only eight task wrappers are dispatched", 8, executor.size())
        assertEquals(0, transportsStarted.get())
        assertEquals(ScriptRequestScheduler.Snapshot(8, 16, 24), scheduler.snapshot())

        val overflowErrors = AtomicInteger()
        assertFalse(submit(scheduler, owner, "burst-25", onRejected = { overflowErrors.incrementAndGet() }) {
            error("overflow must never enter the transport")
        })
        assertEquals("queue overflow fails synchronously", 1, overflowErrors.get())

        val workers = Executors.newFixedThreadPool(8)
        executor.takeAll().forEach { workers.submit(it) }
        assertTrue("the first eight fake transports should enter concurrently", firstWaveStarted.await(3, TimeUnit.SECONDS))
        assertEquals(8, maxActive.get())
        releaseFirstWave.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))

        while (true) {
            val batch = executor.takeAll()
            if (batch.isEmpty()) break
            batch.forEach(Runnable::run)
        }
        assertEquals(24, transportsStarted.get())
        assertEquals(24, callbacks.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun eightRequestPromiseAllStyleBurstRemainsConcurrent() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val started = CountDownLatch(8)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        repeat(8) { index ->
            submit(scheduler, owner, "promise-all-$index") {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { old -> maxOf(old, now) }
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                active.decrementAndGet()
                val completion: () -> Unit = {}
                completion
            }
        }
        val workers = Executors.newFixedThreadPool(8)
        executor.takeAll().forEach { workers.submit(it) }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        assertEquals(8, maxActive.get())
        release.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun freeingOneRunningSlotDispatchesExactlyOneQueuedRequest() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val started = mutableListOf<Int>()
        repeat(9) { index ->
            val id = index + 1
            submit(scheduler, owner, "one-slot-$id") {
                started.add(id)
                val completion: () -> Unit = {}
                completion
            }
        }
        assertEquals(8, executor.size())
        assertEquals(ScriptRequestScheduler.Snapshot(8, 1, 9), scheduler.snapshot())

        executor.takeOne().run()
        assertEquals(listOf(1), started)
        assertEquals("one freed slot promotes exactly one queued request", ScriptRequestScheduler.Snapshot(8, 0, 8), scheduler.snapshot())
        assertEquals(8, executor.size())

        while (true) {
            val batch = executor.takeAll()
            if (batch.isEmpty()) break
            batch.forEach(Runnable::run)
        }
        assertEquals((1..9).toSet(), started.toSet())
        assertEquals(9, started.size)
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun duplicateCidCannotCauseTwoCallbacks() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val callbacks = AtomicInteger()
        val duplicateRejections = AtomicInteger()
        assertTrue(submit(scheduler, owner, "same-cid") { callbacks.incrementAndGet(); val done: () -> Unit = {}; done })
        assertFalse(submit(scheduler, owner, "same-cid", onRejected = { duplicateRejections.incrementAndGet() }) {
            error("duplicate cid must not start another request")
        })
        assertEquals("duplicate cid is ignored rather than dispatched as a second error", 0, duplicateRejections.get())
        executor.takeAll().forEach(Runnable::run)
        assertEquals(1, callbacks.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun cancellingQueuedRequestPreventsItsFakeTransportFromRunning() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val started = AtomicInteger()
        repeat(9) { index ->
            submit(scheduler, owner, "cancel-queued-$index") {
                started.incrementAndGet()
                val completion: () -> Unit = {}
                completion
            }
        }
        assertEquals(8, executor.size())
        assertEquals(ScriptRequestScheduler.Snapshot(8, 1, 9), scheduler.snapshot())
        assertTrue(scheduler.cancel(owner, "cancel-queued-8"))
        assertEquals(ScriptRequestScheduler.Snapshot(8, 0, 8), scheduler.snapshot())
        executor.takeAll().forEach(Runnable::run)
        assertEquals(8, started.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun cancellingRunningRequestCancelsFakeCallAndSuppressesLateCompletion() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val owner = Any()
        val fakeCall = FakeCall()
        val started = CountDownLatch(1)
        val callbacks = AtomicInteger()
        submit(scheduler, owner, "cancel-running") { control ->
            assertTrue(control.installCancellation(fakeCall) { fakeCall.cancel() })
            started.countDown()
            check(fakeCall.cancelSignal.await(3, TimeUnit.SECONDS))
            val completion: () -> Unit = { callbacks.incrementAndGet() }
            completion
        }
        val worker = Thread(executor.takeAll().single())
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(scheduler.cancel(owner, "cancel-running"))
        worker.join(3_000)
        assertFalse(worker.isAlive)
        assertEquals(1, fakeCall.cancelled.get())
        assertEquals("cancel and stale worker completion cannot both dispatch", 0, callbacks.get())
        assertFalse(scheduler.cancel(owner, "cancel-running"))
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun runtimesShareOneLimitButOwnerCancellationLeavesOtherRuntimeWorkIntact() {
        val executor = ManualExecutor()
        val scheduler = scheduler(executor)
        val firstRuntime = Any()
        val secondRuntime = Any()
        val secondCallbacks = AtomicInteger()
        repeat(8) { index -> submit(scheduler, firstRuntime, "runtime-a-$index") { error("cancelled task ran") } }
        repeat(8) { index -> submit(scheduler, secondRuntime, "runtime-b-$index") {
            secondCallbacks.incrementAndGet()
            val completion: () -> Unit = {}
            completion
        } }
        assertEquals("two runtimes share the same eight running slots", 8, executor.size())
        assertEquals(ScriptRequestScheduler.Snapshot(8, 8, 16), scheduler.snapshot())

        scheduler.cancelOwner(firstRuntime)
        assertEquals(ScriptRequestScheduler.Snapshot(8, 8, 16), scheduler.snapshot())
        executor.takeAll().forEach(Runnable::run)
        while (true) {
            val batch = executor.takeAll()
            if (batch.isEmpty()) break
            batch.forEach(Runnable::run)
        }
        assertEquals(8, secondCallbacks.get())
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun playbackHasAReservedWorkerWhenLowerPriorityWorkSaturatesItsAllowance() {
        val executor = ManualExecutor()
        val scheduler = ScriptRequestScheduler(3, 8, executor, reservePlaybackSlots = true)
        val owner = Any()
        fun noOp(control: ScriptRequestScheduler.RequestControl): () -> Unit = {}

        assertTrue(submit(scheduler, owner, "download-1", priority = ScriptRequestScheduler.Priority.DOWNLOAD, work = ::noOp))
        assertTrue(submit(scheduler, owner, "download-2", priority = ScriptRequestScheduler.Priority.DOWNLOAD, work = ::noOp))
        assertTrue(submit(scheduler, owner, "download-3", priority = ScriptRequestScheduler.Priority.DOWNLOAD, work = ::noOp))
        assertEquals(ScriptRequestScheduler.Snapshot(2, 1, 3), scheduler.snapshot())

        assertTrue(submit(scheduler, owner, "play", priority = ScriptRequestScheduler.Priority.PLAY, work = ::noOp))
        assertEquals("PLAY occupies the reserved third worker", ScriptRequestScheduler.Snapshot(3, 1, 4), scheduler.snapshot())
        assertEquals(3, executor.size())
        while (executor.size() > 0) executor.takeAll().forEach(Runnable::run)
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }

    @Test
    fun weightedQueueServicesDownloadsAndPrefetchDuringSustainedPlayback() {
        val executor = ManualExecutor()
        val scheduler = ScriptRequestScheduler(1, 16, executor)
        val owner = Any()
        val started = mutableListOf<String>()
        fun record(label: String): (ScriptRequestScheduler.RequestControl) -> (() -> Unit) = {
            started.add(label)
            val done: () -> Unit = {}
            done
        }

        assertTrue(submit(scheduler, owner, "blocker", work = record("blocker")))
        repeat(4) { assertTrue(submit(scheduler, owner, "play-$it", priority = ScriptRequestScheduler.Priority.PLAY, work = record("play-$it"))) }
        repeat(4) { assertTrue(submit(scheduler, owner, "download-$it", priority = ScriptRequestScheduler.Priority.DOWNLOAD, work = record("download-$it"))) }
        repeat(4) { assertTrue(submit(scheduler, owner, "prefetch-$it", priority = ScriptRequestScheduler.Priority.PREFETCH, work = record("prefetch-$it"))) }

        while (executor.size() > 0) executor.takeOne().run()
        assertEquals(
            listOf("play-0", "play-1", "play-2", "play-3", "download-0", "download-1", "prefetch-0"),
            started.drop(1).take(7)
        )
        assertEquals(13, started.size)
        assertEquals(ScriptRequestScheduler.Snapshot(0, 0, 0), scheduler.snapshot())
    }
}
