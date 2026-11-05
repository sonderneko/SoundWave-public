package xyz.cncnlp.soundwave

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.particleBurst
import xyz.cncnlp.soundwave.ui.common.rememberParticleBurstState
import xyz.cncnlp.soundwave.ui.common.frostedCardFill
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs

/** 封面：真实封面 → 设计稿默认图 → 本地绘制的分类默认封面。 */
@Composable
private fun SleeveCover(cover: String?, defaultRes: Int?, fallbackKind: LibraryDefaultCoverKind, description: String?, modifier: Modifier) {
    if (!cover.isNullOrBlank() || defaultRes == null) LibraryCoverArtwork(cover = cover.orEmpty(), fallbackKind = fallbackKind, contentDescription = description, modifier = modifier)
    else Image(painterResource(defaultRes), description, modifier, contentScale = ContentScale.Crop)
}

/** 纯函数：唱片套卡在给定内宽下的排版（单测覆盖 360 / 411dp）。 */
internal data class SleeveLayout(val artW: Float, val side: Float, val textW: Float, val buttonsBelow: Boolean, val titleSp: Float)

internal fun sleeveLayout(innerDp: Float, fontScale: Float = 1f): SleeveLayout {
    val artW = (innerDp * 0.54f).coerceIn(150f, 196f)
    val side = artW * 0.714f
    val textW = innerDp - artW - 10f
    // 播放胶囊（左右内距 15 + 图标 14 + 间距 6 + 两个汉字）+ 8 + 随机圆钮 40dp；放不下时按钮行折到卡片底部。
    val buttonsW = 15f + 14f + 6f + 26f * fontScale + 15f + 8f + 40f
    val below = textW < buttonsW + 2f
    // 「我的喜欢」四个字 ≈ 4.4 × 字号：按右栏宽度取 18–26sp，永不截断。
    val titleSp = (textW / (4.6f * fontScale)).coerceIn(18f, 26f)
    return SleeveLayout(artW, side, textW, below, titleSp)
}

/** 纯函数：r658c U #4「N 首 · X小时Y分」（如 123 首 · 8小时12分；不足 1 小时「Y分钟」；整点「X小时」）。 */
internal fun favoritesSubtitle(count: Int, totalMs: Long): String {
    if (count <= 0) return "0 首"
    if (totalMs <= 0L) return "$count 首"
    val minutes = totalMs / 60_000L
    if (minutes < 1) return "$count 首"
    val h = minutes / 60; val m = minutes % 60
    return when {
        h == 0L -> "$count 首 · ${m}分钟"
        m == 0L -> "$count 首 · ${h}小时"
        else -> "$count 首 · ${h}小时${m}分"
    }
}

/**
 * r657（v4 #5）：音乐库「我的喜欢」唱片套卡——封面 + 半露出的黑胶（播放时转动），右侧眉题 / 衬线标题 / 副标题，
 * 珊瑚发光「播放 / 暂停」胶囊 + 线框随机键。不显示 ♥ 数量角标；点卡片进入详情，长按打开更多。
 * r657 修复：按内宽自适应（360dp 真机不再截断 / 挤扁），黑胶 5.5s 一圈、播放时滑出 18dp、暂停 600ms 减速停住。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryFavoritesSleeveCard(
    title: String,
    eyebrow: String,
    subtitle: String,
    cover: String?,
    defaultRes: Int?,
    enabled: Boolean,
    spinning: Boolean,
    reduceMotion: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    onPause: () -> Unit = onPlay,
    /** r658b #11：唱片中心显示当前播放歌曲封面（切歌时淡入更新）；为空时退回歌单封面。 */
    discCover: String? = null
) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val dark = colors.background.luminance() < 0.4f
    val shape = RoundedCornerShape(28.dp) // 原型 .fav r28
    val rotation = remember { Animatable(0f) }
    val playBurst = rememberParticleBurstState()
    var optimisticOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(spinning) { optimisticOn = null }
    val pillOn = optimisticOn ?: spinning
    // r658d（用户 10-20 快速点击卡顿）：250ms 内的连点只认第一次，避免连续触发起播 / 暂停事务。
    var lastPillTap by remember { mutableStateOf(0L) }
    fun pillTapAllowed(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPillTap < 250L) return false
        lastPillTap = now
        return true
    }
    LaunchedEffect(spinning, reduceMotion) {
        if (spinning && !reduceMotion) {
            while (true) {
                val from = rotation.value % 360f
                rotation.snapTo(from)
                rotation.animateTo(from + 360f, tween(5500, easing = LinearEasing))
            }
        } else if (!reduceMotion) {
            // 暂停：600ms 减速停在当前角度附近，不跳回 0°。
            rotation.animateTo(rotation.value + 24f, tween(600, easing = LinearOutSlowInEasing))
        }
    }
    val slide by animateDpAsState(
        if (spinning) 18.dp else 0.dp,
        if (reduceMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 380f), label = "vinylSlide"
    )
    // r657 第三轮（用户 10-18 真机：与原型不符）：照原型 app.css .fav 重做——196dp 高、r28、表面色 + 1dp 线框 + shadow-sm，
    // ::before 右上珊瑚 22% / 左下蓝 12% 径向光；封面 .sleeve 140dp r16（left 18 top 28，0 14 30 投影）、黑胶 .disc 136dp（left 64 top 30，
    // 播放时右移 18dp 并旋转）；右栏 .info（left 226 right 16 top 30 bottom 24）：DM Mono 9.5sp 眉题 → 8dp → 衬线 600 26/30 标题 → 4dp → 12sp 副标题，
    // 底部 .acts：42dp 珊瑚「播放 / 暂停」胶囊（flex 1，800 14sp，15dp 图标，间距 6）+ 8dp + 42dp 线框随机键。窄屏按卡宽等比缩小图形区。
    val glowA = accent.copy(alpha = 0.22f)
    val glowB = SoundwaveDesignPalette.Accent.copy(alpha = 0.12f)
    // r658b #11：卡片按原型 .fav 等比——原型卡宽 362（390 宽机身 - 28）时高 196；窄屏整体按 k 缩放（含卡高），不再只缩图形区留白。
    val cardK = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 28f).let { (it / 362f).coerceIn(0.8f, 1f) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height((196f * cardK).dp)
            // r658c #2：歌单卡点按不再整卡缩放，直接普通页面切换。
            .cssShadow(shape, Color(0xFF131915).copy(alpha = if (dark) 0.35f else 0.07f), 26.dp, 10.dp)
            .clip(shape)
            .background(frostedCardFill(colors.surface))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(glowA, Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.70f))
                drawRect(Brush.radialGradient(listOf(glowB, Color.Transparent), center = Offset(0f, size.height), radius = size.width * 0.60f))
            }
            // r658h（用户 10-20）：我的喜欢 / 本地歌单卡去掉 1dp 描边，只靠投影分层。
            .rippleGlow(accent.copy(alpha = 0.14f))
            .combinedClickable(onClick = onOpen, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onMore() })
            .semantics { contentDescription = title }
    ) {
        val k = cardK
        val sleeve = (140f * k).dp
        val discSize = (136f * k).dp
        val infoLeft = (226f * k).dp
        val infoW = maxWidth - infoLeft - 16.dp
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val titleSp = (infoW.value / (4.3f * fontScale)).coerceIn(18f, 26f)
        // 黑胶（在封面下层）：播放时右移 18dp 并 5.5s 一圈；锥形高光不随唱片转动。
        Box(Modifier.offset(x = (64f * k).dp + slide, y = (30f * k).dp).size(discSize)
            .cssShadow(CircleShape, Color.Black.copy(alpha = 0.35f), 26.dp, 12.dp)) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation.value }) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color(0xFF111211), radius = size.minDimension / 2f, center = c)
                    var r = size.minDimension / 2f - 1.dp.toPx()
                    while (r > size.minDimension * 0.17f) {
                        drawCircle(Color(0xFF1B1D1B), radius = r, center = c, style = Stroke(1.2.dp.toPx()))
                        r -= 2.6.dp.toPx()
                    }
                    drawCircle(Color(0xFF0C0D0C), radius = size.minDimension * 0.17f, center = c)
                }
                // r658b #11：中心标签 = 当前歌曲封面，切歌时 260ms 交叉淡入。
                val centre = discCover?.takeIf { it.isNotBlank() } ?: cover
                androidx.compose.animation.Crossfade(centre, modifier = Modifier.align(Alignment.Center), animationSpec = tween(if (reduceMotion) 0 else 260), label = "discCover") { c ->
                    SleeveCover(c, defaultRes, LibraryDefaultCoverKind.LIKED, null,
                        Modifier.size((48f * k).dp).clip(CircleShape))
                }
                Box(Modifier.align(Alignment.Center).size(6.dp).clip(CircleShape).background(colors.surface))
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color.White.copy(alpha = 0.10f), 0.093f to Color.Transparent, 0.453f to Color.Transparent,
                        0.513f to Color.White.copy(alpha = 0.16f), 0.593f to Color.Transparent, 0.953f to Color.Transparent, 1f to Color.White.copy(alpha = 0.10f)
                    ),
                    radius = size.minDimension / 2f
                )
            }
        }
        SleeveCover(
            cover, defaultRes, LibraryDefaultCoverKind.LIKED, "${title}封面",
            Modifier.offset(x = (18f * k).dp, y = (28f * k).dp).size(sleeve)
                .cssShadow(RoundedCornerShape(16.dp), Color(0xFF131915).copy(alpha = if (dark) 0.55f else 0.22f), 30.dp, 14.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        // 原型 .sleeve .gloss：155° 白 24% → 0（36%）高光 + 底部黑 16% → 0（40%）。
        Box(Modifier.offset(x = (18f * k).dp, y = (28f * k).dp).size(sleeve).clip(RoundedCornerShape(16.dp)).drawBehind {
            drawRect(Brush.linearGradient(0f to Color.White.copy(alpha = 0.24f), 0.36f to Color.Transparent,
                start = Offset(size.width * 0.29f, 0f), end = Offset(size.width * 0.71f, size.height)))
            drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f)))
        })
        Column(Modifier.offset(x = infoLeft).padding(top = (30f * k).dp, bottom = (24f * k).dp).width(infoW).fillMaxHeight()) {
            Text(eyebrow, color = accent, fontSize = 9.5.sp, lineHeight = 11.4.sp, letterSpacing = 0.16.em, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
            Text(
                title, color = colors.onSurface, fontSize = titleSp.sp, lineHeight = (titleSp * 30f / 26f).sp, letterSpacing = (-0.04).em,
                fontFamily = DesignFonts.SerifCJK, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // r658c U #4：播放 / 暂停胶囊宽度按原型 .fav .acts（卡宽 362 时约 70dp，按内容收拢，不再撑满右栏），文字 13→12sp。
                Row(
                    // r658d（用户 10-20）：播放 / 暂停胶囊再窄一点（70→60dp、左右内距 14→11dp）。
                    Modifier.height(42.dp).widthIn(min = (60f * k).dp)
                        .particleBurst(playBurst, accent, Color(0xFFFFD678), count = 10, radius = 46.dp, particleSize = 2.5.dp)
                        .pressScale(PressMotion.SCALE_DEFAULT)
                        // r658c B：按原型 .pill2 box-shadow 0 10px 22px rgba(255,92,69,.45)（CSS blur 22px ≈ 11dp 半径），柔和不外溢。
                        .accentGlow(alpha = if (enabled) 0.38f else 0.16f, blur = 11.dp, offsetY = 7.dp)
                        // r658b #11：原型 .pill2 外圈 0 0 0 4px rgba(255,92,69,.12) 柔环。
                        .drawBehind {
                            val ring = 4.dp.toPx()
                            drawRoundRect(accent.copy(alpha = if (enabled) 0.12f else 0.06f), topLeft = Offset(-ring, -ring),
                                size = androidx.compose.ui.geometry.Size(size.width + ring * 2, size.height + ring * 2),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f + ring))
                        }
                        .clip(CircleShape)
                        .drawBehind {
                            // 原型 radial-gradient(120% 140% at 30% 0%, #FF8A73 0, #FF5C45 50%, #E8452F 100%) + 顶部 1px 白 45% 内高光。
                            drawRect(Brush.radialGradient(
                                0f to Color(0xFFFF8A73), 0.5f to Color(0xFFFF5C45), 1f to Color(0xFFE8452F),
                                center = Offset(size.width * 0.3f, 0f), radius = maxOf(size.width * 1.2f, size.height * 1.4f)
                            ))
                            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f), 1.dp.toPx() / size.height to Color.Transparent))
                        }
                        .alpha(if (enabled) 1f else 0.5f)
                        .rippleGlow(Color.White.copy(alpha = 0.55f))
                        // r658c U #4（点击卡顿）：先乐观切换按钮态（下一帧即显示暂停 / 播放），真实播放态到达后以其为准；
                        // 取链 / 起播在宿主协程里进行，按钮不再等它返回才变化。
                        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = if (pillOn) ({ if (pillTapAllowed()) { optimisticOn = false; onPause() } }) else ({ if (pillTapAllowed()) { optimisticOn = true; playBurst.burst(); onPlay() } }))
                        .semantics { contentDescription = (if (pillOn) "暂停" else "播放") + title; role = Role.Button }
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    AnimatedContent(pillOn, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) }, label = "sleevePlay") { on ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on) FavPillGlyphs.Pause else FavPillGlyphs.Play, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (on) "暂停" else "播放", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.width(14.dp)) // r658d（用户 10-20）：播放与随机播放间距 8→14dp
                Box(
                    Modifier.requiredSize(42.dp).pressScale(PressMotion.SCALE_ICON).clip(CircleShape)
                        .background(frostedCardFill(colors.surface).copy(alpha = 0.62f))
                        .border(1.dp, colors.outline, CircleShape)
                        .alpha(if (enabled) 1f else 0.5f)
                        .rippleGlow(accent.copy(alpha = 0.22f))
                        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onShuffle)
                        .semantics { contentDescription = "随机播放$title"; role = Role.Button },
                    contentAlignment = Alignment.Center
                ) { Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Shuffle, null, tint = colors.onSurface, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

/** r657（v3 #10）：「本地歌单」横向卡——封面无手机图标、无底色「扫描」文字按钮。r657 修复：白卡 r24 + 柔和投影、取景框扫描图标。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryLocalRowCard(
    title: String,
    subtitle: String,
    cover: String?,
    defaultRes: Int?,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    /** r657 修复（审计第 24 项）：扫描中显示呼吸光球 + 中线上下扫动；结束时整卡 600ms 高光掠过。 */
    scanning: Boolean = false
) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val dark = colors.background.luminance() < 0.4f
    val shape = RoundedCornerShape(24.dp)
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val sheen = remember { androidx.compose.animation.core.Animatable(1f) }
    var wasScanning by remember { mutableStateOf(scanning) }
    LaunchedEffect(scanning, reduce) {
        if (wasScanning && !scanning && !reduce) {
            sheen.snapTo(0f); sheen.animateTo(1f, tween(ScanMotion.SHEEN_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        wasScanning = scanning
    }
    Row(
        modifier.fillMaxWidth()
            // r658c #2：歌单卡点按不再整卡缩放，直接普通页面切换。
            // r657 第三轮：原型 .local——84dp、r24、表面色 + 1dp 线框 + shadow-sm（0 10 26 rgba(19,25,21,.07)）。
            .height(84.dp)
            .cssShadow(shape, Color(0xFF131915).copy(alpha = if (dark) 0.35f else 0.07f), 26.dp, 10.dp)
            .clip(shape)
            .background(frostedCardFill(colors.surface))
            // r658h（用户 10-20）：我的喜欢 / 本地歌单卡去掉 1dp 描边，只靠投影分层。
            .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.12f))
            .drawWithContent {
                drawContent()
                val p = sheen.value
                if (p < 1f) {
                    // 完成高光：一条 35% 宽的斜向白光从左掠到右。
                    val band = size.width * 0.35f
                    val x = -band + (size.width + band * 2f) * p
                    drawRect(Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = if (dark) 0.16f else 0.55f), Color.Transparent),
                        start = Offset(x - band, 0f), end = Offset(x + band, size.height)
                    ))
                }
            }
            .combinedClickable(onClick = onOpen, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onMore() })
            .semantics { contentDescription = title }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SleeveCover(
            cover, defaultRes, LibraryDefaultCoverKind.LOCAL_PLAYLIST, "${title}封面",
            Modifier.size(60.dp)
                .cssShadow(RoundedCornerShape(16.dp), Color(0xFF131915).copy(alpha = 0.18f), 18.dp, 8.dp) // .lc 60 r16 0 8 18 .18
                .clip(RoundedCornerShape(16.dp))
        )
        // 用户 10-18「标题字号偏大」：原型 .local b 600 18/22 衬线 -.025em；副标题 12sp 灰、上 4dp。
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = colors.onSurface, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.025).em, fontFamily = DesignFonts.SerifCJK, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Row(
            Modifier.pressScale(PressMotion.SCALE_DEFAULT).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onScan)
                .semantics { contentDescription = "扫描本地歌曲"; role = Role.Button }
                .height(36.dp).padding(horizontal = 6.dp), // 原型 .scanbtn（v3 无底色）36dp、左右 6、间距 6
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (scanning) ScanningGlyph(accent, reduce, Modifier.size(16.dp)) else ScanFrameGlyph(accent, Modifier.size(16.dp))
            Text(if (scanning) "扫描中" else "扫描", color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
        }
    }
}

internal object ScanMotion {
    const val BREATH_MS = 2600
    const val SWEEP_MS = 1400
    const val SHEEN_MS = 600
    /** 纯函数：扫描线在取景框内的纵向位置（0..1 往复），t 为 0..1 循环进度。 */
    fun sweepY(t: Float): Float { val x = (t % 1f + 1f) % 1f; return 0.22f + 0.56f * (if (x < 0.5f) x * 2f else 2f - x * 2f) }
}

/** 扫描中：取景框四角 + 上下扫动的中线，背后一颗呼吸光球（原型 ThinkingOrb）。简洁动效时静止。 */
@Composable
internal fun ScanningGlyph(tint: Color, reduceMotion: Boolean, modifier: Modifier) {
    val reduce = !xyz.cncnlp.soundwave.MotionPolicy.allowDecorativeLoops(reduceMotion)
    var breath = 0.25f; var sweep = 0.25f
    if (!reduce) {
        val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "scanLoop")
        breath = loop.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(ScanMotion.BREATH_MS, easing = LinearEasing)), label = "breath").value
        sweep = loop.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(ScanMotion.SWEEP_MS, easing = LinearEasing)), label = "sweep").value
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height; val sw = w * 0.11f; val k = w * 0.30f; val i = sw / 2f
        val b = if (reduce) 0.5f else (0.5f - 0.5f * kotlin.math.cos(breath * 2f * Math.PI.toFloat()))
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.18f + 0.22f * b), Color.Transparent), center = center, radius = w * (0.85f + 0.35f * b)), radius = w * (0.85f + 0.35f * b))
        val stroke = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(x, y + dy * k); lineTo(x, y); lineTo(x + dx * k, y) }
            drawPath(path, tint, style = stroke)
        }
        corner(i, i, 1f, 1f); corner(w - i, i, -1f, 1f); corner(i, h - i, 1f, -1f); corner(w - i, h - i, -1f, -1f)
        val y = h * (if (reduce) 0.5f else ScanMotion.sweepY(sweep))
        drawLine(tint, Offset(w * 0.22f, y), Offset(w * 0.78f, y), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** 原型扫描图标（icons.js `scan`，24 栅格、1.8 描边、圆端点）：四个圆角取景框角 + 中横线。r657 第三轮按原型路径重做。 */
internal val ScanFrameVector: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("sw_scan", 24.dp, 24.dp, 24f, 24f).apply {
        listOf(
            "M4 8.5V6.6A2.6 2.6 0 0 1 6.6 4h1.9M15.5 4h1.9A2.6 2.6 0 0 1 20 6.6v1.9M20 15.5v1.9a2.6 2.6 0 0 1-2.6 2.6h-1.9M8.5 20H6.6A2.6 2.6 0 0 1 4 17.4v-1.9",
            "M8.5 12h7"
        ).forEach { d ->
            addPath(
                pathData = androidx.compose.ui.graphics.vector.addPathNodes(d), fill = null,
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        }
    }.build()
}

@Composable
internal fun ScanFrameGlyph(tint: Color, modifier: Modifier) {
    Icon(ScanFrameVector, null, tint = tint, modifier = modifier)
}


/** r658b #11：原型 icf('play') / icf('pause') 实心图形（圆角三角、两根 4×14 r1.6 圆角竖条），供「我的喜欢」播放胶囊使用。 */
private object FavPillGlyphs {
    val Play: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        androidx.compose.ui.graphics.vector.ImageVector.Builder("sw_fav_play", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                androidx.compose.ui.graphics.vector.addPathNodes("M8 5.8v12.4c0 1 1.1 1.6 1.9 1.1l9.7-6.2c.8-.5.8-1.7 0-2.2L9.9 4.7C9.1 4.2 8 4.8 8 5.8z"),
                fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
            )
        }.build()
    }
    val Pause: androidx.compose.ui.graphics.vector.ImageVector by lazy {
        androidx.compose.ui.graphics.vector.ImageVector.Builder("sw_fav_pause", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(androidx.compose.ui.graphics.vector.addPathNodes("M8.1 5h0.8a1.6 1.6 0 0 1 1.6 1.6v10.8a1.6 1.6 0 0 1 -1.6 1.6h-0.8a1.6 1.6 0 0 1 -1.6 -1.6v-10.8a1.6 1.6 0 0 1 1.6 -1.6z"), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
            addPath(androidx.compose.ui.graphics.vector.addPathNodes("M15.1 5h0.8a1.6 1.6 0 0 1 1.6 1.6v10.8a1.6 1.6 0 0 1 -1.6 1.6h-0.8a1.6 1.6 0 0 1 -1.6 -1.6v-10.8a1.6 1.6 0 0 1 1.6 -1.6z"), fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
        }.build()
    }
}
