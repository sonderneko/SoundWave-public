package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.loopMarquee
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.playModeLabel
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.ParticleStyle
import xyz.cncnlp.soundwave.ui.common.particleBurst
import xyz.cncnlp.soundwave.ui.common.popOnBecomeTrue
import xyz.cncnlp.soundwave.ui.common.rememberParticleBurstState
import xyz.cncnlp.soundwave.ui.common.cssShadow

/**
 * 「默认」播放器主页（r657：在 1.0.655 App 默认播放器基础上按 v4 + 用户最终意见调整，不是重新设计）：
 * 顶部只有小白条（点按 / 下拉收起，无左右圆钮）→ App 大封面（按剩余高度尽量放大；暂停时 spring 缩到 0.86；
 * 点封面缩小淡出进歌词；不显示曲目位置或来源弹窗）→
 * 歌名 / 歌手 + 右侧 ♥ 与 ••• → 珊瑚进度条 + 两端时间 + 居中音质胶囊 →
 * 播放顺序 · 上一首 · 珊瑚播放键 · 下一首 · 播放设备（AirPlay 式图标）→ 音量条（默认隐藏；长按设备键或 ⋯ 菜单切换）→ 底部无底色「UP NEXT」条（细分隔线、等宽珊瑚标签、
 * 下一首粗体、两张叠放小封面 + ›，点按打开播放队列）。颜色默认取 App 主题（浅色暖纸面 / 深色墨面 + 珊瑚强调）。
 */
@Composable
internal fun DefaultPlayerMainPage(state: DefaultPlayerState, actions: DefaultPlayerActions, modifier: Modifier = Modifier, lyricsOpening: Boolean = false, coverOnlyImmersive: Boolean = false) {
    val colors = defaultPlayerColors()
    val artistContext = LocalContext.current
    // SW-12-02（G 波）：喜欢切换触感。
    val hapticView = androidx.compose.ui.platform.LocalView.current
    val song = state.song
    // r656：喜欢 / 开始播放时的一次性粒子（减少动效时不播放）。
    val likeBurst = rememberParticleBurstState()
    val playBurst = rememberParticleBurstState()
    val coverScale by rememberDefaultCoverScale(state, lyricsOpening)
    // r658c P：在 DefaultPlayerRoute 内时封面由顶层飞行封面绘制，这里只保留槽位（测量 + 点按）；单独渲染（截图测试）时自绘。
    val morph = LocalPlayerCoverMorph.current
    // r658b #9（用户 10-19）：沉浸封面从屏幕最顶端（状态栏下方）整宽铺满，底部多段渐隐自然融入页面背景。
    // 封面层画在内容列之后的背景层，向上越过父级的 statusBarsPadding；高度 = 状态栏 + 抓手 + 封面槽位。
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current
    var immersiveSlotBottomPx by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxSize()) {
    // r672：路由内由 DefaultPlayerRoute 的常驻沉浸封面层绘制（主页 / 歌词页共用一张，见 StandardImmersiveCoverLayer）；
    // 这里只在单独渲染（截图测试）时自绘，同样 alpha 渐隐到透明，不再渐变到不透明纸色与背景拼接。
    if (coverOnlyImmersive && morph == null && immersiveSlotBottomPx > 0) {
        val layerHeight = statusTop + with(density) { immersiveSlotBottomPx.toDp() }
        Box(
            Modifier.fillMaxWidth().height(layerHeight).offset(y = -statusTop)
                .playerHeroFadeTo({ Float.MAX_VALUE }, STANDARD_IMMERSIVE_FADE_FRACTION)
        ) {
            DefaultPlayerArtwork(
                song.cover, "", Modifier.fillMaxSize().graphicsLayer {
                    // 播放/暂停时轻微呼吸缩放（只放大不缩小，避免露出边缘）。
                    val s = 1f + (1f - coverScale).coerceIn(0f, 0.2f) * 0.25f
                    scaleX = s; scaleY = s
                }, 0.dp
            )
            Box(Modifier.fillMaxWidth().height(statusTop + 56.dp).background(Brush.verticalGradient(
                listOf(colors.background.copy(alpha = 0.28f), Color.Transparent))))
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        DefaultPlayerGrabBar(colors, "收起播放器", actions.onCollapse)
        // 封面区按剩余高度自适应：尽量做到整宽正方形（App 大封面），矮屏等比缩小并居中。
        // r657 第二轮：封面略下移（顶部 22dp + 剩余空间里偏下 35%），下方标题 / 按钮 / 进度 / 控制整体上移、间距收紧。
        // r657 第三轮（用户 10-18「播放器整体内容向上移动一点」，优先于原型）：顶距 22→10dp、封面在剩余空间中由偏下 35% 改为偏上 −20%。
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp, bottom = 2.dp)
            .onGloballyPositioned { c ->
                val b = (c.positionInParent().y + c.size.height).toInt(); if (b != immersiveSlotBottomPx) immersiveSlotBottomPx = b
                // r672：同时报告给路由常驻沉浸封面层（主页根 Box 位于路由左上角，此值即相对路由顶端；不受下拉收起平移影响）。
                if (coverOnlyImmersive && morph != null && b.toFloat() != morph.immersiveMainBottom) morph.immersiveMainBottom = b.toFloat()
            },
            contentAlignment = androidx.compose.ui.BiasAlignment(0f, -0.2f)) {
            // r658c P（用户 10-20）：封面适当缩小，左右边距与进度条一致——进度条在 22dp 内容边距内再缩 6dp（距屏幕边 28dp），
            // 封面宽 = 内容宽 − 12dp（旧版 + 12dp，距屏幕边 16dp）。高度不足时按剩余高度缩小。
            val side = min(maxWidth - DefaultPlayerSeekInsetDp * 2, maxHeight).coerceAtLeast(120.dp)
            if (coverOnlyImmersive) {
                // Only the existing weighted artwork slot changes: metadata and every control below stay untouched.
                Box(
                    Modifier.requiredWidth(maxWidth + 44.dp).fillMaxHeight()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { actions.onOpenLyrics() }
                        .semantics { contentDescription = "${song.name} 沉浸封面，点按查看歌词" }
                ) {
                    // 封面图像由上方整屏背景层绘制；此处仅保留点按区与缓冲提示。
                    if (state.resolving || state.status.isNotBlank()) {
                        Text(
                            if (state.resolving) "缓冲中…" else state.status, color = colors.ink, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 14.dp)
                        )
                    }
                }
            } else Box(
                Modifier.requiredSize(side).playerCoverMainSlot(morph)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { actions.onOpenLyrics() }
                    .semantics { contentDescription = "${song.name} 封面，点按查看歌词" }
            ) {
                if (morph == null) DefaultPlayerCoverFace(state, colors, Modifier.fillMaxSize().graphicsLayer { scaleX = coverScale; scaleY = coverScale })
            }
        }
        Spacer(Modifier.height(24.dp)) // v5：封面可用区底边 → 歌名区 ≥ 26dp（含上方 2dp）
        Row(Modifier.padding(start = 6.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DefaultPlayerSongTitle(song.name, colors, Modifier.fillMaxWidth().clickable { xyz.cncnlp.soundwave.TrackNavigation.openArtist(artistContext, song) })
                Spacer(Modifier.height(3.dp))
                // r657 截图对照：原型副标题为「歌手 · 专辑」，专辑为空时只显示歌手。
                DefaultPlayerArtistLine(listOf(song.singer.ifBlank { "未知歌手" }, song.album.trim()).filter { it.isNotEmpty() }.joinToString(" · "), colors, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(4.dp))
            DefaultPlayerIconButton(
                if (state.liked) DefaultPlayerGlyphs.HeartFilled else DefaultPlayerGlyphs.Heart,
                if (state.liked) "取消喜欢" else "喜欢", if (state.liked) colors.accent else colors.ink,
                // ACCEPT6-motiondraft（稿 burst pop）：点亮时按钮本体先弹到 1.25 再弹回，与粒子并行（共享 popOnBecomeTrue）。
                Modifier.popOnBecomeTrue(state.liked).particleBurst(likeBurst, colors.accent, Color(0xFFFFD678), ParticleStyle.Hearts, count = 16, radius = 52.dp),
                size = 40.dp, iconSize = 22.dp, onClick = { if (!state.liked) { likeBurst.burst(); xyz.cncnlp.soundwave.ui.common.AppHaptics.confirm(hapticView) } else xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(hapticView); actions.onLike() }
            )
            DefaultPlayerIconButton(DefaultPlayerGlyphs.More, "更多操作", colors.ink, size = 40.dp, iconSize = 22.dp, onClick = actions.onMore)
        }
        // v5：.pmeta 底 226 → .pprog 顶 199（27dp）；进度条 left/right 28（比内容边距各多 6dp）；时间行距条 10dp。
        Spacer(Modifier.height(17.dp))
        DefaultPlayerSeekBar(
            state.progress, colors, Modifier.padding(horizontal = DefaultPlayerSeekInsetDp), touchHeight = 24.dp, onSeekTap = actions.onSeekTap, onDragStart = actions.onDragStart,
            onDrag = actions.onDrag, onDragEnd = actions.onDragEnd, onDragCancel = actions.onDragCancel
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = DefaultPlayerSeekInsetDp), verticalAlignment = Alignment.CenterVertically) {
            // 用户 10-18：时间数字与音质胶囊改主题墨色（浅色黑 / 深色近白），进度条仍珊瑚。
            DefaultPlayerTimeText(defaultPlayerTime(state.displayTime), colors.ink)
            Spacer(Modifier.weight(1f))
            if (state.quality.isNotBlank()) {
                Box(
                    Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, colors.ink.copy(alpha = 0.55f), RoundedCornerShape(99.dp))
                        .clickable(onClick = actions.onQuality).padding(horizontal = 7.dp, vertical = 1.dp) // 用户 10-18：音质胶囊缩小
                        .semantics { contentDescription = "音质：${state.quality}"; role = Role.Button }
                ) {
                    // r657 修复：原型「FLAC · 44.1 kHz」保留大小写（kHz），DM Mono 9.5sp、小字距。
                    Text(state.quality, color = colors.ink, fontSize = 8.5.sp, lineHeight = 11.sp, letterSpacing = 0.12.em, fontWeight = FontWeight.Bold,
                        fontFamily = DesignFonts.DmMono, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            DefaultPlayerTimeText(defaultPlayerTime(state.duration), colors.ink)
        }
        Spacer(Modifier.height(12.dp)) // v5：.pprog 底 168 → 播放键顶 156
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DefaultPlayerIconButton(
                DefaultPlayerGlyphs.playMode(state.playMode), "播放顺序：${playModeLabel(state.playMode)}",
                // v5 原型顺序键始终珊瑚色（循环 / 随机 / 单曲 / 心动都用强调色）。
                colors.accent, iconSize = 23.dp, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(hapticView); actions.onMode() }
            )
            DefaultPlayerIconButton(DefaultPlayerGlyphs.Prev, "上一首", colors.ink, size = 60.dp, iconSize = 34.dp, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(hapticView); actions.onPrev() })
            DefaultPlayerPlayButton(
                state.playing, colors, Modifier.particleBurst(playBurst, colors.accent, colors.ink.copy(alpha = 0.45f), ParticleStyle.Ring, count = 10, radius = 62.dp, particleSize = 2.5.dp),
                onClick = { if (!state.playing) playBurst.burst(); actions.onToggle() }
            )
            DefaultPlayerIconButton(DefaultPlayerGlyphs.Next, "下一首", colors.ink, size = 60.dp, iconSize = 34.dp, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(hapticView); actions.onNext() })
            DefaultPlayerIconButton(
                DefaultPlayerGlyphs.AirPlay, "播放设备（长按显示音量条）", colors.muted, iconSize = 23.dp,
                onLongClick = actions.onToggleVolume, onClick = actions.onDevice
            )
        }
        if (state.showVolume) {
            // 用户 10-18：音量条移到播放控制行下方（仍默认隐藏）。
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(DefaultPlayerGlyphs.VolumeLow, null, Modifier.size(18.dp), tint = colors.accent)
                Spacer(Modifier.width(10.dp))
                DefaultPlayerSeekBar(
                    state.volume, colors, Modifier.weight(1f), trackHeight = 3.dp, thumb = 12.dp,
                    onSeekTap = actions.onVolume, onDragStart = {}, onDrag = actions.onVolume, onDragEnd = {}
                )
                Spacer(Modifier.width(10.dp))
                Icon(DefaultPlayerGlyphs.VolumeHigh, null, Modifier.size(18.dp), tint = colors.accent)
            }
        }
        // r658c P（用户 10-20）：歌名 / 歌手 / 进度条 / 时间 / 播放控制整体略上移（与 UP NEXT 条的间距 10 → 22dp，UP NEXT 位置不变）。
        Spacer(Modifier.height(DefaultPlayerControlsLiftDp))
        DefaultPlayerUpNextStrip(state, colors, Modifier.padding(bottom = 10.dp), actions.onOpenQueue)
    }
    }
}

/**
 * 播放器主页歌名排版（r657 v5 原型：衬线 Noto Serif SC Bold；原 26sp/31sp，用户 10-16 定上限 26sp）。
 * r672 起歌词页标题共用同一排版（字体/字重/跑马灯一致，仅字号经参数区分）。
 * 2026-11-03 用户真机反馈歌名偏大：主页（默认 + 标准共用本组件）26 → 24sp、行高 31 → 29sp；
 * 歌词页偏大更明显，调用点另传 22sp/26sp。沉浸 / Apple 样式不走本组件，不受影响。
 */
@Composable
internal fun DefaultPlayerSongTitle(
    name: String, colors: DefaultPlayerColors, modifier: Modifier = Modifier,
    fontSize: TextUnit = 24.sp, lineHeight: TextUnit = 29.sp
) {
    Text(
        name, color = colors.ink, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em,
        fontFamily = DesignFonts.SerifCJK, maxLines = 1, modifier = modifier.loopMarquee()
    )
}

/** 播放器主页歌手行排版（Manrope 13sp、墨色 55%）。r672 起歌词页歌手行共用同一排版。 */
@Composable
internal fun DefaultPlayerArtistLine(text: String, colors: DefaultPlayerColors, modifier: Modifier = Modifier) {
    Text(
        text.ifBlank { "未知歌手" }, color = colors.ink.copy(alpha = 0.55f), fontSize = 13.sp, lineHeight = 18.sp, fontFamily = DesignFonts.Manrope,
        maxLines = 1, modifier = modifier.loopMarquee()
    )
}

/** 进度条 / 时间行在内容边距内的额外左右缩进；封面左右边距与之一致。 */
internal val DefaultPlayerSeekInsetDp = 6.dp

/** 播放控制行与底部 UP NEXT 条的间距（r658c P：10 → 22dp，控制区整体上移 12dp）。 */
internal val DefaultPlayerControlsLiftDp = 22.dp

/** 封面播放 / 暂停呼吸缩放（暂停 0.92，播放 / 缓冲 1；简洁动效 200ms 线性）。主页自绘与飞行封面共用。 */
@Composable
internal fun rememberDefaultCoverScale(state: DefaultPlayerState, lyricsOpening: Boolean = false): androidx.compose.runtime.State<Float> {
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    return animateFloatAsState(
        when { lyricsOpening -> 0.82f; state.playing || state.resolving -> 1f; else -> 0.92f },
        if (reduceMotion) tween(200, easing = LinearEasing) else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "defaultCoverScale"
    )
}

/** 大封面外观：r28 封面 + 投影 + 细描边 + 左上角来源标签 + 左下角缓冲提示。 */
@Composable
internal fun DefaultPlayerCoverFace(state: DefaultPlayerState, colors: DefaultPlayerColors, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 28.dp, decorationAlpha: Float = 1f) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.cssShadow(shape, Color.Black.copy(alpha = 0.32f * decorationAlpha), 54.dp, 26.dp)) { // v4 .pcover 0 26 54 rgba(0,0,0,.32)
        DefaultPlayerArtwork(state.song.cover, "", Modifier.fillMaxSize(), corner)
        Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = 0.08f * decorationAlpha), shape))
        if (state.coverLabel.isNotBlank()) DefaultPlayerCoverLabel(state, colors, Modifier.align(Alignment.TopStart).padding(12.dp).graphicsLayer { alpha = decorationAlpha })
        if (state.resolving || state.status.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = decorationAlpha }.padding(14.dp).clip(RoundedCornerShape(99.dp))
                    .background(Color.Black.copy(alpha = 0.48f)).padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(if (state.resolving) "缓冲中…" else state.status, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 封面左上角毛玻璃标签：心动模式珊瑚底，平时黑色半透明底；r657 第三轮（用户 10-18）只做展示，不再点按弹出队列/来源对话框。 */
@Composable
private fun DefaultPlayerCoverLabel(state: DefaultPlayerState, colors: DefaultPlayerColors, modifier: Modifier) {
    val shape = RoundedCornerShape(99.dp)
    // r657 第三轮：v4 .pctx——26dp 高、左右 11dp、间距 5dp；Manrope 800(700) 11.5sp 白；
    // 平时 rgba(18,20,18,.34) + 白 20% 内描边，前缀「来自」为 DM Mono 9.5sp / .1em / 72% 不透明；心动模式珊瑚渐变 + ✦ 图标。
    val heart = state.heartMode
    Row(
        modifier.height(26.dp).clip(shape)
            .then(
                if (heart) Modifier.background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.62f), colors.accent.copy(alpha = 0.34f))))
                else Modifier.background(Color(0xFF121412).copy(alpha = 0.34f))
            )
            .border(1.dp, Color.White.copy(alpha = if (heart) 0.26f else 0.20f), shape)
            .padding(horizontal = 11.dp)
            .semantics { contentDescription = state.coverLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val label = state.coverLabel
        val body = when {
            heart -> label.removePrefix("✦").trim()
            label.startsWith("来自 ") -> label.removePrefix("来自 ")
            else -> label
        }
        if (heart) Icon(DefaultPlayerGlyphs.Sparkle, null, Modifier.size(13.dp), tint = Color.White)
        else if (label.startsWith("来自 ")) Text("来自", color = Color.White.copy(alpha = 0.72f), fontSize = 9.5.sp, lineHeight = 10.sp,
            letterSpacing = 0.1.em, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(body, color = Color.White, fontSize = 11.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 底部「UP NEXT」条（无底色）：顶部 1dp 细分隔线；左侧等宽字距珊瑚「UP NEXT」+ 下一首粗体；
 * 右侧两张叠放的小圆角封面 + ›；整条点按打开播放队列（替代 1.0.655 的 UP NEXT 卡片 + 歌词圆钮）。
 */
@Composable
internal fun DefaultPlayerUpNextStrip(state: DefaultPlayerState, colors: DefaultPlayerColors, modifier: Modifier = Modifier, onOpenQueue: () -> Unit) {
    val next = state.upNext
    val pair = state.upNextPair
    Column(
        modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onOpenQueue)
            .semantics { contentDescription = "播放队列，下一首：${next?.name ?: "无"}"; role = Role.Button }
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(colors.line)))
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                DefaultPlayerEyebrow("UP NEXT", colors.accentText, size = 10.sp, spacing = 0.22.em, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    next?.name ?: "队列中没有下一首", color = if (next != null) colors.ink else colors.muted, fontSize = 16.sp,
                    lineHeight = 19.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (pair.isNotEmpty()) {
                Box(Modifier.size(width = (40 + (pair.size - 1) * 26).dp, height = 40.dp)) {
                    pair.forEachIndexed { i, item ->
                        Box(
                            Modifier.offset(x = (i * 26).dp).size(40.dp).clip(RoundedCornerShape(11.dp))
                                .background(colors.background).padding(2.dp)
                        ) { DefaultPlayerArtwork(item.cover, "", Modifier.fillMaxSize(), 9.dp, targetSizePx = 120) }
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Icon(DefaultPlayerGlyphs.ChevronRight, null, Modifier.size(18.dp), tint = colors.muted)
        }
    }
}

/** 正在播放的三柱律动（珊瑚色），暂停时静止。 */
@Composable
internal fun DefaultPlayerEqualizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "defaultEq")
    val phases = listOf(0, 180, 360).map { delay ->
        transition.animateFloat(
            0.3f, 1f, infiniteRepeatable(tween(520, delayMillis = delay, easing = LinearEasing), RepeatMode.Reverse), label = "eq$delay"
        )
    }
    Row(modifier.size(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEachIndexed { i, anim ->
            val v by anim
            val h = if (playing) v else listOf(0.5f, 0.85f, 0.4f)[i]
            Box(Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}
