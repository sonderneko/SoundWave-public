package xyz.cncnlp.soundwave

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenStatsWriteQueueTest {
    @Test
    fun writeBeforeSnapshotIsAwaitedAndSnapshotDoesNotChangeAfterLaterWrite() = runTest {
        val executor = ManualExecutor()
        val queue = newQueue(executor)
        val state = mutableListOf<String>()

        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.MARK_PLAY) {
            state += "first"
            true
        })
        val snapshot = async { queue.snapshot("item") { state.toList() } }
        runCurrent() // Admit the snapshot between the two writes.
        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.DURATION) {
            state += "later"
            true
        })

        assertEquals(3, executor.size)
        executor.runNext() // first write
        assertFalse(snapshot.isCompleted)
        executor.runNext() // snapshot read
        executor.runNext() // later write

        val captured = snapshot.await()
        assertEquals(listOf("first"), captured)
        assertEquals(listOf("first", "later"), state)
    }

    @Test
    fun falseWriteIsDiagnosedAndSnapshotFailsClosedWithoutReplaying() = runTest {
        val executor = ManualExecutor()
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)
        var writes = 0
        var reads = 0

        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.DURATION) {
            writes++
            false
        })
        executor.runAll()
        assertEquals(ListenStatsWriteQueue.Failure.UNCONFIRMED, events.single().reason)
        val failure = snapshotFailure(queue, "item") { reads++; "must not read" }

        assertEquals(ListenStatsWriteQueue.Failure.UNCONFIRMED, failure.reason)
        assertEquals(1, writes)
        assertEquals(0, reads)
        assertEquals(0, executor.size)
    }

    @Test
    fun thrownWriteIsDiagnosedAndMakesThatKeyFailClosed() = runTest {
        val executor = ManualExecutor()
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)
        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.MARK_PLAY) {
            throw IllegalArgumentException("private write detail")
        })
        executor.runAll()

        assertEquals(ListenStatsWriteQueue.Failure.EXCEPTION, events.single().reason)
        assertEquals(ListenStatsWriteQueue.Failure.EXCEPTION,
            snapshotFailure(queue, "item") { "not read" }.reason)
    }

    @Test
    fun rejectedWriteIsDiagnosedAndMakesThatKeyFailClosed() = runTest {
        val executor = ManualExecutor().apply { rejectNext = true }
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)

        assertFalse(queue.submit("item", ListenStatsWriteQueue.Operation.MARK_PLAY) { true })
        assertEquals(ListenStatsWriteQueue.Failure.REJECTED, events.single().reason)
        assertEquals(ListenStatsWriteQueue.Failure.REJECTED,
            snapshotFailure(queue, "item") { "not read" }.reason)
    }

    @Test
    fun staleOwnerIsDiagnosedAndWriteIsSkipped() = runTest {
        val executor = ManualExecutor()
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)
        var writes = 0

        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.MARK_PLAY,
            isCurrent = { false }) { writes++; true })
        executor.runAll()

        assertEquals(0, writes)
        assertEquals(ListenStatsWriteQueue.Failure.STALE_OWNER, events.single().reason)
        assertEquals(ListenStatsWriteQueue.Failure.STALE_OWNER,
            snapshotFailure(queue, "item") { "not read" }.reason)
    }

    @Test
    fun laterSuccessDoesNotClearAnEarlierUnconfirmedWrite() = runTest {
        val executor = ManualExecutor()
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)
        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.DURATION) { false })
        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.MARK_PLAY) { true })
        executor.runAll()

        assertEquals(1, events.size)
        assertEquals(ListenStatsWriteQueue.Failure.UNCONFIRMED, events.single().reason)
        assertEquals(ListenStatsWriteQueue.Failure.UNCONFIRMED,
            snapshotFailure(queue, "item") { "not read" }.reason)
    }

    @Test
    fun failureForOneKeyDoesNotContaminateAnotherKey() = runTest {
        val executor = ManualExecutor()
        val queue = newQueue(executor)
        assertTrue(queue.submit("broken", ListenStatsWriteQueue.Operation.DURATION) { false })
        executor.runAll()

        assertEquals("healthy snapshot", queue.snapshot("healthy") { "healthy snapshot" })
    }

    @Test
    fun cancellingQueuedSnapshotSkipsReaderButDoesNotCancelAcceptedWrite() = runTest {
        val executor = ManualExecutor()
        val queue = newQueue(executor)
        var writes = 0
        var reads = 0
        assertTrue(queue.submit("item", ListenStatsWriteQueue.Operation.DURATION) {
            writes++
            true
        })
        val pendingSnapshot = async { queue.snapshot("item") { reads++; "snapshot" } }
        runCurrent()
        assertEquals(2, executor.size)

        pendingSnapshot.cancel()
        runCurrent()
        executor.runAll()

        assertTrue(pendingSnapshot.isCancelled)
        assertEquals(1, writes)
        assertEquals(0, reads)
    }

    @Test
    fun throwingDiagnosticCallbackDoesNotStopQueue() = runTest {
        val executor = ManualExecutor()
        var successfulWriteRan = false
        val queue = newQueue(executor) { throw IllegalStateException("diagnostic failure") }
        assertTrue(queue.submit("broken", ListenStatsWriteQueue.Operation.DURATION) { false })
        assertTrue(queue.submit("healthy", ListenStatsWriteQueue.Operation.MARK_PLAY) {
            successfulWriteRan = true
            true
        })
        executor.runAll()

        assertTrue(successfulWriteRan)
        assertEquals("ok", queue.snapshot("healthy") { "ok" })
    }

    @Test
    fun idleSnapshotReadsImmediatelyWithoutSchedulingWorker() = runTest {
        val executor = ManualExecutor()
        val queue = newQueue(executor)
        assertEquals(42, queue.snapshot("item") { 42 })
        assertEquals(0, executor.size)
    }

    @Test
    fun readerFailureReportsSafeExceptionWithoutLeakingReaderMessage() = runTest {
        val executor = ManualExecutor()
        val events = mutableListOf<ListenStatsWriteQueue.Diagnostic>()
        val queue = newQueue(executor, events::add)
        val failure = snapshotFailure(queue, "item") {
            throw IllegalStateException("sensitive reader detail")
        }

        assertEquals(ListenStatsWriteQueue.Failure.READ_FAILED, failure.reason)
        assertFalse(failure.message.orEmpty().contains("sensitive reader detail"))
        assertNotNull(failure.message)
        assertEquals(ListenStatsWriteQueue.Failure.READ_FAILED, events.single().reason)
        assertEquals(ListenStatsWriteQueue.Operation.SNAPSHOT, events.single().operation)
        assertEquals(0, executor.size)
    }

    private fun newQueue(
        executor: ManualExecutor,
        diagnostic: (ListenStatsWriteQueue.Diagnostic) -> Unit = {}
    ) = ListenStatsWriteQueue<String>(executor, diagnostic)

    private suspend fun snapshotFailure(
        queue: ListenStatsWriteQueue<String>,
        key: String,
        read: () -> String
    ): ListenStatsSnapshotException {
        val thrown = try {
            queue.snapshot(key, read)
            null
        } catch (failure: ListenStatsSnapshotException) {
            failure
        }
        assertNotNull("Expected snapshot to fail closed", thrown)
        return thrown!!
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var rejectNext = false
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            if (rejectNext) {
                rejectNext = false
                throw RejectedExecutionException("test rejection")
            }
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }
}
