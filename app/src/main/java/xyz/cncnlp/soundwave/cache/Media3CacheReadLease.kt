package xyz.cncnlp.soundwave.cache

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** A reference-counted claim on one Media3 cache key while a CacheDataSource is open. */
internal class Media3CacheReadLease internal constructor(
    val cacheKey: String,
    private val registry: Media3CacheReadLeaseRegistry
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) registry.release(cacheKey)
    }
}

/** Serializes cache-key acquisition against resource deletion and full-cache clear. */
internal class Media3CacheReadLeaseRegistry(
    private val onLastLeaseReleased: ((String) -> Unit)? = null
) {
    private val monitor = java.lang.Object()
    private val leaseCounts = mutableMapOf<String, Int>()
    private val removingKeys = mutableSetOf<String>()
    private var clearInProgress = false

    @Throws(InterruptedIOException::class)
    fun acquire(key: String): Media3CacheReadLease {
        synchronized(monitor) {
            while (clearInProgress || key in removingKeys) awaitChangeLocked()
            leaseCounts[key] = (leaseCounts[key] ?: 0) + 1
        }
        return Media3CacheReadLease(key, this)
    }

    fun isActive(key: String): Boolean = synchronized(monitor) { (leaseCounts[key] ?: 0) > 0 }

    fun activeKeyCount(): Int = synchronized(monitor) { leaseCounts.size }

    /** Removes only if no reader owns [key]; acquisition waits until [remove] completes. */
    fun removeIfUnleased(key: String, remove: () -> Unit): Boolean {
        if (!beginRemoval(key, duringClear = false)) return false
        return performRemoval(key, remove)
    }

    /** Blocks new reads for the clear duration and gives clear code a privileged removal scope. */
    @Throws(InterruptedIOException::class)
    fun <T> withClearFence(action: (ClearFence) -> T): T {
        val fence = synchronized(monitor) {
            while (clearInProgress) awaitChangeLocked()
            clearInProgress = true
            try {
                while (removingKeys.isNotEmpty()) awaitChangeLocked()
            } catch (failure: Throwable) {
                clearInProgress = false
                monitor.notifyAll()
                throw failure
            }
            ClearFence(this)
        }
        try {
            return action(fence)
        } finally {
            synchronized(monitor) {
                clearInProgress = false
                monitor.notifyAll()
            }
        }
    }

    internal fun release(key: String) {
        val becameInactive = synchronized(monitor) {
            val count = leaseCounts[key] ?: return@synchronized false
            if (count <= 1) {
                leaseCounts.remove(key)
                true
            } else {
                leaseCounts[key] = count - 1
                false
            }.also { monitor.notifyAll() }
        }
        if (becameInactive) runCatching { onLastLeaseReleased?.invoke(key) }
    }

    private fun beginRemoval(key: String, duringClear: Boolean): Boolean = synchronized(monitor) {
        if (duringClear != clearInProgress) return@synchronized false
        if (!duringClear && clearInProgress) return@synchronized false
        if (key in removingKeys || (leaseCounts[key] ?: 0) > 0) return@synchronized false
        removingKeys += key
        true
    }

    private fun performRemoval(key: String, remove: () -> Unit): Boolean {
        try {
            remove()
            return true
        } finally {
            synchronized(monitor) {
                removingKeys.remove(key)
                monitor.notifyAll()
            }
        }
    }

    private fun removeDuringClear(key: String, remove: () -> Unit): Boolean {
        if (!beginRemoval(key, duringClear = true)) return false
        return performRemoval(key, remove)
    }

    private fun awaitChangeLocked() {
        try {
            monitor.wait()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while waiting for a cache operation").also {
                it.initCause(interrupted)
            }
        }
    }

    internal class ClearFence internal constructor(private val registry: Media3CacheReadLeaseRegistry) {
        fun removeIfUnleased(key: String, remove: () -> Unit): Boolean = registry.removeDuringClear(key, remove)
    }
}
