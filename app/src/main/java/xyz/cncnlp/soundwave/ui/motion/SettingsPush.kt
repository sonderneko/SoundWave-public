package xyz.cncnlp.soundwave.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 第二轮（用户）：设置主页 ⇄ 设置子页的推入 / 推出转场（原型 motion「页面推入 / 返回」：所有二级页，含设置子页）。
 *  - 进入：新页从右侧 100% 滑入（360ms emphasized-decelerate），旧页左移 25% 并压暗 8%；
 *  - 返回：当前页滑出到右侧（300ms emphasized-accelerate），下层页从 −25% 回到原位、压暗淡出；
 *  - 「简洁动效」时直接切换。
 * 每一层用 SaveableStateProvider 保存 rememberSaveable 状态（设置主页列表滚动位置在返回后保留）。
 */
object SettingsPush {
    // accept6-backnav：深度与方向判定改由 SettingsNavStack 按导航栈计算（深度-2 子页链
    // 统计→历史等需要真实父级），本对象只保留转场变换本身。
    fun transform(forward: Boolean, reduceMotion: Boolean): ContentTransform = when {
        reduceMotion -> EnterTransition.None togetherWith ExitTransition.None
        // r657 第三轮（用户 10-18 真机：两页文字互相透出）：进入页不再 0.6→1 淡入，整页不透明底遮住下层，只做横向推入。
        // P1 / SW-12-05：时长与曲线直接引用 MotionTokens（与 DetailPush 别名同值，数值不变）。
        forward -> slideInHorizontally(tween(MotionTokens.Duration.Enter, easing = MotionTokens.Easing.EmphasizedDecelerate)) { it } togetherWith
            slideOutHorizontally(tween(MotionTokens.Duration.Enter, easing = MotionTokens.Easing.EmphasizedDecelerate)) { -(it * DetailPush.UNDER_SHIFT).toInt() }
        else -> slideInHorizontally(tween(MotionTokens.Duration.PageExit, easing = MotionTokens.Easing.EmphasizedAccelerate)) { -(it * DetailPush.UNDER_SHIFT).toInt() } togetherWith
            slideOutHorizontally(tween(MotionTokens.Duration.PageExit, easing = MotionTokens.Easing.EmphasizedAccelerate)) { it }
    }.apply { targetContentZIndex = if (forward) 1f else 0f }
}

/**
 * 设置页宿主：普通推入 / 推出仍走 [AnimatedContent]（上方 transform）。
 *
 * SW-12-11（设置部分）结构取舍：AnimatedContent 的推栈里只有当前页，无法直接露出「下层页」，
 * 因此手势期间采用等效实现——下层静态渲染一层父级页（accept6-backnav 起为 [stack] 的
 * 次顶层页；深度-1 时即主页 sub=null 态，与槽位内根页共用 SaveableStateProvider 的
 * "__settings_root__" 键，滚动等状态不丢），顶层页以 graphicsLayer 跟手位移；手势完成后
 * 先 300ms 手动收尾滑出，再回调 [onPredictiveBack] 走弹栈路径，并抑制该次弹栈的
 * AnimatedContent 转场以免位移叠加。通知中心页的 onDispose 标已读依赖子页退出组合：
 * pop 后子页仍随宿主退出组合（抑制的只是转场动画），该依赖不受影响。
 */
@Composable
fun SettingsPushHost(sub: String?, stack: List<String> = emptyList(), onPredictiveBack: (() -> Unit)? = null, content: @Composable (String?) -> Unit) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val holder = rememberSaveableStateHolder()
    // SW-12-11（设置部分）：预测性返回。仅 API 34+ 系统手势（或同版本的回调完成事件）参与：
    // enabled 在 API 33 及以下恒为 false，非手势返回（返回键 / AppRoot 的 BackHandler）完全走原路径，行为不变。
    val backFollow = remember { Animatable(0f) }
    val backCommit = remember { Animatable(0f) }
    var backEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }
    // 手势完成收尾后触发的那一次 pop 抑制 AnimatedContent 转场（手动收尾已完成同一段位移），避免动画叠加。
    var suppressPopFor by remember { mutableStateOf<String?>(null) }
    val latestSub by rememberUpdatedState(sub)
    val latestOnPredictiveBack by rememberUpdatedState(onPredictiveBack)
    PredictiveBackHandler(enabled = sub != null && onPredictiveBack != null && Build.VERSION.SDK_INT >= 34) { progress ->
        try {
            progress.collect { event ->
                backEdge = event.swipeEdge
                backFollow.snapTo(event.progress.coerceIn(0f, 1f))
            }
            // 手势完成：300ms 收尾（顶层页继续滑出、下层父级页归位），随后走弹栈路径（回调弹一层回父级）。
            suppressPopFor = latestSub
            if (reduce) backCommit.snapTo(1f) else backCommit.animateTo(1f, tween(MotionTokens.Duration.PageExit, easing = MotionTokens.Easing.EmphasizedAccelerate))
            latestOnPredictiveBack?.invoke()
        } catch (cancelled: CancellationException) {
            // 手势取消：弹簧回弹到原位，页面不退出（协程已取消，回弹在 NonCancellable 中完成）。
            withContext(NonCancellable) { backFollow.animateTo(0f, MotionTokens.Spring.DefaultSpatialSpec) }
            throw cancelled
        }
    }
    LaunchedEffect(sub) {
        // 每次落位（推入/弹栈/回根）后清手势残量；抑制标志在转场规格已于组合期读取后于此清除。
        backFollow.snapTo(0f)
        backCommit.snapTo(0f)
        suppressPopFor = null
    }
    val gestureColors = MaterialTheme.colorScheme
    val gestureDark = gestureColors.background.luminance() < 0.5f
    val backDir = if (backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    // 手势进行 / 收尾期间才渲染下层父级页；普通转场仍由下方 AnimatedContent 独占（结构取舍见类注释与战役报告）。
    val underlayVisible = sub != null && !reduce && (backFollow.value > 0f || backCommit.value > 0f)
    // accept6-backnav：手势下层 = 栈中父页（深度-1 时为 null 即设置主页），与弹栈落点一致。
    val underlaySub = stack.dropLast(1).lastOrNull()
    Box(Modifier.fillMaxSize()) {
        if (underlayVisible) {
            // 下层静态露出：父级页由反向 25% 位移 + 8% 压暗随手势进度归位。
            val underP = maxOf(backFollow.value, backCommit.value)
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { translationX = backDir * -DetailPush.UNDER_SHIFT * (1f - underP) * size.width }
                    .background(gestureColors.background)
                    .drawWithContent {
                        drawContent()
                        if (underP < 1f) drawRect(Color.Black.copy(alpha = DetailPush.UNDER_DIM * (1f - underP)))
                    }
            ) {
                if (xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive) FrostedPageBackdrop(Modifier.fillMaxSize(), gestureDark)
                holder.SaveableStateProvider(underlaySub ?: "__settings_root__") { content(underlaySub) }
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // 顶层跟手：手势期沿手势边缘方向移 0 → 屏宽 25%；收尾期由 backCommit 继续滑出至整屏。
                val followed = backFollow.value * DetailPush.UNDER_SHIFT
                val frac = if (sub == null || reduce) 0f else followed + (1f - followed) * backCommit.value
                translationX = backDir * frac * size.width
            }
        ) {
    AnimatedContent(
        targetState = sub,
        transitionSpec = {
            // 手势收尾已完成同一段位移的那次弹栈（落点为根或父页均同）抑制转场，避免叠加。
            if (initialState != null && initialState == suppressPopFor && targetState != initialState) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                SettingsPush.transform(xyz.cncnlp.soundwave.SettingsNavStack.isForward(stack, initialState, targetState), reduce)
            }
        },
        label = "settingsPush"
    ) { s ->
        // 下层页（非当前目标的一方，即被盖住/被返回到的一页）随转场压暗 0 ⇄ 8%（原型旧页压暗 8%），上层页不压暗。
        val lower = s != sub
        val dim by transition.animateFloat(label = "settingsDim") { st -> if (st == EnterExitState.Visible) 0f else DetailPush.UNDER_DIM }
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        val dark = colors.background.luminance() < 0.5f
        Box(
            Modifier.fillMaxSize()
                // 上层页左缘 18dp 柔和阴影（压在下层页之上，同详情推入）。
                .drawBehind {
                    if (!lower && transition.isRunning) {
                        val w = 18f * density
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f)), startX = -w, endX = 0f), topLeft = Offset(-w, 0f), size = Size(w, size.height))
                    }
                }
                // 每层都有不透明页面底（磨砂背景开时为同一张封面磨砂底），转场中不会透出另一页的文字。
                .background(colors.background)
                .drawWithContent {
                    drawContent()
                    if (lower && dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                }
        ) {
            if (xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive) xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop(Modifier.fillMaxSize(), dark)
            holder.SaveableStateProvider(s ?: "__settings_root__") { content(s) }
        }
    }
        }
    }
}
