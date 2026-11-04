package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * r657：下拉刷新 = 吊在蛛丝上的小蜘蛛，按 v5 原型（src/v5.js SPIDER + proto-v5.css .spid）逐项还原：
 *
 *  - 几何：原型 40×92 viewBox 1:1 换成 dp——蛛丝 1.2dp（墨色 55%），头 r4.6 / 腹 r7.4 实心墨色，
 *    左右各 4 条两段折线腿 1.7dp，珊瑚 #FF5C45 双眼 + 腹部高光；下方 11sp 粗体 muted「正在刷新… / 已是最新内容」。
 *  - 下拉：蛛丝长度跟随下拉距离（[spiderThreadLengthDp]，过阈值后橡皮筋式缓慢再伸长），
 *    蜘蛛按「手指速度 − 弹簧滞后」摆动（停手后阻尼回正），腿随下拉轻轻划动。
 *  - 松手开始刷新：带初速度的欠阻尼弹簧落到 52dp——先冲过头再回弹（原型 cubic-bezier(.3,1.3,.5,1) 的落下回弹），
 *    同时一次衰减摆动；刷新中上下轻点（1.1s）、绕丝「转身」（1.4s，水平缩放 1→0.4→1）、腿 ±6° 摆（0.35s）。
 *  - 完成：文字换成「已是最新内容」，停 350ms 后沿丝爬回顶边（700ms 线性），最后 30% 淡出。
 *  - 简洁动效：无弹簧 / 摆动 / 循环，刷新时直接停在 52dp，完成后 200ms 淡出。
 * 刷新行为与阈值不变：仍由 PullToRefreshBox 的 state.distanceFraction / isRefreshing 驱动。
 */
@Composable
internal fun SpiderRefreshIndicator(
    pull: Float,
    refreshing: Boolean,
    ink: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion(),
    muted: Color = ink.copy(alpha = 0.55f)
) {
    // phase：0 = 跟随下拉；1 = 刷新中；2 = 完成（爬回 / 淡出）。
    var phase by remember { mutableIntStateOf(0) }
    val drop = remember { Animatable(0f) }      // 刷新 / 完成阶段的蛛丝长度（dp）
    val swing = remember { Animatable(0f) }     // 松手时的一次衰减摆动（度）
    val fade = remember { Animatable(1f) }
    var seenRefresh by remember { mutableStateOf(false) }
    val pullLen = spiderThreadLengthDp(pull)
    LaunchedEffect(refreshing) {
        if (refreshing) {
            seenRefresh = true; phase = 1; fade.snapTo(1f)
            if (reduceMotion) { drop.snapTo(SPIDER_FULL_THREAD_DP); return@LaunchedEffect }
            drop.snapTo(pullLen.coerceAtLeast(18f))
            swing.snapTo(9f)
            kotlinx.coroutines.coroutineScope {
                launch { swing.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 90f)) }
                drop.animateTo(SPIDER_FULL_THREAD_DP, spring(dampingRatio = 0.38f, stiffness = 380f), initialVelocity = 260f)
            }
        } else if (seenRefresh) {
            seenRefresh = false; phase = 2
            if (reduceMotion) {
                fade.animateTo(0f, tween(200))
            } else {
                kotlinx.coroutines.delay(350)
                kotlinx.coroutines.coroutineScope {
                    launch { kotlinx.coroutines.delay(490); fade.animateTo(0f, tween(210)) }
                    drop.animateTo(SPIDER_FULL_THREAD_DP - 96f, tween(700, easing = LinearEasing))
                }
            }
            phase = 0; fade.snapTo(1f); drop.snapTo(0f)
        }
    }
    val loops = xyz.cncnlp.soundwave.MotionPolicy.allowDecorativeLoops(reduceMotion)
    // 下拉时的跟手摆动：弹簧滞后的下拉值与实际下拉值之差 → 摆角；停手后自然阻尼回正。
    val lagged by animateFloatAsState(pull, if (reduceMotion) tween(0) else spring(dampingRatio = 0.35f, stiffness = 160f), label = "spiderLag")
    val threadDp = when (phase) { 0 -> pullLen; else -> drop.value }
    if (phase == 0 && pull <= 0.01f) return
    val working = phase == 1
    var bob = 0f; var turn = 1f; var legs = 0f
    if (working && loops) {
        val loop = rememberInfiniteTransition(label = "spider")
        val b by loop.animateFloat(-3f, 4f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spBob")
        val tt by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "spTurn")
        val l by loop.animateFloat(-6f, 6f, infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "spLegs")
        bob = b; turn = 1f - 0.6f * sin(tt * PI.toFloat()); legs = l
    }
    val sway = when {
        reduceMotion -> 0f
        phase == 0 -> ((pull - lagged) * 55f).coerceIn(-12f, 12f)
        else -> swing.value
    }
    if (phase == 0 && !reduceMotion) legs = sin(pull * 14f) * 6f
    SpiderRefreshFrame(
        threadDp = threadDp, ink = ink, accent = accent, muted = muted,
        label = when (phase) { 1 -> "正在刷新…"; 2 -> "已是最新内容"; else -> null },
        sway = sway, bobDp = bob, turn = turn, legDeg = legs,
        modifier = modifier.alpha(fade.value)
    )
}

/** 纯绘制的一帧（截图单测也直接用它画「完成」帧）。原点 = 画布顶边中线；蛛丝末端在 [threadDp]。 */
@Composable
internal fun SpiderRefreshFrame(
    threadDp: Float,
    ink: Color,
    accent: Color,
    muted: Color,
    label: String?,
    modifier: Modifier = Modifier,
    sway: Float = 0f,
    bobDp: Float = 0f,
    turn: Float = 1f,
    legDeg: Float = 0f
) {
    Box(
        modifier.fillMaxWidth().height(SPIDER_CANVAS_HEIGHT_DP.dp)
            .semantics { contentDescription = label ?: "下拉刷新" }
    ) {
        Canvas(Modifier.fillMaxWidth().height(SPIDER_CANVAS_HEIGHT_DP.dp)) {
            val u = 1.dp.toPx()
            val cx = size.width / 2f
            val end = threadDp * u
            rotate(sway, Offset(cx, 0f)) {
                if (end > 0f) drawLine(ink.copy(alpha = 0.55f), Offset(cx, 0f), Offset(cx, end + bobDp * u), 1.2f * u, StrokeCap.Round)
                // 原型 SVG 坐标：丝末端 = (20, 52)；整只蜘蛛以 (20, 52) 为挂点。
                translate(cx - 20f * u, end - 52f * u + bobDp * u) {
                    scale(turn, 1f, Offset(20f * u, 52f * u)) { drawSpiderProto(ink, accent, legDeg) }
                }
            }
        }
        if (label != null) {
            Text(
                label, color = muted, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.TopCenter).wrapContentWidth(unbounded = true).offset(y = (threadDp + 36f).coerceAtLeast(0f).dp)
            )
        }
    }
}

/** 蛛丝长度（dp）：随下拉比例从 4dp 伸到 52dp（原型丝长）；过阈值后橡皮筋式最多再伸 14dp。 */
internal fun spiderThreadLengthDp(drop: Float): Float {
    val p = drop.coerceAtLeast(0f)
    return if (p <= 1f) 4f + (SPIDER_FULL_THREAD_DP - 4f) * p
    else SPIDER_FULL_THREAD_DP + 14f * (1f - exp(-(p - 1f) * 2.2f))
}

internal const val SPIDER_FULL_THREAD_DP = 52f
internal const val SPIDER_CANVAS_HEIGHT_DP = 124

private val SpiderLegs = listOf(
    floatArrayOf(17f, 60f, 8f, 54f, 4f, 62f), floatArrayOf(17f, 63f, 7f, 62f, 4f, 70f),
    floatArrayOf(17f, 66f, 8f, 70f, 7f, 78f), floatArrayOf(17f, 69f, 10f, 76f, 11f, 83f)
)

/** 按原型 SVG（viewBox 40×92，单位 = dp）绘制蜘蛛本体：8 条两段折线腿 + 头 / 腹 + 珊瑚眼与高光。 */
private fun DrawScope.drawSpiderProto(ink: Color, accent: Color, legDeg: Float) {
    val u = 1.dp.toPx()
    val legStroke = Stroke(1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val pivot = Offset(20f * u, 64f * u)
    for (side in listOf(-1f, 1f)) {
        rotate(legDeg * side, pivot) {
            SpiderLegs.forEach { p ->
                fun x(v: Float) = (20f + (v - 20f) * -side) * u   // 左侧 = 原坐标，右侧镜像
                val path = Path().apply { moveTo(x(p[0]), p[1] * u); lineTo(x(p[2]), p[3] * u); lineTo(x(p[4]), p[5] * u) }
                drawPath(path, ink, style = legStroke)
            }
        }
    }
    drawCircle(ink, 4.6f * u, Offset(20f * u, 56.6f * u))
    drawCircle(ink, 7.4f * u, Offset(20f * u, 67f * u))
    val coral = accent
    drawCircle(coral.copy(alpha = 0.9f), 2.2f * u, Offset(17.6f * u, 64.8f * u))
    drawCircle(coral, 1.25f * u, Offset(18.3f * u, 57.2f * u))
    drawCircle(coral, 1.25f * u, Offset(21.7f * u, 57.2f * u))
}
