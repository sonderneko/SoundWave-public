package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 修复（审计第 12 项）：原型 motion.js dissolve——删除 / 清空 / 不喜欢 / 取消喜欢 / 移出队列时，
 * 行内容从左往右被「扫掉」，扫描线处迸出细粒子向右上飘散（≤ [MAX_PARTICLES] 颗，600–1100ms），结束后回调由调用方移除该行
 * （LazyColumn animateItem 负责 250ms 收起行高）。「简洁动效」时退化为 250ms 缩到 0.94 并淡出。
 */
object DissolveMotion {
    const val MAX_PARTICLES = 900
    const val MIN_MS = 600
    const val MAX_MS = 1100
    const val REDUCED_MS = 250

    /** 纯函数：按行宽（px）给出时长，宽行更久，限定在 600–1100ms。 */
    fun durationFor(widthPx: Float, density: Float): Int =
        (MIN_MS + (widthPx / density / 412f) * 500f).toInt().coerceIn(MIN_MS, MAX_MS)

    /** 纯函数：粒子数（每 4dp² 列一颗，封顶 900）。 */
    fun particleCount(widthPx: Float, heightPx: Float, density: Float): Int =
        ((widthPx / density) * (heightPx / density) / 22f).toInt().coerceIn(60, MAX_PARTICLES)
}

@Stable
class ParticleDissolveState internal constructor() {
    internal val progress = Animatable(0f)
    internal var running by mutableIntStateOf(0)
    internal var onDone: (() -> Unit)? = null
    internal var request: (() -> Unit)? = null
    internal var restoreAfter = false

    /**
     * 播放消散，结束后调用 [then]（通常是真正删除数据）。
     * [restore] = true：回调后立即恢复显示（同一帧里行已被移除则看不到；没被移除——例如删除被拒绝——则内容回来，不会留下空行）。
     * 带 animateItem 淡出的列表应保持 false，避免淡出期间内容闪回。
     */
    fun dissolve(restore: Boolean = false, then: () -> Unit) { onDone = then; restoreAfter = restore; request?.invoke() ?: then() }
}

@Composable
fun rememberParticleDissolveState(): ParticleDissolveState = remember { ParticleDissolveState() }

fun Modifier.particleDissolve(state: ParticleDissolveState, color: Color, accent: Color = Color(0xFFFF5C45)): Modifier = composedDissolve(state, color, accent)

private fun Modifier.composedDissolve(state: ParticleDissolveState, color: Color, accent: Color): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    state.request = {
        scope.launch {
            state.running++
            val ms = if (reduce) DissolveMotion.REDUCED_MS else DissolveMotion.durationFor(widthPx.toFloat(), density)
            state.progress.snapTo(0f)
            state.progress.animateTo(1f, tween(ms, easing = LinearEasing))
            state.onDone?.invoke(); state.onDone = null
            if (state.restoreAfter) { state.running = 0; state.progress.snapTo(0f) }
        }
    }
    val active = state.running > 0
    this
        .graphicsLayer {
            if (active && reduce) {
                val p = state.progress.value
                alpha = 1f - p; scaleX = 1f - 0.06f * p; scaleY = 1f - 0.06f * p
            }
        }
        .drawWithContent {
            widthPx = size.width.toInt()
            if (!active || reduce) { drawContent(); return@drawWithContent }
            val p = state.progress.value
            // 扫描线从 -10% 走到 110%：左侧已消散，右侧仍是原内容。
            val sweep = size.width * (-0.1f + 1.2f * p)
            clipRect(left = sweep.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
            val n = DissolveMotion.particleCount(size.width, size.height, density)
            val rnd = kotlin.random.Random(n)
            for (i in 0 until n) {
                val bx = rnd.nextFloat() * size.width
                val by = rnd.nextFloat() * size.height
                val born = (bx / size.width + 0.1f) / 1.2f  // 扫描线经过该点的时刻
                val age = (p - born) / 0.45f
                if (age <= 0f || age >= 1f) continue
                val drift = age * (18f + rnd.nextFloat() * 36f) * density
                val pos = Offset(bx + drift * 0.9f, by - drift * (0.6f + rnd.nextFloat() * 0.5f))
                val tint = (if (i % 7 == 0) accent else color).copy(alpha = (1f - age) * (0.55f + 0.45f * rnd.nextFloat()))
                drawCircle(tint, radius = (0.6f + rnd.nextFloat() * 1.1f) * density * (1f - 0.5f * age), center = pos)
            }
        }
}

/** 非扩展形式（调用方无需新增 import）。 */
fun particleDissolveModifier(state: ParticleDissolveState, color: Color, accent: Color = Color(0xFFFF5C45)): Modifier =
    Modifier.particleDissolve(state, color, accent)
