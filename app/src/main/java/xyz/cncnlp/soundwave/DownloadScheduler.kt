package xyz.cncnlp.soundwave

import android.content.Context
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import xyz.cncnlp.soundwave.download.EncryptedMediaTooLargeException
import xyz.cncnlp.soundwave.download.DownloadClearProtocol
import xyz.cncnlp.soundwave.download.DownloadGenerationFence
import xyz.cncnlp.soundwave.download.DownloadSafCommitProtocol
import xyz.cncnlp.soundwave.download.DownloadTaskIdentity
import xyz.cncnlp.soundwave.download.DownloadNotificationPolicy
import xyz.cncnlp.soundwave.download.DownloadTaskState
import xyz.cncnlp.soundwave.download.DownloadTaskStateMachine
import xyz.cncnlp.soundwave.diagnostics.ConcurrentWorkDiagnostics

internal enum class DownloadWorkSnapshot { MISSING, CURRENT_ACTIVE, STALE_ACTIVE, CURRENT_FINISHED, STALE_FINISHED }
internal enum class DownloadQueueRecoveryAction { KEEP, ENQUEUE, MARK_FAILED }

internal object DownloadQueueRecoveryPolicy {
    fun decide(status: String, work: DownloadWorkSnapshot): DownloadQueueRecoveryAction {
        // SW-02-14：状态语义统一走 DownloadTaskState（旧别名 resolving/completed 等在此归一）。
        return when (DownloadTaskState.fromPersisted(status)) {
            DownloadTaskState.QUEUED -> when (work) {
                DownloadWorkSnapshot.CURRENT_ACTIVE -> DownloadQueueRecoveryAction.KEEP
                DownloadWorkSnapshot.CURRENT_FINISHED -> DownloadQueueRecoveryAction.MARK_FAILED
                DownloadWorkSnapshot.MISSING,
                DownloadWorkSnapshot.STALE_ACTIVE,
                DownloadWorkSnapshot.STALE_FINISHED -> DownloadQueueRecoveryAction.ENQUEUE
            }
            DownloadTaskState.RESOLVING,
            DownloadTaskState.DOWNLOADING,
            DownloadTaskState.VERIFYING,
            DownloadTaskState.RETRY_WAIT ->
                if (work == DownloadWorkSnapshot.CURRENT_ACTIVE) DownloadQueueRecoveryAction.KEEP
                else DownloadQueueRecoveryAction.MARK_FAILED
            else -> DownloadQueueRecoveryAction.KEEP
        }
    }
}

/**
 * 下载是持久任务而不是 Activity 协程：WorkManager 负责网络约束、进程重启后的重试与同曲去重。
 * 不持久化带时效的播放 URL；每次 Worker 实际执行时重新走用户已启用的本地脚本取链。
 */
object DownloadScheduler {
    /** 解析/下载任务全局只允许两个进入，避免多个 WorkManager Worker 反向挤占播放 Runtime。 */
    private val taskLane = Semaphore(2)
    private val waitingTaskCount = AtomicInteger()
    private val activeTaskCount = AtomicInteger()
    private const val INPUT_SONG = "song"
    private const val INPUT_QUALITY = "quality"
    internal const val INPUT_TASK_ID = "task_identity_v2"
    internal const val INPUT_GENERATION = "generation"
    internal const val INPUT_TASK_EPOCH = "task_epoch"
    private const val TASK_EPOCH_PREFIX = "task_epoch_"
    private const val TASK_EPOCH_TAG_PREFIX = "task_epoch:"
    private const val PREFIX = "soundwave_download_"
    private const val DOWNLOAD_WORK_TAG = "soundwave_download_worker"
    private const val GENERATION_PREFS = "soundwave_download_scheduler"
    private const val GENERATION_KEY = "clear_generation"
    private const val NOTIFY_CHANNEL = "soundwave_downloads"
    // Epoch and clear-generation nesting use the SAME reentrant monitor in either direction.
    private val generationFence = DownloadGenerationFence(ProfileWorkIdentity.durableWorkLock)

    internal fun restoreServiceBase(context: Context): String {
        val stored = xyz.cncnlp.soundwave.ContextPreferences.open(context.applicationContext, "soundwave", Context.MODE_PRIVATE, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
            .getString("server_url", "").orEmpty()
        return ApiClient.normalizeBase(stored).takeIf { it.startsWith("https://") }
            ?: DEFAULT_SERVER_URL
    }

    private fun legacyTaskId(song: Song, quality: String): String = "$PREFIX${song.key().hashCode().toUInt().toString(16)}_${quality.ifBlank { "320k" }}"

    fun taskId(song: Song, quality: String): String = DownloadTaskIdentity.taskId(song, quality)

    internal fun isSupportedTaskId(candidateTaskId: String, song: Song, quality: String): Boolean =
        candidateTaskId == taskId(song, quality) ||
            candidateTaskId == DownloadTaskIdentity.previousV2TaskId(song, quality) ||
            candidateTaskId == legacyTaskId(song, quality)

    internal fun oldTaskId(song: Song, quality: String): String = legacyTaskId(song, quality)

    fun enqueue(context: Context, song: Song, quality: String): String {
        val app = context.applicationContext
        val selectedQuality = quality.ifBlank { "320k" }
        return generationFence.atCurrentGeneration({ currentGeneration(app) }) { generation ->
            enqueueLocked(app, song, selectedQuality, generation)
        }
    }

    private fun enqueueLocked(app: Context, song: Song, selectedQuality: String, generation: Long): String {
        // Drain old legacy jobs under their original identity; never create a parallel replacement.
        val songIdentity = DownloadTaskIdentity.songKey(song)
        val id = LocalStore.loadDownloads(app).firstOrNull { DownloadTaskIdentity.songKey(it.song) == songIdentity && it.quality == selectedQuality }
            ?.taskId?.takeIf { it.isNotBlank() } ?: taskId(song, selectedQuality)
        // 恢复/重试不得把既有 paused/queued 项重置或插队；仅缺失时建立记录。
        if (LocalStore.loadDownloads(app).none { it.taskId == id || (DownloadTaskIdentity.songKey(it.song) == songIdentity && it.quality == selectedQuality) }) {
            LocalStore.addDownload(app, DownloadItem(
                song = song,
                path = "pending:$id",
                size = 0L,
                time = System.currentTimeMillis(),
                status = "queued",
                progress = 0f,
                quality = selectedQuality,
                platform = song.source,
                taskId = id
            ))
        }
        return enqueueWork(app, song, selectedQuality, generation, id)
    }

    private fun enqueueWork(
        app: Context,
        song: Song,
        selectedQuality: String,
        generation: Long,
        id: String,
        existingWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
    ): String {
        val ownerContext = ProfileWorkIdentity.persistentWork()
        val taskEpoch = taskEpoch(app, id)
        val input = Data.Builder()
            .putString(ProfileWorkIdentity.INPUT_OWNER, ProfileWorkIdentity.encodeOwner(ownerContext))
            .putLong(ProfileWorkIdentity.INPUT_SESSION_GENERATION, ownerContext.generation)
            .putLong(ProfileWorkIdentity.INPUT_CANCELLATION_EPOCH, ProfileWorkIdentity.cancellationEpoch(app))
            .putString(INPUT_TASK_ID, id)
            .putString(INPUT_SONG, song.toJson().toString())
            .putString(INPUT_QUALITY, selectedQuality)
            .putLong(INPUT_GENERATION, generation)
            .putLong(INPUT_TASK_EPOCH, taskEpoch)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(150, TimeUnit.MILLISECONDS)
            .addTag(id)
            .addTag(DOWNLOAD_WORK_TAG)
            .addTag("$TASK_EPOCH_TAG_PREFIX$taskEpoch")
            .addTag("owner:${ProfileWorkIdentity.encodeOwner(ownerContext)}:${ownerContext.generation}")
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(id, existingWorkPolicy, request)
        recordTaskEvent(id, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.QUEUE, ConcurrentWorkDiagnostics.Outcome.ACCEPTED)
        return id
    }

    fun enqueueBatch(context: Context, songs: List<Pair<Song, String>>): List<String> {
        val app = context.applicationContext
        return generationFence.atCurrentGeneration({ currentGeneration(app) }) { generation ->
            val existing = LocalStore.loadDownloads(app).associateBy { DownloadTaskIdentity.songKey(it.song) to it.quality }
            val unique = songs.distinctBy { DownloadTaskIdentity.songKey(it.first) to it.second.ifBlank { "320k" } }
            val prepared = unique.map { (song, quality) ->
                val q = quality.ifBlank { "320k" }
                val old = existing[DownloadTaskIdentity.songKey(song) to q]
                val id = old?.taskId?.takeIf { it.isNotBlank() } ?: taskId(song, q)
                Triple(song, q, id)
            }
            LocalStore.addDownloads(app, prepared.filter { existing[DownloadTaskIdentity.songKey(it.first) to it.second] == null }.map { (song, q, id) ->
                DownloadItem(song = song, path = "pending:$id", size = 0L, time = System.currentTimeMillis(), status = "queued", progress = 0f, quality = q, platform = song.source, taskId = id)
            })
            prepared.map { (song, q, id) -> enqueueWork(app, song, q, generation, id) }
        }
    }

    /** Repairs the LocalStore-before-WorkManager crash window without reviving terminal rows. */
    internal fun reconcileQueuedWork(context: Context): Int {
        val app = context.applicationContext
        val generation = currentGeneration(app)
        val workManager = try { WorkManager.getInstance(app) } catch (_: Exception) { return 0 }
        val queued = LocalStore.loadDownloads(app).filter {
            it.status in DownloadTaskStateMachine.activePersistedValues && it.taskId.isNotBlank()
        }
        var changed = 0
        for (item in queued) {
            val id = item.taskId
            if (!isSupportedTaskId(id, item.song, item.quality)) continue
            val expectedTaskEpoch = taskEpoch(app, id)
            val initialSnapshot = queryWorkSnapshot(workManager, id, expectedTaskEpoch) ?: continue
            if (DownloadQueueRecoveryPolicy.decide(item.status, initialSnapshot) == DownloadQueueRecoveryAction.KEEP) continue

            // Query outside generationFence: a slow WorkManager database read must not hold the
            // same monitor used by download chunk writes, clear, and final-file commits.
            val latestSnapshot = queryWorkSnapshot(workManager, id, expectedTaskEpoch) ?: continue

            val didChange = generationFence.commitIfCurrent({ currentGeneration(app) }, generation) {
                if (taskEpoch(app, id) != expectedTaskEpoch) return@commitIfCurrent false
                val current = LocalStore.loadDownloads(app).firstOrNull { row ->
                    row.taskId == id || (DownloadTaskIdentity.songKey(row.song) == DownloadTaskIdentity.songKey(item.song) && row.quality == item.quality)
                } ?: return@commitIfCurrent false
                if (current.status !in DownloadTaskStateMachine.activePersistedValues) {
                    return@commitIfCurrent false
                }
                when (DownloadQueueRecoveryPolicy.decide(current.status, latestSnapshot)) {
                    DownloadQueueRecoveryAction.KEEP -> false
                    DownloadQueueRecoveryAction.MARK_FAILED -> {
                        LocalStore.addDownload(app, current.copy(
                            taskId = id,
                            status = "failed",
                            error = "下载任务已结束，自动恢复已停止；请手动重试"
                        ))
                        true
                    }
                    DownloadQueueRecoveryAction.ENQUEUE -> {
                        LocalStore.addDownload(app, current.copy(taskId = id))
                        val policy = if (latestSnapshot == DownloadWorkSnapshot.STALE_ACTIVE) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                        try {
                            enqueueWork(app, current.song, current.quality.ifBlank { "320k" }, generation, id, policy)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            } == true
            if (didChange) {
                changed++
                val action = DownloadQueueRecoveryPolicy.decide(item.status, latestSnapshot)
                recordTaskEvent(
                    id,
                    item.song,
                    expectedTaskEpoch,
                    ConcurrentWorkDiagnostics.Phase.RECOVERY,
                    if (action == DownloadQueueRecoveryAction.ENQUEUE) ConcurrentWorkDiagnostics.Outcome.RECOVERED else ConcurrentWorkDiagnostics.Outcome.FAILED,
                    failureKind = if (action == DownloadQueueRecoveryAction.MARK_FAILED) ConcurrentWorkDiagnostics.FailureKind.PERSISTENCE else null,
                    queuedCount = queued.size
                )
            }
        }
        return changed
    }

    private fun queryWorkSnapshot(
        workManager: WorkManager,
        id: String,
        taskEpoch: Long
    ): DownloadWorkSnapshot? = try {
        workSnapshot(workManager.getWorkInfosForUniqueWork(id).get(5, TimeUnit.SECONDS), taskEpoch)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: Exception) {
        null
    }

    private fun workSnapshot(infos: List<WorkInfo>, taskEpoch: Long): DownloadWorkSnapshot {
        if (infos.isEmpty()) return DownloadWorkSnapshot.MISSING
        fun isCurrent(info: WorkInfo) = info.tags.any { it == "$TASK_EPOCH_TAG_PREFIX$taskEpoch" } ||
            (taskEpoch == 0L && info.tags.none { it.startsWith(TASK_EPOCH_TAG_PREFIX) })
        if (infos.any { isCurrent(it) && !it.state.isFinished }) return DownloadWorkSnapshot.CURRENT_ACTIVE
        if (infos.any { isCurrent(it) && it.state.isFinished }) return DownloadWorkSnapshot.CURRENT_FINISHED
        if (infos.any { !it.state.isFinished }) return DownloadWorkSnapshot.STALE_ACTIVE
        return DownloadWorkSnapshot.STALE_FINISHED
    }

    internal fun recordTaskEvent(
        taskId: String,
        song: Song,
        queueVersion: Long,
        phase: ConcurrentWorkDiagnostics.Phase,
        outcome: ConcurrentWorkDiagnostics.Outcome,
        cancelReason: ConcurrentWorkDiagnostics.CancelReason? = null,
        failureKind: ConcurrentWorkDiagnostics.FailureKind? = null,
        queuedCount: Int? = null
    ) {
        val (waiting, active) = queueSnapshot()
        ConcurrentWorkDiagnostics.record(
            kind = ConcurrentWorkDiagnostics.WorkKind.DOWNLOAD,
            phase = phase,
            outcome = outcome,
            taskId = taskId,
            identity = DownloadTaskIdentity.songKey(song),
            queueVersion = queueVersion,
            cancelReason = cancelReason,
            failureKind = failureKind,
            queuedCount = queuedCount ?: waiting,
            activeCount = active
        )
    }

    internal fun queueSnapshot(): Pair<Int, Int> =
        waitingTaskCount.get().coerceAtLeast(0) to activeTaskCount.get().coerceAtLeast(0)

    fun stopBatch(context: Context, items: List<DownloadItem>, paused: Boolean): List<DownloadItem> =
        stopBatchAndSnapshot(context, items, if (paused) "paused" else null)

    /**
     * Invalidates selected workers and returns their authoritative post-stop rows. The caller's
     * list is a UI snapshot and may still contain pending: paths after a worker has committed.
     */
    private fun stopBatchAndSnapshot(
        context: Context,
        items: List<DownloadItem>,
        statusAfterStop: String?
    ): List<DownloadItem> {
        val app = context.applicationContext
        return generationFence.atCurrentGeneration({ currentGeneration(app) }) { _ ->
            val ids = items.map { it.taskId.ifBlank { taskId(it.song, it.quality) } }.distinct()
            val prefs = app.getSharedPreferences(GENERATION_PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            ids.forEach { id ->
                val nextEpoch = taskEpoch(app, id) + 1L
                editor.putLong(TASK_EPOCH_PREFIX + id, nextEpoch)
                val item = items.firstOrNull { it.taskId.ifBlank { taskId(it.song, it.quality) } == id }
                if (item != null) recordTaskEvent(
                    id,
                    item.song,
                    nextEpoch,
                    ConcurrentWorkDiagnostics.Phase.EXECUTION,
                    ConcurrentWorkDiagnostics.Outcome.CANCELLED,
                    cancelReason = if (statusAfterStop == "paused") ConcurrentWorkDiagnostics.CancelReason.PAUSED else ConcurrentWorkDiagnostics.CancelReason.USER
                )
            }
            editor.apply()
            val work = WorkManager.getInstance(app)
            ids.forEach { id -> work.cancelUniqueWork(id); DownloadHelper.cancel(id) }

            // The generation fence serializes this read with DownloadHelper's final commit.
            // Preserve a completed/failed/cancelled row if finalization won before this stop.
            val current = LocalStore.loadDownloads(app)
            val currentById = current.associateBy {
                it.taskId.ifBlank { taskId(it.song, it.quality) }
            }
            val currentByIdentity = current.associateBy { DownloadTaskIdentity.songKey(it.song) to it.quality }
            val selected = items.map { requested ->
                val id = requested.taskId.ifBlank { taskId(requested.song, requested.quality) }
                (currentById[id] ?: currentByIdentity[DownloadTaskIdentity.songKey(requested.song) to requested.quality])
                    ?.let { if (it.taskId.isBlank()) it.copy(taskId = id) else it }
                    ?: requested.copy(taskId = id)
            }
            val updates = statusAfterStop?.let { requestedStatus ->
                val preserve = if (requestedStatus == "paused") {
                    setOf(DownloadTaskState.COMPLETED, DownloadTaskState.FAILED, DownloadTaskState.CANCELLED)
                } else {
                    setOf(DownloadTaskState.COMPLETED, DownloadTaskState.CANCELLED)
                }
                selected.filterNot { DownloadTaskState.fromPersisted(it.status) in preserve }.map { item ->
                    item.copy(
                        status = requestedStatus,
                        progress = if (requestedStatus == "cancelled") 0f else item.progress,
                        error = if (requestedStatus == "paused") "已暂停" else "已取消"
                    )
                }
            }.orEmpty()
            LocalStore.addDownloads(app, updates)
            val updatedById = updates.associateBy { it.taskId }
            selected.map { updatedById[it.taskId] ?: it }
        }
    }

    fun resumeBatch(context: Context, items: List<DownloadItem>) {
        val app = context.applicationContext
        generationFence.atCurrentGeneration({ currentGeneration(app) }) { generation ->
            val queued = items.map { it.copy(status = "queued", progress = 0f, error = "", taskId = it.taskId.ifBlank { taskId(it.song, it.quality) }) }
            LocalStore.addDownloads(app, queued)
            queued.forEach { enqueueWork(app, it.song, it.quality, generation, it.taskId, ExistingWorkPolicy.REPLACE) }
        }
    }

    internal suspend fun <T> withTaskLane(block: suspend () -> T): T {
        waitingTaskCount.incrementAndGet()
        var countedAsWaiting = true
        try {
            return taskLane.withPermit {
                if (countedAsWaiting) {
                    waitingTaskCount.decrementAndGet()
                    countedAsWaiting = false
                }
                val active = activeTaskCount.incrementAndGet()
                ConcurrentWorkDiagnostics.record(
                    kind = ConcurrentWorkDiagnostics.WorkKind.DOWNLOAD,
                    phase = ConcurrentWorkDiagnostics.Phase.QUEUE,
                    outcome = ConcurrentWorkDiagnostics.Outcome.STARTED,
                    queuedCount = waitingTaskCount.get(),
                    activeCount = active
                )
                try { block() } finally { activeTaskCount.decrementAndGet() }
            }
        } finally {
            if (countedAsWaiting) waitingTaskCount.decrementAndGet()
        }
    }

    fun pause(context: Context, item: DownloadItem) {
        stopBatch(context, listOf(item), paused = true)
    }

    fun resume(context: Context, item: DownloadItem): String {
        val app = context.applicationContext
        val id = item.taskId.ifBlank { taskId(item.song, item.quality) }
        return generationFence.atCurrentGeneration({ currentGeneration(app) }) { generation ->
            LocalStore.addDownload(app, item.copy(status = "queued", progress = 0f, error = "", taskId = id))
            enqueueWork(app, item.song, item.quality.ifBlank { "320k" }, generation, id, ExistingWorkPolicy.REPLACE)
        }
    }

    fun cancel(context: Context, item: DownloadItem) {
        stopBatchAndSnapshot(context, listOf(item), "cancelled")
    }

    fun clearDownloads(context: Context, removeFiles: Boolean): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(GENERATION_PREFS, Context.MODE_PRIVATE)
        val result = DownloadClearProtocol.clear(
            fence = generationFence,
            readGeneration = { prefs.getLong(GENERATION_KEY, 0L) },
            persistGeneration = { next -> prefs.edit().putLong(GENERATION_KEY, next).commit() },
            cancelAll = {
                val workManager = try { WorkManager.getInstance(app) } catch (_: Exception) { null }
                if (workManager != null) {
                    runCatching { workManager.cancelAllWorkByTag(DOWNLOAD_WORK_TAG) }
                    // WorkRequest automatically tags each request with its Worker class name;
                    // retain this cancellation path for tasks enqueued by older app versions.
                    runCatching { workManager.cancelAllWorkByTag(DownloadWorker::class.java.name) }
                    LocalStore.loadDownloads(app).forEach { item ->
                        val id = item.taskId.ifBlank { taskId(item.song, item.quality) }
                        recordTaskEvent(id, item.song, taskEpoch(app, id) + 1L, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.CANCELLED, cancelReason = ConcurrentWorkDiagnostics.CancelReason.CLEAR_ALL)
                        runCatching { workManager.cancelUniqueWork(id) }
                        DownloadHelper.cancel(id)
                    }
                }
                DownloadHelper.cancelAll()
            },
            loadItems = { LocalStore.loadDownloads(app) },
            deleteArtifact = { item -> DownloadHelper.deleteArtifact(app, item) },
            clearRecords = { LocalStore.clearDownloads(app) },
            removeFiles = removeFiles
        )
        return result != null
    }

    internal fun isCurrentGeneration(context: Context, expectedGeneration: Long): Boolean =
        generationFence.commitIfCurrent({ currentGeneration(context.applicationContext) }, expectedGeneration) { true } == true

    internal fun <T> commitIfCurrent(context: Context, expectedGeneration: Long, action: () -> T): T? =
        generationFence.commitIfCurrent({ currentGeneration(context.applicationContext) }, expectedGeneration, action)

    internal fun currentGeneration(context: Context): Long = context.applicationContext
        .getSharedPreferences(GENERATION_PREFS, Context.MODE_PRIVATE)
        .getLong(GENERATION_KEY, 0L)

    internal fun taskEpoch(context: Context, taskId: String): Long =
        context.applicationContext.getSharedPreferences(GENERATION_PREFS, Context.MODE_PRIVATE)
            .getLong(TASK_EPOCH_PREFIX + taskId, 0L)

    internal fun isTaskCurrent(context: Context, taskId: String, epoch: Long): Boolean = taskEpoch(context, taskId) == epoch

    fun update(context: Context, item: DownloadItem, expectedGeneration: Long): Boolean =
        update(context, item, expectedGeneration, null)

    fun update(context: Context, item: DownloadItem, expectedGeneration: Long, expectedTaskEpoch: Long?): Boolean {
        return generationFence.commitIfCurrent(
            { currentGeneration(context.applicationContext) },
            expectedGeneration
        ) {
            if (expectedTaskEpoch != null && !isTaskCurrent(context, item.taskId, expectedTaskEpoch)) false
            else { LocalStore.addDownload(context.applicationContext, item); true }
        } == true
    }

    fun foregroundInfo(context: Context, item: DownloadItem): androidx.work.ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(NOTIFY_CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载进度通知，静音低优先级"
            }
        )
        val pct = (item.progress.coerceIn(0f, 1f) * 100).toInt()
        // SW-02-14：通知标题/进行态由 DownloadNotificationPolicy 按状态统一给出，
        // 且每个任务用稳定独立的通知 ID，并发 Worker 不再互相覆盖进度。
        val presentation = DownloadNotificationPolicy.presentation(item.status)
        val n = NotificationCompat.Builder(context, NOTIFY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(presentation.title)
            .setContentText(item.song.name)
            .setProgress(100, pct, presentation.indeterminate)
            .setOngoing(presentation.ongoing)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        // Android 14+ 禁止 WorkManager 以 type=none 启动前台服务；下载明确声明 dataSync。
        return if (Build.VERSION.SDK_INT >= 29) {
            androidx.work.ForegroundInfo(DownloadNotificationPolicy.notificationId(item.taskId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            androidx.work.ForegroundInfo(DownloadNotificationPolicy.notificationId(item.taskId), n)
        }
    }

}

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = DownloadScheduler.withTaskLane { withContext(Dispatchers.IO) {
        // Old process-lease inputs are explicitly legacy-owned too; never bind to runtime().
        val encodedOwner = ProfileWorkIdentity.decode(
            inputData.getString(ProfileWorkIdentity.INPUT_OWNER),
            inputData.getLong(ProfileWorkIdentity.INPUT_SESSION_GENERATION, 0L)
        ) ?: return@withContext Result.failure()
        val ownerContext = ProfileWorkIdentity.persistentWork()
        val cancellationEpoch = inputData.getLong(ProfileWorkIdentity.INPUT_CANCELLATION_EPOCH, 0L)
        if (!ProfileWorkIdentity.isPersistentWork(applicationContext, encodedOwner, cancellationEpoch)) return@withContext Result.success()
        val generation = inputData.getLong(DownloadScheduler.INPUT_GENERATION, 0L)
        if (!DownloadScheduler.isCurrentGeneration(applicationContext, generation)) return@withContext Result.success()
        val rawSong = inputData.getString("song").orEmpty()
        val quality = inputData.getString("quality").orEmpty().ifBlank { "320k" }
        val song = try { Song.fromJson(JSONObject(rawSong)) } catch (_: Exception) { return@withContext Result.failure() }
        val taskId = inputData.getString(DownloadScheduler.INPUT_TASK_ID) ?: DownloadScheduler.oldTaskId(song, quality)
        if (!DownloadScheduler.isSupportedTaskId(taskId, song, quality)) return@withContext Result.failure()
        val taskEpoch = inputData.getLong(DownloadScheduler.INPUT_TASK_EPOCH, 0L)
        fun isCancelled() = !ProfileWorkIdentity.isPersistentWork(applicationContext, ownerContext, cancellationEpoch) || isStopped ||
            !DownloadScheduler.isCurrentGeneration(applicationContext, generation) || !DownloadScheduler.isTaskCurrent(applicationContext, taskId, taskEpoch)
        if (isCancelled()) {
            DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.CANCELLED, cancelReason = ConcurrentWorkDiagnostics.CancelReason.WORKER_STOPPED)
            return@withContext Result.success()
        }
        DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.STARTED)
        // 状态进度写回会把 path 改成 pending；先保存同一歌曲/音质既有完成目标，提交成功后再安全替换。
        val data = ContextData(applicationContext, ownerContext, cancellationEpoch)
        fun update(context: Context, item: DownloadItem, generation: Long): Boolean =
            ProfileWorkIdentity.commitPersistentWork(context, ownerContext, cancellationEpoch) {
                DownloadScheduler.update(context, item, generation, taskEpoch)
            } == true
        val previousOutputPath = data.downloads().firstOrNull {
            it.status == "done" && !it.path.isBlank() && !it.path.startsWith("pending:") &&
                (it.taskId == taskId || (DownloadTaskIdentity.songKey(it.song) == DownloadTaskIdentity.songKey(song) && it.quality == quality))
        }?.path.orEmpty()
        // WorkManager 可在独立冷启动进程执行，ApiClient.baseUrl 不会继承 Activity。
        // 必须与前台同样恢复默认 HTTPS 服务，才能代理 HTTP 音源。
        val serviceBase = DownloadScheduler.restoreServiceBase(applicationContext)
        ApiClient.setBase(serviceBase)
        fun state(status: String, progress: Float = 0f, error: String = "", resolve: ResolveResult? = null) = DownloadItem(
            song = song,
            path = "pending:$taskId",
            size = 0L,
            time = System.currentTimeMillis(),
            status = status,
            progress = progress.coerceIn(0f, 1f),
            error = error.take(160),
            quality = resolve?.quality.orEmpty().ifBlank { quality },
            platform = resolve?.platform.orEmpty().ifBlank { song.source },
            scriptId = resolve?.scriptId.orEmpty(),
            resolveFailure = resolve?.failure?.name.orEmpty(),
            taskId = taskId
        )
            // 下载必须拥有独立的脚本运行时。播放、预取和下载若共用 WebView/evalMutex，
            // 任一上游脚本挂起都会把前台连续播放拖入等待队列。
            val runtime = DownloadScriptRuntimeHolder.get(applicationContext, ownerContext)
            try {
                runtime.init()
                SmartSourceStore.init(applicationContext)
                ScriptVerdictStore.init(applicationContext)
                ScriptProbeMemoryStore.init(applicationContext)
                val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(applicationContext, "soundwave_scripts", Context.MODE_PRIVATE, ownerContext)
                val scripts = data.scripts().filter { it.enabled && it.code.isNotBlank() }
                if (scripts.isEmpty()) {
                    update(applicationContext, state("failed", error = "没有已启用脚本"), generation)
                    DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.FAILED, failureKind = ConcurrentWorkDiagnostics.FailureKind.OTHER)
                    return@withContext Result.failure()
                }
                // 状态机阶段 2/5：AUTHORIZING（授权/取链中）。UI 不再把取链期误显示为“等待下载”。
                update(applicationContext, state("authorizing", 0.02f), generation)
                val result = UrlResolver.resolveResult(
                    ticket = ResolveTicket(songKey = song.key(), purpose = ResolveTicket.Purpose.DOWNLOAD, ownerContext = ownerContext),
                    song = song,
                    scripts = scripts,
                    runtime = runtime,
                    preferQuality = quality,
                    requestStillCurrent = { !isCancelled() }
                )
                if (isCancelled()) throw CancellationException("下载任务已被清理")
                if (!result.isSuccess) {
                    val transient = result.failure == ResolveFailure.TIMEOUT || result.failure == ResolveFailure.NETWORK_ERROR
                    val retryable = transient && runAttemptCount < 3 && !isCancelled()
                    val message = UrlResolver.failureMessage(result.failure).ifBlank { "取链失败" }
                    // 网络类失败在重试窗口内显示 RETRY_WAIT，不再是模糊的 queued。
                    update(applicationContext, state(if (retryable) "retry_wait" else "failed", error = message, resolve = result), generation)
                    DownloadScheduler.recordTaskEvent(
                        taskId,
                        song,
                        taskEpoch,
                        ConcurrentWorkDiagnostics.Phase.EXECUTION,
                        if (retryable) ConcurrentWorkDiagnostics.Outcome.RETRY_SCHEDULED else ConcurrentWorkDiagnostics.Outcome.FAILED,
                        failureKind = if (result.failure == ResolveFailure.TIMEOUT) ConcurrentWorkDiagnostics.FailureKind.TIMEOUT else ConcurrentWorkDiagnostics.FailureKind.NETWORK
                    )
                    if (retryable) return@withContext Result.retry()
                    return@withContext Result.failure()
                }
                // 状态机阶段 3/5：DOWNLOADING（已取得可播放 URL，进入文件传输）。
                val downloading = state("downloading", 0.05f, resolve = result)
                setForeground(DownloadScheduler.foregroundInfo(applicationContext, downloading))
                update(applicationContext, downloading, generation)
                var lastProgressWriteAt = 0L
                var lastProgress = -1f
                DownloadHelper.download(
                    applicationContext, song, ApiClient.playableUrl(result.url), result,
                    taskId = taskId,
                    previousOutputPath = previousOutputPath,
                    expectedGeneration = generation,
                    expectedTaskEpoch = taskEpoch,
                    ownerContext = ownerContext,
                    cancellationEpoch = cancellationEpoch,
                    shouldCancel = { isCancelled() },
                    // r657：重试时走单流续传；首轮失败的多线程稀疏文件已被丢弃。
                    preferSingleStream = runAttemptCount > 0,
                    onProgress = { progress ->
                        if (!isCancelled()) {
                            // 不能每个 64KB 都写一次 SharedPreferences；大文件会堆积 apply，
                            // 造成下载越来越慢、状态倒退，表现为“卡死”。
                            val now = System.currentTimeMillis()
                            // r658d（用户 10-20 下载时卡顿）：每次进度落盘都要整文件重写主 SharedPreferences（含整包用户数据）并 bump 来源版本号，
                            // 250ms/1% 一次会让 apply 队列在主线程 onPause/onStop 时被迫等待、读偏好时争锁 → 全局卡顿。改为 ≥1.2s 且 ≥4%。
                            if (progress >= 1f || (now - lastProgressWriteAt >= 1200L && progress - lastProgress >= 0.04f) || now - lastProgressWriteAt >= 4000L) {
                                lastProgressWriteAt = now
                                lastProgress = progress
                                val progressState = state(
                                    if (progress >= 0.98f) "verifying" else "downloading",
                                    progress,
                                    resolve = result
                                )
                                update(applicationContext, progressState, generation)
                                setForegroundAsync(DownloadScheduler.foregroundInfo(applicationContext, progressState))
                            }
                        }
                    }
                )
                // Helper 已完成音频容器、目标回读和同步记录提交；此处不再用 apply() 覆盖已提交路径。
                DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.SUCCEEDED)
                Result.success()
            } catch (e: DownloadSafCommitProtocol.MetadataCommitException) {
                update(
                    applicationContext,
                    state("failed", error = e.message ?: "下载记录未能可靠保存；新旧文件均已保留"),
                    generation
                )
                DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.FAILED, failureKind = ConcurrentWorkDiagnostics.FailureKind.PERSISTENCE)
                return@withContext Result.failure()
            } catch (e: EncryptedMediaTooLargeException) {
                update(
                    applicationContext,
                    state("failed", error = e.message ?: "加密媒体超过 200 MiB 解密上限，未保存为完成文件"), generation
                )
                DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.FAILED, failureKind = ConcurrentWorkDiagnostics.FailureKind.OTHER)
                return@withContext Result.failure()
            } catch (e: CancellationException) {
                // WorkManager cancel 是暂停/取消的正常路径；暂停状态由 Scheduler 在 cancel 前落盘，不能被 finally 覆盖成失败。
                DownloadScheduler.recordTaskEvent(taskId, song, taskEpoch, ConcurrentWorkDiagnostics.Phase.EXECUTION, ConcurrentWorkDiagnostics.Outcome.CANCELLED, cancelReason = ConcurrentWorkDiagnostics.CancelReason.WORKER_STOPPED)
                throw e
            } catch (e: DownloadHttpStatusException) {
                // r657：CDN 拒绝（403 过期 / 404 / 410）时旧直链已无用。DOWNLOAD 用途命中 URL 缓存不做媒体校验，
                // 不清缓存的话三次重试都会拿到同一条过期链接，最终“下载失败”。清掉后下次重试重新取链。
                val retryable = DownloadRetryPolicy.shouldRetry(e, runAttemptCount, isCancelled())
                val expiredLink = DownloadRetryPolicy.isExpiredLinkStatus(e.code)
                if (retryable && expiredLink) try { UrlResolver.clearSongCache(song, ownerContext) } catch (_: Exception) {}
                val message = when {
                    expiredLink && retryable -> "音频链接已失效，稍后自动重新取链"
                    expiredLink -> "音频链接已失效，自动重试次数已用尽，请手动重试"
                    retryable -> "音频服务器暂时不可用，稍后自动重试"
                    else -> "音频服务器拒绝请求（HTTP ${e.code}）"
                }
                update(applicationContext, state(if (retryable) "retry_wait" else "failed", error = message), generation)
                DownloadScheduler.recordTaskEvent(
                    taskId,
                    song,
                    taskEpoch,
                    ConcurrentWorkDiagnostics.Phase.EXECUTION,
                    if (retryable) ConcurrentWorkDiagnostics.Outcome.RETRY_SCHEDULED else ConcurrentWorkDiagnostics.Outcome.FAILED,
                    failureKind = ConcurrentWorkDiagnostics.FailureKind.HTTP
                )
                if (retryable) Result.retry() else Result.failure()
            } catch (e: Exception) {
                val retryable = DownloadRetryPolicy.shouldRetry(e, runAttemptCount, isCancelled())
                val message = when {
                    e is DownloadTransportException && retryable -> "网络中断，网络恢复后自动重试"
                    e is DownloadTransportException -> "网络中断，自动重试次数已用尽；网络恢复后可手动重试"
                    else -> e.message ?: "下载失败"
                }
                update(applicationContext, state(if (retryable) "retry_wait" else "failed", error = message), generation)
                DownloadScheduler.recordTaskEvent(
                    taskId,
                    song,
                    taskEpoch,
                    ConcurrentWorkDiagnostics.Phase.EXECUTION,
                    if (retryable) ConcurrentWorkDiagnostics.Outcome.RETRY_SCHEDULED else ConcurrentWorkDiagnostics.Outcome.FAILED,
                    failureKind = if (e is DownloadTransportException) ConcurrentWorkDiagnostics.FailureKind.NETWORK else ConcurrentWorkDiagnostics.FailureKind.IO
                )
                if (retryable) Result.retry() else Result.failure()
            } finally {
                // 共享全局实例不销毁；脚本与 WebView 由进程生命周期持有。
            }
    } }
}
