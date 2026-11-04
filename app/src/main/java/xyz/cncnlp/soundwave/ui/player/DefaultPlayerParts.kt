package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.DefaultLandscapeCover
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.PressMotion

/** 「默认」播放器的配色：完全取自应用设计主题（浅色纸面 / 深色墨面 + 珊瑚强调），随应用浅/深色切换。 */
@Immutable
internal data class DefaultPlayerColors(
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val dark: Boolean
) {
    val onAccent: Color get() = Color.White
    /** SW-12-15（G 波）：眉题等小号珊瑚文字——浅色（纸面）时加深 #C8381F，深色时保持 Accent。填充/进度仍用 accent。 */
    val accentText: Color get() = if (dark) accent else SoundwaveDesignPalette.AccentText
}

@Composable
internal fun defaultPlayerColors(): DefaultPlayerColors {
    val c = MaterialTheme.colorScheme
    return DefaultPlayerColors(
        background = c.background, surface = c.surface, surface2 = c.surfaceVariant,
        ink = c.onBackground, muted = c.onSurfaceVariant, line = c.outline, accent = c.primary,
        dark = c.background.luminance() < 0.5f
    )
}

internal fun defaultPlayerTime(seconds: Float): String {
    val value = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(value / 60, value % 60)
}

/** r657 修复：进度时间（原型「1:51」：DM Mono 11sp、字距 0.5sp，不用宽字距眉题）。 */
@Composable
internal fun DefaultPlayerTimeText(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, maxLines = 1)
}

/** 等宽英文眉题（设计稿 mono eyebrow）：DM Mono、大写、宽字距。 */
@Composable
internal fun DefaultPlayerEyebrow(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 10.sp, spacing: TextUnit = 2.sp, weight: FontWeight = FontWeight.Medium) {
    Text(
        text, color = color, fontFamily = DesignFonts.DmMono, fontWeight = weight,
        fontSize = size, lineHeight = size * 1.3f, letterSpacing = spacing, maxLines = 1, modifier = modifier
    )
}

/** 背景：主题纸面 + 顶部封面色晕（重模糊的封面向纸面渐隐），不使用固定深色背板。 */
@Composable
internal fun DefaultPlayerBackdrop(cover: String, modifier: Modifier = Modifier, playing: Boolean = false) {
    val colors = defaultPlayerColors()
    val reduceMotion = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    // r657：默认 = App 现有暖色纸面 + 顶部封面色晕；只有「透明磨砂背景」打开时才换成磨砂层（「磨砂随封面取色」决定是否取封面色）。
    if (xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive) {
        Box(modifier) {
            xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop(Modifier.matchParentSize(), colors.dark)
            PlayerMotes(playing, reduceMotion, colors.accent, Modifier.matchParentSize())
        }
        return
    }
    val ctx = LocalContext.current
    // r657 修复（审计第 13 项）：封面色晕 16s 往复漂移（1.20↔1.28），切歌 600ms 交叉淡化；暂停 / 简洁动效时静止。
    val drift = if (playing && xyz.cncnlp.soundwave.MotionPolicy.allowDecorativeLoops(reduceMotion)) {
        val t = rememberInfiniteTransition(label = "playerBackdropDrift")
        t.animateFloat(1.20f, 1.28f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse), label = "driftScale")
    } else null
    Box(modifier.background(colors.background)) {
        // Modifier.blur 仅 Android 12+ 生效；更低版本不画封面色晕，避免露出清晰放大的封面。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Crossfade(cover, animationSpec = tween(if (reduceMotion) 0 else 600), label = "playerBackdropCover") { c ->
                if (c.isNotEmpty()) {
                    AsyncImage(
                        model = CacheManager.coverModel(ctx, c) ?: c, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f).graphicsLayer {
                            val s = drift?.value ?: 1.24f
                            alpha = if (colors.dark) 0.42f else 0.30f; scaleX = s; scaleY = s
                        }.blur(70.dp)
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.10f),
                    0.38f to colors.background.copy(alpha = 0.55f),
                    0.62f to colors.background,
                    1f to colors.background
                )
            )
        )
        PlayerMotes(playing, reduceMotion, colors.accent, Modifier.matchParentSize())
    }
}

/** 圆形描边图标按钮（描边 = 首页顶栏切换按钮描边色 outline）。 */
@Composable
internal fun DefaultPlayerCircleButton(
    icon: ImageVector,
    description: String,
    colors: DefaultPlayerColors,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    tint: Color = colors.ink,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.size(size).pressScale(PressMotion.SCALE_ICON)
            .clip(CircleShape)
            .background(if (filled) colors.surface else colors.surface.copy(alpha = if (colors.dark) 0.35f else 0.55f))
            .border(1.dp, colors.line, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(iconSize), tint = tint) }
}

/** 无底色的纯图标按钮（上一首/下一首/模式等）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DefaultPlayerIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    iconWidthFraction: Float = 1f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Box(
        // ACCEPT5-press（稿 §4）：触感只在状态改变时发——原 PressHaptic.Light 在按下帧即震，
        // 已退场；点按类 tick 由各调用点在 onClick（确认点击）内自备，长按在此统一发 long。
        modifier.size(size).pressScale(PressMotion.SCALE_ICON)
            .clip(CircleShape)
            // r657：按下出现浅底（v4「按下缩小并出现浅底」）。
            .background(if (pressed) tint.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick?.let { cb -> { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); cb() } }, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, Modifier.size(iconSize).graphicsLayer { scaleX = iconWidthFraction }, tint = tint) }
}

/** 珊瑚色主播放键：实心圆 + 同色柔光。 */
@Composable
internal fun DefaultPlayerPlayButton(
    playing: Boolean,
    colors: DefaultPlayerColors,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Box(
        // ACCEPT5-press（稿 §4）：播放/暂停 = tick，改在确认点击时发（原按下帧 Light 已退场）。
        modifier.size(size).pressScale(PressMotion.SCALE_ICON)
            .cssShadow(CircleShape, colors.accent.copy(alpha = if (colors.dark) 0.45f else 0.38f), 26.dp, 10.dp)
            .clip(CircleShape).background(colors.accent)
            .clickable(interactionSource = interaction, indication = null, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onClick() })
            .semantics { contentDescription = if (playing) "暂停" else "播放"; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (playing) DefaultPlayerGlyphs.Pause else DefaultPlayerGlyphs.Play, null,
            Modifier.size(size * 0.40f).offset(x = if (playing) 0.dp else size * 0.025f), tint = colors.onAccent
        )
    }
}

/** 播放进度拖动的档位步长（秒）：用户 2026-11-04 裁定——拖动以 5 秒为一档吸附。 */
internal const val SEEK_STEP_SECONDS = 5f

/**
 * 拖动位置（分数）吸附到最近的 [SEEK_STEP_SECONDS] 整数倍秒；时长未知或不超过一档时不吸附（原样返回）。
 * 吸附落在宿主的时间换算层（FullPlayer 的 dragRatio 赋值处）：进度条组件本身只工作在分数空间且被音量条
 * 复用，不感知时长；后续波形进度条样式同样经宿主接线，天然继承本档位（见 WAVE-ACCEPT6-PLAYERDIALOGS 台账交接节）。
 */
internal fun seekStepSnapRatio(ratio: Float, durationSec: Float): Float {
    val r = ratio.coerceIn(0f, 1f)
    if (durationSec <= SEEK_STEP_SECONDS || r.isNaN()) return r
    val snappedSec = kotlin.math.round(r * durationSec / SEEK_STEP_SECONDS) * SEEK_STEP_SECONDS
    return (snappedSec / durationSec).coerceIn(0f, 1f)
}

/** 分数位置对应的档位序号（跨档触感判定用）；不吸附口径下恒为 0。 */
internal fun seekStepIndex(ratio: Float, durationSec: Float): Int =
    if (durationSec <= SEEK_STEP_SECONDS) 0
    else kotlin.math.round(seekStepSnapRatio(ratio, durationSec) * durationSec / SEEK_STEP_SECONDS).toInt()

/** 细进度条：圆角轨道 + 珊瑚已播段 + 珊瑚圆点；支持点按跳转与水平拖动。 */
@Composable
internal fun DefaultPlayerSeekBar(
    progress: Float,
    colors: DefaultPlayerColors,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumb: Dp = 12.dp,
    touchHeight: Dp = 28.dp,
    onSeekTap: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit = onDragEnd
) {
    val tap by rememberUpdatedState(onSeekTap)
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    // ACCEPT6-motiondraft（稿 .v7p-seek .bar.drag）：拖动中拇指放大 ×1.4、轨道 4→6dp，
    // 松手弹簧弹回；仅视觉，不动拖动/回调语义。「简洁动效」时恒为静止态。
    var seekDragging by remember { mutableStateOf(false) }
    val reduceSeekMotion = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val dragT by animateFloatAsState(
        if (seekDragging) 1f else 0f,
        if (reduceSeekMotion) snap() else xyz.cncnlp.soundwave.ui.motion.MotionTokens.Spring.FastSpatialSpec,
        label = "seekDragT"
    )
    BoxWithConstraints(
        modifier.fillMaxWidth().height(touchHeight)
            .pointerInput(Unit) { detectTapGestures { o -> tap((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                var active = false
                try {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> active = true; seekDragging = true; start(); drag((o.x / size.width).coerceIn(0f, 1f)) },
                        onDragEnd = { active = false; seekDragging = false; end() },
                        onDragCancel = { active = false; seekDragging = false; cancel() }
                    ) { change, _ -> change.consume(); drag((change.position.x / size.width).coerceIn(0f, 1f)) }
                } finally {
                    if (active) { seekDragging = false; cancel() }
                }
            }
            .semantics { contentDescription = "播放进度 ${(progress * 100).toInt()}%" },
        contentAlignment = Alignment.CenterStart
    ) {
        val p = progress.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(trackHeight + 2.dp * dragT).clip(RoundedCornerShape(99.dp)).background(colors.ink.copy(alpha = if (colors.dark) 0.16f else 0.10f))) {
            Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(colors.accent))
        }
        // r657 第三轮：原型圆点 = 12dp 实心珊瑚 + 4dp 墨色 12% 光环（app.css .pprog .bar i::after），无白芯。
        Box(
            Modifier.offset(x = (maxWidth - thumb) * p - 4.dp).size(thumb + 8.dp)
                .graphicsLayer { val s = 1f + 0.4f * dragT; scaleX = s; scaleY = s }
                .clip(CircleShape).background(colors.ink.copy(alpha = 0.12f))
        ) { Box(Modifier.align(Alignment.Center).size(thumb).clip(CircleShape).background(colors.accent)) }
    }
}

/** 封面（圆角 + 柔和投影），无封面时回落默认插画。[targetSizePx] 为列表小图位传小尺寸，避免整队按 1080 大图冷下载挤爆封面并发闸（ACCEPT5 数据侧）。 */
@Composable
internal fun DefaultPlayerArtwork(cover: String, description: String, modifier: Modifier, corner: Dp, targetSizePx: Int? = null) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape)) {
        // ACCEPT5 收编：默认封面常驻垫底——加载/失败不再透明透出播放器底。
        DefaultLandscapeCover(Modifier.fillMaxSize())
        if (cover.isNotEmpty()) {
            AsyncImage(
                model = CacheManager.coverModel(ctx, cover, targetSizePx) ?: cover, contentDescription = description,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 顶栏：左收起圆钮、中间眉题 + 小字、右侧圆钮。 */
@Composable
internal fun DefaultPlayerTopBar(
    eyebrow: String,
    caption: String,
    colors: DefaultPlayerColors,
    leftIcon: ImageVector,
    leftDescription: String,
    onLeft: () -> Unit,
    rightIcon: ImageVector,
    rightDescription: String,
    onRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        DefaultPlayerCircleButton(leftIcon, leftDescription, colors, onClick = onLeft)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DefaultPlayerEyebrow(eyebrow, colors.accentText)
            if (caption.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(caption, color = colors.muted, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = DesignFonts.Manrope, maxLines = 1)
            }
        }
        DefaultPlayerCircleButton(rightIcon, rightDescription, colors, onClick = onRight)
    }
}

/** r657：播放器 / 歌词页顶部唯一的小白条（点按收起；下拉收起由播放器手势负责）。 */
@Composable
internal fun DefaultPlayerGrabBar(colors: DefaultPlayerColors, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(26.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(width = 38.dp, height = 5.dp).clip(RoundedCornerShape(99.dp)).background(colors.ink.copy(alpha = 0.22f))) // v5 .pgrab 38×5 墨 22%
    }
}
