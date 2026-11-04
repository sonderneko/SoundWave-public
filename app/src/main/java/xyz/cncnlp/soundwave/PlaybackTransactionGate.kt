package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * Pure identity gate for one foreground playback intent.
 *
 * AppRoot owns UI state, while this gate owns only transaction validity.  A late
 * resolver, prefetch or Service callback can ask whether its captured ticket is
 * still allowed to commit without reading or mutating Compose state.
 */
class PlaybackTransactionGate {
    data class Transaction internal constructor(
        val generation: Long,
        val songKey: String,
        val queueRevision: Long,
        val ownerContext: ProfileContext
    )

    private var generation = 0L
    private var active: Transaction? = null

    @Synchronized
    fun begin(songKey: String, queueRevision: Long, ownerContext: ProfileContext = ProfileWorkIdentity.runtime()): Transaction {
        generation += 1L
        return Transaction(generation, songKey, queueRevision, ownerContext).also { active = it }
    }

    @Synchronized
    fun cancel() {
        generation += 1L
        active = null
    }

    @Synchronized
    fun hasCurrent(songKey: String?, queueRevision: Long): Boolean =
        active?.let { isCurrent(it, songKey, queueRevision) } == true

    /** A queue-tail edit may advance the revision without changing the selected in-flight song. */
    @Synchronized
    fun rebaseCurrentQueueRevision(songKey: String, expectedRevision: Long, newRevision: Long): Boolean {
        val current = active ?: return false
        if (!ProfileWorkIdentity.isRuntime(current.ownerContext) ||
            current.generation != generation ||
            current.songKey != songKey ||
            current.queueRevision != expectedRevision) return false
        active = current.copy(queueRevision = newRevision)
        return true
    }

    /** Bind an async-discovered target only while the search's entry intent still owns the gate. */
    @Synchronized
    fun rebindIfCurrent(
        transaction: Transaction,
        currentSongKey: String?,
        queueRevision: Long,
        targetSongKey: String
    ): Transaction? {
        if (!isCurrent(transaction, currentSongKey, queueRevision)) return null
        generation += 1L
        return Transaction(generation, targetSongKey, queueRevision, transaction.ownerContext)
            .also { active = it }
    }

    @Synchronized
    fun isCurrent(transaction: Transaction, songKey: String?, queueRevision: Long): Boolean {
        val current = active ?: return false
        return ProfileWorkIdentity.isRuntime(transaction.ownerContext) &&
            transaction.generation == generation &&
            current.generation == transaction.generation &&
            transaction.songKey == songKey &&
            current.songKey == transaction.songKey &&
            current.ownerContext.matches(transaction.ownerContext) &&
            current.queueRevision == queueRevision
    }
}
