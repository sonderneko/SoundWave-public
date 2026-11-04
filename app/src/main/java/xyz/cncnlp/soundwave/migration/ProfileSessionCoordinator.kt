package xyz.cncnlp.soundwave.migration

/** Identity reads never acquire the transaction barrier. Rotation may wait for IO on a
 * background thread; commits fail fast while rotation is pending (null means not applied).
 * This is not a guarantee that actions themselves or their storage backends are nonblocking.
 * No production activation API is exposed.
 */
class ProfileSessionCoordinator(seed: Long, private val requireRotationIo: () -> Unit = {
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
        "Session rotation must run off the main thread"
    }
}) {
    private val transactions = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val pendingRotations = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var current = ProfileContext.legacy(seed)
    @Volatile private var valid = true
    fun capture(): ProfileContext = current
    fun isCurrent(token: ProfileContext): Boolean = valid && current.matches(token)
    internal fun hasPendingRotation(): Boolean = pendingRotations.get() != 0
    fun rotateLegacy(): ProfileContext {
        // Reject unsupported read -> write upgrades, even on an IO thread.
        check(transactions.readHoldCount == 0 && !transactions.isWriteLockedByCurrentThread) {
            "Recursive session rotation is forbidden"
        }
        requireRotationIo()
        pendingRotations.incrementAndGet()
        val barrier = transactions.writeLock()
        try {
            barrier.lock()
            try {
                if (!valid || current.generation == Long.MAX_VALUE) {
                    valid = false
                    error("Session generation exhausted")
                }
                current = ProfileContext.legacy(current.generation + 1L)
                return current
            } finally { barrier.unlock() }
        } finally { pendingRotations.decrementAndGet() }
    }
    fun <T> commit(token: ProfileContext, action: () -> T): T? {
        // A writer queued after this thread entered an outer transaction cannot revoke
        // that transaction's authority to perform its own nested preference commit.
        val nested = transactions.readHoldCount > 0
        if (!nested && hasPendingRotation()) return null
        val permit = transactions.readLock()
        // Never queue UI mutation behind an active/queued writer. Recheck pending state
        // after acquisition because tryLock may barge ahead of a waiting rotation. An
        // already-admitted nested commit is the exception: rotation waits for its outer hold.
        if (!permit.tryLock()) return null
        return try {
            if ((!nested && hasPendingRotation()) || !isCurrent(token)) null else action()
        } finally { permit.unlock() }
    }
    /** Remote profiles stay closed until an independently verified runtime integration gate exists.
     * Namespace/catalog validity and opt-in alone are insufficient. No credential reads allowed.
     */
    fun remoteReady(token: ProfileContext): Boolean = false
}
