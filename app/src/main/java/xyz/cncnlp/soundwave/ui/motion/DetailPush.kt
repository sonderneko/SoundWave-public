package xyz.cncnlp.soundwave.ui.motion

import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 修复（审计第 10 项）：进入详情页的推入转场——360ms emphasized-decelerate `CubicBezier(.05,.7,.1,1)`，
 * 从右侧 100% 滑到 0，同时 0.6→1 淡入；左缘一道 18dp 的柔和阴影模拟「压在底页之上」。
 * 每个新详情播放一次；返回时 300ms 推出、底页同步复位（见 [rememberDetailPushFrame]）；「简洁动效」时直接出现 / 消失。
 */
object DetailPush {
    // P1 / SW-12-05：时长与曲线唯一出口为 MotionTokens，以下为兼容别名，数值原样不变。
    const val ENTER_MS = MotionTokens.Duration.Enter
    const val EXIT_MS = MotionTokens.Duration.PageExit
    val Emphasized = MotionTokens.Easing.EmphasizedDecelerate
    /** 返回：emphasized-accelerate（原型 300ms）。 */
    val EmphasizedAccelerate = MotionTokens.Easing.EmphasizedAccelerate
    /** 底页位移比例（原型 −25%）与压暗（8%）。 */
    const val UNDER_SHIFT = 0.25f
    const val UNDER_DIM = 0.08f

    /** 详情覆盖度：0 = 无详情，1 = 详情完全推入。由 [rememberDetailPushFrame] 写入，底页 [detailUnderlayModifier] 读取（绘制阶段读，不触发重组）。 */
    val coverage = mutableFloatStateOf(0f)

    /**
     * SW-12-11（详情部分）：预测性返回跟手进度与完成收尾进度（各 0..1），由 [DetailPredictiveBackHandler] 写入，
     * 帧修饰与 [coverage] 在绘制 / 快照阶段读取（不触发重组）。
     */
    val predictiveFollow = mutableFloatStateOf(0f)
    val predictiveCommit = mutableFloatStateOf(0f)

    /** 手势完成收尾后触发的那一次 pop 抑制推出动画（收尾已完成同一段位移，避免叠加）；由帧的进场分支兜底清除，不得跨详情存活。 */
    var suppressNextExit = false

    /** 纯函数：跟手 / 收尾 → 有效退出比例（跟手只走 [UNDER_SHIFT] 段，收尾补完其余，与 SettingsPush 同式）。 */
    fun predictiveFraction(follow: Float, commit: Float): Float {
        val followed = follow.coerceIn(0f, 1f) * UNDER_SHIFT
        return (followed + (1f - followed) * commit.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** 当前手势有效退出比例（0 = 未介入，1 = 已完全退出位）。 */
    fun predictiveFraction(): Float = predictiveFraction(predictiveFollow.floatValue, predictiveCommit.floatValue)

    /** 纯函数：覆盖度 → (底页横移比例, 压暗不透明度)。 */
    fun underlay(c: Float): Pair<Float, Float> = c.coerceIn(0f, 1f).let { (0f - UNDER_SHIFT * it) to UNDER_DIM * it }

    /** 纯函数：推入进度 p（0 = 起点，1 = 到位）→ 横向位移比例。 */
    fun offsetFraction(p: Float): Float = 1f - p.coerceIn(0f, 1f)
}

/** 当前应渲染的详情帧：[item] 为 null 表示不渲染；离场动画期间保留最后一帧内容。 */
class DetailPushFrame<T>(val item: T?, val modifier: Modifier)

/**
 * r657 修复（审计第 10 项，第二轮）：进入 360ms 推入、返回 300ms 推出（emphasized-accelerate），
 * 离场期间保留最后一帧详情内容（[current] 已为 null 时仍画旧详情直到滑出），同时驱动 [DetailPush.coverage]，
 * 让底页跟随左移 25% + 压暗 8%。「简洁动效」时进出都直接切换。
 */
@Composable
fun <T : Any> rememberDetailPushFrame(current: T?, key: (T) -> Any?): DetailPushFrame<T> {
    val reduce = MotionPolicy.rememberReduceMotion()
    var retained by remember { mutableStateOf(current) }
    val progress = remember { Animatable(if (current != null && reduce) 1f else 0f) }
    val k = current?.let(key)
    if (current != null) retained = current
    LaunchedEffect(k, current == null, reduce) {
        if (current != null) {
            // 新详情（含嵌套详情回跳父级）清掉上一轮手势残留，抑制标志不得跨详情存活。
            DetailPush.suppressNextExit = false
            DetailPush.predictiveFollow.floatValue = 0f
            DetailPush.predictiveCommit.floatValue = 0f
            if (reduce) progress.snapTo(1f) else {
                if (progress.value >= 1f) progress.snapTo(0f)
                progress.animateTo(1f, tween(DetailPush.ENTER_MS, easing = DetailPush.Emphasized))
            }
        } else if (retained != null) {
            // 预测性返回已用 300ms 收尾完成同一段位移的那一次 pop 不再重播推出（suppressNextExit），直接落位。
            if (!reduce && !DetailPush.suppressNextExit) progress.animateTo(0f, tween(DetailPush.EXIT_MS, easing = DetailPush.EmphasizedAccelerate)) else progress.snapTo(0f)
            DetailPush.suppressNextExit = false
            DetailPush.predictiveFollow.floatValue = 0f
            DetailPush.predictiveCommit.floatValue = 0f
            retained = null
        }
    }
    LaunchedEffect(Unit) {
        // coverage 随手势同步回落：底页（主路由常驻下层）左移与压暗随有效退出比例归位，手势时自然露出。
        androidx.compose.runtime.snapshotFlow { progress.value to DetailPush.predictiveFraction() }
            .collect { (p, f) -> DetailPush.coverage.floatValue = p * (1f - f) }
    }
    // 总位移比例：推入 / 推出进度与手势有效退出比例叠加（手势只作用于尚未被转场覆盖的剩余部分）。
    fun totalOffset(p: Float): Float {
        val base = DetailPush.offsetFraction(p)
        val frac = if (reduce) 0f else DetailPush.predictiveFraction()
        return base + (1f - base) * frac
    }
    val mod = Modifier
        .graphicsLayer {
            translationX = size.width * totalOffset(progress.value)
            // r658c U A（用户录屏 1000073631）：去掉 0.6→1 淡入——半透明详情层会透出下层音乐库，推入 / 返回时两页内容叠在一起；
            // 改为整页不透明平滑滑入 / 滑出（底页同步左移 25% + 压暗），无重叠错位。
        }
        .drawBehind {
            val t = totalOffset(progress.value)
            if (t < 1f) {
                val w = 18f * density
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f * (1f - t))), startX = -w, endX = 0f),
                    topLeft = Offset(-w, 0f), size = Size(w, size.height)
                )
            }
        }
    return DetailPushFrame(retained, mod)
}

/**
 * SW-12-11（详情部分）：详情页预测性返回（API 34+），写法与 [SettingsPushHost] 的设置部分保持一致。
 * 结构差异：设置页下层需临时静态渲染，详情页底页（主路由）本就常驻下层且由 [DetailPush.coverage] 驱动，
 * 故手势只需让详情层跟手右移、coverage 同步回落即可露出底页。跟手只走 UNDER_SHIFT 段；完成时先 300ms
 * 收尾补完剩余位移，再回调 [onBack] 走原 pop 路径并抑制该次推出动画；取消则弹簧回弹清零、页面不退出。
 * 「简洁动效」时不跟手、收尾直接落位。API 33 及以下不启用，非手势返回完全走原路径、行为不变。
 * 启用条件（何时算「详情打开且可抢占」）由调用方按 AppRoute 返回优先级给出，本函数只再与 SDK 34 取与。
 */
@Composable
fun DetailPredictiveBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val follow = remember { Animatable(0f) }
    val commit = remember { Animatable(0f) }
    val latestOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { follow.value }.collect { DetailPush.predictiveFollow.floatValue = it }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { commit.value }.collect { DetailPush.predictiveCommit.floatValue = it }
    }
    PredictiveBackHandler(enabled = enabled && Build.VERSION.SDK_INT >= 34) { progress ->
        try {
            // 每轮手势从干净状态开始（上一轮的收尾值不得带入本轮跟手）。
            follow.snapTo(0f); commit.snapTo(0f)
            progress.collect { event -> follow.snapTo(event.progress.coerceIn(0f, 1f)) }
            // 手势完成：300ms 收尾（详情层继续滑出、底页归位），随后走原 pop 路径（回调关闭详情）。
            if (reduce) commit.snapTo(1f) else commit.animateTo(1f, tween(DetailPush.EXIT_MS, easing = DetailPush.EmphasizedAccelerate))
            DetailPush.suppressNextExit = true
            latestOnBack()
        } catch (cancelled: CancellationException) {
            // 手势取消：弹簧回弹到原位，页面不退出（协程已取消，回弹在 NonCancellable 中完成）。
            withContext(NonCancellable) { follow.animateTo(0f, MotionTokens.Spring.DefaultSpatialSpec) }
            throw cancelled
        }
    }
}

/** 底页（首页 / Tab 路由）：随详情覆盖度左移 25% 并压暗 8%；覆盖度 0 时无任何效果。 */
fun detailUnderlayModifier(): Modifier = Modifier
    .graphicsLayer {
        val c = DetailPush.coverage.floatValue
        translationX = if (c > 0f) size.width * DetailPush.underlay(c).first else 0f
    }
    .drawWithContent {
        drawContent()
        val c = DetailPush.coverage.floatValue
        if (c > 0f) drawRect(Color.Black.copy(alpha = DetailPush.underlay(c).second))
    }
