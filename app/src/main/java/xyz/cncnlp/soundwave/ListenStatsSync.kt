package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileOwner
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import java.util.concurrent.Executors

internal data class ListenStatsWriteOwner(val owner: ProfileOwner, val generation: Long) {
    companion object {
        fun of(context: ProfileContext) = ListenStatsWriteOwner(context.owner, context.generation)
    }
}

/** Process-scoped, so accepted writes remain observable after a service instance is destroyed. */
internal object ListenStatsSync {
    private var writer = ListenStatsWriteQueue<ListenStatsWriteOwner>(
        Executors.newSingleThreadExecutor { task -> Thread(task, "SWListenStats").apply { isDaemon = true } }
    )
    internal const val SNAPSHOT_TIMEOUT_MS = 5_000L

    fun submit(context: Context, owner: ProfileContext, markPlay: Song?, flushes: List<ListenStatsAccumulator.Flush>, notifyLocalChange: Boolean = true) {
        if (markPlay == null && flushes.isEmpty()) return
        val app = context.applicationContext
        val key = ListenStatsWriteOwner.of(owner)
        val queue = writer
        // Capture attribution before queueing; never read a later login session on the worker.
        val accountUser = try { ListenStats.activeAccountUser(app) } catch (_: Exception) {
            val operation = if (markPlay != null) ListenStatsWriteQueue.Operation.MARK_PLAY else ListenStatsWriteQueue.Operation.DURATION
            queue.submit(key, operation) { throw IllegalStateException("Stats attribution unavailable") }
            return
        }
        markPlay?.let { song ->
            queue.submit(key, ListenStatsWriteQueue.Operation.MARK_PLAY, { ProfileWorkIdentity.isWork(owner) }) {
                ContextData(app, owner).markPlay(song, accountUser).also { committed ->
                    if (notifyLocalChange && committed && owner.owner == ProfileOwner.LegacyUnassigned && accountUser != null) {
                        AccountOwnedDataChanges.localChange(AccountDataIdentity.key(accountUser))
                    }
                }
            }
        }
        flushes.forEach { flush ->
            queue.submit(key, ListenStatsWriteQueue.Operation.DURATION, { ProfileWorkIdentity.isWork(owner) }) {
                ContextData(app, owner).addDuration(flush.song, flush.seconds, accountUser).also { committed ->
                    if (notifyLocalChange && committed && owner.owner == ProfileOwner.LegacyUnassigned && accountUser != null) {
                        AccountOwnedDataChanges.localChange(AccountDataIdentity.key(accountUser))
                    }
                }
            }
        }
    }

    /**
     * Queues a caller-built write on the shared serial writer with the same owner
     * gating and unconfirmed-failure tracking as [submit]. PlayerService uses this
     * to apply its accumulated duration flushes itself (r658c D#2) without giving up
     * the snapshot barrier: batches share the writer and failure state with [submit].
     */
    fun submitOnWriter(owner: ProfileContext, operation: ListenStatsWriteQueue.Operation, write: () -> Boolean) {
        writer.submit(ListenStatsWriteOwner.of(owner), operation, { ProfileWorkIdentity.isWork(owner) }, write)
    }

    fun submitBackfill(context: Context, owner: ProfileContext) {
        val app = context.applicationContext
        val queue = writer
        val key = ListenStatsWriteOwner.of(owner)
        when (val profileOwner = owner.owner) {
            is ProfileOwner.RecordedProfile -> {
                queue.submit(key, ListenStatsWriteQueue.Operation.BACKFILL,
                    { ProfileWorkIdentity.isWork(owner) }) {
                    ProfileWorkIdentity.commitWork(owner) {
                        ListenStats.backfillFromHistory(app, profileOwner.id, ContextData(app, owner).history())
                    } == true
                }
            }
            ProfileOwner.LegacyUnassigned -> {
                // Account history is backfillable only when the v2 owner marker and
                // active login match at submission; both are checked again around
                // the worker history read so a queued A job cannot pick up B/guest data.
                val account = ListenStats.activeAccountUser(app) ?: return
                val storedOwner = LocalStore.accountDataOwner(app)
                val expectedOwner = AccountDataIdentity.key(account)
                if (storedOwner != expectedOwner) return
                queue.submit(key, ListenStatsWriteQueue.Operation.BACKFILL,
                    { ProfileWorkIdentity.isWork(owner) }) {
                    ProfileWorkIdentity.commitWork(owner) {
                        if (ListenStats.activeAccountUser(app) != account ||
                            LocalStore.accountDataOwner(app) != expectedOwner
                        ) return@commitWork true // Obsolete derived work has no facts to commit.
                        val history = ContextData(app, owner).history()
                        if (ListenStats.activeAccountUser(app) != account ||
                            LocalStore.accountDataOwner(app) != expectedOwner
                        ) return@commitWork true
                        val committed = ListenStats.backfillAccountHistory(app, account, expectedOwner, history)
                        // Skipped estimates can be recomputed from their owner's preserved history.
                        // Do not latch a failure for superseded history/identity, but retain a
                        // genuine unconfirmed write for the still-current account and history.
                        committed || ListenStats.activeAccountUser(app) != account ||
                            LocalStore.accountDataOwner(app) != expectedOwner ||
                            LocalStore.loadHistory(app) != history
                    } == true
                }
            }
        }
    }

    suspend fun webDavSnapshot(
        context: Context,
        bundle: LocalStore.UserBundle,
        owner: ProfileContext = ProfileWorkIdentity.runtime(),
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): LocalStore.UserBundle {
        val account = ListenStats.activeAccountUser(context)
            ?: throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
        val stats = snapshot(owner, dispatcher) {
            if (ListenStats.activeAccountUser(context) != account) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
            ListenStats.accountDailyListenStatsForAccount(context, account, AccountDataIdentity.ownedStats(bundle, account))
        }
        if (ListenStats.activeAccountUser(context) != account) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
        return bundle.syncSnapshot().copy(songPlayCountCounters = linkedMapOf(), dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = AccountDataIdentity.key(account), legacyDailyListenStatsJson = null)
    }

    suspend fun <T : Any> snapshot(
        owner: ProfileContext = ProfileWorkIdentity.runtime(),
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        read: () -> T
    ): T = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        if (!ProfileWorkIdentity.isRuntime(owner)) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
        val queue = writer
        val result = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
            // No transaction/storage lock is held during this suspending main-thread handoff.
            PlayerService.prepareListenStatsSnapshot(owner)
            // The idle fast path reads on this IO dispatcher; busy reads run on the writer.
            queue.snapshot(ListenStatsWriteOwner.of(owner)) {
                if (!ProfileWorkIdentity.isRuntime(owner)) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
                read()
            }
        } ?: run {
            queue.reportTimeout()
            throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.TIMEOUT)
        }
        currentCoroutineContext().ensureActive()
        if (!ProfileWorkIdentity.isRuntime(owner)) throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
        result
    }
}
