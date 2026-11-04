package xyz.cncnlp.soundwave.ui.common

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 修复（审计第 20 项）：首屏错峰入场——每项 620ms、间隔 60ms（最多 8 级）、上移 16dp、0.985→1、
 * API 31+ 额外 4dp→0 模糊。每个 [key] 只播放一次（页面首次进入 / 详情切换），「简洁动效」时直接显示。
 * 只改 graphicsLayer（绘制阶段读值），不触发重组与重新布局。
 */
object StaggerMotion {
    const val DURATION_MS = 620
    const val STEP_MS = 60
    const val MAX_STEPS = 8
    val Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 纯函数：第 [index] 项的入场延迟。 */
    fun delayFor(index: Int): Int = index.coerceIn(0, MAX_STEPS) * STEP_MS
}

fun Modifier.staggerEnter(index: Int, key: Any? = Unit): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val p = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key, reduce) {
        if (reduce) p.snapTo(1f)
        else p.animateTo(1f, tween(StaggerMotion.DURATION_MS, delayMillis = StaggerMotion.delayFor(index), easing = StaggerMotion.Easing))
    }
    this.staggerLayer { p.value }
}

/**
 * r657 修复第二轮：页面级错峰门。LazyColumn 的项滚出再滚回会重新组合，用页面级 [StaggerGate] 记住已播放过的序号，
 * 避免回到顶部时首屏项再次入场。页面重新进入（Tab 切换重建）时门随之重建 → 与原型 restagger 一致。
 */
class StaggerGate {
    internal val played = mutableSetOf<Int>()
    /** 纯函数式查询（单测）：该序号是否还需要播放入场。 */
    fun pending(index: Int): Boolean = index !in played
}

@androidx.compose.runtime.Composable
fun rememberStaggerGate(): StaggerGate = remember { StaggerGate() }

fun Modifier.staggerEnter(index: Int, gate: StaggerGate): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val first = remember { gate.pending(index) && !reduce }
    val p = remember { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(Unit) {
        gate.played += index
        if (first) p.animateTo(1f, tween(StaggerMotion.DURATION_MS, delayMillis = StaggerMotion.delayFor(index), easing = StaggerMotion.Easing))
    }
    this.staggerLayer { p.value }
}

private fun Modifier.staggerLayer(progress: () -> Float): Modifier = this.graphicsLayer {
    val v = progress()
    if (v < 1f) {
        alpha = v
        translationY = (1f - v) * 16.dp.toPx()
        val s = 0.985f + 0.015f * v
        scaleX = s; scaleY = s
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && v < 0.98f) {
            val r = (1f - v) * 4.dp.toPx()
            if (r > 0.5f) renderEffect = BlurEffect(r, r, TileMode.Decal)
        }
    }
}
