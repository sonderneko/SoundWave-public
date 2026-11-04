package xyz.cncnlp.soundwave.playback

/**
 * r657：播放故障恢复的统一决策表。前台 PlaybackController 与无界面的 PlayerService 共用同一实例，
 * 保证“断流 → 重新取链（续播原进度）→ 智能换源 → 跳下一首 → 连续失败才停”在两条路径上完全一致。
 *
 * 之前的行为：
 *  - 播放中途报错（CDN 链接过期 / 网络切换）一律当成“脚本坏链”：隔离命中脚本 + forceCross 换源，
 *    并且从 0 秒重播；换源失败后只 Toast，不再推进 → 表现为“播着播着就不播了”。
 *  - 自动切下一首时取链失败同样只 Toast 停在原地 → 表现为“播完一首不会继续播放”。
 *  - Activity 被回收后 onError 为空，Service 直接吞掉错误。
 */
enum class RecoveryAction {
    /** 已正常播放过一段：多半是直链过期或网络抖动，原平台重新取链并从原进度续播。 */
    REFRESH_SAME_SOURCE,
    /** 起播即失败或重新取链后仍失败：隔离命中脚本并走智能换源（跨脚本 / 跨平台）。 */
    SWITCH_SOURCE,
    /** 本曲已用尽恢复预算（或报错时已接近曲尾）：跳到下一首。 */
    SKIP_NEXT,
    /** 连续多首都无法播放：多半是断网或音源整体失效，停止推进避免把整张歌单刷完。 */
    STOP,
}

internal enum class ResolveFailureDisposition { ADVANCE, STOP }

/** An automatic failure may advance only when the policy allows it and a next queue entry exists. */
internal fun resolveFailureDisposition(action: RecoveryAction, queueSize: Int): ResolveFailureDisposition =
    if (action == RecoveryAction.SKIP_NEXT && queueSize > 1) {
        ResolveFailureDisposition.ADVANCE
    } else {
        ResolveFailureDisposition.STOP
    }

data class RecoveryDecision(
    val action: RecoveryAction,
    /** 重新起播时的定位；0 表示从头。 */
    val resumeAtMs: Long,
    /** 本曲第几次播放故障（从 1 开始）；resolve 失败决策时为 0。 */
    val attempt: Int,
    /** 曲尾附近断流：等同播完，静默切下一首。 */
    val nearEnd: Boolean = false,
)

class PlaybackRecoveryPolicy(
    private val maxConsecutiveSkips: Int = DEFAULT_MAX_CONSECUTIVE_SKIPS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var songKey: String = ""
    private var attempts: Int = 0
    private var lastAction: RecoveryAction? = null
    private var lastFailureAt: Long = 0L
    private var consecutiveSkips: Int = 0

    /** ExoPlayer 报错时调用。positionMs/durationMs 取自出错的那个播放器。 */
    @Synchronized
    fun onPlaybackError(songKey: String, positionMs: Long, durationMs: Long): RecoveryDecision {
        val now = clock()
        if (songKey != this.songKey || now - lastFailureAt > ATTEMPT_WINDOW_MS) {
            // 换了歌，或同一首已经稳定播放了很久（长歌中途第二次过期）：重新给完整预算。
            this.songKey = songKey
            attempts = 0
            lastAction = null
        }
        lastFailureAt = now
        attempts++
        // 曲尾附近断流（CDN 提前断开 / Content-Length 不符）：等同于播完，直接下一首，不消耗跳过预算。
        if (isNearEnd(positionMs, durationMs)) {
            lastAction = RecoveryAction.SKIP_NEXT
            return RecoveryDecision(RecoveryAction.SKIP_NEXT, 0L, attempts, nearEnd = true)
        }
        val resumeAt = resumePosition(positionMs, durationMs)
        val action = when {
            attempts == 1 && isTransient(positionMs) -> RecoveryAction.REFRESH_SAME_SOURCE
            attempts == 1 -> RecoveryAction.SWITCH_SOURCE
            attempts == 2 && lastAction == RecoveryAction.REFRESH_SAME_SOURCE -> RecoveryAction.SWITCH_SOURCE
            else -> skipOrStop()
        }
        lastAction = action
        return RecoveryDecision(action, if (action == RecoveryAction.SKIP_NEXT || action == RecoveryAction.STOP) 0L else resumeAt, attempts)
    }

    /** 自动推进（自然播完 / 下一首 / 恢复重试）时取链彻底失败。 */
    @Synchronized
    fun onResolveFailure(songKey: String): RecoveryDecision {
        this.songKey = songKey
        lastAction = skipOrStop()
        return RecoveryDecision(lastAction!!, 0L, 0)
    }

    /** 某首歌已稳定播放（≥ [HEALTHY_PLAYED_MS]）：清空连续跳过计数。 */
    @Synchronized
    fun onHealthyPlayback(songKey: String) {
        consecutiveSkips = 0
        if (songKey != this.songKey) {
            this.songKey = songKey
            attempts = 0
            lastAction = null
        }
    }

    /** 用户明确点歌/切歌：之前的失败链与当前意图无关。 */
    @Synchronized
    fun onUserIntent() {
        consecutiveSkips = 0
        attempts = 0
        lastAction = null
        songKey = ""
    }

    @Synchronized
    internal fun consecutiveSkipsForTests(): Int = consecutiveSkips

    private fun skipOrStop(): RecoveryAction =
        if (consecutiveSkips < maxConsecutiveSkips) {
            consecutiveSkips++
            RecoveryAction.SKIP_NEXT
        } else {
            RecoveryAction.STOP
        }

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_SKIPS = 3
        /** 播放超过该时长后再报错，视为链接过期/网络抖动，而不是“脚本给了坏链”。 */
        const val TRANSIENT_MIN_PLAYED_MS = 5_000L
        const val HEALTHY_PLAYED_MS = 5_000L
        const val TAIL_TOLERANCE_MS = 3_000L
        /** 同一首两次故障相隔超过该窗口时，重新给完整预算（长歌第二次直链过期）。 */
        const val ATTEMPT_WINDOW_MS = 90_000L

        // A fixed tail window must not classify a short track's startup as natural completion.
        private fun isNearEnd(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0L && positionMs > 0L &&
                positionMs >= durationMs - minOf(TAIL_TOLERANCE_MS, durationMs / 2L)

        fun isTransient(positionMs: Long): Boolean = positionMs >= TRANSIENT_MIN_PLAYED_MS

        fun resumePosition(positionMs: Long, durationMs: Long): Long = when {
            positionMs < TRANSIENT_MIN_PLAYED_MS -> 0L
            isNearEnd(positionMs, durationMs) -> 0L
            // 回退 2 秒，补上被丢弃的解码缓冲，避免续播时听感上“跳了一小段”。
            else -> (positionMs - 2_000L).coerceAtLeast(0L)
        }
    }
}

/**
 * r658c D#1：本地文件失败时不能"同源重新取链"或"换源"——两者都会把本地歌曲送进脚本取链。
 * 本地歌只保留 SKIP_NEXT / STOP 语义（连续失败上限由同一策略计数）。
 */
fun localRecoveryAction(isLocal: Boolean, action: RecoveryAction): RecoveryAction = when {
    !isLocal -> action
    action == RecoveryAction.REFRESH_SAME_SOURCE || action == RecoveryAction.SWITCH_SOURCE -> RecoveryAction.SKIP_NEXT
    else -> action
}
