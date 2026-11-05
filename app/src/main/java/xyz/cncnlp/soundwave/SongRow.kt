@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package xyz.cncnlp.soundwave

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import xyz.cncnlp.soundwave.ui.player.PlayerSongEqualizer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.popOnBecomeTrue
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.PressMotion

// ===== 歌曲列表行 + 操作图标（自 MainActivity 迁入；ActionIcon private→internal；歌曲菜单已归一至 TrackActionsSheet） =====
@Composable
fun SongRow(
    song: Song,
    index: Int = 0,
    showIndex: Boolean = true,
    /** 仅首页传入当前平台作为展示回退，避免缺 source 的首页歌曲丢失平台名。 */
    platformSource: String = song.source,
    /** 首页序号不随当前播放态变色。 */
    uniformIndexColor: Boolean = false,
    showActions: Boolean = true,
    showLike: Boolean = false,
    flat: Boolean = false,
    showDownload: Boolean = false,
    liked: Boolean = false,
    selected: Boolean = false,
    multiMode: Boolean = false,
    onLike: () -> Unit = {},
    onAdd: () -> Unit = {},
    onDownload: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onAddNext: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** 三点底部对话框附加操作：不感兴趣 / 相似歌曲。 */
    onDislike: (() -> Unit)? = null,
    onSimilar: (() -> Unit)? = null,
    nowPlaying: Boolean = false,
    isPlayingNow: Boolean = false,
    compact: Boolean = false,
    coverScale: Float = 1f,
    tight: Boolean = false,
    /** 详情列表专用微调，不影响首页、搜索等其它歌曲列表。 */
    detailIndexOffset: Dp = 0.dp,
    detailMoreOffset: Dp = 0.dp,
    /** Only Default2/Immersive details pass this; null preserves the original row palette and checkbox. */
    detailForeground: Color? = null,
    /** 仅用于列表外壳已有水平 inset 的页面，保持封面和右侧操作与首页同一基准线。 */
    flatOuterHorizontal: Dp = ThemeTokens.TrackRow.DefaultOuterHorizontal,
    flatContentHorizontal: Dp = ThemeTokens.TrackRow.DefaultContentHorizontal,
    /** 仅用于首页等紧凑行（新歌速递）：歌名/歌手/平台字号更小更细。 */
    slimFonts: Boolean = false,
    /** r657 修复（审计第 12 项）：取消喜欢会把本行移出列表（我的喜欢详情）时传 true → 先粒子消散再执行。 */
    dissolveOnUnlike: Boolean = false,
    /**
     * r673（用户 10-22）：仅歌手页 / 数字专辑·新碟首发 / 榜单等详情传 true——封面加应用封面阴影（首页同款 homeCoverShadow）。
     * 默认 false：其它列表保持「封面自身保留图片，不叠卡片底/描边/阴影」的原约定。
     */
    coverShadow: Boolean = false,
    onClick: () -> Unit
) {
    // 列表行仅换表面材质；交互、行高、封面尺寸和内边距保持不变。
    val colors = MaterialTheme.colorScheme
    val songTitleColor = detailForeground ?: colors.onSurface
    val songMetaColor = detailForeground ?: colors.onSurfaceVariant
    val songPlatformColor = detailForeground ?: colors.onSurfaceVariant
    var showSheet by remember { mutableStateOf(false) }
    val rowIx = remember { MutableInteractionSource() }
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val rowDissolve = xyz.cncnlp.soundwave.ui.common.rememberParticleDissolveState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (compact) ThemeTokens.TrackRow.CompactHeight else ThemeTokens.TrackRow.Height)
            .then(xyz.cncnlp.soundwave.ui.common.particleDissolveModifier(rowDissolve, songTitleColor, colors.primary))
            .then(if (flat) Modifier.padding(horizontal = flatOuterHorizontal, vertical = if (tight) 0.dp else ThemeTokens.TrackRow.LooseFlatVertical) else Modifier.padding(horizontal = ThemeTokens.TrackRow.CardHorizontal, vertical = ThemeTokens.TrackRow.CardVertical).offset(y = ThemeTokens.TrackRow.CardOffset))
            .pressScale(PressMotion.SCALE_ROW)
            .then(if (nowPlaying) Modifier.background(Brush.horizontalGradient(
                0f to colors.primary.copy(alpha = ThemeTokens.TrackRow.PlayingAlpha), ThemeTokens.TrackRow.PlayingGradientEnd to Color.Transparent
            )) else Modifier)
            .then(if (detailForeground != null && selected && multiMode) Modifier.background(detailForeground.copy(alpha = ThemeTokens.TrackRow.DetailSelectionAlpha), RoundedCornerShape(ThemeTokens.TrackRow.SelectionRadius)) else Modifier)
            .combinedClickable(
                interactionSource = rowIx,
                indication = null,
                onClick = onClick,
                // ACCEPT5-press（稿 §4）：长按识别成功（出菜单）发 long 触感，全站歌曲行统一在此。
                onLongClick = { if (onLongClick != null) { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongClick.invoke() } }
            )
            .semantics { role = Role.Button }
            .padding(start = if (compact) ThemeTokens.TrackRow.CompactStartPadding else flatContentHorizontal, end = flatContentHorizontal),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size((if (compact) ThemeTokens.TrackRow.CompactCover else ThemeTokens.TrackRow.Cover) * coverScale)
                // 封面自身保留图片，默认不额外叠卡片底/描边/阴影；coverShadow 时按应用封面阴影绘制在裁切之外。
                .then(if (coverShadow) Modifier.homeCoverShadow(RoundedCornerShape(ThemeTokens.TrackRow.CoverRadius)) else Modifier)
                .clip(RoundedCornerShape(ThemeTokens.TrackRow.CoverRadius))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            val normalizedSongCover = remember(song.source, song.id, song.cover) { normalizeCoverUrl(song.cover) }
            val coverStateKey = remember(song.source, song.key(), normalizedSongCover) {
                coverRequestStateKey(song.source, song.key(), normalizedSongCover, 512)
            }
            var coverLoadFailed by remember(coverStateKey) { mutableStateOf(false) }
            // J 波：首次失败先自动重试一次再锁存（与 FoldedCoverCard 同口径）。
            var coverRetry by remember(coverStateKey) { androidx.compose.runtime.mutableIntStateOf(0) }
            val coverContext = LocalContext.current
            if (normalizedSongCover.isNotBlank() && !coverLoadFailed) {
                Box(Modifier.fillMaxSize()) {
                    DefaultLandscapeCover(Modifier.fillMaxSize())
                    androidx.compose.runtime.key(coverRetry) {
                        AsyncImage(
                            model = CacheManager.coverModel(coverContext, normalizedSongCover) ?: normalizedSongCover, contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(ThemeTokens.TrackRow.CoverRadius)),
                            contentScale = ContentScale.Crop,
                            onError = { if (coverRetry == 0) coverRetry = 1 else coverLoadFailed = true }
                        )
                    }
                }
            } else {
                DefaultLandscapeCover(Modifier.fillMaxSize())
            }
            // r657 第三轮第 30 项：封面中间的白色律动 + 暗层去掉，改为歌手名左侧珊瑚小律动（NowPlayingEqBars）。
        }
        Spacer(Modifier.width(if (compact) ThemeTokens.TrackRow.CompactCoverGap else ThemeTokens.TrackRow.CoverGap))
        Column(Modifier.weight(1f).padding(end = ThemeTokens.TrackRow.TextEndPadding).then(if (compact) Modifier.align(Alignment.Top) else Modifier)) {
            Text(
                song.name,
                color = songTitleColor,
                fontWeight = if (slimFonts) FontWeight.Medium else FontWeight.Bold,
                fontSize = if (slimFonts) ThemeTokens.TrackRow.SlimTitleSize else ThemeTokens.TrackRow.TitleSize,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().loopMarquee()
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    song.singer,
                    color = songMetaColor,
                    // 用户要求：歌手名字体 +1sp；不改变行位置。
                    fontSize = if (slimFonts) ThemeTokens.TrackRow.SlimMetaSize else ThemeTokens.TrackRow.MetaSize,
                    fontWeight = if (slimFonts) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 歌手文字仅改字号，保持原位置。
                    modifier = Modifier.widthIn(max = ThemeTokens.TrackRow.SingerMaxWidth).loopMarquee()
                )
                if (platformSource.isNotEmpty()) {
                    Spacer(Modifier.width(ThemeTokens.TrackRow.MetaGap))
                    Text(
                        platformName(platformSource),
                        color = songPlatformColor,
                        // 用户要求：平台名字体 +1sp；不改变行位置。
                        fontSize = if (slimFonts) ThemeTokens.TrackRow.SlimMetaSize else ThemeTokens.TrackRow.MetaSize,
                        maxLines = 1
                    )
                }
            }
        }
        if (showIndex && index > 0 && !multiMode) {
            val idxColor = when {
                detailForeground != null -> detailForeground
                nowPlaying && !uniformIndexColor -> colors.primary
                else -> colors.onSurfaceVariant
            }
            Text(
                "%02d".format(index),
                color = idxColor,
                fontSize = ThemeTokens.TrackRow.IndexSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = ThemeTokens.TrackRow.IndexGap).offset(x = detailIndexOffset)
            )
        }
        if (showActions && !multiMode) {
            if (showLike) {
                Box(
                    // ACCEPT5-press（稿 §3/§4）：行内喜欢钮改图标档 pressScale；触感对稿——点亮 confirm、取消 tick。
                    Modifier.size(ThemeTokens.TrackRow.ActionTarget).pressScale(PressMotion.SCALE_ICON).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!liked) xyz.cncnlp.soundwave.ui.common.AppHaptics.confirm(pressHapticView) else xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView)
                        onLike()
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (liked) "取消喜欢：${song.name}" else "喜欢：${song.name}",
                        tint = if (liked) colors.primary else colors.onSurfaceVariant,
                        // ACCEPT6-motiondraft（稿 burst pop）：点亮时心形本体弹跳（共享 popOnBecomeTrue）。
                        modifier = Modifier.size(ThemeTokens.TrackRow.ActionIcon).popOnBecomeTrue(liked)
                    )
                }
            }
            // E17：正在播放律动移到行右侧、「···」左侧（与 E16 队列行一致的全 app 位置）。
            if (nowPlaying) {
                NowPlayingEqBars(isPlayingNow)
                Spacer(Modifier.width(ThemeTokens.TrackRow.MetaGap))
            }
            Box(
                Modifier.offset(x = detailMoreOffset).size(ThemeTokens.TrackRow.ActionTarget).pressScale(PressMotion.SCALE_ICON).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (onMore != null) onMore() else showSheet = true
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    "更多：${song.name}",
                    tint = detailForeground ?: colors.onSurfaceVariant,
                    modifier = Modifier.size(ThemeTokens.TrackRow.ActionIcon)
                )
            }
        }
        // E17：无「···」的表面（或多选态）律动放行最右端。
        if (nowPlaying && (!showActions || multiMode)) {
            NowPlayingEqBars(isPlayingNow)
            Spacer(Modifier.width(ThemeTokens.TrackRow.MetaGap))
        }
        // 多选勾选：圆圈更小（20dp），未选无边框（轻圆点），选中实心+白勾
        if (multiMode) {
            if (detailForeground != null) {
                Box(
                    Modifier.size(ThemeTokens.TrackRow.SelectionSize).clip(CircleShape)
                        .background(if (selected) detailForeground else Color.Transparent, CircleShape)
                        .border(ThemeTokens.TrackRow.SelectionBorder, detailForeground.copy(alpha = if (selected) 1f else ThemeTokens.TrackRow.SelectionOutlineAlpha), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // ACCEPT6-motiondraft（稿 .ck）：勾选 ✓ 弹入（共享 AnimatedCheckMark）。
                    xyz.cncnlp.soundwave.ui.common.AnimatedCheckMark(selected, Icons.Filled.Check, if (detailForeground.luminance() < 0.5f) Color.White else Color.Black, ThemeTokens.TrackRow.SelectionCheck)
                }
            } else {
                Box(
                    Modifier.size(ThemeTokens.TrackRow.SelectionSize)
                        .then(
                            if (selected) Modifier.clip(CircleShape).background(colors.primary)
                            else Modifier.clip(CircleShape).background(colors.onSurfaceVariant.copy(alpha = ThemeTokens.TrackRow.IdleSelectionAlpha))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    xyz.cncnlp.soundwave.ui.common.AnimatedCheckMark(selected, Icons.Filled.Check, colors.onPrimary, ThemeTokens.TrackRow.SelectionCheck)
                }
            }
            Spacer(Modifier.width(ThemeTokens.TrackRow.SelectionGap))
        }
    }
    if (showSheet) {
        // MENU-UNIFY：全 App 歌曲菜单只此一家——与详情/搜索/播放器共用 TrackActionsSheet
        // （旧自绘菜单已整删）；回调映射保留 like/delete 的粒子消散包装语义。
        TrackActionsSheet(
            song = song,
            liked = liked,
            showDownload = showDownload,
            canDelete = onDelete != null,
            onDismiss = { showSheet = false },
            onSimilar = { onSimilar?.invoke() },
            onLike = { if (dissolveOnUnlike && liked) rowDissolve.dissolve(restore = true) { onLike() } else onLike() },
            onAddNext = { onAddNext?.invoke() },
            onAdd = { onAdd() },
            onDownload = { onDownload() },
            onDelete = { rowDissolve.dissolve(restore = true) { onDelete?.invoke() } },
            onDislike = { onDislike?.invoke() }
        )
    }
}

@Composable
internal fun ActionIcon(icon: ImageVector, description: String, color: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, animationSpec = tween(90), label = "act")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = null
            ) { onClick() }
    ) {
        Icon(icon, description, tint = color, modifier = Modifier.size(18.dp))
    }
}
