package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.motion.sharedCoverSource
import xyz.cncnlp.soundwave.ui.motion.SharedCoverTransition
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.IsolatedSection
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.HorizontalDivider

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

import android.Manifest
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.media.MediaRouter
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.staggerEnter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
// width/height via layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.util.Calendar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
// Switch/AlertDialog via material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import xyz.cncnlp.soundwave.ui.common.rememberScrolledPastTop
import xyz.cncnlp.soundwave.ui.common.smoothScrollToTop
import xyz.cncnlp.soundwave.ui.common.smoothScrollToItem

internal data class LibraryPageThemeColors(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val outline: Color
)

internal fun libraryPageThemeColors(colorScheme: ColorScheme) = LibraryPageThemeColors(
    background = colorScheme.background,
    onBackground = colorScheme.onBackground,
    surface = colorScheme.surface,
    onSurface = colorScheme.onSurface,
    surfaceVariant = colorScheme.surfaceVariant,
    onSurfaceVariant = colorScheme.onSurfaceVariant,
    primary = colorScheme.primary,
    onPrimary = colorScheme.onPrimary,
    outline = colorScheme.outline
)

/** Fade only the lower edge of non-classic detail artwork into the same blurred page backdrop. */
internal fun Modifier.fadeArtworkToBackdrop(fraction: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val height = size.height.coerceAtLeast(1f)
            val mask = Brush.verticalGradient(
                colorStops = (0..32).map { step ->
                    val position = step / 32f
                    position to Color.Black.copy(
                        alpha = immersivePlayerHeroArtworkAlpha(position * height, height, fraction)
                    )
                }.toTypedArray(),
                startY = 0f,
                endY = height
            )
            onDrawWithContent {
                drawContent()
                drawRect(brush = mask, blendMode = BlendMode.DstIn)
            }
        }

@Composable
private fun DetailCoverActions(
    songs: List<Song>,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    buttonBackground: Color,
    buttonForeground: Color,
    secondaryBackground: Color,
    secondaryForeground: Color,
    modifier: Modifier = Modifier
) {
    val primarySurface = Modifier.clip(RoundedCornerShape(24.dp)).background(buttonBackground)
    val secondarySurface = { shape: androidx.compose.ui.graphics.Shape ->
        Modifier.clip(shape).background(secondaryBackground)
    }
    Row(modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).then(secondarySurface(CircleShape)).clickable(enabled = songs.isNotEmpty()) {
                val shuffled = songs.shuffled()
                shuffled.firstOrNull()?.let { onPlay(it, shuffled) }
            },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Shuffle, "随机播放", tint = secondaryForeground, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.height(42.dp).then(primarySurface)
                .clickable(enabled = songs.isNotEmpty()) { songs.firstOrNull()?.let { onPlay(it, songs) } }
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.PlaySolid, "播放全部", tint = buttonForeground, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
            Text("播放全部", color = buttonForeground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(42.dp).then(secondarySurface(CircleShape)).clickable(onClick = onToggleFavorite), contentAlignment = Alignment.Center) {
            Icon(
                if (favorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (favorited) "取消收藏歌单" else "收藏歌单",
                tint = secondaryForeground,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 我的喜欢 / 本地歌单三张叠放封面：[front, middle, back]，null 用设计稿默认封面。
 * 未登录 → 全默认；无歌曲 → 全默认；无自定义封面 → 前三首歌曲封面（缺失补默认）；有自定义封面 → 主封面为自定义，后两张为前两首歌曲封面。
 */
internal fun libraryStackCovers(loggedIn: Boolean, customCover: String?, songCovers: List<String>): List<String?> {
    val songs = songCovers.filter { it.isNotBlank() }
    if (!loggedIn || songCovers.isEmpty()) return listOf(null, null, null)
    val picks = if (!customCover.isNullOrBlank()) listOf(customCover) + songs.take(2) else songs.take(3)
    return (0 until 3).map { picks.getOrNull(it) }
}

/** SW-09-16：详情排序按歌单 id 存于 soundwave_sort 的 `mode_<id>` 键；与歌单位置无关。 */
internal fun readPlaylistSortMode(context: android.content.Context, playlistId: String): String? = runCatching {
    ContextPreferences.open(context, "soundwave_sort", android.content.Context.MODE_PRIVATE)
        .getString("mode_$playlistId", null)
}.getOrNull()

/** SW-09-16：删歌单 / 同 id 重导时清理排序键，避免新歌单继承旧排序（按钮常驻变红的根因之一）。 */
internal fun clearPlaylistSortMode(context: android.content.Context, playlistId: String) {
    runCatching {
        ContextPreferences.open(context, "soundwave_sort", android.content.Context.MODE_PRIVATE)
            .edit().remove("mode_$playlistId").apply()
    }
}

/** SW-09-16：撤销删除恢复歌单时，把删前快照的排序值一并恢复（无快照则保持清理结果）。 */
internal fun restorePlaylistSortMode(context: android.content.Context, playlistId: String, mode: String?) {
    runCatching {
        val prefs = ContextPreferences.open(context, "soundwave_sort", android.content.Context.MODE_PRIVATE)
        if (mode.isNullOrBlank() || mode == "default") prefs.edit().remove("mode_$playlistId").apply()
        else prefs.edit().putString("mode_$playlistId", mode).apply()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryPage(
    bundle: LocalStore.UserBundle,
    history: List<Song>,
    downloads: List<DownloadItem>,
    loggedIn: Boolean,
    user: String,
    avatarPath: String = "",
    detail: Playlist?,
    detailSongs: List<Song>,
    detailLoading: Boolean = false,
    /** accept6-favempty：回源拉取失败（且条目标称非空）时为真——空区渲染失败卡而非空单卡。 */
    detailLoadFailed: Boolean = false,
    onRetryDetail: () -> Unit = {},
    onOpen: (Playlist) -> Unit,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onPlayFromPlaylist: (Song, List<Song>, String) -> Unit = { song, songs, _ -> onPlay(song, songs) },
    onResumeDetail: (Song, List<Song>) -> Unit = onPlay,
    onLogin: () -> Unit,
    onImport: () -> Unit,
    onScanLocal: () -> Unit = {},
    onAddLocalFolder: () -> Unit = {},
    localFolderLabels: List<String> = emptyList(),
    localScanRunning: Boolean = false,
    localScanStatus: String = "尚未扫描",
    onRefreshPlaylistCounts: () -> Unit = {},
    onHeartMode: (List<Song>) -> Unit = {},
    onPlaylistHeartMode: (Playlist) -> Unit = { playlist -> onHeartMode(playlist.songs) },
    onClearLiked: () -> Unit = {},
    heartActive: Boolean = false,
    activeHeartPlaylistKey: String? = null,
    onLike: (Song) -> Unit = {},
    onLikeBatch: (List<Song>, Boolean) -> Unit = { _, _ -> },
    isLiked: (Song) -> Boolean = { false },
    onAdd: (Song) -> Unit = {},
    onAddBatch: (List<Song>) -> Unit = {},
    onAddNext: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {},
    onDownloadBatch: (List<Song>) -> Unit = {},
    onDeleteSong: (Song, Boolean) -> Unit = { _, _ -> },
    onDeleteSongs: (List<Song>, Boolean) -> Unit = { _, _ -> },
    /** 歌曲三点对话框附加操作：不感兴趣 / 相似歌曲。 */
    onDislikeSong: (Song) -> Unit = {},
    onSimilarSong: (Song) -> Unit = {},
    onCoverPick: (Playlist) -> Unit = {},
    onRenamePlaylist: (Playlist, String) -> Unit = { _, _ -> },
    onDeletePlaylist: (Playlist) -> Unit = {},
    showPlaylistFavorite: Boolean = false,
    isPlaylistFavorited: (Playlist) -> Boolean = { it.favorited },
    onTogglePlaylistFavorite: (Playlist) -> Unit = {},
    detailCoverStyle: String = DetailCoverStyles.DEFAULT,
    showLocateButton: Boolean = false,
    locateKey: Int = 0,
    currentKey: String = "",
    /** r658b #11：当前播放歌曲封面（音乐库「我的喜欢」唱片中心随之更新）。 */
    currentCover: String = "",
    onReorderPlaylists: (List<String>) -> Unit = {},
    onClearHistory: () -> Unit = {},
    libOrder: List<String> = emptyList(),
    /** 收藏内部顺序（FavoriteOrderSync 显示序，歌单组/专辑组各一份；与在线池顺序互不读写）。 */
    favPlOrder: List<String> = emptyList(),
    favAlbumOrder: List<String> = emptyList(),
    onReorderFavorites: (List<String>, Boolean) -> Unit = { _, _ -> },
    playingNow: Boolean = false,
    detailFromHome: Boolean = false,
    /** 非空时覆盖设计稿详情页页头标题（如首页「新碟首发」进入的专辑），并隐藏左上返回箭头。 */
    detailHeaderTitle: String = "",
    onNewPlaylist: () -> Unit = {},
    onPlatformClick: (String) -> Unit = {},
    platFilter: String = "",
    onPlatFilter: (String) -> Unit = {},
    platform: String = "wy",
    onDetailStatusBarAppearance: (Boolean?) -> Unit = {},
    suppressArtworkBackdrop: Boolean = false,
    /** 设计稿详情页多选态：为 true 时宿主隐藏迷你条与底栏，由详情页底部操作条接管。 */
    onDetailSelectionModeChange: (Boolean) -> Unit = {},
    /** 歌手详情「专辑」横排：按平台歌手 id 拉取专辑列表；点击打开专辑详情。 */
    loadSingerAlbums: suspend (artistId: String, source: String) -> List<ArtistAlbum> = { id, src -> xyz.cncnlp.soundwave.source.direct.fetchArtistAlbumsDirect(src.ifBlank { "wy" }, id) },
    onOpenSingerAlbum: (ArtistAlbum) -> Unit = {},
    loadSingerAlbumsPage: suspend (artistId: String, source: String, page: Int) -> List<ArtistAlbum> = { id, src, page -> xyz.cncnlp.soundwave.source.direct.fetchArtistAlbumsDirect(src.ifBlank { "wy" }, id, page = page) }
 ) {
    val pageColors = libraryPageThemeColors(MaterialTheme.colorScheme)
    val primary = pageColors.primary
    val onBg = pageColors.onBackground
    val dialogFg = pageColors.onSurface
    val listState = rememberLazyListState()
    val listReduceMotion = MotionPolicy.rememberReduceMotion()
    // ACCEPT4（v8 稿 §5）：详情曲目到达后错峰淡入——页面级入场门按 detail.id 重置，已播序号不重播。
    val detailStaggerGate = remember(detail?.id) { xyz.cncnlp.soundwave.ui.common.StaggerGate() }
    val scope = rememberCoroutineScope()
    val libCtx = LocalContext.current
    // 1.0.655：本地歌单曲目不进入账号快照，内存为空时回落读取本机扫描结果（已扫描过则不再提示扫描）。
    @Suppress("NAME_SHADOWING")
    val detailSongs = remember(detail?.id, detailSongs) {
        if (detail?.id == "local" && detailSongs.isEmpty()) LocalLibraryStore.songs(libCtx) else detailSongs
    }
    val libraryOwner = remember(libCtx) { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() }
    // 歌单滚动位置记忆（洛雪 isSaveScrollLocation）
    LaunchedEffect(detail?.id) {
        val id = detail?.id ?: return@LaunchedEffect
        val idx = ListScrollStore.index(libCtx, id, libraryOwner)
        val offset = ListScrollStore.offset(libCtx, id, libraryOwner)
        if (idx > 0 || offset > 0) {
            try { listState.scrollToItem(idx, offset) } catch (_: Exception) {}
        }
    }
    // r657 第二轮性能：原来在组合期读取 firstVisibleItemIndex 作为 LaunchedEffect 键，滚动中每跨过一行整页（详情 + 全部可见行）都要重组一次；
    // 改为 snapshotFlow 在协程里观察，滚动不再触发 LibraryPage 重组。
    LaunchedEffect(detail?.id) {
        val id = detail?.id ?: return@LaunchedEffect
        // Do not write guarded preferences for every row crossed during a fling.
        // Observe the offset too, but checkpoint only after movement settles.
        @OptIn(FlowPreview::class)
        val settled = androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().debounce(250)
        settled.collect { (idx, offset) -> ListScrollStore.save(libCtx, id, idx, offset, libraryOwner) }
    }
    DisposableEffect(detail?.id, libraryOwner) {
        val id = detail?.id
        onDispose {
            if (id != null) ListScrollStore.save(libCtx, id,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, libraryOwner)
        }
    }
    if (detail != null) {
        // 「我的喜欢」封面优先用户自定义封面（detail.cover），空才回退收藏歌曲高清原图。
        val coverUrl = if (detail.id == "liked") {
            detail.cover
                .ifEmpty { detailSongs.firstOrNull()?.cover.orEmpty() }
                .ifEmpty { bundle.playlists.firstOrNull { it.id == detail.id }?.cover.orEmpty() }
        } else {
            detail.cover
                .ifEmpty { detailSongs.firstOrNull()?.cover.orEmpty() }
                .ifEmpty { bundle.playlists.firstOrNull { it.id == detail.id }?.cover.orEmpty() }
        }
        val detailCoverTargetPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp * 1.5f).roundToPx() }.coerceIn(600, 1600)
        val detailCover = normalizeCoverUrl(if (isTodayRecommendationDetail(detail)) detail.cover.ifBlank { todayRecommendationCover(detailSongs) } else coverUrl, targetSizePx = detailCoverTargetPx)
        // 优先复用已存在的落盘图；首次进入则保持规范化 URL 不变，由 Coil 显示、后台同步落盘供后续复用。
        // 页面已绘制后不替换 Image model，避免背景与 Hero 因重复请求短暂空白。
        var detailCoverModel by remember(detailCover) { mutableStateOf(CacheManager.coverModel(libCtx, detailCover, detailCoverTargetPx) ?: detailCover) }
        var detailCoverLoadFailed by remember(detailCover) { mutableStateOf(false) }
        // 排行榜/推荐流禁止删除；仍允许多选批量操作。
        val isFeedPl = detail.id.startsWith("rank_") || detail.id.startsWith("hot_") || detail.id.startsWith("reco_")
        val allowDelete = !isFeedPl && detail.id != "liked" && (detail.local || detail.id == "local" || detail.id == "history" || detail.id.startsWith("local_") || bundle.playlists.any { it.id == detail.id })
        var plSearch by remember(detail.id) { mutableStateOf("") }
        var showPlSearch by remember(detail.id) { mutableStateOf(false) }
        val searchFocusRequester = remember(detail.id) { FocusRequester() }
        val softwareKeyboard = LocalSoftwareKeyboardController.current
        var sortMode by remember(detail.id) {
                    // 批24b：排序状态按歌单持久化（SharedPreferences），下次进入保留用户手动选择。
                    val saved = runCatching {
                        ContextPreferences.open(libCtx, "soundwave_sort", android.content.Context.MODE_PRIVATE, libraryOwner)
                            .getString("mode_${detail.id}", "default")
                    }.getOrNull() ?: "default"
                    mutableStateOf(saved)
                } // default|old|name|singer
        // 1.0.654：默认2 / 沉浸封面已删除（normalize 迁移为默认），旧分支保留但不再命中。
        val normalizedDetailStyle = DetailCoverStyles.normalize(detailCoverStyle)
        val standardDetailStyle = normalizedDetailStyle == DetailCoverStyles.STANDARD
        val immersiveCover = normalizedDetailStyle == DetailCoverStyles.IMMERSIVE
        val default2Cover = normalizedDetailStyle == DetailCoverStyles.DEFAULT2
        val detailHeroFadeFraction = detailHeroArtworkFadeFraction(normalizedDetailStyle)
        val adaptiveDetailStyle = detailUsesArtworkBackdrop(normalizedDetailStyle)
        val editorialDetailStyle = !immersiveCover && !default2Cover && !adaptiveDetailStyle
        val detailBaseColor = MaterialTheme.colorScheme.background
        var coverTint by remember(detailCover, adaptiveDetailStyle) {
            mutableStateOf(if (adaptiveDetailStyle) detailBaseColor else if (normalizedDetailStyle == DetailCoverStyles.IMMERSIVE_HERO) Color(0xFF2A302B) else primary)
        }
        val detailTitle = when (detail.id) {
            "liked" -> "我的喜欢"
            "local" -> "本地歌单"
            "history" -> "最近播放"
            else -> detail.name.ifBlank { "歌单" }
        }
        // 今日推荐详情页：专用艺术体头部 + 收藏/播放 + 3 个纯图标按钮 + 歌曲分隔线。
        val isTodayRec = isTodayRecommendationDetail(detail)
        // r657 第二轮：播放来源（历史「来自 X」）= 详情名；今日推荐 / 我的喜欢 / 本地歌单用固定名。
        // M 波：同时携带 detail 身份，播放器菜单「出处」播放来源行可按 ID 跳回本详情（无身份页面仍只传名）。
        PlaySourceScope(when { isTodayRec -> "今日推荐"; detail.id == "liked" -> "我的喜欢"; detail.id == "local" -> "本地歌单"; else -> detail.name.ifBlank { "歌单" } }, playlist = detail)
        // 设计稿 v2 详情（默认封面样式）：歌单 / 专辑 / 榜单三种页头 + 统一工具条、曲目行与多选操作条。
        val designDetail = isTodayRec || editorialDetailStyle
        // r657 第三轮 b：「沉浸」详情样式（DetailImmersiveHero.kt）。r658b #8（用户 10-19）：今日推荐也跟随沉浸样式。
        // r658h（用户 10-20）：沉浸封面只用于今日推荐详情（同首页今日推荐卡：英文眉题 / 标题 / 按钮压在封面上）。
        val immersiveHero = !isTodayRec && normalizedDetailStyle == DetailCoverStyles.IMMERSIVE_HERO
        val designKind = remember(detail.id, detail.name, detailSongs) { detailDesignKind(detail, detailSongs) }
        var showDesignSortMenu by remember(detail.id) { mutableStateOf(false) }
        var showDesignDetailMenu by remember(detail.id) { mutableStateOf(false) }
        val isSingerDetail = detail.id.startsWith("singer_")
        var singerAlbums by remember(detail.id) { mutableStateOf(emptyList<ArtistAlbum>()) }
        var singerAlbumPage by remember(detail.id) { mutableStateOf(1) }
        var singerAlbumLoading by remember(detail.id) { mutableStateOf(false) }
        var singerAlbumError by remember(detail.id) { mutableStateOf(false) }
        var singerAlbumHasMore by remember(detail.id) { mutableStateOf(true) }
        var singerAlbumRequest by remember(detail.id) { mutableStateOf(0) }
        LaunchedEffect(detail.id, detail.remoteId, detail.source, singerAlbumRequest) {
            if (isSingerDetail && detail.remoteId.isNotBlank()) {
                singerAlbumLoading = true
                singerAlbumError = false
                try {
                    val page = singerAlbumPage
                    val batch = withContext(Dispatchers.IO) {
                        if (page == 1) loadSingerAlbums(detail.remoteId, detail.source)
                        else loadSingerAlbumsPage(detail.remoteId, detail.source, page)
                    }
                    val combined = (singerAlbums + batch).distinctBy { "${it.source}:${it.kind}:${it.id}" }
                    singerAlbumHasMore = batch.isNotEmpty() && combined.size > singerAlbums.size
                    singerAlbums = combined
                    if (singerAlbumHasMore) singerAlbumPage = page + 1
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    singerAlbumError = true
                } finally {
                    singerAlbumLoading = false
                }
            }
        }
        // 设计稿详情搜索态：隐藏封面主视觉与工具条，只显示页头搜索框 + 曲目。
        val designSearching = designDetail && showPlSearch
        val detailAuthor = if (detail.id == "liked") "在线歌单" else detail.author.trim()
        val detailDescription = detail.description.trim()
        val detailProvenance = when {
            detail.id == "liked" -> ""
            // 收藏条目历史落盘带 local=true 误标，但 remoteId 在——按远程来源呈现，不冒充本地。
            detail.id == "local" || (detail.local && detail.remoteId.isBlank()) -> "本地"
            else -> platformName(detail.source).ifEmpty { "在线" }
        }
        val detailSongCount = detail.count.takeIf { it > 0 } ?: detailSongs.size
        val heroCoverSize = (LocalConfiguration.current.screenWidthDp.dp * 0.66f).coerceAtMost(300.dp)
        val heroCoverHeight = heroCoverSize + 12.dp
        // 近似方形主视觉；高度与设备宽度关联，避免窄屏上形成过长的竖向裁切。
        val immersiveHeroHeight = immersiveCoverHeroHeightDp(LocalConfiguration.current.screenWidthDp).dp
        val detailHeroRequestSizePx = with(LocalDensity.current) {
            maxOf(LocalConfiguration.current.screenWidthDp.dp.roundToPx(), immersiveHeroHeight.roundToPx())
        }
        val detailHeroImageRequest = remember(detailCover, detailCoverModel, immersiveCover, detailHeroRequestSizePx, libCtx) {
            if (detailCover.isBlank()) null
            else coil.request.ImageRequest.Builder(libCtx)
                .data(detailCoverModel)
                .size(detailHeroRequestSizePx)
                .precision(coil.size.Precision.EXACT)
                .memoryCacheKey(detailHeroCoverMemoryCacheKey(detailCover, detailHeroRequestSizePx))
                .build()
        }
        val detailSurfaceTone = if (adaptiveDetailStyle) detailArtworkBackdropTone(detailBaseColor, coverTint) else detailBaseColor
        // Adaptive text still follows the sampled cover; the tone now matches the actual backplate opacity, not a theme/accent guess.
        val detailAdaptiveForeground = detailAdaptiveTextForeground(detailSurfaceTone)
        val detailTextColor = if (adaptiveDetailStyle) detailAdaptiveForeground else onBg
        SideEffect {
            onDetailStatusBarAppearance(detailStatusBarLightAppearance(normalizedDetailStyle, detailTextColor))
        }
        DisposableEffect(Unit) {
            onDispose { onDetailStatusBarAppearance(null) }
        }
        val detailHeaderTint = if (adaptiveDetailStyle) detailAdaptiveForeground else pageColors.onBackground
        val actionPrimary = pageColors.primary
        val detailActionTint = if (adaptiveDetailStyle) detailAdaptiveForeground else pageColors.onSurface
        // 普通播放动作使用主题强调角色；自适应封面上的次级动作保留原有对比衬底。
        val playActionBackground = pageColors.primary
        val playActionForeground = pageColors.onPrimary
        val secondaryActionBackground = if (adaptiveDetailStyle) {
            if (detailAdaptiveForeground == Color.Black) {
                Color.White.copy(alpha = 0.20f)
            } else {
                Color.Black.copy(alpha = 0.22f)
            }
        } else {
            pageColors.surfaceVariant
        }
        val secondaryActionForeground = if (adaptiveDetailStyle) detailAdaptiveForeground else pageColors.onSurfaceVariant
        var showSortSheet by remember(detail.id) { mutableStateOf(false) }
        var showPlaylistSettings by remember(detail.id) { mutableStateOf(false) }
        var multiMode by remember(detail.id) { mutableStateOf(false) }
        var selectedKeys by remember(detail.id) { mutableStateOf(setOf<String>()) }
        var batchSnap by remember(detail.id) { mutableStateOf(listOf<Song>()) }
        LaunchedEffect(detailCover, adaptiveDetailStyle, detailCoverLoadFailed) {
            if (detailCover.isNotBlank()) {
                val local = withContext(Dispatchers.IO) { CacheManager.ensureCover(libCtx, detailCover, targetSizePx = detailCoverTargetPx) }
                // Keep a successful request's model stable. Only after failure use the now-cached
                // local copy; an unchanged error model must not restart this effect indefinitely.
                val cachedFallback = CacheManager.coverModel(libCtx, local) ?: local
                if (detailCoverLoadFailed &&
                    (local.startsWith("/") || local.startsWith("file:")) &&
                    cachedFallback != detailCoverModel
                ) {
                    detailCoverModel = cachedFallback
                    detailCoverLoadFailed = false
                }
                val sampled = withContext(Dispatchers.IO) {
                    runCatching {
                        val options = BitmapFactory.Options().apply { inSampleSize = 32 }
                        BitmapFactory.decodeFile(local, options)?.let { bitmap ->
                            try {
                                var red = 0L
                                var green = 0L
                                var blue = 0L
                                var count = 0L
                                val stepX = (bitmap.width / 12).coerceAtLeast(1)
                                val stepY = (bitmap.height / 12).coerceAtLeast(1)
                                for (y in 0 until bitmap.height step stepY) {
                                    for (x in 0 until bitmap.width step stepX) {
                                        val pixel = bitmap.getPixel(x, y)
                                        red += android.graphics.Color.red(pixel)
                                        green += android.graphics.Color.green(pixel)
                                        blue += android.graphics.Color.blue(pixel)
                                        count++
                                    }
                                }
                                if (count == 0L) null else Color(
                                    red = (red / count / 255f).coerceIn(0f, 1f),
                                    green = (green / count / 255f).coerceIn(0f, 1f),
                                    blue = (blue / count / 255f).coerceIn(0f, 1f)
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }.getOrNull()
                }
                if (sampled != null) coverTint = sampled
            }
        }
        // 返回优先级：批量操作菜单 → 退出多选 → 关闭排序 → 关闭搜索 → 详情返回（交给全局处理）。
        BackHandler(enabled = showPlaylistSettings || multiMode || showPlSearch || showSortSheet) {
            when {
                showPlaylistSettings -> showPlaylistSettings = false
                multiMode -> { multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList() }
                showSortSheet -> showSortSheet = false
                showPlSearch -> { showPlSearch = false; plSearch = "" }
            }
        }
        LaunchedEffect(showPlSearch, detail.id) {
            if (showPlSearch) {
                // 设计稿详情的搜索框位于列表首项（页头）：先回到顶部再聚焦。
                if (designDetail) runCatching { listState.scrollToItem(0) }
                delay(60L)
                runCatching { searchFocusRequester.requestFocus() }
                softwareKeyboard?.show()
            } else {
                softwareKeyboard?.hide()
            }
        }
        val displaySongs = remember(detailSongs, plSearch, sortMode) {
            // 服务端歌单偶发重复歌曲：去重防 LazyColumn 重复 key 闪退
            var list = detailSongs.toList().distinctBy { it.key() }
            if (plSearch.isNotBlank()) {
                val q = plSearch.trim().lowercase()
                list = list.filter { it.name.lowercase().contains(q) || it.singer.lowercase().contains(q) }
            }
            list = when (sortMode) {
                // 默认 = 原列表顺序（收藏为最近添加在前）；最早 = 最早添加在前（收藏列表反转，批24b 修复此前恒等于默认的假排序）。
                "old" -> list.reversed()
                "name" -> list.sortedBy { it.name.lowercase() }
                "singer" -> list.sortedBy { it.singer.lowercase() }
                else -> list
            }
            list
        }
        var resumeDismissed by remember(libraryOwner, detail.source, detail.id) { mutableStateOf(false) }
        val resumeSong = remember(detailSongs, history) { DetailResumePolicy.candidate(detailSongs, history) }
        val selectedCount = remember(displaySongs, selectedKeys) { if (selectedKeys.isEmpty()) 0 else displaySongs.count { it.key() in selectedKeys } }
        val reduceTopMotion = MotionPolicy.rememberReduceMotion()
        var returnTopJob by remember(listState) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var previousSortMode by remember(detail.id) { mutableStateOf(sortMode) }
        LaunchedEffect(sortMode, detail.id) {
            // Initial composition must not compete with the saved-position restore above.
            // Only a user change of sorting should return an existing page to the top.
            if (previousSortMode == sortMode) return@LaunchedEffect
            previousSortMode = sortMode
            try { listState.smoothScrollToTop(reduceTopMotion) } catch (_: Exception) {}
        }
        LaunchedEffect(plSearch, sortMode, detail.id) {
            if (plSearch.isNotBlank() || sortMode != "default") {
                val visible = displaySongs.map { it.key() }.toSet()
                selectedKeys = selectedKeys.intersect(visible)
                batchSnap = batchSnap.filter { it.key() in selectedKeys }
            }
        }
        // 定位当前歌曲：以当前展示列表（含搜索/排序）为准，独立 item 后索引与行号一致。
        LaunchedEffect(locateKey, currentKey, displaySongs) {
            if (locateKey <= 0 || currentKey.isEmpty()) return@LaunchedEffect
            val i = displaySongs.indexOfFirst { it.key() == currentKey }
            if (i >= 0) try { listState.smoothScrollToItem(i) } catch (_: Exception) {}
        }
        // r657：透明磨砂背景打开时页面底由宿主的 FrostedPageBackdrop 绘制（不透明），这里不再盖纸色。
        // r673：详情页渲染树放进独立 composable lambda，避免 LibraryPage 合成超大 dex 方法（见 ui/common/IsolatedSection.kt）。
        IsolatedSection { Box(Modifier.fillMaxSize().background(if (xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive) Color.Transparent else MaterialTheme.colorScheme.background)) {
            // Adaptive detail styles use the current cover across the full page; classic Default keeps its original path.
            // 设计稿详情为纯纸色底：不绘制整页封面模糊背景。
            if (detailCover.isNotEmpty() && !suppressArtworkBackdrop) {
                if (!designDetail) AsyncImage(
                    model = detailCoverModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (adaptiveDetailStyle) IMMERSIVE_PLAYER_BACKDROP_ALPHA else 0.40f
                        scaleX = if (adaptiveDetailStyle) IMMERSIVE_PLAYER_BACKDROP_SCALE else 1.14f
                        scaleY = if (adaptiveDetailStyle) IMMERSIVE_PLAYER_BACKDROP_SCALE else 1.14f
                    }.blur(if (adaptiveDetailStyle) IMMERSIVE_PLAYER_BACKDROP_BLUR_DP.dp else 72.dp),
                    contentScale = ContentScale.Crop
                )
            }
            if (!designDetail) Box(
                Modifier.fillMaxSize().background(
                    if (adaptiveDetailStyle) Brush.verticalGradient(colorStops = arrayOf(
                        0f to coverTint.copy(alpha = detailBackdropWashAlpha(0f)),
                        0.22f to coverTint.copy(alpha = detailBackdropWashAlpha(0.22f)),
                        0.48f to coverTint.copy(alpha = detailBackdropWashAlpha(0.48f)),
                        0.74f to coverTint.copy(alpha = detailBackdropWashAlpha(0.74f)),
                        1f to detailSurfaceTone.copy(alpha = detailBackdropWashAlpha(1f))
                    )) else Brush.verticalGradient(colorStops = arrayOf(
                        0f to coverTint.copy(alpha = 0.10f),
                        0.22f to coverTint.copy(alpha = 0.12f),
                        0.48f to coverTint.copy(alpha = 0.16f),
                        0.74f to coverTint.copy(alpha = 0.20f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.58f)
                    ))
                )
            )
            if (!designDetail) Box(
                Modifier.fillMaxSize().background(
                    if (adaptiveDetailStyle) Color.Transparent
                    else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.Black.copy(alpha = 0.08f)
                    else Color.White.copy(alpha = 0.74f)
                )
            )
            Box(Modifier.fillMaxSize()) {
                val toolTint = if (editorialDetailStyle) {
                    Color(0xFFF4F3EB)
                } else if (adaptiveDetailStyle) {
                    detailAdaptiveForeground
                } else detailActionTint
                val topControlBackdrop = Color.Transparent
                // 今日推荐：设计稿页头随列表滚动（TodayDesignHeader）；仅搜索态叠加此工具行。
                if (!designDetail) Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(if (designDetail) MaterialTheme.colorScheme.background else Color.Transparent)
                        .align(Alignment.TopCenter).zIndex(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(34.dp).clip(CircleShape)
                            .background(topControlBackdrop)
                            .clickable {
                            when {
                                showPlaylistSettings -> showPlaylistSettings = false
                                multiMode -> { multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList() }
                                showSortSheet -> showSortSheet = false
                                showPlSearch -> { showPlSearch = false; plSearch = "" }
                                else -> onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = toolTint, modifier = Modifier.size(22.dp))
                    }
                    if (showPlSearch) {
                        Row(
                            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp))
                                .background(toolTint.copy(alpha = 0.10f)).padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Search, null, tint = toolTint.copy(alpha = 0.78f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            BasicTextField(
                                value = plSearch,
                                onValueChange = { plSearch = it },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = toolTint, fontSize = 14.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(toolTint),
                                modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (plSearch.isBlank()) Text("搜索曲目", color = toolTint.copy(alpha = 0.58f), fontSize = 13.sp)
                                        inner()
                                    }
                                }
                            )
                            if (plSearch.isNotEmpty()) {
                                Text("×", color = toolTint, fontSize = 18.sp, modifier = Modifier.clickable { plSearch = "" }.padding(start = 6.dp))
                            }
                        }
                        Text(
                            "完成", color = toolTint, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.width(44.dp).clickable { showPlSearch = false; plSearch = "" }
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(34.dp).clip(CircleShape)
                            .background(topControlBackdrop)
                            .clickable { showPlSearch = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Search, "搜索曲目", tint = toolTint, modifier = Modifier.size(20.dp))
                        }
                        // 今日推荐详情页：右侧搜索 + 登录头像（用户要求）
                        if (isTodayRec) {
                            Spacer(Modifier.width(6.dp))
                            UserAvatarBadge(loggedIn = loggedIn, avatarPath = avatarPath, onClick = onLogin, sizeDp = 34f)
                        }
                    }
                }
                // 1.0.655：设计稿详情页顶栏（标题 + 右侧按钮）固定不随列表滚动，与首页一致。
                // r657 修复：顶栏叠在列表上方——静止时透明（与页面 / 磨砂底无接缝），滚动 0→120dp 淡入 paper 64% + 22dp 模糊 + 1dp 线（原型 02b）。
                val headerGlass = xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState()
                // r657 第二轮：大页头随列表滚走，滚过英雄卡后折叠磨砂顶栏淡入，「曲目列表」工具条钉在其下（原型 02 / 02b，DetailTopChrome.kt）。
                val detailToolbarKey = "today_toolbar_${detail.id}"
                val topChrome = rememberDetailTopChromeState(listState, "detail_hero_${detail.id}", detailToolbarKey)
                val detailToolbar: @Composable (Boolean) -> Unit = { pinnedCopy ->
                        val allSelected = displaySongs.isNotEmpty() && selectedKeys.size == displaySongs.size
                        DetailDesignToolbar(
                            title = when {
                                isTodayRec -> "为你选了 $detailSongCount 首"
                                standardDetailStyle -> "曲目"
                                designKind == DetailDesignKind.Chart -> "榜单曲目"
                                isSingerDetail -> "热门歌曲"
                                designKind == DetailDesignKind.Album -> "曲目"
                                else -> "曲目列表"
                            },
                            // 歌单 / 歌手页标题右侧不再显示曲目数。
                            countLabel = "",
                            multiMode = multiMode,
                            selectedCount = selectedCount,
                            allSelected = allSelected,
                            sortMode = sortMode,
                            sortMenuOpen = showDesignSortMenu && pinnedCopy == topChrome.toolbarPinned.value,
                            // 今日推荐「为你选了 N 首」行的下载按钮已按用户要求去除（批量下载走多选）。
                            showDownload = false,
                            onDownload = { onDownloadBatch(displaySongs) },
                            onSortClick = { showDesignSortMenu = !showDesignSortMenu },
                            onSortPick = { mode ->
                                // ACCEPT5（退出动画普查 L3）：选排序只落模式+落盘；菜单退场走完后
                                // 由 onSortDismiss 清 showDesignSortMenu（此前同步清旗标、Popup 即刻卸载无退场）。
                                sortMode = mode
                                runCatching {
                                    ContextPreferences.open(libCtx, "soundwave_sort", android.content.Context.MODE_PRIVATE, libraryOwner)
                                        .edit().putString("mode_${detail.id}", mode).apply()
                                }
                            },
                            onSortDismiss = { showDesignSortMenu = false },
                            onEnterSelect = { multiMode = true; selectedKeys = emptySet(); batchSnap = emptyList() },
                            onToggleAll = {
                                if (allSelected) {
                                    selectedKeys = emptySet(); batchSnap = emptyList()
                                } else {
                                    selectedKeys = displaySongs.map { it.key() }.toSet()
                                    batchSnap = displaySongs.toList()
                                }
                            },
                            onDone = { multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList() },
                            // r657 第二轮（用户）：按钮行→曲目表头间距收紧（10→4dp）。
                            topPadding = if (pinnedCopy) 2.dp else if (isTodayRec) 16.dp else if (normalizedDetailStyle == DetailCoverStyles.DEFAULT) 16.dp else 4.dp,
                            leadingTools = { if (isTodayRec) xyz.cncnlp.soundwave.ui.today.TodayHeaderExtraActions(detail.source, displaySongs) }
                        )
                        // r657 第二轮：磨砂背景开时不画分隔线（钉住时也不画，由顶栏面负责）。
                        if (standardDetailStyle && !isTodayRec && !pinnedCopy && !xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(MaterialTheme.colorScheme.outline)))
                        }
                }
                Box(Modifier.fillMaxSize()) {
                if (designDetail && !designSearching) DetailCollapsedChrome(
                    state = topChrome, glass = headerGlass, title = detailTitle,
                    playEnabled = displaySongs.isNotEmpty(),
                    onPlayAll = { displaySongs.firstOrNull()?.let { onPlay(it, displaySongs) } },
                    onBack = onBack,
                    immersive = immersiveHero,
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(1f)
                ) { detailToolbar(true) }
                if (immersiveHero && !designSearching) {
                    // r658c U #1/#3：浮动磨砂圆钮随滚动连续淡出；背后真模糊取自详情列表（兄弟节点 headerGlass）。
                    ImmersiveDetailChrome(
                        topbarOn = topChrome.topbarOn, onBack = onBack, onMore = { showDesignDetailMenu = true },
                        onStatusBarAppearance = onDetailStatusBarAppearance,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(0.9f),
                        heroProgress = topChrome.heroProgress, glass = headerGlass,
                        // r658f：浮动栏标题与默认模式页头同文案（歌单详情 / 专辑详情 …），样式同 TodayDesignHeader。
                        title = when {
                            detailHeaderTitle.isNotBlank() -> detailHeaderTitle
                            isTodayRec -> "今日推荐"
                            detail.id == "liked" -> "我的喜欢"
                            detail.id == "local" -> "本地歌单"
                            designKind == DetailDesignKind.Chart -> "榜单详情"
                            designKind == DetailDesignKind.Album -> "专辑详情"
                            detail.id.startsWith("singer_") -> "歌手详情"
                            else -> "歌单详情"
                        }, onSearch = { showPlSearch = true }
                    )
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalRowListState provides listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(if (designDetail) Modifier.then(xyz.cncnlp.soundwave.ui.common.localGlassSourceModifier(headerGlass)) else Modifier),
                    contentPadding = PaddingValues(
                        top = if (designDetail) 0.dp else if (immersiveCover) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 42.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    // r656：详情页封面与顶栏间距 = 首页今日推荐顶距（DetailHeroTopGap）。
                    if (designDetail && (!immersiveHero || designSearching)) item(key = "detail_page_header_${detail.id}", contentType = "detail_header") {
                        TodayDesignHeader(
                            onBack = onBack,
                            onSearch = { showPlSearch = true },
                            // 歌单详情（甄选歌单 / 歌单广场）去掉左上返回键，返回依靠系统手势。
                            // 今日推荐 / 我的喜欢 / 本地歌单 / 新碟首发 均去掉左上返回箭头，返回依靠系统手势。
                            // r657 第三轮：用户 2026-10-18 08:11 要求详情页顶部恢复返回按钮（覆盖此前「无返回箭头」约束）：
                            // 原型展开态 = 左 42dp 圆形返回 + 31sp 衬线标题 + 右侧 搜索 / 更多。
                            showBack = false, // r658d（用户 10-20）：顶部左侧返回箭头去除；上滑后的折叠栏仍保留返回
                            searchField = if (showPlSearch) ({
                                DetailCompactSearchField(
                                    value = plSearch, onValueChange = { plSearch = it },
                                    modifier = Modifier.weight(1f), fieldModifier = Modifier.focusRequester(searchFocusRequester)
                                )
                            }) else null,
                            onCancelSearch = { showPlSearch = false; plSearch = "" },
                            loggedIn = loggedIn,
                            avatarPath = avatarPath,
                            onLogin = onLogin,
                            // r657 第二轮：原型详情页头右侧 = 搜索 + 更多（今日推荐保留用户要求的头像）。
                            onMore = if (isTodayRec) null else ({ showDesignDetailMenu = true }),
                            title = when {
                                detailHeaderTitle.isNotBlank() -> detailHeaderTitle
                                isTodayRec -> "今日推荐"
                                detail.id == "liked" -> "我的喜欢"
                                detail.id == "local" -> "本地歌单"
                                designKind == DetailDesignKind.Chart -> "榜单详情"
                                designKind == DetailDesignKind.Album -> "专辑详情"
                                detail.id.startsWith("singer_") -> "歌手详情"
                                else -> "歌单详情"
                            }
                        )
                    }
                    if (designDetail && !immersiveHero) item(key = "today_header_gap_${detail.id}") { Spacer(Modifier.height(DetailHeroTopGap)) }
                    if (!designSearching) item(key = "detail_hero_${detail.id}") {
                        // 只给真实封面提供 key；不缩放标题、元数据或整块 hero。
                        androidx.compose.runtime.CompositionLocalProvider(xyz.cncnlp.soundwave.ui.motion.LocalSharedCoverKey provides detail.id) {
                        // r658f（用户 10-20，1000073708）：今日推荐默认样式与歌单详情默认样式一致（大圆角封面卡 + 标题 + 按钮），
                        // 不再用单独的 TodayRecommendationHero；沉浸样式同歌单沉浸封面。
                        if (isTodayRec && !immersiveHero) {
                            TodayRecommendationHero(
                                songs = displaySongs,
                                songCount = detailSongCount,
                                cover = detailCover,
                                coverModel = detailCoverModel,
                                favorited = isPlaylistFavorited(detail),
                                onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                onPlayAll = { displaySongs.firstOrNull()?.let { onPlay(it, displaySongs) } },
                                onAddAll = { onAddBatch(displaySongs) },
                                onAddNextAll = { displaySongs.forEach { onAddNext(it) } },
                                onDownloadAll = { onDownloadBatch(displaySongs) },
                                onMore = { showDesignDetailMenu = true },
                                onShuffle = { val shuffled = displaySongs.shuffled(); shuffled.firstOrNull()?.let { onPlay(it, shuffled) } }
                            )
                        } else if (designDetail) {
                            val playAll = { displaySongs.firstOrNull()?.let { onPlay(it, displaySongs) }; Unit }
                            // r657（v3 #1）：所有详情页封面下方 = 播放全部 + 随机播放（+ 收藏）。
                            val shuffleAll = { val shuffled = displaySongs.shuffled(); shuffled.firstOrNull()?.let { onPlay(it, shuffled) }; Unit }
                            // r658c #6：歌手页不再提供收藏，改为随机播放。
                            val canFavorite = !isSingerDetail && (showPlaylistFavorite || detail.id !in setOf("liked", "local", "history"))
                            if (immersiveHero) ImmersiveDetailHero(
                                title = if (isTodayRec) "今日推荐" else detailTitle,
                                subtitle = if (isSingerDetail || isTodayRec) "" else detailAuthor.ifBlank { if (designKind == DetailDesignKind.Album) detailSongs.firstOrNull()?.singer.orEmpty() else "" },
                                meta = listOf(
                                    if (detailLoading && detailSongCount == 0) "正在获取…" else "$detailSongCount 首",
                                    if (isSingerDetail) detailTotalDurationLabel(detailSongs) else "",
                                    detailProvenance
                                ),
                                cover = if (detailCoverLoadFailed) "" else detailCover,
                                coverModel = detailCoverModel,
                                tint = coverTint,
                                favorited = isPlaylistFavorited(detail),
                                showFavorite = canFavorite,
                                playEnabled = displaySongs.isNotEmpty(),
                                onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                onPlayAll = playAll,
                                onDownload = if (displaySongs.isNotEmpty()) ({ onDownloadBatch(displaySongs) }) else null,
                                onMore = { showDesignDetailMenu = true },
                                onShuffle = if (isSingerDetail || detail.id in setOf("liked", "local")) shuffleAll else null,
                                actionTopGap = if (isSingerDetail) 28.dp else 18.dp
                            ) else if (standardDetailStyle) StandardDetailHero(
                                title = detailTitle,
                                eyebrow = listOf(
                                    when {
                                        isSingerDetail -> "Artist"
                                        designKind == DetailDesignKind.Chart -> "Chart"
                                        designKind == DetailDesignKind.Album -> "Album"
                                        else -> "Playlist"
                                    },
                                    if (isSingerDetail) "" else detailAuthor.ifBlank { detailSongs.firstOrNull()?.singer.orEmpty() },
                                    detailProvenance
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                cover = if (detailCoverLoadFailed) "" else detailCover,
                                coverModel = detailCoverModel,
                                favorited = isPlaylistFavorited(detail),
                                showFavorite = canFavorite,
                                playEnabled = displaySongs.isNotEmpty(),
                                onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                onPlayAll = playAll,
                                onShuffle = shuffleAll
                            ) else when (designKind) {
                                // r657 第三轮 b（用户：排行榜详情头卡与设计稿不符）：v5 pDetail(kind=chart) 与歌单同用 396dp 全幅封面英雄卡——
                                // 左上「✦ TOP CHART」、眉题「Top chart · 平台 · 每天更新」、衬线榜名、「共 N 首」；不再是旧的深蓝装饰横幅 + 独立大标题。
                                DetailDesignKind.Chart -> PlaylistDesignHero(
                                    title = detailTitle,
                                    eyebrow = listOf("Top chart", detailProvenance, "每天更新").filter { it.isNotBlank() }.joinToString(" · "),
                                    meta = listOf(
                                        if (detailLoading && detailSongCount == 0) "正在获取…" else "共 $detailSongCount 首",
                                        detailAuthor
                                    ).filter { it.isNotBlank() },
                                    cover = if (detailCoverLoadFailed) "" else detailCover,
                                    coverModel = detailCoverModel,
                                    favorited = isPlaylistFavorited(detail),
                                    showFavorite = canFavorite,
                                    playEnabled = displaySongs.isNotEmpty(),
                                    onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                    onPlayAll = playAll,
                                    onMore = { showDesignDetailMenu = true },
                                    onShuffle = shuffleAll,
                                    onDownload = { onDownloadBatch(displaySongs) }
                                )
                                // 1.0.655：「默认」样式下专辑也使用默认深色卡片页头（此前方形封面 + 大标题与「标准」样式几乎一致，被误认为标准样式）。
                                DetailDesignKind.Album -> PlaylistDesignHero(
                                    title = detailTitle,
                                    eyebrow = listOf("Album", detailAuthor.ifBlank { detailSongs.firstOrNull()?.singer.orEmpty() }).filter { it.isNotBlank() }.joinToString(" · "),
                                    meta = listOf(
                                        if (detailLoading && detailSongCount == 0) "正在获取…" else "$detailSongCount tracks",
                                        detailTotalDurationLabel(detailSongs),
                                        detailProvenance
                                    ).filter { it.isNotBlank() },
                                    cover = if (detailCoverLoadFailed) "" else detailCover,
                                    coverModel = detailCoverModel,
                                    favorited = isPlaylistFavorited(detail),
                                    showFavorite = canFavorite,
                                    playEnabled = displaySongs.isNotEmpty(),
                                    onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                    onPlayAll = playAll,
                                    onMore = { showDesignDetailMenu = true },
                                    onShuffle = shuffleAll,
                                    onDownload = { onDownloadBatch(displaySongs) }
                                )
                                DetailDesignKind.Playlist -> PlaylistDesignHero(
                                    title = detailTitle,
                                    eyebrow = when {
                                        detail.id == "liked" -> "My favorites · 我的喜欢"
                                        detail.id == "history" -> "Recently played"
                                        detail.id == "local" || (detail.local && detail.remoteId.isBlank()) -> "Local playlist · 本地"
                                        detail.id.startsWith("singer_") -> listOf("Artist", detailProvenance).filter { it.isNotBlank() }.joinToString(" · ")
                                        else -> listOf("Playlist", detailAuthor.ifBlank { detailProvenance }).filter { it.isNotBlank() }.joinToString(" · ")
                                    },
                                    meta = listOf(
                                        if (detailLoading && detailSongCount == 0) "正在获取…" else "$detailSongCount tracks",
                                        detailTotalDurationLabel(detailSongs),
                                        detailProvenance
                                    ).filter { it.isNotBlank() },
                                    cover = if (detailCoverLoadFailed) "" else detailCover,
                                    coverModel = detailCoverModel,
                                    favorited = isPlaylistFavorited(detail),
                                    showFavorite = canFavorite,
                                    playEnabled = displaySongs.isNotEmpty(),
                                    onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                    onPlayAll = playAll,
                                    onMore = { showDesignDetailMenu = true },
                                    onShuffle = shuffleAll,
                                    onDownload = { onDownloadBatch(displaySongs) }
                                )
                            }
                        } else {
                        Column(
                            Modifier.fillMaxWidth().then(
                                if (editorialDetailStyle) Modifier.padding(horizontal = 14.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF171A17))
                                else Modifier
                            )
                        ) {
                            if (immersiveCover) {
                                Box(Modifier.fillMaxWidth().height(immersiveHeroHeight)) {
                                    if (detailCover.isNotEmpty() && !detailCoverLoadFailed) {
                                        AsyncImage(
                                            model = detailHeroImageRequest ?: detailCoverModel,
                                            contentDescription = "$detailTitle 封面",
                                            modifier = Modifier.fillMaxSize().fadeArtworkToBackdrop(
                                                detailHeroFadeFraction ?: IMMERSIVE_HERO_FADE_FRACTION
                                            ),
                                            contentScale = ContentScale.Crop,
                                            onError = { detailCoverLoadFailed = true }
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(coverTint, primary)))) {
                                            // ACCEPT5 收编：无封面态的音符图标换黑胶母题（渐变是该样式的 Hero 面，保留）。
                                            VinylDiscMotif(Modifier.align(Alignment.Center).size(120.dp))
                                        }
                                    }
                                    Column(
                                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 0.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            detailTitle,
                                            color = detailTextColor,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Clip,
                                            lineHeight = 30.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().loopMarquee()
                                        )
                                        val immersiveMeta = listOfNotNull(
                                            detailProvenance.takeIf { it.isNotBlank() },
                                            detailDescription.takeIf { it.isNotBlank() },
                                            detailAuthor.takeIf { it.isNotBlank() },
                                            if (detailLoading && detailSongCount == 0) "正在获取…" else "$detailSongCount 首"
                                        )
                                        if (immersiveMeta.isNotEmpty()) {
                                            Text(
                                                immersiveMeta.joinToString("  ·  "),
                                                color = detailTextColor,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth().padding(top = 7.dp).loopMarquee()
                                            )
                                        }
                                        DetailCoverActions(
                                            songs = displaySongs,
                                            favorited = isPlaylistFavorited(detail),
                                            onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                            onPlay = onPlay,
                                            buttonBackground = playActionBackground,
                                            buttonForeground = playActionForeground,
                                            secondaryBackground = secondaryActionBackground,
                                            secondaryForeground = secondaryActionForeground,
                                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                                        )
                                    }
                                }
                            } else {
                            Box(Modifier.fillMaxWidth().height(heroCoverHeight)) {
                                Box(
                                    Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(heroCoverSize)
                                        .then(if (default2Cover) Modifier.clip(RoundedCornerShape(14.dp)) else Modifier.shadow(7.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)))
                                        .background(Brush.linearGradient(if (adaptiveDetailStyle) listOf(coverTint, detailSurfaceTone) else listOf(coverTint.copy(alpha = 0.82f), primary.copy(alpha = 0.78f))))
                                        .then(detailHeroFadeFraction?.let { Modifier.fadeArtworkToBackdrop(it) } ?: Modifier),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (detailCover.isNotEmpty() && !detailCoverLoadFailed) {
                                        AsyncImage(
                                            model = detailCoverModel,
                                            contentDescription = "$detailTitle 封面",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            onError = { detailCoverLoadFailed = true }
                                        )
                                    } else {
                                        // ACCEPT5 收编：无封面态的音符图标换黑胶母题（盒底渐变是该样式的 Hero 面，保留）。
                                        VinylDiscMotif(Modifier.size(heroCoverSize * 0.62f))
                                    }
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth().padding(
                                    top = if (default2Cover) 12.dp else if (editorialDetailStyle) 12.dp else 5.dp,
                                    start = if (editorialDetailStyle) 16.dp else 18.dp,
                                    end = if (editorialDetailStyle) 16.dp else 18.dp,
                                    bottom = if (editorialDetailStyle) 4.dp else 0.dp
                                ),
                                horizontalAlignment = if (editorialDetailStyle) Alignment.Start else Alignment.CenterHorizontally
                            ) {
                                Text(
                                    detailTitle,
                                    color = if (editorialDetailStyle) Color(0xFFF4F3EB) else detailHeaderTint,
                                    fontSize = if (editorialDetailStyle) 38.sp else 20.sp,
                                    lineHeight = if (editorialDetailStyle) 39.sp else 26.sp,
                                    fontFamily = if (editorialDetailStyle) androidx.compose.ui.text.font.FontFamily.Serif else androidx.compose.ui.text.font.FontFamily.Default,
                                    fontWeight = if (editorialDetailStyle) FontWeight.Medium else FontWeight.Bold,
                                    textAlign = if (editorialDetailStyle) TextAlign.Start else TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().loopMarquee()
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (editorialDetailStyle) Arrangement.Start else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    val metadata = listOfNotNull(
                                        detailProvenance.takeIf { it.isNotBlank() },
                                        detailDescription.takeIf { default2Cover && it.isNotBlank() }?.let { value ->
                                            if (value.length > 32) value.take(31) + "…" else value
                                        },
                                        detailAuthor.takeIf { it.isNotBlank() },
                                        if (detailLoading && detailSongCount == 0) "正在获取…" else "$detailSongCount 首"
                                    )
                                    if (default2Cover) {
                                        Text(
                                            metadata.joinToString("  ·  "),
                                            color = detailTextColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        metadata.forEachIndexed { index, value ->
                                            if (index > 0) Text("·", color = detailTextColor.copy(alpha = 0.56f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                                            Text(
                                                value,
                                                color = if (editorialDetailStyle) Color.White.copy(alpha = 0.66f) else detailTextColor,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = when (value) {
                                                    detailAuthor -> Modifier.widthIn(max = 130.dp)
                                                    detailProvenance -> Modifier.widthIn(max = 100.dp)
                                                    else -> Modifier
                                                }
                                            )
                                        }
                                    }
                                }
                                if (default2Cover) DetailCoverActions(
                                    songs = displaySongs,
                                    favorited = isPlaylistFavorited(detail),
                                    onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                    onPlay = onPlay,
                                    buttonBackground = playActionBackground,
                                    buttonForeground = playActionForeground,
                                    secondaryBackground = secondaryActionBackground,
                                    secondaryForeground = secondaryActionForeground,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                )
                                if (!default2Cover) {
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        Modifier.fillMaxWidth().height(if (editorialDetailStyle) 42.dp else 44.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val buttonShape = RoundedCornerShape(24.dp)
                                        val actionTint = Modifier.shadow(7.dp, buttonShape).clip(buttonShape).background(actionPrimary)
                                        val sequenceColor = if (editorialDetailStyle) Color.White.copy(alpha = 0.82f) else pageColors.onSurfaceVariant
                                        val shuffleTint = Modifier.shadow(7.dp, buttonShape).clip(buttonShape).background(if (editorialDetailStyle) Color.White.copy(alpha = 0.12f) else pageColors.surfaceVariant)
                                        Row(
                                            Modifier.weight(1f).fillMaxHeight().then(shuffleTint)
                                                .clickable(enabled = displaySongs.isNotEmpty()) {
                                                    val shuffled = displaySongs.shuffled()
                                                    shuffled.firstOrNull()?.let { onPlay(it, shuffled) }
                                                },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Filled.Shuffle, "随机播放", tint = sequenceColor, modifier = Modifier.size(19.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("随机播放", color = sequenceColor, fontSize = if (editorialDetailStyle) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        Row(
                                            Modifier.weight(1f).fillMaxHeight().then(actionTint)
                                                .clickable(enabled = displaySongs.isNotEmpty()) { displaySongs.firstOrNull()?.let { onPlay(it, displaySongs) } },
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.PlaySolid, "播放全部", tint = pageColors.onPrimary, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("播放全部", color = pageColors.onPrimary, fontSize = if (editorialDetailStyle) 12.sp else 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            }
                            Spacer(Modifier.height(if (editorialDetailStyle) 18.dp else 20.dp))
                        }
                        }
                        }
                    }
                    if (detailDescription.isNotEmpty() && !editorialDetailStyle && !isTodayRec) item(key = "detail_info_${detail.id}") {
                            var plDescExpand by remember(detail.id) { mutableStateOf(false) }
                            var descriptionHasMore by remember(detail.id, detailDescription) { mutableStateOf(false) }
                            Column(
                                Modifier.fillMaxWidth().clickable { plDescExpand = !plDescExpand }
                                    .padding(start = 18.dp, end = 18.dp, top = 5.dp, bottom = 5.dp)
                            ) {
                                Text(
                                    detailDescription,
                                    color = detailTextColor,
                                    fontSize = 12.sp,
                                    maxLines = if (plDescExpand) Int.MAX_VALUE else 2,
                                    overflow = if (plDescExpand) TextOverflow.Clip else TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                    onTextLayout = { if (it.hasVisualOverflow) descriptionHasMore = true }
                                )
                                if (!plDescExpand && (descriptionHasMore || detailDescription.length > 60)) {
                                    Text("展开", color = if (adaptiveDetailStyle) detailAdaptiveForeground else primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 2.dp))
                                } else if (plDescExpand && (descriptionHasMore || detailDescription.length > 60)) {
                                    Text("收起", color = if (adaptiveDetailStyle) detailAdaptiveForeground else primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                    }
                    if (designDetail && !designSearching && isSingerDetail) item(key = "singer_albums_${detail.id}") {
                        SingerAlbumsRow(singerAlbums, onOpenSingerAlbum, singerAlbumLoading, singerAlbumHasMore, singerAlbumError) {
                            if (!singerAlbumLoading) singerAlbumRequest++
                        }
                    }
                    if (designDetail && !designSearching) item(key = detailToolbarKey, contentType = "detail_toolbar") { Column(Modifier.hideWhenPinned(topChrome)) { detailToolbar(false) } }
                    else if (designSearching) item(key = "detail_tracks_header_${detail.id}") {
                        // 1.0.655：设计稿详情搜索态「曲目列表」右侧不再显示收藏 / 排序 / 多选 / 批量按钮。
                        Text(
                            "曲目列表", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, letterSpacing = (-0.02).em, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)
                        )
                    } else item(key = "detail_tracks_header_${detail.id}") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("曲目列表", color = detailTextColor, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            if (multiMode) {
                                Text("已选 $selectedCount 首", color = if (adaptiveDetailStyle) detailAdaptiveForeground else primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            if (showPlaylistFavorite && !immersiveCover && !default2Cover) {
                                Box(Modifier.size(34.dp).clip(CircleShape).clickable { onTogglePlaylistFavorite(detail) }, contentAlignment = Alignment.Center) {
                                    RoundedStar(filled = isPlaylistFavorited(detail), color = detailActionTint, modifier = Modifier.size(20.dp))
                                }
                            } else Spacer(Modifier.size(34.dp))
                            Box(Modifier.size(34.dp).clickable { showSortSheet = true }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.SwapVert, "排序", tint = toolTint, modifier = Modifier.size(21.dp))
                            }
                            Box(
                                Modifier.size(34.dp).clickable {
                                    val allSelected = displaySongs.isNotEmpty() && selectedKeys.size == displaySongs.size
                                    if (allSelected) {
                                        multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList()
                                    } else {
                                        multiMode = true
                                        selectedKeys = displaySongs.map { it.key() }.toSet()
                                        batchSnap = displaySongs.toList()
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (displaySongs.isNotEmpty() && selectedKeys.size == displaySongs.size) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                    "多选",
                                    tint = toolTint,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Box(
                                Modifier.size(34.dp).clickable(enabled = multiMode && selectedKeys.isNotEmpty()) { showPlaylistSettings = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Tune, "批量操作", tint = toolTint.copy(alpha = if (multiMode && selectedKeys.isNotEmpty()) 1f else 0.45f), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    // Keep the existing hero/player spacing intact; this is a separate list item.
                    if (!showPlSearch && !multiMode && !resumeDismissed && resumeSong != null) {
                        item(key = "detail_resume_${detail.id}", contentType = "detail_resume") {
                            DetailResumeBar(
                                song = resumeSong,
                                foreground = detailTextColor,
                                onResume = {
                                    // Revalidate membership at click time; never fall back to another song.
                                    DetailResumePolicy.candidate(displaySongs, history)
                                        ?.takeIf { it.key() == resumeSong.key() }
                                        ?.let { onResumeDetail(it, displaySongs) }
                                },
                                onDismiss = { resumeDismissed = true }
                            )
                        }
                    }
                    if (displaySongs.isEmpty()) item(key = "detail_empty_${detail.id}") {
                        when {
                            detail.id == "local" && detailSongs.isEmpty() && plSearch.isEmpty() -> LocalScanDesignCard(
                                status = localScanStatus, running = localScanRunning, folders = localFolderLabels,
                                onScan = onScanLocal, onAddFolder = onAddLocalFolder
                            )
                            plSearch.isNotEmpty() -> SpaceCompressedStateCard(
                                title = "没有匹配的歌曲",
                                message = "清除筛选后查看完整歌单。",
                                status = SpaceCompressedState.Empty,
                                actionLabel = "清除筛选",
                                onAction = { plSearch = "" }
                            )
                            detailLoading -> Column {
                                // E4（验收二轮，用户点名详情页加载态）：原为页中一张静态状态卡
                                // （三段节奏条完全静止、与曲目列表不同构）。改与详情曲目行同构的
                                // 骨架行（56dp 封面位，与真实曲目行同尺寸 + 双行线条，SkeletonKit 暖灰 + 珊瑚系 shimmer），
                                // 仍在头图/简历条之下、曲目区原位呈现。
                                // ACCEPT4（v8 稿 §5）：骨架之上加旋转唱片 + 音柱加载胶囊，文案随详情类型（稿 V8LOAD）。
                                xyz.cncnlp.soundwave.ui.common.LoadingPill(
                                    label = when {
                                        detail.id == "local" -> "正在扫描本地歌单"
                                        designKind == DetailDesignKind.Album -> "正在载入专辑"
                                        else -> "正在载入歌单"
                                    },
                                    modifier = Modifier.padding(start = 14.dp, bottom = 16.dp)
                                )
                                xyz.cncnlp.soundwave.ui.common.SkeletonRows(count = 6, coverSize = 56.dp)
                            }
                            detailLoadFailed -> SpaceCompressedStateCard(
                                title = "加载失败",
                                message = "没能拉回这个歌单的曲目，检查网络后重试。",
                                status = SpaceCompressedState.Error,
                                actionLabel = "重试",
                                onAction = onRetryDetail
                            )
                            else -> SpaceCompressedStateCard(
                                title = "当前没有可显示的歌曲",
                                message = "歌单曲目会显示在这里。",
                                status = SpaceCompressedState.Empty
                            )
                        }
                    } else if (designDetail && designKind == DetailDesignKind.Chart && !isTodayRec) itemsIndexed(displaySongs, key = { _, song -> song.key() }, contentType = { _, _ -> "chart_track" }) { idx, song ->
                        val sel = song.key() in selectedKeys
                        // SW-12-12：详情曲目增删平滑让位 / 淡入（key = Song.key()，数据稳定 id）。
                        // ACCEPT4：首批到达行叠错峰入场（门按 detail.id 管已播序号）。
                        Box(swAnimateItem(listReduceMotion).staggerEnter(idx, detailStaggerGate)) {
                        ChartDetailTrackRow(
                            song = song,
                            rank = idx + 1,
                            nowPlaying = song.key() == currentKey,
                            isPlayingNow = playingNow && song.key() == currentKey,
                            multiMode = multiMode,
                            selected = sel,
                            liked = isLiked(song),
                            showDownload = true,
                            onLike = { onLike(song) },
                            onAdd = { onAdd(song) },
                            onAddNext = { onAddNext(song) },
                            onDownload = { onDownload(song) },
                            onDelete = if (allowDelete) ({ onDeleteSong(song, false) }) else null,
                            onDislike = { onDislikeSong(song) },
                            onSimilar = { onSimilarSong(song) },
                            onLongClick = {
                                multiMode = true
                                selectedKeys = selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (selectedKeys + song.key()) }
                            },
                            // 顶部搜索态：曲目行右侧按钮去除。
                            showTrailing = !designSearching
                        ) {
                            if (multiMode) {
                                selectedKeys = if (sel) selectedKeys - song.key() else selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (if (sel) selectedKeys - song.key() else selectedKeys + song.key()) }
                            } else onPlay(song, displaySongs)
                        }
                        }
                    } else if (designDetail) itemsIndexed(displaySongs, key = { _, song -> song.key() }, contentType = { _, _ -> "track" }) { idx, song ->
                        val sel = song.key() in selectedKeys
                        // SW-12-12：详情曲目增删平滑让位 / 淡入（key = Song.key()，数据稳定 id）。
                        // ACCEPT4：首批到达行叠错峰入场（门按 detail.id 管已播序号）。
                        Box(swAnimateItem(listReduceMotion).staggerEnter(idx, detailStaggerGate)) {
                        TodayTrackRow(
                            // r657 第三轮（用户 10-18 再次确认）：所有详情列表（歌单 / 专辑 / 今日推荐 / 我的喜欢 / 沉浸样式）一律不显示曲目编号。
                            showIndex = DetailTrackListShowsIndex,
                            showCover = designKind != DetailDesignKind.Album,
                            showDuration = true,
                            song = song,
                            index = idx + 1,
                            nowPlaying = song.key() == currentKey,
                            isPlayingNow = playingNow && song.key() == currentKey,
                            multiMode = multiMode,
                            selected = sel,
                            liked = isLiked(song),
                            showDownload = isTodayRec || (detail.id != "local" && !detail.local),
                            onLike = { onLike(song) },
                            onAdd = { onAdd(song) },
                            onAddNext = { onAddNext(song) },
                            onDownload = { onDownload(song) },
                            onDelete = if (allowDelete && !isTodayRec) ({ onDeleteSong(song, false) }) else null,
                            onDislike = { onDislikeSong(song) },
                            onSimilar = { onSimilarSong(song) },
                            onLongClick = {
                                multiMode = true
                                selectedKeys = selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (selectedKeys + song.key()) }
                            },
                            // 顶部搜索态：曲目行右侧按钮去除。
                            showTrailing = !designSearching
                        ) {
                            if (multiMode) {
                                selectedKeys = if (sel) selectedKeys - song.key() else selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (if (sel) selectedKeys - song.key() else selectedKeys + song.key()) }
                            } else onPlay(song, displaySongs)
                        }
                        }
                    } else itemsIndexed(displaySongs, key = { _, song -> song.key() }, contentType = { _, _ -> "song_row" }) { idx, song ->
                        val sel = song.key() in selectedKeys
                        // SW-12-12：详情曲目增删平滑让位 / 淡入（key = Song.key()，数据稳定 id）。
                        Box(swAnimateItem(listReduceMotion)) {
                        SongRow(
                            song,
                            index = idx + 1,
                            showIndex = DetailTrackListShowsIndex, // r657 第三轮：详情列表无曲目编号
                            platformSource = "",
                            showActions = !multiMode,
                            showLike = false,
                            flat = true,
                            compact = false,
                            tight = true,
                            flatOuterHorizontal = 0.dp,
                            flatContentHorizontal = 14.dp,
                            detailIndexOffset = 21.dp,
                            detailMoreOffset = 13.dp,
                            detailForeground = if (adaptiveDetailStyle) detailAdaptiveForeground else null,
                            showDownload = detail.id != "local" && !detail.local,
                            liked = isLiked(song),
                            selected = sel,
                            multiMode = multiMode,
                            onLike = { onLike(song) },
                            onAdd = { onAdd(song) },
                            onDownload = { onDownload(song) },
                            onDelete = if (allowDelete) ({ onDeleteSong(song, false) }) else null,
                            onRescan = null,
                            onAddNext = { onAddNext(song) },
                            onDislike = { onDislikeSong(song) },
                            onSimilar = { onSimilarSong(song) },
                            dissolveOnUnlike = detail.id == "liked",
                            // r673（用户 10-22）：歌手页、数字专辑 / 新碟首发（有页头标题覆盖）、榜单详情的歌曲封面加应用封面阴影。
                            coverShadow = isSingerDetail || detailHeaderTitle.isNotBlank() || designKind == DetailDesignKind.Chart,
                            nowPlaying = song.key() == currentKey,
                            isPlayingNow = playingNow && song.key() == currentKey,
                            onLongClick = {
                                multiMode = true
                                selectedKeys = selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (selectedKeys + song.key()) }
                            }
                        ) {
                            if (multiMode) {
                                selectedKeys = if (sel) selectedKeys - song.key() else selectedKeys + song.key()
                                batchSnap = displaySongs.filter { it.key() in (if (sel) selectedKeys - song.key() else selectedKeys + song.key()) }
                            } else onPlay(song, displaySongs)
                        }
                        }
                    }
                    // 底部留出迷你条 + 底栏宿主（148dp）高度，最后一首不再被迷你条遮挡。
                    item(key = "detail_bottom_space_${detail.id}") { Spacer(Modifier.height(dockContentBottomPadding())) }
                }
                } // LocalRowListState
                } // 固定顶栏 Box（顶栏叠在列表上）
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                            .background(
                                if (adaptiveDetailStyle) Brush.verticalGradient(listOf(
                                    Color.Transparent,
                                    detailSurfaceTone.copy(alpha = 0.10f),
                                    detailSurfaceTone.copy(alpha = 0.14f)
                                )) else Brush.verticalGradient(listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                    MaterialTheme.colorScheme.background
                                ))
                            )
                    )
                if (showSortSheet && !designDetail) {
                    ChoiceSheet(
                        title = "排序方式",
                        opts = listOf("default" to "默认（最新在前）", "old" to "最早添加", "name" to "歌名", "singer" to "歌手"),
                        current = sortMode,
                        onPick = { mode ->
                            sortMode = mode
                            runCatching {
                                ContextPreferences.open(libCtx, "soundwave_sort", android.content.Context.MODE_PRIVATE, libraryOwner)
                                    .edit().putString("mode_${detail.id}", mode).apply()
                            }
                        },
                        onDismiss = { showSortSheet = false }
                    )
                }
                if (showPlaylistSettings && multiMode && selectedCount > 0) {
                    val pickedSongs = (if (batchSnap.isNotEmpty()) batchSnap.filter { it.key() in selectedKeys } else displaySongs.filter { it.key() in selectedKeys }).toList()
                    val batchActions = buildList {
                        add("like_sel" to if (detail.id == "liked") "取消喜欢" else "添加到喜欢")
                        add("next" to "下一首播放")
                        add("add_sel" to "添加到歌单")
                        if (detail.id != "local") add("download_sel" to "下载歌曲")
                        if (allowDelete) add("remove_sel" to "从歌单删除")
                    }
                    // ACCEPT4（审计 §9）：ChoiceSheet 的 onPick 在退场动画之前执行——原接线在 onPick 里同步清
                    // showPlaylistSettings/选集会立刻卸载弹层、掐断退场。改为：onPick 只执行动作并记下已选项，
                    // 状态清除全部推迟到退场完成后的 onDismiss（且仅在确有选项时清选集，点遮罩关闭不清）。
                    var batchPicked by remember { mutableStateOf(false) }
                    ChoiceSheet(
                        title = "批量操作",
                        opts = batchActions,
                        current = "",
                        onPick = { key ->
                            batchPicked = true
                            when (key) {
                                "like_sel" -> {
                                    val likeTargets = if (detail.id == "liked") pickedSongs.filter { isLiked(it) } else pickedSongs.filterNot { isLiked(it) }
                                    if (likeTargets.isNotEmpty()) onLikeBatch(likeTargets, detail.id != "liked")
                                }
                                "next" -> pickedSongs.firstOrNull()?.let { song -> onAddNext(song) }
                                "add_sel" -> if (pickedSongs.isNotEmpty()) onAddBatch(pickedSongs)
                                "download_sel" -> if (pickedSongs.isNotEmpty()) onDownloadBatch(pickedSongs)
                                "remove_sel" -> onDeleteSongs(pickedSongs, false)
                            }
                        },
                        onDismiss = {
                            showPlaylistSettings = false
                            if (batchPicked) { selectedKeys = emptySet(); batchSnap = emptyList(); batchPicked = false }
                        }
                    )
                }
            }
                if (designDetail) {
                    val selectionVisible = multiMode
                    SideEffect { onDetailSelectionModeChange(selectionVisible) }
                    DisposableEffect(detail.id) { onDispose { onDetailSelectionModeChange(false) } }
                    if (selectionVisible) {
                        val pickedNow = displaySongs.filter { it.key() in selectedKeys }
                        // 多选操作：加入歌单 / 加入队列 / 下载歌曲（自建歌单额外保留「移除」）。
                        DetailSelectionActionBar(
                            enabled = pickedNow.isNotEmpty(),
                            showDownload = isTodayRec || (detail.id != "local" && !detail.local),
                            showRemove = allowDelete && !isTodayRec,
                            onQueue = {
                                pickedNow.asReversed().forEach { onAddNext(it) }
                                multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList()
                            },
                            onAddTo = {
                                if (pickedNow.isNotEmpty()) onAddBatch(pickedNow)
                                multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList()
                            },
                            onDownload = {
                                if (pickedNow.isNotEmpty()) onDownloadBatch(pickedNow)
                                multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList()
                            },
                            onRemove = {
                                onDeleteSongs(pickedNow, false)
                                multiMode = false; selectedKeys = emptySet(); batchSnap = emptyList()
                            },
                            modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f)
                        )
                    }
                    if (showDesignDetailMenu && isTodayRec) {
                        DesignBottomSheetDialog(onDismiss = { showDesignDetailMenu = false }) {
                            DesignTodayActionSheetContent(
                                cover = detailCover, songCount = detailSongCount, showDownload = true,
                                // ACCEPT4（§9 C 类）：prop 只执行动作，关闭由内容侧退场句柄承担。
                                onPlayAll = { displaySongs.firstOrNull()?.let { onPlay(it, displaySongs) } },
                                onSaveAsPlaylist = { onAddBatch(displaySongs) },
                                onQueueAll = { displaySongs.asReversed().forEach { onAddNext(it) } },
                                onDownloadAll = { onDownloadBatch(displaySongs) }
                            )
                        }
                    } else if (showDesignDetailMenu) {
                        val detailHeartActive = heartActive && activeHeartPlaylistKey == heartPlaylistKey(detail)
                        // SW-09-05（G 波）：榜单/推荐流详情（rank_/hot_/reco_，即 Chart 类）不再一刀切排除心动模式——
                        // 它们与普通在线歌单一样有真实曲目池；空池/不支持来源由 PlaybackController 的兜底提示处理。
                        val canHeartDetail = !detail.local &&
                            detail.id !in setOf("local", "history") && !detail.id.startsWith("singer_")
                        DesignBottomSheetDialog(onDismiss = { showDesignDetailMenu = false }) {
                            DesignPlaylistActionSheetContent(
                                eyebrow = when (designKind) { DetailDesignKind.Chart -> "Chart actions"; DetailDesignKind.Album -> "Album actions"; else -> "Playlist actions" },
                                title = when (designKind) { DetailDesignKind.Chart -> "榜单操作"; DetailDesignKind.Album -> "专辑操作"; else -> "歌单操作" },
                                cover = detailCover,
                                name = detailTitle,
                                subtitle = listOf("$detailSongCount 首", detailTotalDurationLabel(detailSongs).lowercase()).filter { it.isNotBlank() }.joinToString(" · "),
                                favorited = isPlaylistFavorited(detail),
                                showFavorite = showPlaylistFavorite || detail.id !in setOf("liked", "local", "history"),
                                showDownload = detail.id != "local" && !detail.local,
                                onToggleFavorite = { onTogglePlaylistFavorite(detail) },
                                onAddNextAll = { displaySongs.forEach { onAddNext(it) } },
                                onAddAll = { onAddBatch(displaySongs) },
                                onDownloadAll = { onDownloadBatch(displaySongs) },
                                onSearch = { showPlSearch = true },
                                // E14：仅本地歌单——去快捷方块区与「搜索」，竖排末尾加「重新扫描」（既有 onScanLocal 链）。
                                quickTiles = detail.id != "local",
                                onRescan = if (detail.id == "local") ({ onScanLocal() }) else null
                            )
                            if (canHeartDetail) {
                                val dismissDetailMenu = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showDesignDetailMenu = false }
                                DesignSheetOption(
                                    if (detailHeartActive) xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.HeartLoopOff else xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.HeartLoop,
                                    if (detailHeartActive) "关闭心动模式" else "心动模式",
                                    if (detailHeartActive) "恢复原播放顺序" else "智能续播相似歌曲"
                                ) {
                                    onPlaylistHeartMode(detail.copy(songs = detailSongs.toMutableList()))
                                    dismissDetailMenu()
                                }
                            }
                        }
                    }
                }
                // 右下角悬浮层必须是详情根 Box 的 sibling，固定在可见视口，不随列表 Column 流走。
                // 详情操作浮层需同时避开 Dock 和一整条迷你播放器高度。
                val fabBg = pageColors.surfaceVariant
                val fabTint = pageColors.primary
                val fabSurface = Modifier.clip(CircleShape).background(fabBg)
                if (!(designDetail && multiMode)) Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockContentBottomPadding() + 12.dp - DockItemH),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 一键回顶在上、定位当前歌曲在下（位置对调）；回顶出现时从上方叠出，
                    // 定位按钮固定在底部，不随回顶出现/消失晃动。
                    if (listState.rememberScrolledPastTop()) {
                        // 设计稿详情：回顶 / 定位使用首页今日推荐播放键配色（珊瑚底 + 白色图标）。
                        // 所有详情样式统一首页一键回顶样式（表面色圆 + 主色图标 + 投影）。
                        DetailAccentFab(Icons.Outlined.KeyboardArrowUp, "回到顶部", 22.dp) {
                            if (returnTopJob?.isActive != true) returnTopJob = scope.launch { listState.smoothScrollToTop(reduceTopMotion) }
                        }
                    }
                    if (showLocateButton && currentKey.isNotEmpty() && displaySongs.any { it.key() == currentKey }) {
                        val locate = {
                            val i = displaySongs.indexOfFirst { it.key() == currentKey }
                            if (i >= 0) scope.launch { listState.smoothScrollToItem(i) }
                            Unit
                        }
                        DetailAccentFab(Icons.Outlined.MyLocation, "定位当前歌曲", 20.dp, locate)
                    }
                    if (detail.id == "liked") {
                        // 占位：详情页右下角 ⋮ 已移除（功能在歌单页喜欢封面三点弹窗内）
                    }
                }
        } } // IsolatedSection: detail page
        return
    }

    val liked = bundle.playlists.firstOrNull { it.id == "liked" }
    val local = bundle.playlists.firstOrNull { it.id == "local" }
    // 收藏专辑只来自用户在专辑详情中显式收藏的条目（AlbumFavoriteMarks）；
    // 不再从「我的喜欢」或导入歌单曲目的 album 字段推导/计数专辑。
    val albumMarks = remember(bundle.playlists.map { it.id }) { AlbumFavoriteMarks.marks(libCtx) }
    val favoriteAlbums = remember(bundle.playlists, albumMarks) {
        bundle.playlists.filter { it.favorited && it.id in albumMarks }
    }
    // 收藏歌单是本地持久化数据，未登录也必须在底栏歌单页可见、可播放。
    val allImported = bundle.playlists.filter {
        it.id != "liked" && it.id != "local" &&
            !it.id.startsWith("rank_") && !it.id.startsWith("hot_") && !it.id.startsWith("reco_")
    }
    var showPlActions by remember { mutableStateOf(false) }
    // ACCEPT5（用户 2026-11-04）：在线歌单区改双视图——在线歌单 ⇄ 收藏，一页只显其一；
    // 视图选择持久化（默认在线歌单），下次进音乐库停在上次视图。「排序歌单」弹层已删除，
    // 在线歌单改整行长按拖动排序（拖动状态在 orderIds 派生之后）。
    var showFavoritesView by remember {
        mutableStateOf(
            runCatching {
                ContextPreferences.open(libCtx, "soundwave_library", android.content.Context.MODE_PRIVATE, libraryOwner)
                    .getString("section_view_v1", "online") == "favorites"
            }.getOrDefault(false)
        )
    }
    fun setLibraryView(favorites: Boolean) {
        showFavoritesView = favorites
        runCatching {
            ContextPreferences.open(libCtx, "soundwave_library", android.content.Context.MODE_PRIVATE, libraryOwner)
                .edit().putString("section_view_v1", if (favorites) "favorites" else "online").apply()
        }
    }
    // 隐藏歌单功能已移除；旧版本曾隐藏的歌单也必须重新显示，避免数据永久不可见。
    val imported = allImported
    // 每次进入歌单页由上层按来源刷新远程歌单播放量；不在此处本地累加。
    LaunchedEffect(Unit) { onRefreshPlaylistCounts() }
    var menuPl by remember { mutableStateOf<Playlist?>(null) }
    var showLibraryPlatformPicker by remember { mutableStateOf(false) }
    var deletePl by remember { mutableStateOf<Playlist?>(null) }
    var showDeleteSongs by remember { mutableStateOf(false) }
    var delSel by remember { mutableStateOf(setOf<String>()) }
    var renamePl by remember { mutableStateOf<Playlist?>(null) }
    var renameText by remember { mutableStateOf("") }
    val surface = pageColors.surface

    val defaultIds = libraryDefaultPlaylistIds(loggedIn, imported.map { it.id })
    var orderIds by remember(defaultIds.joinToString(), libOrder.joinToString()) {
        mutableStateOf(libraryOrderIds(defaultIds, libOrder))
    }
    // Match the old firstOrNull lookup even if a legacy bundle contains duplicate ids.
    val importedById = imported.asReversed().associateBy { it.id }
    val orderedVisible = orderIds.mapNotNull(importedById::get)
        .filter { it.id !in setOf("import", "newpl") && (platFilter.isEmpty() || it.source == platFilter) }
    val myPlaylists = orderedVisible.filter { !it.favorited }
    // 收藏自有顺序（用户 2026-11-05 纠正口径）：收藏不掺和导入在线/新建两个池子的排序，但收藏
    // 本身内部可排序——顺序由 FavoriteOrderSync 戳记（favPlOrder/favAlbumOrder 入参）决定，
    // 与 orderIds 序带互不读写。无戳记时以「今天的排列」为默认种子：收藏歌单沿用池序带相对序、
    // 收藏专辑沿用 bundle 原序，升级后画面不跳位。
    val favPlDefaultIds = orderIds.mapNotNull(importedById::get)
        .filter { it.favorited && it.id !in albumMarks }.map { it.id }
    var favPlOrderIds by remember(favPlDefaultIds.joinToString(), favPlOrder.joinToString()) {
        mutableStateOf(libraryOrderIds(favPlDefaultIds, favPlOrder))
    }
    val savedPlaylistsAll = favPlOrderIds.mapNotNull(importedById::get)
    val savedPlaylists = savedPlaylistsAll.filter { platFilter.isEmpty() || it.source == platFilter }
    val favAlbumDefaultIds = favoriteAlbums.map { it.id }
    var favAlbumOrderIds by remember(favAlbumDefaultIds.joinToString(), favAlbumOrder.joinToString()) {
        mutableStateOf(libraryOrderIds(favAlbumDefaultIds, favAlbumOrder))
    }
    val favoriteAlbumById = favoriteAlbums.associateBy { it.id }
    val visibleAlbumsAll = favAlbumOrderIds.mapNotNull(favoriteAlbumById::get)
    val visibleAlbums = visibleAlbumsAll.filter { platFilter.isBlank() || it.source == platFilter }

    // ACCEPT5：在线歌单长按拖动——手势期只动「当前可见在线子集」的草稿序（快照），
    // 松手才拼回 orderIds 并落盘。长按区域分工后（用户 2026-11-05），拖动只由封面长按进入，
    // 行身长按直接打开管理抽屉——拖动松手不再回退弹表（原双载耦合就此解开）。
    var dragOnlineIds by remember { mutableStateOf<List<String>?>(null) }
    var dragStartIds by remember { mutableStateOf<List<String>?>(null) }
    var draggingPlId by remember { mutableStateOf<String?>(null) }
    var plDragAcc by remember { mutableStateOf(0f) }
    val onlinePlaylists = dragOnlineIds?.mapNotNull(importedById::get) ?: myPlaylists
    // 行 pointerInput 会捕获创建时的回调（同队列 latestX 范式），列表快照一律读最新值。
    val latestMyPlaylists by rememberUpdatedState(myPlaylists)
    val latestOrderIds by rememberUpdatedState(orderIds)

    val dragHapticView = androidx.compose.ui.platform.LocalView.current
    fun beginOnlineDrag(pl: Playlist) {
        draggingPlId = pl.id
        plDragAcc = 0f
        if (dragOnlineIds == null) {
            val snapshot = latestMyPlaylists.map { it.id }
            dragOnlineIds = snapshot
            dragStartIds = snapshot
        }
    }
    fun advanceOnlineDrag(pl: Playlist, dyPx: Float, pitchPx: Float) {
        val ids = dragOnlineIds ?: return
        val (next, acc) = libraryDragAdvance(ids, pl.id, plDragAcc + dyPx, pitchPx)
        // ACCEPT5-press（稿 §4）：草稿序真的换格才发 drag 触感。
        if (next != ids) xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(dragHapticView)
        dragOnlineIds = next
        plDragAcc = acc
    }
    fun finishOnlineDrag(pl: Playlist, cancelled: Boolean) {
        val draft = dragOnlineIds
        val start = dragStartIds
        draggingPlId = null
        plDragAcc = 0f
        dragOnlineIds = null
        dragStartIds = null
        if (cancelled || draft == null) return
        if (start != null && draft != start) {
            // 松手落盘：新子序拼回完整顺序的原位置——收藏、筛外歌单、liked/local 的
            // 位置与相对序不动（收藏不参与在线排序），落盘走既有 onReorderPlaylists 链。
            val merged = librarySpliceReorder(latestOrderIds, draft.toSet(), draft)
            if (merged != latestOrderIds) {
                orderIds = merged
                onReorderPlaylists(merged.filterNot { it == "liked" || it == "local" })
            }
        }
        // 草稿未变（封面长按未拖动）松手：什么都不发生——操作表已改由行身长按直达。
    }

    // 收藏内部拖动（歌单组 / 专辑组各一台，与在线同一范式）：手势期只动「当前可见子集」草稿序，
    // 松手拼回该组完整自有顺序并经 onReorderFavorites 落盘（FavoriteOrderSync 戳记）；两组与
    // 在线池的顺序互不读写。收藏分组渲染（歌单组在前、专辑组在后）决定拖动只能组内换位。
    var dragFavPlIds by remember { mutableStateOf<List<String>?>(null) }
    var dragFavPlStartIds by remember { mutableStateOf<List<String>?>(null) }
    var draggingFavPlId by remember { mutableStateOf<String?>(null) }
    var favPlDragAcc by remember { mutableStateOf(0f) }
    val favPlaylists = dragFavPlIds?.mapNotNull(importedById::get) ?: savedPlaylists
    val latestSavedPlaylists by rememberUpdatedState(savedPlaylists)
    val latestFavPlOrderIds by rememberUpdatedState(favPlOrderIds)
    fun beginFavPlDrag(pl: Playlist) {
        draggingFavPlId = pl.id
        favPlDragAcc = 0f
        if (dragFavPlIds == null) {
            val snapshot = latestSavedPlaylists.map { it.id }
            dragFavPlIds = snapshot
            dragFavPlStartIds = snapshot
        }
    }
    fun advanceFavPlDrag(pl: Playlist, dyPx: Float, pitchPx: Float) {
        val ids = dragFavPlIds ?: return
        val (next, acc) = libraryDragAdvance(ids, pl.id, favPlDragAcc + dyPx, pitchPx)
        if (next != ids) xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(dragHapticView)
        dragFavPlIds = next
        favPlDragAcc = acc
    }
    fun finishFavPlDrag(pl: Playlist, cancelled: Boolean) {
        val draft = dragFavPlIds
        val start = dragFavPlStartIds
        draggingFavPlId = null
        favPlDragAcc = 0f
        dragFavPlIds = null
        dragFavPlStartIds = null
        if (cancelled || draft == null || start == null || draft == start) return
        val merged = librarySpliceReorder(latestFavPlOrderIds, draft.toSet(), draft)
        if (merged != latestFavPlOrderIds) {
            favPlOrderIds = merged
            onReorderFavorites(merged, false)
        }
    }

    var dragFavAlbumIds by remember { mutableStateOf<List<String>?>(null) }
    var dragFavAlbumStartIds by remember { mutableStateOf<List<String>?>(null) }
    var draggingFavAlbumId by remember { mutableStateOf<String?>(null) }
    var favAlbumDragAcc by remember { mutableStateOf(0f) }
    val favAlbums = dragFavAlbumIds?.mapNotNull(favoriteAlbumById::get) ?: visibleAlbums
    val latestVisibleAlbums by rememberUpdatedState(visibleAlbums)
    val latestFavAlbumOrderIds by rememberUpdatedState(favAlbumOrderIds)
    fun beginFavAlbumDrag(album: Playlist) {
        draggingFavAlbumId = album.id
        favAlbumDragAcc = 0f
        if (dragFavAlbumIds == null) {
            val snapshot = latestVisibleAlbums.map { it.id }
            dragFavAlbumIds = snapshot
            dragFavAlbumStartIds = snapshot
        }
    }
    fun advanceFavAlbumDrag(album: Playlist, dyPx: Float, pitchPx: Float) {
        val ids = dragFavAlbumIds ?: return
        val (next, acc) = libraryDragAdvance(ids, album.id, favAlbumDragAcc + dyPx, pitchPx)
        if (next != ids) xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(dragHapticView)
        dragFavAlbumIds = next
        favAlbumDragAcc = acc
    }
    fun finishFavAlbumDrag(album: Playlist, cancelled: Boolean) {
        val draft = dragFavAlbumIds
        val start = dragFavAlbumStartIds
        draggingFavAlbumId = null
        favAlbumDragAcc = 0f
        dragFavAlbumIds = null
        dragFavAlbumStartIds = null
        if (cancelled || draft == null || start == null || draft == start) return
        val merged = librarySpliceReorder(latestFavAlbumOrderIds, draft.toSet(), draft)
        if (merged != latestFavAlbumOrderIds) {
            favAlbumOrderIds = merged
            onReorderFavorites(merged, true)
        }
    }

    // r673：音乐库页渲染树放进独立 composable lambda，避免 LibraryPage 合成超大 dex 方法（见 ui/common/IsolatedSection.kt）。
    IsolatedSection { Column(Modifier.fillMaxSize()) {
        // 首页、音乐库、设置共用状态栏内边距和顶栏高度；平台切换位于“我的歌单”操作菜单。
        // accept6-topbars：音乐库滚动联动顶栏（列表 state 显式化，原为 LazyColumn 内部默认 state，行为等价）+ 局部玻璃源。
        val libraryListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val libraryChromeState = remember { MainTabTopChromeState() }
        val libraryChromeGlass = xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState()
        MainTabChromeDriver(libraryListState, libraryChromeState)
        val filterOrder = listOf(
            "" to "全部平台（清除歌单筛选）",
            "wy" to "网易云音乐",
            "tx" to "QQ 音乐",
            "kg" to "酷狗音乐",
            "kw" to "酷我音乐",
            "mg" to "咪咕音乐"
        )
        AppPageHeader(
            title = "音乐库",
            loggedIn = loggedIn,
            avatarPath = avatarPath,
            onProfileClick = onLogin,
            showAvatar = true,
            // 设计稿 v2 页头：31sp 衬线标题 + 42dp 白色圆形平台按钮 + 42dp 头像，无副标题。
            avatarSizeDp = 42f, titleSizeSp = 31, serifTitle = true, designTitle = true, startPadding = 14.dp,
            chromeState = libraryChromeState,
            chromeGlass = libraryChromeGlass,
            trailing = {
                // 平台切换按钮与弹层与首页一致（锚定按钮下方的小卡片）。
                Box {
                    HomePlatformButton { showLibraryPlatformPicker = true }
                    DesignPlatformPopover(
                        expanded = showLibraryPlatformPicker, onDismiss = { showLibraryPlatformPicker = false },
                        options = filterOrder.map { (k, v) -> k to if (k.isBlank()) "全部平台" else v },
                        // r658b #19：原型 pick 抽屉「筛选歌单平台」——全称 + 每个平台的在线歌单数。
                        current = platFilter, title = "筛选歌单平台", eyebrow = "Filter",
                        subtitle = "只显示所选平台导入的在线歌单。",
                        optionTitle = { k, v -> if (k.isBlank()) v else xyz.cncnlp.soundwave.ui.common.platformFullName(k) },
                        optionSub = { k -> if (k.isBlank()) "清除歌单筛选" else "${bundle.playlists.count { it.source == k && it.id !in setOf("liked", "local") }} 个歌单" }
                    ) { source ->
                        onPlatFilter(source)
                        if (source.isNotBlank()) onPlatformClick(source)
                    }
                }
            }
        )
        // r657 第二轮：我的喜欢卡点击卡顿——原来每次重组都复制 600 首、逐首拼 key 判断正在播放、汇总时长 / 封面；
        // 改为按收藏数量 + 首尾 key 记忆，正在播放用 key 集合 O(1) 查询。
        val favsRaw = bundle.favs
        val favSig = favsRaw.size to (favsRaw.firstOrNull()?.key().orEmpty() + "|" + favsRaw.lastOrNull()?.key().orEmpty())
        val favSongs = remember(favSig) { favsRaw.toList() }
        val favKeys = remember(favSongs) { favSongs.mapTo(HashSet(favSongs.size)) { it.key() } }
        val favSubtitle = remember(favSongs) { favoritesSubtitle(favSongs.size, favSongs.sumOf { it.duration.toLong().coerceAtLeast(0L) }) }
        val favCover = remember(favSongs, loggedIn, liked?.cover) { libraryStackCovers(loggedIn, liked?.cover, favSongs.take(12).map { it.cover }).firstOrNull() }
        val likedShortcut = remember(liked, favSongs) { liked ?: Playlist("liked", "我的喜欢", local = true, songs = favSongs.toMutableList()) }
        val localShortcut = remember(local, local?.songs?.size) {
            (local ?: Playlist("local", "本地歌单", local = true)).let { pl ->
                if (pl.songs.isEmpty()) pl.copy(songs = LocalLibraryStore.songs(libCtx).toMutableList()) else pl
            }
        }
        LazyColumn(state = libraryListState, modifier = Modifier.fillMaxSize().then(xyz.cncnlp.soundwave.ui.common.localGlassSourceModifier(libraryChromeGlass))) {

            item(key = "library_shortcuts") {
                if (libraryQuickCardsVisible(orderIds)) {
                    Column(Modifier.fillMaxWidth()) {
                    // r657（v4 #5 / v3 #10）：我的喜欢 = v2 唱片套卡（无 ♥ 数量角标）；本地歌单 = 横向卡（无手机图标、扫描按钮无底色）。
                    val localSongs = localShortcut.songs.toList()
                    val likedPlaying = playingNow && currentKey.isNotEmpty() && currentKey in favKeys
                    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        LibraryFavoritesSleeveCard(
                            title = "我的喜欢", eyebrow = "MY FAVORITES", subtitle = favSubtitle,
                            cover = favCover,
                            defaultRes = R.drawable.library_design_cover_1,
                            enabled = favSongs.isNotEmpty(), spinning = likedPlaying,
                            reduceMotion = MotionPolicy.rememberReduceMotion(),
                            onOpen = { onOpen(likedShortcut) },
                            onMore = { menuPl = likedShortcut },
                            // r658d（快速点击卡顿）：当前歌就在「我的喜欢」里且已有可播媒体时，「播放」只是继续播放，
                            // 不再每次从第一首重新取链起播。
                            onPlay = {
                                if (currentKey.isNotEmpty() && currentKey in favKeys && PlayerService.currentKey() == currentKey && PlayerService.hasPlayableMedia()) PlayerService.toggle()
                                else favSongs.firstOrNull()?.let { onPlayFromPlaylist(it, favSongs, "liked") }
                            },
                            onShuffle = { val sh = favSongs.shuffled(); sh.firstOrNull()?.let { onPlayFromPlaylist(it, sh, "liked") } },
                            onPause = { PlayerService.toggle() },
                            discCover = currentCover.ifBlank { null },
                            modifier = Modifier.staggerEnter(1)
                        )
                        LibraryLocalRowCard(
                            title = "本地歌单", subtitle = if (localSongs.isEmpty()) "扫描设备中的音乐" else "${localSongs.size} 首 · 设备中的音乐",
                            cover = libraryStackCovers(loggedIn, localShortcut.cover, localSongs.map { it.cover }).firstOrNull(),
                            defaultRes = R.drawable.library_design_cover_3,
                            onOpen = { onOpen(localShortcut) },
                            onMore = { menuPl = localShortcut },
                            onScan = onScanLocal,
                            modifier = Modifier.staggerEnter(2),
                            scanning = localScanRunning
                        )
                    }
                    }
                }
            }
            item(key = "library_playlists_header") {
                Column(Modifier.padding(horizontal = 14.dp).padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(bottom = 6.dp, top = 6.dp)) {
                    // r657 修复：原型区块标题 20sp Manrope Bold、无副标题。
                    // ACCEPT5：标题随视图（在线歌单 ⇄ 收藏），一页只显其一。
                    Text(if (showFavoritesView) "收藏" else "在线歌单", color = onBg, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
                }
                // 设计稿 v2：在线歌单标题右侧不显示数量。
                Box {
                    // r657 修复：原型 34dp 描边圆钮（功能仍集成在一个按钮里）。
                    // E15（验收二轮）：视觉调小一档——圆钮 34→30dp、图标 17dp→IconSize.Inline(16dp)；点按热区 40dp 不变。
                    Box(
                        Modifier.size(40.dp).clickable(onClick = { showPlActions = true }, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() })
                            .semantics { contentDescription = "歌单操作"; role = androidx.compose.ui.semantics.Role.Button },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(30.dp).pressScale(PressMotion.SCALE_ICON).clip(CircleShape).background(xyz.cncnlp.soundwave.ui.common.frostedChipFill(pageColors.surface)).border(1.dp, pageColors.outline, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(DesignLineGlyphs.Sort, null, tint = onBg, modifier = Modifier.size(xyz.cncnlp.soundwave.ui.common.IconSize.Inline))
                        }
                    }
                    // 设计稿 .sw-add-btn 弹层：白色圆角卡片，图标 + 标题 + 说明；动作与原「歌单操作」完全一致。
                    DropdownMenu(
                        expanded = showPlActions, onDismissRequest = { showPlActions = false },
                        shape = RoundedCornerShape(16.dp), containerColor = pageColors.surface,
                        tonalElevation = 0.dp, shadowElevation = 14.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, xyz.cncnlp.soundwave.ui.settings.settingsLineColor()),
                        modifier = Modifier.widthIn(min = 236.dp)
                    ) {
                        listOf(
                            Triple("import", DesignLineGlyphs.Cloud, "导入在线歌单" to "从网易云、QQ 音乐等链接导入"),
                            Triple("new", DesignLineGlyphs.Plus, "新建歌单" to "创建一个只属于你的歌单"),
                            Triple("scan", DesignLineGlyphs.Disc, "扫描本地歌曲" to "把设备中的音乐加入本地歌单"),
                            // ACCEPT5：原「排序歌单」槽位改为视图切换（小字行明示当前态，主行明示去向）；
                            // 排序本身改整行长按拖动，不再有排序弹层。
                            Triple(
                                "toggle_view",
                                if (showFavoritesView) DesignLineGlyphs.Library else DesignLineGlyphs.Heart,
                                (if (showFavoritesView) "当前：收藏" else "当前：在线歌单") to
                                    (if (showFavoritesView) "切换到在线歌单" else "切换到收藏")
                            ),
                            Triple("switch_platform", DesignLineGlyphs.Repeat, "切换平台" to "当前：${platformName(platform)}")
                        ).forEach { (key, icon, text) ->
                            DropdownMenuItem(
                                leadingIcon = { Icon(icon, null, tint = onBg, modifier = Modifier.size(18.dp)) },
                                text = {
                                    Column {
                                        Text(text.first, color = pageColors.onSurfaceVariant, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
                                        Text(text.second, color = onBg, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                },
                                onClick = {
                                    showPlActions = false
                                    when (key) {
                                        "toggle_view" -> setLibraryView(!showFavoritesView)
                                        "switch_platform" -> showLibraryPlatformPicker = true
                                        "import" -> onImport()
                                        "scan" -> onScanLocal()
                                        "new" -> onNewPlaylist()
                                    }
                                }
                            )
                        }
                    }
                }
            }
                }
            }
            if (!showFavoritesView) {
                if (onlinePlaylists.isEmpty()) {
                    item(key = "library_online_empty") {
                        Column(Modifier.padding(horizontal = 14.dp)) {
                        val platformFiltered = platFilter.isNotBlank()
                        // ACCEPT4：按 v8 稿 .v8empty 的浮动艺术空态 + 双按钮（用户补发截图逐元素对齐）；
                        // 「新建歌单」与区头「歌单操作」菜单入口并存（用户 2026-11-04 裁定）。
                        LibraryPlaylistsEmptyState(
                            title = if (platformFiltered) "这个平台还没有歌单" else "还没有导入歌单",
                            message = if (platformFiltered) "清除平台筛选查看全部，或从这个平台导入一个歌单。"
                                else "粘贴网易云、QQ 音乐的歌单链接即可一键导入，也可以新建一个只属于你的歌单。",
                            primaryLabel = "导入歌单",
                            primaryIcon = if (platformFiltered) DesignLineGlyphs.Plus else DesignLineGlyphs.Download,
                            onPrimary = onImport,
                            secondaryLabel = if (platformFiltered) "清除筛选" else "新建歌单",
                            secondaryIcon = if (platformFiltered) null else DesignLineGlyphs.Plus,
                            onSecondary = if (platformFiltered) ({ onPlatFilter("") }) else onNewPlaylist,
                            hint = if (platformFiltered) null else "支持 网易云 · QQ 音乐 · 酷狗 · 酷我 · 咪咕"
                        )
                        }
                    }
                } else {
                    items(onlinePlaylists, key = { "library_playlist_${it.id}" }, contentType = { "library_playlist" }) { pl ->
                        // SW-12-12：歌单增删平滑让位 / 淡入（key = 歌单 id，数据稳定 id）。
                        // 长按区域分工：封面长按拖动排序（松手落盘）；行身长按打开管理抽屉。
                        Box(swAnimateItem(listReduceMotion).padding(horizontal = 14.dp)) {
                            val localOnly = pl.local && pl.remoteId.isBlank() && pl.source.isBlank()
                            LibraryDesignRow(
                                title = pl.name,
                                cover = playlistDisplayCover(pl),
                                badge = if (localOnly) "本地" else libraryPlatformBadge(pl.source),
                                count = "${pl.songCount()} 首 · " + if (localOnly) "本地歌单" else "在线导入",
                                onLongPress = { menuPl = pl },
                                coverKey = pl.id,
                                dragging = pl.id == draggingPlId,
                                onDragStart = { beginOnlineDrag(pl) },
                                onDrag = { dy, pitch -> advanceOnlineDrag(pl, dy, pitch) },
                                onDragEnd = { cancelled -> finishOnlineDrag(pl, cancelled) }
                            ) { SharedCoverTransition.arm(pl.id); onOpen(pl) }
                        }
                    }
                }
            } else if (savedPlaylists.isEmpty() && visibleAlbums.isEmpty()) {
                item(key = "library_saved_empty") {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        // ACCEPT5：收藏视图空态沿用同一浮动艺术空态组件，主按钮切回在线歌单视图。
                        LibraryPlaylistsEmptyState(
                            title = "还没有收藏",
                            message = "在歌单或专辑详情页点收藏后，会回到这里集中存放。",
                            primaryLabel = "切换到在线歌单",
                            primaryIcon = DesignLineGlyphs.Library,
                            onPrimary = { setLibraryView(false) }
                        )
                    }
                }
            } else {
                // ACCEPT5：收藏视图内部分组——歌单在前、专辑在后，各组非空才出小标题；
                // 收藏行去掉标题下小字行（count 传空），平台胶囊保留；无「ITEMS」计数标签。
                if (savedPlaylists.isNotEmpty()) {
                    item(key = "library_saved_group_playlists") { LibrarySavedGroupHeader("歌单") }
                    items(favPlaylists, key = { "library_saved_${it.id}" }, contentType = { "library_playlist" }) { pl ->
                        // SW-12-12：收藏歌单增删平滑让位 / 淡入（key = 歌单 id）。
                        // 收藏组内排序：封面长按拖动（自有顺序落盘）；行身长按打开管理抽屉（不变）。
                        Box(swAnimateItem(listReduceMotion).padding(horizontal = 14.dp)) {
                            LibraryDesignRow(
                                title = pl.name,
                                cover = playlistDisplayCover(pl),
                                badge = libraryPlatformBadge(pl.source),
                                onLongPress = { menuPl = pl },
                                coverKey = pl.id,
                                dragging = pl.id == draggingFavPlId,
                                onDragStart = { beginFavPlDrag(pl) },
                                onDrag = { dy, pitch -> advanceFavPlDrag(pl, dy, pitch) },
                                onDragEnd = { cancelled -> finishFavPlDrag(pl, cancelled) }
                            ) { SharedCoverTransition.arm(pl.id); onOpen(pl) }
                        }
                    }
                }
                if (visibleAlbums.isNotEmpty()) {
                    item(key = "library_saved_group_albums") { LibrarySavedGroupHeader("专辑") }
                    items(favAlbums, key = { "library_album_${it.id}" }, contentType = { "library_playlist" }) { album ->
                        // SW-12-12：收藏专辑增删平滑让位 / 淡入（key = 专辑 id）。
                        // 收藏专辑组内排序：封面长按拖动（自有顺序落盘）；行身长按打开管理抽屉（不变）。
                        Box(swAnimateItem(listReduceMotion).padding(horizontal = 14.dp)) {
                            LibraryDesignRow(
                                title = album.name,
                                cover = playlistDisplayCover(album),
                                badge = libraryPlatformBadge(album.source),
                                fallbackKind = "playlist",
                                onLongPress = { menuPl = album },
                                dragging = album.id == draggingFavAlbumId,
                                onDragStart = { beginFavAlbumDrag(album) },
                                onDrag = { dy, pitch -> advanceFavAlbumDrag(album, dy, pitch) },
                                onDragEnd = { cancelled -> finishFavAlbumDrag(album, cancelled) }
                            ) { onOpen(album) }
                        }
                    }
                }
            }
        // 仅保留行标题右侧的导入/扫描按钮，底部按钮已移除。
        item { Spacer(Modifier.height(dockContentBottomPadding())) }
        }
    } } // IsolatedSection: library page

    menuPl?.let { pl ->
        val canDelete = pl.id !in setOf("liked", "local", "history")
        val isLiked = pl.id == "liked"
        val playlistHeartActive = heartActive && activeHeartPlaylistKey == heartPlaylistKey(pl)
        // r657 第二轮：我的喜欢 / 本地歌单 / 在线歌单长按与 ⋮ 菜单统一为设计抽屉（原型 .sheet：眉题 + 标题、条目头、线性图标行；
        // 磨砂背景开时面板半透明 + 背后模糊、分隔线隐藏）。操作项与原业务入口一一对应。
        val isLocalPlaylist = pl.local || pl.id == "local" || pl.id.startsWith("local_")
        val g = xyz.cncnlp.soundwave.ui.common.DesignSheetGlyphs
        // ACCEPT5（退出动画普查 L8）：act 只执行动作；关闭走内容侧退场句柄，
        // menuPl=null 由退场后的 onDismiss 承担（此前 act 同步清状态、退场从未播放）。
        fun act(key: String) {
            when (key) {
                "edit" -> { renameText = pl.name; renamePl = pl }
                "cover" -> onCoverPick(pl)
                "heart" -> {
                    val loadedDetail = detail?.takeIf { heartPlaylistKey(it) == heartPlaylistKey(pl) }
                    onPlaylistHeartMode(if (loadedDetail != null) pl.copy(songs = detailSongs.toMutableList()) else pl)
                }
                "clear" -> onClearLiked()
                "delsong" -> { deletePl = pl; delSel = emptySet(); showDeleteSongs = true }
                "del" -> onDeletePlaylist(pl)
            }
        }
        xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = { menuPl = null }) {
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { menuPl = null }
            xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Playlist actions", if (isLiked) "我的喜欢" else if (pl.id == "local") "本地歌单" else "歌单操作")
            xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader(
                pl.cover.ifEmpty { pl.songs.firstOrNull()?.cover.orEmpty() }, pl.name,
                if (pl.songs.isNotEmpty()) "${pl.songs.size} 首" else if (pl.count > 0) "${pl.count} 首" else ""
            )
            xyz.cncnlp.soundwave.ui.common.DesignSheetRow(g.Rename, "编辑名称", "重命名") { act("edit"); dismiss() }
            xyz.cncnlp.soundwave.ui.common.DesignSheetRow(g.Cover, "更换封面", "从相册选择") { act("cover"); dismiss() }
            // 本地歌单不提供心动模式；在线/收藏歌单可启动真实心动会话。
            if (!isLocalPlaylist) xyz.cncnlp.soundwave.ui.common.DesignSheetRow(
                if (playlistHeartActive) xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.HeartLoopOff else xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.HeartLoop,
                if (playlistHeartActive) "关闭心动模式" else "心动模式", if (playlistHeartActive) "恢复原播放顺序" else "智能续播相似歌曲"
            ) { act("heart"); dismiss() }
            // ACCEPT6 dialogfrost：危险区留白——上方空带 + 区内行距（与歌曲操作表同语言，只留白不加色）。
            val delSongsRow = !isLiked && isLocalPlaylist
            if (isLiked || delSongsRow || canDelete) Spacer(Modifier.height(ThemeTokens.Sheet.DangerZoneTopGap))
            if (isLiked) xyz.cncnlp.soundwave.ui.common.DesignSheetRow(g.Delete, "删除全部", "清空我的喜欢", danger = true) { act("clear"); dismiss() }
            if (delSongsRow) xyz.cncnlp.soundwave.ui.common.DesignSheetRow(g.Delete, "删除歌曲", "勾选移除", danger = true) { act("delsong"); dismiss() }
            if (delSongsRow && canDelete) Spacer(Modifier.height(ThemeTokens.Sheet.DangerZoneRowGap))
            if (canDelete) xyz.cncnlp.soundwave.ui.common.DesignSheetRow(g.Remove, "删除歌单", "不可恢复", danger = true) { act("del"); dismiss() }
        }
    }

    if (showDeleteSongs) {
        deletePl?.let { pl ->
            val picked = pl.songs.filter { it.key() in delSel }
            // 对话框清扫：自绘 Dialog 弹层收敛到 DesignSheet 家族；勾选行与两种删除语义逐字不变，
            // 「删除并删本地文件」走家族危险主按钮，未勾选时两键禁用（原为 clickable enabled 门控）。
            xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = { showDeleteSongs = false }, scrollContent = false) {
                // 收尾棒 J8 句柄补齐：两种删除均即时执行、清可见状态走关闭句柄（退场后触发 onDismiss）。
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showDeleteSongs = false }
                xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Playlist", "删除歌曲 · ${pl.name}")
                Spacer(Modifier.height(6.dp))
                Text("勾选后选择移除方式", color = pageColors.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(pl.songs, key = { it.key() }) { s ->
                        val sel = s.key() in delSel
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pageColors.surfaceVariant).clickable {
                                delSel = if (sel) delSel - s.key() else delSel + s.key()
                            }.padding(vertical = 6.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (sel) Icons.Filled.CheckCircle else Icons.Filled.CheckBoxOutlineBlank,
                                if (sel) "已选" else "未选",
                                tint = primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是「♪」文本、加载时只剩 primary 12% 底）。
                                DefaultCoverPlaceholder(Modifier.fillMaxSize())
                                if (s.cover.isNotEmpty()) AsyncImage(CacheManager.coverModel(LocalContext.current, s.cover) ?: s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, color = dialogFg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.singer, color = pageColors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                xyz.cncnlp.soundwave.ui.common.SheetActionButtons(
                    secondary = "仅从歌单删除", onSecondary = {
                        onDeleteSongs(picked, false)
                        dismiss()
                    }, secondaryEnabled = picked.isNotEmpty(),
                    primary = "删除并删本地文件", primaryEnabled = picked.isNotEmpty(), primaryDanger = true,
                    onPrimary = {
                        onDeleteSongs(picked, true)
                        dismiss()
                    }
                )
            }
        }
    }
    renamePl?.let { pl ->
        xyz.cncnlp.soundwave.ui.common.RenamePlaylistDialog(
            name = renameText,
            onNameChange = { renameText = it },
            onDismiss = { renamePl = null },
            onConfirm = {
                val n = renameText.trim()
                if (n.isNotEmpty()) onRenamePlaylist(pl, n)
                // 收尾棒 J8 配对：清状态（renamePl = null）归 onDismiss，由弹层关闭句柄在退场后触发。
            }
        )
    }
}

/** r657 第三轮（用户 10-18 硬约束）：详情曲目列表不显示编号；R657DetailNoIndexTest 与 detail-no-index 守卫锁定。 */
internal const val DetailTrackListShowsIndex = false


/** r658b #11：音乐库歌单行平台胶囊文案（未知来源显示「在线」）。 */
internal fun libraryPlatformBadge(source: String): String =
    if (source.isBlank()) "在线" else platformName(source).ifBlank { source }

/** ACCEPT5：收藏视图内分组小标题（歌单 / 专辑），与区块标题同一排版令牌的降阶版。 */
@Composable
private fun LibrarySavedGroupHeader(label: String) {
    val pageColors = libraryPageThemeColors(MaterialTheme.colorScheme)
    Text(
        label, color = pageColors.onSurfaceVariant, fontWeight = FontWeight.Bold,
        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, fontSize = 12.sp,
        letterSpacing = 0.08.em,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 2.dp)
    )
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
