package xyz.cncnlp.soundwave.migration

import android.content.Context

/** Durable identity for WorkManager jobs. Session generation remains an ephemeral UI lease. */
object ProfileWorkIdentity {
    const val LEGACY_OWNER = "legacy-unassigned"
    const val INPUT_OWNER = "profile_owner_v1"
    const val INPUT_SESSION_GENERATION = "profile_session_generation_v1"
    const val INPUT_CANCELLATION_EPOCH = "profile_cancellation_epoch_v1"
    private const val EPOCH_PREFS = "soundwave_persistent_work_identity"
    private const val EPOCH_KEY = "cancellation_epoch"
    // One reentrant authority for persistent epoch and ALL download generation transactions.
    // Do not introduce a second download monitor: finalization re-enters this through metadata,
    // while progress enters it through commitPersistentWork before updating the generation fence.
    internal val durableWorkLock = Any()
    private val sessions = ProfileSessionCoordinator(
        (java.security.SecureRandom().nextLong() and 0x3fffffffffffffffL).coerceAtLeast(1L))

    fun runtime(): ProfileContext = sessions.capture()
    fun isRuntime(context: ProfileContext): Boolean = sessions.isCurrent(context)
    fun rotateLegacy(): ProfileContext = xyz.cncnlp.soundwave.sync.WebDavOperations.identityChange {
        sessions.rotateLegacy()
    }
    fun <T> commit(context: ProfileContext, action: () -> T): T? = sessions.commit(context, action)
    fun remoteReady(context: ProfileContext): Boolean = sessions.remoteReady(context)

    /** Fixed owner survives process restart; cancellation is explicit and durable. */
    fun persistentWork(): ProfileContext = ProfileContext.legacy(0L)
    fun cancellationEpoch(context: Context): Long = context.applicationContext
        .getSharedPreferences(EPOCH_PREFS, Context.MODE_PRIVATE).getLong(EPOCH_KEY, 0L)
    /** Explicit OWNER-WIDE revocation, currently no production caller. Not task cancellation.
     * Never wire this into pause/cancel/download-clear/probe-disable. Existing effects remain;
     * old workers silently stop and do not repair rows or refresh WorkManager inputs.
     * Before exposing this API, an IO recovery controller must durably record revocation intent,
     * cancel/drain old owner work, reconcile queued/retry download rows (preserving paused/cancelled
     * and completed artifacts), and REPLACE stale unique requests with fresh epoch inputs.
     * Enabled periodic probes require reconcile/UPDATE; manual KEEP jobs must be drained/replaced.
     * Recovery must be replayable after process death; no such controller/activation is wired here.
     */
    fun cancelPersistentWork(context: Context): Long = synchronized(durableWorkLock) {
        val p = context.applicationContext.getSharedPreferences(EPOCH_PREFS, Context.MODE_PRIVATE)
        val previous = p.getLong(EPOCH_KEY, 0L)
        check(previous in 0 until Long.MAX_VALUE)
        val next = previous + 1L
        check(p.edit().putLong(EPOCH_KEY, next).commit() && p.getLong(EPOCH_KEY, -1L) == next)
        next
    }
    fun <T> commitPersistentWork(context: Context, token: ProfileContext, epoch: Long, action: () -> T): T? =
        synchronized(durableWorkLock) { if (!isPersistentWork(context, token, epoch)) null else action() }
    fun isPersistentWork(context: Context, token: ProfileContext, epoch: Long): Boolean =
        token.owner == ProfileOwner.LegacyUnassigned && epoch >= 0L && epoch == cancellationEpoch(context)

    fun <T> commitWork(context: ProfileContext, action: () -> T): T? =
        if (context.owner == ProfileOwner.LegacyUnassigned && context.generation == 0L) action()
        else sessions.commit(context, action)
    fun isWork(context: ProfileContext): Boolean = isRuntime(context) ||
        (context.owner == ProfileOwner.LegacyUnassigned && context.generation == 0L)

    fun encodeOwner(context: ProfileContext): String = when (val owner = context.owner) {
        ProfileOwner.LegacyUnassigned -> LEGACY_OWNER
        is ProfileOwner.RecordedProfile -> "profile:${owner.id.value}"
    }
    fun decode(owner: String?, generation: Long): ProfileContext? {
        if (generation < 0L || (owner == null && generation != 0L)) return null
        return if (owner == null || owner == LEGACY_OWNER) ProfileContext.legacy(generation) else null
    }
}
