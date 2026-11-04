package xyz.cncnlp.soundwave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.graphics.toArgb
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import xyz.cncnlp.soundwave.perf.NotificationRefreshPolicy
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import xyz.cncnlp.soundwave.cache.LeaseAwareCacheEvictor
import xyz.cncnlp.soundwave.cache.LeasedCacheDataSource
import xyz.cncnlp.soundwave.cache.Media3CacheReadLeaseRegistry
import xyz.cncnlp.soundwave.cache.clearMedia3CacheResources
import xyz.cncnlp.soundwave.cache.trimMedia3Cache
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.player.CoverToneCache

/**
 * ExoPlayer + MediaSession + 双缓冲 + 定时关闭 + 播放模式
 */
class PlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "music_playback_v12"
        const val NOTIFY_ID = 1001
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_NEXT = "next"
        const val ACTION_PREV = "prev"
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_LIKE = "like"
        const val ACTION_LYRIC_FLOAT = "lyric_float"

        @Volatile private var instance: PlayerService? = null
        internal suspend fun prepareListenStatsSnapshot(owner: xyz.cncnlp.soundwave.migration.ProfileContext) {
            if (instance == null) return
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val service = instance ?: return@withContext
                if (!owner.matches(serviceOwner) || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(owner)) {
                    throw ListenStatsSnapshotException(ListenStatsWriteQueue.Failure.STALE_OWNER)
                }
                player?.let { service.recordListenStats(SystemClock.elapsedRealtime(), currentSong, it.isPlaying, notifyLocalChange = false) }
                service.submitListenStats(null, service.listenStats.flushForSnapshot(), notifyLocalChange = false)
            }
        }

        private data class PendingPlay(
            val app: Context,
            val url: String,
            val song: Song?,
            val startMs: Long,
            val cacheLease: MediaCacheLease?,
            val sourcePlatform: String,
            val intent: Int
        )
        private val pendingPlayLock = Any()
        @Volatile private var pendingPlay: PendingPlay? = null

        /** 供悬浮歌词等外部模块在锁定/解锁后即时刷新通知栏「词」三态图标。 */
        fun refreshLyricNotif() {
            instance?.updateSessionAndNotify()
        }
        @Volatile private var player: ExoPlayer? = null
        @Volatile private var simpleCache: SimpleCache? = null
        @Volatile private var cacheEvictor: LeaseAwareCacheEvictor? = null
        @Volatile private var cacheLimitBytes: Long = 512L * 1024 * 1024
        @Volatile private var nextPlayer: ExoPlayer? = null
        private val mediaLeasesByPlayer = java.util.IdentityHashMap<ExoPlayer, MediaCacheLease>()
        /** Format callbacks are scoped to the exact player and unique MediaItem ID. */
        private val audioFormatsByPlayer = java.util.IdentityHashMap<ExoPlayer, PlaybackAudioFormat>()
        private val audioFormatListeners = java.util.IdentityHashMap<ExoPlayer, AnalyticsListener>()
        private val audioMediaIdsByPlayer = java.util.IdentityHashMap<ExoPlayer, String>()
        private val crossfadePlayers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ExoPlayer, Boolean>())
        private val fixedCrossfade = xyz.cncnlp.soundwave.playback.FixedCrossfade<ExoPlayer>(
            now = { SystemClock.elapsedRealtime() },
            schedule = { task, delay -> mainHandler.postDelayed(task, delay); Unit },
            unschedule = { mainHandler.removeCallbacks(it) },
            isCurrent = { it === player && instance != null && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner) },
            isPlaying = { it.isPlaying },
            volumeScale = { focusVolumeScale },
            setVolume = { target, volume -> try { target.volume = volume } catch (_: Exception) {} },
            release = { releaseTrackedPlayer(it) }
        )
        private fun configuredCrossfadeMs(): Long = instance?.let {
            xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore.read(
                it.transitionPreferences
            ) * 1_000L
        } ?: 0L
        private var earlyCrossfadeRequestedToken = ""
        private var earlyCrossfadeDispatch: String? = null
        fun earlyCrossfadeDispatchToken(): String? = earlyCrossfadeDispatch
        fun canCommitEarlyCrossfade(token: String): Boolean =
            token == activePlaybackToken && instance?.isFixedCrossfadeEligible(false) == true
        @Volatile private var currentSong: Song? = null
        @Volatile private var activeSourcePlatform: String = ""
        /** 通知栏封面异步加载必须与播放代次绑定，旧图不得覆盖新歌。 */
        @Volatile private var notificationCoverToken: String = ""
        /** 同一歌曲/代次的封面只允许一个加载任务；成功或短期失败都不在进度 tick 中重试。 */
        @Volatile private var notificationCoverLoadToken: String = ""
        /** 已加载的当前代次封面；进度刷新不得把它重置成 null。 */
        @Volatile private var notificationCoverBitmap: Bitmap? = null
        @Volatile private var isPlayingFlag = false
        @Volatile private var durationMs: Long = 0
        @Volatile private var positionMs: Long = 0
        /** 试听判断和错误都必须绑定具体 MediaItem，不能由旧播放器事件误伤新歌。 */
        @Volatile private var previewCandidateToken: String? = null
        @Volatile private var activePlaybackToken: String = ""
        private const val PREVIEW_DURATION_MIN_MS = 28_000L
        private const val PREVIEW_DURATION_LIMIT_MS = 35_000L
        /** r657：曲间过渡唤醒锁上限；正常取链 + 起播远小于该值，超时由系统自动释放。 */
        private const val TRANSITION_WAKE_MS = 45_000L
        /** r657：无界面时距曲尾多久开始预取下一首（太早预取，直链可能在用到前过期）。 */
        private const val HEADLESS_PREFETCH_LEAD_MS = 90_000L
        private val playGen = AtomicInteger(0)
        private val queue = CopyOnWriteArrayList<Song>()
        /**
         * r658c F：队列的不可变视图，仅在 [setQueue] 时重建。tick 每 400ms 发布快照，旧实现每次
         * queue.toList() 整列表复制（几千首歌单 = 每秒数千次分配），UI 侧再逐首拼 key 比较；
         * 复用同一实例后 UI 可按引用相等直接跳过。
         */
        @Volatile private var queueView: List<Song> = emptyList()
        private val serviceOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
        private val _playback = MutableStateFlow(PlaybackSnapshot(ownerContext = serviceOwner))
        /** Activity、通知/会话刷新与重进恢复都读取此流；服务才是唯一写入者。 */
        val playback: StateFlow<PlaybackSnapshot> = _playback.asStateFlow()
        private fun publishPlayback() {
            _playback.value = PlaybackSnapshot(
                song = currentSong,
                isPlaying = isPlayingFlag,
                positionMs = positionMs,
                durationMs = durationMs,
                generation = playGen.get(),
                queueIndex = queueIndex,
                queueSize = queue.size,
                queue = queueView,
                mode = playMode,
                hasPreparedNext = nextPlayer != null && pendingNextSong != null && pendingNextReady,
                audioFormat = player?.let { audioFormatsByPlayer[it] },
                sourcePlatform = activeSourcePlatform,
                ownerContext = serviceOwner
            )
        }
        @Volatile private var queueIndex = -1
        @Volatile private var pendingNextUrl: String? = null
        @Volatile private var pendingNextSong: Song? = null
        @Volatile private var pendingNextPlatform: String = ""
        @Volatile private var pendingNextReady: Boolean = false
        @Volatile private var playMode: PlayMode = PlayMode.SEQUENCE
        @Volatile private var sleepDeadlineElapsed: Long = 0L // SystemClock.elapsedRealtime
        @Volatile private var recoveryInFlight = false
        @Volatile private var recoveryExplicitResume = false
        @Volatile private var recoveryStatePrepared = false
        /** 恢复协程取链完成后必须确认没有新前台播放介入；否则旧恢复会覆盖新事务。 */
        @Volatile private var recoveryGen = 0
        @Volatile private var allowMixWithOthers: Boolean = false
        /** 每个媒体 token 的 STATE_ENDED 只允许消费一次，避免 Media3 边界重复事件并发切两次歌。 */
        @Volatile private var endedHandledToken: String = ""
        /** 新曲仍在解析时旧曲自然结束：该 token 只允许结束，不得再推进队列跳过用户点选的新曲。 */
        @Volatile private var naturalEndHoldToken: String = ""
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var deferredMediaCacheTrimTargetBytes: Long = cacheLimitBytes
        private val mediaCacheReadLeases = Media3CacheReadLeaseRegistry {
            mainHandler.post { trimMediaCacheTo(deferredMediaCacheTrimTargetBytes) }
        }

        var onSongChanged: ((Song?) -> Unit)? = null
        var onPlayState: ((Boolean) -> Unit)? = null
        var onNeedNext: (() -> Unit)? = null
        var onNeedPrev: (() -> Unit)? = null
        /** 已准备下一首也由 AppRoot 建立前台事务后再认领，不能绕开 Gate 直接提升。 */
        var onPreparedNext: (() -> Unit)? = null
        var onNeedReplay: ((Int) -> Unit)? = null
        /** Explicit pause/stop only; false playing snapshots also represent errors. */
        var onPlaybackInterrupted: (() -> Unit)? = null
        private val playbackIntent = AtomicInteger(0)
        @Volatile private var interruptedIntent = -1
        fun isPlaybackInterrupted(): Boolean = interruptedIntent == playbackIntent.get()
        fun currentPlaybackIntent(): Int = playbackIntent.get()
        fun beginPlaybackIntent(): Int = playbackIntent.incrementAndGet().also { intent ->
            instance?.cancelPlaybackRecovery(intent)
        }
        fun isPlaybackIntentCurrent(token: Int): Boolean = playbackIntent.get() == token

        /** F3：前台失败终止收尾（见 [xyz.cncnlp.soundwave.playback.FailedTransitionFinisher]）。 */
        private val failedTransitionFinisher = xyz.cncnlp.soundwave.playback.FailedTransitionFinisher(
            isIntentCurrent = { isPlaybackIntentCurrent(it) && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner) },
            pausePlayer = { fixedCrossfade.cancel(); player?.pause() },
            releaseTransitionWake = { instance?.releaseTransitionWake() },
            publishStopped = {
                isPlayingFlag = false
                onPlayState?.invoke(false)
                instance?.updateSessionAndNotify()
            },
            runOnPlayerThread = { block -> if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block) }
        )

        fun finishFailedTransition(expectedIntent: Int): Boolean = failedTransitionFinisher.finish(expectedIntent)

        private fun interruptPlayback() {
            val intent = beginPlaybackIntent()
            interruptedIntent = intent
            recoveryExplicitResume = false
            val cancel: () -> Unit = cancel@{
                if (!isPlaybackIntentCurrent(intent)) return@cancel
                onPlaybackInterrupted?.invoke()
                instance?.let { PlaybackStore.setExplicitResume(it, false, serviceOwner) }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cancel() else mainHandler.post(cancel)
        }
        data class PlaybackFailure(
            val songKey: String,
            val generation: Int,
            val message: String,
            /** r657：出错时的播放进度/总长，用于“从原进度续播”与“曲尾断流视为播完”。 */
            val positionMs: Long = 0L,
            val durationMs: Long = 0L
        )

        /** r657：前台 Controller 与无界面 Service 共用的故障恢复决策（重新取链→换源→跳过→连续失败才停）。 */
        val recoveryPolicy = xyz.cncnlp.soundwave.playback.PlaybackRecoveryPolicy()
        /** 每个播放 token 只上报一次“稳定播放”、只做一次无界面预取。 */
        @Volatile private var healthyMarkedToken: String = ""
        @Volatile private var headlessPrefetchToken: String = ""

        var onError: ((PlaybackFailure) -> Unit)? = null
        var onLikeToggle: (() -> Unit)? = null
        /** 通知栏「词」：切换悬浮歌词开关。 */
        var onLyricFloatToggle: (() -> Unit)? = null
        var onSleepTick: ((Long) -> Unit)? = null // remain ms
        var onModeChanged: ((PlayMode) -> Unit)? = null

        fun snapshot(): PlaybackSnapshot = playback.value

        fun getPosition(): Long = try { player?.currentPosition ?: positionMs } catch (_: Exception) { positionMs }
        fun getDuration(): Long {
            return try {
                val d = player?.duration ?: durationMs
                if (d < 0) durationMs else d
            } catch (_: Exception) { durationMs }
        }
        /**
         * r658c F：toggle 的“用户意图”判定。ExoPlayer.isPlaying 在缓冲（弱网卡顿）时为 false，
         * 旧 toggle 此时会走 resume()→replay，点“暂停”反而再次 play()，用户看到的是点了没反应/卡住。
         * 以 playWhenReady（且未 IDLE/ENDED）为准：缓冲中点按钮即暂停。
         */
        fun isPlayIntentActive(): Boolean = try {
            player?.let {
                it.playWhenReady && it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED
            } ?: isPlayingFlag
        } catch (_: Exception) { false }

        /** 已在主线程（UI 点击 / 通知动作）就地执行，省一次排队；其它线程照旧投递。 */
        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
        }

        fun isPlayingNow(): Boolean = try {
            // 播放器存在时，必须以 ExoPlayer 实际状态为准；不能用旧 isPlayingFlag
            // 把已经停住的播放器误判为播放中，导致 APP 内 toggle() 再次调用 pause()。
            player?.let { it.isPlaying } ?: isPlayingFlag
        } catch (_: Exception) { false }
        fun currentKey(): String = currentSong?.key().orEmpty()
        /** 在旧曲继续播、新曲后台解析期间冻结“自然结束自动下一首”；返回的 token 只可由同一事务释放。 */
        fun holdNaturalEndForTransition(): String {
            val token = activePlaybackToken
            naturalEndHoldToken = token
            return token
        }
        fun releaseNaturalEndHold(token: String) {
            if (token.isNotBlank() && naturalEndHoldToken == token) naturalEndHoldToken = ""
        }
        fun hasPlayableMedia(): Boolean {
            val p = player ?: return false
            return try {
                p.currentMediaItem != null &&
                    p.playbackState != Player.STATE_IDLE &&
                    p.playbackState != Player.STATE_ENDED
            } catch (_: Exception) { false }
        }
        fun sleepRemainMs(): Long {
            if (sleepDeadlineElapsed <= 0) return 0
            return (sleepDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }

        fun setMode(m: PlayMode) {
            playMode = m
            publishPlayback()
            onModeChanged?.invoke(m)
        }

        fun cycleMode(): PlayMode {
            playMode = nextPlayMode(playMode)
            publishPlayback()
            onModeChanged?.invoke(playMode)
            return playMode
        }

        /** minutes<=0 取消 */
        fun setAllowMix(mix: Boolean) {
            // 兼容旧入口：仅映射为 MIX / 默认 DUCK；新设置走 setAudioFocusSettings。
            setAudioFocusSettings(audioFocusSettings.copy(mode = if (mix) xyz.cncnlp.soundwave.playback.AudioFocusMode.MIX else xyz.cncnlp.soundwave.playback.AudioFocusMode.DUCK))
        }
        fun allowMix(): Boolean = allowMixWithOthers

        /** r656：音频焦点策略（自动暂停/降低音量/同时播放）与通话打断后是否继续，真实作用于焦点申请与回调。 */
        @Volatile private var audioFocusSettings = xyz.cncnlp.soundwave.playback.AudioFocusSettings()
        /** 焦点“降低音量”时的音量系数；换歌/淡入淡出都乘以它，避免把压低的音量冲回满音量。 */
        @Volatile private var focusVolumeScale: Float = 1f
        private var audioFocusController: xyz.cncnlp.soundwave.playback.PlaybackAudioFocusController? = null
        fun setAudioFocusSettings(settings: xyz.cncnlp.soundwave.playback.AudioFocusSettings) {
            audioFocusSettings = settings
            allowMixWithOthers = settings.mode == xyz.cncnlp.soundwave.playback.AudioFocusMode.MIX
            mainHandler.post { audioFocusController?.update(settings) }
        }
        fun audioFocusSettings(): xyz.cncnlp.soundwave.playback.AudioFocusSettings = audioFocusSettings
        private fun focusController(app: Context): xyz.cncnlp.soundwave.playback.PlaybackAudioFocusController =
            audioFocusController ?: xyz.cncnlp.soundwave.playback.PlaybackAudioFocusController(
                app,
                isPlaying = { isPlayingNow() },
                pause = { pauseInternal() },
                resume = { resumeInternal() },
                setVolumeScale = { scale ->
                    focusVolumeScale = scale
                    if (fixedCrossfade.active) fixedCrossfade.refreshVolume()
                    else try { player?.volume = scale } catch (_: Exception) {}
                }
            ).also { it.update(audioFocusSettings); audioFocusController = it }

        /** 洛雪 timeoutExitPlayed：到点后等当前曲播完再停，而不是立刻暂停。 */
        @Volatile private var sleepStopAfterCurrent: Boolean = false
        fun setSleepMinutes(minutes: Int) {
            sleepStopAfterCurrent = false
            sleepDeadlineElapsed = if (minutes <= 0) 0L
            else SystemClock.elapsedRealtime() + minutes * 60_000L
            instance?.scheduleSleepCheck()
            onSleepTick?.invoke(sleepRemainMs())
        }
        /** minutes==-1 表示「播完当前」；其它正数等同 setSleepMinutes。 */
        fun setSleepMode(minutes: Int) {
            if (minutes == -1) {
                sleepDeadlineElapsed = 0L
                sleepStopAfterCurrent = true
                instance?.scheduleSleepCheck()
                // 用一个很大的 remain 提示 UI「播完当前」模式仍在生效；真正归零在曲终。
                onSleepTick?.invoke(Long.MAX_VALUE / 4)
            } else {
                setSleepMinutes(minutes)
            }
        }
        fun isSleepAfterCurrent(): Boolean = sleepStopAfterCurrent

        /**
         * 替换队列默认使双缓冲失效。prepared next 的身份不仅是歌曲 key，还隐含了
         * 当时的队列顺序；例如“设为下一首/清空队列”后继续提升旧播放器会播错队列。
         * 只有 AppRoot 正在认领同一首 prepared next 时才可显式保留。
         */
        fun setQueue(songs: List<Song>, startIndex: Int, keepPreparedNext: Boolean = false) {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return
            if (!keepPreparedNext) discardPreparedNext()
            queue.clear()
            queue.addAll(songs)
            queueView = java.util.Collections.unmodifiableList(ArrayList(songs))
            queueIndex = startIndex.coerceIn(-1, (queue.size - 1).coerceAtLeast(-1))
            publishPlayback()
        }

        private fun releaseMediaLease(p: ExoPlayer?) {
            if (p != null) mediaLeasesByPlayer.remove(p)?.close()
        }

        private fun detachAudioFormatListener(p: ExoPlayer) {
            audioFormatListeners.remove(p)?.let { listener ->
                try { p.removeAnalyticsListener(listener) } catch (_: Exception) {}
            }
        }

        private fun clearAudioFormatTracking(p: ExoPlayer) {
            detachAudioFormatListener(p)
            audioFormatsByPlayer.remove(p)
            audioMediaIdsByPlayer.remove(p)
            if (p === player) publishPlayback()
        }

        private fun releaseTrackedPlayer(p: ExoPlayer?) {
            if (p == null) return
            clearAudioFormatTracking(p)
            try {
                p.release()
            } catch (_: Exception) {
            } finally {
                // SW-02-06：release() 抛错时租约也要释放，否则媒体文件被永久钉住。
                releaseMediaLease(p)
                crossfadePlayers.remove(p)
            }
        }

        /** A lease is released only after ExoPlayer confirms the media item was detached. */
        private fun clearPlayerMedia(p: ExoPlayer?) {
            if (p == null) return
            clearAudioFormatTracking(p)
            p.stop()
            p.clearMediaItems()
            releaseMediaLease(p)
        }

        /** Takes ownership of [lease], retaining it if setMediaItem leaves a media item attached. */
        private fun setPlayerMedia(p: ExoPlayer, url: String, lease: MediaCacheLease?) {
            clearPlayerMedia(p)
            if (lease != null) mediaLeasesByPlayer[p] = lease
            val mediaId = java.util.UUID.randomUUID().toString()
            val mediaItem = MediaItem.Builder().setUri(url).setMediaId(mediaId).build()
            audioMediaIdsByPlayer[p] = mediaId
            val listener = object : AnalyticsListener {
                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                ) {
                    if (audioFormatListeners[p] !== this || audioMediaIdsByPlayer[p] != mediaId ||
                        p.currentMediaItem?.mediaId != mediaId
                    ) return
                    val eventMediaId = runCatching {
                        if (eventTime.windowIndex !in 0 until eventTime.timeline.windowCount) return@runCatching null
                        eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
                    }.getOrNull()
                    if (eventMediaId != mediaId) return
                    val observed = PlaybackAudioFormat.fromMedia3(
                        sampleMimeType = format.sampleMimeType,
                        codecs = format.codecs,
                        sampleRateHz = format.sampleRate,
                        channelCount = format.channelCount
                    )
                    if (observed == null) audioFormatsByPlayer.remove(p) else audioFormatsByPlayer[p] = observed
                    if (p === player) publishPlayback()
                }
            }
            audioFormatListeners[p] = listener
            p.addAnalyticsListener(listener)
            try {
                p.setMediaItem(mediaItem)
            } catch (e: Exception) {
                clearAudioFormatTracking(p)
                if (p.currentMediaItem == null) releaseMediaLease(p)
                throw e
            }
        }

        private fun closeLeaseIfUnowned(lease: MediaCacheLease?) {
            if (lease != null && mediaLeasesByPlayer.values.none { it === lease }) lease.close()
        }

        private fun discardPreparedNext() {
            val stale = nextPlayer
            nextPlayer = null
            pendingNextUrl = null
            pendingNextSong = null
            pendingNextPlatform = ""
            pendingNextReady = false
            if (stale != null) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    releaseTrackedPlayer(stale)
                } else {
                    mainHandler.post { releaseTrackedPlayer(stale) }
                }
            }
        }

        fun moveQueue(delta: Int): Song? {
            if (queue.isEmpty()) return null
            when (playMode) {
                PlayMode.SHUFFLE, PlayMode.HEART -> {
                    if (queue.size == 1) {
                        queueIndex = 0
                    } else {
                        // 心动/随机：优先跳到非当前索引；心动跳过 dislike，并略偏向后半段
                        val app = instance?.applicationContext
                        val candidates = queue.indices.filter { idx ->
                            idx != queueIndex && (app == null || !xyz.cncnlp.soundwave.ContextData(app, serviceOwner).disliked(queue[idx]))
                        }.ifEmpty {
                            queue.indices.filter { it != queueIndex }
                        }
                        queueIndex = if (playMode == PlayMode.HEART && candidates.size > 2) {
                            val mid = candidates.size / 3
                            candidates.drop(mid).random()
                        } else {
                            candidates.random()
                        }
                    }
                }
                PlayMode.LOOP_ONE -> {
                    if (delta == 0) return queue.getOrNull(queueIndex)
                    queueIndex = (queueIndex + delta).let { idx ->
                        when {
                            idx < 0 -> queue.lastIndex
                            idx > queue.lastIndex -> 0
                            else -> idx
                        }
                    }
                }
                PlayMode.SEQUENCE -> {
                    queueIndex = (queueIndex + delta).let { idx ->
                        when {
                            idx < 0 -> queue.lastIndex
                            idx > queue.lastIndex -> 0
                            else -> idx
                        }
                    }
                }
            }
            publishPlayback()
            return queue.getOrNull(queueIndex)
        }

        fun cancelForTransition() {
            mainHandler.post {
                // 用户明确切到另一首时先终止旧媒体。宁可短暂等待取链，也不能让 UI 已是下一首却继续播上一首。
                fixedCrossfade.cancel()
                playGen.incrementAndGet()
                recoveryGen = playGen.get()
                notificationCoverToken = ""
                notificationCoverBitmap = null
                activePlaybackToken = ""
                previewCandidateToken = null
                activeSourcePlatform = ""
                try { clearPlayerMedia(player) } catch (_: Exception) {}
                releaseTrackedPlayer(nextPlayer)
                nextPlayer = null
                pendingNextUrl = null
                pendingNextSong = null
                pendingNextPlatform = ""
                pendingNextReady = false
                currentSong = null
                positionMs = 0L
                durationMs = 0L
                isPlayingFlag = false
                onPlayState?.invoke(false)
                instance?.updateSessionAndNotify()
            }
        }

        fun playUrl(
            context: Context,
            url: String,
            song: Song?,
            startMs: Long = 0L,
            cacheLease: MediaCacheLease? = null,
            sourcePlatform: String = song?.source.orEmpty(),
            owner: xyz.cncnlp.soundwave.migration.ProfileContext = serviceOwner,
            intent: Int = playbackIntent.get()
        ) {
            val app = context.applicationContext
            // SW-02-06（adapt）：本地媒体在交给播放器前先取得租约，删除/清理不得 unlink
            // 正在播放的资源；调用方已带租约时沿用。加密容器 URL 绝不直达播放器（须先经
            // MediaDecryptCache 解密）——硬拒。租约取不到（文件已消失等）时不硬拒：
            // 沿用既有行为让播放器走错误恢复链（与 r718 的差异，台账 M1-SW02 记录）。
            val ownedLease = cacheLease ?: MediaCacheLeaseManager.acquireForPlayback(url)
            if (isPlaybackInterrupted() || !isPlaybackIntentCurrent(intent) || !owner.matches(serviceOwner) || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(owner)) {
                ownedLease?.close()
                return
            }
            if (MediaDecryptCache.encryptedExtOf(url) != null) {
                ownedLease?.close()
                onError?.invoke(PlaybackFailure(song?.key().orEmpty(), playGen.get(), "media lease or decrypt preparation required"))
                return
            }
            // Service 尚未建立时禁止“裸播放器”先 prepare/play。否则首播会绕过 Service 的
            // listener、AudioFocus 和前台生命周期，形成必须先播放其它 App 才出声的状态。
            if (instance == null) {
                val pending = PendingPlay(app, url, song, startMs, ownedLease, sourcePlatform, intent)
                var replacedPending: PendingPlay? = null
                val queued = synchronized(pendingPlayLock) {
                    if (instance == null) {
                        replacedPending = pendingPlay
                        pendingPlay = pending
                        true
                    } else false
                }
                if (queued) {
                    replacedPending?.cacheLease?.close()
                    try {
                        val i = Intent(app, PlayerService::class.java)
                        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                    } catch (e: Exception) {
                        val failed = synchronized(pendingPlayLock) {
                            if (pendingPlay === pending) pendingPlay.also { pendingPlay = null } else null
                        }
                        failed?.cacheLease?.close()
                        onError?.invoke(PlaybackFailure(song?.key().orEmpty(), playGen.get(), e.message ?: "service start failed"))
                    }
                    return
                }
            }
            try {
                ensurePlayer(app)
            } catch (e: Exception) {
                ownedLease?.close()
                throw e
            }
            val commit: () -> Unit = commit@{
                if (!isPlaybackIntentCurrent(intent)) { ownedLease?.close(); return@commit }
                val p = player ?: run { ownedLease?.close(); return@commit }
                fixedCrossfade.cancel()
                val generation = playGen.incrementAndGet()
                recoveryGen = playGen.get()
                val token = "${song?.key().orEmpty()}#$generation"
                activePlaybackToken = token
                endedHandledToken = ""
                notificationCoverToken = token
                notificationCoverBitmap = null
                previewCandidateToken = if (MusicSourceRegistry.capabilitiesOf(song?.source.orEmpty()).previewClipRisk) token else null
                releaseTrackedPlayer(nextPlayer)
                nextPlayer = null
                pendingNextUrl = null
                pendingNextSong = null
                pendingNextPlatform = ""
                pendingNextReady = false
                positionMs = 0L
                durationMs = 0L
                // token 已记录在 activePlaybackToken；后续监听只处理仍匹配当前代次的事件。
                currentSong = song
                activeSourcePlatform = sourcePlatform.ifBlank { song?.source.orEmpty() }
                if (song != null) {
                    val i = queue.indexOfFirst { it.key() == song.key() }
                    if (i >= 0) queueIndex = i
                }
                onSongChanged?.invoke(song)
                try {
                    instance?.requestAudioFocus()
                    // 同一 Exo 实例换歌后必须重新绑定 listener token；旧异步事件不能归因给新歌。
                    instance?.attachListener(p)
                    // 预取淡出或上一次播放可能把这个实例音量留在 0；主播放必须恢复满音量。
                    p.volume = focusVolumeScale
                    setPlayerMedia(p, url, ownedLease)
                    PlaybackDiagnostics.markPrepareStart(song?.key().orEmpty())
                    // 先置 playWhenReady 再 prepare；READY 回调中再次确认，避免只靠一次 play()
                    // 进入“每次点播放只推进一小段”的假播放状态。
                    p.playWhenReady = true
                    p.prepare()
                    if (startMs > 0) p.seekTo(startMs)
                    // 换歌后重绑速度 / 均衡器（session 变化后 EQ 必须重挂）
                    try { p.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed, 1f) } catch (_: Exception) {}
                    if (eqPreset != 0) setEqualizerPreset(eqPreset)
                    p.playWhenReady = true
                    p.play()
                    isPlayingFlag = true
                    onPlayState?.invoke(true)
                    instance?.updateSessionAndNotify()
                } catch (e: Exception) {
                    closeLeaseIfUnowned(ownedLease)
                    isPlayingFlag = false
                    val failure = PlaybackFailure(song?.key().orEmpty(), generation, e.message ?: "play fail")
                    onError?.invoke(failure)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) commit()
            else if (!mainHandler.post(commit)) ownedLease?.close()
        }

        fun playNextPrepared(): Boolean {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return false
            // 非主线程调用只投递一次；真正的 claim 仍在主线程同步完成。
            if (Looper.myLooper() != Looper.getMainLooper()) {
                android.util.Log.w("SWPlay", "playNextPrepared called off-main; async claim posted")
                mainHandler.post { playNextPrepared() }
                return true
            }
            // 只能在主线程同步认领 pending；先清空再切换，保证同一预取播放器永远不会被提升两次。
            val np = nextPlayer ?: run {
                android.util.Log.w("SWPlay", "playNextPrepared: no nextPlayer")
                return false
            }
            val song = pendingNextSong ?: run {
                android.util.Log.w("SWPlay", "playNextPrepared: no pendingNextSong; releasing np")
                nextPlayer = null
                pendingNextUrl = null
                pendingNextPlatform = ""
                pendingNextReady = false
                releaseTrackedPlayer(np)
                return false
            }
            // 队列身份校验：预取曲必须仍是“当前队列中当前索引指向的下一首”。
            // 用户可能刚完成“设为下一首/清空/重排”，此时 key 相同但队列顺序已变；
            // 直接提升会播放旧队列顺序里的预取播放器，而不是用户当前队列的下一首。
            val currentNextKey = queue.getOrNull(queueIndex + 1)?.key()
            if (currentNextKey == null || currentNextKey != song.key()) {
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "playNextPrepared: queue mismatch cur=$currentNextKey want=${song.key()}; releasing")
                // 预取已不再对应队列中的当前位置：释放旧播放器，回到正常解析路径。
                nextPlayer = null
                pendingNextUrl = null
                pendingNextSong = null
                pendingNextPlatform = ""
                pendingNextReady = false
                releaseTrackedPlayer(np)
                return false
            }
            // 对象已创建不代表已缓冲可播；未就绪时走正常下一首解析，不能提升半成品播放器。
            if (!pendingNextReady || np.playbackState != Player.STATE_READY) {
                android.util.Log.w("SWPlay", "playNextPrepared: not ready (pendingNextReady=$pendingNextReady state=${np.playbackState})")
                return false
            }
            if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "playNextPrepared: promoting ${song.key()}")
            fixedCrossfade.cancel()
            val old = player
            val fadeMs = if (old?.isPlaying == true && !sleepStopAfterCurrent && !isPlaybackInterrupted()) {
                xyz.cncnlp.soundwave.playback.FixedCrossfadePolicy.duration(
                    configuredCrossfadeMs(), old.duration - old.currentPosition, playbackSpeed
                ).takeIf { np.duration > it * playbackSpeed * 2 } ?: 0L
            } else 0L
            val promotedPlatform = pendingNextPlatform
            nextPlayer = null
            pendingNextUrl = null
            pendingNextSong = null
            pendingNextPlatform = ""
            pendingNextReady = false
            if (old != null) crossfadePlayers.add(old)
            player = np
            val generation = playGen.incrementAndGet()
            recoveryGen = playGen.get()
            val token = "${song.key()}#$generation"
            activePlaybackToken = token
            endedHandledToken = ""
            previewCandidateToken = if (MusicSourceRegistry.capabilitiesOf(song.source).previewClipRisk) token else null
            notificationCoverToken = token
            notificationCoverBitmap = null
            currentSong = song
            activeSourcePlatform = promotedPlatform.ifBlank { song.source }
            positionMs = 0L
            durationMs = 0L
            val i = queue.indexOfFirst { it.key() == song.key() }
            if (i >= 0) queueIndex = i
            try {
                instance?.detachFrom(old)
                // 固定线性交叉淡化；新播放器真正输出后才推进，关闭/曲终则立即以正常音量切歌。
                try {
                    np.volume = if (fadeMs > 0) 0f else focusVolumeScale
                    instance?.attachListener(np)
                    try { np.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed, 1f) } catch (_: Exception) {}
                    np.playWhenReady = true
                    np.play()
                    if (old != null && np.playWhenReady && !isPlaybackInterrupted()) {
                        fixedCrossfade.start(old, np, fadeMs)
                    } else {
                        releaseTrackedPlayer(old)
                        np.volume = focusVolumeScale
                    }
                    if (eqPreset != 0) setEqualizerPreset(eqPreset)
                } catch (_: Exception) {
                    fixedCrossfade.cancel()
                    instance?.attachListener(np)
                    try { np.volume = focusVolumeScale } catch (_: Exception) {}
                    np.playWhenReady = true
                    np.play()
                    try { old?.stop() } catch (_: Exception) {}
                    releaseTrackedPlayer(old)
                }
                isPlayingFlag = np.isPlaying
                currentSong?.let { PlaybackDiagnostics.markReady(it, prefetched = true) }
                onSongChanged?.invoke(song)
                onPlayState?.invoke(isPlayingFlag)
                instance?.updateSessionAndNotify()
                return true
            } catch (e: Exception) {
                fixedCrossfade.cancel()
                if (old != null && crossfadePlayers.contains(old)) releaseTrackedPlayer(old)
                try { np.volume = focusVolumeScale } catch (_: Exception) {}
                isPlayingFlag = false
                val failure = PlaybackFailure(song.key(), generation, e.message ?: "prepared next failed")
                onError?.invoke(failure)
                return false
            }
        }

        /** 当前播放会话代次，预取结果必须带着它回来校验，旧任务不得串入新队列。 */
        fun currentGeneration(): Int = playGen.get()

        fun prepareNext(
            context: Context,
            url: String,
            song: Song,
            expectedCurrentKey: String,
            expectedGeneration: Int,
            startMs: Long = 0L,
            autoSwitchWhenReady: Boolean = false,
            requestStillCurrent: () -> Boolean = { true },
            cacheLease: MediaCacheLease? = null,
            sourcePlatform: String = song.source
        ) {
            val app = context.applicationContext
            // SW-02-06（adapt）：与 playUrl 同口径——本地媒体先取租约，加密容器 URL 硬拒。
            val ownedLease = cacheLease ?: MediaCacheLeaseManager.acquireForPlayback(url)
            if (MediaDecryptCache.encryptedExtOf(url) != null) {
                ownedLease?.close()
                return
            }
            val accepted = mainHandler.post {
                var created: ExoPlayer? = null
                try {
                    // 网络/脚本晚返回的预取不能覆盖用户已经切换后的真实下一首。
                    if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner) || !requestStillCurrent() || currentSong?.key() != expectedCurrentKey || playGen.get() != expectedGeneration) {
                        ownedLease?.close()
                        return@post
                    }
                    releaseTrackedPlayer(nextPlayer)
                    nextPlayer = null
                    pendingNextUrl = null
                    pendingNextSong = null
                    pendingNextPlatform = ""
                    pendingNextReady = false
                    val np = buildCachedPlayer(app)
                    created = np
                    np.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (nextPlayer !== np) return
                            if (state == Player.STATE_READY) {
                                pendingNextReady = true
                                publishPlayback()
                                if (autoSwitchWhenReady && nextPlayer === np &&
                                    requestStillCurrent() &&
                                    currentSong?.key() == expectedCurrentKey && playGen.get() == expectedGeneration
                                ) {
                                    np.seekTo(startMs.coerceAtLeast(0L))
                                    playNextPrepared()
                                }
                            }
                            if (state == Player.STATE_IDLE) {
                                pendingNextReady = false
                                publishPlayback()
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            // 无缝切歌的预备播放器失败，不能只留 pending=false：此时 UI 已指向新歌，
                            // 旧曲仍在播；若不进入既有错误恢复链，目标曲既不会切入也不会换源重试。
                            if (nextPlayer === np) {
                                nextPlayer = null
                                pendingNextUrl = null
                                pendingNextSong = null
                                pendingNextPlatform = ""
                                pendingNextReady = false
                                publishPlayback()
                                releaseTrackedPlayer(np)
                                // 仅当前前台事务仍认领该目标时才上报，避免旧预取的迟到错误污染新播放。
                                if (autoSwitchWhenReady && requestStillCurrent() &&
                                    currentSong?.key() == expectedCurrentKey && playGen.get() == expectedGeneration
                                ) {
                                    onError?.invoke(
                                        PlaybackFailure(song.key(), expectedGeneration, error.message ?: "prepared next failed")
                                    )
                                }
                            }
                        }
                    })
                    setPlayerMedia(np, url, ownedLease)
                    nextPlayer = np
                    pendingNextUrl = url
                    pendingNextSong = song
                    pendingNextPlatform = sourcePlatform.ifBlank { song.source }
                    np.prepare()
                } catch (_: Exception) {
                    nextPlayer = null
                    pendingNextUrl = null
                    pendingNextSong = null
                    pendingNextPlatform = ""
                    pendingNextReady = false
                    releaseTrackedPlayer(created)
                    closeLeaseIfUnowned(ownedLease)
                }
            }
            if (!accepted) ownedLease?.close()
        }

        fun hasPreparedNext(): Boolean = nextPlayer != null && pendingNextSong != null && pendingNextReady
        fun preparedNextKey(): String? = pendingNextSong?.key()

        fun pause() {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return
            interruptPlayback()
            // 用户/定时器的暂停：清掉“焦点暂停”记忆，对方结束后不再自动开播。
            val intent = playbackIntent.get()
            runOnMain { if (isPlaybackIntentCurrent(intent)) audioFocusController?.onUserPause() }
            pauseInternal()
        }

        private fun pauseInternal() {
            val intent = playbackIntent.get()
            runOnMain {
                if (!isPlaybackIntentCurrent(intent) || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return@runOnMain
                fixedCrossfade.cancel()
                player?.pause()
                isPlayingFlag = false
                instance?.releaseTransitionWake()
                onPlayState?.invoke(false)
                instance?.updateSessionAndNotify()
            }
        }

        fun resumeInternal(intent: Int = playbackIntent.get()) {
            runOnMain {
                if (isPlaybackInterrupted() || !isPlaybackIntentCurrent(intent) || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return@runOnMain
                instance?.requestAudioFocus()
                player?.play()
                isPlayingFlag = true
                onPlayState?.invoke(true)
                instance?.updateSessionAndNotify()
            }
        }

        fun resume() {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return
            val intent = beginPlaybackIntent()
            // 服务被系统重建时没有 UI 回调；通知栏的明确播放意图单次持久化，供服务安全重新取链。
            if (onNeedReplay == null && !hasPlayableMedia() && (currentSong == null || recoveryStatePrepared)) {
                recoveryExplicitResume = true
                PlaybackStore.setExplicitResume(instance ?: return, true, serviceOwner)
                instance?.restoreFromStore(explicitResume = true)
                return
            }
            if (onNeedReplay == null && !hasPlayableMedia()) {
                val song = currentSong ?: return
                instance?.resolveInService(song, startMs = positionMs, autoSkip = false)
                return
            }
            // 必须走 UI：通知栏/会话点播放时，若界面已切到下一首而 Exo 还停在上一首，直接 play() 会播错歌同进度
            runOnMain {
                if (!isPlaybackIntentCurrent(intent)) return@runOnMain
                if (onNeedReplay != null) onNeedReplay?.invoke(intent)
                else resumeInternal(intent)
            }
        }

        fun toggle() {
            if (isPlayIntentActive()) pause() else resume()
        }

        fun seekTo(ms: Long) {
            mainHandler.post {
                if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return@post
                fixedCrossfade.cancel()
                player?.seekTo(ms.coerceAtLeast(0))
                instance?.updateSessionAndNotify()
            }
        }

        /** 播放速度（0.5x–2x）：走 ExoPlayer 原生 playbackParameters，保持音高。 */
        @Volatile private var playbackSpeed: Float = 1f
        fun setPlaybackSpeed(speed: Float) {
            val s = speed.coerceIn(0.5f, 2f)
            playbackSpeed = s
            mainHandler.post {
                fixedCrossfade.cancel()
                try { player?.playbackParameters = androidx.media3.common.PlaybackParameters(s, 1f) } catch (_: Exception) {}
                try { nextPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(s, 1f) } catch (_: Exception) {}
            }
        }
        fun playbackSpeed(): Float = playbackSpeed

        /** 均衡器（android.media.audiofx.Equalizer）：按预置拉高/压低各频段；0=关闭。 */
        @Volatile private var equalizer: android.media.audiofx.Equalizer? = null
        @Volatile private var eqPreset: Int = 0
        fun setEqualizerPreset(preset: Int) {
            val p = preset.coerceIn(0, 5)
            eqPreset = p
            mainHandler.post {
                try {
                    if (p == 0) {
                        try { equalizer?.release() } catch (_: Exception) {}
                        equalizer = null
                        return@post
                    }
                    val session = try { player?.audioSessionId ?: nextPlayer?.audioSessionId } catch (_: Exception) { null }
                    if (session == null || session == 0) return@post
                    val eq = equalizer ?: android.media.audiofx.Equalizer(0, session).also { equalizer = it }
                    val bands = eq.numberOfBands.coerceAtLeast(5)
                    // 增益表单一事实源（与设置页曲线/说明共用 EqualizerPresets；数值与原内联表逐项一致，单测钉住）。
                    val gains = EqualizerPresets.gains(p)
                    for (i in 0 until bands) {
                        val band = if (i < gains.size) i else i % gains.size
                        val range = eq.getBandLevelRange()
                        // getBandLevelRange() 返回 ShortArray；coerceIn 两侧类型须一致
                        val lo = range[0].toInt()
                        val hi = range[1].toInt()
                        val level = gains[band].coerceIn(lo, hi)
                        try { eq.setBandLevel(band.toShort(), level.toShort()) } catch (_: Exception) {}
                    }
                    try { eq.enabled = true } catch (_: Exception) {}
                } catch (_: Exception) {
                }
            }
        }
        fun equalizerPreset(): Int = eqPreset

        fun stopPlayback() {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return
            interruptPlayback()
            val intent = playbackIntent.get()
            val stopNow: () -> Unit = stop@{
                if (!isPlaybackIntentCurrent(intent)) return@stop
                // 用户显式停止是新的播放意图：使正在进行的恢复取链立即失效，
                // 防止旧恢复协程在“停止”之后仍把快照歌曲重新拉起来播放。
                playGen.incrementAndGet()
                recoveryGen = playGen.get()
                recoveryStatePrepared = false
                fixedCrossfade.cancel()
                try { clearPlayerMedia(player) } catch (_: Exception) {}
                isPlayingFlag = false
                instance?.releaseTransitionWake()
                onPlayState?.invoke(false)
                instance?.updateSessionAndNotify()
            }
            // 通知栏 ACTION_STOP 本身在主线程；同步完成才能在 stopSelf/onDestroy 前写入正确暂停状态。
            if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else mainHandler.post(stopNow)
        }


        /** 当前边播边缓存实际占用，用于与封面/歌词统一计算缓存预算。 */
        fun mediaCacheBytes(): Long = synchronized(this) {
            try { simpleCache?.cacheSpace ?: 0L } catch (_: Exception) { 0L }
        }

        /**
         * 将 Media3 音频缓存压到指定字节数。SimpleCache 自己管理索引/锁，按最久未触碰的资源整首淘汰。
         * 不能由普通 File.delete 触碰该目录。
         */
        internal fun trimMediaCacheTo(targetBytes: Long): MediaCacheTrimResult {
            val target = targetBytes.coerceAtLeast(0L)
            deferredMediaCacheTrimTargetBytes = target
            return synchronized(this) {
                val cache = simpleCache ?: return@synchronized MediaCacheTrimResult(target, 0L, 0, 0)
                val result = try {
                    trimMedia3Cache(cache, target, mediaCacheReadLeases) { CacheManager.recordEvictLru() }
                } catch (_: Exception) {
                    MediaCacheTrimResult(target, runCatching { cache.cacheSpace }.getOrDefault(0L), 0, 1)
                }
                CacheManager.recordMediaCacheTrim(result)
                result
            }
        }

        /** 安全清空 Media3 缓存；冷启动时按需打开磁盘上的同一 SimpleCache。 */
        fun clearMediaCache(context: Context): Int = synchronized(this) {
                val app = context.applicationContext
                val dir = File(app.cacheDir, "exo_media_cache")
                val current = simpleCache
                if (current == null && !dir.exists()) return@synchronized 0
                check(dir.isDirectory) { "Media cache directory is unavailable" }
                val cache = current ?: ensureSimpleCache(app)
                val skipped = clearMedia3CacheResources(cache, mediaCacheReadLeases)
                if (skipped == 0) check(cache.cacheSpace == 0L) { "Media cache could not be fully cleared" }
                check(dir.isDirectory) { "Media cache directory must remain in place" }
                skipped
            }

        @androidx.annotation.VisibleForTesting
        internal fun hasSimpleCacheForTests(): Boolean = synchronized(this) { simpleCache != null }

        @androidx.annotation.VisibleForTesting
        internal fun resetSimpleCacheForTests() {
            synchronized(this) {
                try { simpleCache?.release() } finally {
                    simpleCache = null
                    cacheEvictor = null
                }
            }
        }

        /** 让设置页缓存上限真实接管 Exo SimpleCache 的 LRU 淘汰器。 */
        fun reconfigureCache(app: Context, bytes: Long) {
            val target = if (bytes <= 0L || bytes >= Long.MAX_VALUE / 8) Long.MAX_VALUE / 4 else bytes
            synchronized(this) {
                if (cacheLimitBytes == target && simpleCache != null) return
                cacheLimitBytes = target
                deferredMediaCacheTrimTargetBytes = target
                cacheEvictor?.setMaxBytes(target)
                // 更新活动驱逐器即可立即采用新上限；不释放可能仍被刚创建的数据源引用的 SimpleCache。
            }
        }

        private fun ensureSimpleCache(app: Context): SimpleCache {
            simpleCache?.let { return it }
            synchronized(this) {
                simpleCache?.let { return it }
                val dir = File(app.cacheDir, "exo_media_cache")
                if (!dir.exists()) dir.mkdirs()
                val evictor = LeaseAwareCacheEvictor(cacheLimitBytes, mediaCacheReadLeases) {
                    CacheManager.recordSkippedActiveMediaResources(it)
                }
                val db = StandaloneDatabaseProvider(app)
                val c = SimpleCache(dir, evictor, db)
                cacheEvictor = evictor
                simpleCache = c
                return c
            }
        }

        @androidx.media3.common.util.UnstableApi
        fun buildCachedPlayer(app: Context): ExoPlayer {
            val upstream = DefaultHttpDataSource.Factory()
                .setUserAgent(MediaRequestPolicy.USER_AGENT)
                .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Referer" to MediaRequestPolicy.REFERER))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000)
            val guardedUpstream = xyz.cncnlp.soundwave.network.CleartextPolicyDataSource.Factory(upstream)
            val localAndHttp = androidx.media3.datasource.DefaultDataSource.Factory(app, guardedUpstream)
            return try {
                val cache = ensureSimpleCache(app)
                val cacheKeyFactory = CacheKeyFactory.DEFAULT
                val cacheFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setCacheKeyFactory(cacheKeyFactory)
                    .setUpstreamDataSourceFactory(localAndHttp)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                val leasedCacheFactory = LeasedCacheDataSource.Factory(cacheFactory, cacheKeyFactory, mediaCacheReadLeases)
                val mediaSourceFactory = DefaultMediaSourceFactory(app).setDataSourceFactory(leasedCacheFactory)
                    .setLoadErrorHandlingPolicy(xyz.cncnlp.soundwave.playback.PlaybackLoadErrorPolicy())
                configurePlayer(ExoPlayer.Builder(app).setMediaSourceFactory(mediaSourceFactory).build())
            } catch (_: Throwable) {
                // 缓存索引/数据库损坏不能阻断 APP 启动；本次进程直接退回无缓存播放。
                synchronized(this) {
                    if (mediaCacheReadLeases.activeKeyCount() == 0) {
                        try { simpleCache?.release() } catch (_: Throwable) {}
                        simpleCache = null
                        cacheEvictor = null
                    }
                }
                val directFactory = DefaultMediaSourceFactory(app).setDataSourceFactory(localAndHttp)
                    .setLoadErrorHandlingPolicy(xyz.cncnlp.soundwave.playback.PlaybackLoadErrorPolicy())
                configurePlayer(ExoPlayer.Builder(app).setMediaSourceFactory(directFactory).build())
            }
        }

        /** 播放器统一声明媒体音频属性；音频焦点由 PlaybackAudioFocusController 按用户策略管理（r656），耳机拔出仍交给 Media3。 */
        private fun configurePlayer(p: ExoPlayer): ExoPlayer {
            p.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            p.setHandleAudioBecomingNoisy(true)
            // r657：缓冲/播放期间持有 PARTIAL_WAKE_LOCK + WifiLock。此前没有任何唤醒锁，
            // 熄屏后网络缓冲阶段 CPU/Wi-Fi 可能休眠，表现为“播着播着就不播了”。
            // 只在 playWhenReady 且 BUFFERING/READY 时持有，预备播放器（playWhenReady=false）不持锁。
            try { p.setWakeMode(C.WAKE_MODE_NETWORK) } catch (_: Exception) {}
            return p
        }

        private fun ensurePlayer(app: Context) {
            if (instance == null) {
                try {
                    val i = Intent(app, PlayerService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                } catch (_: Exception) {}
            }
            if (player == null) {
                // 关键竞态：主线程点击必须在本轮 commit 前建好播放器；非主线程仍投递。
                // 两条路径都吞掉缓存库/设备音频构造失败，防止 START_STICKY 重建杀死进程。
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    player = try { buildCachedPlayer(app).also { instance?.attachListener(it) } } catch (_: Throwable) { null }
                } else {
                    mainHandler.post {
                        if (player == null) player = try { buildCachedPlayer(app).also { instance?.attachListener(it) } } catch (_: Throwable) { null }
                    }
                }
            }
        }

        fun handleTrackEnded() {
            if (isPlaybackInterrupted()) return
            // 洛雪 timeoutExitPlayed：到点后/显式「播完当前」时，曲终即停，不再推进队列。
            if (sleepStopAfterCurrent) {
                sleepStopAfterCurrent = false
                sleepDeadlineElapsed = 0L
                pause()
                onSleepTick?.invoke(0)
                return
            }
            when (playMode) {
                PlayMode.LOOP_ONE -> {
                    val intent = playbackIntent.get()
                    val token = activePlaybackToken
                    val generation = playGen.get()
                    mainHandler.post {
                        if (isPlaybackInterrupted() || !isPlaybackIntentCurrent(intent) ||
                            activePlaybackToken != token || playGen.get() != generation ||
                            !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return@post
                        player?.seekTo(0)
                        player?.play()
                        isPlayingFlag = true
                        onPlayState?.invoke(true)
                    }
                }
                else -> {
            val token = activePlaybackToken
            if (token.isBlank() || endedHandledToken == token) {
                android.util.Log.w("SWPlay", "handleTrackEnded: duplicate/invalid token=$token handled=$endedHandledToken")
                return
            }
            endedHandledToken = token
            if (naturalEndHoldToken == token) {
                // 用户已点下一首，新歌却仍在取链；旧曲此刻结束只能等待该事务，绝不能再次 moveQueue(+1)。
                isPlayingFlag = false
                onPlayState?.invoke(false)
                instance?.updateSessionAndNotify()
                return
            }
            if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "handleTrackEnded: dispatch next token=$token song=${currentSong?.key()} queueIndex=$queueIndex")
            // r657：曲间没有音频输出，系统不再替我们保活 CPU；取链/起播期间短时持锁，熄屏也能切到下一首。
            instance?.holdTransitionWake()
            // Activity 被回收而预取下一首已 READY 时，Service 自己可直接提升，不能因 callback 为 null 停播。
            if (hasPreparedNext() && onPreparedNext == null) {
                if (!playNextPrepared()) {
                    // r657：认领失败（队列已变/未 READY）且无界面时，旧实现只调用为 null 的 onError → 静默停播。
                    val handler = onError
                    if (handler != null) handler.invoke(PlaybackFailure(currentSong?.key().orEmpty(), playGen.get(), "prepared next claim failed"))
                    else instance?.resolveQueueFallback(1)
                }
                return
            }
            // 洛雪/Sollin 的完成事件是一次性 request 消费；回调异常不能静默吞掉。
            val next = if (hasPreparedNext() && onPreparedNext != null) onPreparedNext else onNeedNext
            try {
                if (next != null) next.invoke() else instance?.resolveQueueFallback(1)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("SWPlay", "handleTrackEnded callback failed: ${e.javaClass.simpleName}: ${e.message}", e) else android.util.Log.w("SWPlay", "handleTrackEnded callback failed: ${e.javaClass.simpleName}")
                onError?.invoke(PlaybackFailure(currentSong?.key().orEmpty(), playGen.get(), "next track callback failed"))
            }
                }
            }
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private val transitionPreferences by lazy { ContextPreferences.open(this, "soundwave", 0, serviceOwner) }
    private var audioManager: AudioManager? = null
    /** 悬浮歌词由 Service 持有，不能依赖 Activity/Compose 生命周期。 */
    @Volatile private var floatingLyricLines: List<LyricLine> = emptyList()
    @Volatile private var floatingLyricToken: String = ""
    @Volatile private var floatingLyricLoadingToken: String = ""
    private val tick = Handler(Looper.getMainLooper())
    /** r658c D#2：听歌统计由服务计时（界面不在也计），单线程串行落盘。 */
    private val listenStats = ListenStatsAccumulator()

    private fun recordListenStats(nowMs: Long, song: Song?, playing: Boolean) =
        recordListenStats(nowMs, song, playing, notifyLocalChange = true)

    private fun recordListenStats(nowMs: Long, song: Song?, playing: Boolean, notifyLocalChange: Boolean) {
        val step = listenStats.onTick(nowMs, song, playing)
        if (step.markPlay == null && step.flushes.isEmpty()) return
        submitListenStats(step.markPlay, step.flushes, notifyLocalChange)
    }

    private fun submitListenStats(markPlay: Song?, flushes: List<ListenStatsAccumulator.Flush>) =
        submitListenStats(markPlay, flushes, notifyLocalChange = true)

    private fun submitListenStats(markPlay: Song?, flushes: List<ListenStatsAccumulator.Flush>, notifyLocalChange: Boolean) {
        if (flushes.isEmpty()) {
            ListenStatsSync.submit(applicationContext, serviceOwner, markPlay, flushes, notifyLocalChange)
            return
        }
        // r658c D#2: the service applies its accumulated durations itself (batch below);
        // attribution is captured here, at submit time, exactly as ListenStatsSync.submit
        // would. If it cannot be captured, keep the shared path so the failure is
        // recorded by the queue instead of being silently dropped.
        val accountUser = try { ListenStats.activeAccountUser(applicationContext) } catch (_: Exception) {
            ListenStatsSync.submit(applicationContext, serviceOwner, markPlay, flushes, notifyLocalChange)
            return
        }
        if (markPlay != null) {
            ListenStatsSync.submit(applicationContext, serviceOwner, markPlay, emptyList(), notifyLocalChange)
        }
        val data = ContextData(applicationContext, serviceOwner)
        ListenStatsSync.submitOnWriter(serviceOwner, ListenStatsWriteQueue.Operation.DURATION) {
            // The signed-in account may have changed while this batch waited in the
            // queue; then the seconds still belong to the account captured at submit
            // time (explicit-attribution write), never to the new account.
            val writeDuration: (ListenStatsAccumulator.Flush) -> Boolean =
                if (ListenStats.activeAccountUser(applicationContext) != accountUser) {
                    { data.addDuration(it.song, it.seconds, accountUser) }
                } else {
                    { data.addDuration(it.song, it.seconds) }
                }
            var committed = true
            flushes.forEach {
                if (writeDuration(it)) {
                    if (notifyLocalChange && accountUser != null &&
                        serviceOwner.owner == xyz.cncnlp.soundwave.migration.ProfileOwner.LegacyUnassigned
                    ) {
                        AccountOwnedDataChanges.localChange(AccountDataIdentity.key(accountUser))
                    }
                } else {
                    committed = false
                }
            }
            committed
        }
    }
    /** 通知/会话进度节流：1s 一次即可让系统媒体控件与通知进度跟随播放位置。 */
    private var lastNotifyRefreshMs = 0L
    /** r658c F：Metadata / 通知去重键（见 [NotificationRefreshPolicy]）。 */
    @Volatile private var lastMetadataKey: String = ""
    @Volatile private var lastNotificationKey: String = ""
    @Volatile private var lastNotificationAtMs: Long = 0L
    /** Presentation/cache lifetime remains bound to this service instance. */
    private val notificationPresenter by lazy { NotificationPresenter(this) }
    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                val p = player
                if (p != null) {
                    positionMs = p.currentPosition
                    val d = p.duration
                    if (d > 0) durationMs = d
                    publishPlayback()
                    recordListenStats(SystemClock.elapsedRealtime(), currentSong, p.isPlaying)
                    updateFloatingLyric(p.currentPosition)
                    markHealthyAndPrefetch(p)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotifyRefreshMs >= 1000L) {
                        lastNotifyRefreshMs = now
                        updateSessionAndNotify()
                    }
                }
                if (sleepDeadlineElapsed > 0) {
                    val rem = sleepRemainMs()
                    onSleepTick?.invoke(rem)
                    if (rem <= 0) {
                        sleepDeadlineElapsed = 0
                        // 到点后改为「播完当前再停」（洛雪 timeoutExitPlayed=true 默认行为）
                        sleepStopAfterCurrent = true
                        onSleepTick?.invoke(Long.MAX_VALUE / 4)
                    }
                }
                maybeStartFixedCrossfade()
            } catch (_: Exception) {}
            tick.postDelayed(this, 400)
        }
    }

    /** Use the existing foreground transaction callback; never promote around its gate. */
    private fun maybeStartFixedCrossfade() {
        if (!isFixedCrossfadeEligible(true)) return
        val token = activePlaybackToken
        earlyCrossfadeRequestedToken = token
        earlyCrossfadeDispatch = token
        try {
            val callback = onPreparedNext
            if (callback != null) callback.invoke() else playNextPrepared()
        } finally {
            earlyCrossfadeDispatch = null
        }
        // Natural STATE_ENDED is NOT consumed here: a stale/rejected early request must still advance normally.
    }

    private fun isFixedCrossfadeEligible(checkAlreadyRequested: Boolean): Boolean {
        val p = player ?: return false
        val configuredMs = configuredCrossfadeMs()
        if (fixedCrossfade.active && configuredMs == 0L) fixedCrossfade.cancel()
        val np = nextPlayer ?: return false
        val token = activePlaybackToken
        val validQueue = pendingNextSong?.key() == queue.getOrNull(queueIndex + 1)?.key()
        return xyz.cncnlp.soundwave.playback.FixedCrossfadePolicy.shouldStart(
                p.duration, p.currentPosition, np.duration, configuredMs, playbackSpeed,
                p.isPlaying, hasPreparedNext() && np.playbackState == Player.STATE_READY && validQueue,
                playMode == PlayMode.SEQUENCE,
                token.isBlank() || (checkAlreadyRequested && token == earlyCrossfadeRequestedToken) || fixedCrossfade.active ||
                    sleepStopAfterCurrent || (sleepDeadlineElapsed > 0 && sleepRemainMs() <= configuredMs) ||
                    naturalEndHoldToken == token || isPlaybackInterrupted() ||
                    !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)
            )
    }

    /**
     * r657：稳定播放 ≥5 秒才清零连续跳过计数（避免“每首起播 1 秒就报错”时在歌单里无限循环）；
     * 没有前台回调时在曲尾前 [HEADLESS_PREFETCH_LEAD_MS] 预取下一首。
     */
    private fun markHealthyAndPrefetch(p: ExoPlayer) {
        val song = currentSong ?: return
        val token = activePlaybackToken
        if (token.isBlank() || !p.isPlaying) return
        val pos = p.currentPosition
        if (pos < xyz.cncnlp.soundwave.playback.PlaybackRecoveryPolicy.HEALTHY_PLAYED_MS) return
        if (healthyMarkedToken != token) {
            healthyMarkedToken = token
            recoveryPolicy.onHealthyPlayback(song.key())
        }
        if (headlessPrefetchToken != token && onNeedNext == null && onPreparedNext == null && nextPlayer == null) {
            val d = p.duration
            if (d <= 0 || d - pos <= HEADLESS_PREFETCH_LEAD_MS) {
                headlessPrefetchToken = token
                prefetchNextWithoutUi(token)
            }
        }
    }

    /** Service-only 播放也持续驱动悬浮歌词；异步结果绑定歌曲与播放代次。 */
    private fun updateFloatingLyric(posMs: Long) {
        val song = currentSong ?: return
        if (!LyricFloat.isUserEnabled(this)) return
        val token = "${song.key()}#${playGen.get()}"
        if (floatingLyricToken != token && floatingLyricLoadingToken != token) {
            floatingLyricLines = emptyList()
            floatingLyricToken = token
            floatingLyricLoadingToken = token
            LyricFloat.tick(this, song.name, song.singer)
            val priority = ContextPreferences.open(this, "soundwave", 0, serviceOwner)
                .getString("lyric_priority", "external") ?: "external"
            recoveryScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    val choices = if (priority == "embedded") listOf("embedded", "external") else listOf("external", "embedded")
                    var found: List<LyricLine>? = null
                    for (source in choices) {
                        found = (try {
                            parseLyricTextBundle(LyricSourceLoader.load(applicationContext, song, source, serviceOwner))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) { null })?.takeIf { it.isNotEmpty() }
                        if (found != null) break
                    }
                    found ?: emptyList()
                }
                if (floatingLyricLoadingToken == token && currentSong?.key() == song.key() && playGen.get().toString() == token.substringAfterLast('#')) {
                    floatingLyricLines = loaded
                    floatingLyricLoadingToken = ""
                }
            }
        }
        val lines = floatingLyricLines
        if (lines.isEmpty()) {
            LyricFloat.tick(this, song.name, song.singer)
        } else {
            val i = LyricParser.indexAt(lines, posMs)
            // r656：附带下一句起点（无词轴整句线性填充）与播放态（悬浮窗逐帧插值仅在播放时外推）。
            LyricFloat.tickLine(
                this, lines.getOrNull(i), lines.getOrNull(i + 1)?.text.orEmpty(), posMs,
                nextStartMs = lines.getOrNull(i + 1)?.timeMs ?: 0L, playing = isPlayingNow()
            )
        }
    }

    private val sleepCheck = object : Runnable {
        override fun run() {
            if (sleepDeadlineElapsed > 0 && sleepRemainMs() <= 0) {
                sleepDeadlineElapsed = 0
                // 与 tick 一致：到点后等当前曲播完再停
                sleepStopAfterCurrent = true
                onSleepTick?.invoke(Long.MAX_VALUE / 4)
            } else if (sleepDeadlineElapsed > 0) {
                tick.postDelayed(this, 1000)
            }
        }
    }

    fun scheduleSleepCheck() {
        tick.removeCallbacks(sleepCheck)
        if (sleepDeadlineElapsed > 0) tick.post(sleepCheck)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        val mediaOwner = serviceOwner
        fun mediaOwnerCurrent() = mediaOwner.matches(serviceOwner) &&
            xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(mediaOwner)
        mediaSession = MediaSessionCompat(this, "SoundWave").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (mediaOwnerCurrent()) resume() }
                override fun onPause() { if (mediaOwnerCurrent()) pause() }
                override fun onSkipToNext() {
                    if (!mediaOwnerCurrent()) return
                    recoveryPolicy.onUserIntent()
                    val next = onNeedNext
                    if (next != null) next.invoke()
                    else if (hasPreparedNext()) playNextPrepared()
                    else resolveQueueFallback(1)
                }
                override fun onSkipToPrevious() { if (mediaOwnerCurrent()) { recoveryPolicy.onUserIntent(); onNeedPrev?.invoke() ?: resolveQueueFallback(-1) } }
                override fun onStop() { if (mediaOwnerCurrent()) stopPlayback() }
                override fun onSeekTo(pos: Long) { if (mediaOwnerCurrent()) seekTo(pos) }
            })
            isActive = true
        }
        if (player == null) {
            // START_STICKY 可在 Activity 之前重建 Service；播放器/缓存构建失败只能降级为无播放器，不能让整个 app 进程退出。
            player = try { buildCachedPlayer(this) } catch (_: Throwable) { null }
        }
        // playUrl 可能早于 Service.onCreate 执行：此时静态播放器已经存在，但首播时
        // instance 还为空，listener 与 AudioFocus 都被跳过。Service 接管后必须无条件补齐，
        // 否则会出现“先让其它 App 播放一下，本 App 才有声音”的假性唤醒现象。
        player?.let {
            attachListener(it)
            // 仅在已有播放意图时申请焦点：服务被重建但未播放时不得打断其它 App。
            if (it.playWhenReady) requestAudioFocus()
        }
        startForeground(NOTIFY_ID, buildNotification("SoundWave", "准备就绪"))
        // 消费首播闸门：此时 Service、播放器、listener、AudioFocus 均已就绪。
        val firstPlay = synchronized(pendingPlayLock) { pendingPlay.also { pendingPlay = null } }
        firstPlay?.let { pending ->
            playUrl(
                pending.app,
                pending.url,
                pending.song,
                pending.startMs,
                pending.cacheLease,
                pending.sourcePlatform,
                intent = pending.intent
            )
        }
        // START_STICKY 被系统拉起时 Activity 尚未存在：恢复队列/进度为暂停态和 MediaSession，绝不自动播放。
        recoveryExplicitResume = PlaybackStore.consumeExplicitResume(applicationContext, serviceOwner)
        restoreFromStore(explicitResume = recoveryExplicitResume)
        tick.post(tickRunnable)
        // r658c D#2：统计曾因界面计时丢失；按播放历史回补「有历史无计时」的日期（幂等，真实计时优先）。
        val app = applicationContext
        val owner = serviceOwner
        ListenStatsSync.submitBackfill(app, owner)
    }

    private var playerListener: Player.Listener? = null
    /** 不依赖 Activity 的恢复协程；只在服务重建时准备暂停态，或由用户明确点通知栏播放后恢复。 */
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // r656-G：通知封面加载改为可取消协程（受限 IO 线程池），切歌即取消旧任务，不再每首歌新建裸线程。
    private val notificationCoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var notificationCoverJob: kotlinx.coroutines.Job? = null

    private val playbackRecoveryLock = Any()
    private var playbackRecoveryJob: kotlinx.coroutines.Job? = null
    private var playbackRecoveryIntent = -1

    /** Cancel only playback work, never lyrics/prefetch siblings in recoveryScope. */
    private fun cancelPlaybackRecovery(keepIntent: Int) {
        val old = synchronized(playbackRecoveryLock) {
            if (!isPlaybackIntentCurrent(keepIntent) || playbackRecoveryIntent == keepIntent) return
            playbackRecoveryJob.also {
                playbackRecoveryJob = null
                playbackRecoveryIntent = -1
                recoveryInFlight = false
            }
        }
        old?.cancel()
    }

    private fun launchPlaybackRecovery(intent: Int, block: suspend CoroutineScope.() -> Unit) {
        val job = synchronized(playbackRecoveryLock) {
            if (!isPlaybackIntentCurrent(intent)) return
            val old = playbackRecoveryJob
            val next = recoveryScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
            playbackRecoveryJob = next
            playbackRecoveryIntent = intent
            recoveryInFlight = true
            next.invokeOnCompletion {
                synchronized(playbackRecoveryLock) {
                    if (playbackRecoveryJob === next) {
                        playbackRecoveryJob = null
                        playbackRecoveryIntent = -1
                        recoveryInFlight = false
                    }
                }
            }
            old?.cancel()
            next
        }
        job.start()
    }

    private fun restoreFromStore(explicitResume: Boolean) {
        // 新明确恢复不等待旧 child 的 finally；owner identity 防旧完成清除新状态。
        if (recoveryStatePrepared && !explicitResume) return
        // 记录发起恢复时的播放代次；取链完成时若代次已变，说明有新前台播放介入，放弃恢复。
        val startGen = playGen.get()
        val intent = playbackIntent.get()
        launchPlaybackRecovery(intent) launch@{
                val state = withContext(Dispatchers.IO) { PlaybackStore.load(applicationContext, serviceOwner) } ?: return@launch
                if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner)) return@launch
                // 加载期间用户可能已点新歌（resolveAndPlay 已写 currentSong 但尚未 playUrl 换代次），
                // 或已发生一次真实播放（代次已变）；此时恢复快照不得回灌，否则覆盖用户刚选的歌曲与队列。
                if (!isPlaybackIntentCurrent(intent) || playGen.get() != startGen || (currentSong != null && currentSong?.key() != state.song.key())) return@launch
                setQueue(state.queue, state.index)
                currentSong = state.song
                queueIndex = state.index
                positionMs = state.positionMs.coerceAtLeast(0L)
                durationMs = 0L
                isPlayingFlag = false
                recoveryStatePrepared = true
                // 服务自动重建只恢复可见的暂停会话；不抢音频焦点、不自动播放。
                updateSessionAndNotify()
                if (!explicitResume) return@launch
                // onCreate 读取的一次性标记或通知栏当前点击；两者都代表明确用户意图。
                if (!recoveryExplicitResume) return@launch
                recoveryExplicitResume = false
                // 已下载本地文件必须绕开脚本/网络恢复；否则离线也会错误提示“没有可用脚本”。
                // 与点播/预取使用同一统一入口（同平台官方身份 + 可读文件），恢复不能有第二套本地判定。
                val localRestoreHit = withContext(Dispatchers.IO) { LocalFirst.findLocalHit(state.song, applicationContext) }
                if (!isPlaybackIntentCurrent(intent)) return@launch
                if (localRestoreHit != null) {
                    // ACCEPT5（本地直读）：恢复直读与点播同口径——实测音质回填 + Resolver 留存记「本地文件」。
                    val localQuality = LocalPlayQuality.forHit(state.song, localRestoreHit)
                    if (localQuality.isNotBlank()) state.song.actualQuality = localQuality
                    UrlResolver.noteLocalPlayback(state.song.key(), state.song.source, localQuality, downloaded = localRestoreHit.download != null, owner = serviceOwner)
                    recoveryStatePrepared = false
                    playUrl(applicationContext, localRestoreHit.uri, state.song, state.positionMs, sourcePlatform = "local", intent = intent)
                    return@launch
                }
                val scripts = withContext(Dispatchers.IO) {
                    val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(applicationContext, "soundwave_scripts", Context.MODE_PRIVATE, serviceOwner)
                    ScriptStorage.loadScripts(prefs).filter { it.enabled && it.code.isNotBlank() }
                }
                if (scripts.isEmpty()) return@launch
                // 复用进程级共享 ScriptRuntime：WebView 常驻、脚本已初始化，恢复不再冷启动。
                val runtime = ScriptRuntimeHolder.get(applicationContext, serviceOwner)
                try {
                    runtime.init()
                    val result = withContext(Dispatchers.IO) {
                        UrlResolver.resolveResult(
                            ticket = ResolveTicket(songKey = state.song.key(), purpose = ResolveTicket.Purpose.PLAY, ownerContext = serviceOwner),
                            song = state.song,
                            scripts = scripts,
                            runtime = runtime,
                            preferQuality = SmartSourceStore.platformPlayQuality(state.song.source.ifEmpty { "wy" }, storedPlayQuality()),
                            requestStillCurrent = { isPlaybackIntentCurrent(intent) && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner) && currentSong?.key() == state.song.key() }
                        )
                    }
                    // 只有发起恢复后没有新播放介入（代次未变）、且当前歌曲仍是恢复目标时才提交。
                    if (isPlaybackIntentCurrent(intent) && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(serviceOwner) && result.isSuccess && currentSong?.key() == state.song.key() && playGen.get() == startGen && recoveryGen == startGen) {
                        recoveryStatePrepared = false
                        PlaybackDiagnostics.markResolved(state.song, result.elapsedMs, result.fromCache, result.scriptId, result.platform, result.quality, purpose = "恢复")
                        playUrl(
                            applicationContext,
                            ApiClient.playableUrl(result.url),
                            state.song,
                            state.positionMs,
                            sourcePlatform = result.platform,
                            intent = intent
                        )
                    }
                } finally {
                    // 共享全局实例不销毁，由进程持有。
                }
        }
    }

    /**
     * Service 在 Activity/Compose 回调已失效时仍需能驱动通知栏上下首与自然续播。
     * 复用持久化脚本与共享 Runtime；只在没有前台回调时调用，且用代次防止迟到取链覆盖用户的新点歌。
     *
     * r657：用户划掉任务/系统回收 Activity 后，自然播完走的就是这里。旧实现有两个断点：
     *  1. 没有启用脚本时直接报 NO_ENABLED_SCRIPT 返回——连官方直连（kg/kw/mg/wy 匿名取链）都不试；
     *  2. 取链失败只调用 onError（此时为 null），既不跳过也不提示 → “播完一首不会继续播放”。
     */
    private fun resolveQueueFallback(delta: Int, autoSkip: Boolean = delta > 0) {
        val song = moveQueue(delta) ?: return
        resolveInService(song, startMs = 0L, autoSkip = autoSkip)
    }

    /** 无界面时按统一决策恢复播放故障（Activity 在时由 PlaybackController.handlePlaybackFailure 负责）。 */
    private fun recoverWithoutUi(failure: PlaybackFailure) {
        val song = currentSong?.takeIf { it.key() == failure.songKey } ?: return
        if (isPlaybackInterrupted() || playGen.get() != failure.generation) return
        val decision = recoveryPolicy.onPlaybackError(song.key(), failure.positionMs, failure.durationMs)
        android.util.Log.w("SWPlay", "recoverWithoutUi: ${decision.action} attempt=${decision.attempt} resume=${decision.resumeAtMs}")
        when (xyz.cncnlp.soundwave.playback.localRecoveryAction(LocalPlayback.isLocalSong(song), decision.action)) {
            xyz.cncnlp.soundwave.playback.RecoveryAction.REFRESH_SAME_SOURCE ->
                resolveInService(song, startMs = decision.resumeAtMs, autoSkip = true)
            xyz.cncnlp.soundwave.playback.RecoveryAction.SWITCH_SOURCE -> {
                val blocked = UrlResolver.markWinningScriptBad(song.key(), serviceOwner)
                SmartSourceStore.forget(song.key())
                resolveInService(
                    song,
                    startMs = decision.resumeAtMs,
                    forceCross = true,
                    skipScripts = blocked.takeIf { it.isNotBlank() }?.let(::setOf).orEmpty(),
                    autoSkip = true
                )
            }
            xyz.cncnlp.soundwave.playback.RecoveryAction.SKIP_NEXT -> {
                if (!decision.nearEnd) showServiceToast("《${song.name}》暂时无法播放，已跳到下一首")
                // 曲尾断流且已预取好下一首：直接提升预备播放器，和自然播完一致。
                if (decision.nearEnd && hasPreparedNext() && playNextPrepared()) return
                resolveQueueFallback(1)
            }
            xyz.cncnlp.soundwave.playback.RecoveryAction.STOP -> stopAfterRepeatedFailures()
        }
    }

    private fun stopAfterRepeatedFailures() {
        releaseTransitionWake()
        isPlayingFlag = false
        onPlayState?.invoke(false)
        showServiceToast("连续多首无法播放，已暂停。请检查网络或音源设置")
        updateSessionAndNotify()
    }

    private fun showServiceToast(message: String) {
        mainHandler.post {
            try {
                AppNotice.attach(applicationContext)
                AppNotice.inbox(AppNotice.Category.PLAYBACK, message, alsoBanner = true)
            } catch (_: Exception) {}
        }
    }

    /**
     * 服务侧取链的全局播放音质：与 MainActivity.readUserQuality 同口径读 "soundwave"
     * 命名空间的 play_br。此前恢复/服务取链/预取三处把全局参数写死 "320k"，用户设的
     * 全局最高音质在服务路径上被完全丢弃（只剩平台覆盖生效）。任何读取异常回落
     * "320k"，与旧写死行为等价兜底。
     */
    private fun storedPlayQuality(): String = try {
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(
            applicationContext, "soundwave", Context.MODE_PRIVATE, serviceOwner
        )
        normalizeUserSelectableQuality(prefs.getString("play_br", "320k"))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        "320k"
    }

    private suspend fun loadEnabledScripts(): List<Script> = withContext(Dispatchers.IO) {
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(applicationContext, "soundwave_scripts", Context.MODE_PRIVATE, serviceOwner)
        ScriptStorage.loadScripts(prefs).filter { it.enabled && it.code.isNotBlank() }
    }

    /**
     * 无界面取链并起播。scripts 为空也照常进入 Resolver：官方直连/服务端匿名取链不依赖脚本，
     * Resolver 自己会在“既无脚本又不可匿名”时返回 NO_ENABLED_SCRIPT。
     */
    private fun resolveInService(
        song: Song,
        startMs: Long,
        forceCross: Boolean = false,
        skipScripts: Set<String> = emptySet(),
        autoSkip: Boolean
    ) {
        val intent = beginPlaybackIntent()
        val startGen = playGen.incrementAndGet()
        recoveryGen = startGen
        currentSong = song
        positionMs = startMs
        durationMs = 0L
        isPlayingFlag = false
        holdTransitionWake()
        try { clearPlayerMedia(player) } catch (_: Exception) {}
        updateSessionAndNotify()
        launchPlaybackRecovery(intent) launch@{
            fun stillCurrent() = isPlaybackIntentCurrent(intent) && currentSong?.key() == song.key() && playGen.get() == startGen
            // r658c D#1：无界面续播/恢复同样本地优先；本地歌永不进脚本（Resolver 入口也会拒绝）。
            val localHit = withContext(Dispatchers.IO) { try { LocalFirst.findLocalHit(song, applicationContext) } catch (_: Exception) { null } }
            if (!stillCurrent()) return@launch
            if (localHit != null) {
                // ACCEPT5（本地直读）：续播直读同口径——实测音质回填 + Resolver 留存记「本地文件」。
                val localQuality = try { LocalPlayQuality.forHit(song, localHit) } catch (_: Exception) { "" }
                if (localQuality.isNotBlank()) song.actualQuality = localQuality
                UrlResolver.noteLocalPlayback(song.key(), song.source, localQuality, downloaded = localHit.download != null, owner = serviceOwner)
                song.resolvedUrl = localHit.uri
                playUrl(applicationContext, localHit.uri, song, startMs, sourcePlatform = "local", intent = intent)
                return@launch
            }
            val scripts = loadEnabledScripts()
            val runtime = ScriptRuntimeHolder.get(applicationContext, serviceOwner)
            runtime.init()
            // ACCEPT6-FREEZE：本协程在 recoveryScope（Main.immediate）上，shouldForceCross
            // 的全量标记表解析与 notePlayOutcome 的全表回写都必须在 IO 段内做（同前台纪律）。
            var effectiveForceCross = forceCross
            val result = withContext(Dispatchers.IO) {
                if (!effectiveForceCross) effectiveForceCross = PlaylistImportChecker.shouldForceCross(song.key())
                UrlResolver.resolveResult(
                    ticket = ResolveTicket(songKey = song.key(), purpose = ResolveTicket.Purpose.PLAY),
                    song = song, scripts = scripts, runtime = runtime,
                    preferQuality = SmartSourceStore.platformPlayQuality(song.source.ifEmpty { "wy" }, storedPlayQuality()),
                    skipScripts = skipScripts,
                    forceCross = effectiveForceCross,
                    requestStillCurrent = ::stillCurrent
                )
            }
            if (!stillCurrent()) return@launch
            if (result.isSuccess) {
                // ACCEPT5（死链回写）：与前台实播同一纪律（见 PlaylistImportChecker.notePlayOutcome）。
                withContext(Dispatchers.IO) {
                    PlaylistImportChecker.notePlayOutcome(
                        songKey = song.key(),
                        originSource = song.source.ifEmpty { "wy" },
                        servedPlatform = result.platform,
                        wasForceCross = effectiveForceCross
                    )
                }
                if (!stillCurrent()) return@launch
                playUrl(
                    applicationContext,
                    ApiClient.playableUrl(result.url),
                    song,
                    startMs,
                    sourcePlatform = result.platform,
                    intent = intent
                )
                return@launch
            }
            val failure = result.failure.name.takeIf { it != "NONE" }.orEmpty().ifBlank { "NO_PLAYABLE_URL" }
            PlaybackDiagnostics.markFailure(song, failure)
            // Activity 若已回来，交给前台统一处理（它会走同一份 recoveryPolicy）。
            val handler = onError
            if (handler != null) {
                handler.invoke(PlaybackFailure(song.key(), startGen, failure))
                return@launch
            }
            if (!autoSkip) {
                releaseTransitionWake()
                showServiceToast("《${song.name}》取链失败")
                updateSessionAndNotify()
                return@launch
            }
            val decision = recoveryPolicy.onResolveFailure(song.key())
            if (decision.action == xyz.cncnlp.soundwave.playback.RecoveryAction.SKIP_NEXT && queue.size > 1) {
                showServiceToast("《${song.name}》暂时无法播放，已跳到下一首")
                resolveQueueFallback(1)
            } else {
                stopAfterRepeatedFailures()
            }
        }
    }

    /**
     * r657：Activity 不在时也要预取下一首，否则每首歌结束都要现场取链（熄屏时尤其慢）。
     * 只做顺序模式（下一首确定）；结果放进唯一的预备播放器，handleTrackEnded 在无回调时会直接提升它。
     */
    private fun prefetchNextWithoutUi(token: String) {
        if (playMode != PlayMode.SEQUENCE || queue.size < 2) return
        val current = currentSong ?: return
        // 末尾回绕时 playNextPrepared 的“下一首=queue[index+1]”校验必然失败，交给曲终现场取链。
        val next = queue.getOrNull(queueIndex + 1) ?: return
        if (next.key() == current.key() || pendingNextSong?.key() == next.key()) return
        val expectedGen = playGen.get()
        fun stillCurrent() = activePlaybackToken == token && playGen.get() == expectedGen &&
            onPreparedNext == null && onNeedNext == null
        recoveryScope.launch {
            val localHit = withContext(Dispatchers.IO) { try { LocalFirst.findLocalHit(next, applicationContext) } catch (_: Exception) { null } }
            if (!stillCurrent()) return@launch
            if (localHit != null) {
                // ACCEPT5（本地直读）：备下一首命中本地文件同口径——实测音质回填副本 + 留存记「本地文件」。
                val localQuality = try { LocalPlayQuality.forHit(next, localHit) } catch (_: Exception) { "" }
                UrlResolver.noteLocalPlayback(next.key(), next.source, localQuality, downloaded = localHit.download != null, owner = serviceOwner)
                prepareNext(applicationContext, localHit.uri, next.copy(resolvedUrl = localHit.uri, actualQuality = localQuality.ifBlank { next.actualQuality }), current.key(), expectedGen,
                    requestStillCurrent = ::stillCurrent, sourcePlatform = "local")
                return@launch
            }
            val scripts = loadEnabledScripts()
            val runtime = ScriptRuntimeHolder.get(applicationContext, serviceOwner)
            runtime.init()
            val result = withContext(Dispatchers.IO) {
                UrlResolver.resolveResult(
                    ticket = ResolveTicket(songKey = next.key(), serviceGeneration = expectedGen, purpose = ResolveTicket.Purpose.PREFETCH),
                    song = next, scripts = scripts, runtime = runtime,
                    preferQuality = SmartSourceStore.platformPlayQuality(next.source.ifEmpty { "wy" }, storedPlayQuality()),
                    forceCross = PlaylistImportChecker.shouldForceCross(next.key()),
                    requestStillCurrent = ::stillCurrent
                )
            }
            if (!stillCurrent() || !result.isSuccess) return@launch
            // 加密媒体需要先下载解密，留给切歌时的正常取链路径处理。
            if (MediaDecryptCache.encryptedExtOf(result.url) != null) return@launch
            prepareNext(applicationContext, ApiClient.playableUrl(result.url), next, current.key(), expectedGen,
                requestStillCurrent = ::stillCurrent, sourcePlatform = result.platform)
        }
    }

    // ---- r657：曲间过渡唤醒锁 ----
    private var transitionWakeLock: android.os.PowerManager.WakeLock? = null

    /** 曲间/恢复取链期间没有音频输出，ExoPlayer 也已释放锁；短时持有 PARTIAL_WAKE_LOCK，超时自动释放。 */
    fun holdTransitionWake() {
        try {
            val lock = transitionWakeLock ?: (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SoundWave:transition")
                .also { it.setReferenceCounted(false); transitionWakeLock = it }
            lock.acquire(TRANSITION_WAKE_MS)
        } catch (_: Exception) {}
    }

    fun releaseTransitionWake() {
        try { transitionWakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }

    fun detachFrom(p: ExoPlayer?) {
        val lis = playerListener ?: return
        if (p == null) return
        try { p.removeListener(lis) } catch (_: Exception) {}
    }

    fun attachListener(p: ExoPlayer) {
        detachFrom(p)
        val listenerOwner = serviceOwner
        val listenerToken = activePlaybackToken
        val listenerSongKey = currentSong?.key().orEmpty()
        val listenerGeneration = playGen.get()
        val lis = object : Player.Listener {
            /** 同一播放器复用时，监听器只服务于创建时那一首；任何迟到事件直接丢弃。 */
            private fun isCurrentTransaction(): Boolean =
                listenerOwner.matches(serviceOwner) && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(listenerOwner) &&
                    p === player && listenerToken.isNotBlank() && listenerToken == activePlaybackToken &&
                    listenerSongKey == currentSong?.key().orEmpty() && listenerGeneration == playGen.get()

            override fun onIsPlayingChanged(playing: Boolean) {
                if (!isCurrentTransaction()) return
                if (!playing && !p.playWhenReady && p.playerError == null) fixedCrossfade.cancel()
                if (playing && fixedCrossfade.retainingOutgoing) fixedCrossfade.cancel()
                // 兜底：任何路径真正开播时都确保已按策略持有焦点（重复申请幂等）。
                if (playing && audioFocusSettings.mode != xyz.cncnlp.soundwave.playback.AudioFocusMode.MIX) requestAudioFocus()
                if (playing) releaseTransitionWake()
                isPlayingFlag = playing
                onPlayState?.invoke(playing)
                updateSessionAndNotify()
            }
            override fun onPlaybackStateChanged(state: Int) {
                // 播放完成（STATE_ENDED）不绑定代次：预取/恢复等代次变化不得吞掉“播完切下一首”，
                // 只要求事件来自当前播放器实例，避免旧 listener 误触发。
                if (state == Player.STATE_ENDED) {
                    if (p !== player || !listenerOwner.matches(serviceOwner) ||
                        !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(listenerOwner)) return
                } else if (!isCurrentTransaction()) return
                // 播放器已被前台服务持续持有；界面离开/回来不得把真实播放态误刷成暂停。
                isPlayingFlag = p.isPlaying
                onPlayState?.invoke(isPlayingFlag)
                if (state == Player.STATE_READY) {
                    // ExoPlayer 的持续播放条件是 playWhenReady=true 且播放器 READY；
                    // 某些设备在 stop/clear/setMediaItem 后会吞掉第一次 play()，READY 时补一次。
                    if (!isPlaybackInterrupted() && p.playWhenReady && !p.isPlaying) {
                        try { p.play() } catch (_: Exception) {}
                    }
                    val d = p.duration
                    if (d > 0) durationMs = d
                    // session 就绪后再次绑定速度/EQ，避免换歌后失效
                    try { p.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed, 1f) } catch (_: Exception) {}
                    if (eqPreset != 0) setEqualizerPreset(eqPreset)
                    currentSong?.let {
                        PlaybackDiagnostics.markReady(it, prefetched = false)
                        // 音频缓存元数据索引：记录实际音质与来源
                        try { CacheManager.recordAudioIndex(instance?.applicationContext ?: return@let, it) } catch (_: Exception) {}
                    }
                    // 网易云部分脚本会把版权受限曲伪装成可播直链，最终只返回约 30 秒试听。
                    // 这不是绕过：检测后立即废弃该 URL、短时隔离本次脚本，并让上层走合法备用链路。
                    if (previewCandidateToken == listenerToken && d in PREVIEW_DURATION_MIN_MS..PREVIEW_DURATION_LIMIT_MS) {
                        previewCandidateToken = null
                        val song = currentSong
                        try { clearPlayerMedia(p) } catch (_: Exception) {}
                        isPlayingFlag = false
                        if (song != null) {
                            // 试听是最终命中链不可播，按歌曲+脚本短熔断，下一轮不会重复选同一脚本。
                            UrlResolver.clearSongCache(song, serviceOwner)
                            SmartSourceStore.forget(song.key())
                            UrlResolver.markWinningScriptBad(song.key(), serviceOwner)
                            PlaybackDiagnostics.markFailure(song, "preview")
                            onError?.invoke(PlaybackFailure(listenerSongKey, listenerGeneration, "网易云仅返回试听片段"))
                        }
                        updateSessionAndNotify()
                        return
                    }
                    previewCandidateToken = null
                    updateSessionAndNotify()
                }
                if (state == Player.STATE_ENDED) {
                    // 先交给队列推进：有预取/下一首时不能向 UI 广播“暂停”，否则重进 App 或通知栏会把新播放错刷成暂停。
                    positionMs = durationMs.coerceAtLeast(0L)
                    if (playMode == PlayMode.LOOP_ONE || hasPreparedNext() || onNeedNext != null || queue.isNotEmpty()) {
                        handleTrackEnded()
                    } else {
                        isPlayingFlag = false
                        onPlayState?.invoke(false)
                        updateSessionAndNotify()
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentTransaction() || isPlaybackInterrupted()) return
                fixedCrossfade.holdOutgoingForRecovery()
                isPlayingFlag = false
                val failedAtMs = (try { p.currentPosition } catch (_: Exception) { positionMs }).coerceAtLeast(0L)
                val failedDurationMs = try { p.duration.takeIf { it > 0 } ?: durationMs } catch (_: Exception) { durationMs }
                // r657：已经稳定播放过一段才报错，多半是直链过期 / 网络切换，不是脚本给了坏链。
                // 只清 URL 缓存，不隔离脚本、不清智能换源记忆，让第一次恢复按原平台重新取链并续播。
                val transient = xyz.cncnlp.soundwave.playback.PlaybackRecoveryPolicy.isTransient(failedAtMs)
                currentSong?.takeIf { it.key() == listenerSongKey }?.let { song ->
                    UrlResolver.clearSongCache(song, serviceOwner)
                    if (!transient) {
                        // 最终已播放链起播即报错时隔离该歌曲的命中脚本；不会波及其它歌。
                        SmartSourceStore.forget(song.key())
                        UrlResolver.markWinningScriptBad(song.key(), serviceOwner)
                    }
                    PlaybackDiagnostics.markFailure(song, PlaybackDiagnostics.failureLayerOf(error.message ?: "error"))
                }
                val failure = PlaybackFailure(listenerSongKey, listenerGeneration, error.message ?: "error", failedAtMs, failedDurationMs)
                holdTransitionWake()
                val handler = onError
                if (handler != null) handler.invoke(failure) else recoverWithoutUi(failure)
                updateSessionAndNotify()
            }
        }
        playerListener = lis
        p.addListener(lis)
    }

    /** r656：Media3 不再自动管理焦点（其单一策略无法表达自动暂停/同时播放/通话后保持暂停）；
     * 开播入口统一经此按用户策略申请，MIX 模式不申请。 */
    fun requestAudioFocus(): Boolean {
        if (player == null) return false
        return focusController(applicationContext).request()
    }

    private fun createChannel() {
        // 全 App 通知渠道统一策略（三条渠道以此为准）：播放 / 下载为静音低优先（IMPORTANCE_LOW），
        // 脚本检测完成为默认优先（IMPORTANCE_DEFAULT）；新增渠道须先登记于此，不得另行散设重要性与描述。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "播放控制通知，静音低优先级"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Old unticketed intents remain legacy-only; malformed or foreign tickets never rebind.
        if (intent?.hasExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER) == true) {
            val owner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.decode(
                intent.getStringExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER),
                intent.getLongExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_SESSION_GENERATION, -1L))
            if (owner == null || !owner.matches(serviceOwner)) return START_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> {
                recoveryPolicy.onUserIntent()
                val next = onNeedNext
                if (next != null) next.invoke()
                else if (hasPreparedNext()) playNextPrepared()
                else resolveQueueFallback(1)
            }
            ACTION_PREV -> {
                recoveryPolicy.onUserIntent()
                val prev = onNeedPrev
                if (prev != null) prev.invoke() else resolveQueueFallback(-1)
            }
            ACTION_LIKE -> onLikeToggle?.invoke()
            ACTION_LYRIC_FLOAT -> {
                // 前台 Activity 存在时必须走它注册的回调：它除了三态切换，还会同步
                // lyricFloatEnabled Compose 状态；直接改 SharedPreferences 会被 350ms tick 当成关闭再 hide。
                // 服务独立存活（没有 Activity）才使用同一状态机的无 UI fallback。
                val callback = onLyricFloatToggle
                if (callback != null) {
                    callback.invoke()
                } else {
                    LyricFloat.handleNotifWord(this, currentSong?.name.orEmpty(), currentSong?.singer.orEmpty())
                }
            }
            ACTION_STOP -> {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        updateSessionAndNotify()
        return START_STICKY
    }

    private fun pi(action: String, req: Int): PendingIntent {
        return PendingIntent.getService(
            this, req,
            Intent(this, PlayerService::class.java).setAction(action)
                .putExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_OWNER, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(serviceOwner))
                .putExtra(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.INPUT_SESSION_GENERATION, serviceOwner.generation),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun updateSessionAndNotify() {
        publishPlayback()
        val song = currentSong
        val title = song?.name ?: "SoundWave"
        val artist = song?.singer ?: if (isPlayingNow()) "播放中" else "已暂停"
        val playing = isPlayingNow()
        val pos = getPosition()
        val dur = getDuration()
        val coverSongKey = song?.key()
        val coverGeneration = playGen.get()
        val coverToken = "${coverSongKey.orEmpty()}#$coverGeneration"
        if (notificationCoverToken != coverToken) {
            // 切歌时绝不能先把上一首封面画给新标题；同时允许新 token 重新触发加载。
            notificationCoverBitmap = null
            notificationCoverLoadToken = ""
        }
        notificationCoverToken = coverToken
        try {
            mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(
                        if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                        pos,
                        if (playing) 1f else 0f
                    )
                    .build()
            )
            // r658c F：进度只走 PlaybackState；Metadata（含 ~1MB 封面位图，经 Binder 传两份）仅在
            // 标题/歌手/时长/封面真正变化时重设。旧实现每秒整份重传，主线程与 system_server 持续搬位图。
            val cover = notificationCoverBitmap
            val metaKey = NotificationRefreshPolicy.metadataKey(title, artist, if (dur > 0) dur else -1, cover)
            if (metaKey != lastMetadataKey) {
                val meta = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (dur > 0) dur else -1)
                // 重设 Metadata 时必须把已加载封面一并带回，否则系统媒体控件刚显示封面又被清空。
                cover?.let {
                    meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                    meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                }
                mediaSession?.setMetadata(meta.build())
                lastMetadataKey = metaKey
            }
        } catch (_: Exception) {}

        // 状态动作（尤其通知栏悬浮歌词）变化时立即重建通知；内容未变时只按心跳重发（被划掉的暂停通知仍会回来），
        // API 29 以下 MediaStyle 不读会话进度，进度秒数纳入 key 保持旧版进度条。
        try {
            val lyricEnabled = LyricFloat.isUserEnabled(this)
            val lyricLocked = lyricEnabled && LyricFloat.isLocked(this)
            val notifyKey = NotificationRefreshPolicy.notificationKey(
                title, artist, playing, lyricEnabled, lyricLocked, notificationCoverBitmap,
                progressSecond = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) pos / 1000L else -1L
            )
            val now = SystemClock.elapsedRealtime()
            if (NotificationRefreshPolicy.shouldNotify(notifyKey, lastNotificationKey, now, lastNotificationAtMs)) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFY_ID, buildNotification(title, artist, cover = notificationCoverBitmap))
                lastNotificationKey = notifyKey
                lastNotificationAtMs = now
            }
        } catch (_: Exception) {}
        // 桌面小组件与通知共用同一事实源推送（内容门在 WidgetUpdater 内，逐秒 tick 不产生流量）。
        try { xyz.cncnlp.soundwave.widget.WidgetUpdater.refreshFromService(this, notificationCoverBitmap) } catch (_: Exception) {}
        if (song?.cover.isNullOrBlank() || notificationCoverLoadToken == coverToken) return
        notificationCoverLoadToken = coverToken
        notificationCoverJob?.cancel()
        val requestService = this
        notificationCoverJob = notificationCoverScope.launch {
            val cover = kotlinx.coroutines.runInterruptible { loadCover(song?.cover) }
            // 封面文件已由 loadCover 落盘：在同一 IO 任务里把取色算进 CoverToneCache（64px 本地
            // 小图从磁盘缓存解码 + 聚类，不为取色新发网络请求），随后 tick 提交的
            // buildNotification 即可同批读到封面色，封面与颜色一次到位、不闪色。
            // 取色失败/无结果时 tonesFor 返回 null，通知保持珊瑚回落（旧行为）。
            if (cover != null && !song?.cover.isNullOrBlank()) {
                CoverToneCache.tonesFor(requestService, song?.cover)
            }
            tick.post {
                // IO 结果可能在发帖后、执行前变旧；所有可见写回都在主线程再次验证。
                if (!NotificationRefreshPolicy.mayCommitCover(
                        serviceIsCurrent = instance === requestService,
                        requestToken = coverToken,
                        currentToken = notificationCoverToken,
                        requestSongKey = coverSongKey,
                        currentSongKey = currentSong?.key(),
                        requestGeneration = coverGeneration,
                        currentGeneration = playGen.get()
                    )) return@post
                if (cover != null) {
                    notificationCoverBitmap = cover
                    lastMetadataKey = NotificationRefreshPolicy.metadataKey(title, artist, if (dur > 0) dur else -1, cover)
                    try {
                        mediaSession?.setMetadata(
                            MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (dur > 0) dur else -1)
                                // OEM 通知/锁屏有的只取 ART、有的只取 ALBUM_ART；异步首帧两项必须同时写。
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover)
                                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
                                .build()
                        )
                    } catch (_: Exception) {}
                }
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFY_ID, buildNotification(title, artist, cover ?: notificationCoverBitmap))
                    lastNotificationKey = "" // 下一次常规刷新按新封面重算 key
                    lastNotificationAtMs = SystemClock.elapsedRealtime()
                } catch (_: Exception) {}
                try { xyz.cncnlp.soundwave.widget.WidgetUpdater.refreshFromService(requestService, notificationCoverBitmap) } catch (_: Exception) {}
            }
        }
    }

    private fun buildNotification(title: String, artist: String, cover: Bitmap? = null): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = isPlayingNow()
        val lyricEnabled = LyricFloat.isUserEnabled(this)
        val lyricLocked = lyricEnabled && LyricFloat.isLocked(this)
        val duration = getDuration()
        val state = NotificationPresentation(
            channelId = CHANNEL_ID,
            title = title,
            artist = artist,
            playing = playing,
            durationMs = duration,
            positionMs = if (duration > 0L) getPosition() else 0L,
            lyricEnabled = lyricEnabled,
            lyricLocked = lyricLocked,
            cover = cover,
            accentColor = notificationAccentArgb(),
        )
        val controls = NotificationControls(
            content = contentPi,
            like = pi(ACTION_LIKE, 4),
            previous = pi(ACTION_PREV, 2),
            playPause = pi(if (playing) ACTION_PAUSE else ACTION_RESUME, 1),
            next = pi(ACTION_NEXT, 3),
            lyric = pi(ACTION_LYRIC_FLOAT, 5),
        )
        return notificationPresenter.build(state, controls, mediaSession?.sessionToken)
    }

    /**
     * 通知着色：当前歌曲封面已有取色结果（CoverToneCache——与全屏播放器背景、磨砂面同一套
     * 聚类取色）时用封面色；无封面、未算出或提取失败时回落品牌珊瑚，即旧行为的写死值。
     * 本函数只同步读缓存、零 IO；取色计算由封面加载任务在后台完成后写入缓存
     * （见 updateSessionAndNotify 的 notificationCoverJob），通知构建路径不触发计算、
     * 不进播放热路径。
     */
    private fun notificationAccentArgb(): Int {
        val cover = currentSong?.cover
        if (cover.isNullOrBlank()) return NotificationTint.FallbackArgb
        return NotificationTint.accentArgb(CoverToneCache.cached(cover))
    }

    private fun loadCover(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            // coverModel 只负责命中本地缓存/返回 URL，不会为通知栏下载远程封面。
            // 通知首次播放必须先 ensureCover，否则 largeIcon 一直为空。
            val local = CacheManager.ensureCover(this, url)
            if (local.startsWith("/") || local.startsWith("file:")) {
                val path = if (local.startsWith("file:")) java.net.URI(local).path else local
                val file = java.io.File(path)
                if (file.length() > BoundedCoverReader.MAX_BYTES) return null
                return NotificationCoverCodec.decode(file)
            }
            // A failed cache fetch must not retry through an unbounded, redirecting second client.
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Decoding only; input size cap, file ownership and song-generation checks remain in the service. */
    private fun decodeNotificationCover(bytes: ByteArray): Bitmap? = NotificationCoverCodec.decode(bytes)

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户只是回桌面/划掉任务，不是点“停止”。前台媒体服务必须继续，状态与通知由服务自身维持。
        updateSessionAndNotify()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // The process queue outlives this instance; sync can still await its accepted final writes.
        submitListenStats(null, listenStats.flushAll())
        releaseTransitionWake()
        recoveryScope.cancel()
        notificationCoverScope.cancel()
        val wasPlaying = isPlayingNow()
        // 进程被系统回收后重建服务时，静态 UI 回调尚未注册；保留最后真实状态，避免错误地持久为暂停。
        val snap = snapshot()
        try { PlaybackStore.save(applicationContext, snap.copy(isPlaying = wasPlaying)) } catch (_: Exception) {}
        tick.removeCallbacksAndMessages(null)
        try { audioFocusController?.release() } catch (_: Exception) {}
        audioFocusController = null
        try { mediaSession?.isActive = false; mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        fixedCrossfade.cancel()
        val abandonedEqualizer = equalizer
        equalizer = null
        try { abandonedEqualizer?.release() } catch (_: Exception) {}
        releaseTrackedPlayer(player)
        releaseTrackedPlayer(nextPlayer)
        crossfadePlayers.toList().forEach(::releaseTrackedPlayer)
        crossfadePlayers.clear()
        player = null
        nextPlayer = null
        instance = null
        val abandonedPending = synchronized(pendingPlayLock) { pendingPlay.also { pendingPlay = null } }
        abandonedPending?.cacheLease?.close()
        super.onDestroy()
    }
}
