package xyz.cncnlp.soundwave.ui.home

import androidx.compose.ui.layout.layout
import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.normalizeCoverUrl
import xyz.cncnlp.soundwave.officialUpdateLabel
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import androidx.compose.foundation.layout.height

/*
 * 排行榜（设计稿「排行榜 redesign」+ v2.7/v2.8/v2.12）纯展示组件。
 * 选择/持久化/加载均由 HomePage 现有状态驱动，这里只接收数据与回调。
 */

private fun RankBoard.displayCover(): String =
    previewCover.ifBlank { cover }.ifBlank { songs.firstOrNull()?.cover.orEmpty() }

/** 横向榜单目录：108dp 封面（圆角 18、柔和投影），选中项珊瑚描边 + 珊瑚标题。 */
@Composable
internal fun ChartCatalogStrip(
    boards: List<RankBoard>,
    selectedId: String?,
    state: LazyListState,
    onSelect: (RankBoard) -> Unit
) {
    val context = LocalContext.current
    val accent = xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent
    val onBg = MaterialTheme.colorScheme.onBackground
    // 原型 grid repeat(3,1fr) gap 10、左右 14：一屏恰好 3 张，第 4 张从屏外开始。
    val cardW = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 14 - 14 - 20) / 3f).dp
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(boards, key = { "catalog_${it.source}:${it.id}" }) { board ->
            val active = board.id == selectedId
            val cover = board.displayCover()
            val model = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
            Column(
                Modifier.width(cardW).clickable { onSelect(board) }
                    .semantics { contentDescription = "切换到${board.name}"; role = Role.Tab }
            ) {
                val shape = RoundedCornerShape(18.dp)
                Box(
                    Modifier.padding(top = if (active) 6.dp else 0.dp, bottom = if (active) 0.dp else 6.dp)
                        .drawBehind {
                            if (active) {
                                // 原型 box-shadow 0 0 0 2.5px paper, 0 0 0 4.5px accent → 外扩 2.5–4.5dp 的 2dp 珊瑚环。
                                val o = 3.5.dp.toPx()
                                drawRoundRect(
                                    accent, topLeft = Offset(-o, -o),
                                    size = androidx.compose.ui.geometry.Size(size.width + 2 * o, size.height + 2 * o),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx() + o),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
                                )
                            }
                        }
                ) {
                    Box(
                        Modifier.size(cardW)
                            .homeCoverShadow(shape) // r673（用户 10-22）：排行榜顶部榜单封面 = 应用封面阴影
                            .clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        // ACCEPT5 收编：黑胶母题占位垫底（此前 MusicNote 图标常驻垫底，加载时透出）。
                        xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize())
                        if (cover.isNotBlank()) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    // 原型：800 13.5/18、上 10dp，选中为珊瑚强调色。
                    board.name, color = if (active) accent else onBg, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp)
                )
                // r656：顶部榜单目录只保留榜名，「每天更新 · N 首」移回下方头卡（ChartHeroCard）。
            }
        }
    }
}

/**
 * 榜单头卡（r657 第三轮第 3 项，按原型 pChart .hero 重做）：176dp、r28、阴影 --shadow-sm；
 * 背景 = 选中榜单封面放大 1.3 + 模糊 18 + 饱和 1.4 + 亮度 .7，叠 .hero::before 渐变（rgb(6,8,12) 0→.55→.86，25%/62%/100%）；
 * 左上 20dp：眉题「平台 · TOP CHART」10sp DM Mono .16em 白 72% + 40/46 衬线榜名；右上 20dp 96dp r18 封面（0 14 28 黑 40%）；
 * 左下 20/16dp：38dp 珊瑚「播放全部」+ 38dp 玻璃「随机播放」，间距 8dp；右下 14/10dp：46dp .cswap（30dp 白色 ⇄ + 投影）。
 */
@Composable
internal fun ChartHeroCard(
    board: RankBoard,
    platformLabel: String,
    onSelect: (() -> Unit)? = null,
    onPlayAll: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onSwitch: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val context = LocalContext.current
    val cover = board.displayCover()
    val accent = xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent
    Box(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp)
            .height(176.dp)
            .homeCoverShadow(shape) // r673（用户 10-22）：榜单大卡加应用封面阴影（外层 padding 留出阴影空间）
            .clip(shape)
            .background(Color(0xFF173A55))
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
    ) {
        Crossfade(targetState = cover, animationSpec = tween(420), label = "chartHeroBg") { c ->
            Box(Modifier.fillMaxSize()) {
                if (c.isNotBlank()) {
                    val model = remember(c) { CacheManager.coverModel(context, c) ?: c }
                    AsyncImage(
                        model, null,
                        Modifier.fillMaxSize().graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }.blur(18.dp),
                        contentScale = ContentScale.Crop,
                        colorFilter = ChartHeroBgFilter
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color(0xFF06080C).copy(alpha = 0f), 0.25f to Color(0xFF06080C).copy(alpha = 0f),
                            0.62f to Color(0xFF06080C).copy(alpha = 0.55f), 1f to Color(0xFF06080C).copy(alpha = 0.86f)
                        )
                    )
                )
                if (c.isNotBlank()) {
                    val model = remember(c) { CacheManager.coverModel(context, c) ?: c }
                    val cs = RoundedCornerShape(18.dp)
                    AsyncImage(
                        model, "${board.name} 封面",
                        Modifier.align(Alignment.TopEnd).padding(top = ChartHeroCoverTop, end = 20.dp).size(96.dp)
                            .homeCoverShadow(cs)
                            .clip(cs),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 20.dp, end = 130.dp, top = 20.dp)) {
            Text("$platformLabel · Top Chart".uppercase(), color = Color.White.copy(alpha = 0.72f), fontSize = 10.sp, lineHeight = 12.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1)
            AnimatedContent(targetState = board.name, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "chartHeroName") { name ->
                Text(
                    // r657 第三轮 b：原型 600 40/46 var(--cjkserif)（榜名多为中文，Newsreader 回退系统字体与设计不符）。
                    name, color = Color.White, fontFamily = DesignFonts.SerifCJK, fontWeight = FontWeight.Bold,
                    fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.04).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val updateLabel = board.officialUpdateLabel()
            if (updateLabel.isNotBlank()) {
                Text(updateLabel, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp,
                    lineHeight = 14.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).swMarquee())
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onPlayAll != null) ChartHeroPill("播放全部", xyz.cncnlp.soundwave.ui.common.DesignGlyphs.PlayTriangle, glass = false, accent = accent, onClick = onPlayAll)
            if (onShuffle != null) ChartHeroPill("随机播放", xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Shuffle, glass = true, accent = accent, onClick = onShuffle)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp)) {
            ChartHeroIcon(ChartSwitchGlyph, "切换榜单", onSwitch)
        }
    }
}

/** .hero img.bg filter: saturate(1.4) brightness(.7)。 */
private val ChartHeroBgFilter: androidx.compose.ui.graphics.ColorFilter by lazy {
    val m = androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(1.4f) }
    val b = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(0.7f, 0f, 0f, 0f, 0f, 0f, 0.7f, 0f, 0f, 0f, 0f, 0f, 0.7f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
    m.timesAssign(b)
    androidx.compose.ui.graphics.ColorFilter.colorMatrix(m)
}

/** 头卡 38dp 胶囊：.pill（珊瑚 + 发光）或 .pill.gl（白 16% + 白 28% 内描边）；13sp 800。 */
@Composable
private fun ChartHeroPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, glass: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp)
            .then(if (glass) Modifier else Modifier.shadow(10.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.5f)))
            .clip(CircleShape)
            .background(if (glass) Color.White.copy(alpha = 0.16f) else accent)
            .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(start = if (glass) 14.dp else 16.dp, end = if (glass) 16.dp else 20.dp), // #ph .pill.gl 0 16 0 14
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
    }
}

/** r656：头卡右侧封面顶距（原 20dp，按反馈下移）。 */
internal val ChartHeroCoverTop = 20.dp // r657 第三轮：原型 right/top 20px

/** r656：头卡左下更新信息：「每天更新 · 共 N 首」；任一缺失时只显示另一项。 */
internal fun chartHeroUpdateLine(updateLabel: String, count: Int): String =
    listOf(updateLabel.trim(), if (count > 0) "共 $count 首" else "").filter { it.isNotBlank() }.joinToString(" · ")

/** 切换榜单：原型 icons.js `pswap`（两条带圆角弯折的反向箭头，1.75 描边）。 */
private val ChartSwitchGlyph: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("sw_pswap", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M16.5 3.8 20 7.3l-3.5 3.5", "M20 7.3H8.5A4.5 4.5 0 0 0 4 11.8", "M7.5 20.2 4 16.7l3.5-3.5", "M4 16.7h11.5a4.5 4.5 0 0 0 4.5-4.5").forEach { d ->
            addPath(
                pathData = androidx.compose.ui.graphics.vector.addPathNodes(d), fill = null,
                stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        }
    }.build()
}

@Composable
private fun ChartHeroIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        // 原型 .cswap svg drop-shadow(0 2px 6px rgba(0,0,0,.35))：先画一层偏移的半透明黑图标近似投影。
        Icon(icon, null, tint = Color.Black.copy(alpha = 0.22f), modifier = Modifier.size(30.dp).offset(y = 2.dp).blur(3.dp))
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp))
    } // 原型 .cswap 46dp / 30dp 图标
}

/** 排行榜列表标题行：「榜单曲目」+ 右侧多选（多选态显示 全选 / 已选 N 首 / 完成）。 */
@Composable
internal fun ChartListHeader(
    multiMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onEnterSelect: () -> Unit,
    onToggleAll: () -> Unit,
    onDone: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (multiMode) {
            ChartPill(if (allSelected) "取消全选" else "全选", primary = false, onClick = onToggleAll)
            Text("已选 $selectedCount 首", color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            ChartPill("完成", primary = true, onClick = onDone)
        } else {
            Text("榜单曲目", color = colors.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                letterSpacing = (-0.02).em, maxLines = 1, modifier = Modifier.weight(1f))
            xyz.cncnlp.soundwave.DesignCircleTool(xyz.cncnlp.soundwave.ToolGlyph.Select, "多选", onClick = onEnterSelect)
        }
    }
}

@Composable
private fun ChartPill(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(36.dp).widthIn(min = 50.dp).clip(CircleShape)
            .background(if (primary) colors.onBackground else xyz.cncnlp.soundwave.ui.common.frostedChipFill(colors.surface))
            .border(1.dp, if (primary) colors.onBackground else colors.outline, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (primary) colors.background else colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1) }
}

/** 未提供或为零时不画箭头，也不将未知冒充持平。 */
internal fun chartRankMovementLabel(change: Int?): String = when {
    change == null || change == 0 -> ""
    change > 0 -> "↑$change"
    else -> "↓${-change.toLong()}"
}

/** 榜单歌曲行：统一 52dp 封面；前三仅强调序号，不改变歌曲顺序或封面尺寸。 */
@Composable
internal fun ChartRankRow(
    song: Song,
    rank: Int,
    nowPlaying: Boolean,
    multiMode: Boolean = false,
    selected: Boolean = false,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val top = rank in 1..3
    val coverSize = 52.dp
    val shape = RoundedCornerShape(12.dp)
    val cover = normalizeCoverUrl(song.cover)
    val model = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
    val rankColor = when (rank) {
        1 -> Color(0xFFD9A43C)
        2 -> Color(0xFF9AA3AD)
        3 -> Color(0xFFC27D4F)
        else -> colors.onSurfaceVariant.copy(alpha = 0.8f)
    }
    Row(
        // 不裁切整行，封面投影可以完整落在行内外。
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 1.dp).height(68.dp)
            .background(if (nowPlaying && !multiMode) colors.primary.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = if (multiMode) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 独立序号列：前三略放大，保留金 / 银 / 铜色；下方字号不变。
        Column(Modifier.width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%02d".format(rank), color = rankColor, fontFamily = DesignFonts.DmMono, softWrap = false,
                fontWeight = if (top) FontWeight.Bold else FontWeight.Medium,
                fontSize = if (top) 16.sp else 12.sp, maxLines = 1
            )
            val change = song.rankChange
            val movement = chartRankMovementLabel(change)
            if (movement.isNotEmpty() && change != null) {
                Text(movement, color = if (change > 0) xyz.cncnlp.soundwave.designAccentTextColor() else colors.onSurfaceVariant,
                    fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics {
                        contentDescription = "排名${if (change > 0) "上升" else "下降"} ${kotlin.math.abs(change.toLong())} 位"
                    })
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(coverSize).testTag("chart_cover_$rank")
                .homeCoverShadow(shape) // r673：榜单歌曲封面 = 应用封面阴影
                .clip(shape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是 MusicNote 图标、加载时平灰）。
            xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize())
            if (cover.isNotBlank()) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = if (nowPlaying) Color(0xFFC9381F) else colors.onBackground, fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1, modifier = Modifier.swMarquee())
            val meta = listOf(song.singer, song.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.2.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, modifier = Modifier.padding(top = 4.dp).swMarquee())
        }
        if (multiMode) {
            Spacer(Modifier.width(8.dp))
            xyz.cncnlp.soundwave.DesignSelectCheck(selected)
        } else if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreHoriz, "更多：${song.name}", tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 底部榜单选择单行（设计稿 .chart-picker-sheet）：52dp 封面、名称、更新周期 · 曲数，右侧箭头。 */
@Composable
internal fun ChartPickerRow(board: RankBoard, selected: Boolean, onClick: () -> Unit) {
    val count = board.songCount()
    xyz.cncnlp.soundwave.ChartPickerDesignRow(
        name = board.name,
        sub = listOf(board.officialUpdateLabel(), if (count > 0) "$count 首" else "").filter { it.isNotBlank() }.joinToString(" · "),
        cover = board.displayCover(),
        selected = selected,
        onClick = onClick
    )
}

/** 1.0.655 榜单选择网格：一行 3 个，圆角方封面 + 下方榜名（当前榜珊瑚字 + 珊瑚描边），不显示更新周期 / 曲数。 */
@Composable
internal fun ChartPickerGrid(boards: List<RankBoard>, selectedId: String?, onPick: (RankBoard) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        // r673：网格向两侧外扩 ChartPickerShadowBleed 再用同宽 contentPadding 收回——封面尺寸不变，
        // 但 LazyGrid 的裁切边界离开封面，左右两列与首行的阴影不再被裁掉。
        modifier = Modifier.fillMaxWidth().horizontalShadowBleed(ChartPickerShadowBleed).heightIn(max = 520.dp),
        contentPadding = PaddingValues(start = ChartPickerShadowBleed, end = ChartPickerShadowBleed, top = 10.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(boards.size, key = { "${boards[it].source}:${boards[it].id}" }) { i ->
            val board = boards[i]
            val selected = board.id == selectedId
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.fillMaxWidth().clickable { onPick(board) } // r673：去掉整格 clip，避免裁掉封面阴影
                    .semantics { contentDescription = "榜单：${board.name}"; role = Role.Button }
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .homeCoverShadow(shape) // r673（用户 10-22）：榜单切换弹窗封面 = 应用封面阴影
                        .clip(shape).background(colors.surfaceVariant)
                        .then(if (selected) Modifier.border(2.dp, xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent, shape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    val c = remember(board.id) { xyz.cncnlp.soundwave.normalizeCoverUrl(board.displayCover()) }
                    // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是 MusicNote 图标、加载时平灰）。
                    xyz.cncnlp.soundwave.DefaultCoverPlaceholder(Modifier.fillMaxSize())
                    if (c.isNotBlank()) coil.compose.AsyncImage(xyz.cncnlp.soundwave.CacheManager.coverModel(context, c) ?: c, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(
                    board.name, color = if (selected) Color(0xFFC9381F) else colors.onSurface, fontSize = 12.5.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
                )
            }
        }
    }
}


/** r673：榜单切换网格左右外扩量（≥ homeCoverShadow 的横向模糊半径 9dp）。 */
internal val ChartPickerShadowBleed = 12.dp

/** 让组件的测量 / 裁切边界向左右各外扩 [bleed]，布局占位不变；配合同宽 contentPadding 使内容位置与尺寸不变。 */
internal fun Modifier.horizontalShadowBleed(bleed: androidx.compose.ui.unit.Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val b = bleed.roundToPx()
        val wide = if (constraints.hasBoundedWidth) constraints.copy(
            minWidth = constraints.minWidth + 2 * b, maxWidth = constraints.maxWidth + 2 * b
        ) else constraints
        val placeable = measurable.measure(wide)
        val w = (placeable.width - 2 * b).coerceAtLeast(0)
        layout(w, placeable.height) { placeable.place(-b, 0) }
    }
)
