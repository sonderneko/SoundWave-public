package xyz.cncnlp.soundwave

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** FIFO writes and immutable snapshots. No delta retry: a failed commit may already be in memory. */
internal class ListenStatsWriteQueue<K>(
    private val executor: Executor,
    private val diagnostic: (Diagnostic) -> Unit = {
        android.util.Log.w("ListenStats", "stats ${if (it.operation == Operation.SNAPSHOT) "snapshot" else "write"} failed: seq=${it.sequence} op=${it.operation} reason=${it.reason}")
    }
) {
    enum class Operation { MARK_PLAY, DURATION, BACKFILL, SNAPSHOT }
    enum class Failure { UNCONFIRMED, EXCEPTION, REJECTED, STALE_OWNER, READ_FAILED, TIMEOUT }
    data class Diagnostic(val sequence: Long, val operation: Operation, val reason: Failure)

    private val admission = Any()
    private var sequence = 0L
    private var pending = 0
    // A later successful delta cannot prove that an earlier unconfirmed delta was preserved.
    private val unconfirmed = mutableMapOf<K, Diagnostic>()

    private fun report(event: Diagnostic) {
        try { diagnostic(event) } catch (_: Exception) { /* Diagnostics must never stop the writer. */ }
    }

    private fun fail(key: K, event: Diagnostic) {
        synchronized(admission) { unconfirmed.putIfAbsent(key, event) }
        report(event)
    }

    fun submit(key: K, operation: Operation, isCurrent: () -> Boolean = { true }, write: () -> Boolean): Boolean =
        synchronized(admission) {
            val id = ++sequence
            pending++
            try {
                executor.execute {
                    try {
                        val reason = try {
                            when {
                                !isCurrent() -> Failure.STALE_OWNER
                                write() -> null
                                else -> Failure.UNCONFIRMED
                            }
                        } catch (_: Exception) { Failure.EXCEPTION }
                        if (reason != null) fail(key, Diagnostic(id, operation, reason))
                    } finally { synchronized(admission) { pending-- } }
                }
                true
            } catch (_: RejectedExecutionException) {
                pending--
                fail(key, Diagnostic(id, operation, Failure.REJECTED))
                false
            }
        }

    suspend fun <T : Any> snapshot(key: K, read: () -> T): T = suspendCancellableCoroutine { continuation ->
        synchronized(admission) {
            val id = ++sequence
            fun capture() {
                if (!continuation.isActive) return
                val previous = synchronized(admission) { unconfirmed[key] }
                if (previous != null) {
                    continuation.resumeWithException(ListenStatsSnapshotException(previous.reason))
                    return
                }
                try {
                    continuation.resume(read())
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    continuation.resumeWithException(cancelled)
                } catch (failure: ListenStatsSnapshotException) {
                    report(Diagnostic(id, Operation.SNAPSHOT, failure.reason))
                    continuation.resumeWithException(failure)
                } catch (_: Exception) {
                    report(Diagnostic(id, Operation.SNAPSHOT, Failure.READ_FAILED))
                    continuation.resumeWithException(ListenStatsSnapshotException(Failure.READ_FAILED))
                }
            }
            // An idle queue can capture under the admission lock without launching a worker or
            // blocking on it. New submissions cannot overtake this read. Busy queues use FIFO.
            if (pending == 0) {
                capture()
            } else {
                pending++
                try {
                    executor.execute {
                        try { capture() } finally { synchronized(admission) { pending-- } }
                    }
                } catch (_: RejectedExecutionException) {
                    pending--
                    fail(key, Diagnostic(id, Operation.SNAPSHOT, Failure.REJECTED))
                    continuation.resumeWithException(ListenStatsSnapshotException(Failure.REJECTED))
                }
            }
        }
    }

    fun reportTimeout() = report(Diagnostic(0L, Operation.SNAPSHOT, Failure.TIMEOUT))
}

internal class ListenStatsSnapshotException(val reason: ListenStatsWriteQueue.Failure) :
    IllegalStateException("Listening statistics snapshot unconfirmed (${reason.name})")
