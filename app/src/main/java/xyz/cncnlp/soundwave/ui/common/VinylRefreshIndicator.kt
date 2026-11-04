package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * r658d（用户 10-20：不要蜘蛛了）：下拉刷新 = 一张小黑胶。
 *  - 下拉：唱片从顶边落下（位移跟随下拉比例），外圈珊瑚进度弧按下拉比例画满，唱片随下拉转动。
 *  - 松手刷新：弹簧落到位，唱片匀速旋转（1.1s 一圈），进度弧变成绕圈的 90° 短弧（0.9s），中心标签呼吸。
 *  - 完成：停 350ms 后上收 + 淡出；刷新状态只供读屏使用，不显示瞬时文字。
 *  - 简洁动效：无弹簧与循环，刷新时静止显示。
 * 驱动参数与旧蜘蛛指示器一致（state.distanceFraction / isRefreshing）。
 */
@Composable
internal fun VinylRefreshIndicator(
    pull: Float,
    refreshing: Boolean,
    ink: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion(),
    muted: Color = ink.copy(alpha = 0.55f)
) {
    var phase by remember { mutableIntStateOf(0) } // 0 跟手；1 刷新中；2 完成
    val drop = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(false) }
    val pullDrop = VINYL_REST_DP * pull.coerceIn(0f, 1.25f)
    LaunchedEffect(refreshing) {
        if (refreshing) {
            seen = true; phase = 1; fade.snapTo(1f)
            if (reduceMotion) { drop.snapTo(VINYL_REST_DP); return@LaunchedEffect }
            drop.snapTo(pullDrop.coerceAtLeast(12f))
            drop.animateTo(VINYL_REST_DP, spring(dampingRatio = 0.5f, stiffness = 420f))
        } else if (seen) {
            seen = false; phase = 2
            if (reduceMotion) fade.animateTo(0f, tween(200))
            else {
                kotlinx.coroutines.delay(350)
                kotlinx.coroutines.coroutineScope {
                    launch { fade.animateTo(0f, tween(320)) }
                    drop.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
                }
            }
            phase = 0; fade.snapTo(1f); drop.snapTo(0f)
        }
    }
    if (phase == 0 && pull <= 0.01f) return
    val loops = xyz.cncnlp.soundwave.MotionPolicy.allowDecorativeLoops(reduceMotion)
    var spin = pull * 300f
    var arcStart = -90f
    var pulse = 1f
    if (phase == 1 && loops) {
        val loop = rememberInfiniteTransition(label = "vinyl")
        val s by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "vSpin")
        val a by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "vArc")
        val p by loop.animateFloat(0.86f, 1.08f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "vPulse")
        spin = s; arcStart = a - 90f; pulse = p
    }
    val y = if (phase == 0) pullDrop else drop.value
    val sweep = if (phase == 0) 360f * pull.coerceIn(0f, 1f) else 90f
    // Keep status available to accessibility services without a visible caption.
    val accessibilityLabel = when (phase) { 1 -> "正在刷新…"; 2 -> "已是最新内容"; else -> "下拉刷新" }
    Box(
        modifier.fillMaxWidth().height(VINYL_CANVAS_HEIGHT_DP.dp).alpha(fade.value)
            .semantics { contentDescription = accessibilityLabel }
    ) {
        Canvas(
            Modifier.align(Alignment.TopCenter).offset(y = (y - VINYL_SIZE_DP).dp).size(VINYL_SIZE_DP.dp)
        ) {
            val u = 1.dp.toPx()
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 3f * u
            // 外圈进度弧
            drawArc(
                accent, startAngle = arcStart, sweepAngle = sweep.coerceAtLeast(0.1f), useCenter = false,
                topLeft = Offset(c.x - r - 1.5f * u, c.y - r - 1.5f * u), size = Size((r + 1.5f * u) * 2, (r + 1.5f * u) * 2),
                style = Stroke(2.4f * u, cap = StrokeCap.Round)
            )
            val disc = r - 3.5f * u
            rotate(spin, c) {
                drawCircle(Color(0xFF151615), disc, c)
                var g = disc - 2.5f * u
                while (g > disc * 0.42f) {
                    drawCircle(Color(0xFF2A2C2A), g, c, style = Stroke(0.8f * u)); g -= 2.6f * u
                }
                // 一道高光，让旋转看得见
                drawArc(
                    Color.White.copy(alpha = 0.22f), -60f, 38f, false,
                    topLeft = Offset(c.x - disc * 0.78f, c.y - disc * 0.78f), size = Size(disc * 1.56f, disc * 1.56f),
                    style = Stroke(1.6f * u, cap = StrokeCap.Round)
                )
                drawCircle(accent, disc * 0.34f * pulse, c)
                drawCircle(Color.White.copy(alpha = 0.9f), 1.8f * u, c)
            }
        }
    }
}

internal const val VINYL_SIZE_DP = 34f
internal const val VINYL_REST_DP = 52f
internal const val VINYL_CANVAS_HEIGHT_DP = 92f
