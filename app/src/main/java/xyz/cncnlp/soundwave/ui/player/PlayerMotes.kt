package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/** 一颗光尘的静态参数（纯数据，单测覆盖分布）。 */
internal data class Mote(val x: Float, val y0: Float, val radiusDp: Float, val speedDp: Float, val gold: Boolean, val alpha: Float, val phase: Float)

/** 原型 motion.js startMotes：N=22，r 0.8–2.6dp，上升 6–20dp/s，35% 暖金，alpha .25–.6。 */
internal fun motesFor(count: Int = 22, seed: Int = 657): List<Mote> {
    val rnd = kotlin.random.Random(seed)
    return List(count) {
        Mote(
            x = rnd.nextFloat(), y0 = rnd.nextFloat(),
            radiusDp = 0.8f + rnd.nextFloat() * 1.8f,
            speedDp = 6f + rnd.nextFloat() * 14f,
            gold = rnd.nextFloat() < 0.35f,
            alpha = 0.25f + rnd.nextFloat() * 0.35f,
            phase = rnd.nextFloat() * 2f * PI.toFloat()
        )
    }
}

/**
 * 默认播放器 / 歌词页光尘：珊瑚 / 暖金小粒子缓慢上升并闪烁；暂停时 600ms 淡出、不再逐帧绘制；「简洁动效」时不画。
 * 时间只在绘制阶段读取（不触发重组）。
 */
@Composable
internal fun PlayerMotes(playing: Boolean, reduceMotion: Boolean, accent: Color, modifier: Modifier = Modifier) {
    if (reduceMotion) return
    val motes = remember { motesFor() }
    val fade = remember { Animatable(if (playing) 1f else 0f) }
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) { fade.animateTo(if (playing) 1f else 0f, tween(600)) }
    LaunchedEffect(playing) {
        if (!playing || xyz.cncnlp.soundwave.MotionPolicy.decorativeLoopsFrozen) return@LaunchedEffect
        val start = withFrameNanos { it } - nanos
        while (true) { withFrameNanos { nanos = it - start } }
    }
    val gold = Color(0xFFFFD678)
    Canvas(modifier) {
        val a = fade.value
        if (a <= 0.01f) return@Canvas
        val t = nanos / 1_000_000_000f
        val h = size.height
        motes.forEach { m ->
            val travel = (m.speedDp * density * t) % (h + 40f)
            val y = ((m.y0 * h - travel) % (h + 40f) + h + 40f) % (h + 40f) - 20f
            val x = m.x * size.width + sin(t * 0.6f + m.phase) * 6f * density
            val twinkle = 0.65f + 0.35f * sin(t * 2.2f + m.phase * 3f)
            val c = (if (m.gold) gold else accent).copy(alpha = m.alpha * twinkle * a)
            val r = m.radiusDp * density
            drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset(x, y), radius = r * 3f), radius = r * 3f, center = Offset(x, y))
            drawCircle(c, radius = r, center = Offset(x, y))
        }
    }
}
