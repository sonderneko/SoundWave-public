package xyz.cncnlp.soundwave.ui.notice

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.motion.MotionTokens

/**
 * [AppNotice] 的唯一渲染宿主，挂在 AppOverlayHostLayer 的最上层（最后渲染）。
 *
 * - 底部胶囊：主题 surface 底（浅色白底/夜间暖黑，用户 2026-11-04 裁定）、圆角 16dp、
 *   水平居中，浮在迷你条上方（底栏 + 迷你条 + 间隙 + 16dp，再加系统导航栏安全垫）；
 *   队列与时长由 AppNotice 决定，本宿主按 id 计时关闭。右侧动作（如「撤销」）无背景，
 *   仅文字着色（承 E11.1 无竖线，本轮再去底）。
 * - 顶部横幅：同走主题 surface 底；状态栏下方滑入，右滑跟随位移、松手过 80dp 关闭，
 *   上滑保留（累计 -48dp 关闭）；整条可点击查看（[AppNotice.triggerBannerClick]），
 *   带 action 时另有文字按钮。
 * - 触感：胶囊以 SUCCESS 出现时 CONFIRM、FAILURE 时 REJECT，直接走
 *   [View.performHapticFeedback]（不依赖并行开发的 AppHaptics，避免耦合）；
 *   低版本系统自动降级到既有常量。
 * - 无障碍：胶囊/横幅容器带 liveRegion，播报新提示（FAILURE 用 Assertive）。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AppNoticeHost() {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(appContext) { AppNotice.attach(appContext) }
    DisposableEffect(Unit) {
        AppNotice.setHostMounted(true)
        onDispose { AppNotice.setHostMounted(false) }
    }

    val capsule by AppNotice.capsuleState.collectAsState()
    val banner by AppNotice.bannerState.collectAsState()
    val view = LocalView.current

    // 按 id 计时自动消失：新消息替换旧消息时旧计时随 id 变化自动取消。
    LaunchedEffect(capsule?.id) {
        val current = capsule ?: return@LaunchedEffect
        delay(current.durationMillis)
        AppNotice.dismissCapsule(current.id)
    }
    LaunchedEffect(banner?.id) {
        val current = banner ?: return@LaunchedEffect
        delay(current.durationMillis)
        AppNotice.dismissBanner(current.id)
    }

    // 出现触感：只认胶囊的 SUCCESS / FAILURE，横幅不震。
    LaunchedEffect(capsule?.id) {
        val current = capsule ?: return@LaunchedEffect
        when (current.kind) {
            AppNotice.Kind.SUCCESS -> performConfirmHaptic(view)
            AppNotice.Kind.FAILURE -> performRejectHaptic(view)
            AppNotice.Kind.NEUTRAL -> Unit
        }
    }

    // 退场动画期间保留最后一帧内容，避免状态置 null 时胶囊/横幅瞬间变空。
    var lastCapsule by remember { mutableStateOf<AppNotice.CapsuleState?>(null) }
    LaunchedEffect(capsule) { if (capsule != null) lastCapsule = capsule }
    var lastBanner by remember { mutableStateOf<AppNotice.BannerState?>(null) }
    LaunchedEffect(banner) { if (banner != null) lastBanner = banner }

    val capsuleBottomPadding = capsuleBottomPadding()

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = banner != null,
            modifier = Modifier.align(Alignment.TopCenter),
            // ACCEPT6（用户真机反馈 2026-11-04）：淡入淡出不再挂在 AnimatedVisibility 本层——
            // 改由内容根节点的 animateEnterExit 承担：胶囊/横幅（含阴影与填充）作为单个图层
            // 统一 alpha 合成，避免退场末段填充先透、边缘/阴影残留成与背景的分割线。
            // 本层只保留位移（滑入/滑出）。
            enter = slideInVertically(
                animationSpec = tween(
                    durationMillis = MotionTokens.Duration.Enter,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            ) { -it },
            exit = slideOutVertically(
                animationSpec = tween(
                    durationMillis = MotionTokens.Duration.Exit,
                    easing = MotionTokens.Easing.EmphasizedAccelerate,
                ),
            ) { -it },
        ) {
            (banner ?: lastBanner)?.let { state ->
                Box(
                    Modifier.animateEnterExit(
                        enter = fadeIn(
                            tween(
                                durationMillis = MotionTokens.Duration.Enter,
                                easing = MotionTokens.Easing.EmphasizedDecelerate,
                            ),
                        ),
                        exit = fadeOut(
                            tween(
                                durationMillis = MotionTokens.Duration.Exit,
                                easing = MotionTokens.Easing.EmphasizedAccelerate,
                            ),
                        ),
                    ),
                ) { NoticeBanner(state) }
            }
        }

        AnimatedVisibility(
            visible = capsule != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = capsuleBottomPadding),
            // 淡出改走内容根节点的整层 alpha（同横幅，见上）：本层只保留位移。
            enter = slideInVertically(
                animationSpec = tween(
                    durationMillis = MotionTokens.Duration.Medium,
                    easing = MotionTokens.Easing.EmphasizedDecelerate,
                ),
            ) { it / 2 },
            exit = slideOutVertically(
                animationSpec = tween(
                    durationMillis = MotionTokens.Duration.Exit,
                    easing = MotionTokens.Easing.EmphasizedAccelerate,
                ),
            ) { it / 2 },
        ) {
            (capsule ?: lastCapsule)?.let { state ->
                Box(
                    Modifier.animateEnterExit(
                        enter = fadeIn(
                            tween(
                                durationMillis = MotionTokens.Duration.Medium,
                                easing = MotionTokens.Easing.EmphasizedDecelerate,
                            ),
                        ),
                        exit = fadeOut(
                            tween(
                                durationMillis = MotionTokens.Duration.Exit,
                                easing = MotionTokens.Easing.EmphasizedAccelerate,
                            ),
                        ),
                    ),
                ) { NoticeCapsule(state) }
            }
        }
    }
}

/**
 * ACCEPT6（用户裁定 2026-11-04 19:19 + 19:20 夜间修正）：通知磨砂填充——
 * 浅色走主题 surface 半透明（透出下层内容的磨砂感），夜间走半透明黑（用户点名）；
 * 文字色仍走 onSurface（浅色深字/夜间浅字），两主题对比都成立。
 * 说明：真·背景采样模糊需要把全应用内容包进捕获层（Haze 式），与当前覆盖层结构和
 * 性能战况不合，本棒不引入；磨砂观感由半透明填充 + 柔和阴影承担，已在台账登记此界限。
 */
@Composable
private fun noticeFrostFill(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.surface.luminance() < 0.5f) {
        Color.Black.copy(alpha = 0.62f)
    } else {
        colors.surface.copy(alpha = 0.86f)
    }
}

/** 胶囊底部偏移：底栏 66 + 迷你条 62 + 间隙（DockMetrics）+ 16dp + 导航栏安全垫。 */
@Composable
private fun capsuleBottomPadding(): Dp =
    DockMetrics.BarNavHeight +
        DockMetrics.CapsuleMiniHeight +
        DockMetrics.MiniGap +
        16.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun NoticeCapsule(state: AppNotice.CapsuleState) {
    // ACCEPT5（用户 2026-11-04）：底色随主题（浅色白底、夜间暖黑），文字 onSurface，
    // 不再写死深墨药丸；ACCEPT6 起填充改走 noticeFrostFill 磨砂半透明（见该函数注）。
    // 右侧动作去背景：
    // 无药丸无描边，仅文字着色（designAccentTextColor），热区 ≥48dp、行高 ≥52dp。
    // ACCEPT6（用户真机反馈 2026-11-04）：胶囊边缘描边整条删除——只留填充 + 柔和阴影；
    // 描边在退场淡出末段会与填充脱节、显成与背景的分割线。淡出由宿主侧整层 alpha 承担。
    val shape = RoundedCornerShape(16.dp)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 480.dp)
            .shadow(14.dp, shape)
            .clip(shape)
            .background(noticeFrostFill())
            .semantics(mergeDescendants = true) {
                liveRegion = if (state.kind == AppNotice.Kind.FAILURE) {
                    LiveRegionMode.Assertive
                } else {
                    LiveRegionMode.Polite
                }
            }
            // ACCEPT6 二轮（用户裁定 2026-11-04 19:54）：整体适当缩小——最小高 52→46、
            // 内边距 16→14、间距 10→8、正文 14→13sp、徽标 20→18dp；动作热区 48dp 不动（红线）。
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state.kind) {
            // ui/common 既有 glyph 集没有对勾/叹号字形，按任务书回退文字符号徽标（不引新图标依赖）。
            AppNotice.Kind.SUCCESS -> GlyphBadge(text = "✓", background = SoundwaveDesignPalette.Accent)
            AppNotice.Kind.FAILURE -> GlyphBadge(
                text = "!",
                background = xyz.cncnlp.soundwave.SoundwaveDanger,
            )
            AppNotice.Kind.NEUTRAL -> Unit
        }
        Text(
            text = state.message,
            color = colors.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val actionLabel = state.actionLabel
        if (actionLabel != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = actionLabel) { AppNotice.triggerCapsuleAction(state.id) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    color = xyz.cncnlp.soundwave.designAccentTextColor(),
                    // 字号随整体缩小 13→12sp；外层 Box 热区仍 ≥48dp 高、横向内边距 10dp 不变。
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(state: AppNotice.BannerState) {
    // ACCEPT5（用户 2026-11-04）：底色随主题（surface/onSurface，不再用反色面对冲）；
    // 手势：右滑跟随位移、松手过 80dp 关闭（未达阈值弹簧回位），上滑累计 -48dp 关闭保留；
    // 整条可点击查看（AppNotice.bannerHasClick 决定是否挂 clickable，落点见 triggerBannerClick）。
    // 手势竞争：单一 detectDragGestures 统管双轴——纵向只累计判定、不消费位移跟随，
    // 页面纵滚不在横幅区域内发生；点按与拖动由 consume 区分（拖动消费后点按自动取消）。
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val offsetX = remember(state.id) { Animatable(0f) }
    val hasClick = AppNotice.bannerHasClick(state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(state.id) {
                val rightDismissPx = 80.dp.toPx()
                val upDismissPx = 48.dp.toPx()
                val leftClampPx = 24.dp.toPx()
                // 累计量在本作用域内同步维护（不回读 Animatable 当前值，避免逐事件 launch 的执行时序竞态）。
                var totalDy = 0f
                var totalDx = 0f
                detectDragGestures(
                    onDragStart = { totalDy = 0f; totalDx = offsetX.value },
                    onDragEnd = {
                        if (totalDx > rightDismissPx) {
                            AppNotice.dismissBanner()
                        } else {
                            scope.launch {
                                offsetX.animateTo(0f, animationSpec = MotionTokens.Spring.FastSpatialSpec)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetX.animateTo(0f, animationSpec = MotionTokens.Spring.FastSpatialSpec)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDy += dragAmount.y
                        totalDx = (totalDx + dragAmount.x).coerceAtLeast(-leftClampPx)
                        if (totalDy < -upDismissPx) {
                            AppNotice.dismissBanner()
                        } else {
                            scope.launch { offsetX.snapTo(totalDx) }
                        }
                    },
                )
            }
            .shadow(10.dp, shape)
            .clip(shape)
            .background(noticeFrostFill())
            // ACCEPT6（用户真机反馈 2026-11-04）：横幅描边与胶囊一并删除（整层淡出见宿主）。
            .then(
                if (hasClick) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "查看") {
                        AppNotice.triggerBannerClick(state.id)
                    }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            // ACCEPT6 二轮（2026-11-04 19:54）：横幅高度收紧——内边距 16/12→14/9、
            // 标题 14→13sp、正文 13→12sp；动作走 TextButton 自带最小高，命中区不缩。
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val body = state.body
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        color = colors.onSurface.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val actionLabel = state.actionLabel
            if (actionLabel != null) {
                TextButton(onClick = { AppNotice.triggerBannerAction(state.id) }) {
                    Text(
                        text = actionLabel,
                        color = xyz.cncnlp.soundwave.designAccentTextColor(),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlyphBadge(text: String, background: Color) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 成功轻触感：API 30+ CONFIRM，低版本降级 VIRTUAL_KEY。 */
private fun performConfirmHaptic(view: View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** 失败触感：API 30+ REJECT，低版本降级 CONTEXT_CLICK。 */
private fun performRejectHaptic(view: View) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    } else {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}
