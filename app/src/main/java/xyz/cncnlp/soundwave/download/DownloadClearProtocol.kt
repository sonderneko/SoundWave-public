package xyz.cncnlp.soundwave.download

/**
 * Serializes download-result commits with history clearing. The generation itself is supplied by
 * the caller so Android storage and the synchronization protocol remain independently testable.
 */
internal class DownloadGenerationFence(private val lock: Any = Any()) {
    // Production injects the persistent-work authority; standalone protocol tests own a local lock.

    fun <T> atCurrentGeneration(readGeneration: () -> Long, action: (Long) -> T): T =
        synchronized(lock) { action(readGeneration()) }

    fun <T> commitIfCurrent(readGeneration: () -> Long, expectedGeneration: Long, action: () -> T): T? =
        synchronized(lock) {
            if (readGeneration() != expectedGeneration) null else action()
        }

    fun <T> advanceAndRun(
        readGeneration: () -> Long,
        persistGeneration: (Long) -> Boolean,
        action: (Long) -> T
    ): T? = synchronized(lock) {
        val nextGeneration = Math.addExact(readGeneration(), 1L)
        if (!persistGeneration(nextGeneration)) return@synchronized null
        action(nextGeneration)
    }
}

/** Testable ordering boundary used by the Android download service. */
internal object DownloadClearProtocol {
    fun <T> clear(
        fence: DownloadGenerationFence,
        readGeneration: () -> Long,
        persistGeneration: (Long) -> Boolean,
        cancelAll: () -> Unit,
        loadItems: () -> List<T>,
        deleteArtifact: (T) -> Unit,
        clearRecords: () -> Unit,
        removeFiles: Boolean
    ): List<T>? = fence.advanceAndRun(readGeneration, persistGeneration) {
        // The durable generation changes before cancellation is requested. Cancellation is
        // asynchronous; stale workers are therefore also prevented from committing below.
        cancelAll()
        val items = loadItems()
        if (removeFiles) items.forEach(deleteArtifact)
        clearRecords()
        items
    }
}
