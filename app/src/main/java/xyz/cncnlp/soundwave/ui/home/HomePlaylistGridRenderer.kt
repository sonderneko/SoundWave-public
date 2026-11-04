@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package xyz.cncnlp.soundwave.ui.home

import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import androidx.compose.foundation.border
import xyz.cncnlp.soundwave.ui.motion.sharedCoverSource
import xyz.cncnlp.soundwave.ui.motion.SharedCoverTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import xyz.cncnlp.soundwave.ui.common.editorialCardShadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.wrapContentWidth
import xyz.cncnlp.soundwave.CoverRevealPlay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.songCount
import xyz.cncnlp.soundwave.playlistDisplayCover
import xyz.cncnlp.soundwave.playlistRecommendationKey
import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Renders the existing two-column playlist rows without owning feed or list state.
 * 设计稿「歌单广场 redesign」：首个歌单为整行宽卡（132dp 封面 + 衬线标题 + 编辑推荐标记），
 * 其余两列；封面圆角 18 + 柔和投影，左上角白色耳机图标 + 播放量（无底色），
 * 右下角显现播放键直接播放；卡片其他区域打开详情。
 */
internal fun LazyListScope.homePlaylistGridRows(
    visiblePlaylists: List<Playlist>,
    platform: String,
    label: String,
    primary: Color,
    onBg: Color,
    /** 右下角播放键交给首页共用播放控制器，详情导航独立。 */
    onPlayPlaylist: ((Playlist) -> Unit)? = null,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    fun open(pl: Playlist) {
        SharedCoverTransition.arm(pl.id)
        onOpenPlaylist(
        pl.copy(
            remoteId = pl.remoteId.ifBlank { pl.id },
            author = pl.author.ifEmpty { label },
            description = pl.description,
        )
        )
    }
    fun play(pl: Playlist) = onPlayPlaylist?.invoke(pl) ?: open(pl)
    // r658b 第 7 项（用户 10-19，1000073560 圈出）：去掉分类下方的整行宽卡，所有歌单直接两列。
    items(
        items = visiblePlaylists.chunked(2),
        key = { row -> row.joinToString("|") { playlistRecommendationKey(it, platform) } },
    ) { playlistRow ->
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            playlistRow.forEach { pl ->
                val cnt = pl.songCount().takeIf { it > 0 } ?: pl.count
                val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val pressHapticView = androidx.compose.ui.platform.LocalView.current
                val reveal = rememberCoverPlayReveal(press)
                Column(
                    Modifier.weight(1f).fillMaxWidth().hoverable(press)
                        .combinedClickable(interactionSource = press, indication = null, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); reveal.toggleHeld() }) { reveal.release(); open(pl) }
                ) {
                    // 封面：左上耳机 + 播放量、右上曲目数、左下作者；
                    // r656：右下纯白播放三角与首页甄选歌单一致——悬停/按压出现、松手保留 2.5s、长按固定显示。
                    PlaylistTileCover(
                        pl, cnt, primary, Modifier.fillMaxWidth().aspectRatio(1f),
                        revealPlay = reveal.visible, author = pl.author.ifEmpty { label }, onPlay = { reveal.release(); play(pl) }
                    )
                    // 标题最多两行；与封面间距减半（10 → 5dp）；原标题下的曲目数 / 作者已移入封面。
                    Text(
                        pl.name,
                        color = onBg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 5.dp),
                    )
                }
            }
            if (playlistRow.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@androidx.compose.runtime.Composable
private fun PlaylistTileCover(
    pl: Playlist,
    count: Int,
    primary: Color,
    modifier: Modifier,
    // r658b 第 7 项（用户 10-19）：封面右上角不再显示「xx 首 / TRACKS」。
    showCount: Boolean = false,
    revealPlay: Boolean = false,
    author: String = "",
    onPlay: () -> Unit = {},
) {
    val cover = playlistDisplayCover(pl)
    val coverContext = LocalContext.current
    val coverModel = remember(cover) { CacheManager.coverModel(coverContext, cover) ?: cover }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.sharedCoverSource(pl.id)
            .homeCoverShadow(shape)
            .clip(shape)
            .background(primary.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        // J 波：占位收编为 DefaultLandscapeCover（与首页甄选同脸；原粉灰底+列表字形是全仓第三张脸）+ 失败重试一次。
        xyz.cncnlp.soundwave.DefaultLandscapeCover(Modifier.fillMaxSize())
        if (cover.isNotEmpty()) {
            var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
            androidx.compose.runtime.key(coverRetry) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { if (coverRetry == 0) coverRetry = 1 },
                )
            }
        }
        // r657：左上角改为与首页甄选歌单相同的毛玻璃胶囊（耳机 + 播放量）。
        CoverPlayCountPill(pl.playCount, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp))
        val labelShadow = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(0f, 1f), 5f))
        if (showCount && count > 0) Text(
            // 用户 10-18：「xx 首」→「xx TRACKS」（大写英文，同详情英雄卡元信息「67 TRACKS」：Manrope、不加字距）。
            "$count TRACKS", color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1, style = labelShadow,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 12.dp)
        )
        if (author.isNotBlank()) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.38f)))))
            Text(
                author, color = Color.White, fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = labelShadow, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.7f).padding(start = 10.dp, bottom = 10.dp)
            )
        }
        // 设计稿 .cover-play：无底色纯白圆角三角，仅在按压 / 悬停歌单时出现。
        val playAppear by androidx.compose.animation.core.animateFloatAsState(if (revealPlay) 1f else 0f, tween(180), label = "plazaPlay")
        // r656：尺寸 / 边距与首页甄选歌单（28dp / 8dp）一致。
        CoverRevealPlay(playAppear, "播放：${pl.name}", size = 28.dp, padding = 8.dp, onClick = onPlay)
    }
}

@androidx.compose.runtime.Composable
private fun PlaylistWideTile(pl: Playlist, label: String, primary: Color, onBg: Color, onPlay: () -> Unit, onClick: () -> Unit) {
    val cnt = pl.songCount().takeIf { it > 0 } ?: pl.count
    val shape = RoundedCornerShape(22.dp)
    val wideIx = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val wideReveal = rememberCoverPlayReveal(wideIx)
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp)
            .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
            .clip(shape).background(MaterialTheme.colorScheme.surface)
            .hoverable(wideIx).combinedClickable(interactionSource = wideIx, indication = null, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); wideReveal.toggleHeld() }) { wideReveal.release(); onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PlaylistTileCover(pl, cnt, primary, Modifier.size(132.dp), showCount = false, revealPlay = wideReveal.visible, onPlay = { wideReveal.release(); onPlay() })
        Column(Modifier.weight(1f).height(132.dp).padding(start = 16.dp)) {
            Text(
                pl.name, color = onBg, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp, lineHeight = 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            Text(
                buildString {
                    if (cnt > 0) append("${cnt}首 · ")
                    append(pl.author.ifEmpty { label })
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                "编辑推荐 · 本周更新", color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, letterSpacing = 0.1.em, maxLines = 1,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}

/** r656：封面右下播放键显隐（同首页甄选歌单）：悬停 / 按压出现，松手保留 2.5s，长按切换固定显示。 */
internal class CoverPlayReveal(val visible: Boolean, val toggleHeld: () -> Unit, val release: () -> Unit)

@androidx.compose.runtime.Composable
internal fun rememberCoverPlayReveal(source: androidx.compose.foundation.interaction.MutableInteractionSource): CoverPlayReveal {
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    var linger by remember { androidx.compose.runtime.mutableStateOf(false) }
    var held by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pressed) {
        if (pressed) linger = true else if (linger) { kotlinx.coroutines.delay(2500L); linger = false }
    }
    return CoverPlayReveal(pressed || hovered || linger || held, toggleHeld = { held = !held }, release = { held = false })
}

/** 播放量：<1万 原数；<1亿 "x.x万"；否则 "x.x亿"（一位小数，去掉 .0）。0 → 空串（不显示）。 */
internal fun formatPlazaPlayCount(value: Long): String {
    if (value <= 0L) return ""
    fun one(v: Double, unit: String): String {
        val t = String.format(java.util.Locale.US, "%.1f", v)
        return (if (t.endsWith(".0")) t.dropLast(2) else t) + unit
    }
    return when {
        value >= 100_000_000L -> one(value / 100_000_000.0, "亿")
        value >= 10_000L -> one(value / 10_000.0, "万")
        else -> value.toString()
    }
}

/**
 * 歌单广场头图（设计稿 .sw-plaza-hero）：深色渐变 + 珊瑚/青柠光晕，右侧三张扇形错叠封面，
 * 左下 Featured · Week NN、衬线标题、作者/首数与珊瑚播放胶囊。只使用真实歌单数据；点按打开当前歌单。
 * 默认轮换前 3 个推荐歌单：左滑切换下一个、右滑回到上一个（循环），切换时横向滑动 + 淡入。
 */
@androidx.compose.runtime.Composable
internal fun PlaylistPlazaHero(playlists: List<Playlist>, label: String, onOpen: (Playlist) -> Unit) {
    if (playlists.isEmpty()) return
    val context = LocalContext.current
    val shape = RoundedCornerShape(26.dp)
    val week = remember { java.time.LocalDate.now().get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
    val accent = SoundwaveDesignPalette.Accent
    val lime = SoundwaveDesignPalette.Lime
    var index by remember(playlists.map { it.id }) { mutableIntStateOf(0) }
    var direction by remember { mutableIntStateOf(1) }
    val n = playlists.size
    val current = index.coerceIn(0, n - 1)
    val featured = playlists[current]
    Box(
        // 与首页今日推荐头图同样距页头（r656：17→14dp，DetailHeroTopGap），各页顶部首张大卡视觉间距统一。
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = xyz.cncnlp.soundwave.DetailHeroTopGap, bottom = 22.dp).height(236.dp)
            .editorialCardShadow(shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1B211D), Color(0xFF0E110F))))
            .pointerInput(n) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = {
                        if (n > 1 && kotlin.math.abs(distance) > 40.dp.toPx()) {
                            direction = if (distance < 0) 1 else -1
                            index = (index + direction + n) % n
                        }
                    },
                    onHorizontalDrag = { change, amount -> distance += amount; change.consume() }
                )
            }
            .clickable { val selected = playlists[index.coerceIn(0, n - 1)]; SharedCoverTransition.arm(selected.id); onOpen(selected) }
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f), radius = 520f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lime.copy(alpha = 0.22f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(700f, Float.POSITIVE_INFINITY), radius = 460f)))
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = direction
                (slideInHorizontally(tween(360)) { w -> dir * w / 3 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(320)) { w -> -dir * w / 3 } + fadeOut(tween(220)))
            },
            label = "plazaHero",
            modifier = Modifier.fillMaxSize()
        ) { shown ->
            val pl = playlists[shown]
            val cnt = pl.songCount().takeIf { it > 0 } ?: pl.count
            // 当前歌单封面在最前，其余推荐依次在后。
            val covers = (0 until n).map { playlists[(shown + it) % n] }.map { playlistDisplayCover(it) }.filter { it.isNotBlank() }
            Box(Modifier.fillMaxSize()) {
                // 三张扇形封面：后→前 = 9° / -4° / -12°
                Box(Modifier.align(Alignment.CenterEnd).offset(x = 6.dp).width(206.dp).height(172.dp)) {
                    val placements = listOf(Triple(6, 8, 9f), Triple(38, 26, -4f), Triple(70, 40, -12f))
                    val alphas = listOf(0.75f, 0.9f, 1f)
                    val shownCovers = covers.take(3).reversed()
                    val offsetIdx = 3 - shownCovers.size
                    shownCovers.forEachIndexed { i0, cover ->
                        val i = i0 + offsetIdx
                        val (end, top, rot) = placements[i]
                        // J 波：扇形封面加统一占位垫底（原裸 AsyncImage 失败即透出卡面深色底）+ 失败重试一次。
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(end = end.dp, top = top.dp).size(108.dp)
                                .graphicsLayer { rotationZ = rot; alpha = alphas[i] }
                                .homeCoverShadow(RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .then(if (i0 == shownCovers.lastIndex) Modifier.sharedCoverSource(pl.id) else Modifier)
                        ) {
                            xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 22)
                            var coverRetry by remember(cover) { androidx.compose.runtime.mutableIntStateOf(0) }
                            androidx.compose.runtime.key(coverRetry) {
                                AsyncImage(
                                    CacheManager.coverModel(context, cover) ?: cover, null,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    onError = { if (coverRetry == 0) coverRetry = 1 }
                                )
                            }
                        }
                    }
                }
                // 标题区向右延伸（56% → 62%）；标题 / 作者整体略上移，底部「打开歌单」胶囊固定高度不被挤压。
                Column(Modifier.fillMaxHeight().fillMaxWidth(0.62f).padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.Bottom) {
                    Text("FEATURED · WEEK $week", color = lime, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 0.16.em, maxLines = 1)
                    // 标题字号缩小（30 → 24sp）、行高加大（→ 30sp）。
                    Text(
                        pl.name, color = Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                        fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.02).em, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp, bottom = 5.dp)
                    )
                    Text(
                        listOf(pl.author.ifEmpty { label }, if (cnt > 0) "$cnt 首" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color(0xFFF6F7F0).copy(alpha = 0.62f), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        Modifier.padding(top = 18.dp).height(40.dp).wrapContentWidth(unbounded = true).clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.12f))
                            .padding(start = 5.dp, top = 5.dp, bottom = 5.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                            Icon(DesignGlyphs.PlayTriangle, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Play now", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) // r657（v3 #7）
                    }
                }
            }
        }
        if (n > 1) Row(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(n) { i ->
                Box(Modifier.size(if (i == current) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (i == current) accent else Color.White.copy(alpha = 0.35f)))
            }
        }
    }
}
