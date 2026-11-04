package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.prefetch.BoundedPrefetchWorkPool
import xyz.cncnlp.soundwave.prefetch.SharedPrefetchWorkPool
import xyz.cncnlp.soundwave.playback.RecoveryAction
import xyz.cncnlp.soundwave.playback.ResolveFailureDisposition
import xyz.cncnlp.soundwave.playback.resolveFailureDisposition
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat
import xyz.cncnlp.soundwave.playback.PlaybackSnapshotPolicy
import xyz.cncnlp.soundwave.playback.SnapshotSongAction
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.heartSimilarSearchRegistrySourceOrNull
import xyz.cncnlp.soundwave.recommend.TasteProfileV2
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import kotlin.random.Random

/** r658c F：断点序列化单线程（进程级共享，Activity 重建不新建线程）。 */
private val playbackCheckpointDispatcher by lazy {
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sw-checkpoint").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
}

/**
 * 播放编排与播放状态单点：从 AppRoot 抽出。
 *
 * 持有取链/切歌/预取/心动模式所需的全部播放态，并作为这些状态唯一写入口。
 * Service（PlayerService）仍是真实播放器与队列快照的事实源，本控制器只消费快照
 * 并驱动“新链就绪后原子切换、旧曲保持播放”的前台事务。
 *
 * 跨切依赖（脚本、用户数据、音质偏好、歌词优先级、历史回写）通过构造参数注入，
 * 避免控制器直接触碰 AppRoot 的 Compose 状态。
 */
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scriptRuntime: ScriptRuntime,
    prefs: SharedPreferences,
    private val getScripts: () -> List<Script>,
    private val getUserBundle: () -> LocalStore.UserBundle,
    private val getPreferQ: () -> String,
    private val getLyricPriority: () -> String,
    private val onHistoryChanged: (List<Song>) -> Unit,
    /** R 波一期：与今日推荐共用的统一画像（前台装配）；缺省为空时心动回落旧无权重行为。 */
    private val getTasteProfile: () -> TasteProfileV2? = { null },
) {
    val currentSongState = mutableStateOf<Song?>(null)
    var currentSong: Song?
        get() = currentSongState.value
        set(v) { currentSongState.value = v }

    private val controllerOwner = scriptRuntime.ownerContext

    init { AppNotice.attach(context.applicationContext) }

    private val playbackFailureRecovery = PlaybackFailureRecovery(
        scope = scope,
        currentSong = { currentSong },
        currentGeneration = { PlayerService.currentGeneration() },
        clearSongCache = { failed -> withContext(Dispatchers.IO) { UrlResolver.clearSongCache(failed, controllerOwner) } },
    )

    val playingState = mutableStateOf(false)
    var playing: Boolean
        get() = playingState.value
        set(v) { playingState.value = v }

    val resolvingState = mutableStateOf(false)
    var resolving: Boolean
        get() = resolvingState.value
        set(v) { resolvingState.value = v }

    private var activeResolveJob: Job? = null
    private var activeResolveRequestId = ""
    private var activeHeartJob: Job? = null
    private val prefetchBatchLock = Any()
    private var activePrefetchBatchJob: Job? = null

    private fun cancelPrefetchBatch() {
        val previous = synchronized(prefetchBatchLock) {
            activePrefetchBatchJob.also { activePrefetchBatchJob = null }
        }
        previous?.cancel(CancellationException("superseded playback prefetch batch"))
    }
    /** 已被 UI 接纳的 Service 代次；用于让服务/媒体会话实际换歌时 UI 能回归唯一事实源。 */
    private var acceptedSnapshotGeneration = -1

    val queueRevisionState = mutableLongStateOf(0L)
    var queueRevision: Long
        get() = queueRevisionState.value
        set(v) { queueRevisionState.value = v }

    val queueCurrentIndexState = mutableIntStateOf(PlayerService.snapshot().queueIndex)
    var queueCurrentIndex: Int
        get() = queueCurrentIndexState.intValue
        set(v) { queueCurrentIndexState.intValue = v }

    // 抽出 Compose 之外的播放事务身份门：旧解析、预取或 Service 回调只能静默让位。
    val playbackTransactionGate = PlaybackTransactionGate()
    val statusState = mutableStateOf("")
    var status: String
        get() = statusState.value
        set(v) { statusState.value = v }

    val progressState = mutableFloatStateOf(0f)
    var progress: Float
        get() = progressState.value
        set(v) { progressState.value = v }

    val durationState = mutableFloatStateOf(0f)
    var duration: Float
        get() = durationState.value
        set(v) { durationState.value = v }

    val currentTimeState = mutableFloatStateOf(0f)
    var currentTime: Float
        get() = currentTimeState.value
        set(v) { currentTimeState.value = v }

    val queueState = mutableStateOf(listOf<Song>())
    var queue: List<Song>
        get() = queueState.value
        set(v) { queueState.value = v }

    /** ACCEPT5 队列来源同步：当前队列的来源歌单身份（无来源为 null）与条目级来源标记（与 [queue] 对齐，手动插入为 false）。 */
    var queueOrigin: QueueOrigin? = null
        private set
    private var queueEntryFlags: List<Boolean> = emptyList()

    /** 把 [queueEntryFlags] 对齐到 [items]（按 key+出现次序继承），返回对齐后的标记。 */
    private fun alignedEntryFlags(items: List<Song>): List<Boolean> =
        QueueOriginSync.reconcileEntryFlags(queue.map { it.key() }, queueEntryFlags, items.map { it.key() })

    /** 生成型会话队列（心动/相似）不属于任何歌单：清来源，条目全标非来源。 */
    private fun clearQueueOrigin(queueSize: Int) {
        queueOrigin = null
        queueEntryFlags = List(queueSize) { false }
    }

    /** 冷启动恢复：断点里的来源身份与条目标记回填（replaceQueueForRestore 之后调用）。 */
    fun adoptRestoredQueueOrigin(origin: QueueOrigin?, flags: List<Boolean>) {
        queueOrigin = origin
        queueEntryFlags = if (flags.size == queue.size) flags else List(queue.size) { origin != null }
    }

    /**
     * ACCEPT5 队列来源同步：来源歌单移除了 [removedKeys] 时，同步移除队列中带来源标记的非活动实例。
     * 活动条目（服务快照 queueIndex；解析事务中即待播条目）受保护，手动插入条目（标记 false）不动；
     * 队列无来源或来源不匹配时无操作。新增/恢复永不走这里（反向不同步由构造保证）。
     */
    fun onOriginPlaylistSongsRemoved(playlistId: String, removedKeys: Set<String>) {
        val origin = queueOrigin ?: return
        if (origin.playlistId != playlistId || removedKeys.isEmpty()) return
        val snap = PlayerService.snapshot()
        val items = snap.queue.ifEmpty { queue }
        if (items.isEmpty()) return
        val flags = alignedEntryFlags(items)
        queueEntryFlags = flags
        val protectedIndex = snap.queueIndex.takeIf { it in items.indices }
            ?: items.indexOfFirst { it.key() == snap.song?.key() }
        val result = QueueOriginSync.removeOriginInstances(items, flags, protectedIndex, removedKeys) ?: return
        replaceQueue(result.items, result.currentIndex)
    }

    val lyricsState = mutableStateOf(listOf<LyricLine>())
    var lyrics: List<LyricLine>
        get() = lyricsState.value
        set(v) { lyricsState.value = v }

    val lyricIdxState = mutableIntStateOf(-1)
    var lyricIdx: Int
        get() = lyricIdxState.value
        set(v) { lyricIdxState.value = v }

    val showLyricsState = mutableStateOf(false)
    var showLyrics: Boolean
        get() = showLyricsState.value
        set(v) { showLyricsState.value = v }

    val playModeState = mutableStateOf(PlayerService.snapshot().mode)
    var playMode: PlayMode
        get() = playModeState.value
        set(v) { playModeState.value = v }
    val playbackAudioFormatState = mutableStateOf(PlayerService.snapshot().audioFormat)

    val sleepRemainState = mutableLongStateOf(0L)
    var sleepRemain: Long
        get() = sleepRemainState.value
        set(v) { sleepRemainState.value = v }

    val likedState = mutableStateOf(false)
    var liked: Boolean
        get() = likedState.value
        set(v) { likedState.value = v }

    val likedTickState = mutableStateOf(0) // force SongRow heart refresh
    var likedTick: Int
        get() = likedTickState.value
        set(v) { likedTickState.value = v }

    val playDeltaRef = arrayOf<suspend (Int) -> Unit>({})
    val prefetchRef = arrayOf<suspend () -> Unit>({})
    val heartExcludeState = mutableStateOf(setOf<String>())
    var heartExclude: Set<String>
        get() = heartExcludeState.value
        set(v) { heartExcludeState.value = v }
    private var activeHeartPlaylistContext: HeartPlaylistContext? = null
    private var heartSessionRevision = 0L
    val activeHeartPlaylistKeyState = mutableStateOf<String?>(null)
    val heartCandidatesState = mutableStateOf<List<Song>>(emptyList())
    var heartCandidates: List<Song>
        get() = heartCandidatesState.value
        set(v) { heartCandidatesState.value = v }

    val prefetchEnabledState = mutableStateOf(prefs.getBoolean("prefetch_next", true))
    var prefetchEnabled: Boolean
        get() = prefetchEnabledState.value
        set(v) { prefetchEnabledState.value = v }

    val prefetchIntervalSecState = mutableIntStateOf(prefs.getInt("prefetch_interval_sec", 6))
    var prefetchIntervalSec: Int
        get() = prefetchIntervalSecState.value
        set(v) { prefetchIntervalSecState.value = v }

    val prefetchCountState = mutableIntStateOf(prefs.getInt("prefetch_count", 2).coerceIn(1, 5))
    var prefetchCount: Int
        get() = prefetchCountState.value
        set(v) { prefetchCountState.value = v }


    /** Synchronously reconcile a route tap without waiting for a new StateFlow emission.
     * Existing snapshot policy keeps an in-flight new-song transaction from being replaced.
     * A different profile's service state must never hydrate this controller.
     */
    fun reconcileForPlayerEntry() {
        val snapshot = PlayerService.snapshot()
        if (!snapshot.ownerContext.matches(controllerOwner) ||
            !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(controllerOwner)) return
        onSnapshot(snapshot)
    }

    /** Service 快照同步：Service 是播放状态唯一事实源。 */
    fun onSnapshot(snapshot: PlaybackSnapshot) {
        // Snapshot 是 Service 的进度/队列事实源，但不是“选歌提交”通道。
        // 用户点击新歌到真正 playUrl/onSongChanged 之间，Service 必然还会连续发布旧歌快照；
        // 若这里允许不同 key 覆盖 currentSong，就会把第二首点击回写成第一首，甚至让
        // ResolveTicket 在进入取链前失效。真正的跨歌提交仅由 onSongChanged 完成。
        val snapshotDecision = PlaybackSnapshotPolicy.decide(
            currentSongKey = currentSong?.key(),
            snapshotSongKey = snapshot.song?.key(),
            resolving = resolving,
            acceptedSnapshotGeneration = acceptedSnapshotGeneration,
            snapshotGeneration = snapshot.generation,
            controllerQueueIsNotEmpty = queue.isNotEmpty()
        )
        when (snapshotDecision.songAction) {
            SnapshotSongAction.NO_CHANGE -> Unit
            SnapshotSongAction.HYDRATE_EMPTY -> {
                val song = requireNotNull(snapshot.song)
                currentSong = song
                acceptedSnapshotGeneration = snapshot.generation
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "onSnapshot hydrated empty currentSong -> ${song.key()}")
            }
            SnapshotSongAction.ACCEPT_SERVICE_CHANGE -> {
                val song = requireNotNull(snapshot.song)
                currentSong = song
                liked = getUserBundle().isLiked(song)
                acceptedSnapshotGeneration = snapshot.generation
                scope.launch { refreshLyrics(song) }
                maybeBackfillAlbumCover(song)
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "onSnapshot accepted service song=${song.key()} gen=${snapshot.generation}")
            }
            SnapshotSongAction.IGNORE_SERVICE_CHANGE -> {
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "onSnapshot ignored stale song=${snapshot.song?.key()} target=${currentSong?.key()} resolving=$resolving")
            }
        }
        acceptedSnapshotGeneration = maxOf(acceptedSnapshotGeneration, snapshot.generation)
        playing = snapshot.isPlaying
        queueCurrentIndex = snapshot.queueIndex
        currentTime = snapshot.positionMs / 1000f
        duration = snapshot.durationMs / 1000f
        progress = if (snapshot.durationMs > 0) {
            snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()
        } else 0f
        playMode = snapshot.mode
        playbackAudioFormatState.value = snapshot.audioFormat.takeIf {
            snapshot.song?.key() == currentSong?.key()
        }
        val fallbackSong = snapshot.song ?: currentSong
        val nextQueue = if (snapshotDecision.preserveControllerQueue) {
            // 新事务已先写入控制器队列；Service 迟到的旧快照不得覆盖它。
            queue
        } else when {
            snapshot.queue.isNotEmpty() -> snapshot.queue
            fallbackSong != null -> listOf(fallbackSong)
            else -> emptyList()
        }
        // PlayerService 每 400ms 发布快照；队列未变时不要重写 Compose 状态，避免拖慢 basicMarquee。
        // r658c F：Service 只在 setQueue 时换新的队列实例，引用相同即跳过逐首拼 key 比较。
        if (queue !== nextQueue && (queue.size != nextQueue.size || queue.indices.any { queue[it].key() != nextQueue[it].key() })) {
            queue = nextQueue
        }
    }

    /** 队列唯一命令入口：页面不得直接调用 PlayerService.setQueue。 */
    /**
     * r658c 冷启动：恢复队列但未自动播放时，记住上次进度；用户第一次点播放从该进度起播（此前从 0 秒开始），
     * 并让进度条立即显示恢复的位置。仅对同一首歌生效，用过即清。
     */
    private var pendingRestore: Pair<String, Long>? = null

    fun replaceQueueForRestore(next: List<Song>, index: Int, song: Song, positionMs: Long) {
        replaceQueue(next, index)
        if (positionMs > 0L) {
            pendingRestore = song.key() to positionMs
            currentTime = positionMs / 1000f
        }
    }

    private fun takePendingRestoreMs(song: Song): Long {
        val p = pendingRestore ?: return 0L
        pendingRestore = null
        return if (p.first == song.key()) p.second else 0L
    }

    fun replaceQueue(next: List<Song>, index: Int = 0) {
        val normalized = next.toList()
        val safeIndex = index.coerceIn(0, (normalized.size - 1).coerceAtLeast(0))
        val pendingSongKey = currentSong?.key()
        val preservesPendingIntent = resolving && pendingSongKey != null &&
            normalized.getOrNull(safeIndex)?.key() == pendingSongKey
        var rebased = false
        if (preservesPendingIntent) {
            synchronized(playbackTransactionGate) {
                val oldRevision = queueRevision
                val newRevision = oldRevision + 1L
                rebased = playbackTransactionGate.rebaseCurrentQueueRevision(
                    pendingSongKey!!, oldRevision, newRevision
                )
                if (rebased) queueRevision = newRevision
            }
        }
        if (!rebased) {
            if (resolving) {
                // A queue edit that removes/replaces the pending selection cancels it explicitly;
                // do not leave the former selected song or spinner detached from the Service.
                cancelPendingPlayback()
                val playingSong = PlayerService.snapshot().song
                currentSong = playingSong
                liked = playingSong?.let { getUserBundle().isLiked(it) } ?: false
                lyrics = emptyList()
                lyricIdx = -1
                playingSong?.let { scope.launch { refreshLyrics(it) } }
            } else {
                synchronized(playbackTransactionGate) { queueRevision++ }
            }
        }
        // ACCEPT5：队列整表替换时按 key+出现次序对齐继承条目来源标记（重排/删行/追加/撤销恢复共用）。
        queueEntryFlags = QueueOriginSync.reconcileEntryFlags(queue.map { it.key() }, queueEntryFlags, normalized.map { it.key() })
        queue = normalized
        PlayerService.setQueue(normalized, safeIndex)
    }

    fun appendToQueue(song: Song) {
        val snap = PlayerService.snapshot()
        val current = snap.queue.ifEmpty { queue }
        if (current.any { it.key() == song.key() }) return
        replaceQueue(current + song, snap.queueIndex.coerceAtLeast(0))
    }

    fun insertNext(song: Song) {
        val snap = PlayerService.snapshot()
        val current = snap.queue.ifEmpty { queue }.toMutableList()
        val selectedKey = if (resolving) currentSong?.key() else snap.song?.key()
        if (resolving && selectedKey == song.key()) return
        current.removeAll { it.key() == song.key() }
        val selectedIndex = selectedKey?.let { key -> current.indexOfFirst { it.key() == key } }
            ?.takeIf { it >= 0 }
            ?: snap.queueIndex.coerceIn(0, (current.size - 1).coerceAtLeast(0))
        current.add((selectedIndex + 1).coerceAtMost(current.size), song)
        replaceQueue(current, selectedIndex)
        // ACCEPT5：手动插入的条目不是来源条目。reconcile 按出现次序会对同 key 的新插入误继承旧标记，必须显式清掉插入位。
        val insertedAt = (selectedIndex + 1).coerceAtMost(queue.lastIndex)
        if (insertedAt in queueEntryFlags.indices) {
            queueEntryFlags = queueEntryFlags.toMutableList().also { it[insertedAt] = false }
        }
    }

    /** Reorder against one service snapshot; replacing the queue invalidates any stale prepared-next player. */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        if (resolving) return
        val snap = PlayerService.snapshot()
        val items = snap.queue.ifEmpty { queue }.toList()
        val currentIndex = snap.queueIndex.takeIf { it in items.indices }
            ?: items.indexOfFirst { it.key() == snap.song?.key() }
        val edit = QueueEdits.move(items, currentIndex, fromIndex, toIndex) ?: return
        replaceQueue(edit.items, edit.currentIndex)
    }

    /** The active item is protected; removing another row preserves its exact queue position. */
    fun removeQueueAt(index: Int) {
        if (resolving) return
        val snap = PlayerService.snapshot()
        val items = snap.queue.ifEmpty { queue }.toList()
        val currentIndex = snap.queueIndex.takeIf { it in items.indices }
            ?: items.indexOfFirst { it.key() == snap.song?.key() }
        val edit = QueueEdits.remove(items, currentIndex, index, protectCurrent = true) ?: return
        replaceQueue(edit.items, edit.currentIndex)
    }

    /** Undo partner of [removeQueueAt]: restores a removed row at its previous position. */
    fun insertQueueAt(index: Int, song: Song) {
        if (resolving) return
        val snap = PlayerService.snapshot()
        val items = snap.queue.ifEmpty { queue }.toList()
        val currentIndex = snap.queueIndex.takeIf { it in items.indices }
            ?: items.indexOfFirst { it.key() == snap.song?.key() }
        val edit = QueueEdits.insert(items, currentIndex, index, song)
        replaceQueue(edit.items, edit.currentIndex)
    }

    fun removeFromQueue(songKey: String, keepCurrent: Boolean = true) {
        val snap = PlayerService.snapshot()
        val current = snap.queue.ifEmpty { queue }
        val pos = snap.queueIndex.coerceAtLeast(0)
        val next = current.filterNot { it.key() == songKey }
        if (next.isEmpty() && keepCurrent) {
            PlayerService.snapshot().song?.let { replaceQueue(listOf(it), 0) }
        } else if (next != current) {
            replaceQueue(next, pos.coerceAtMost((next.size - 1).coerceAtLeast(0)))
        }
    }

    fun clearQueueKeepCurrent() {
        PlayerService.snapshot().song?.let { replaceQueue(listOf(it), 0) } ?: replaceQueue(emptyList(), 0)
    }

    /** Service 仅通过快照回灌，避免 UI 自己读取可变队列形成第二事实源。 */
    fun liveQueueSnapshot(): List<Song> = queue

    /** Service callbacks must not mint a new transaction after dispatch becomes stale. */
    fun enqueueQueueAdvance(delta: Int) {
        val earlyCrossfade = PlayerService.earlyCrossfadeDispatchToken()
        val intent = PlayerService.currentPlaybackIntent()
        val revision = queueRevision
        val generation = PlayerService.currentGeneration()
        val songKey = currentSong?.key()
        scope.launch {
            if (!PlayerService.isPlaybackIntentCurrent(intent) || queueRevision != revision ||
                PlayerService.currentGeneration() != generation || currentSong?.key() != songKey ||
                !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(controllerOwner)) return@launch
            if (earlyCrossfade != null && !PlayerService.canCommitEarlyCrossfade(earlyCrossfade)) return@launch
            playDeltaRef[0](delta)
        }
    }

    /** Replay keeps the Service intent and UI selection captured before coroutine dispatch. */
    fun replay(intent: Int) {
        val song = currentSong ?: return
        val revision = queueRevision
        val generation = PlayerService.currentGeneration()
        fun stillCurrent(): Boolean =
            PlayerService.isPlaybackIntentCurrent(intent) && queueRevision == revision &&
                currentSong?.key() == song.key() && PlayerService.currentGeneration() == generation &&
                xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(controllerOwner)
        if (!stillCurrent()) return
        if (PlayerService.currentKey() == song.key() && PlayerService.hasPlayableMedia()) {
            PlayerService.resumeInternal(intent)
        } else {
            val list = queue.ifEmpty { listOf(song) }
            val index = PlayerService.snapshot().queueIndex.coerceAtLeast(0)
            val startMs = takePendingRestoreMs(song)
            scope.launch {
                if (!stillCurrent()) return@launch
                resolveAndPlay(song, list, index, startMs)
            }
        }
    }

    /** Explicit Service pause/stop event; never inferred from isPlaying=false. */
    fun cancelPendingPlayback() {
        synchronized(playbackTransactionGate) {
            playbackTransactionGate.cancel()
            queueRevision++
        }
        activeResolveJob?.cancel(CancellationException("playback paused or stopped"))
        activeResolveJob = null
        activeHeartJob?.cancel(CancellationException("playback paused or stopped"))
        activeHeartJob = null
        scriptRuntime.cancelPlayUrlRequests()
        resolving = false
        status = ""
    }

    /**
     * 播放故障只恢复仍属于当前歌曲和 Service 代次的事务。
     *
     * r657：按 [PlayerService.recoveryPolicy] 统一决策——
     *  已稳定播放后断流 → 原平台重新取链并从原进度续播；起播即失败或重取后仍失败 → 隔离脚本 + 智能换源；
     *  本曲预算用尽或曲尾断流 → 跳下一首；连续多首失败 → 停止并提示。
     * 旧实现永远 forceCross + 从 0 秒重播，换源失败后只 Toast 不推进，是“播着播着就不播了”的直接原因。
     */
    fun handlePlaybackFailure(failure: PlayerService.Companion.PlaybackFailure) {
        // A failure belongs to the accepted media, never to a pending selection (even the same key).
        if (resolving || PlayerService.isPlaybackInterrupted()) return
        val failed = playbackFailureRecovery.currentFailedSong(failure.songKey, failure.generation) ?: return
        val failureIntent = PlayerService.currentPlaybackIntent()
        if (!playbackFailureRecovery.claimFailure(failure.songKey, failure.generation, failureIntent)) return
        val failureRevision = queueRevision
        fun recoveryIsCurrent(): Boolean =
            !PlayerService.isPlaybackInterrupted() && queueRevision == failureRevision && !resolving &&
                xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(controllerOwner) &&
                playbackFailureRecovery.currentFailedSong(failure.songKey, failure.generation) != null
        val msg = failure.message
        val decision = PlayerService.recoveryPolicy.onPlaybackError(failed.key(), failure.positionMs, failure.durationMs)
        PlaybackDiagnostics.markFailure(failed, PlaybackDiagnostics.failureLayerOf(msg))
        android.util.Log.w("SWPlay", "handlePlaybackFailure: ${decision.action} attempt=${decision.attempt} resume=${decision.resumeAtMs}")
        // r658c D#1：本地文件解码失败不重新取链/换源（那会把本地歌送进脚本），直接按跳过处理。
        when (xyz.cncnlp.soundwave.playback.localRecoveryAction(LocalPlayback.isLocalSong(failed), decision.action)) {
            RecoveryAction.REFRESH_SAME_SOURCE -> {
                status = "音频连接中断，正在重新取链…"
                playbackFailureRecovery.afterCacheClear(failed, failure.songKey, failure.generation) {
                    if (!recoveryIsCurrent()) return@afterCacheClear
                    failed.resolvedUrl = ""
                    resolveAndPlay(
                        failed,
                        queue.ifEmpty { listOf(failed) },
                        PlayerService.snapshot().queueIndex.coerceAtLeast(0),
                        startMs = decision.resumeAtMs,
                        autoSkipOnFailure = true
                    )
                }
            }
            RecoveryAction.SWITCH_SOURCE -> {
                val blockedScript = UrlResolver.markWinningScriptBad(failed.key())
                status = UrlResolver.playbackFailureMessage(msg)
                playbackFailureRecovery.afterCacheClear(failed, failure.songKey, failure.generation) {
                    if (!recoveryIsCurrent()) return@afterCacheClear
                    SmartSourceStore.forget(failed.key())
                    failed.resolvedUrl = ""
                    resolveAndPlay(
                        failed,
                        queue.ifEmpty { listOf(failed) },
                        PlayerService.snapshot().queueIndex.coerceAtLeast(0),
                        startMs = decision.resumeAtMs,
                        skipScripts = blockedScript.takeIf { it.isNotBlank() }?.let(::setOf).orEmpty(),
                        forceCross = true,
                        autoSkipOnFailure = true
                    )
                }
            }
            RecoveryAction.SKIP_NEXT -> skipAfterFailure(failed, quiet = decision.nearEnd)
            RecoveryAction.STOP -> stopAfterRepeatedFailures(failureIntent)
        }
    }

    private fun skipAfterFailure(failed: Song, quiet: Boolean) {
        val revision = queueRevision
        val generation = PlayerService.currentGeneration()
        fun skipIsCurrent(): Boolean =
            queueRevision == revision && currentSong?.key() == failed.key() &&
                PlayerService.currentGeneration() == generation &&
                xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(controllerOwner)
        status = ""
        if (!quiet) {
            try { AppNotice.inbox(AppNotice.Category.PLAYBACK, "《${failed.name}》暂时无法播放", body = "已自动跳到下一首", alsoBanner = true) } catch (_: Exception) {}
        }
        if (quiet && PlayerService.snapshot().mode == PlayMode.LOOP_ONE) {
            // 单曲循环下曲尾断流等同播完：重新取链从头再播这一首。
            scope.launch {
                if (skipIsCurrent()) resolveAndPlay(failed, queue.ifEmpty { listOf(failed) }, PlayerService.snapshot().queueIndex.coerceAtLeast(0), autoSkipOnFailure = true)
            }
            return
        }
        scope.launch { if (skipIsCurrent()) safePlayDelta(+1) }
    }

    private fun stopAfterRepeatedFailures(failureIntent: Int) {
        status = ""
        resolving = false
        // F3：此前 STOP 只改 UI，Service 侧过渡唤醒锁要等 45s 超时；现在按捕获的意图暂停 + 释放（过期则不动）。
        PlayerService.finishFailedTransition(failureIntent)
        try { AppNotice.inbox(AppNotice.Category.PLAYBACK, "连续多首无法播放，已暂停", body = "请检查网络或音源设置", alsoBanner = true) } catch (_: Exception) {}
    }

    fun savePlaybackState() {
        val s = currentSong ?: return
        val checkpoint = PlayerService.snapshot()
        if (checkpoint.song?.key() != s.key()) return
        // ACCEPT5：控制器保存携带队列来源（权威）；Service/退出保存不携带时由 PlaybackStore 保留。
        PlaybackStore.save(
            context, checkpoint,
            queueOrigin = queueOrigin,
            queueOriginFlags = alignedEntryFlags(checkpoint.queue),
            queueOriginKnown = true
        )
    }

    /** r658c F：定期断点在后台单线程序列化；内容未变（暂停中、同曲同进度）直接跳过。 */
    @Volatile private var lastCheckpointMark: xyz.cncnlp.soundwave.perf.PlaybackCheckpointPolicy.Mark? = null
    suspend fun savePlaybackStateInBackground() {
        val s = currentSong ?: return
        val checkpoint = PlayerService.snapshot()
        val song = checkpoint.song ?: return
        if (song.key() != s.key()) return
        val mark = xyz.cncnlp.soundwave.perf.PlaybackCheckpointPolicy.Mark(
            song.key(), checkpoint.generation, System.identityHashCode(checkpoint.queue),
            checkpoint.queueIndex, checkpoint.isPlaying, checkpoint.positionMs
        )
        if (xyz.cncnlp.soundwave.perf.PlaybackCheckpointPolicy.shouldSkip(lastCheckpointMark, mark)) return
        val capturedAt = System.nanoTime()
        withContext(playbackCheckpointDispatcher) {
            PlaybackStore.save(
                context, checkpoint, capturedAt,
                queueOrigin = queueOrigin,
                queueOriginFlags = alignedEntryFlags(checkpoint.queue),
                queueOriginKnown = true
            )
        }
        lastCheckpointMark = mark
    }

    /** 专辑封面兜底回补（accept6-singercover）：当前歌自带封面为空、又带真实专辑 ID 时，
     * 异步按专辑取封面，回填当前歌与队列中的同 key 条目。与 refreshLyrics 同级的旁路，
     * 不阻塞解析与播放事务；只补专辑封面本身，不拿歌手头像冒充专辑图（批27 反冒充口径）。 */
    private fun maybeBackfillAlbumCover(song: Song) {
        if (AlbumCoverBackfill.targetAlbumId(song) == null) return
        scope.launch {
            val cover = AlbumCoverBackfill.coverFor(song) ?: return@launch
            val current = currentSong
            if (current != null && current.key() == song.key() && current.cover.isBlank()) {
                currentSong = current.copy(cover = cover)
            }
            if (queue.any { it.key() == song.key() && it.cover.isBlank() }) {
                queue = queue.map { if (it.key() == song.key() && it.cover.isBlank()) it.copy(cover = cover) else it }
            }
        }
    }

    suspend fun refreshLyrics(song: Song) {
        var lines = emptyList<LyricLine>()
        val prios = if (getLyricPriority() == "embedded") listOf("embedded", "external") else listOf("external", "embedded")
        for (tryPrio in prios) {
            val rawLrc = withContext(Dispatchers.IO) { LyricSourceLoader.load(context, song, tryPrio, controllerOwner) }
            lines = parseLyricTextBundle(rawLrc)
            if (lines.isNotEmpty()) break
        }
        if (lines.isEmpty()) {
            delay(350)
            val rawLrc = withContext(Dispatchers.IO) { LyricSourceLoader.load(context, song, getLyricPriority(), controllerOwner) }
            lines = parseLyricTextBundle(rawLrc)
        }
        if (currentSong?.key() == song.key()) {
            lyrics = lines
            lyricIdx = LyricParser.indexAt(lines, PlayerService.getPosition())
        }
    }

    suspend fun resolveAndPlay(
        song: Song,
        list: List<Song> = queue.ifEmpty { listOf(song) },
        index: Int = -1,
        startMs: Long = 0L,
        skipScripts: Set<String> = emptySet(),
        forceCross: Boolean = false,
        /** r657：自动推进/故障恢复时取链失败要跳下一首（受连续失败上限约束），而不是停在原地。 */
        autoSkipOnFailure: Boolean = false,
        /** ACCEPT5：新队列的来源歌单 id（调用方已知时显式传，如音乐库卡片）；null 时从 PlaySourceTracker 绑定推导。 */
        originPlaylistId: String? = null
    ): Unit = coroutineScope resolve@{
        val serviceIntent = PlayerService.beginPlaybackIntent()
        // SW-02-08：新播放意图取代上一批预取，立即撤销其池内任务。
        cancelPrefetchBatch()
        // 独立的结构化子 Job：替换解析只能取消本次请求，不能取消长期 collector 调用者。
        // 明确的点歌/重播是新的用户意图，之前的连续失败计数与它无关。
        if (!autoSkipOnFailure && startMs == 0L && !forceCross && skipScripts.isEmpty()) {
            PlayerService.recoveryPolicy.onUserIntent()
        }
        val thisResolveJob = currentCoroutineContext()[Job]
        activeResolveJob?.takeIf { it !== thisResolveJob }?.cancel(CancellationException("superseded by newer play intent"))
        activeResolveJob = thisResolveJob
        try {
            // Gate invalidates results; explicit cancellation also frees the old PLAY from
            // playResolveLane/evalMutex instead of leaving it queued until timeout.
            scriptRuntime.cancelPlayUrlRequests()
            PlaybackDiagnostics.markStart(song)
            PlaySourceTracker.noteQueue(list) // r657 第二轮：新队列记下来源页（历史「来自 X」）
            // ACCEPT5 队列来源同步：传入表与当前队列 key 序不同才算「新队列建立」，此时才换绑来源；
            // 自动推进/失败恢复/续播传的都是当前队列本身，同序不换绑（来源在队列存续期内稳定）。
            // 来源优先显式参数（音乐库卡片直传歌单 id），否则取 PlaySourceTracker 刚绑定的详情页身份——
            // 与播放器菜单「出处」同源；搜索/首页/历史等非歌单页绑定为 null，即无来源。
            val establishesNewQueue = list.size != queue.size ||
                list.indices.any { list[it].key() != queue[it].key() }
            if (establishesNewQueue) {
                queueOrigin = originPlaylistId?.let { QueueOrigin.fromPlaylistId(it) }
                    ?: PlaySourceTracker.originPlaylist()?.let { QueueOrigin.fromPlaylistId(it.id) }
                queueEntryFlags = List(list.size) { queueOrigin != null }
            }
            // 必须先进入“解析事务中”状态，再替换 Service 队列。否则旧 Service 快照会在
            // 新 currentSong 写入前回灌界面，将刚创建的 ResolveTicket 立即判作过期。
            resolving = true
            val serviceKey = PlayerService.snapshot().song?.key().orEmpty()
            val serviceGeneration = PlayerService.snapshot().generation
            val keepOldPlayback = serviceKey.isNotEmpty() && serviceKey != song.key()
            // 新歌在后台解析；旧曲保持播放，直到新媒体已 READY 后才原子切换。
            val playQueue = list.ifEmpty { listOf(song) }
            val idx = if (index >= 0) index.coerceIn(0, playQueue.lastIndex) else playQueue.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0)
            val (revision, transaction) = synchronized(playbackTransactionGate) {
                val nextRevision = ++queueRevision
                nextRevision to playbackTransactionGate.begin(song.key(), nextRevision, controllerOwner)
            }
            val ticket = ResolveTicket(
                songKey = song.key(),
                queueRevision = revision,
                serviceGeneration = PlayerService.snapshot().generation,
                purpose = ResolveTicket.Purpose.PLAY,
                ownerContext = transaction.ownerContext
            )
            fun requestIsCurrent(): Boolean = synchronized(playbackTransactionGate) {
                playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision) &&
                    PlayerService.currentGeneration() == serviceGeneration &&
                    PlayerService.isPlaybackIntentCurrent(serviceIntent)
            }
            // 被新歌替代的旧事务只允许静默退出；不得清新歌的 resolving，也不得把 STALE_REQUEST 显示给用户。
            fun finishIfCurrent() {
                if (synchronized(playbackTransactionGate) {
                        playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision)
                    }) {
                    resolving = false
                    status = ""
                }
            }
            var pendingCacheLease: MediaCacheLease? = null
            // hold 获取后立即进入 finally；本地命中/不可读、查找异常和 init 异常也配对释放。
            val naturalEndHoldToken = if (keepOldPlayback) PlayerService.holdNaturalEndForTransition() else ""
            try {
                // 只有正准备认领的同一首才保留双缓冲；任意其它队列替换都必须废弃旧预取。
                val keepPreparedNext = PlayerService.preparedNextKey() == song.key()
                queue = playQueue
                PlayerService.setQueue(playQueue, idx, keepPreparedNext = keepPreparedNext)
                currentSong = song
                liked = getUserBundle().isLiked(song)
                lyrics = emptyList()
                lyricIdx = -1
                scope.launch { refreshLyrics(song) }
                maybeBackfillAlbumCover(song)
                // 已下载歌曲只按同平台官方身份命中；本地可读即直接播放，绝不再联网取链或按歌名猜文件。
                // 本地歌曲也必须进入统一 LocalFirst：source=local 时优先验证 resolvedUrl，
                // 失效后再按下载记录精确匹配；绝不能因为 source 已是 local 就跳过本地分支。
                run {
                    val localHit = withContext(Dispatchers.IO) { LocalFirst.findLocalHit(song, context) }
                    if (localHit != null && requestIsCurrent()) {
                        val localFile = localHit.uri
                        // ACCEPT5（本地直读）：音质取下载文件的真实档位——实测
                        // （格式+字节+时长）优先于下载记录的存储标签，两者皆无
                        // 才回退「本地」描述；同时写入 Resolver 计划/尝试留存，
                        // 使路径弹层与文本导出的策略行宣布「本地文件」而非旧取链链。
                        val localQuality = withContext(Dispatchers.IO) { LocalPlayQuality.forHit(song, localHit) }
                        val s2 = song.copy(
                            resolvedUrl = localFile,
                            actualQuality = localQuality.ifBlank { song.actualQuality }
                        )
                        currentSong = s2
                        liked = getUserBundle().isLiked(s2)
                        PlayerService.setQueue(playQueue.map { if (it.key() == song.key()) s2 else it }, idx, keepPreparedNext = keepPreparedNext)
                        UrlResolver.noteLocalPlayback(
                            song.key(), song.source, localQuality,
                            downloaded = localHit.download != null, owner = controllerOwner
                        )
                        PlaybackDiagnostics.markResolved(song, 0L, fromCache = false, scriptId = "local", platform = "local", quality = localQuality.ifBlank { "本地" })
                        PlayerService.playUrl(context, localFile, s2, startMs, sourcePlatform = "local")
                        finishIfCurrent()
                        return@resolve
                    }
                }
                if (!requestIsCurrent()) return@resolve
                // source=local 且本地失效时不允许重新取链；下载记录损坏应明确失败。
                if (song.source == "local") {
                    status = ""
                    PlaybackDiagnostics.markFailure(song, "local_unavailable")
                    AppNotice.banner("本地歌曲文件不可读")
                    finishIfCurrent()
                    return@resolve
                }
                scriptRuntime.init()
                // 不直接认领预加载播放器：连续点歌必须走当前事务的全新取链，
                // 避免旧队列中的 prepared-next 状态阻断第二首点击。
                // URL 必须只由 Resolver 的身份化缓存返回；Song.resolvedUrl 没有候选平台/脚本/TTL 元数据，
                // 尤其导入歌单和跨平台结果会把旧链带到下一次播放，因此不再把它作为播放捷径。
                if (song.resolvedUrl.startsWith("http")) {
                    song.resolvedUrl = ""
                    song.actualQuality = ""
                }
                // 并行拉歌词
                scope.launch(Dispatchers.IO) {
                    val rawLrc = LyricSourceLoader.load(context, song, getLyricPriority(), controllerOwner)
                    val lines = parseLyricTextBundle(rawLrc)
                    withContext(Dispatchers.Main) {
                        if (currentSong?.key() == song.key()) lyrics = lines
                    }
                }
                status = "智能取链…"
                // ACCEPT6-FREEZE：shouldForceCross 会全量解析导入标记表 JSON，不得在
                // 调用方主线程序幕执行——与解析本体同入 IO 段，结果经返回值带出。
                // 进度文案经 ResolveProgressCoalescer 节流（最新优先）：慢解析快失败
                // 阶段每次尝试都写 Compose 状态会让消费方整树重组、主线程全程饱和。
                val progressCoalescer = xyz.cncnlp.soundwave.playback.ResolveProgressCoalescer()
                var force = forceCross
                val result = withContext(Dispatchers.IO) {
                    if (!force) force = PlaylistImportChecker.shouldForceCross(song.key())
                    UrlResolver.resolveResult(
                        ticket = ticket,
                        song = song,
                        scripts = getScripts(),
                        runtime = scriptRuntime,
                        preferQuality = SmartSourceStore.platformPlayQuality(song.source.ifEmpty { "wy" }, getPreferQ()),
                        skipScripts = skipScripts,
                        forceCross = force,
                        requestStillCurrent = ::requestIsCurrent,
                        onProgress = { label ->
                            if (thisResolveJob?.isActive == true && resolving && requestIsCurrent()) {
                                progressCoalescer.offer(label)?.let { status = it }
                            }
                        }
                    )
                }
                val pair = result.url to result.quality
                if (!requestIsCurrent()) return@resolve
                // STALE is terminal for this identity; never mint a new revision to revive it.
                if (result.failure == ResolveFailure.STALE_REQUEST) return@resolve
                if (!result.isSuccess) {
                    // Resolver 已在同一轮硬预算内完成跨脚本/跨平台阶段；这里不能再开启第二个完整 6.5 秒事务。
                    // 仅清理当前歌曲旧缓存，保留失败脚本短时冷却，避免同一坏链立即复用。
                    withContext(Dispatchers.IO) { UrlResolver.clearSongCache(song, controllerOwner) }
                    // Cache cleanup suspends; a newer intent may take over while the old failure waits.
                    if (!requestIsCurrent()) return@resolve
                    song.resolvedUrl = ""
                    song.actualQuality = ""
                }
                val raw = pair.first
                val q = pair.second
                if (raw.isEmpty()) {
                    status = ""
                    finishIfCurrent()
                    val reason = UrlResolver.failureMessage(result.failure).ifBlank { "没有可用音源" }
                    PlaybackDiagnostics.markFailure(song, PlaybackDiagnostics.failureLayerOf(reason).ifBlank { "script" })
                    val trace = UrlResolver.attemptsSummary(result.attempts)
                    val detail = PlaybackDiagnostics.explainFailure(PlaybackDiagnostics.failureLayerOf(reason))
                    val message = listOf(reason, trace, detail).filter { it.isNotBlank() }.joinToString("\n")
                    if (autoSkipOnFailure) {
                        // r657：自动推进时取链失败 → 跳下一首（连续失败达上限才停），否则“播完一首不会继续播放”。
                        val decision = PlayerService.recoveryPolicy.onResolveFailure(song.key())
                        if (resolveFailureDisposition(decision.action, playQueue.size) == ResolveFailureDisposition.ADVANCE) {
                            AppNotice.inbox(AppNotice.Category.PLAYBACK, "《${song.name}》已跳到下一首", body = reason, alsoBanner = true)
                            scope.launch { if (requestIsCurrent()) safePlayDelta(+1) }
                        } else {
                            AppNotice.inbox(AppNotice.Category.PLAYBACK, "《${song.name}》无法播放", body = message, alsoBanner = true)
                            stopAfterRepeatedFailures(serviceIntent)
                        }
                        return@resolve
                    }
                    AppNotice.inbox(AppNotice.Category.PLAYBACK, "《${song.name}》无法播放", body = message, alsoBanner = true)
                    return@resolve
                }
                // ACCEPT5（死链回写）：本轮未强制跨源而靠跨平台救活 = 原平台已死的实证，
                // 落 cross 标记供后续点播/预取直达；原平台成功则纠正/刷新已有标记。
                // ACCEPT6-FREEZE：notePlayOutcome 内部对标记表做全量解析+回写（至多两轮），
                // 此处已是主线程段，必须移入 IO，不得在出声前的提交路径上同步付全表价。
                withContext(Dispatchers.IO) {
                    PlaylistImportChecker.notePlayOutcome(
                        songKey = song.key(),
                        originSource = song.source.ifEmpty { "wy" },
                        servedPlatform = result.platform,
                        wasForceCross = force
                    )
                }
                if (!requestIsCurrent()) return@resolve
                val playUrl = ApiClient.playableUrl(raw)
                // 加密媒体（.mflac/.mgg/.ncm/.tkm）：取链返回的是加密文件，ExoPlayer 无法直接播放，
                // 先下载到缓存并解密成明文，再以明文本地路径交给播放器；song.source 保持原平台身份不变。
                val extOf = MediaDecryptCache.encryptedExtOf(raw)
                if (extOf != null) {
                    val mediaLease = withContext(Dispatchers.IO) {
                        // Capture before dispatcher handoff: prompt cancellation can discard the lease value.
                        MediaDecryptCache.prepareLeased(context, playUrl, result, extOf)
                            .also { pendingCacheLease = it }
                    }
                    if (mediaLease == null) {
                        // 下载/解密失败：按播放失败处理，不回落到播放加密 URL（ExoPlayer 播不了）。
                        status = ""
                        finishIfCurrent()
                        PlaybackDiagnostics.markFailure(song, "decrypt")
                        AppNotice.banner("加密媒体下载/解密失败")
                        if (autoSkipOnFailure &&
                            resolveFailureDisposition(PlayerService.recoveryPolicy.onResolveFailure(song.key()).action, playQueue.size) == ResolveFailureDisposition.ADVANCE) {
                            scope.launch { if (requestIsCurrent()) safePlayDelta(+1) }
                        } else {
                            // F3：不跳下一首即终止：按本事务意图暂停并释放过渡唤醒锁。
                            PlayerService.finishFailedTransition(serviceIntent)
                        }
                        return@resolve
                    }
                    pendingCacheLease = mediaLease
                    val plainPath = mediaLease.path
                    // 解密期间切歌/停止：同一事务身份与 Service 代次必须仍有效。
                    if (!requestIsCurrent()) return@resolve
                    song.resolvedUrl = plainPath
                    song.actualQuality = q
                    PlaybackDiagnostics.markResolved(song, result.elapsedMs, result.fromCache, result.scriptId, result.platform, "$q 解密")
                    if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(transaction.ownerContext)) return@resolve
                    PlayerService.playUrl(context, plainPath, song, startMs, mediaLease, result.platform)
                    pendingCacheLease = null
                    xyz.cncnlp.soundwave.playback.PlaybackMetadataWork.runIfCurrent(
                        { requestIsCurrent() },
                        {
                            val data = ContextData(context, transaction.ownerContext)
                            data.pushHistory(song)
                            data.history()
                        }
                    )?.let { if (requestIsCurrent()) onHistoryChanged(it) }
                    status = ""
                    return@resolve
                }
                song.resolvedUrl = playUrl
                song.actualQuality = q
                PlaybackDiagnostics.markResolved(song, result.elapsedMs, result.fromCache, result.scriptId, result.platform, q)
                // 手动点歌必须在取链成功后直接提交唯一真实播放器。双缓冲只服务自动预取；
                // 如果把明确点歌也送入 prepareNext，UI 会先显示新歌而 ExoPlayer 仍停在旧歌，
                // prepareNext 任意未 READY/身份失配时又没有 playUrl 回退，正是“封面是第二首、
                // 实际声音还是第一首、进度条也拖不动”的故障。
                if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(transaction.ownerContext)) return@resolve
                PlayerService.playUrl(context, playUrl, song, startMs, sourcePlatform = result.platform)
                xyz.cncnlp.soundwave.playback.PlaybackMetadataWork.runIfCurrent(
                    { requestIsCurrent() },
                    {
                        val data = ContextData(context, transaction.ownerContext)
                        data.pushHistory(song)
                        data.history()
                    }
                )?.let { if (requestIsCurrent()) onHistoryChanged(it) }
                status = ""
                // 预取只由 PlayerService.onSongChanged 统一触发；这里再次触发会让两个批次竞争 nextPlayer。
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!requestIsCurrent()) return@resolve
                status = ""
                // 顶层异常必须留痕：此前该路径“无 Toast / 无诊断 / 无日志”，
                // 与用户“点了没反应、错误日志也没有”完全匹配。
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "resolveAndPlay unexpected: ${song.key()} ${e.javaClass.simpleName}: ${e.message}", e) else android.util.Log.w("SWPlay", "resolveAndPlay unexpected: ${e.javaClass.simpleName}")
                PlaybackDiagnostics.markFailure(song, "other")
                if (autoSkipOnFailure) {
                    // 自动推进语境下顶层异常同样走统一 disposition：策略允许且有下一首才跳，
                    // 否则真正停住——此前该路径只发「已跳过」通知却不推进，文案与行为不符。
                    val decision = PlayerService.recoveryPolicy.onResolveFailure(song.key())
                    if (resolveFailureDisposition(decision.action, playQueue.size) == ResolveFailureDisposition.ADVANCE) {
                        try {
                            AppNotice.inbox(AppNotice.Category.PLAYBACK, "播放异常，已跳过", alsoBanner = true)
                        } catch (_: Exception) {}
                        scope.launch { if (requestIsCurrent()) safePlayDelta(+1) }
                    } else {
                        try {
                            AppNotice.inbox(AppNotice.Category.PLAYBACK, "播放异常", alsoBanner = true)
                        } catch (_: Exception) {}
                        stopAfterRepeatedFailures(serviceIntent)
                    }
                } else {
                    try {
                        AppNotice.inbox(AppNotice.Category.PLAYBACK, "播放异常，已跳过", alsoBanner = true)
                    } catch (_: Exception) {}
                }
            } finally {
                pendingCacheLease?.close()
                PlayerService.releaseNaturalEndHold(naturalEndHoldToken)
                finishIfCurrent()
            }
        } finally {
            if (activeResolveJob === thisResolveJob) activeResolveJob = null
        }
    }

    /** 播放切歌调用点顶层异常同样必须留痕（下一首/上一首/列表双击）。 */
    suspend fun safePlayDelta(delta: Int) {
        try {
            playDelta(delta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "playDelta unexpected: ${e.javaClass.simpleName}: ${e.message}", e) else android.util.Log.w("SWPlay", "playDelta unexpected: ${e.javaClass.simpleName}")
            try {
                AppNotice.inbox(AppNotice.Category.PLAYBACK, "切歌异常，请重试", alsoBanner = true)
            } catch (_: Exception) {}
        }
    }

    private suspend fun searchHeartSimilarServer(keyword: String, source: String): List<Song> {
        val registrySource = heartSimilarSearchRegistrySourceOrNull(source)
        return if (registrySource != null) {
            MusicSourceRegistry.platformSource(registrySource).searchServer(keyword)
        } else {
            ApiClient.search(keyword, source)
        }
    }

    suspend fun playHeartSimilar(): Unit = coroutineScope heart@ {
        val serviceIntent = PlayerService.beginPlaybackIntent()
        val playlistContext = activeHeartPlaylistContext
        val sessionRevision = heartSessionRevision
        val profile = playlistContext?.profile ?: HeartRecommendationPolicy.profile(getUserBundle().favs.toList())
        val taste = getTasteProfile()
        if (profile.seeds.isEmpty()) {
            AppNotice.toast(if (playlistContext != null) "当前歌单没有可推荐的歌曲" else "请先在「我的喜欢」收藏歌曲")
            return@heart
        }
        val item = currentSong ?: return@heart
        // 心动/相似意图按“按下即注册”取代旧意图，代次也在网络搜索前认领，搜索完成顺序不能决定胜负。
        val thisJob = currentCoroutineContext()[Job]
        activeHeartJob?.takeIf { it !== thisJob }?.cancel(CancellationException("superseded by newer intent"))
        activeHeartJob = thisJob
        try {
            scriptRuntime.init()
            val serviceGeneration = PlayerService.currentGeneration()
            val revision = ++queueRevision
            var transaction = playbackTransactionGate.begin(item.key(), revision, controllerOwner)
            fun searchIsCurrent(): Boolean =
                heartSessionRevision == sessionRevision &&
                playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision) &&
                    PlayerService.currentGeneration() == serviceGeneration &&
                    PlayerService.isPlaybackIntentCurrent(serviceIntent)
            val preferences = ContextPreferences.open(context, "soundwave", 0, controllerOwner)
            val seenPreferenceKey = playlistContext?.seenPreferenceKey ?: "heart_seen_v1"
            val seen = HeartRecommendationPolicy.decodeSeen(preferences.getString(seenPreferenceKey, "").orEmpty())
            // Only explicit playlist seeds (or legacy likes) teach preferences; playback queue excludes only.
            val excluded = PlayerService.snapshot().queue + item
            val preferredSource = playlistContext?.source?.takeIf { it.isNotBlank() }
                ?: item.source.ifEmpty { "wy" }
            val cachedCandidates = HeartRecommendationPolicy.candidates(profile, DislikeStore.filter(context, heartCandidates), excluded, seen, preferredSource)
            val cachedFresh = cachedCandidates.filter { HeartRecommendationPolicy.identity(it) !in seen }
            val similar = if (cachedFresh.isNotEmpty()) cachedFresh else withContext(Dispatchers.IO) {
                val plats = playlistContext?.searchSources
                    ?: (listOf(preferredSource) + listOf("wy", "kg")).distinct().take(2)
                // At most four concurrent, cancellable source queries; reuse the remaining pool on next tap.
                // Song has no trustworthy per-track genre: never invent a category from album artwork/name.
                // R 波一期：查询歌手改按统一画像亲和加权抽样（当前歌歌手作会话锚），不再无权重随机。
                val queryArtists = TasteProfileV2.heartQueryPlan(profile.queries, taste, item, Random)
                val acc = coroutineScope {
                    queryArtists.flatMap { singer -> plats.map { plat ->
                        async {
                            if (!searchIsCurrent()) emptyList() else try {
                                kotlinx.coroutines.withTimeoutOrNull(6000L) { searchHeartSimilarServer(singer, plat) }.orEmpty()
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                    } }.flatMap { it.await() }
                }
                HeartRecommendationPolicy.candidates(profile, DislikeStore.filter(context, acc + cachedCandidates), excluded, seen, preferredSource)
            }
            // Never rebind begin() after a late search unless the entry intent still owns the gate.
            if (!searchIsCurrent()) return@heart
            // R 波一期：资格过滤后的候选按统一画像打分重排，目标取最高分而非搜索返回的第一命中。
            val rankedSimilar = if (taste != null) similar.sortedByDescending { taste.songScore(it) } else similar
            val target = rankedSimilar.firstOrNull() ?: run {
                AppNotice.toast(if (playlistContext != null) "当前歌单暂无新歌，稍后再试" else "喜欢列表暂无新歌，稍后再试")
                return@heart
            }
            heartCandidates = rankedSimilar.filter { HeartRecommendationPolicy.identity(it) != HeartRecommendationPolicy.identity(target) }
            val oldQueue = PlayerService.snapshot().queue
            // 心动模式目标使用独立会话队列，不恢复旧 queuePos。目标此刻才确定，原子重绑：
            // 入口事务已失效（已有更新意图接管）时静默让位，绝不以 begin() 抢回前台。
            transaction = playbackTransactionGate.rebindIfCurrent(transaction, currentSong?.key(), queueRevision, target.key()) ?: return@heart
            fun heartRequestIsCurrent(): Boolean =
                heartSessionRevision == sessionRevision &&
                playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision) &&
                    PlayerService.isPlaybackIntentCurrent(serviceIntent)
            resolving = true
            currentSong = target
            liked = getUserBundle().isLiked(target)
            currentTime = 0f
            duration = 0f
            progress = 0f
            lyrics = emptyList()
            lyricIdx = -1
            refreshLyrics(target)
            val ticket = ResolveTicket(
                songKey = target.key(),
                queueRevision = revision,
                serviceGeneration = PlayerService.snapshot().generation,
                purpose = ResolveTicket.Purpose.PLAY,
                ownerContext = transaction.ownerContext
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    UrlResolver.resolveResult(
                        ticket = ticket,
                        song = target,
                        scripts = getScripts(),
                        runtime = scriptRuntime,
                        preferQuality = SmartSourceStore.platformPlayQuality(target.source.ifEmpty { item.source.ifEmpty { "wy" } }, getPreferQ()),
                        forceCross = PlaylistImportChecker.shouldForceCross(target.key()),
                        requestStillCurrent = ::heartRequestIsCurrent
                    )
                }
                val raw = result.url
                if (result.isSuccess && heartRequestIsCurrent()) {
                    // 提交前复核 Service 代次：已被更晚意图换代的结果静默丢弃，不得重建旧队列。
                    if (PlayerService.currentGeneration() != serviceGeneration) return@heart
                    val playUrl = ApiClient.playableUrl(raw)
                    target.resolvedUrl = playUrl
                    target.actualQuality = result.quality
                    // 心动歌必须先成为 Service 会话队列的当前项，再提交媒体；
                    // 不能播放后立刻恢复旧队列，否则 currentSong 与 queuePos 会错位。
                    val sessionQueue = (listOf(target) + oldQueue.filter {
                        HeartRecommendationPolicy.identity(it) != HeartRecommendationPolicy.identity(target)
                    }).take(24)
                    queue = sessionQueue
                    clearQueueOrigin(sessionQueue.size)
                    PlayerService.setQueue(sessionQueue, 0)
                    PlayerService.playUrl(context, playUrl, target, sourcePlatform = result.platform)
                    val nextSeen = HeartRecommendationPolicy.remember(seen, target)
                    heartExclude = nextSeen.toSet()
                    preferences.edit().putString(seenPreferenceKey, HeartRecommendationPolicy.encodeSeen(nextSeen)).apply()
                } else if (heartRequestIsCurrent()) {
                    // 取链失败/过期：播放器仍持有原曲，UI 必须回退到原曲，避免“看着相似歌、听着原曲”。
                    resolving = false
                    currentSong = item
                    liked = getUserBundle().isLiked(item)
                    lyrics = emptyList()
                    lyricIdx = -1
                    refreshLyrics(item)
                    status = ""
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (heartRequestIsCurrent()) {
                    resolving = false
                    currentSong = item
                    liked = getUserBundle().isLiked(item)
                    lyrics = emptyList()
                    lyricIdx = -1
                    refreshLyrics(item)
                    status = ""
                }
            } finally {
                if (heartRequestIsCurrent()) resolving = false
            }
        } finally {
            if (activeHeartJob === thisJob) activeHeartJob = null
        }
    }

    /** 播放器的相似歌曲也走 Gate，不能由搜索协程旁路抢回前台状态。 */
    suspend fun playSimilarSong(item: Song) {
        scriptRuntime.init()
        // 与心动意图同源并发处理：入口取代旧意图，并在网络搜索前认领事务。
        val thisJob = currentCoroutineContext()[Job]
        activeHeartJob?.takeIf { it !== thisJob }?.cancel(CancellationException("superseded by newer intent"))
        activeHeartJob = thisJob
        val serviceGeneration = PlayerService.currentGeneration()
        val revision = ++queueRevision
        var transaction = playbackTransactionGate.begin(item.key(), revision, controllerOwner)
        val target = try {
            withContext(Dispatchers.IO) {
                val query = listOf(item.singer, item.name.take(8)).filter { it.isNotBlank() }.joinToString(" ")
                val source = item.source.ifEmpty { "wy" }
                val candidates = if (source in MusicSourceRegistry.platformIds) {
                    MusicSourceRegistry.platformSource(source).searchServer(query)
                } else {
                    ApiClient.search(query, source)
                }
                // R 波一期：「相似歌曲」归位为真相似选歌——追加按歌手名的同歌手召回，
                // 与旧查询结果合并后过滤不喜欢/当前队列，按统一画像打分取最高分（不再取第一命中）。
                val artistCandidates = if (item.singer.isNotBlank()) {
                    try {
                        searchHeartSimilarServer(item.singer, source)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { emptyList() }
                } else emptyList()
                val queueKeys = PlayerService.snapshot().queue.mapTo(mutableSetOf()) { it.key() }
                TasteProfileV2.pickSimilar(item, DislikeStore.filter(context, candidates + artistCandidates), queueKeys, getTasteProfile())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: run {
            AppNotice.toast("暂无相似歌曲")
            return
        }
        val oldQueue = PlayerService.snapshot().queue
        val oldPos = PlayerService.snapshot().queueIndex
        // 入口已按本意图 revision 认领事务；目标确定后仅原子重绑到目标曲目：
        // 迟到搜索结果不得覆盖搜索期间出现的更新播放意图。重绑身份用入口事务自身的
        // item（歌曲菜单可对非当前播放歌曲调用，此时 currentSong 尚未切换，不能作身份键）。
        transaction = playbackTransactionGate.rebindIfCurrent(transaction, item.key(), queueRevision, target.key()) ?: return
        fun isCurrent() = playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision)
        resolving = true
        currentSong = target
        liked = getUserBundle().isLiked(target)
        currentTime = 0f
        duration = 0f
        progress = 0f
        lyrics = emptyList()
        lyricIdx = -1
        refreshLyrics(target)
        try {
            val result = withContext(Dispatchers.IO) {
                UrlResolver.resolveResult(
                    ticket = ResolveTicket(
                        songKey = target.key(),
                        queueRevision = revision,
                        serviceGeneration = PlayerService.snapshot().generation,
                        purpose = ResolveTicket.Purpose.PLAY
                    ),
                    song = target,
                    scripts = getScripts(),
                    runtime = scriptRuntime,
                    preferQuality = SmartSourceStore.platformPlayQuality(target.source.ifEmpty { item.source.ifEmpty { "wy" } }, getPreferQ()),
                    forceCross = PlaylistImportChecker.shouldForceCross(target.key()),
                    requestStillCurrent = ::isCurrent
                )
            }
            if (result.isSuccess && isCurrent()) {
                // 提交前复核 Service 代次：已被更晚意图换代的结果静默丢弃，不得重建旧队列。
                if (PlayerService.currentGeneration() != serviceGeneration) return
                val playUrl = ApiClient.playableUrl(result.url)
                target.resolvedUrl = playUrl
                target.actualQuality = result.quality
                val sessionQueue = if (oldQueue.isEmpty()) listOf(target)
                else oldQueue.map { if (it.key() == item.key()) target else it }
                queue = sessionQueue
                clearQueueOrigin(sessionQueue.size)
                PlayerService.setQueue(sessionQueue, sessionQueue.indexOfFirst { it.key() == target.key() }.coerceAtLeast(0))
                PlayerService.playUrl(context, playUrl, target, sourcePlatform = result.platform)
                AppNotice.toast("正在播放相似歌曲：${target.name}")
            } else if (isCurrent()) {
                currentSong = item
                liked = getUserBundle().isLiked(item)
                lyrics = emptyList()
                lyricIdx = -1
                refreshLyrics(item)
                AppNotice.toast("相似歌曲取链失败", AppNotice.Kind.FAILURE)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (isCurrent()) {
                currentSong = item
                liked = getUserBundle().isLiked(item)
                lyrics = emptyList()
                lyricIdx = -1
                refreshLyrics(item)
                AppNotice.toast("相似歌曲取链失败", AppNotice.Kind.FAILURE)
            }
        } finally {
            if (isCurrent()) resolving = false
        }
    }

    fun toggleHeartMode(pool: List<Song>) {
        when (heartModeToggleAction(PlayerService.snapshot().mode)) {
            HeartModeToggleAction.START -> startHeartMode(pool)
            HeartModeToggleAction.STOP -> stopHeartMode()
        }
    }
    fun stopHeartMode() {
        if (PlayerService.snapshot().mode != PlayMode.HEART) return
        PlayerService.beginPlaybackIntent()
        activeHeartJob?.cancel(CancellationException("heart mode disabled"))
        activeHeartJob = null
        heartSessionRevision++
        activeHeartPlaylistContext = null
        activeHeartPlaylistKeyState.value = null
        heartCandidates = emptyList()
        // 使搜索/取链中的旧相似事务失效；队列保留当前会话项，只结束自动推荐模式。
        playbackTransactionGate.cancel()
        queueRevision++
        resolving = false
        status = ""
        playMode = PlayMode.SEQUENCE
        PlayerService.setMode(PlayMode.SEQUENCE)
    }
    /** Playlist actions toggle only their own session; another playlist switches context. */
    fun togglePlaylistHeartMode(playlist: Playlist, songs: List<Song> = playlist.songs) {
        if (PlayerService.snapshot().mode == PlayMode.HEART &&
            activeHeartPlaylistKeyState.value == heartPlaylistKey(playlist)) {
            stopHeartMode()
            return
        }
        val playlistContext = HeartPlaylistContext.from(playlist, songs)
        if (playlistContext.profile.seeds.isEmpty()) {
            AppNotice.toast("当前歌单暂无歌曲，请先加载")
            return
        }
        if (playlistContext.searchSources.isEmpty()) {
            AppNotice.toast("该歌单来源暂不支持推荐")
            return
        }
        startHeartSession(playlistContext.profile.seeds, playlistContext)
    }

    @Suppress("UNUSED_PARAMETER") // Non-playlist screens retain their explicit favorites-only behavior.
    fun startHeartMode(pool0: List<Song>) {
        val pool = HeartRecommendationPolicy.profile(getUserBundle().favs.toList()).seeds
        if (pool.isEmpty()) {
            AppNotice.toast("请先在「我的喜欢」收藏歌曲")
            return
        }
        startHeartSession(pool, null)
    }

    private fun startHeartSession(pool: List<Song>, playlistContext: HeartPlaylistContext?) {
        activeHeartJob?.cancel(CancellationException("heart playlist changed"))
        activeHeartJob = null
        playbackTransactionGate.cancel()
        queueRevision++
        val sessionRevision = ++heartSessionRevision
        // Claim the new intent now, before the coroutine can be scheduled.
        val serviceIntent = PlayerService.beginPlaybackIntent()
        activeHeartPlaylistContext = playlistContext
        activeHeartPlaylistKeyState.value = playlistContext?.key
        playMode = PlayMode.HEART
        PlayerService.setMode(PlayMode.HEART)
        heartExclude = emptySet()
        heartCandidates = emptyList()
        val start = currentSong?.takeIf { candidate -> pool.any { it.key() == candidate.key() } } ?: pool.randomOrNull() ?: return
        val label = playlistContext?.name?.let { "心动模式 · $it" } ?: "心动模式"
        PlaySourceTracker.force(label, pool)
        scope.launch {
            if (sessionRevision != heartSessionRevision || !PlayerService.isPlaybackIntentCurrent(serviceIntent)) return@launch
            replaceQueue(pool, pool.indexOfFirst { it.key() == start.key() }.coerceAtLeast(0))
            // 心动是生成型会话队列而非歌单队列：清来源（resolveAndPlay 同表同序不会换绑，必须在这里清）。
            clearQueueOrigin(pool.size)
            resolveAndPlay(start, pool, pool.indexOfFirst { it.key() == start.key() }.coerceAtLeast(0))
        }
    }

    suspend fun playDelta(delta: Int) {
        // 正在投屏时，下一首/上一首只切换远端会话，不再打断本机播放。
        if (DlnaController.currentDevice() != null) {
            when (delta) {
                1 -> PlayerService.onNeedNext?.invoke()
                -1 -> PlayerService.onNeedPrev?.invoke()
            }
            return
        }
        if (PlayerService.snapshot().mode == PlayMode.HEART && delta > 0) {
            playHeartSimilar()
            return
        }
        val list = queue.ifEmpty { PlayerService.snapshot().queue }
        if (list.isEmpty()) return
        if (delta < 0 && PlayerService.getPosition() > 3000) {
            PlayerService.seekTo(0)
            return
        }
        // 当前用户播放已经开始新事务；任何在队列移动前启动的解析/预取全部立即失效。
        playbackTransactionGate.cancel()
        // 命中预备播放器时必须在索引前移前认领：playNextPrepared 要求 preparedKey == queue[queueIndex+1]，
        // 且认领成功后自己会把 queueIndex 重定位到该曲；先 moveQueue 只会让身份校验必然失败。
        val posBefore = PlayerService.snapshot().queueIndex
        val preparedKey = PlayerService.preparedNextKey()
        if (delta > 0 && preparedKey != null && preparedKey == queue.getOrNull(posBefore + 1)?.key()) {
            val revision = ++queueRevision
            playbackTransactionGate.begin(preparedKey, revision, controllerOwner)
            val claimed = queue.getOrNull(posBefore + 1) ?: return
            currentSong = claimed
            liked = getUserBundle().isLiked(claimed)
            resolving = true
            refreshLyrics(claimed)
            if (PlayerService.playNextPrepared()) {
                currentTime = 0f
                duration = 0f
                progress = 0f
                resolving = false
                prefetchRef[0]()
                return
            }
            resolving = false
        }
        val song = PlayerService.moveQueue(delta) ?: return
        // 先让 UI 归零，避免上一首的进度停留到新歌开始。
        currentTime = 0f
        duration = 0f
        progress = 0f
        lyrics = emptyList()
        lyricIdx = -1
        if (delta > 0 && PlayerService.preparedNextKey() == song.key()) {
            // 双缓冲提升也是一次前台切歌提交。先建立新事务，否则 resolving 中的
            // Service 回调会被 Gate 拒绝，历史/歌词/后续预取都会断掉。
            val revision = ++queueRevision
            playbackTransactionGate.begin(song.key(), revision, controllerOwner)
            currentSong = song
            liked = getUserBundle().isLiked(song)
            resolving = false
            refreshLyrics(song)
            if (PlayerService.playNextPrepared()) {
                prefetchRef[0]()
                return
            }
        }
        // 未命中双缓冲时同样后台取链，旧曲继续播到新媒体 READY。
        // r657：向后推进（自然播完/下一首/故障跳过）取链失败时继续跳，受 recoveryPolicy 连续失败上限保护。
        resolveAndPlay(song, list, PlayerService.snapshot().queueIndex, autoSkipOnFailure = delta > 0)
    }

    suspend fun prefetchNext() {
        val batchJob = currentCoroutineContext()[Job] ?: return
        val previous = synchronized(prefetchBatchLock) {
            if (activePrefetchBatchJob === batchJob) null
            else activePrefetchBatchJob.also { activePrefetchBatchJob = batchJob }
        }
        previous?.cancel(CancellationException("superseded playback prefetch batch"))
        try {
            prefetchNextBatch()
        } finally {
            synchronized(prefetchBatchLock) {
                if (activePrefetchBatchJob === batchJob) activePrefetchBatchJob = null
            }
        }
    }

    private suspend fun prefetchNextBatch() {
        if (!prefetchEnabled) return
        scriptRuntime.init()
        val list = if (PlayerService.snapshot().mode == PlayMode.HEART) heartCandidates else queue.ifEmpty { PlayerService.snapshot().queue }
        val sourceKey = currentSong?.key() ?: return
        val sourceGeneration = PlayerService.currentGeneration()
        val sourceRevision = queueRevision
        if (list.isEmpty() || PlayerService.snapshot().mode == PlayMode.LOOP_ONE) return
        val cur = if (PlayerService.snapshot().mode == PlayMode.HEART) -1 else PlayerService.snapshot().queueIndex
        // 未在播时立刻预取；在播时按间隔，避免抢当前取链带宽
        if (playing) {
            kotlinx.coroutines.delay((prefetchIntervalSec.coerceIn(1, 30) * 1000L))
        }
        val n = prefetchCount.coerceIn(1, 5)
        val indices = mutableListOf<Int>()
        when (PlayerService.snapshot().mode) {
            PlayMode.SHUFFLE, PlayMode.HEART -> {
                if (list.size <= 1) return
                val candidates = list.indices.filter { it != cur }.shuffled()
                indices += candidates.take(n)
            }
            else -> {
                for (i in 1..n) indices += (cur + i) % list.size
            }
        }
        val prepared = java.util.concurrent.atomic.AtomicBoolean(false)
        // 预取批次也绑定当前播放事务；切歌后旧任务即使晚返回，也不能写 Song 缓存或覆盖 pending next。
        fun prefetchStillCurrent(): Boolean =
            currentSong?.key() == sourceKey &&
                PlayerService.currentGeneration() == sourceGeneration &&
                queueRevision == sourceRevision
        // 并行取链 + 歌词，保证预解析数量内都拿到链接和歌词。
        // SW-02-08：每首歌的工作走共享预取池（PLAYBACK 优先、带保留名额），
        // 批次被新播放意图取代时整批撤销。
        coroutineScope {
            val batchScope = this
            indices.mapIndexedNotNull { step, idx ->
                SharedPrefetchWorkPool.submit(
                    batchScope,
                    BoundedPrefetchWorkPool.Priority.PLAYBACK,
                    diagnosticIdentity = list.getOrNull(idx)?.key(),
                    cancelReason = xyz.cncnlp.soundwave.diagnostics.ConcurrentWorkDiagnostics.CancelReason.REPLACED
                ) {
                    val next = list.getOrNull(idx) ?: return@submit
                    if (next.key() == currentSong?.key()) return@submit
                    // 与点播使用同一“同平台官方身份 + 可读文件”判定，预取和点播绝不能一边本地一边联网。
                    val localHit = try { LocalFirst.findLocalHit(next, context) } catch (_: Exception) { null }
                    if (localHit != null) {
                        val downloaded = localHit.uri
                        // ACCEPT5（本地直读）：预取命中的音质与记录口径与点播一致
                        //（实测 > 存储标签；计划/尝试留存写「本地文件」一行）。
                        val localQuality = try { LocalPlayQuality.forHit(next, localHit) } catch (_: Exception) { "" }
                        if (step == 0 && PlayerService.snapshot().mode != PlayMode.SHUFFLE && PlayerService.snapshot().mode != PlayMode.HEART &&
                            prepared.compareAndSet(false, true) && prefetchStillCurrent()) {
                            PlayerService.prepareNext(
                                context,
                                downloaded,
                                next.copy(resolvedUrl = downloaded, actualQuality = localQuality.ifBlank { next.actualQuality }),
                                sourceKey,
                                sourceGeneration,
                                requestStillCurrent = ::prefetchStillCurrent,
                                sourcePlatform = "local"
                            )
                        }
                        UrlResolver.noteLocalPlayback(
                            next.key(), next.source, localQuality,
                            downloaded = localHit.download != null, owner = controllerOwner
                        )
                        PlaybackDiagnostics.markResolved(next, 0L, fromCache = false, scriptId = "local", platform = "local", quality = localQuality.ifBlank { "本地" }, purpose = "预取")
                        if (!prefetchStillCurrent()) return@submit
                        try {
                            LyricSourceLoader.load(context, next, getLyricPriority(), controllerOwner)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {}
                        return@submit
                    }
                    try {
                        // 预取也只信 Resolver 身份缓存；裸 resolvedUrl 可能属于旧候选或过期跨源链。
                        val ticket = ResolveTicket(
                            songKey = next.key(),
                            queueRevision = queueRevision,
                            serviceGeneration = sourceGeneration,
                            purpose = ResolveTicket.Purpose.PREFETCH
                        )
                        val result = UrlResolver.resolveResult(
                            ticket = ticket,
                            song = next,
                            scripts = getScripts(),
                            runtime = scriptRuntime,
                            preferQuality = SmartSourceStore.platformPlayQuality(next.source.ifEmpty { "wy" }, getPreferQ()),
                            forceCross = PlaylistImportChecker.shouldForceCross(next.key()),
                            requestStillCurrent = ::prefetchStillCurrent
                        )
                        if (!prefetchStillCurrent()) return@submit
                        // 预取观测：resolve 完成即记录，作为“下一首准备了多久”的依据
                        PlaybackDiagnostics.markResolved(next, result.elapsedMs, result.fromCache, result.scriptId, result.platform, result.quality, purpose = "预取")
                        val nu = result.url
                        fun preparePrefetched(rawUrl: String, sourcePlatform: String) {
                            // SHUFFLE/HEART 的下一首由 moveQueue 运行时随机决定，无法预知；只对顺序模式做
                            // 唯一预备播放器的媒体 prepare，随机模式保持 URL/歌词预解析。
                            val audioEligible = PlayerService.snapshot().mode != PlayMode.SHUFFLE && PlayerService.snapshot().mode != PlayMode.HEART
                            if (step == 0 && audioEligible && prepared.compareAndSet(false, true) && prefetchStillCurrent()) {
                                // 顺序模式可确定下一首：在唯一的 Service 预备播放器中提前 prepare，
                                // 切歌时直接复用 READY 媒体；随机/心动仍只做 URL 预解析。
                                PlayerService.prepareNext(
                                    context = context,
                                    url = rawUrl,
                                    song = next,
                                    expectedCurrentKey = sourceKey,
                                    expectedGeneration = sourceGeneration,
                                    autoSwitchWhenReady = false,
                                    requestStillCurrent = ::prefetchStillCurrent,
                                    sourcePlatform = sourcePlatform
                                )
                            }
                        }
                        if (nu.isNotEmpty()) {
                            preparePrefetched(nu, result.platform)
                        } else {
                            // PRE-FETCH 可因播放抢占/超时而失败，不代表原平台失效；只有
                            // PlaylistImportChecker 的 originOnly 探测可以写入 cross 状态。
                            val retryResult = UrlResolver.resolveResult(
                                ticket = ticket.copy(requestId = java.util.UUID.randomUUID().toString()),
                                song = next,
                                scripts = getScripts(),
                                runtime = scriptRuntime,
                                preferQuality = SmartSourceStore.platformPlayQuality(next.source.ifEmpty { "wy" }, getPreferQ()),
                                forceCross = true,
                                requestStillCurrent = ::prefetchStillCurrent
                            )
                            val retry = retryResult.url
                            if (!prefetchStillCurrent()) return@submit
                            if (retry.isNotEmpty()) {
                                preparePrefetched(retry, retryResult.platform)
                            }
                        }
                        // 预加载歌词进磁盘缓存（LyricSourceLoader 自带缓存）
                        if (!prefetchStillCurrent()) return@submit
                        try {
                            LyricSourceLoader.load(context, next, getLyricPriority(), controllerOwner)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
            }.joinAll()
        }
    }
}
