package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ThemeTokens.MiniPlayer as MiniTokens

import xyz.cncnlp.soundwave.ui.common.rippleGlow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.ui.common.AppHaptics
import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.player.miniPlayerExpandShouldOpen
import xyz.cncnlp.soundwave.ui.player.miniPlayerExpandThresholdCrossed
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.dockGlass

// ===== 迷你播放条（设计稿 v2 .bottom-player；capsule = .bottom-player.mini-pill） =====
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MiniPlayer(
    // ACCEPT6-FREEZE：resolving 以 State 入参延迟读取（宿主根作用域不再订阅解析状态）。
    song: Song, playing: Boolean, progressState: androidx.compose.runtime.State<Float>, resolvingState: androidx.compose.runtime.State<Boolean>,
    lyricLine: String = "",
    compact: Boolean = false,
    fullWidth: Boolean = false,
    showLyricsWhenCompact: Boolean = false,
    onClick: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onSwipeDown: () -> Unit = {},
    // SW-12-06：上滑跟手位移回调（dragUpPx, screenHeightPx）；松手未打开时以 dragUpPx=0 回告，宿主据此复位同一进度持有者。
    onExpandDrag: (Float, Float) -> Unit = { _, _ -> },
    style: String = "default"
) {
    // BATCH2 反馈 F4：progressState 不在主体顶层读——播放 tick（Service 每 400ms 发布快照）
    // 曾使整条迷你条（封面/跑马灯/按钮）每 tick 重组。环形进度改在 drawBehind 的绘制阶段
    // 直接读 State.value：tick 只重绘该 Box，不触发本组件重组（宿主与页面同理受益）。
    val coverContext = LocalContext.current
    val designScheme = soundwaveDesignScheme(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    // 独立 dock 开关控制实色/磨砂路径；显式封面混色还需 pageFrosted && coverTint。透明层仍可能透出背景色。
    val isDarkMini = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val miniText = MiniTokens.ink(designScheme)
    val miniMuted = MiniTokens.muted(designScheme)
    val primary = MiniTokens.progress(designScheme)
    val progressTrack = MiniTokens.progressTrack(designScheme)
    val line = designScheme.outlineVariant
    // .mini-play：var(--ink) 底 + var(--paper) 实心三角
    val playBg = MiniTokens.playFill(designScheme)
    val playInk = MiniTokens.playInk(designScheme)
    val presentation = rememberMiniPlayerPresentation(style, compact, fullWidth)
    val capsuleStyle = presentation.capsule
    val embedded = presentation.embedded
    val compactEmbedded = presentation.compactEmbedded
    val miniHeight = presentation.height
    val coverSize = presentation.coverSize
    val sidePad = when {
        embedded -> 0.dp
        else -> 14.dp
    }
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val animateCover = MotionPolicy.allowDecorative(playing && capsuleStyle, visible = true, reduce = reduceMotion)
    // r655：仅在真正旋转时才创建无限动画；暂停/非胶囊样式不再每帧重组重绘（也让 UI 测试能进入空闲）。
    val coverRotState = if (animateCover) {
        val miniSpin = rememberInfiniteTransition(label = "miniCover")
        miniSpin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(MiniTokens.CoverSpinMillis, easing = LinearEasing), RepeatMode.Restart),
            label = "miniRot"
        )
    } else null
    // r654：圆角胶囊样式按副分支（feat/neumorphic-ui-refresh）做成整条 999dp 胶囊 + 圆形旋转封面。
    val cardShape = if (capsuleStyle) RoundedCornerShape(MiniTokens.CapsuleRadius) else RoundedCornerShape(MiniTokens.Radius)
    val nowInteraction = remember { MutableInteractionSource() }
    val nowPressed by nowInteraction.collectIsPressedAsState()
    val playInteraction = remember { MutableInteractionSource() }
    val playPressed by playInteraction.collectIsPressedAsState()
    // r657 修复：统一弹簧按压（按钮 0.92，按下 1800 刚度 / 松开 0.55 阻尼回弹），简洁动效时不缩放。
    val playScale by animateFloatAsState(
        xyz.cncnlp.soundwave.ui.common.PressMotion.target(playPressed, xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_ICON, reduceMotion),
        if (playPressed) xyz.cncnlp.soundwave.ui.common.PressMotion.pressSpec else xyz.cncnlp.soundwave.ui.common.PressMotion.releaseSpec, label = "miniPlayPress"
    )
    val nextLabel = "下一首：${song.name}"
    // SW-12-06 上滑跟手：expandAnim（px，正值 = 已上滑位移）是迷你条侧的展开进度，同一手势内可随时
    // 反向（snapTo 打断回弹、animateTo 打断跟手）；同一位移经 onExpandDrag 同步给宿主的同一
    // PlayerDismissOffsetHolder（屏高 − 位移），松手打开时播放器进场从该偏移续滑。
    val expandScope = rememberCoroutineScope()
    val expandAnim = remember { Animatable(0f) }
    val expandView = LocalView.current
    val expandDensity = LocalDensity.current
    val screenHeightPx = with(expandDensity) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 手势壳（不位移，保证指针坐标稳定）+ 视觉层（graphicsLayer 跟手位移）分离，同 FullPlayer 的外层位移 / 内层手势分工。
    Box(
        Modifier
            .padding(start = sidePad, end = sidePad)
            .fillMaxWidth()
            .height(miniHeight)
            .pointerInput(compact, screenHeightPx) {
                val velocityTracker = VelocityTracker()
                var totalX = 0f
                var totalY = 0f
                var nextFired = false
                var downFired = false
                var pastOpenThreshold = false
                detectDragGestures(
                    onDragStart = {
                        totalX = 0f; totalY = 0f; nextFired = false; downFired = false; pastOpenThreshold = false
                        velocityTracker.resetTracking()
                    },
                    onDragEnd = {
                        val dragUp = (-totalY).coerceAtLeast(0f)
                        val velocityUp = -velocityTracker.calculateVelocity().y
                        if (!nextFired && miniPlayerExpandShouldOpen(dragUp, screenHeightPx, velocityUp, density)) onSwipeUp()
                        else onExpandDrag(0f, screenHeightPx)
                        expandScope.launch { expandAnim.animateTo(0f, MotionTokens.Spring.SlowSpatialSpec) }
                    },
                    onDragCancel = {
                        onExpandDrag(0f, screenHeightPx)
                        expandScope.launch { expandAnim.animateTo(0f, MotionTokens.Spring.SlowSpatialSpec) }
                    },
                    onDrag = { change, amt ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        totalX += amt.x; totalY += amt.y
                        when {
                            // 横滑切歌沿用旧契约：一手势一次，且认领后不再驱动展开，避免与上滑跟手互相抢位移。
                            !nextFired && kotlin.math.abs(amt.x) > kotlin.math.abs(amt.y) && amt.x < -14f -> { nextFired = true; onNext() }
                            nextFired -> Unit
                            totalY < 0f -> {
                                val dragUp = -totalY
                                if (!reduceMotion) expandScope.launch { expandAnim.snapTo(dragUp) }
                                onExpandDrag(dragUp, screenHeightPx)
                                val crossed = miniPlayerExpandThresholdCrossed(dragUp, screenHeightPx)
                                if (crossed != pastOpenThreshold) {
                                    pastOpenThreshold = crossed
                                    AppHaptics.thresholdCrossed(expandView, crossed)
                                }
                            }
                            totalY > 8f && !downFired -> { downFired = true; onSwipeDown() }
                        }
                    }
                )
            }
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = if (reduceMotion) 0f else -expandAnim.value }
            // box-shadow: 0 12px 34px rgba(0,0,0,.16), 0 0 0 1px var(--line)
            .cssShadow(cardShape, designScheme.scrim.copy(alpha = xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCardAlpha), xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCardBlur, xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCardOffsetY)
            // r654：去掉边缘描边；磨砂不透底——不透明表面色，背后内容不再透出。
            // r657 修复：真实背景模糊（磨砂开）/ 实色（磨砂关，默认）/ 94% 实色降级（API ≤ 31），背后列表永远不清晰透出。
            .dockGlass(cardShape, MiniTokens.surface(designScheme), isDarkMini, line)
            .semantics { customActions = listOf(CustomAccessibilityAction(nextLabel) { onNext(); true }) }
            .clickable(interactionSource = nowInteraction, indication = null) { onClick() }
    ) {
        // r657（v3 #14）：去掉底边 / 行内进度线，进度改为播放键外圈的 2dp 珊瑚进度环（轨道 = surfaceVariant）。
        val inlineProgress = capsuleStyle && !compactEmbedded
        Row(
            Modifier.fillMaxSize()
                .padding(start = if (capsuleStyle) 6.dp else if (compactEmbedded) 6.dp else 10.dp, end = 10.dp, top = MiniTokens.VerticalPadding, bottom = MiniTokens.VerticalPadding)
                .graphicsLayer { alpha = if (nowPressed) MiniTokens.ContentPressedAlpha else 1f },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val coverShape = if (capsuleStyle) CircleShape else RoundedCornerShape(if (compactEmbedded) MiniTokens.CompactCoverRadius else MiniTokens.CoverRadius)
            Box(
                Modifier
                    .size(coverSize)
                    .graphicsLayer { coverRotState?.let { rotationZ = it.value } }
                    .then(
                        if (capsuleStyle) Modifier.padding(1.dp)
                        else Modifier.cssShadow(coverShape, designScheme.scrim.copy(alpha = xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCoverAlpha), xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCoverBlur, xyz.cncnlp.soundwave.ui.common.ShadowScale.DockCoverOffsetY)
                    )
                    .clip(coverShape)
                    .background(designScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // ACCEPT5 收编：默认封面常驻垫底——加载/失败不再是 surfaceVariant 平灰。
                DefaultLandscapeCover(Modifier.fillMaxSize())
                if (song.cover.isNotEmpty()) {
                    AsyncImage(
                        model = CacheManager.coverModel(coverContext, song.cover) ?: song.cover, contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(coverShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Spacer(Modifier.width(if (capsuleStyle) 12.dp else 11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.name, color = miniText, style = presentation.titleStyle, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().loopMarquee()
                )
                Spacer(Modifier.height(presentation.textGap))
                Text(
                    miniPlayerSubtitle(song, resolvingState.value, compact, showLyricsWhenCompact, lyricLine),
                    color = miniMuted, style = presentation.subtitleStyle, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().loopMarquee()
                )
            }
            Spacer(Modifier.width(8.dp))
            val ringSize = if (compactEmbedded) MiniTokens.CompactProgressRing else MiniTokens.ProgressRing
            Box(
                Modifier.size(ringSize).drawBehind {
                    // F4：绘制阶段读进度（见主体顶部注）——State 在 draw 作用域的读取只失效绘制，
                    // 不使 MiniPlayer 主体重组。
                    val tickProgress = progressState.value
                    val stroke = MiniTokens.ProgressStroke.toPx()
                    val inset = stroke / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(progressTrack, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawArc(primary, -90f, 360f * tickProgress.coerceIn(0f, 1f), false, topLeft, arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                },
                contentAlignment = Alignment.Center
            ) {
            Box(
                Modifier.size(if (compactEmbedded) MiniTokens.CompactPlayTarget else MiniTokens.PlayTarget)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale }
                    .clip(CircleShape)
                    .background(playBg)
                    .rippleGlow(playInk.copy(alpha = 0.40f)) // r657 审计第 21 项
                    .clickable(interactionSource = playInteraction, indication = null) { onToggle() }
                    .semantics { contentDescription = if (playing) "暂停：${song.name}" else "播放：${song.name}"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) DesignChromeGlyphs.PauseBold else DesignChromeGlyphs.PlaySolid, null,
                    Modifier.size(if (compactEmbedded) MiniTokens.CompactPlayIcon else MiniTokens.PlayIcon).offset(x = if (playing) 0.dp else 1.dp), tint = playInk
                )
            }
            }
        }
    }
    }
}
