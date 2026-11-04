package xyz.cncnlp.soundwave.diagnostics

import android.util.Log
import xyz.cncnlp.soundwave.BuildConfig
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Bounded, process-local diagnostics for concurrency and cache lifecycles.
 * Raw task identifiers and identities are reduced to SHA-256 fingerprints before storage or logging.
 */
internal object ConcurrentWorkDiagnostics {
    private const val TAG = "SoundWaveConcurrency"
    internal const val MAX_EVENTS = 256

    internal enum class WorkKind { DOWNLOAD, PREFETCH, CACHE, WEBDAV }
    internal enum class Phase { QUEUE, EXECUTION, CACHE, DISK, RECOVERY, CLEANUP }
    internal enum class Outcome {
        ACCEPTED, REJECTED, STARTED, SUCCEEDED, FAILED, CANCELLED,
        RETRY_SCHEDULED, RECOVERED, HIT, MISS, SKIPPED, UNCERTAIN
    }
    internal enum class CancelReason {
        USER, PAUSED, CLEAR_ALL, PAGE_LEFT, REPLACED, PROFILE_CHANGED,
        WORKER_STOPPED, OWNER_CANCELLED, TIMEOUT, UNKNOWN
    }
    internal enum class FailureKind { NETWORK, HTTP, IO, STORAGE, PERSISTENCE, TIMEOUT, OTHER }

    internal data class Event(
        val sequence: Long,
        val timestampMs: Long,
        val kind: WorkKind,
        val phase: Phase,
        val outcome: Outcome,
        val taskFingerprint: String?,
        val identityFingerprint: String?,
        val queueVersion: Long?,
        val cancelReason: CancelReason?,
        val failureKind: FailureKind?,
        val cacheHit: Boolean?,
        val queuedCount: Int?,
        val activeCount: Int?,
        val diskUsedBytes: Long?,
        val diskAvailableBytes: Long?
    ) {
        fun safeLogLine(): String = buildString {
            append("seq=").append(sequence)
            append(" ts=").append(timestampMs)
            append(" kind=").append(kind)
            append(" phase=").append(phase)
            append(" result=").append(outcome)
            append(" task=").append(taskFingerprint ?: "-")
            append(" identity=").append(identityFingerprint ?: "-")
            queueVersion?.let { append(" queueVersion=").append(it) }
            cancelReason?.let { append(" cancelReason=").append(it) }
            failureKind?.let { append(" failureKind=").append(it) }
            cacheHit?.let { append(" cacheHit=").append(it) }
            queuedCount?.let { append(" queued=").append(it) }
            activeCount?.let { append(" active=").append(it) }
            diskUsedBytes?.let { append(" diskUsedBytes=").append(it) }
            diskAvailableBytes?.let { append(" diskAvailableBytes=").append(it) }
        }
    }

    private val lock = Any()
    private val events = ArrayDeque<Event>()
    private var nextSequence = 0L

    fun record(
        kind: WorkKind,
        phase: Phase,
        outcome: Outcome,
        taskId: String? = null,
        identity: String? = null,
        queueVersion: Long? = null,
        cancelReason: CancelReason? = null,
        failureKind: FailureKind? = null,
        cacheHit: Boolean? = null,
        queuedCount: Int? = null,
        activeCount: Int? = null,
        diskUsedBytes: Long? = null,
        diskAvailableBytes: Long? = null
    ) {
        val event = synchronized(lock) {
            Event(
                sequence = ++nextSequence,
                timestampMs = System.currentTimeMillis(),
                kind = kind,
                phase = phase,
                outcome = outcome,
                taskFingerprint = fingerprint(taskId),
                identityFingerprint = fingerprint(identity),
                queueVersion = queueVersion,
                cancelReason = cancelReason,
                failureKind = failureKind,
                cacheHit = cacheHit,
                queuedCount = queuedCount?.coerceAtLeast(0),
                activeCount = activeCount?.coerceAtLeast(0),
                diskUsedBytes = diskUsedBytes?.coerceAtLeast(0L),
                diskAvailableBytes = diskAvailableBytes?.coerceAtLeast(0L)
            ).also {
                events.addLast(it)
                while (events.size > MAX_EVENTS) events.removeFirst()
            }
        }
        if (BuildConfig.DEBUG) {
            try { Log.d(TAG, event.safeLogLine()) } catch (_: Exception) { }
        }
    }

    internal fun recent(limit: Int = MAX_EVENTS): List<Event> = synchronized(lock) {
        events.toList().takeLast(limit.coerceIn(0, MAX_EVENTS))
    }

    internal fun clearForTest() = synchronized(lock) {
        events.clear()
        nextSequence = 0L
    }

    private fun fingerprint(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
