package xyz.cncnlp.soundwave.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** Click-time source snapshot; the only flying renderer is SharedCoverOverlayRoot. */
object SharedCoverTransition {
    const val DURATION_MS = 420
    const val ARM_WINDOW_MS = 1500L
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private val sources = HashMap<String, LayoutCoordinates>()
    private val arms = SharedCoverArms()
    internal val generation = mutableLongStateOf(0L)

    @Synchronized internal fun report(key: String, coordinates: LayoutCoordinates) {
        if (key.isBlank()) return
        if (sources.size > 96 && key !in sources) sources.clear()
        sources[key] = coordinates
    }

    /**
     * r658c #2（用户 10-19）：点击甄选歌单 / 音乐库歌单 / 搜索结果歌单 / 专辑 / 新碟首发等封面进入详情时，
     * 不再做共享封面放大（返回时也不缩回），统一为普通详情推入 / 推出。所有入口都经 [arm] 与根层反向飞行，
     * 这里一处关闭即覆盖全部入口；保留实现以便日后恢复。
     */
    const val FLIGHT_ENABLED = false

    @Synchronized fun arm(key: String, now: Long = SystemClock.uptimeMillis()) {
        if (!FLIGHT_ENABLED) return
        // Detached/offscreen/cropped sources must not revive an old bounds cache.
        generation.longValue = arms.arm(key, visibleSourceRect(key), now)
    }

    /** 来源完整可见（未滚出、未被裁剪）时的窗口坐标；否则 null。 */
    @Synchronized internal fun visibleSourceRect(key: String): Rect? = sources[key]?.takeIf { it.isAttached }?.let { c ->
        val visible = c.boundsInWindow()
        val full = Rect(c.localToWindow(Offset.Zero), c.localToWindow(Offset(c.size.width.toFloat(), c.size.height.toFloat())))
        full.takeIf { visible.width > 0f && visible.height > 0f &&
            visible.left <= full.left + 1f && visible.top <= full.top + 1f &&
            visible.right >= full.right - 1f && visible.bottom >= full.bottom - 1f }
    }

    /** 反向飞行每帧读取来源的实时窗口坐标（底页复位时跟随移动）。 */
    @Synchronized internal fun sourceRect(key: String): Rect? = sources[key]?.takeIf { it.isAttached }?.let { c ->
        Rect(c.localToWindow(Offset.Zero), c.localToWindow(Offset(c.size.width.toFloat(), c.size.height.toFloat())))
    }

    @Synchronized internal fun takeRequest(key: String, now: Long = SystemClock.uptimeMillis()) = arms.take(key, now)

    /** Compatibility geometry helper; no child transform uses this anymore. */
    fun frame(origin: Rect, target: Rect, p: Float): FloatArray {
        val rect = sharedCoverFlightRect(origin, target, p)
        return floatArrayOf(if (target.width > 0f) rect.width / target.width else 1f,
            if (target.height > 0f) rect.height / target.height else 1f,
            rect.left - target.left, rect.top - target.top)
    }
}

val LocalSharedCoverKey = compositionLocalOf { "" }

fun Modifier.sharedCoverSource(key: String): Modifier =
    if (key.isBlank()) this else onGloballyPositioned { SharedCoverTransition.report(key, it) }

fun SharedCoverSourceModifier(key: String): Modifier = Modifier.sharedCoverSource(key)

/** Stationary slot: capture the real shaped artwork once per draw, never translate this child. */
fun Modifier.sharedCoverTarget(key: String, targetCoordinates: () -> LayoutCoordinates?): Modifier =
    if (key.isBlank()) this else composed { sharedCoverOverlayTarget(key, targetCoordinates) }
