package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * r657 第二轮：全局按压反馈 = 原型 `.press`（按下 scale .965 / 140ms ease-out，松开弹簧回 1）+ `.rg` 光晕
 * （从触点扩散的珊瑚径向光，520ms 淡出），替代 Material 灰色水波纹。
 *
 * 由 SoundwaveDesignTheme 以 LocalIndication 下发：所有未显式指定 indication 的 clickable / combinedClickable /
 * selectable 都使用它。显式 `indication = null` 的组件（自带 pressScale 的按钮等）不受影响。
 * 「减少动效」时不缩放、不扩散动画，只在按下期间叠一层以触点为中心、边缘渐隐的静态柔光底——
 * 验收二轮 E19 起禁止再用 drawRect 平涂：直角矩形罩在圆角卡片内会露出与卡片形状不一致的
 * 矩形色块（用户真机截图点名），柔光渐隐则在任何形状上都不出现硬边。
 */
class PressGlowIndication(
    private val glow: Color = Color(0xFFFF5C45).copy(alpha = 0.16f),
    private val scaleTo: Float = PRESS_SCALE,
    private val reduceMotion: () -> Boolean = { false }
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, glow, scaleTo, reduceMotion)

    override fun equals(other: Any?): Boolean = other is PressGlowIndication && other.glow == glow && other.scaleTo == scaleTo
    override fun hashCode(): Int = glow.hashCode() * 31 + scaleTo.hashCode()

    private class Node(
        private val source: InteractionSource,
        private val glow: Color,
        private val scaleTo: Float,
        private val reduceMotion: () -> Boolean
    ) : Modifier.Node(), DrawModifierNode {
        private val scale = Animatable(1f)
        private val ring = Animatable(1f)
        private var origin = Offset.Unspecified
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> {
                            pressed = true; origin = i.pressPosition
                            if (!reduceMotion()) {
                                launch { scale.animateTo(scaleTo, PressMotion.pressSpec) }
                                launch { ring.snapTo(0f); ring.animateTo(1f, tween(RING_MS, easing = LinearEasing)) }
                            }
                            invalidateDraw()
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> {
                            pressed = false
                            launch { scale.animateTo(1f, PressMotion.releaseSpec) }
                            invalidateDraw()
                        }
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val s = scale.value
            if (s != 1f) scale(s) { this@draw.drawContent() } else drawContent()
            val p = ring.value
            if (p < 1f && origin.isSpecified) {
                val o = origin
                val maxR = kotlin.math.hypot(kotlin.math.max(o.x, size.width - o.x), kotlin.math.max(o.y, size.height - o.y)) * 1.2f
                val r = (maxR * (0.15f + 0.85f * (1f - (1f - p) * (1f - p)))).coerceAtLeast(1f)
                val a = (1f - p) * glow.alpha
                clipRect {
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = a), glow.copy(alpha = 0f)), center = o, radius = r), radius = r, center = o)
                }
            } else if (pressed && reduceMotion()) {
                // E19：减少动效的按压底改为静态径向柔光（触点为心、无动画、边缘渐隐至全透明），
                // 绝不 drawRect 平涂整块边界——圆角表面上会露出直角矩形色块。
                val o = if (origin.isSpecified) origin else center
                val maxR = kotlin.math.hypot(kotlin.math.max(o.x, size.width - o.x), kotlin.math.max(o.y, size.height - o.y)) * 1.2f
                val a = glow.alpha * 0.6f
                clipRect {
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = a), glow.copy(alpha = 0f)), center = o, radius = maxR), radius = maxR, center = o)
                }
            }
        }
    }

    companion object {
        /**
         * 原型 `.press.down{transform:scale(.965)}`。
         * P1 注记：0.965 是用户已确认的全局默认指示比例（B.2 视为 Card 档的默认指示变体），保留现值；
         * 显式 pressScale 的三档为 PressMotion 的 Row 0.98 / Card 0.96 / Icon 0.92。用了 pressScale 的
         * 元素必须 indication=null，避免与本指示的缩放叠加（SW-12-01）。缩放弹簧经 PressMotion 引用
         * MotionTokens.Spring.Press/Release。
         */
        const val PRESS_SCALE = 0.965f
        /** 原型 `.rg` 动画 520ms（B.2 Glow 已确认值；与 PressFeedback.RippleGlow 的 360ms 是两套实现）。 */
        const val RING_MS = 520
    }
}
