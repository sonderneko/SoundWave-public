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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import xyz.cncnlp.soundwave.ui.player.PlayerSongEqualizer
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.sheetPanelGlass
import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.PressMotion

// ===== 歌曲列表行 + 操作弹层 + 操作图标（自 MainActivity 迁入；SongActionSheet/ActionIcon private→internal） =====
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
    onRescan: (() -> Unit)? = null,
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
                        modifier = Modifier.size(ThemeTokens.TrackRow.ActionIcon)
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
                    if (selected) Icon(Icons.Filled.Check, null, tint = if (detailForeground.luminance() < 0.5f) Color.White else Color.Black, modifier = Modifier.size(ThemeTokens.TrackRow.SelectionCheck))
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
                    if (selected) Icon(Icons.Filled.Check, null, tint = colors.onPrimary, modifier = Modifier.size(ThemeTokens.TrackRow.SelectionCheck))
                }
            }
            Spacer(Modifier.width(ThemeTokens.TrackRow.SelectionGap))
        }
    }
    if (showSheet) {
        SongActionSheet(
            song = song,
            liked = liked,
            showDownload = showDownload,
            canDelete = onDelete != null,
            canRescan = onRescan != null,
            onDismiss = { showSheet = false },
            onLike = { if (dissolveOnUnlike && liked) rowDissolve.dissolve(restore = true) { onLike() } else onLike() },
            onAdd = { onAdd() },
            onAddNext = { onAddNext?.invoke() },
            onDownload = { onDownload() },
            onDelete = { rowDissolve.dissolve(restore = true) { onDelete?.invoke() } },
            onRescan = { onRescan?.invoke() },
            onDislike = { onDislike?.invoke() },
            onSimilar = { onSimilar?.invoke() }
        )
    }
}

@Composable
internal fun SongActionSheet(
    song: Song,
    liked: Boolean,
    showDownload: Boolean,
    canDelete: Boolean,
    canRescan: Boolean,
    onDismiss: () -> Unit,
    onLike: () -> Unit,
    onAdd: () -> Unit,
    onAddNext: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRescan: () -> Unit,
    /** 不感兴趣（本地屏蔽，影响心动模式与推荐）。 */
    onDislike: (() -> Unit)? = null,
    /** 相似歌曲（基于当前曲目启动相似播放）。 */
    onSimilar: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val menuFg = colors.onSurface
    val menuBg = colors.surface
    val menuSel = colors.primary
    // r657 第三轮第 19 项：「播放取链链路」→ 播放路径抽屉。
    var showPath by remember { mutableStateOf(false) }
    if (showPath) PlaybackPathSheet(song) { showPath = false }
    // ACCEPT5（退出动画普查 S1）：本壳原为裸 AppOverlayDialog、无任何进/退场，点选靠宿主同步
    // 清状态瞬时卸载。补与 DesignSheetDialog 同参的进/退场（420 emphasized 入 / 220 出、
    // 遮罩同步淡变、面板自底滑入/滑出）；动作立即执行、onDismiss 在退场走完才触发，
    // 宿主回调已同步去清状态。「播放取链链路」为叠开路径层，保持不关本层（原语义）。
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val enterAnim = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    val sheetScope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (reduce) onDismiss()
        else if (!closing) {
            closing = true
            sheetScope.launch {
                enterAnim.animateTo(0f, androidx.compose.animation.core.tween(xyz.cncnlp.soundwave.ui.common.SheetMotion.EXIT_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                onDismiss()
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!reduce) try {
            enterAnim.animateTo(1f, androidx.compose.animation.core.tween(xyz.cncnlp.soundwave.ui.common.SheetMotion.ENTER_MS, easing = xyz.cncnlp.soundwave.ui.common.SheetMotion.Emphasized))
        } finally {
            if (!closing) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { enterAnim.snapTo(1f) }
        }
    }
    xyz.cncnlp.soundwave.ui.common.AppOverlayDialog(onDismissRequest = close) { // r658h 应用内覆盖层，贴屏幕底边
        xyz.cncnlp.soundwave.ui.common.DialogEdgeToEdge()
        xyz.cncnlp.soundwave.ui.common.DesignSheetWindowEffect() // r658d：贴底不露缝 + 背后模糊
        Box(Modifier.fillMaxSize()) {
        // ACCEPT6 dialogfrost：与 DesignSheet 家族同等处理——全屏雾化背板 + 面板玻璃（自绘壳归队）。
        xyz.cncnlp.soundwave.ui.common.SheetFrostBackdrop(enterAnim)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f * enterAnim.value)).clickable { close() }, contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().graphicsLayer { translationY = (1f - enterAnim.value) * size.height }.sheetPanelGlass(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), menuBg).clickable(enabled = false) {}) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(menuFg.copy(alpha = 0.22f)))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(menuSel.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            if (song.cover.isNotEmpty()) AsyncImage(CacheManager.coverModel(LocalContext.current, song.cover) ?: song.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else DefaultLandscapeCover(Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.name, color = menuFg, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().loopMarquee())
                            Text(song.singer.ifBlank { "未知歌手" }, color = menuFg.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1)
                        }
                        // 最右侧：下载按钮
                        if (showDownload) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(menuSel.copy(alpha = 0.12f)).clickable { onDownload(); close() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FileDownload, "下载歌曲", tint = menuSel, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    val actions = buildList {
                        add(Triple(if (liked) "取消喜欢" else "添加喜欢", if (liked) "heart_filled" else "heart") { onLike(); close() })
                        add(Triple("添加到歌单", "menu_playlist") { onAdd(); close() })
                        add(Triple("下一首", "menu_next") { onAddNext(); close() })

                        if (onDislike != null) add(Triple("不感兴趣", "menu_hide") { onDislike(); close() })
                        if (onSimilar != null) add(Triple("相似歌曲", "menu_similar") { onSimilar(); close() })
                        if (canDelete) add(Triple("从歌单删除", "menu_timer") { onDelete(); close() })
                        if (canRescan) add(Triple("重新扫描", "menu_refresh") { onRescan(); close() })
                        add(Triple("播放取链链路", "source", { showPath = true }))
                    }
                    actions.chunked(2).forEach { rowActions ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            rowActions.forEach { (title, glyph, action) ->
                                PlayerMenuActionCard(Modifier.weight(1f), title, glyph, menuFg, menuSel, action)
                            }
                            if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(9.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        }
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
