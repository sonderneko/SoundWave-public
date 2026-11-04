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
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * 当前应渲染的详情帧（accept6-backnav 三层结构）：
 * - [under]：主层之下的父级详情——压栈进入期间是被盖住的旧详情、预测性返回期间是栈顶
 *   父级；仅在转场 / 手势期间组合，落位后撤下，父级不常驻组合。
 * - [item]：主层详情（进入中或已落位）；弹栈返回时父级立即以静止帧成为主层。
 * - [exiting]：弹栈 / 关闭时在主层（或 tab 底页）之上滑出的子详情。滑出期间其下
 *   始终是父级详情（关闭到 tab 时才是底页），tab 根不会插入详情层之间。
 */
class DetailPushFrame<T>(
    val under: T?,
    val underModifier: Modifier,
    val item: T?,
    val modifier: Modifier,
    val exiting: T?,
    val exitingModifier: Modifier
)

/**
 * r657 帧机制的 accept6-backnav 重构（用户 2026-11-04 23:17：歌手页 → 专辑 → 返回闪一帧首页）。
 *
 * 旧单层帧把「子 → 父恢复」当作全新进入：retained 立即换成父详情、progress 从 0 重播
 * 360ms 进入动画，期间画面下层只有 tab 根页。本版按导航栈分工（见 [DetailPushFrame]）：
 * - 压栈进入（current 变为新详情）：新详情滑入，旧详情作 under 层以 [DetailPush.underlay]
 *   同式左移 25% + 压暗 8%（与 tab 底页同参数）；
 * - 弹栈返回（current 变回主层落位时记录的 under 键）：父级立即静止落位为主层，子详情
 *   转 exiting 层 300ms 滑出——父级在返回组合期间全程保持组合，tab 根不插入任何一帧；
 * - 预测性返回：手势期间 under 层 = [underlayItem]（栈顶父级快照），随手势 parallax 归位；
 *   提交后 closeDetail 触发的恢复由 suppressNextExit 抑制 exiting 重播（与旧版同口径）；
 * - 关闭到 tab（current → null）：沿用旧 retained 语义，最后一帧滑出后撤层。
 *
 * [DetailPush.coverage] 口径：任一详情已落位在 tab 之上（under 在场 / exiting 在父级之上）
 * 时为 1；仅根详情自身的进出与手势沿用旧公式 progress × (1 − 手势比例)。纯函数
 * （underlay / offsetFraction / predictiveFraction）与 DetailPredictiveBackHandler 不变。
 * 已知取舍：exiting 滑出未完（300ms 内）又发生程序化切目标时直接落位、不叠三层动画。
 */
@Composable
fun <T : Any> rememberDetailPushFrame(current: T?, underlayItem: T?, key: (T) -> Any?): DetailPushFrame<T> {
    val reduce = MotionPolicy.rememberReduceMotion()
    var mainItem by remember { mutableStateOf(current) }
    val progress = remember { Animatable(if (current != null && reduce) 1f else 0f) }
    var pushUnder by remember { mutableStateOf<T?>(null) }
    var exitItem by remember { mutableStateOf<T?>(null) }
    var exitFraction by remember { mutableFloatStateOf(0f) }
    var entering by remember { mutableStateOf(false) }
    var pendingEnter by remember { mutableStateOf(current != null) }
    var gestureActive by remember { mutableStateOf(false) }
    // 主层当前详情落位时其下方的详情键（= underlayItem 的键）；current 变回该键即弹栈恢复。
    var settledUnderKey by remember { mutableStateOf<Any?>(null) }
    val k = current?.let(key)

    // ---- 组合期层转移（与旧版 retained 同步赋值同理，保证状态变更同帧换层） ----
    if (current != null) {
        val m = mainItem
        when {
            m == null -> { mainItem = current; pendingEnter = true }
            key(m) != k -> {
                if (exitItem == null && settledUnderKey != null && settledUnderKey == k) {
                    // 弹栈恢复：父级落位为主层（静止），子详情转滑出层；手势已收尾 / 简洁动效时不建滑出层。
                    if (!DetailPush.suppressNextExit && !reduce) { exitItem = m; exitFraction = 1f }
                    DetailPush.suppressNextExit = false
                    mainItem = current
                    pushUnder = null
                    pendingEnter = false
                } else if (exitItem == null) {
                    // 压栈进入：旧主层转 under，新详情成为主层并重播进入。
                    pushUnder = m
                    mainItem = current
                    pendingEnter = true
                } else {
                    // 滑出未完又切目标（程序化竞态）：直接落位，不叠三层动画。
                    exitItem = null
                    pushUnder = null
                    mainItem = current
                    pendingEnter = false
                }
            }
        }
        // 主层身份与 current 一致时记录其下方详情键（下一轮弹栈判定依据）。
        if (mainItem != null && key(mainItem) == k) settledUnderKey = underlayItem?.let(key)
    } else if (mainItem != null && exitItem == null) {
        // 关闭到 tab / 播放器：主层转滑出层（手势收尾已滑出的不重播），under 一并撤下。
        exitItem = mainItem
        exitFraction = if (DetailPush.suppressNextExit || reduce) 0f else 1f
        mainItem = null
        pushUnder = null
        pendingEnter = false
    }

    // 进入动画：pendingEnter 置位时驱动主层 progress；完成后撤下 under 层。
    LaunchedEffect(pendingEnter, reduce) {
        if (!pendingEnter) return@LaunchedEffect
        DetailPush.suppressNextExit = false
        DetailPush.predictiveFollow.floatValue = 0f
        DetailPush.predictiveCommit.floatValue = 0f
        entering = true
        if (reduce) progress.snapTo(1f) else {
            if (progress.value >= 1f) progress.snapTo(0f)
            progress.animateTo(1f, tween(DetailPush.ENTER_MS, easing = DetailPush.Emphasized))
        }
        entering = false
        pendingEnter = false
        pushUnder = null
    }
    // 滑出动画：exitItem 出现即驱动；弹栈恢复时先把主层（父级）progress 落位 1，保持静止在下。
    LaunchedEffect(exitItem) {
        val exitingNow = exitItem ?: return@LaunchedEffect
        if (mainItem != null) progress.snapTo(1f)
        if (exitFraction > 0f) {
            if (reduce) exitFraction = 0f else androidx.compose.animation.core.animate(
                exitFraction, 0f, tween(DetailPush.EXIT_MS, easing = DetailPush.EmphasizedAccelerate)
            ) { v, _ -> exitFraction = v }
        }
        if (exitItem === exitingNow) { exitItem = null; exitFraction = 0f }
    }
    // 与旧版同口径：current 身份变化时清手势残留全局量，抑制标志不得跨详情存活
    // （镜像流只在手势源值变化时写回全局量，清零后不会被旧值覆写）。
    LaunchedEffect(k, current == null) {
        DetailPush.predictiveFollow.floatValue = 0f
        DetailPush.predictiveCommit.floatValue = 0f
        if (current != null) DetailPush.suppressNextExit = false
    }
    LaunchedEffect(Unit) {
        // 手势在途标志（结构量）：仅控制 under 层挂载，位移本身在绘制阶段读全局手势比例。
        androidx.compose.runtime.snapshotFlow { DetailPush.predictiveFraction() > 0f }
            .distinctUntilChanged()
            .collect { gestureActive = it }
    }
    LaunchedEffect(Unit) {
        // coverage：tab 底页随「压在其上的详情层」左移/压暗的程度（口径见类注释）。
        androidx.compose.runtime.snapshotFlow {
            val f = DetailPush.predictiveFraction()
            when {
                exitItem != null && mainItem != null -> 1f
                exitItem != null -> exitFraction * (1f - f)
                pushUnder != null -> 1f
                else -> progress.value * (1f - f)
            }
        }.collect { DetailPush.coverage.floatValue = it }
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
    // under 层：推入期间随主层 progress 取 underlay 位移/压暗；手势期间随手势比例 parallax 归位。
    val underMod = Modifier
        .graphicsLayer {
            val p = when {
                entering -> progress.value
                gestureActive -> 1f - DetailPush.predictiveFraction()
                else -> 1f
            }
            translationX = size.width * DetailPush.underlay(p).first
        }
        .drawWithContent {
            drawContent()
            val p = when {
                entering -> progress.value
                gestureActive -> 1f - DetailPush.predictiveFraction()
                else -> 1f
            }
            val dim = DetailPush.underlay(p).second
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
    val exitingMod = Modifier
        .graphicsLayer { translationX = size.width * DetailPush.offsetFraction(exitFraction) }
        .drawBehind {
            val t = DetailPush.offsetFraction(exitFraction)
            if (t < 1f) {
                val w = 18f * density
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f * (1f - t))), startX = -w, endX = 0f),
                    topLeft = Offset(-w, 0f), size = Size(w, size.height)
                )
            }
        }
    val under = when {
        exitItem != null -> null
        entering -> pushUnder
        gestureActive -> underlayItem
        else -> null
    }
    return DetailPushFrame(under, underMod, mainItem, mod, exitItem, exitingMod)
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
