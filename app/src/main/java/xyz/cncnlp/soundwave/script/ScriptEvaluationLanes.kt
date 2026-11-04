package xyz.cncnlp.soundwave.script

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WebView script calls share mutable per-script LX request bindings. Keep each
 * script slot single-flight, but never hold the global submission gate while a
 * JS Promise/native HTTP request is pending. A fixed stripe table bounds memory.
 */
internal class ScriptEvaluationLanes(private val stripeCount: Int = 32) {
    init { require(stripeCount > 0) }

    private val submissionMutex = Mutex()
    private val slotMutexes = Array(stripeCount) { Mutex() }

    private fun slotMutex(slotId: String): Mutex =
        slotMutexes[slotId.hashCode().ushr(1) % slotMutexes.size]

    suspend fun <T> withSlot(slotId: String, block: suspend () -> T): T {
        val mutex = slotMutex(slotId)
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /** Serializes only a synchronous WebView enqueue or cleanup-post operation. */
    suspend fun <T> submit(block: () -> T): T = submissionMutex.withLock { block() }
}
