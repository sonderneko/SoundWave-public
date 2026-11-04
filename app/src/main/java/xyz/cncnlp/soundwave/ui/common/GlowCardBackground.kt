package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/** r657：深色光晕卡底色（听歌统计「查看完整播放历史」卡、设置账号卡共用）。 */
internal val GlowCardBase = Color(0xFF181613)
private val GlowCoral = Color(0xFFFF6A4D)
private val GlowBlue = Color(0xFFCCF35B)
private val GlowCyan = Color(0xFFF2B544)

/**
 * 深色暖墨底 + 珊瑚 / 青柠（[withCyan] 时再加琥珀）径向光晕 + 顶部玻璃高光（E8 去蓝，变量名为历史遗留）。
 * [phase] 0..1 让光晕缓慢漂移；静止版传 0。纯绘制，不影响布局与语义。
 */
fun glowCardBackground(phase: Float = 0f, withCyan: Boolean = false): Modifier = Modifier.drawBehind {
    drawRect(GlowCardBase)
    val w = size.width
    val h = size.height
    val r = maxOf(w, h)
    val a = phase * 2f * Math.PI.toFloat()
    fun glow(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = Offset(cx, cy), radius = radius))
    }
    glow(GlowCoral, 0.55f, w * (0.12f + 0.06f * cos(a)), h * (0.15f + 0.10f * sin(a)), r * 0.55f)
    glow(GlowBlue, 0.50f, w * (0.92f - 0.06f * sin(a)), h * (0.95f + 0.08f * cos(a)), r * 0.60f)
    if (withCyan) glow(GlowCyan, 0.32f, w * (0.62f + 0.08f * cos(a + 2f)), h * (0.10f + 0.10f * sin(a + 2f)), r * 0.40f)
    // 玻璃高光：上沿一道白色渐隐。
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), startY = 0f, endY = h * 0.45f))
}

/** 缓慢漂移的光晕卡底（24s 一周期）；[reduceMotion] 时静止。 */
@Composable
fun rememberDriftingGlowBackground(reduceMotion: Boolean, withCyan: Boolean = true): Modifier {
    if (reduceMotion) return glowCardBackground(0f, withCyan)
    val t = rememberInfiniteTransition(label = "glowDrift")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart), label = "glowPhase")
    return glowCardBackground(phase, withCyan)
}
