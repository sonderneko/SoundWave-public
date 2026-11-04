package xyz.cncnlp.soundwave.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One process-wide entry point for manual and any future automatic WebDAV work. No scheduler. */
internal val WebDavOperations = WebDavOperationGate()

/**
 * The slot covers the whole read/merge/conditional-write and credential persistence transaction.
 * Cancellation releases a caller promptly, but NOT the slot until even an uncooperative transport
 * has returned. Reentrant blocking calls share the outer lease. Only transaction() may acquire
 * on the UI thread (suspending); blocking()/run() are IO compatibility boundaries.
 */
internal class WebDavOperationGate(
    private val dispatch: (Runnable) -> Unit = { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }
) {
    private val monitor = Any()
    private var generation = 0L
    private val slot = Mutex()
    private var transitionsPending = 0
    private val running = mutableSetOf<Lease>()
    private val current = ThreadLocal<Lease>()

    inner class Lease internal constructor(private val ownerGeneration: Long) {
        private var cancelled = false
        private val cancellationHandlers = mutableSetOf<() -> Unit>()

        fun ensureActive() = synchronized(monitor) { checkActive() }
        private fun checkActive() {
            if (cancelled || generation != ownerGeneration || transitionsPending != 0) throw CancellationException("WebDAV operation expired")
        }

        /** Atomic with account invalidation; only short local mutations, never network/waits. */
        fun <T> commit(action: () -> T): T = synchronized(monitor) {
            checkActive()
            action()
        }

        fun cancel() = synchronized(monitor) {
            if (!cancelled) {
                cancelled = true
                cancellationHandlers.toList().forEach { runCatching { it() } }
                cancellationHandlers.clear()
            }
        }

        /** Registration and cancellation are atomic, including cancellation before registration. */
        fun onCancel(handler: () -> Unit): () -> Unit = synchronized(monitor) {
            if (cancelled || generation != ownerGeneration) handler() else cancellationHandlers.add(handler)
            return@synchronized { synchronized(monitor) { cancellationHandlers.remove(handler); Unit } }
        }
    }

    fun capture(): Lease = synchronized(monitor) {
        if (transitionsPending != 0) throw CancellationException("WebDAV identity transition pending")
        Lease(generation)
    }

    /** Admission remains closed through suspension/network/waiting on the account mutex. */
    fun beginIdentityTransition(): () -> Unit = synchronized(monitor) {
        transitionsPending += 1
        invalidate()
        var closed = false
        return@synchronized {
            synchronized(monitor) {
                if (!closed) { transitionsPending -= 1; closed = true }
            }
        }
    }

    /** Does not wait for HTTP or the transaction slot. Already-sent PUTs cannot be rolled back. */
    fun invalidate() = synchronized(monitor) {
        generation += 1L
        running.toList().forEach { it.cancel() }
    }

    fun <T> identityChange(action: () -> T): T = synchronized(monitor) {
        invalidate()
        action()
    }

    fun currentLease(): Lease = checkNotNull(current.get()) { "WebDAV operation gate required" }

    fun <T> blocking(lease: Lease = current.get() ?: capture(), action: () -> T): T {
        if (current.get() != null) {
            check(current.get() === lease) { "Nested WebDAV lease mismatch" }
            lease.ensureActive()
            return action().also { lease.ensureActive() }
        }
        synchronized(monitor) { lease.ensureActive(); running.add(lease) }
        var acquired = false
        try {
            // Compatibility boundary for synchronous clients; production calls this on IO only.
            // Mutex is shared with transaction(), so UI lifecycle acquisition itself can suspend.
            runBlocking {
                val waiter = coroutineContext.job
                val unregister = lease.onCancel { waiter.cancel() }
                try { slot.lock(); acquired = true } finally { unregister() }
            }
            lease.ensureActive()
            current.set(lease)
            return action().also { lease.ensureActive() }
        } finally {
            current.remove()
            if (acquired) slot.unlock()
            synchronized(monitor) { running.remove(lease) }
        }
    }

    /**
     * Full UI transaction: acquire before reading the latest snapshot; release after local apply.
     * Keeps the caller dispatcher for UI work; callers use withContext(IO) for blocking clients.
     * The lease context makes those synchronous calls reentrant across dispatcher handoffs.
     */
    suspend fun <T> transaction(lease: Lease = capture(), action: suspend () -> T): T = coroutineScope {
        synchronized(monitor) { lease.ensureActive(); running.add(lease) }
        val transactionJob = coroutineContext.job
        val unregister = lease.onCancel { transactionJob.cancel() }
        // A child cancellation handler fires at cancellation onset, not after a blocking IO child
        // finally completes. This is what actively cancels its OkHttp Call while withContext(IO) waits.
        val cancellationObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<Nothing> { observer ->
                observer.invokeOnCancellation { lease.cancel() }
            }
        }
        var acquired = false
        try {
            slot.lock()
            acquired = true
            lease.ensureActive()
            withContext(current.asContextElement(lease)) { action().also { lease.ensureActive() } }
        } finally {
            unregister()
            cancellationObserver.cancel()
            if (acquired) slot.unlock()
            synchronized(monitor) { running.remove(lease) }
        }
    }

    /** The worker retains the slot after cancellation until all blocking work actually exits. */
    suspend fun <T> run(lease: Lease = capture(), action: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { lease.cancel() }
        try {
            dispatch(Runnable {
                try {
                    val result = blocking(lease, action)
                    continuation.resume(result)
                } catch (failure: Throwable) {
                    continuation.resumeWithException(failure)
                }
            })
        } catch (failure: Throwable) {
            lease.cancel()
            continuation.resumeWithException(failure)
        }
    }
}
