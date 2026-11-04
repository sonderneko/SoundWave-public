package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.homeCoverShadow
import xyz.cncnlp.soundwave.ui.common.swMarquee
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

/* ───────────────────── 今日推荐详情页（设计稿 v2 .page[data-page=today]，412dp 宽实测值） ───────────────────── */

private val TodayHeroBg = Color(0xFF121713)
private val TodayLime = Color(0xFFCCF35B)
private val TodayCurrentTitle = Color(0xFFFF5C45) // r657 第三轮：原型 .row.on b = var(--accent)

/** .topbar：31sp 衬线标题 + 右侧 42dp 圆形按钮。保留返回与曲目搜索入口。 */
@Composable
internal fun TodayDesignHeader(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    loggedIn: Boolean,
    avatarPath: String,
    onLogin: () -> Unit,
    title: String = "今日推荐",
    showBack: Boolean = false,
    /** 非空时为搜索态：标题位换成紧凑搜索框，右侧搜索钮变为 ✕ 取消。 */
    searchField: (@Composable RowScope.() -> Unit)? = null,
    onCancelSearch: () -> Unit = {},
    /** r657 第二轮：非空时右侧第二个圆钮为「⋯ 更多」（原型 02 详情页头 = 搜索 + 更多），否则为登录头像。 */
    onMore: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        // 与首页 AppPageHeader(designTitle) 同几何（上 PageHeaderTopPad=6dp、行高 42dp），进入详情页标题/按钮不跳动。
        // r657 第三轮：原型 .hdr——左右 14dp、间距 9dp；返回键为 42dp .cbtn 圆钮（表面色 + 线框 + 0 6 16 投影）。
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = PageHeaderTopPad).height(42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (searchField != null) {
            // r674（用户 10-22：详情页点搜索后的搜索栏向左扩展，与屏幕左侧边距和整体一致）：
            // 去掉原 10dp 前置留白，搜索栏左缘 = 行 start 14dp = 页面标准左边距（标题 / 封面卡 / 曲目列表同一条线）。
            searchField()
            Spacer(Modifier.width(9.dp))
        } else {
            if (showBack) {
                xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.Back, "返回", onClick = onBack)
                Spacer(Modifier.width(9.dp))
            }
            Text(
                title, color = colors.onBackground, fontSize = 31.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, letterSpacing = (-0.045).em, maxLines = 1,
                modifier = Modifier.weight(1f).offset(y = (-4).dp)
            )
        }
        // 与首页平台切换按钮（HomePlatformButton）同描边、同投影；r657 第二轮：磨砂背景开时为半透明表面（原型 #ph.frost .hdr .cbtn）。
        xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(
            if (searchField != null) Icons.Outlined.Close else Icons.Outlined.Search,
            if (searchField != null) "取消搜索" else "搜索曲目",
            onClick = if (searchField != null) onCancelSearch else onSearch
        )
        Spacer(Modifier.width(9.dp))
        if (onMore != null && searchField == null) xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(Icons.Filled.MoreHoriz, "更多操作", onClick = onMore)
        else UserAvatarBadge(loggedIn = loggedIn, avatarPath = avatarPath, onClick = onLogin, sizeDp = 42f)
    }
}

/** Full-cover rounded recommendation hero; original weekday/art/description/actions float over the image. */
@Composable
internal fun TodayRecommendationHero(
    songs: List<Song>,
    songCount: Int,
    cover: String,
    coverModel: Any?,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onAddAll: () -> Unit,
    onAddNextAll: () -> Unit,
    onDownloadAll: () -> Unit,
    onMore: () -> Unit = {},
    onShuffle: (() -> Unit)? = null
) {
    val accent = SoundwaveDesignPalette.Accent
    val shape = RoundedCornerShape(28.dp)
    val weekday = remember { LocalDate.now().dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.ENGLISH) }
    // Full-height cover, as on HomeDailyHero. Keep imagery visible below the title;
    // readability comes from a gentle continuous veil and local text shadows, not a black panel.
    val heroInk = Color(0xFF06080C)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(470.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape).background(Brush.linearGradient(listOf(Color(0xFF315C54), TodayHeroBg, Color(0xFF23424E))))
    ) {
    if (cover.isNotEmpty()) AsyncImage(coverModel ?: cover, "今日推荐封面", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    Box(Modifier.matchParentSize().background(todayRecommendationReadabilityScrim()))
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
        // r658d（用户 10-20）：英文标题到底部按钮的空间收紧一点。
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp)) {
            Text(
                "$weekday · Made for you".uppercase(), color = Color(0xFFFF5B49), fontSize = 10.sp, lineHeight = 13.sp,
                fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.13.em, maxLines = 1,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 1f), 4f))
            )
            Spacer(Modifier.height(12.dp))
            TodayTitleArt(Modifier.fillMaxWidth().height(106.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                "根据你的收藏与最近收听，为你挑选了 $songCount 首；从熟悉的旋律出发，再接上几首可能会喜欢的新歌。",
                color = Color.White.copy(alpha = 0.94f), fontSize = 12.sp, lineHeight = 19.2.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 4f))
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(46.dp)
                        .accentGlow()
                        .shadow(12.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.55f))
                        .clip(CircleShape).background(accent)
                        .clickable(enabled = songs.isNotEmpty(), onClick = onPlayAll)
                        .padding(start = 20.dp, end = 30.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(DesignGlyphs.PlayTriangle, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("播放全部", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
                }
                // r657（v3 #1）：播放全部 + 随机播放毛玻璃胶囊（+ 收藏）。
                // r657 第 5 轮 #1：随机播放改白底黑色图标圆钮（无文字），收藏圆钮 requiredSize 不再被挤变形。
                if (onShuffle != null) {
                    Spacer(Modifier.width(10.dp))
                    TodayRoundAction(onShuffle, enabled = songs.isNotEmpty(), description = "随机播放") {
                        Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Shuffle, null, tint = SoundwaveDesignPalette.Ink, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                TodayRoundAction(onToggleFavorite) {
                    Icon(
                        if (favorited) DesignGlyphs.HeartFilled else DesignGlyphs.HeartOutline, if (favorited) "取消收藏今日推荐" else "收藏今日推荐",
                        tint = accent, modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun TodayRoundAction(onClick: () -> Unit, enabled: Boolean = true, description: String? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.requiredSize(46.dp)
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.32f))
            .clip(CircleShape).background(Color(0xFFF7F7F2))
            .border(1.dp, SoundwaveDesignPalette.Ink.copy(alpha = 0.08f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description; role = Role.Button } else Modifier),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** .recommend-title：Listen / to your / favorite / music! 错落排版（相对 336x116 标题区定位）。 */
@Composable
private fun TodayTitleArt(modifier: Modifier) {
    Box(modifier) {
        Text(
            "Listen", color = Color.White, fontSize = 36.sp, lineHeight = 26.sp, fontFamily = DesignFonts.Newsreader,
            fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic, letterSpacing = (-0.035).em, softWrap = false,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f)),
            modifier = Modifier.offset(x = 7.dp, y = (-3).dp).graphicsLayer { rotationZ = -2f }
        )
        Text(
            "to your", color = TodayLime, fontSize = 17.6.sp, lineHeight = 13.sp, fontFamily = DesignFonts.Newsreader,
            fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic, letterSpacing = 0.015.em, softWrap = false,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f)),
            modifier = Modifier.offset(x = 134.dp, y = 27.dp).graphicsLayer { rotationZ = 1f }
        )
        // favorite：白字 + 青柠色错位重影与发光。
        Text(
            "favorite", color = TodayLime.copy(alpha = 0.55f), fontSize = 39.6.sp, lineHeight = 29.sp, fontFamily = DesignFonts.Manrope,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.085).em, softWrap = false, fontStyle = FontStyle.Italic,
            modifier = Modifier.offset(x = 29.dp, y = 38.dp)
        )
        Text(
            "favorite", color = Color.White, fontSize = 39.6.sp, lineHeight = 29.sp, fontFamily = DesignFonts.Manrope,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.085).em, softWrap = false, fontStyle = FontStyle.Italic,
            style = TextStyle(shadow = Shadow(TodayLime.copy(alpha = 0.6f), Offset(0f, 2f), 14f)),
            modifier = Modifier.offset(x = 27.dp, y = 35.dp)
        )
        val coral = SoundwaveDesignPalette.Accent
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 23.3.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)) { append("m") }
                withStyle(SpanStyle(fontSize = 33.6.sp)) { append("u") }
                withStyle(SpanStyle(fontSize = 40.1.sp)) { append("s") }
                withStyle(SpanStyle(fontSize = 38.2.sp)) { append("i") }
                withStyle(SpanStyle(fontSize = 54.1.sp, letterSpacing = 0.04.em)) { append("c") }
                withStyle(SpanStyle(fontSize = 55.sp, letterSpacing = 0.015.em)) { append("!") }
            },
            color = coral, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic,
            letterSpacing = (-0.06).em, lineHeight = 40.sp, softWrap = false,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 4f)),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 66.dp).graphicsLayer { rotationZ = -1.2f }
        )
    }
}

/** .sw-toolbar：20sp/700 标题 + 三个 32dp 白色描边圆形工具（下载 / 排序 / 多选）。 */
@Composable
internal fun TodayToolbar(
    songCount: Int,
    multiMode: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onDownload: () -> Unit,
    onSort: () -> Unit,
    onToggleSelect: () -> Unit,
    onBatch: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 30.dp, bottom = 14.dp).height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (multiMode) "已选 $selectedCount 首" else "为你选了 $songCount 首",
            color = colors.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
            letterSpacing = (-0.02).em, maxLines = 1, modifier = Modifier.weight(1f)
        )
        if (multiMode) {
            TodayTool(Icons.Rounded.Tune, "批量操作", enabled = selectedCount > 0, onClick = onBatch)
            Spacer(Modifier.width(6.dp))
        } else {
            TodayTool(Icons.Outlined.FileDownload, "下载", onClick = onDownload)
            Spacer(Modifier.width(6.dp))
            TodayTool(Icons.Outlined.Sort, "排序", onClick = onSort)
            Spacer(Modifier.width(6.dp))
        }
        TodayTool(if (allSelected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, "多选", onClick = onToggleSelect)
    }
}

@Composable
private fun TodayTool(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(colors.surface).border(1.dp, colors.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = colors.onSurface.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(17.dp))
    }
}

internal fun formatTrackDuration(ms: Long): String {
    if (ms < 1000L) return ""
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** .playlist-track：64dp 行；序号 DM Mono 12sp、48dp 封面、15sp/650 标题、12sp 歌手、时长、更多。当前曲目珊瑚底 + 左侧 3dp 色条。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TodayTrackRow(
    song: Song,
    index: Int,
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
    showIndex: Boolean = true,
    /** 搜索跨平台混排时在歌手右侧显示的平台名（与歌手 Text 同字号/同色/同字重）；空 = 不显示（其他共用面默认）。 */
    platformLabel: String = "",
    showCover: Boolean = true,
    showDuration: Boolean = true,
    /** 详情页顶部搜索态下隐藏右侧更多按钮（时长保留）。 */
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
    val ix = remember { MutableInteractionSource() }
    val pressed by ix.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.98f else 1f, tween(90), label = "todayRow")
    val shape = RoundedCornerShape(12.dp)
    // r657 第三轮：原型 .row——72dp 高、左右 14dp、间距 14dp；56dp r14 封面 + 0 8 18 rgba(19,25,21,.14) 投影；
    // 当前曲目 = 珊瑚 12% → 透明（85%）横向渐变，标题珊瑚色；右侧 DM Mono 11sp 时长 + 20dp ⋯。
    Row(
        Modifier.fillMaxWidth().height(72.dp)
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .then(
                // 第 29 项（用户 10-18，参考 1000073453）：整行通栏、无圆角，浅珊瑚粉从左往右渐隐到透明。
                if (nowPlaying && !multiMode) Modifier.background(Brush.horizontalGradient(0f to accent.copy(alpha = 0.16f), 0.45f to accent.copy(alpha = 0.10f), 1f to Color.Transparent)) else Modifier
            )
            .combinedClickable(interactionSource = ix, indication = null, onClick = onClick, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongClick() })
            .semantics { role = Role.Button }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIndex) {
            Text(
                "%02d".format(index), color = colors.onSurfaceVariant, fontSize = 12.sp, fontFamily = DesignFonts.DmMono,
                fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.width(28.dp)
            )
            Spacer(Modifier.width(if (showCover) 12.dp else 10.dp))
        }
        if (showCover) Box(
            Modifier.size(56.dp)
                // r673（用户 10-22）：数字专辑 / 新碟首发单曲、歌手页歌曲、搜索「查看全部」歌曲等共用的 v5 曲目行封面
                // 统一用应用封面阴影（首页同款 homeCoverShadow），替换原先偏淡的 elevation 阴影。
                .homeCoverShadow(RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center
        ) {
            // r657 第二轮性能：按行封面像素解码、data: 封面后台解析（ListRowPerf.kt）。
            val req = rememberRowCoverRequest(song.cover, 56.dp)
            // ACCEPT5 收编：占位常驻垫底——加载/失败不再是 surfaceVariant 平灰。
            DefaultLandscapeCover(Modifier.fillMaxSize())
            if (req != null) AsyncImage(req, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = if (showCover) 14.dp else 0.dp, end = 14.dp)) {
            Text(
                song.name, color = if (nowPlaying) TodayCurrentTitle else colors.onBackground, fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em,
                maxLines = 1, modifier = Modifier.rowMarquee(nowPlaying)
            )
            // r657 第三轮（用户 10-18）：原型 .row.on——封面不再盖暗层 + 律动；正在播放时副标题前 3 根 2.5dp 珊瑚律动柱（.eqb，12dp 高、间距 2dp）。
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                // r658b 第 2 项（用户 10-19）：音质标识不再只在正在播放行显示（详情 / 搜索歌曲行一律显示）。
                val qBadge = songQualityBadgeLabel(song)
                if (qBadge.isNotEmpty()) { QualityBadge(qBadge); Spacer(Modifier.width(6.dp)) }
                Text(
                    song.singer.ifBlank { "未知歌手" }, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                    fontFamily = DesignFonts.Manrope, maxLines = 1, modifier = Modifier.weight(1f, fill = false).rowMarquee(nowPlaying)
                )
                if (platformLabel.isNotBlank()) Text(
                    " · $platformLabel", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                    fontFamily = DesignFonts.Manrope, maxLines = 1
                )
            }
        }
        val dur = formatTrackDuration(song.duration)
        if (showDuration) Text(
            dur, color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.width(48.dp).alpha(if (multiMode) 0f else 1f)
        )
        // E17：律动移到行右侧、「···」左侧（时长之后、按钮之前；无按钮时即行最右端）。
        if (nowPlaying && !multiMode) {
            NowPlayingEqBars(isPlayingNow)
            Spacer(Modifier.width(2.dp))
        }
        if (!showTrailing && !multiMode) Spacer(Modifier.width(8.dp))
        else Box(Modifier.size(36.dp).clip(CircleShape).clickable(enabled = !multiMode) { showSheet = true }, contentAlignment = Alignment.Center) {
            if (multiMode) DesignSelectCheck(selected)
            else Icon(Icons.Filled.MoreHoriz, "更多：${song.name}", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
    if (showSheet) {
        DesignTrackActionSheet(
            song = song,
            liked = liked,
            showDownload = showDownload,
            canDelete = onDelete != null,
            onDismiss = { showSheet = false },
            // ACCEPT4（审计 §9 C 类）：prop 只执行动作；关闭走内容侧退场句柄，showSheet=false 由 onDismiss 在退场后承担。
            onSimilar = { onSimilar() },
            onLike = { onLike() },
            onAddNext = { onAddNext() },
            onAdd = { onAdd() },
            onDownload = { onDownload() },
            onDelete = { onDelete?.invoke() },
            onDislike = { onDislike() },
            originPlaylist = originPlaylist
        )
    }
}

/** Shared real Home/detail overlay: clear top, continuous local lower scrim (never a panel). */
internal fun todayRecommendationReadabilityScrim() = Brush.verticalGradient(
    0f to Color.Transparent, 0.25f to Color.Transparent,
    0.62f to Color(0xFF06080C).copy(alpha = 0.55f), 1f to Color(0xFF06080C).copy(alpha = 0.86f)
)
