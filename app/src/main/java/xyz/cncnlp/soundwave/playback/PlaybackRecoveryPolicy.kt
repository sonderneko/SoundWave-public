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
    /** PB-F2：本次 STOP 由连播失败率熔断触发（UI 据此给熔断专属提示）。 */
    val circuitTripped: Boolean = false,
)

/**
 * PB-F2 连播失败熔断（Ceru Music 同型止损，E-2）：既有连续跳过上限只数
 * 「跳过」一种动作，重取链/换源重试不吃跳过预算，混合失败链仍可能长时间
 * 自动推进。本熔断在自动推进的失败事实上独立计账：
 * - 窗口 = 自上次成功起播以来最近 [WINDOW_SIZE] 次自动尝试（滑动窗口）；
 * - 样本数达到 [MIN_SAMPLES] 且失败率 ≥ [FAILURE_RATE_THRESHOLD]（30%）即
 *   熔断，停自动推进；熔断后持续记录也不自愈，只能由成功起播/用户意图重置；
 * - 成功起播即重置计数（[recordSuccess] 清窗 + 解熔断）——与
 *   [PlaybackRecoveryPolicy.onHealthyPlayback] / onUserIntent 的既有重置点同点。
 * 纯内存、无 IO，可独立单测。
 */
class AutoAdvanceCircuitBreaker {
    private val attemptsWindow = ArrayDeque<Boolean>() // true = 失败
    private var tripped: Boolean = false

    /** 记一次自动推进失败；返回是否已熔断（含此前已熔断）。 */
    @Synchronized
    fun recordFailure(): Boolean {
        attemptsWindow.addLast(true)
        while (attemptsWindow.size > WINDOW_SIZE) attemptsWindow.removeFirst()
        if (!tripped && attemptsWindow.size >= MIN_SAMPLES && failureRate() >= FAILURE_RATE_THRESHOLD) {
            tripped = true
        }
        return tripped
    }

    /** 成功起播：清窗并解熔断（计数重置）。 */
    @Synchronized
    fun recordSuccess() {
        attemptsWindow.clear()
        tripped = false
    }

    @Synchronized
    fun isTripped(): Boolean = tripped

    @Synchronized
    fun failureRate(): Double =
        if (attemptsWindow.isEmpty()) 0.0 else attemptsWindow.count { it }.toDouble() / attemptsWindow.size

    @Synchronized
    fun sampleCount(): Int = attemptsWindow.size

    companion object {
        /** 失败率阈值：自动尝试失败占比达到 30% 即止损。 */
        const val FAILURE_RATE_THRESHOLD = 0.30
        /** 滑动窗口：只看最近 10 次自动尝试。 */
        const val WINDOW_SIZE = 10
        /** 最小样本：不足 4 次不熔断，避免单首歌的偶发抖动误停整单。 */
        const val MIN_SAMPLES = 4
    }
}

class PlaybackRecoveryPolicy(
    private val maxConsecutiveSkips: Int = DEFAULT_MAX_CONSECUTIVE_SKIPS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var songKey: String = ""
    private var attempts: Int = 0
    private var lastAction: RecoveryAction? = null
    private var lastFailureAt: Long = 0L
    private var consecutiveSkips: Int = 0

    /** PB-F2：自动推进失败率熔断，与连续跳过计数并行计账。 */
    val autoAdvanceBreaker = AutoAdvanceCircuitBreaker()

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
        // PB-F2：真正要自动跳走的失败才进熔断窗口（重取/换源是同曲重试，不算连播尝试）；
        // 熔断时把本次 SKIP_NEXT 就地升级为 STOP，不再多跳一首。
        if (action == RecoveryAction.SKIP_NEXT && autoAdvanceBreaker.recordFailure()) {
            lastAction = RecoveryAction.STOP
            return RecoveryDecision(RecoveryAction.STOP, 0L, attempts, circuitTripped = true)
        }
        lastAction = action
        return RecoveryDecision(action, if (action == RecoveryAction.SKIP_NEXT || action == RecoveryAction.STOP) 0L else resumeAt, attempts)
    }

    /** 自动推进（自然播完 / 下一首 / 恢复重试）时取链彻底失败。 */
    @Synchronized
    fun onResolveFailure(songKey: String): RecoveryDecision {
        this.songKey = songKey
        // PB-F2：取链失败是自动连播的一次失败尝试，先过熔断账，熔断即停。
        if (autoAdvanceBreaker.recordFailure()) {
            lastAction = RecoveryAction.STOP
            return RecoveryDecision(RecoveryAction.STOP, 0L, 0, circuitTripped = true)
        }
        lastAction = skipOrStop()
        return RecoveryDecision(lastAction!!, 0L, 0)
    }

    /** 某首歌已稳定播放（≥ [HEALTHY_PLAYED_MS]）：清空连续跳过计数。 */
    @Synchronized
    fun onHealthyPlayback(songKey: String) {
        // PB-F2：成功起播（稳定播放）即重置熔断计数。
        autoAdvanceBreaker.recordSuccess()
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
        autoAdvanceBreaker.recordSuccess()
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
