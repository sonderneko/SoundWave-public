package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.MotionPolicy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * r656：克制的一次性粒子特效（Canvas 绘制，无额外依赖）。
 *
 * - 只在 [ParticleBurstState.burst] 被调用后播放一次（约 0.7s 的有限 Animatable），结束后不再重绘；
 *   不使用无限动画，Compose UI 测试可正常进入空闲。
 * - 系统 / 应用内「减少动效」开启时 burst 直接忽略（[MotionPolicy.rememberReduceMotion]）。
 * - 进度只在 draw 阶段读取，动画期间仅重绘、不触发重组。
 */
@Stable
class ParticleBurstState internal constructor() {
    internal var trigger by mutableIntStateOf(0)
        private set

    /** 播放一次粒子。减少动效时由修饰符忽略。 */
    fun burst() { trigger++ }
}

@Composable
fun rememberParticleBurstState(): ParticleBurstState = remember { ParticleBurstState() }

enum class ParticleStyle {
    /** 小圆点 + 外扩细环（播放键）。 */
    Ring,
    /** 小心形与圆点（喜欢）。 */
    Hearts,
    /** 四角星闪（下拉刷新到位）。 */
    Sparkles
}

private class Particle(val angle: Float, val speed: Float, val size: Float, val spin: Float, val alt: Boolean)

private fun particlesFor(seed: Int, count: Int): List<Particle> {
    val rnd = kotlin.random.Random(seed * 7919 + count)
    val base = rnd.nextFloat() * 2f * PI.toFloat()
    return List(count) { i ->
        val slice = 2f * PI.toFloat() / count
        Particle(
            angle = base + slice * i + (rnd.nextFloat() - 0.5f) * slice * 0.7f,
            speed = 0.62f + rnd.nextFloat() * 0.38f,
            size = 0.7f + rnd.nextFloat() * 0.6f,
            spin = (rnd.nextFloat() - 0.5f) * 120f,
            alt = i % 3 == 0
        )
    }
}

/** 纯函数：给定进度 0..1 计算粒子位移比例（缓出）与透明度，便于单测。 */
internal fun particleTravel(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return 1f - (1f - p) * (1f - p) * (1f - p)
}

internal fun particleAlpha(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (p < 0.15f) p / 0.15f else (1f - (p - 0.15f) / 0.85f).let { it * it }.coerceIn(0f, 1f)
}

/**
 * 在当前组件中心向外迸发粒子；粒子画在组件内容之下（[behind] = true）或之上，可超出组件边界。
 */
fun Modifier.particleBurst(
    state: ParticleBurstState,
    color: Color,
    secondary: Color = color,
    style: ParticleStyle = ParticleStyle.Ring,
    count: Int = 12,
    radius: Dp = 44.dp,
    particleSize: Dp = 3.dp,
    durationMs: Int = 720,
    behind: Boolean = true
): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val progress = remember { Animatable(1f) }
    var seed by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.trigger) {
        if (state.trigger == 0 || reduce) return@LaunchedEffect
        seed = state.trigger
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = LinearOutSlowInEasing))
    }
    val particles = remember(seed, count) { particlesFor(seed, count) }
    drawWithContent {
        val p = progress.value
        val active = seed != 0 && p < 1f
        if (!behind) drawContent()
        if (active) drawBurst(p, particles, color, secondary, style, radius.toPx(), particleSize.toPx())
        if (behind) drawContent()
    }
}

private fun DrawScope.drawBurst(
    p: Float, particles: List<Particle>, color: Color, secondary: Color,
    style: ParticleStyle, radiusPx: Float, sizePx: Float
) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val start = size.minDimension * 0.42f
    val travel = particleTravel(p)
    val alpha = particleAlpha(p)
    if (style == ParticleStyle.Ring) {
        drawCircle(
            color.copy(alpha = 0.45f * (1f - p)), radius = start + (radiusPx - start) * 0.75f * travel, center = c,
            style = Stroke(width = sizePx * 0.6f * (1f - p) + 0.5f)
        )
    }
    particles.forEach { pt ->
        val d = start + (radiusPx - start) * pt.speed * travel
        val pos = Offset(c.x + cos(pt.angle) * d, c.y + sin(pt.angle) * d)
        val s = sizePx * pt.size * (1f - 0.45f * p)
        val tint = (if (pt.alt) secondary else color).copy(alpha = alpha * (if (pt.alt) 0.85f else 1f))
        when {
            style == ParticleStyle.Hearts && !pt.alt -> drawPath(heartPath(pos, s * 1.5f), tint)
            style == ParticleStyle.Sparkles -> drawPath(sparklePath(pos, s * 1.6f, pt.spin * p), tint)
            else -> drawCircle(tint, s, pos)
        }
    }
}

private fun heartPath(c: Offset, r: Float): Path = Path().apply {
    moveTo(c.x, c.y + r * 0.9f)
    cubicTo(c.x - r * 1.4f, c.y - r * 0.1f, c.x - r * 0.7f, c.y - r * 1.2f, c.x, c.y - r * 0.45f)
    cubicTo(c.x + r * 0.7f, c.y - r * 1.2f, c.x + r * 1.4f, c.y - r * 0.1f, c.x, c.y + r * 0.9f)
    close()
}

private fun sparklePath(c: Offset, r: Float, rotationDeg: Float): Path = Path().apply {
    val rot = rotationDeg / 180f * PI.toFloat()
    for (i in 0 until 8) {
        val a = rot + i * PI.toFloat() / 4f
        val len = if (i % 2 == 0) r else r * 0.32f
        val x = c.x + cos(a) * len
        val y = c.y + sin(a) * len
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
