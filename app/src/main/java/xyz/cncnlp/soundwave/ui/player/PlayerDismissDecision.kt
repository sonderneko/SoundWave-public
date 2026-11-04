package xyz.cncnlp.soundwave.ui.player

import android.os.SystemClock
import android.view.View
import xyz.cncnlp.soundwave.ui.common.AppHaptics

internal enum class PlayerDismissDecision {
    RETURN_TO_PREVIOUS_MODE,
    CLOSE_PLAYER,
    RESET_OFFSET
}

/** SW-12-06：全屏播放器下滑甩动关闭阈值（dp/s）；达到该速度时即使位移未过阈值也关闭。 */
internal const val PLAYER_DISMISS_VELOCITY_DP_PER_SEC = 1200f

/** SW-12-06：迷你条上滑打开阈值——上甩速度（dp/s）或位移占屏高比例，任一满足即打开。 */
internal const val MINI_PLAYER_EXPAND_OPEN_VELOCITY_DP_PER_SEC = 1000f
internal const val MINI_PLAYER_EXPAND_OPEN_FRACTION = 0.30f

internal fun playerDismissDecision(
    offsetPx: Float,
    thresholdPx: Float,
    immersiveMode: Boolean,
    lyricPage: Boolean,
    velocityDownPxPerSec: Float = 0f,
    velocityThresholdPxPerSec: Float = Float.POSITIVE_INFINITY
): PlayerDismissDecision {
    // SW-12-06：速度达标与位移达标同权（子模式下同样先回上一模式，不直接关整页）。
    val thresholdReached = offsetPx >= thresholdPx || velocityDownPxPerSec >= velocityThresholdPxPerSec
    return when {
        thresholdReached && (immersiveMode || lyricPage) -> PlayerDismissDecision.RETURN_TO_PREVIOUS_MODE
        thresholdReached -> PlayerDismissDecision.CLOSE_PLAYER
        else -> PlayerDismissDecision.RESET_OFFSET
    }
}

/**
 * SW-12-06（纯函数）：迷你条松手时是否展开播放器。
 * [velocityUpPxPerSec] 为向上速度（px/s，正值向上）；速度阈值按 [density] 由 dp/s 换算。
 */
internal fun miniPlayerExpandShouldOpen(
    dragUpPx: Float,
    screenHeightPx: Float,
    velocityUpPxPerSec: Float,
    density: Float
): Boolean {
    if (screenHeightPx <= 0f) return false
    return velocityUpPxPerSec >= MINI_PLAYER_EXPAND_OPEN_VELOCITY_DP_PER_SEC * density ||
        dragUpPx >= screenHeightPx * MINI_PLAYER_EXPAND_OPEN_FRACTION
}

/** SW-12-06：迷你条开/关阈值（位移口径）——上滑位移达到屏高 30% 视为越过打开阈值。 */
internal fun miniPlayerExpandThresholdCrossed(dragUpPx: Float, screenHeightPx: Float): Boolean =
    screenHeightPx > 0f && dragUpPx >= screenHeightPx * MINI_PLAYER_EXPAND_OPEN_FRACTION

/**
 * SW-12-06：播放器进场起始偏移。迷你条跟手松手时已把同一 [PlayerDismissOffsetHolder] 预置为
 * 「屏高 − 上滑位移」，进场从该偏移续滑；无预置（点按打开，偏移 0）时仍从全屏高滑入（旧行为）。
 */
internal fun playerSheetEnterStartOffsetPx(fullHeightPx: Int, seededOffsetPx: Float): Int {
    val height = fullHeightPx.coerceAtLeast(0)
    return if (seededOffsetPx > 0f) seededOffsetPx.toInt().coerceIn(0, height) else height
}

/**
 * SW-12-06：FullPlayer 下滑手势的速度采样 + 阈值触感（与 DesignSheet 同一模式）。
 * onDismissDrag 每次位移更新时调用 [onDrag]：内部做指数平滑的速度估算（停顿 >120ms 后读数归零，
 * 避免「拖到位移后停住再松手」被旧速度误判为甩动），并在越过 / 退回位移阈值时各发一次
 * [AppHaptics.thresholdCrossed]（同一手势内状态不变不重复）。
 */
internal class PlayerDismissVelocityTracker {
    private var lastTimeMs = 0L
    private var lastOffsetPx = 0f
    private var velocityPxPerSec = 0f
    private var pastThreshold = false

    fun onDrag(offsetPx: Float, thresholdPx: Float, view: View) {
        val now = SystemClock.uptimeMillis()
        if (lastTimeMs > 0L && now > lastTimeMs) {
            val instant = (offsetPx - lastOffsetPx) / (now - lastTimeMs).toFloat() * 1000f
            velocityPxPerSec = velocityPxPerSec * 0.5f + instant * 0.5f
        }
        lastOffsetPx = offsetPx
        lastTimeMs = now
        val crossed = thresholdPx > 0f && offsetPx >= thresholdPx
        if (crossed != pastThreshold) {
            pastThreshold = crossed
            AppHaptics.thresholdCrossed(view, crossed)
        }
    }

    /** 当前向下滑速度估算（px/s）；距最后一次位移更新过久视为已停手，返回 0。 */
    fun velocityDownPxPerSec(): Float =
        if (SystemClock.uptimeMillis() - lastTimeMs > 120L) 0f else velocityPxPerSec
}
