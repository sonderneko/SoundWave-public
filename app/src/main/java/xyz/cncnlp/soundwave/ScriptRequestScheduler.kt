package xyz.cncnlp.soundwave

import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-wide admission control for native script requests. */
internal class ScriptRequestScheduler(
    private val maxRunning: Int,
    private val maxQueued: Int,
    private val executor: Executor,
    private val reservePlaybackSlots: Boolean = false
) {
    companion object {
        const val MAX_RUNNING = 8
        const val MAX_QUEUED = 16

        /** Probes cannot occupy playback/download request slots; scripts get one low-priority request. */
        val probes = ScriptRequestScheduler(1, 4,
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "soundwave-script-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            })

        val processWide = ScriptRequestScheduler(
            maxRunning = MAX_RUNNING,
            maxQueued = MAX_QUEUED,
            executor = ThreadPoolExecutor(
                MAX_RUNNING,
                MAX_RUNNING,
                0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                ThreadFactory { task ->
                    Thread(task, "soundwave-script-request").apply { isDaemon = true }
                }
            ),
            reservePlaybackSlots = true
        )
    }

    internal enum class Priority { PLAY, DOWNLOAD, PREFETCH }

    internal data class Snapshot(val running: Int, val queued: Int, val tracked: Int)

    private enum class State { QUEUED, RUNNING, CANCELLED, FINISHED }

    private class Key(val owner: Any, val cid: String) {
        override fun equals(other: Any?): Boolean = other is Key && owner === other.owner && cid == other.cid
        override fun hashCode(): Int = 31 * System.identityHashCode(owner) + cid.hashCode()
    }

    private class Entry(
        val key: Key,
        val priority: Priority,
        val onRejected: () -> Unit,
        val onCancelled: () -> Unit,
        val onFailure: (Throwable) -> (() -> Unit),
        val work: (RequestControl) -> (() -> Unit)
    ) {
        var state = State.QUEUED
        var cancelToken: Any? = null
        var cancelAction: (() -> Unit)? = null
    }

    /** A task may attach its current transport cancellation action while it runs. */
    internal class RequestControl internal constructor(
        private val cancelled: () -> Boolean,
        private val install: (Any, () -> Unit) -> Boolean,
        private val clear: (Any) -> Unit
    ) {
        fun isCancelled(): Boolean = cancelled()
        fun installCancellation(token: Any, action: () -> Unit): Boolean = install(token, action)
        fun clearCancellation(token: Any) = clear(token)
    }

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>()
    private val queues = linkedMapOf(
        Priority.PLAY to ArrayDeque<Entry>(),
        Priority.DOWNLOAD to ArrayDeque<Entry>(),
        Priority.PREFETCH to ArrayDeque<Entry>()
    )
    private val runningByPriority = mutableMapOf<Priority, Int>()
    /** Four PLAY, two DOWNLOAD, one PREFETCH admission opportunities prevent lower-lane starvation. */
    private val serviceCycle = listOf(
        Priority.PLAY, Priority.PLAY, Priority.PLAY, Priority.PLAY,
        Priority.DOWNLOAD, Priority.DOWNLOAD,
        Priority.PREFETCH
    )
    private var serviceCursor = 0
    private var running = 0

    /**
     * Returns false for a duplicate cid or an unserviceable full queue. A higher-priority request
     * may displace the newest queued request from a lower lane; its normal rejection callback runs.
     */
    fun submit(
        owner: Any,
        cid: String,
        onRejected: () -> Unit,
        onCancelled: () -> Unit = {},
        onFailure: (Throwable) -> (() -> Unit),
        priority: Priority = Priority.PLAY,
        work: (RequestControl) -> (() -> Unit)
    ): Boolean {
        val key = Key(owner, cid)
        var launch: Entry? = null
        var evicted: Entry? = null
        var full = false
        synchronized(lock) {
            if (entries.containsKey(key)) return false
            val entry = Entry(key, priority, onRejected, onCancelled, onFailure, work)
            when {
                canStartLocked(priority) -> {
                    startLocked(entry)
                    entries[key] = entry
                    launch = entry
                }
                queuedCountLocked() < maxQueued -> queueLocked(entry)
                else -> {
                    evicted = evictionCandidateLocked(priority)
                    val victim = evicted
                    if (victim == null) {
                        full = true
                    } else {
                        removeQueuedLocked(victim)
                        entries.remove(victim.key)
                        victim.state = State.FINISHED
                        queueLocked(entry)
                    }
                }
            }
        }
        evicted?.let { safe(it.onRejected) }
        if (full) {
            safe(onRejected)
            return false
        }
        launch?.let(::dispatch)
        return true
    }

    fun cancel(owner: Any, cid: String): Boolean {
        val entry = synchronized(lock) { entries[Key(owner, cid)] } ?: return false
        return cancel(entry)
    }

    /** Cancels only requests owned by this runtime; other runtimes share but retain their work. */
    fun cancelOwner(owner: Any) {
        val owned = synchronized(lock) { entries.values.filter { it.key.owner === owner } }
        owned.forEach(::cancel)
    }

    internal fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(running, queuedCountLocked(), entries.size)
    }

    private fun cancel(entry: Entry): Boolean {
        var cancelAction: (() -> Unit)? = null
        var onCancelled: (() -> Unit)? = null
        synchronized(lock) {
            when (entry.state) {
                State.QUEUED -> {
                    removeQueuedLocked(entry)
                    entries.remove(entry.key)
                    entry.state = State.FINISHED
                    onCancelled = entry.onCancelled
                }
                State.RUNNING -> {
                    entry.state = State.CANCELLED
                    cancelAction = entry.cancelAction
                    entry.cancelAction = null
                    entry.cancelToken = null
                    onCancelled = entry.onCancelled
                }
                State.CANCELLED, State.FINISHED -> return false
            }
        }
        cancelAction?.let(::safe)
        onCancelled?.let(::safe)
        return true
    }

    private fun dispatch(entry: Entry) {
        try {
            executor.execute { runEntry(entry) }
        } catch (error: Throwable) {
            val delivery = try { entry.onFailure(error) } catch (_: Throwable) { null }
            finish(entry, delivery)
        }
    }

    private fun runEntry(entry: Entry) {
        val control = RequestControl(
            cancelled = { synchronized(lock) { entry.state == State.CANCELLED || entry.state == State.FINISHED } },
            install = { token, action -> installCancellation(entry, token, action) },
            clear = { token -> clearCancellation(entry, token) }
        )
        val canRun = synchronized(lock) { entry.state == State.RUNNING }
        if (!canRun) {
            finish(entry, null)
            return
        }
        val delivery = try {
            entry.work(control)
        } catch (error: Throwable) {
            try { entry.onFailure(error) } catch (_: Throwable) { null }
        }
        finish(entry, delivery)
    }

    private fun installCancellation(entry: Entry, token: Any, action: () -> Unit): Boolean {
        var cancelNow = false
        synchronized(lock) {
            when (entry.state) {
                State.RUNNING -> {
                    entry.cancelToken = token
                    entry.cancelAction = action
                    return true
                }
                State.CANCELLED -> cancelNow = true
                State.QUEUED, State.FINISHED -> Unit
            }
        }
        if (cancelNow) safe(action)
        return false
    }

    private fun clearCancellation(entry: Entry, token: Any) {
        synchronized(lock) {
            if (entry.cancelToken === token) {
                entry.cancelToken = null
                entry.cancelAction = null
            }
        }
    }

    private fun finish(entry: Entry, delivery: (() -> Unit)?) {
        var shouldDeliver = false
        val launch = mutableListOf<Entry>()
        synchronized(lock) {
            if (entry.state != State.RUNNING && entry.state != State.CANCELLED) return
            shouldDeliver = entry.state == State.RUNNING
            entry.state = State.FINISHED
            entry.cancelToken = null
            entry.cancelAction = null
            entries.remove(entry.key)
            running--
            decrementRunningLocked(entry.priority)
            while (running < maxRunning) {
                val next = nextRunnableLocked() ?: break
                startLocked(next)
                launch.add(next)
            }
        }
        if (shouldDeliver) delivery?.let(::safe)
        launch.forEach(::dispatch)
    }

    private fun canStartLocked(priority: Priority): Boolean {
        if (running >= maxRunning) return false
        if (!reservePlaybackSlots) return true
        val lowerRunning = runningCountLocked(Priority.DOWNLOAD) + runningCountLocked(Priority.PREFETCH)
        val lowerLimit = (maxRunning - 1).coerceAtLeast(0)
        return when (priority) {
            Priority.PLAY -> true
            Priority.DOWNLOAD -> lowerRunning < lowerLimit
            Priority.PREFETCH -> lowerRunning < lowerLimit &&
                runningCountLocked(Priority.PREFETCH) < (maxRunning - 2).coerceAtLeast(0)
        }
    }

    private fun startLocked(entry: Entry) {
        entry.state = State.RUNNING
        running++
        runningByPriority[entry.priority] = runningCountLocked(entry.priority) + 1
    }

    private fun decrementRunningLocked(priority: Priority) {
        val next = runningCountLocked(priority) - 1
        if (next <= 0) runningByPriority.remove(priority) else runningByPriority[priority] = next
    }

    private fun runningCountLocked(priority: Priority): Int = runningByPriority[priority] ?: 0

    private fun queuedCountLocked(): Int = queues.values.sumOf { it.size }

    private fun queueLocked(entry: Entry) {
        entry.state = State.QUEUED
        entries[entry.key] = entry
        queues.getValue(entry.priority).addLast(entry)
    }

    private fun removeQueuedLocked(entry: Entry) {
        queues.getValue(entry.priority).remove(entry)
    }

    private fun evictionCandidateLocked(priority: Priority): Entry? {
        val lowerPriorities = when (priority) {
            Priority.PLAY -> listOf(Priority.PREFETCH, Priority.DOWNLOAD)
            Priority.DOWNLOAD -> listOf(Priority.PREFETCH)
            Priority.PREFETCH -> emptyList()
        }
        for (lower in lowerPriorities) {
            queues.getValue(lower).peekLast()?.let { return it }
        }
        return null
    }

    private fun nextRunnableLocked(): Entry? {
        repeat(serviceCycle.size) {
            val priority = serviceCycle[serviceCursor]
            serviceCursor = (serviceCursor + 1) % serviceCycle.size
            val candidate = queues.getValue(priority).peekFirst() ?: return@repeat
            if (!canStartLocked(candidate.priority)) return@repeat
            return queues.getValue(priority).removeFirst()
        }
        return null
    }

    private fun safe(action: () -> Unit) {
        try { action() } catch (_: Throwable) { }
    }
}
