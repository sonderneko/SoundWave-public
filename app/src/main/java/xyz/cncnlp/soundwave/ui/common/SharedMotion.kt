package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.ui.motion.MotionTokens

/**
 * ACCEPT6-motiondraft：自 v8 设计稿采纳的三件共享动效（稿面参数见 WAVE-ACCEPT6-MOTIONDRAFT §一/§二）。
 * 全部只走绘制层 / 状态动画，不改任何布局与功能；「简洁动效」时一律直显。
 */

/**
 * 稿面勾选 ✓ 弹入：未选时无物，选中时 ✓ 自 0.6 倍 + 透明弹入（稿 .ck：opacity 0 / scale(.6) →
 * 显形 150ms，v8pk 变体 transform .2s spring）。进入用 [MotionTokens.Spring.Pop] 弹簧，
 * 退出 120ms 淡出 + 缩回。供页面内多选面共用（歌曲行 / 下载 / 详情）；sheet 内同类面由
 * dialogfrost 棒复用本件。
 */
@Composable
fun AnimatedCheckMark(
    visible: Boolean,
    glyph: ImageVector,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None
        else fadeIn(tween(MotionTokens.Duration.Short)) +
            scaleIn(initialScale = 0.6f, animationSpec = MotionTokens.Spring.PopSpec),
        exit = if (reduce) ExitTransition.None
        else fadeOut(tween(120)) + scaleOut(targetScale = 0.6f, animationSpec = tween(120))
    ) {
        Icon(glyph, contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * 稿面「点亮」本体弹跳：状态由 false → true 的边沿上，元素本体先放大到 [peak]（稿 burst 的
 * pop：1 → 1.25 → 1、420ms spring 系；此处升程 140ms 减速 + 落程走 [MotionTokens.Spring.Pop]）
 * 再弹回。粒子仍由 ParticleBurst 负责，本件只管本体缩放，两者可叠加（稿面亦是并行）。
 * 只在绘制层缩放，不触发重组；true → false 不播（取消喜欢无此效，稿同）。
 */
fun Modifier.popOnBecomeTrue(trigger: Boolean, peak: Float = 1.25f): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val scale = remember { Animatable(1f) }
    var prev by remember { mutableStateOf(trigger) }
    // 边沿稳定性门：false 须稳定存续 ≥700ms 后的翻转才算「点亮」——挡掉 liked 异步读入
    // 与切歌瞬间带来的假边沿（稿面 pop 是点按奖励，不是状态播报）。
    var prevSince by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trigger) {
        val now = System.currentTimeMillis()
        val was = prev
        val stableFor = now - prevSince
        prev = trigger
        prevSince = now
        if (trigger && !was && !reduce && stableFor >= 700L) {
            scale.snapTo(1f)
            scale.animateTo(peak, tween(140, easing = MotionTokens.Easing.EmphasizedDecelerate))
            scale.animateTo(1f, MotionTokens.Spring.PopSpec)
        } else if (!trigger && scale.value != 1f) {
            // 中途取消喜欢 / 边沿被打断：把本体缩放收回，勿卡在放大态。
            scale.animateTo(1f, tween(120))
        }
    }
    this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * 稿面拖拽 lift：被拖行浮起一层大柔影（稿 .lift：0 18px 36px rgba(19,25,21,.22)），
 * Compose 侧以 18dp 阴影高度近似，150ms 补间升降 + zIndex 抬升；行自身的拖动态淡染
 * 由各调用面保留（单一来源：本件只管影，不动底色）。
 */
fun Modifier.dragLift(active: Boolean, shape: Shape): Modifier = composed {
    val elevation by animateDpAsState(
        if (active) 18.dp else 0.dp,
        tween(durationMillis = MotionTokens.Duration.Short, easing = MotionTokens.Easing.Emphasized),
        label = "dragLift"
    )
    this.zIndex(if (active) 3f else 0f).shadow(elevation, shape, clip = false)
}
