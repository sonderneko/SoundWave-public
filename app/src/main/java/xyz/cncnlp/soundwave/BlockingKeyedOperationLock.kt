package xyz.cncnlp.soundwave

/** Blocking counterpart of KeyedOperationLock for synchronous disk/cache operations.
 * References include callers waiting on the monitor. Never remove an entry while a
 * waiter can still use it: doing so would create two locks for the same file.
 * Registry bookkeeping is short and never held during an operation or monitor wait.
 * Like synchronized, thread interruption alone does not cancel a monitor wait.
 */
internal class BlockingKeyedOperationLock<K : Any> {
    private class Entry { val monitor = Any(); var references = 0 }
    private val entries = HashMap<K, Entry>()

    fun <T> withKey(key: K, operation: () -> T): T {
        val entry = synchronized(entries) {
            (entries[key] ?: Entry().also { entries[key] = it }).also { it.references++ }
        }
        try {
            return synchronized(entry.monitor) { operation() }
        } finally {
            synchronized(entries) {
                entry.references--
                if (entry.references == 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    internal fun trackedKeysForTests(): Int = synchronized(entries) { entries.size }
    internal fun referencesForTests(key: K): Int = synchronized(entries) { entries[key]?.references ?: 0 }
}
