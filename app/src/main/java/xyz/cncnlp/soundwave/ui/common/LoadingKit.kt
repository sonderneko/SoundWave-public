package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

/**
 * 全仓统一加载语言（ACCEPT4 按 v8 稿 §5 `.v8ld`）：旋转黑胶唱片 + 跳动音柱，取代 Material 旋转圈。
 * - 唱片：34dp 基准（可缩放），1.6s/圈线性旋转；盘面沟纹环（稿 repeating-radial #1D211E/#2C312D）、
 *   珊瑚芯标（稿 radial 16% of farthest-corner ≈ 盘半径 22.6%）+ 纸色细环 + 锥形高光（白 22% @80%）。
 * - 音柱：4 根 3dp 珊瑚柱、高 14dp，scaleY .25→1、0.9s ease-in-out 交替，错相 0/−.3/−.6/−.15s（稿 `.v8ld-eq`）。
 * - 胶囊：表面色 78% 毛玻璃感 + 柔影 + 6% 墨色细边（稿 `.v8ld`），眉题 mono 珊瑚 + 13sp 粗标签。
 * 「减少动效」/ 装饰循环冻结时全部静止（唱片不转、音柱取中位）。
 */

/**
 * 旋转唱片单元：加载指示的唯一图形。任意尺寸可辨（小尺寸沟纹按比例稀疏）。
 * [labelFraction] = 芯标半径 / 盘半径（加载器稿面 .226；空态艺术唱片稿面 .198）；
 * [spinMs] 一圈时长（加载器 1600、空态艺术 9000，均为稿面值）。
 */
@Composable
fun VinylDiscSpinner(
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
    labelFraction: Float = 0.226f,
    showHighlight: Boolean = true,
    spinMs: Int = 1600
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val spinning = MotionPolicy.allowDecorativeLoops(reduce)
    val angle = if (spinning) {
        val transition = rememberInfiniteTransition(label = "vinylSpin")
        val a by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(spinMs, easing = LinearEasing)),
            label = "vinylAngle"
        )
        a
    } else 0f
    val grooveDark = Color(0xFF1D211E)
    val grooveBase = Color(0xFF2C312D)
    val paper = SoundwaveDesignPalette.Surface
    val accent = SoundwaveDesignPalette.Accent
    Canvas(modifier.size(size).rotate(angle)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(grooveBase, radius = r, center = c)
        // 沟纹：周期 ≈3dp（2.5 暗 + .5 亮），自外向内。
        val period = 3.dp.toPx()
        val band = 2.5.dp.toPx()
        var gr = r - period / 2f
        while (gr > r * (labelFraction + 0.075f)) {
            drawCircle(grooveDark, radius = gr, center = c, style = Stroke(width = band))
            gr -= period
        }
        // 锥形高光（稿 conic：70% 起、白 22% @80%、90% 止；sweep 起点对齐顶部）。
        if (showHighlight) drawCircle(
            Brush.sweepGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent, 0.70f to Color.Transparent,
                    0.80f to Color.White.copy(alpha = 0.22f), 0.90f to Color.Transparent,
                    1f to Color.Transparent
                ),
                center = c
            ),
            radius = r, center = c
        )
        // 纸色细环 + 珊瑚芯标。
        drawCircle(paper, radius = r * (labelFraction + 0.032f), center = c, style = Stroke(width = r * 0.05f))
        drawCircle(accent, radius = r * labelFraction, center = c)
    }
}

/** 跳动音柱单元（4 柱）。[height] 默认稿面 14dp；小场景可缩。 */
@Composable
fun EqBars(
    modifier: Modifier = Modifier,
    color: Color = SoundwaveDesignPalette.Accent,
    height: Dp = 14.dp,
    barWidth: Dp = 3.dp
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val active = MotionPolicy.allowDecorativeLoops(reduce)
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "eqPhase")
        val p by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "eqPhaseValue"
        )
        p
    } else 0.25f
    // 稿错相（.9s 半程计）：0 / −.3 / −.6 / −.15 → 1.8s 全程相位 0 / 1/6 / 1/3 / 1/12。
    val offsets = listOf(0f, 1f / 6f, 1f / 3f, 1f / 12f)
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.Bottom) {
        offsets.forEach { off ->
            val x = (phase + off) % 1f
            val tri = if (x < 0.5f) x * 2f else 2f - 2f * x
            val eased = tri * tri * (3f - 2f * tri) // smoothstep ≈ ease-in-out
            val scale = if (active) 0.25f + 0.75f * eased else 0.6f
            Box(
                Modifier.width(barWidth).height(height).graphicsLayer {
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }.clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}

/** 稿面加载胶囊：唱片 + 眉题/标签 + 音柱。详情页 hero 骨架内与整块加载态共用。 */
@Composable
fun LoadingPill(label: String, modifier: Modifier = Modifier, eyebrow: String = "LOADING") {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.shadow(16.dp, shape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.10f))
            .clip(shape)
            .background(colors.surface.copy(alpha = 0.78f))
            .border(1.dp, designLineColor().copy(alpha = 0.55f), shape)
            .padding(start = 9.dp, top = 9.dp, bottom = 9.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VinylDiscSpinner(34.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                eyebrow, color = xyz.cncnlp.soundwave.designAccentTextColor(),
                fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium,
                fontFamily = DesignFonts.DmMono, letterSpacing = 0.16.em
            )
            Text(
                label, color = colors.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope
            )
        }
        Spacer(Modifier.width(12.dp))
        EqBars()
    }
}
