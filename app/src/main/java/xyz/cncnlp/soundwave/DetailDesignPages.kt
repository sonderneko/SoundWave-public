package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.floatingControlShadow
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.motion.sharedCoverTarget
import xyz.cncnlp.soundwave.ui.motion.LocalSharedCoverKey
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding

import androidx.compose.foundation.ExperimentalFoundationApi
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.cssShadow

/* ─────────────── 详情页（歌单 / 专辑 / 榜单）设计稿 v2：.playlist-hero / .detail-hero / .chart-detail-head ─────────────── */

internal enum class DetailDesignKind { Playlist, Album, Chart }

private val DetailHeroBg = Color(0xFF121713)
private val DetailTitleInk = Color(0xFFF6F7F0)
private val DetailCurrentTitle = Color(0xFFFF5C45) // r657 第三轮：原型 .row.on b = var(--accent)
private val DangerRed = Color(0xFFB84F43)

/** r656：首页今日推荐卡片与页头的间距（17→14dp）；所有设计稿详情页封面顶距统一使用该值。
 * accept6-topbars（用户 2026-11-04 22:44）：顶栏上移后下方内容同步跟上移约 4dp，14→10dp。 */
internal val DetailHeroTopGap = 10.dp

/** 珊瑚发光（与首页今日推荐播放键一致的柔光晕）：在控件背后绘制一层模糊的珊瑚色圆角块。 */
internal fun Modifier.accentGlow(
    color: Color = SoundwaveDesignPalette.Accent,
    alpha: Float = 0.55f,
    blur: androidx.compose.ui.unit.Dp = 14.dp,
    offsetY: androidx.compose.ui.unit.Dp = 5.dp
): Modifier = this.drawBehind {
    val r = blur.toPx()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.argb((alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        maskFilter = android.graphics.BlurMaskFilter(r, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val inset = 3.dp.toPx()
    val dy = offsetY.toPx()
    drawContext.canvas.nativeCanvas.drawRoundRect(
        inset, inset + dy, size.width - inset, size.height - inset + dy, size.height / 2f, size.height / 2f, paint
    )
}

/** 榜单 / 推荐流 → 榜单详情；歌曲同属一张以歌单名命名的专辑 → 专辑详情；其余按歌单详情。 */
internal fun detailDesignKind(detail: Playlist, songs: List<Song>): DetailDesignKind = when {
    detail.id.startsWith("rank_") || detail.id.startsWith("hot_") -> DetailDesignKind.Chart
    detail.id.startsWith("album_") || detail.id.startsWith("library_album_") -> DetailDesignKind.Album
    songs.size >= 2 && detail.name.isNotBlank() && songs.all { it.album.isNotBlank() && it.album == detail.name } -> DetailDesignKind.Album
    else -> DetailDesignKind.Playlist
}

/** 「1 HR 14 MIN」/「42 MIN」；无可用时长时为空。 */
internal fun detailTotalDurationLabel(songs: List<Song>): String {
    val totalMin = songs.sumOf { it.duration.coerceAtLeast(0L) } / 60_000L
    if (totalMin <= 0L) return ""
    return if (totalMin >= 60) "${totalMin / 60} HR ${totalMin % 60} MIN" else "$totalMin MIN"
}

/** 在 [sizes] 中挑选使 [text] 不超过 [maxLines] 行的最大字号，避免长标题被截断或重叠。 */
@Composable
private fun rememberFittingFontSize(text: String, style: TextStyle, maxWidthPx: Int, maxLines: Int, sizes: List<TextUnit>): TextUnit {
    val measurer = rememberTextMeasurer()
    return remember(text, maxWidthPx, style, sizes) {
        if (maxWidthPx <= 0) return@remember sizes.last()
        sizes.firstOrNull { size ->
            val r = measurer.measure(
                text, style.copy(fontSize = size, lineHeight = size * 0.86f),
                maxLines = maxLines, overflow = TextOverflow.Clip,
                constraints = Constraints(maxWidth = maxWidthPx)
            )
            !r.hasVisualOverflow && !r.didOverflowHeight && !r.didOverflowWidth
        } ?: sizes.last()
    }
}

private val TightTitleLine = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/** r657 第二轮：详情英雄卡大标题行高系数（Noto Serif SC 的字面 + 「|」等竖线字形在 1.28 倍下两行之间留有间隙）。 */
internal const val DetailHeroTitleLineFactor = 1.28f

@Composable
private fun DesignHeroTitle(
    text: String, color: Color, maxSize: TextUnit, minSize: TextUnit, letterSpacingEm: Float, modifier: Modifier = Modifier, lineFactor: Float = 0.86f,
    fontWeight: FontWeight = FontWeight.Medium
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // r657 修复：中文衬线（Noto Serif SC Bold）；原 Newsreader 无中文字形，中文会回落无衬线。
        val base = TextStyle(fontFamily = DesignFonts.SerifCJK, fontWeight = fontWeight, letterSpacing = letterSpacingEm.em)
        val steps = remember(maxSize, minSize) {
            generateSequence(maxSize.value) { it - 4f }.takeWhile { it >= minSize.value }.map { it.sp }.toList().ifEmpty { listOf(minSize) }
        }
        val size = rememberFittingFontSize(text, base, widthPx, 2, steps)
        Text(
            text, color = color, style = base.copy(lineHeightStyle = TightTitleLine), fontSize = size, lineHeight = size * lineFactor,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DetailCoverImage(
    cover: String, coverModel: Any?, description: String, modifier: Modifier,
    artworkModifier: Modifier = Modifier
) {
    // Keep this stationary layout anchor and all artwork modifiers in place. The target
    // records artwork for the root overlay, outside page alpha and the list viewport.
    val coordinates = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    Box(modifier.onGloballyPositioned { coordinates[0] = it }) {
        Box(
            Modifier.fillMaxSize()
                .sharedCoverTarget(LocalSharedCoverKey.current) { coordinates[0] }
                .then(artworkModifier),
            contentAlignment = Alignment.Center
        ) {
            // 验收二轮 E4：hero 封面未加载/加载中/失败改走全 App 统一占位垫底（原先是一块
            // 深绿灰平块 #2A302B + 白音符，与占位体系不同物种，且加载中无垫底直接露空）。
            // 布局锚与 artwork 修饰符不动，占位只作底层，图片成功后盖住。
            DefaultCoverPlaceholder(Modifier.fillMaxSize(), glyphSizeDp = 26)
            if (cover.isNotEmpty()) FadingCoverImage(coverModel ?: cover, description, Modifier.fillMaxSize())
        }
    }
}

/** 46dp 珊瑚播放胶囊（阴影 0 6 18 accent/0.32）。 */
@Composable
internal fun DesignPlayPill(
    label: String, labelSize: TextUnit, enabled: Boolean, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit,
    startPad: androidx.compose.ui.unit.Dp = 20.dp, endPad: androidx.compose.ui.unit.Dp = 22.dp, height: androidx.compose.ui.unit.Dp = 46.dp,
    gap: androidx.compose.ui.unit.Dp = 10.dp
) {
    val accent = SoundwaveDesignPalette.Accent
    Row(
        Modifier.height(height)
            .pressScale(PressMotion.SCALE_DEFAULT)
            .accentGlow()
            .shadow(12.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.55f))
            .clip(CircleShape).background(accent)
            .rippleGlow(Color.White.copy(alpha = 0.55f)) // r657 审计第 21 项
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "播放全部"; role = Role.Button }
            .padding(start = startPad, end = endPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(DesignGlyphs.PlayTriangle, null, tint = Color.White, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(gap))
        Text(label, color = Color.White, fontSize = labelSize, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
    }
}

/** r657（v3 #1）：封面区「随机播放」毛玻璃胶囊（深色卡上为白色半透明，浅色页上为表面色半透明 + 细描边）。
 *  r657 修复：横向内距 14dp、文字 12.5sp；[iconOnly] 时为 [height] 见方圆钮（窄屏放不下文字时）。 */
@Composable
internal fun DesignShufflePill(onDark: Boolean, enabled: Boolean, height: androidx.compose.ui.unit.Dp = 46.dp, iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fg = if (onDark) Color.White else colors.onSurface
    Row(
        Modifier.height(height).then(if (iconOnly) Modifier.requiredWidth(height) else Modifier)
            .pressScale(PressMotion.SCALE_DEFAULT)
            .clip(CircleShape)
            // 原型 .pill.gl：白 16% + 白 28% 内描边（英雄卡上）。
            .background(if (onDark) Color.White.copy(alpha = 0.16f) else xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface.copy(alpha = 0.72f)))
            .border(1.dp, if (onDark) Color.White.copy(alpha = 0.28f) else colors.outline, CircleShape)
            .rippleGlow(if (onDark) Color.White.copy(alpha = 0.35f) else SoundwaveDesignPalette.Accent.copy(alpha = 0.22f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "随机播放"; role = Role.Button }
            .padding(start = if (iconOnly) 0.dp else 14.dp, end = if (iconOnly) 0.dp else if (onDark) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Shuffle, null, tint = fg, modifier = Modifier.size(if (onDark) 16.dp else 18.dp))
        if (!iconOnly) {
            Spacer(Modifier.width(7.dp))
            Text("随机播放", color = fg, fontSize = if (onDark) 14.sp else 12.5.sp, fontWeight = if (onDark) FontWeight.ExtraBold else FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
        }
    }
}

/** 纯函数：详情 hero 按钮行在 [availDp] 内的排法（单测覆盖 360dp）。0 = 全尺寸，1 = 收紧播放胶囊内距（随机仍带文字），2 = 随机再收成圆钮。 */
internal fun heroActionsMode(availDp: Float, playDp: Float, playTightDp: Float, shuffle: Boolean, shuffleDp: Float, height: Float, fav: Boolean, favDp: Float, gap: Float = 10f): Int {
    val tail = (if (fav) gap + favDp else 0f)
    val sh = if (shuffle) gap + shuffleDp else 0f
    if (playDp + sh + tail <= availDp) return 0
    if (playTightDp + sh + tail <= availDp) return 1
    return 2
}

/**
 * r657 修复：详情 hero 的 播放全部 / 随机播放 / ♥ 一行，按可用宽度自适应（360dp 真机 ♥ 不再被挤成细线）。
 * ♥ 与随机圆钮用 requiredSize，播放全部 weight(1f, fill = false)。
 */
@Composable
private fun HeroActionRow(
    labelSize: TextUnit, iconSize: androidx.compose.ui.unit.Dp, startPad: androidx.compose.ui.unit.Dp, endPad: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp, onDark: Boolean, playEnabled: Boolean, onPlayAll: () -> Unit, onShuffle: (() -> Unit)?,
    favSize: androidx.compose.ui.unit.Dp, favGap: androidx.compose.ui.unit.Dp = 10.dp, playGap: androidx.compose.ui.unit.Dp = 10.dp,
    favorite: (@Composable () -> Unit)?
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelW = labelSize.value * fontScale * 4 * 1.02f
        val playW = startPad.value + iconSize.value + playGap.value + labelW + endPad.value
        val playTight = 14f + iconSize.value + 8f + labelW + 14f
        val shuffleW = if (onDark) 14f + 16f + 7f + 14f * fontScale * 4 * 1.02f + 16f else 14f + 18f + 7f + 12.5f * fontScale * 4 * 1.02f + 14f
        val mode = heroActionsMode(maxWidth.value, playW, playTight, onShuffle != null, shuffleW, height.value, favorite != null, favSize.value, favGap.value)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) {
                if (mode == 0) DesignPlayPill("播放全部", labelSize, playEnabled, iconSize, onPlayAll, startPad = startPad, endPad = endPad, height = height, gap = playGap)
                else DesignPlayPill("播放全部", labelSize, playEnabled, iconSize, onPlayAll, startPad = 14.dp, endPad = 14.dp, height = height, gap = 8.dp)
            }
            if (onShuffle != null) { Spacer(Modifier.width(10.dp)); DesignShufflePill(onDark = onDark, enabled = playEnabled, height = height, iconOnly = mode >= 2, onClick = onShuffle) }
            if (favorite != null) { Spacer(Modifier.width(favGap)); favorite() }
        }
    }
}

/** 46dp 圆形次级按钮；[raised] = .recommend-favorite 投影版，否则为 .icon-btn / .secondary-btn 描边版。 */
@Composable
internal fun DesignRoundButton(
    description: String,
    background: Color,
    borderColor: Color,
    raised: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        Modifier.requiredSize(size)
            .pressScale(PressMotion.SCALE_ICON)
            .then(if (raised) Modifier.shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.32f)) else Modifier)
            .clip(CircleShape).background(background).border(1.dp, borderColor, CircleShape)
            .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.22f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** .playlist-hero：384dp 深色卡 r28；270dp 封面渐隐，眉题 / 56sp 衬线标题 / 播放·收藏·更多 / DM Mono 元信息。 */
@Composable
internal fun PlaylistDesignHero(
    title: String,
    eyebrow: String,
    meta: List<String>,
    cover: String,
    coverModel: Any?,
    favorited: Boolean,
    showFavorite: Boolean,
    playEnabled: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onMore: () -> Unit,
    onShuffle: (() -> Unit)? = null,
    /** r657 第三轮（用户 10-18：原型按钮组 = 播放全部 + ♥ / 下载 / 更多 圆钮）。 */
    onDownload: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(28.dp)
    val accent = SoundwaveDesignPalette.Accent
    // r657 第三轮：按原型 pDetail .hero 重做——396dp 高、封面铺满整卡（不再是上图下墨块），
    // 叠 app.css .hero::before 渐变 rgba(6,8,12) 0@25% → .55@62% → .86@100%；左上 16dp 毛玻璃 chipg「✦ PLAYLIST」；
    // 内容贴底 22dp：眉题 → 8dp → 衬线标题（>9 字 30/36，否则 40/46，-.04em）→ 6dp → 12.5/19 白 78% 元信息 → 18dp → 按钮行（gap 10）。
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            // r657 第三轮 b：合并可靠性分支——飞行封面由根层 SharedCoverOverlay 重放，不受此处 clip 影响；
            // 祖先不再 staggerEnter（页面已整体推入，祖先缩放/模糊会连带飞行封面）。
            .cssShadow(shape, Color(0xFF131915).copy(alpha = 0.22f), 40.dp, 18.dp) // .hero 0 18 40 rgba(19,25,21,.22)
            .clip(shape).background(DetailHeroBg)
            .height(396.dp)
    ) {
        DetailCoverImage(cover, coverModel, "$title 封面", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(DetailHeroFullScrim))
        DetailHeroChip(heroChipLabel(eyebrow), Modifier.align(Alignment.TopStart).padding(16.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(22.dp)) {
            Text(
                eyebrow.uppercase(), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, lineHeight = 12.sp,
                fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            val long = title.length > 9
            DesignHeroTitle(
                title, DetailTitleInk, if (long) 30.sp else 40.sp, 22.sp, -0.04f,
                lineFactor = if (long) 1.2f else 1.15f, fontWeight = FontWeight.Bold
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    meta.filter { it.isNotBlank() }.joinToString(" · ") { it.uppercase() }, color = Color.White.copy(alpha = 0.78f), fontSize = 12.5.sp, lineHeight = 19.sp,
                    fontFamily = DesignFonts.Manrope, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(18.dp))
            // r658b 第 1 项（用户 10-19）：封面下只留「播放全部」+ 右侧白底黑字「收藏」胶囊；下载 / ⋯ 去掉（⋯ 在页头，下载在 ⋯ 抽屉）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesignPlayPill("播放全部", 14.sp, playEnabled, 15.dp, onPlayAll, startPad = 16.dp, endPad = 20.dp, height = 44.dp, gap = 7.dp)
                if (showFavorite) {
                    Spacer(Modifier.width(10.dp))
                    ImmersiveFavoritePill(favorited, onToggleFavorite, height = 44.dp)
                }
            }
        }
    }
}

/** 原型 .hero::before：rgba(6,8,12) 0@25% → .55@62% → .86@100%（起点按用户要求下移，见下）。 */
private val DetailHeroFullScrim = Brush.verticalGradient(
    // 用户 10-16「封面下方渐变黑显示范围太高了，降低」优先于原型 25% 起始：起点压到 40%，曲线形状 / 终点不透明度同原型。
    0f to Color.Transparent, 0.40f to Color(0xFF06080C).copy(alpha = 0f), 0.70f to Color(0xFF06080C).copy(alpha = 0.55f), 1f to Color(0xFF06080C).copy(alpha = 0.86f)
)

/** 英雄卡左上 chip 文案：原型 MADE FOR YOU / TOP CHART / PLAYLIST（取眉题第一段）。 */
internal fun heroChipLabel(eyebrow: String): String = when (eyebrow.substringBefore(" ·").trim().lowercase()) {
    "chart", "top chart" -> "TOP CHART"
    "album" -> "ALBUM"
    "artist" -> "ARTIST"
    "my favorites" -> "MY FAVORITES"
    "recently played" -> "RECENTLY PLAYED"
    "local playlist" -> "LOCAL"
    else -> "PLAYLIST"
}

/** app.css .chipg：22dp 高、左 6 右 8、间距 4；DM Mono 10sp .04em 白；rgba(18,20,18,.32) + 白 22% 内描边；12dp ✦。 */
@Composable
internal fun DetailHeroChip(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(22.dp).clip(CircleShape).background(Color(0xFF121412).copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Sparkle, null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 10.sp, lineHeight = 10.sp, letterSpacing = 0.04.em, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * 「标准」详情页头部（参考 1000073096，412dp 实测）：左 27dp、宽 = 屏宽 - 110dp 的 r24 方形封面 + 柔和投影；
 * 下 28dp 为 DM Mono 12sp 宽字距眉题（ALBUM · 歌手 · 平台），衬线粗体超大标题（≤64sp 自适应两行），
 * 52dp 珊瑚「播放」胶囊（发光）+ 52dp 白底描边收藏圆钮。歌单 / 榜单 / 专辑 / 歌手统一使用。
 */
@Composable
internal fun StandardDetailHero(
    title: String,
    eyebrow: String,
    cover: String,
    coverModel: Any?,
    favorited: Boolean,
    showFavorite: Boolean,
    playEnabled: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val coverSize = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 110).coerceIn(200, 420).dp
    Column(Modifier.fillMaxWidth().padding(start = 27.dp, end = 27.dp, bottom = 8.dp)) {
        DetailCoverImage(
            cover, coverModel, "$title 封面",
            Modifier.size(coverSize),
            artworkModifier = Modifier
                .shadow(24.dp, shape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.20f))
                .clip(shape)
        )
        Spacer(Modifier.height(30.dp))
        Text(
            eyebrow.uppercase(), color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 15.sp,
            fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        // r657 第二轮（用户）：原 64sp / 行高 0.92 两行相撞（「|」压到下一行的字）→ 字号收小、行高放大；标题→按钮间距 28→16dp。
        DesignHeroTitle(title, colors.onBackground, 50.sp, 28.sp, -0.04f, lineFactor = DetailHeroTitleLineFactor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        HeroActionRow(
            18.sp, 20.dp, 22.dp, 26.dp, 52.dp, onDark = false, playEnabled = playEnabled, onPlayAll = onPlayAll, onShuffle = onShuffle, favSize = 52.dp, favGap = 12.dp,
            favorite = if (showFavorite) ({
                DesignRoundButton(if (favorited) "取消收藏" else "收藏", colors.surface, colors.outline, raised = false, onClick = onToggleFavorite, size = 52.dp) {
                    Icon(if (favorited) DesignGlyphs.HeartFilled else DesignGlyphs.HeartOutline, null,
                        tint = if (favorited) SoundwaveDesignPalette.Accent else colors.onSurface, modifier = Modifier.size(24.dp))
                }
            }) else null
        )
    }
}

/* ─────────────── .sw-toolbar：常态（标题 + 下载/排序/多选）与多选态（全选 / 已选 N 首 / 完成） ─────────────── */

internal val DetailSortOptions = listOf("default" to "默认排序", "old" to "最早添加", "name" to "按标题", "singer" to "按艺人")

@Composable
internal fun DetailDesignToolbar(
    title: String,
    countLabel: String,
    multiMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    sortMode: String,
    sortMenuOpen: Boolean,
    showDownload: Boolean,
    onDownload: () -> Unit,
    onSortClick: () -> Unit,
    onSortPick: (String) -> Unit,
    onSortDismiss: () -> Unit,
    onEnterSelect: () -> Unit,
    onToggleAll: () -> Unit,
    onDone: () -> Unit,
    /** 1.0.655：除今日推荐外，曲目列表与上方封面的间距收紧（24→10dp）。 */
    topPadding: androidx.compose.ui.unit.Dp = 24.dp,
    leadingTools: @Composable RowScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    // r657 第三轮：原型 .sec.dtool——上 24dp、30dp 行高（34dp 圆钮上下各溢出 2dp）、下 12dp；h2 17sp；计数 DM Mono 11sp；圆钮间距 16dp。
    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = if (multiMode) 14.dp else 12.dp, top = topPadding, bottom = 10.dp).height(34.dp), contentAlignment = Alignment.CenterStart) {
        if (multiMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DesignPillTool(if (allSelected) "取消全选" else "全选", primary = false, onClick = onToggleAll)
                Text(
                    "已选 $selectedCount 首", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f)
                )
                DesignPillTool("完成", primary = true, onClick = onDone)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // r657 第二轮（用户）：「60 首」与「曲目」基线对齐（原底对齐 + 3dp 偏移看起来顶在上方），
                // 数字用 DM Mono、「首」/「·」用 Manrope，间距统一为一个窄空格（不再是等宽字体的宽空格）。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, letterSpacing = (-0.03).em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                    if (countLabel.isNotBlank()) Text(
                        detailCountAnnotated(countLabel), color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope, fontWeight = FontWeight.Medium,
                        maxLines = 1, modifier = Modifier.padding(start = 6.dp).alignByBaseline()
                    )
                }
                leadingTools() // r655：今日推荐在排序左侧插入「风格推荐」「换一批」（每钮后自带 DetailToolMetrics.Gap）。
                // r658c U #2（用户 10-20，1000073619）：排序 / 多选与「标签」「刷新」统一为同一尺寸与间距
                // （DetailToolMetrics：30dp 圆钮、7dp 间距），四钮等大等距。
                if (showDownload) {
                    DesignCircleTool(ToolGlyph.Download, "下载全部", onClick = onDownload)
                    Spacer(Modifier.width(DetailToolMetrics.Gap))
                }
                Box {
                    DesignCircleTool(ToolGlyph.Sort, "排序", active = sortMenuOpen || sortMode != "default", onClick = onSortClick)
                    if (sortMenuOpen) {
                        val density = LocalDensity.current
                        // ACCEPT5（退出动画普查 L3）：Popup 菜单补家族退场——点选先执行 onSortPick
                        // （动作立即生效），卡片 220ms 淡出+微下沉后才回调 onSortDismiss 卸载；
                        // 点外关闭同路。减少动效时直接卸载。
                        val sortReduce = MotionPolicy.rememberReduceMotion()
                        val sortExit = remember { androidx.compose.animation.core.Animatable(0f) }
                        val sortScope = androidx.compose.runtime.rememberCoroutineScope()
                        var sortExiting by remember { mutableStateOf(false) }
                        val latestSortDismiss by androidx.compose.runtime.rememberUpdatedState(onSortDismiss)
                        val startSortExit: () -> Unit = {
                            if (sortReduce) latestSortDismiss()
                            else if (!sortExiting) {
                                sortExiting = true
                                sortScope.launch {
                                    sortExit.animateTo(1f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                                    latestSortDismiss()
                                }
                            }
                        }
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = with(density) { IntOffset(38.dp.roundToPx(), 40.dp.roundToPx()) },
                            onDismissRequest = startSortExit,
                            properties = PopupProperties(focusable = true)
                        ) {
                            Box(Modifier.graphicsLayer {
                                alpha = 1f - sortExit.value
                                translationY = sortExit.value * with(density) { 6.dp.toPx() }
                            }) {
                                DesignSortMenuCard(sortMode) { key -> onSortPick(key); startSortExit() }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(DetailToolMetrics.Gap))
                DesignCircleTool(ToolGlyph.Select, "多选", onClick = onEnterSelect)
            }
        }
    }
}

internal enum class ToolGlyph { Download, Sort, Select }

/** r658c U #2：详情曲目工具条圆钮统一规格 = 今日推荐「标签」「刷新」圆钮（30dp、14dp 图标、钮间距 7dp）。 */
internal object DetailToolMetrics {
    val Size = 30.dp
    val Icon = 14.dp
    val Gap = 7.dp
}

/** 工具条圆钮：白色实心圆 + 1.5dp 浅灰描边 + 柔和投影，墨色线性图标（下载 / 排序 / 多选）。 */
@Composable
internal fun DesignCircleTool(glyph: ToolGlyph, description: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // 用户裁定（2026-11-04，真机投诉）：active = 珊瑚强调态——恢复 SW-09-16（54eaeeeb）之前的
    // 实现（图标/描边用 colorScheme.primary，浅/夜主题均解析为珊瑚 Accent）。G 波曾以「排序持久化后
    // 常驻红、删单重导脏键无法恢复」为由改成墨色实心，但脏键一半已由同波 clearPlaylistSortMode
    // （删单/重导清 soundwave_sort 的 mode_<id>）单独解决；着色按用户裁定回珊瑚，非默认排序一眼可辨，
    // 恢复默认排序走排序菜单选「默认排序」。不把持久排序状态画成常驻黑底。
    val ink = if (active) colors.primary else colors.onSurface
    // r658c U #2：尺寸统一为 DetailToolMetrics（30dp / 14dp 图标），与「标签」「刷新」同款柔和投影；1dp 描边。
    Box(
        Modifier.size(DetailToolMetrics.Size)
            .shadow(6.dp, CircleShape, ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(colors.surface)
            // 描边与首页平台切换按钮一致：1dp colors.outline；active 时描边转珊瑚（colors.primary）。
            .border(1.dp, if (active) colors.primary else colors.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(DetailToolMetrics.Icon)) {
            val w = size.width; val h = size.height
            val sw = 1.5.dp.toPx()
            val cap = androidx.compose.ui.graphics.StrokeCap.Round
            when (glyph) {
                ToolGlyph.Download -> {
                    drawLine(ink, Offset(w * 0.5f, h * 0.06f), Offset(w * 0.5f, h * 0.66f), sw, cap)
                    drawLine(ink, Offset(w * 0.24f, h * 0.42f), Offset(w * 0.5f, h * 0.68f), sw, cap)
                    drawLine(ink, Offset(w * 0.76f, h * 0.42f), Offset(w * 0.5f, h * 0.68f), sw, cap)
                    drawLine(ink, Offset(w * 0.12f, h * 0.92f), Offset(w * 0.88f, h * 0.92f), sw, cap)
                }
                ToolGlyph.Sort -> {
                    drawLine(ink, Offset(w * 0.06f, h * 0.20f), Offset(w * 0.58f, h * 0.20f), sw, cap)
                    drawLine(ink, Offset(w * 0.06f, h * 0.50f), Offset(w * 0.44f, h * 0.50f), sw, cap)
                    drawLine(ink, Offset(w * 0.06f, h * 0.80f), Offset(w * 0.30f, h * 0.80f), sw, cap)
                    drawLine(ink, Offset(w * 0.80f, h * 0.12f), Offset(w * 0.80f, h * 0.88f), sw, cap)
                    drawLine(ink, Offset(w * 0.64f, h * 0.70f), Offset(w * 0.80f, h * 0.88f), sw, cap)
                    drawLine(ink, Offset(w * 0.96f, h * 0.70f), Offset(w * 0.80f, h * 0.88f), sw, cap)
                }
                ToolGlyph.Select -> {
                    val inset = sw / 2f + w * 0.06f
                    drawRoundRect(ink, Offset(inset, inset), Size(w - inset * 2, h - inset * 2),
                        androidx.compose.ui.geometry.CornerRadius(w * 0.24f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                    val p = Path().apply { moveTo(w * 0.30f, h * 0.52f); lineTo(w * 0.45f, h * 0.66f); lineTo(w * 0.71f, h * 0.36f) }
                    drawPath(p, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun DesignPillTool(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).widthIn(min = 50.dp).clip(CircleShape)
            .background(if (primary) colors.onBackground else xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface))
            .border(1.dp, if (primary) colors.onBackground else colors.outline, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (primary) colors.background else colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1) }
}

/** .sw-menu：200dp 浮层，DM Mono 小标签 + 44dp 选项，当前项珊瑚字 + 勾。 */
@Composable
internal fun DesignSortMenuCard(current: String, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(bottom = 24.dp, start = 24.dp).width(200.dp)
            .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.30f))
            .clip(shape).background(xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface)).border(1.dp, colors.outline, shape).padding(7.dp)
    ) {
        Text("排序方式", color = colors.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium,
            letterSpacing = 0.12.em, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
        DetailSortOptions.forEach { (key, label) ->
            val on = key == current
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(key) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = if (on) DetailCurrentTitle else colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.weight(1f))
                xyz.cncnlp.soundwave.ui.common.AnimatedCheckMark(on, Icons.Filled.Check, SoundwaveDesignPalette.Accent, 16.dp)
            }
        }
    }
}

/** .sw-check：24dp 圆，未选 2dp 描边、选中珊瑚实心 + 白勾。 */
@Composable
internal fun DesignSelectCheck(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (selected) accent else colors.surface)
            .border(2.dp, if (selected) accent else colors.onBackground.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) { xyz.cncnlp.soundwave.ui.common.AnimatedCheckMark(selected, Icons.Filled.Check, Color.White, 15.dp) }
}

/* ─────────────── .rank-row：榜单详情行（名次 / 封面 / 标题 / 更多；多选显示勾选） ─────────────── */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChartDetailTrackRow(
    song: Song,
    rank: Int,
    nowPlaying: Boolean,
    isPlayingNow: Boolean,
    multiMode: Boolean,
    selected: Boolean,
    liked: Boolean,
    showDownload: Boolean,
    onLike: () -> Unit,
    onAdd: () -> Unit,
    onAddNext: () -> Unit,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)?,
    onDislike: () -> Unit,
    onSimilar: () -> Unit,
    onLongClick: () -> Unit,
    showTrailing: Boolean = true,
    /** M 波出处区歌单行身份透传（容器歌单）；开在该歌单自己的详情页时传 null（默认）。 */
    originPlaylist: Playlist? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val accent = SoundwaveDesignPalette.Accent
    var showSheet by remember { mutableStateOf(false) }
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val top = rank <= 3
    // SW-09-06（G 波）：封面统一 52dp、行高统一 68dp，与首页榜单行同口径（home-chart-maintenance 守卫：
    // 「统一 52dp 封面；前三仅强调序号」）。旧实现前三名封面 64dp/行高 84dp 两档，与计划「统一封面大小」相悖；
    // 前三强调改由序号字号/金银铜色承担（下方 rank 列），不再靠封面尺寸。
    val coverSize = 52.dp
    val coverShape = RoundedCornerShape(12.dp)
    val rankColor = when (rank) {
        1 -> Color(0xFFD9A43C)
        2 -> Color(0xFF9AA3AD)
        3 -> Color(0xFFC27D4F)
        else -> colors.onSurfaceVariant.copy(alpha = 0.8f)
    }
    Row(
        // r657 第三轮第 29 项：正在播放 = 整行通栏（无圆角、无外边距）浅珊瑚从左往右渐隐；多选态不铺底色。
        Modifier.fillMaxWidth().padding(vertical = 1.dp).height(68.dp)
            .then(
                if (nowPlaying && !multiMode) Modifier.background(Brush.horizontalGradient(0f to accent.copy(alpha = 0.16f), 0.45f to accent.copy(alpha = 0.10f), 1f to Color.Transparent))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongClick() })
            .semantics { role = Role.Button }
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 独立两位数序号列（定宽 34dp）；SW-09-07（G 波）：序号下加真实名次变动——仅平台给真实
        // rankChange 时显示（chartRankMovementLabel 对 null/0 返回空，绝不伪造持平/趋势），与首页行同口径。
        Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%02d".format(rank), color = rankColor, fontFamily = DesignFonts.Newsreader, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold,
                fontSize = if (top) 30.sp else 22.sp, lineHeight = if (top) 30.sp else 22.sp, maxLines = 1, softWrap = false,
                textAlign = TextAlign.Center
            )
            val change = song.rankChange
            val movement = xyz.cncnlp.soundwave.ui.home.chartRankMovementLabel(change)
            if (movement.isNotEmpty() && change != null) {
                Text(
                    movement, color = if (change > 0) designAccentTextColor() else colors.onSurfaceVariant,
                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.semantics {
                        contentDescription = "排名${if (change > 0) "上升" else "下降"} ${kotlin.math.abs(change.toLong())} 位"
                    }
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(coverSize)
                .homeCoverShadow(coverShape) // r673：榜单详情曲目封面 = 应用封面阴影
                .clip(coverShape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val req = rememberRowCoverRequest(song.cover, coverSize) // r657 第二轮性能：按行封面像素解码
            // ACCEPT5 收编：占位常驻垫底——加载/失败不再是 surfaceVariant 平灰（此前仅无封面时才画占位）。
            DefaultLandscapeCover(Modifier.fillMaxSize())
            if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // 第 30 项：封面上不再盖暗层 + 白色律动，律动改到歌手名左侧。
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(
                song.name, color = if (nowPlaying) DetailCurrentTitle else colors.onBackground, fontSize = 15.sp, lineHeight = 19.5.sp,
                fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1,
                modifier = Modifier.rowMarquee(nowPlaying)
            )
            val meta = listOf(song.singer.ifBlank { "未知歌手" }, song.album).filter { it.isNotBlank() }.joinToString(" · ")
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // r658b 第 2 项（用户 10-19）：音质标识不再只在正在播放行显示（详情 / 搜索歌曲行一律显示）。
                val qBadge = songQualityBadgeLabel(song)
                if (qBadge.isNotEmpty()) { QualityBadge(qBadge); Spacer(Modifier.width(6.dp)) }
                Text(meta, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.2.sp, fontFamily = DesignFonts.Manrope,
                    maxLines = 1, modifier = Modifier.weight(1f, fill = false).rowMarquee(nowPlaying))
            }
        }
        // E17：律动移到行右侧、「···」左侧（无尾随按钮时即行最右端）。
        if (nowPlaying && !multiMode) {
            NowPlayingEqBars(isPlayingNow)
            Spacer(Modifier.width(6.dp))
        }
        if (!showTrailing && !multiMode) Spacer(Modifier.width(8.dp))
        else Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !multiMode) { showSheet = true }, contentAlignment = Alignment.Center) {
            if (multiMode) DesignSelectCheck(selected)
            else Icon(Icons.Filled.MoreHoriz, "更多：${song.name}", tint = colors.onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
    if (showSheet) {
        DesignTrackActionSheet(
            song = song, liked = liked, showDownload = showDownload, canDelete = onDelete != null,
            onDismiss = { showSheet = false },
            // ACCEPT4（审计 §9 C 类）：prop 只执行动作；关闭走内容侧退场句柄，showSheet=false 由 onDismiss 在退场后承担。
            onSimilar = { onSimilar() }, onLike = { onLike() },
            onAddNext = { onAddNext() }, onAdd = { onAdd() },
            onDownload = { onDownload() }, onDelete = { onDelete?.invoke() },
            onDislike = { onDislike() },
            originPlaylist = originPlaylist
        )
    }
}

/* ─────────────── 底部抽屉（.sheet）：歌曲操作 / 歌单操作 ─────────────── */

/** 设计稿底部抽屉外壳（1.0.655 起委托 ui/common 统一设计抽屉 DesignSheetDialog）。 */
@Composable
internal fun DesignBottomSheetDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss, content)

@Composable
internal fun DesignSheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    xyz.cncnlp.soundwave.ui.common.DesignSheetPanel(modifier, content)

/** 眉题（珊瑚色）+ 标题。 */
@Composable
internal fun DesignSheetHead(eyebrow: String, title: String) = xyz.cncnlp.soundwave.ui.common.DesignSheetHeader(eyebrow, title)

/** 选项行（统一线性图标 + 标题 + 右侧说明，顶部分隔线）。 */
@Composable
internal fun DesignSheetOption(icon: ImageVector, label: String, hint: String, danger: Boolean = false, onClick: () -> Unit) =
    xyz.cncnlp.soundwave.ui.common.DesignSheetRow(icon, label, hint, danger, onClick = onClick)

/** 歌曲「更多」抽屉内容：r658c P 起委托全 App 共用的 [TrackActionsSheetContent]（与播放器「⋯」同一布局）。
 *  M 波：共用内容改为 ColumnScope 扩展（固定区/内滚区需 weight），本委托层以一层 Column 承接 receiver，
 *  对外签名保持不变（截图测试等以普通内容调用）。 */
@Composable
internal fun DesignTrackActionSheetContent(
    song: Song,
    liked: Boolean,
    showDownload: Boolean,
    canDelete: Boolean,
    onSimilar: () -> Unit,
    onLike: () -> Unit,
    onAddNext: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDislike: () -> Unit,
    onNavigate: () -> Unit = {},
    /** M 波出处区歌单行身份透传（容器歌单）；开在该歌单自己的详情页时传 null（默认）。 */
    originPlaylist: Playlist? = null
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        TrackActionsSheetContent(song, liked, showDownload, canDelete, onSimilar, onLike, onAddNext, onAdd, onDownload, onDelete, onDislike, onNavigate, originPlaylist = originPlaylist)
    }
}

@Composable
internal fun DesignTrackActionSheet(
    song: Song,
    liked: Boolean,
    showDownload: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSimilar: () -> Unit,
    onLike: () -> Unit,
    onAddNext: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDislike: () -> Unit,
    /** M 波出处区歌单行身份透传（容器歌单）；开在该歌单自己的详情页时传 null（默认）。 */
    originPlaylist: Playlist? = null
) {
    TrackActionsSheet(song, liked, showDownload, canDelete, onDismiss, onSimilar, onLike, onAddNext, onAdd, onDownload, onDelete, onDislike, originPlaylist = originPlaylist)
}

/** 歌单「更多」抽屉内容（Playlist actions / 歌单操作）。 */
@Composable
internal fun DesignPlaylistActionSheetContent(
    eyebrow: String,
    title: String,
    cover: String,
    name: String,
    subtitle: String,
    favorited: Boolean,
    showFavorite: Boolean,
    showDownload: Boolean,
    onToggleFavorite: () -> Unit,
    onAddNextAll: () -> Unit,
    onAddAll: () -> Unit,
    onDownloadAll: () -> Unit,
    onSearch: () -> Unit,
    /** E14（验收二轮）：本地歌单弹层去掉顶部快捷方块区（含「搜索」），全部选项竖排；默认 true 不影响其它歌单。 */
    quickTiles: Boolean = true,
    /** E14：非空时在竖排选项末尾加「重新扫描」（仅本地歌单传入，接既有 onScanLocal 扫描链）。 */
    onRescan: (() -> Unit)? = null
) {
    val g = xyz.cncnlp.soundwave.ui.common.DesignSheetGlyphs
    // ACCEPT4（审计 §9 C 类）：瓷砖/选项行在动作后走退场句柄关闭，宿主 prop 不再同步清状态。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { }
    DesignSheetHead(eyebrow, title)
    xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader(cover, name, subtitle)
    // r658b 第 1 项（用户 10-19，参考 1000073550）：与「歌曲操作」同布局——表头 + 条目头 + 四项图标快捷方块 + 列表行。
    // E14：本地歌单（quickTiles = false）整排快捷方块取消——「下一首」与下方「整张加入队列」是同一回调，
    // 功能由竖排行承接；「搜索」随快捷区一并去掉（仅本地歌单，其它歌单的搜索入口不动）。
    if (quickTiles) xyz.cncnlp.soundwave.ui.common.DesignQuickTileRow(listOfNotNull(
        if (showFavorite) xyz.cncnlp.soundwave.ui.common.DesignQuickTile(if (favorited) g.HeartFilled else g.Heart, if (favorited) "已收藏" else "收藏", if (favorited) "取消收藏" else "收藏", active = favorited, onClick = { onToggleFavorite(); dismiss() }) else null,
        xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.PlayNext, "下一首", "整张下一首播放", onClick = { onAddNextAll(); dismiss() }),
        if (showDownload) xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.Download, "下载", "下载全部", onClick = { onDownloadAll(); dismiss() }) else null,
        xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.SearchInPage, "搜索", "搜索曲目", onClick = { onSearch(); dismiss() })
    ))
    DesignSheetOption(g.AddToPlaylist, "全部添加到歌单", "选择歌单", onClick = { onAddAll(); dismiss() })
    DesignSheetOption(g.QueueAll, "整张加入队列", "依次下一首播放", onClick = { onAddNextAll(); dismiss() })
    if (onRescan != null) {
        DesignSheetOption(xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Repeat, "重新扫描", "扫描设备中的音乐", onClick = { onRescan(); dismiss() })
    }
}

/* ─────────────── .sw-actionbar：多选底部操作条 ─────────────── */

@Composable
internal fun DetailSelectionActionBar(
    enabled: Boolean,
    showDownload: Boolean,
    showRemove: Boolean,
    onAddTo: () -> Unit,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    /** r658b 第 5 项：非空时按此底距摆放（排行榜等带底栏 / 迷你条的页面传 dockContentBottomPadding()），不再被迷你条与底栏盖住。 */
    bottomInset: androidx.compose.ui.unit.Dp? = null
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth()
            .then(if (bottomInset != null) Modifier.padding(start = 12.dp, end = 12.dp, bottom = bottomInset) else Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            .shadow(26.dp, shape, ambientColor = Color.Black.copy(alpha = 0.24f), spotColor = Color.Black.copy(alpha = 0.30f))
            .clip(shape).background(colors.surface.copy(alpha = 0.96f)).border(1.dp, colors.outline, shape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        @Composable
        fun RowScope.Action(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
            val fg = if (danger) Color(0xFFE5372A) else colors.onSurface
            Column(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(16.dp)).alpha(if (enabled) 1f else 0.38f)
                    .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label; role = Role.Button },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1)
            }
        }
        Action(Icons.Outlined.Add, "加入歌单", onClick = onAddTo)
        Action(Icons.AutoMirrored.Outlined.QueueMusic, "加入队列", onClick = onQueue)
        if (showDownload) Action(Icons.Outlined.FileDownload, "下载歌曲", onClick = onDownload)
        if (showRemove) Action(Icons.Outlined.Close, "移除", danger = true, onClick = onRemove)
    }
}

/* ─────────────── 榜单切换抽屉（.chart-picker-sheet） ─────────────── */

@Composable
internal fun ChartPickerSheetHead(platformLabel: String) {
    val colors = MaterialTheme.colorScheme
    Text("$platformLabel · CHARTS", color = designAccentTextColor(), fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.13.em, maxLines = 1)
    Text("选择榜单", color = colors.onSurface, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 4.dp))
    Text("这里显示当前音乐平台提供的榜单。", color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
}

/** 榜单选择单行：52dp 封面、15sp 名称、11sp 更新周期，右侧箭头；当前榜珊瑚字。 */
@Composable
internal fun ChartPickerDesignRow(name: String, sub: String, cover: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) SoundwaveDesignPalette.Accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.26f))
                .clip(shape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val c = remember(cover) { normalizeCoverUrl(cover) }
            // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是 MusicNote 图标、加载时平灰）。
            DefaultCoverPlaceholder(Modifier.fillMaxSize())
            if (c.isNotBlank()) AsyncImage(CacheManager.coverModel(context, c) ?: c, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, color = if (selected) DetailCurrentTitle else colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 底部占位：迷你条 + 底栏宿主 + 系统导航栏 + 16dp，保证最后一行不被遮挡且可点。 */
@Composable
internal fun DetailDesignBottomSpacer() {
    Spacer(Modifier.height(dockContentBottomPadding()))
}

/** 今日推荐「更多」抽屉（设计稿 D.today：Made for you / 今日推荐）。 */
@Composable
internal fun DesignTodayActionSheetContent(
    cover: String,
    songCount: Int,
    showDownload: Boolean,
    onPlayAll: () -> Unit,
    onSaveAsPlaylist: () -> Unit,
    onQueueAll: () -> Unit,
    onDownloadAll: () -> Unit
) {
    val g = xyz.cncnlp.soundwave.ui.common.DesignSheetGlyphs
    // ACCEPT4（审计 §9 C 类）：瓷砖/选项行在动作后走退场句柄关闭，宿主 prop 不再同步清状态。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { }
    DesignSheetHead("Made for you", "今日推荐")
    xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader(cover, "今日推荐 · $songCount 首", "根据收藏与最近收听生成")
    // r658b 第 1 项：与「歌曲操作」同布局（四项快捷方块 + 列表行）。
    xyz.cncnlp.soundwave.ui.common.DesignQuickTileRow(listOfNotNull(
        xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.PlayAll, "播放", "播放全部", onClick = { onPlayAll(); dismiss() }),
        xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.PlayNext, "下一首", "整张下一首播放", onClick = { onQueueAll(); dismiss() }),
        if (showDownload) xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.Download, "下载", "下载全部", onClick = { onDownloadAll(); dismiss() }) else null,
        xyz.cncnlp.soundwave.ui.common.DesignQuickTile(g.SaveAsPlaylist, "存为歌单", "保存为歌单", onClick = { onSaveAsPlaylist(); dismiss() })
    ))
    DesignSheetOption(g.SaveAsPlaylist, "保存为歌单", "把今天的推荐留下来", onClick = { onSaveAsPlaylist(); dismiss() })
    DesignSheetOption(g.QueueAll, "整张加入队列", "接在当前歌曲之后", onClick = { onQueueAll(); dismiss() })
}

/** 歌手详情「专辑」横排：150dp 圆角方封面（约 2.5 张可见），粗体标题 + 灰色「日期 · N 首」。 */
@Composable
internal fun SingerAlbumsRow(albums: List<ArtistAlbum>, onOpen: (ArtistAlbum) -> Unit,
    loading: Boolean = false, hasMore: Boolean = false, error: Boolean = false, onLoadMore: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val listReduceMotion = MotionPolicy.rememberReduceMotion()
    // 一屏 3 张：卡宽 = (屏宽 - 28 - 2×12) / 3。
    val albumW = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 28 - 24) / 3f).dp
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text("专辑", color = colors.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
            letterSpacing = (-0.02).em, modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
        if (albums.isEmpty()) {
            if (loading) {
                // ACCEPT4（v8 稿 §5）：专辑条加载不再纯文字——加载胶囊 + 与实卡同尺寸的骨架封面排。
                xyz.cncnlp.soundwave.ui.common.LoadingPill("正在载入专辑", modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
                xyz.cncnlp.soundwave.ui.common.SkeletonGroup {
                    Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(3) {
                            xyz.cncnlp.soundwave.ui.common.SkeletonCoverBlock(Modifier.size(albumW), RoundedCornerShape(16.dp))
                        }
                    }
                }
            } else Text(if (error) "专辑加载失败" else "暂无专辑数据",
                color = colors.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(albums.size, key = { "${albums[it].source}:${albums[it].kind}:${albums[it].id}" }) { i ->
                val a = albums[i]
                val shape = RoundedCornerShape(16.dp)
                // SW-12-12：专辑增删 / 追加时平滑让位与淡入（key = source:kind:id，数据稳定 id）。
                // r673：不再整卡 clip（会把封面阴影裁掉）；封面自身仍按圆角裁切。
                Column(swAnimateItem(listReduceMotion).width(albumW).clickable { onOpen(a) }
                    .semantics { contentDescription = "专辑：${a.name}"; role = Role.Button }) {
                    Box(
                        Modifier.padding(top = 4.dp).size(albumW)
                            .homeCoverShadow(shape) // r673（用户 10-22）：歌手页专辑封面 = 应用封面阴影
                            .clip(shape).background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        val c = remember(a.cover) { normalizeCoverUrl(a.cover) }
                        // ACCEPT5 收编：占位常驻垫底——FadingCoverImage 自身无占位，加载/失败时原为 surfaceVariant 平灰。
                        DefaultCoverPlaceholder(Modifier.fillMaxSize())
                        if (c.isNotBlank()) FadingCoverImage(CacheManager.coverModel(context, c) ?: c, null, Modifier.fillMaxSize())
                    }
                    Text(a.name, color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    val sub = listOf(a.date, if (a.count > 0) "${a.count} 首" else "").filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** 全局右下悬浮按钮（回顶 / 定位当前歌曲）：统一首页一键回顶样式——40dp 表面色圆 + 主色图标 + 柔和投影。 */
@Composable
internal fun DetailAccentFab(icon: ImageVector, description: String, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp)
            // r675 后续（用户 10-22：再次调淡）：首页及详情回顶 / 定位独立轻投影，不影响封面阴影。
            .floatingControlShadow(CircleShape)
            .clip(CircleShape).background(colors.surface).clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = colors.primary, modifier = Modifier.size(iconSize + 1.dp)) }
}

/** 详情页搜索态的紧凑搜索框（参考 1000073316）：珊瑚描边圆胶囊 + 浅珊瑚内环 + 白色胶囊输入区。 */
@Composable
internal fun DetailCompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    Row(
        // 与搜索页同构：珊瑚外描边 → 浅珊瑚内环 → 白色胶囊（缩小版）。
        modifier.height(46.dp)
            // r674（用户 10-22：搜索栏边缘也可以加阴影）：胶囊外侧投影，只画在形状外（与 r673 搜索大卡同法），放在 clip 之前不被自身裁切。
            .cssShadow(RoundedCornerShape(999.dp), Color(0xFF131915).copy(alpha = if (colors.background.luminance() < 0.25f) 0.42f else 0.12f), 16.dp, 4.dp, excludeInterior = true)
            .clip(RoundedCornerShape(999.dp)).background(colors.surface).background(accent.copy(alpha = 0.13f))
            .border(1.5.dp, accent.copy(alpha = 0.86f), RoundedCornerShape(999.dp))
            .padding(4.5.dp)
            .clip(RoundedCornerShape(999.dp)).background(colors.surface)
            .border(1.dp, accent.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Search, null, tint = accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontFamily = DesignFonts.Manrope),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
            modifier = Modifier.weight(1f).then(fieldModifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("搜索本页曲目、歌手…", color = colors.onSurface.copy(alpha = 0.48f), fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            }
        )
        if (value.isNotEmpty()) Text("清除", color = colors.onSurface.copy(alpha = 0.52f), fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onValueChange("") }.padding(horizontal = 4.dp, vertical = 6.dp))
    }
}

/**
 * r657 第二轮：工具条曲目数「· 60 首」——数字段 DM Mono（等宽数字），其余（「·」「首」与空格）Manrope；
 * 等宽字体的空格过宽，「60」与「首」之间曾出现明显空洞。
 */
internal fun detailCountAnnotated(label: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val mono = androidx.compose.ui.text.SpanStyle(fontFamily = DesignFonts.DmMono, letterSpacing = 0.02.em)
    var i = 0
    while (i < label.length) {
        val c = label[i]
        if (c.isDigit()) {
            var j = i
            while (j < label.length && (label[j].isDigit() || label[j] == ',')) j++
            pushStyle(mono); append(label.substring(i, j)); pop(); i = j
        } else { append(c); i++ }
    }
}

/**
 * SW-12-12：主列表增删 / 换位动画。淡入淡出走 MotionTokens 效果弹簧（1.0 / 1600），
 * 让位走审计规格 spring(0.9, 700)；「减少动效」时返回空 Modifier（无淡入、无位移），
 * 与仓内 MotionPolicy 判定一致。key 由各 items 调用点的数据稳定 id 提供，本修饰符不涉及 key。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.swAnimateItem(reduceMotion: Boolean): Modifier =
    if (reduceMotion) Modifier
    else Modifier.animateItem(
        fadeInSpec = MotionTokens.Spring.EffectsSpec,
        placementSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        fadeOutSpec = MotionTokens.Spring.EffectsSpec
    )
