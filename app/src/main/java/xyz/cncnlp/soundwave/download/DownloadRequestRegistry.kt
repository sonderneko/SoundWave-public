package xyz.cncnlp.soundwave.download

/** Serializes session/call ownership; external gates and cancel callbacks never run under this lock. */
internal class DownloadRequestRegistry<T : Any>(private val cancelCall: (T) -> Unit) {
    class Session internal constructor(internal val taskId: String) {
        @Volatile var isCancelled: Boolean = false
            internal set
    }

    private val lock = Any()
    private val callsBySession = mutableMapOf<Session, MutableSet<T>>()

    fun begin(taskId: String): Session = synchronized(lock) {
        Session(taskId).also { callsBySession[it] = mutableSetOf() }
    }

    fun finish(session: Session) {
        val outstanding = synchronized(lock) {
            val calls = callsBySession.remove(session)?.toList().orEmpty()
            if (calls.isNotEmpty()) session.isCancelled = true
            calls
        }
        outstanding.forEach(::cancelSafely)
    }

    fun track(session: Session, call: T, shouldCancel: () -> Boolean): Boolean {
        val registered = synchronized(lock) {
            val calls = callsBySession[session]
            if (calls == null || session.isCancelled) false else calls.add(call)
        }
        if (!registered) {
            cancelSafely(call)
            return false
        }

        // Register first, then recheck: cancel either sees this session-owned call or invalidates its session.
        val cancelled = try {
            session.isCancelled || shouldCancel()
        } catch (failure: Throwable) {
            cancelSafely(call)
            untrack(session, call)
            throw failure
        }
        if (cancelled) {
            cancelSafely(call)
            untrack(session, call)
            return false
        }
        return true
    }

    fun untrack(session: Session, call: T) {
        synchronized(lock) { callsBySession[session]?.remove(call) }
    }

    /** Explicit task-level cancellation intentionally invalidates every active attempt for this task. */
    fun cancel(taskId: String) {
        val snapshot = synchronized(lock) {
            val matching = callsBySession.keys.filter { it.taskId == taskId }
            matching.forEach { it.isCancelled = true }
            matching.flatMap { callsBySession[it].orEmpty().toList() }
        }
        snapshot.forEach(::cancelSafely)
    }

    fun cancelSession(session: Session) {
        val snapshot = synchronized(lock) {
            session.isCancelled = true
            callsBySession[session]?.toList().orEmpty()
        }
        snapshot.forEach(::cancelSafely)
    }

    fun cancelAll() {
        val snapshot = synchronized(lock) {
            callsBySession.keys.forEach { it.isCancelled = true }
            callsBySession.values.flatMap { it.toList() }
        }
        snapshot.forEach(::cancelSafely)
    }

    private fun cancelSafely(call: T) {
        try { cancelCall(call) } catch (_: Exception) {}
    }
}
