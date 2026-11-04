package xyz.cncnlp.soundwave.ui.common

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.ui.motion.MotionTokens

/**
 * r656-G：全局统一的按压反馈令牌。
 *
 * - 按下：高刚度弹簧，**手指落下的同一帧**开始缩放（不等 clickable 在可滚动容器里约 100ms 的按压延迟）；
 * - 松开：略带回弹的中刚度弹簧回到 1；
 * - 快速轻点也至少保持 [MIN_PRESS_VISIBLE_MS] 的按下态，保证每次点击都“看得见”；
 * - 系统/应用“减少动效”时不缩放（只保留组件自身的涟漪/颜色反馈）。
 */
object PressMotion {
    // P1 / SW-12-01 + B.2：按压比例全仓只许三档——Row 0.98 / Card 0.96 / Icon 0.92，禁止再写裸数字。
    // 全局默认指示 PressGlowIndication.PRESS_SCALE 0.965 是用户已确认值，视为 Card 档的默认指示变体，保留不动。
    /** 卡片、封面、列表行的标准按下比例（Card 档，历史名保留兼容既有调用）。 */
    const val SCALE_DEFAULT = 0.96f
    /** Card 档（B.2 命名），与 [SCALE_DEFAULT] 同值。 */
    const val SCALE_CARD = SCALE_DEFAULT
    /** 整行列表等大面积元素用更轻的比例，避免整屏“晃动”（Row 档）。 */
    const val SCALE_ROW = 0.98f
    /** 圆形图标按钮/播放键等小目标可以更明显（Icon 档）。 */
    const val SCALE_ICON = 0.92f
    internal const val MIN_PRESS_VISIBLE_MS = 70L

    // P1 / SW-12-05：弹簧唯一出口为 MotionTokens（Press = 1.0/1800、Release = 0.55/400，数值与原实现一致）。
    val pressSpec: AnimationSpec<Float> = MotionTokens.Spring.PressSpec
    val releaseSpec: AnimationSpec<Float> = MotionTokens.Spring.ReleaseSpec
    /** 仍使用 animate*AsState 的旧组件统一采用的按压规格（替代散落的 tween(90/100)）。0.7/1400 无对应 MotionTokens 条目，历史值保留。 */
    val stateSpec: AnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 1400f)

    fun target(pressed: Boolean, scale: Float, reduceMotion: Boolean): Float =
        if (pressed && !reduceMotion) scale else 1f
}

/** 触感强度：只在关键操作（播放/暂停、上一首/下一首、收藏）上使用。 */
enum class PressHaptic { None, Light }

/**
 * 由设计主题（SoundwaveDesignTheme）提供，避免每个列表行各自注册系统设置监听；
 * 未提供处（根层底栏等）退回进程级缓存读取（≤1s 过期）。
 */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

internal object ReduceMotionCache {
    @Volatile private var value = false
    @Volatile private var readAt = 0L
    fun get(context: android.content.Context): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (readAt == 0L || now - readAt > 1000L) {
            value = xyz.cncnlp.soundwave.MotionPolicy.isReduceMotionEnabled(context)
            readAt = now
        }
        return value
    }
}

/**
 * 按压缩放。放在 Modifier 链上 **背景/裁剪/阴影之前**，整块元素一起缩放：
 * `Modifier.pressScale().clip(shape).background(...).clickable { }`。
 *
 * 只观察指针、不消费事件，因此与 clickable/combinedClickable/拖拽/滚动共存；移动超过 touch slop
 * （即用户在滚动列表）立即回弹。缩放值只在 graphicsLayer 的绘制阶段读取，不触发重组。
 *
 * P1 / SW-12-01 规则（一个元素只许一个缩放来源）：本修饰符自身**不安装任何 indication**，
 * 缩放完全由它负责；调用方给同一元素挂 clickable 时必须显式 `indication = null`（并自备
 * interactionSource），否则会与全局默认 [PressGlowIndication] 的 0.965 缩放叠加成双重缩放。
 */
fun Modifier.pressScale(
    scale: Float = PressMotion.SCALE_DEFAULT,
    haptic: PressHaptic = PressHaptic.None,
    enabled: Boolean = true
): Modifier = if (!enabled) this else composed {
    val view = LocalView.current
    val reduceMotion = LocalReduceMotion.current ?: ReduceMotionCache.get(view.context)
    val animatable = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    this
        .pointerInput(scale, haptic, reduceMotion) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val downAt = System.currentTimeMillis()
                if (haptic == PressHaptic.Light) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                if (!reduceMotion) scope.launch { animatable.animateTo(scale, PressMotion.pressSpec) }
                val slop = viewConfiguration.touchSlop
                var cancelledByMove = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - down.position).getDistance() > slop) { cancelledByMove = true; break }
                }
                scope.launch {
                    if (!cancelledByMove) {
                        val held = System.currentTimeMillis() - downAt
                        if (held < PressMotion.MIN_PRESS_VISIBLE_MS) delay(PressMotion.MIN_PRESS_VISIBLE_MS - held)
                    }
                    animatable.animateTo(1f, PressMotion.releaseSpec)
                }
            }
        }
        .graphicsLayer {
            val s = animatable.value
            scaleX = s
            scaleY = s
        }
}

/**
 * r657 修复（审计第 21 项）：ripple-glow——从触点向外扩散的柔光圆（原型 motion.js rippleGlow：主按钮白 55%、选项行珊瑚 22%，360ms）。
 * 只观察指针、不消费事件；半径与透明度在绘制阶段读取，不触发重组。「简洁动效」时不绘制。
 * P1 注记：此处 360ms 是原型已确认值，冻结不动；与 [PressGlowIndication] 光晕的 520ms（B.2 Glow）是两套独立实现，不要混同。
 */
object RippleGlow {
    const val DURATION_MS = 360
    /** 纯函数：进度 → (半径占最大半径比例, 透明度系数)。先快后慢扩散，同时线性淡出。 */
    fun frame(p: Float): Pair<Float, Float> {
        val t = p.coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return (0.15f + 0.85f * eased) to (1f - t)
    }
}

fun Modifier.rippleGlow(color: androidx.compose.ui.graphics.Color, enabled: Boolean = true): Modifier = if (!enabled) this else composed {
    val view = LocalView.current
    val reduceMotion = LocalReduceMotion.current ?: ReduceMotionCache.get(view.context)
    val progress = remember { Animatable(1f) }
    val origin = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val scope = rememberCoroutineScope()
    if (reduceMotion) return@composed this
    this
        .pointerInput(color) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                origin.value = down.position
                scope.launch {
                    progress.snapTo(0f)
                    progress.animateTo(1f, androidx.compose.animation.core.tween(RippleGlow.DURATION_MS, easing = androidx.compose.animation.core.LinearEasing))
                }
            }
        }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p < 1f) {
                val o = origin.value
                val maxR = kotlin.math.hypot(kotlin.math.max(o.x, size.width - o.x), kotlin.math.max(o.y, size.height - o.y))
                val (r, a) = RippleGlow.frame(p)
                clipRect { drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(color.copy(alpha = color.alpha * a), color.copy(alpha = 0f)), center = o, radius = (maxR * r).coerceAtLeast(1f)
                    ),
                    radius = maxR * r, center = o
                ) }
            }
        }
}
