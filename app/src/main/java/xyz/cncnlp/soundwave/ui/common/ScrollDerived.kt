package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * r656-G：“是否已滚离顶部”的派生状态。
 *
 * 直接在组合里读 `firstVisibleItemScrollOffset` 会让外层整页作用域在滚动的**每一帧**重组；
 * derivedStateOf 只在布尔值翻转时（离开/回到顶部各一次）通知重组。
 */
@Composable
fun LazyListState.rememberScrolledPastTop(thresholdPx: Int = 72): Boolean {
    val state = this
    val scrolled by remember(state, thresholdPx) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > thresholdPx }
    }
    return scrolled
}

/** 远距离回顶 / 定位时先瞬移到目标附近再短距离动画的阈值（行数）。 */
const val SMOOTH_SCROLL_JUMP_ITEMS = 8

/** Return-to-top and arbitrary-row location are separate policies.
 * animateScrollToItem itself teleports for long lists (100-item threshold in Compose 1.7).
 * A single call therefore does NOT establish a visually continuous return-to-top.
 */
internal suspend fun returnToTop(reduceMotion: Boolean, jump: suspend () -> Unit, animate: suspend () -> Unit) {
    if (reduceMotion) jump() else animate()
}

internal fun returnToTopFrameStep(remainingPx: Float, viewportPx: Float, seconds: Float): Float {
    if (remainingPx <= 0f || viewportPx <= 0f) return 0f
    val dt = seconds.coerceIn(1f / 240f, 1f / 20f)
    return min(remainingPx, min(viewportPx * 2f, max(1f, remainingPx * (1f - exp(-dt / 0.12f)))))
}

suspend fun LazyListState.smoothScrollToTop(reduceMotion: Boolean = false) =
    returnToTop(reduceMotion, { scrollToItem(0) }, {
        // One cancellable scroll transaction; only consumed pixel deltas, never a hidden
        // index snap or animateScrollToItem's teleport. Gesture/route cancellation still wins.
        scroll {
            var previous = withFrameNanos { it }
            while (canScrollBackward) {
                val now = withFrameNanos { it }
                val dt = (now - previous) / 1_000_000_000f
                previous = now
                val layout = layoutInfo
                val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                val average = layout.visibleItemsInfo.map { it.size }.average().takeUnless { it.isNaN() }?.toFloat() ?: 1f
                // This estimate sets speed only, not a destination. Recompute from actual
                // layout every frame so tall headers, heterogeneous rows and padding converge.
                val remaining = firstVisibleItemIndex * average + firstVisibleItemScrollOffset
                val step = returnToTopFrameStep(max(1f, remaining), max(1f, viewport), dt)
                if (scrollBy(-step) == 0f) break
            }
        }
    })

/** 同上，定位到任意行（定位当前歌曲 / 排序后归位）。 */
suspend fun LazyListState.smoothScrollToItem(index: Int, scrollOffset: Int = 0) {
    val target = index.coerceAtLeast(0)
    val first = firstVisibleItemIndex
    val jump = smoothScrollJumpIndex(first, target)
    if (jump != null) scrollToItem(jump)
    animateScrollToItem(target, scrollOffset)
}

/** 纯函数（单测覆盖）：需要先瞬移时返回瞬移到的行，否则 null。 */
fun smoothScrollJumpIndex(first: Int, target: Int, window: Int = SMOOTH_SCROLL_JUMP_ITEMS): Int? = when {
    first - target > window -> target + window
    target - first > window -> (target - window).coerceAtLeast(0)
    else -> null
}
