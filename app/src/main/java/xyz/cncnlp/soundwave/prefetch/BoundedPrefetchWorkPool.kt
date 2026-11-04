package xyz.cncnlp.soundwave.prefetch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicLong
import xyz.cncnlp.soundwave.diagnostics.ConcurrentWorkDiagnostics

/**
 * Shared admission for optional work only. Foreground playback and visible detail requests bypass
 * this pool and retain their existing priority lanes. Playback-oriented prefetch keeps reserved
 * active/admission capacity so UI cover warming cannot occupy every speculative slot.
 */
internal class BoundedPrefetchWorkPool(
    private val maxActive: Int,
    private val maxQueued: Int,
    private val reservedActiveForPlayback: Int,
    private val reservedAdmissionForPlayback: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal enum class Priority { PLAYBACK, UI }

    internal data class Snapshot(
        val running: Int,
        val queued: Int,
        val admitted: Int,
        val uiRunning: Int,
        val uiAdmitted: Int,
    )

    private val admissionCapacity = maxActive + maxQueued
    private val uiAdmissionCapacity = admissionCapacity - reservedAdmissionForPlayback
    private val uiActiveCapacity = maxActive - reservedActiveForPlayback
    private val admission = Semaphore(admissionCapacity)
    private val uiAdmission = Semaphore(uiAdmissionCapacity)
    private val active = Semaphore(maxActive)
    private val uiActive = Semaphore(uiActiveCapacity)
    private val diagnosticSequence = AtomicLong()

    init {
        require(maxActive > 0)
        require(maxQueued >= 0)
        require(reservedActiveForPlayback in 0 until maxActive)
        require(reservedAdmissionForPlayback in 0..maxQueued)
    }

    /** Returns null rather than growing the queue when optional work has exhausted its budget. */
    fun submit(
        scope: CoroutineScope,
        priority: Priority,
        diagnosticIdentity: String? = null,
        cancelReason: ConcurrentWorkDiagnostics.CancelReason = ConcurrentWorkDiagnostics.CancelReason.OWNER_CANCELLED,
        block: suspend () -> Unit,
    ): Job? {
        val diagnosticId = diagnosticSequence.incrementAndGet()
        val diagnosticTaskId = "prefetch-$diagnosticId"
        val holdsUiAdmission = priority == Priority.UI
        if (holdsUiAdmission && !uiAdmission.tryAcquire()) {
            val counts = snapshot()
            ConcurrentWorkDiagnostics.record(
                ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
                ConcurrentWorkDiagnostics.Phase.QUEUE,
                ConcurrentWorkDiagnostics.Outcome.REJECTED,
                taskId = diagnosticTaskId,
                identity = diagnosticIdentity,
                queueVersion = diagnosticId,
                queuedCount = counts.queued,
                activeCount = counts.running
            )
            return null
        }
        if (!admission.tryAcquire()) {
            if (holdsUiAdmission) uiAdmission.release()
            val counts = snapshot()
            ConcurrentWorkDiagnostics.record(
                ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
                ConcurrentWorkDiagnostics.Phase.QUEUE,
                ConcurrentWorkDiagnostics.Outcome.REJECTED,
                taskId = diagnosticTaskId,
                identity = diagnosticIdentity,
                queueVersion = diagnosticId,
                queuedCount = counts.queued,
                activeCount = counts.running
            )
            return null
        }

        val admitted = snapshot()
        ConcurrentWorkDiagnostics.record(
            ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
            ConcurrentWorkDiagnostics.Phase.QUEUE,
            ConcurrentWorkDiagnostics.Outcome.ACCEPTED,
            taskId = diagnosticTaskId,
            identity = diagnosticIdentity,
            queueVersion = diagnosticId,
            queuedCount = admitted.queued,
            activeCount = admitted.running
        )
        var terminalOutcome = ConcurrentWorkDiagnostics.Outcome.CANCELLED
        var terminalFailure: ConcurrentWorkDiagnostics.FailureKind? = null
        val job = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var holdsUiActive = false
            var holdsActive = false
            try {
                if (priority == Priority.UI) {
                    uiActive.acquire()
                    holdsUiActive = true
                }
                active.acquire()
                holdsActive = true
                val counts = snapshot()
                ConcurrentWorkDiagnostics.record(
                    ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
                    ConcurrentWorkDiagnostics.Phase.EXECUTION,
                    ConcurrentWorkDiagnostics.Outcome.STARTED,
                    taskId = diagnosticTaskId,
                    identity = diagnosticIdentity,
                    queueVersion = diagnosticId,
                    queuedCount = counts.queued,
                    activeCount = counts.running
                )
                block()
                terminalOutcome = ConcurrentWorkDiagnostics.Outcome.SUCCEEDED
            } catch (cancelled: CancellationException) {
                terminalOutcome = ConcurrentWorkDiagnostics.Outcome.CANCELLED
                throw cancelled
            } catch (_: Exception) {
                terminalOutcome = ConcurrentWorkDiagnostics.Outcome.FAILED
                terminalFailure = ConcurrentWorkDiagnostics.FailureKind.OTHER
                // Optional work must not fail its siblings or the page owner.
            } finally {
                if (holdsActive) active.release()
                if (holdsUiActive) uiActive.release()
            }
        }
        job.invokeOnCompletion {
            admission.release()
            if (holdsUiAdmission) uiAdmission.release()
            val counts = snapshot()
            ConcurrentWorkDiagnostics.record(
                ConcurrentWorkDiagnostics.WorkKind.PREFETCH,
                ConcurrentWorkDiagnostics.Phase.EXECUTION,
                terminalOutcome,
                taskId = diagnosticTaskId,
                identity = diagnosticIdentity,
                queueVersion = diagnosticId,
                cancelReason = if (terminalOutcome == ConcurrentWorkDiagnostics.Outcome.CANCELLED) cancelReason else null,
                failureKind = terminalFailure,
                queuedCount = counts.queued,
                activeCount = counts.running
            )
        }
        job.start()
        return job
    }

    internal fun snapshot(): Snapshot {
        val admitted = admissionCapacity - admission.availablePermits
        val running = maxActive - active.availablePermits
        return Snapshot(
            running = running,
            queued = (admitted - running).coerceAtLeast(0),
            admitted = admitted,
            uiRunning = uiActiveCapacity - uiActive.availablePermits,
            uiAdmitted = uiAdmissionCapacity - uiAdmission.availablePermits,
        )
    }
}

/** Process-wide pool shared by page-owned cover warming and playback-owned next-item prefetch. */
internal object SharedPrefetchWorkPool {
    private val pool = BoundedPrefetchWorkPool(
        maxActive = 3,
        maxQueued = 13,
        reservedActiveForPlayback = 1,
        reservedAdmissionForPlayback = 4,
    )

    fun submit(
        scope: CoroutineScope,
        priority: BoundedPrefetchWorkPool.Priority,
        diagnosticIdentity: String? = null,
        cancelReason: ConcurrentWorkDiagnostics.CancelReason = ConcurrentWorkDiagnostics.CancelReason.OWNER_CANCELLED,
        block: suspend () -> Unit,
    ): Job? = pool.submit(scope, priority, diagnosticIdentity, cancelReason, block)

    internal fun snapshot(): BoundedPrefetchWorkPool.Snapshot = pool.snapshot()
}
