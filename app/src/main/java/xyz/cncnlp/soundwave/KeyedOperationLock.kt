package xyz.cncnlp.soundwave

import kotlinx.coroutines.sync.Mutex

/** 同键操作互斥、异键并行；仅在有等待者或活动操作时保留对应锁。 */
internal class KeyedOperationLock<K : Any> {
    private class Entry {
        val mutex = Mutex()
        var references = 0
    }

    private val entries = HashMap<K, Entry>()

    suspend fun <T> withKey(key: K, operation: suspend () -> T): T {
        val entry = synchronized(entries) {
            (entries[key] ?: Entry().also { entries[key] = it }).also { it.references++ }
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return operation()
        } finally {
            try {
                if (acquired) entry.mutex.unlock()
            } finally {
                synchronized(entries) {
                    entry.references--
                    if (entry.references == 0 && entries[key] === entry) entries.remove(key)
                }
            }
        }
    }

    internal fun referencesForTests(key: K): Int = synchronized(entries) { entries[key]?.references ?: 0 }

    internal fun trackedKeysForTests(): Int = synchronized(entries) { entries.size }
}
