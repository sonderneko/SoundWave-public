package xyz.cncnlp.soundwave.ui.route

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 第二轮（用户：Tab 切换动画看不到）：首页是常驻层（不在 AnimatedContent 里），原来切到 / 切出首页时直接
 * 0 尺寸 / 满尺寸硬切，fade-through 只在 搜索 ⇄ 音乐库 ⇄ 设置 之间可见。这里让首页层走同一套 [TabFadeThrough]：
 * 离开首页时保留布局 [TabFadeThrough.OUT_MS] 并线性淡出；回到首页时延迟 OUT_MS 后 210ms 淡入 + 0.96→1 放大。
 * 返回 (是否仍需布局, 绘制阶段的 alpha/scale Modifier)。简洁动效下硬切。
 */
@Composable
internal fun rememberHomeLayerFadeThrough(visible: Boolean): Pair<Boolean, androidx.compose.ui.Modifier> {
    val reduce = MotionPolicy.rememberReduceMotion()
    val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (visible) 1f else 0f) }
    var laidOut by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(visible) }
    val first = androidx.compose.runtime.remember { booleanArrayOf(true) }
    androidx.compose.runtime.LaunchedEffect(visible, reduce) {
        if (first[0]) { first[0] = false; alpha.snapTo(if (visible) 1f else 0f); laidOut = visible; return@LaunchedEffect }
        if (reduce) {
            // 「简洁动效」：保留 120ms 线性淡入淡出（无缩放），不再硬切。
            if (visible) laidOut = true
            alpha.animateTo(if (visible) 1f else 0f, tween(TabFadeThrough.REDUCED_MS, easing = LinearEasing))
            laidOut = visible
            return@LaunchedEffect
        }
        if (visible) {
            laidOut = true
            alpha.snapTo(0f)
            alpha.animateTo(1f, tween(TabFadeThrough.IN_MS, delayMillis = TabFadeThrough.OUT_MS, easing = TabFadeThrough.Decelerate))
        } else {
            alpha.animateTo(0f, tween(TabFadeThrough.OUT_MS, easing = LinearEasing))
            laidOut = false
        }
    }
    val mod = androidx.compose.ui.Modifier.graphicsLayer {
        val a = alpha.value
        this.alpha = a
        val sc = if (visible && !reduce) TabFadeThrough.IN_SCALE + (1f - TabFadeThrough.IN_SCALE) * a else 1f
        scaleX = sc; scaleY = sc
    }
    return (laidOut || visible) to mod
}
