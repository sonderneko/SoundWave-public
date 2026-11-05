package xyz.cncnlp.soundwave

import androidx.compose.foundation.lazy.itemsIndexed
import xyz.cncnlp.soundwave.ui.common.TypeScale
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.motion.SharedCoverTransition
import xyz.cncnlp.soundwave.ui.motion.sharedCoverSource
import xyz.cncnlp.soundwave.ui.common.staggerEnter
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding

import androidx.compose.ui.draw.shadow
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.StateBlock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em

internal fun searchRecommendationPreview(songs: List<Song>): List<Song> =
    songs.distinctBy(PersonalRecommendations::songIdentity).take(3)

internal sealed interface SearchResultPresentation {
    data object Loading : SearchResultPresentation
    data object Empty : SearchResultPresentation
    data object Ready : SearchResultPresentation
    data class Stale(val message: String) : SearchResultPresentation
    data class Error(val message: String) : SearchResultPresentation
}

internal fun searchResultPresentation(
    content: LoadableContent<*>,
    hasItems: Boolean
): SearchResultPresentation = when (content) {
    LoadableContent.Loading -> SearchResultPresentation.Loading
    is LoadableContent.Available<*> -> if (hasItems) SearchResultPresentation.Ready else SearchResultPresentation.Empty
    is LoadableContent.Stale<*> -> if (hasItems) SearchResultPresentation.Stale(content.error) else SearchResultPresentation.Error(content.error)
    is LoadableContent.Empty -> SearchResultPresentation.Empty
    is LoadableContent.Unavailable -> SearchResultPresentation.Error(content.error)
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchPage(
    songResults: List<Song>,
    plResults: List<Playlist>,
    artistResults: List<Song> = emptyList(),
    albumResults: List<Song> = emptyList(),
    loggedIn: Boolean,
    user: String,
    avatarPath: String = "",
    kw: String = "",
    onKw: (String) -> Unit = {},
    mode: String = "all",
    onMode: (String) -> Unit = {},
    searched: Boolean = false,
    onLogin: () -> Unit,
    onSearch: (String, String, String) -> Unit,
    onPlay: (Song) -> Unit,
    onOpenAlbum: (Song) -> Unit = {},
    onOpenPl: (Playlist) -> Unit,
    onOpenSinger: (Song) -> Unit = {},
    isPlaylistFavorited: (Playlist) -> Boolean = { PlaylistFavorites.isFavorited(it) },
    onTogglePlaylistFavorite: (Playlist) -> Unit = { PlaylistFavorites.toggle(it) },
    onLike: (Song) -> Unit = {},
    onAdd: (Song) -> Unit = {},
    onDownload: (Song) -> Unit = {},
    onAddNext: (Song) -> Unit = {},
    onSimilarSong: (Song) -> Unit = {},
    onDislikeSong: (Song) -> Unit = {},
    hotKeywords: Map<String, List<String>> = emptyMap(),
    hotWordsPlatform: String = "wy",
    searchHistory: List<String> = emptyList(),
    recommendedSongs: List<Song> = emptyList(),
    recommendationLoading: Boolean = false,
    recommendationMessage: String = "",
    onRefreshRecommendations: () -> Unit = {},
    onHistoryClick: (String) -> Unit = {},
    onHistoryDelete: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {},
    crossPlatform: Boolean = false,
    onCrossPlatform: (Boolean) -> Unit = {},
    isLiked: (Song) -> Boolean = { false },
    platform: String = "wy",
    onPlatform: (String) -> Unit = {},
    nowPlayingKey: String = "",
    isPlayingNow: Boolean = false,
    songContent: LoadableContent<List<Song>> = LoadableContent.Empty(),
    playlistContent: LoadableContent<List<Playlist>> = LoadableContent.Empty(),
    artistContent: LoadableContent<List<Song>> = LoadableContent.Empty(),
    albumContent: LoadableContent<List<Song>> = LoadableContent.Empty(),
    onRetrySearch: () -> Unit = {},
    onSearchFocus: (Boolean) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onBackToSearch: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    PlaySourceScope("搜索") // r657 第二轮：播放来源
    val listReduceMotion = MotionPolicy.rememberReduceMotion()
    val primary = MaterialTheme.colorScheme.primary
    val hotWords = hotKeywords[hotWordsPlatform].orEmpty()
    val hotPageCount = ((hotWords.size + 9) / 10).coerceAtLeast(1)
    var src by remember { mutableStateOf(platform) }
    val onBg = MaterialTheme.colorScheme.onBackground
    LaunchedEffect(platform) { if (src != platform) src = platform }
    // r656：搜索栏聚焦时在其下方弹出搜索记录小下拉框（替代原「最近搜索」区块）。
    var fieldFocused by remember { mutableStateOf(false) }
    var recommendMoreSong by remember { mutableStateOf<Song?>(null) }
    var platformMenuOpen by remember { mutableStateOf(false) }
    var searchTypeMenuOpen by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val platforms = listOf("wy" to "网易云", "kg" to "酷狗", "kw" to "酷我", "tx" to "QQ", "mg" to "咪咕")
    val modes = listOf(
        "all" to "全部",
        "song" to "歌曲",
        "pl" to "歌单",
        "artist" to "歌手",
        "album" to "专辑"
    )
    fun doSearch(query: String = kw, source: String = src, targetMode: String = mode) {
        val q = query.trim()
        if (q.isEmpty()) return
        onSearch(q, source, targetMode)
    }
    // r654：搜索框聚焦后未输入就按返回——系统先收起键盘但输入框仍持焦，底栏/迷你条因“搜索聚焦”一直隐藏。
    // 键盘由显示变为隐藏时主动清焦并通知宿主恢复底栏。
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) { imeWasVisible = false; focusManager.clearFocus(); onSearchFocus(false) }
    }
    // r654：从「全部」结果点「查看全部」进入分类结果后，返回应回到全部结果页而不是搜索主页。
    var showAllFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searched) { if (!searched) showAllFrom = null }
    androidx.activity.compose.BackHandler(enabled = searched && kw.isNotBlank() && showAllFrom != null && mode != showAllFrom) {
        val back = showAllFrom ?: "all"
        showAllFrom = null
        onMode(back); doSearch(targetMode = back)
    }
    // 搜索页不再自绘设计稿暖白 Paper（看起来偏黄）：与首页 / 音乐库 / 设置一致，透出 AppRoot 根背景（同一主题 background token）。
    val pageBg = Color.Transparent
    Column(
        Modifier
            .fillMaxSize()
            .background(pageBg)
    ) {
        AppPageHeader(
            title = if (searched && kw.isNotBlank()) "搜索结果" else "搜索",
            loggedIn = loggedIn,
            avatarPath = avatarPath,
            onProfileClick = onLogin,
            showAvatar = true,
            // 设计稿 v2 页头：31sp 衬线标题 + 42dp 白色圆钮（切换搜索平台）+ 42dp 头像。
            avatarSizeDp = 42f, titleSizeSp = 31, serifTitle = true, designTitle = true, startPadding = 14.dp,
            trailing = {
                // 平台切换弹层与首页一致（锚定按钮下方的小卡片）。
                Box {
                    xyz.cncnlp.soundwave.HomePlatformButton(filterGlyph = searched && kw.isNotBlank()) { searchTypeMenuOpen = false; platformMenuOpen = true }
                    // r658b #19：原型 pick 抽屉「切换搜索平台」（3 列）。
                    xyz.cncnlp.soundwave.DesignPlatformPopover(
                        expanded = platformMenuOpen, onDismiss = { platformMenuOpen = false },
                        options = platforms.sortedBy { xyz.cncnlp.soundwave.PlatformPickOrder.indexOf(it.first).let { i -> if (i < 0) 99 else i } },
                        current = src, title = "切换搜索平台", eyebrow = "Search platform",
                        subtitle = "搜索歌曲、歌单、专辑与歌手使用的平台。", columns = 3, optionSub = { "" }
                    ) { key ->
                        if (key != src) {
                            src = key
                            onPlatform(key)
                            if (kw.isNotBlank()) doSearch(source = key)
                        }
                    }
                }
            }
        )
        if (!searched || kw.isBlank()) {
            // 设计稿 .search-shell：左上「SEARCH / 01」，珊瑚 2dp 外壳（8% 底）包住 58dp 输入框。
            Text(
                "SEARCH / 01", color = designAccentTextColor(), fontSize = 8.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
                fontWeight = FontWeight.Medium, letterSpacing = 0.12.em,
                // accept6-topbars（用户 2026-11-04 22:44）：顶栏上移后首块内容同步跟上移约 4dp，上距 6→2dp。
                modifier = Modifier.padding(start = 32.dp, top = 2.dp, bottom = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    .shadow(16.dp, RoundedCornerShape(23.dp), ambientColor = Color(0xFF131915).copy(alpha = 0.08f), spotColor = Color(0xFF131915).copy(alpha = 0.10f))
                    // 珊瑚 2dp 外描边 → 4.5dp 浅珊瑚内环（accent 12%，同设计稿 focus ring 0 0 0 4px）→ 白色输入胶囊（1dp 淡珊瑚边）。
                    .clip(RoundedCornerShape(23.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .background(primary.copy(alpha = 0.13f))
                    .border(2.dp, primary.copy(alpha = 0.86f), RoundedCornerShape(23.dp))
                    .padding(start = 6.5.dp, end = 6.5.dp, top = 6.5.dp, bottom = 6.5.dp)
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, primary.copy(alpha = 0.20f), RoundedCornerShape(17.dp))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(DesignLineGlyphs.Search, contentDescription = "搜索", tint = primary, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(13.dp))
                BasicTextField(
                    value = kw,
                    onValueChange = onKw,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = onBg, fontSize = TypeScale.Body.fontSize),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearchFocus(false)
                        keyboardController?.hide()
                        doSearch()
                    }),
                    modifier = Modifier.weight(1f).onFocusChanged { fieldFocused = it.isFocused; onSearchFocus(it.isFocused) },
                    decorationBox = { innerTextField ->
                        Box {
                            if (kw.isBlank()) Text("搜索歌曲、专辑、艺人…", color = onBg.copy(alpha = 0.48f), fontSize = TypeScale.Body.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            innerTextField()
                        }
                    }
                )
                if (kw.isNotBlank()) Text("清除", color = onBg.copy(alpha = 0.52f), fontSize = TypeScale.Micro.fontSize, modifier = Modifier.clickable { onKw("") }.padding(horizontal = 5.dp, vertical = 8.dp))
                Box {
                    // 设计稿 .search-mode-button：46×44、圆角 14，琥珀色下箭头；选了类别时在箭头前显示类别名。
                    Row(
                        Modifier.heightIn(min = 44.dp).widthIn(min = 46.dp).clip(RoundedCornerShape(14.dp))
                            .clickable { searchTypeMenuOpen = !searchTypeMenuOpen }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                    ) {
                        if (mode != "all") Text(modes.firstOrNull { it.first == mode }?.second.orEmpty(), color = onBg, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 3.dp))
                        Icon(DesignLineGlyphs.Down, contentDescription = "选择搜索类别", tint = Color(0xFFE0A100), modifier = Modifier.size(20.dp))
                    }
                    // 设计稿搜索类别弹层：白色圆角 16 卡片 + 柔和投影；当前项浅灰底；12sp 粗体「搜索歌曲 / 歌单 / 专辑 / 歌手」。
                    DropdownMenu(
                        expanded = searchTypeMenuOpen, onDismissRequest = { searchTypeMenuOpen = false },
                        shape = RoundedCornerShape(16.dp), containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp, shadowElevation = 12.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, xyz.cncnlp.soundwave.ui.settings.settingsLineColor()),
                        modifier = Modifier.widthIn(min = 148.dp).padding(horizontal = 6.dp)
                    ) {
                        modes.filterNot { it.first == "all" }.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text("搜索$label", color = onBg, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (mode == key) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                                onClick = {
                                // Tapping the selected category clears it back to All, without submitting.
                                onMode(if (mode == key) "all" else key)
                                searchTypeMenuOpen = false
                            })
                        }
                    }
                }
                // 设计稿空闲态无独立搜索键（键盘「搜索」提交）；有输入时出现珊瑚搜索键便于单手提交。
                if (kw.isNotBlank()) {
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.size(34.dp).clip(CircleShape).background(primary).clickable {
                        onSearchFocus(false)
                        keyboardController?.hide()
                        doSearch()
                    }, contentAlignment = Alignment.Center) {
                        Icon(DesignLineGlyphs.Search, contentDescription = "开始搜索", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        // r657 修复（审计第 20 项）：搜索首屏区块错峰入场（探索声音 → 大家都在搜 → 热词 → 猜你喜欢）。
        val searchStagger = xyz.cncnlp.soundwave.ui.common.rememberStaggerGate()
        Box(Modifier.fillMaxSize()) {
        // r657 第二轮：搜索记录下拉框磨砂——页面列表作为局部模糊源（仅磨砂背景开时）。
        val historyGlass = xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState()
        val historyFrosted = xyz.cncnlp.soundwave.ui.common.FrostedPrefs.pageActive
        LazyColumn(
            Modifier.fillMaxSize()
                .then(if (historyFrosted) xyz.cncnlp.soundwave.ui.common.localGlassSourceModifier(historyGlass) else Modifier)
                .padding(top = if (!searched || kw.isBlank()) 5.dp else 0.dp)
        ) {
            if (!searched || kw.isBlank()) {
                item {
                    // 设计稿 .genre-grid：9 个分类（纯音乐 / 抖音 / 国风 / 古典 / 欧美 / 华语 / 日语 / 摇滚 / 氛围），各自绘制封面，滚动时波浪起伏。
                    // r654：探索声音及下方整体上移 10dp（24→14）。
                    Column(Modifier.fillMaxWidth().staggerEnter(0, searchStagger).padding(top = 10.dp, bottom = 0.dp)) { // r655：搜索栏下方各区间距 -4dp
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("探索声音", color = onBg, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.03).em, modifier = Modifier.weight(1f).semantics { heading() })
                            Text("SWIPE FOR MORE", color = Muted, fontSize = TypeScale.Micro.fontSize, letterSpacing = 0.04.em, fontWeight = FontWeight.Medium, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono) // 原型 .sec .link
                        }
                        xyz.cncnlp.soundwave.ui.search.SearchGenreRail(onPick = { genre -> onKw(genre); doSearch(query = genre) })
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().staggerEnter(1, searchStagger).padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("大家都在搜", color = onBg, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.03).em, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.weight(1f))
                        Text("TRENDING NOW", color = Muted, fontSize = TypeScale.Micro.fontSize, letterSpacing = 0.04.em, fontWeight = FontWeight.Medium, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                    }
                }
                item {
                    val hotPager = rememberPagerState(initialPage = 0) { hotPageCount }
                    LaunchedEffect(hotWordsPlatform) {
                        if (hotPager.currentPage != 0) hotPager.scrollToPage(0)
                    }
                                        // 按热词数量估算行数，避免固定 196dp 在热词较少时留下大片空白。
                    val hotRows = hotWords.take(10).size.let { n -> when { n == 0 -> 1; n <= 6 -> 2; n <= 9 -> 3; else -> 4 } }
                    HorizontalPager(state = hotPager, modifier = Modifier.fillMaxWidth().staggerEnter(2, searchStagger).height((hotRows * 42 + 8).dp)) { page ->
                        val hots = hotWords.drop(page * 10).take(10)
                        Column(Modifier.padding(horizontal = 14.dp).padding(top = 2.dp, bottom = 4.dp)) {
                            if (hots.isEmpty()) {
                                Text("暂无热词", color = Muted, fontSize = TypeScale.Caption.fontSize, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                // r657 第三轮：原型 .chip——34dp 高、左右 13dp、间距 6dp；DM Mono 10sp 珊瑚序号 + Manrope 700 12.5sp 热词；胶囊间距 8dp。
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    hots.forEachIndexed { index, word ->
                                        val rank = page * 10 + index
                                        Row(
                                            Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(999.dp))
                                                .background(xyz.cncnlp.soundwave.ui.common.frostedChipFill(MaterialTheme.colorScheme.surface))
                                                .border(1.dp, xyz.cncnlp.soundwave.ui.settings.settingsLineColor(), RoundedCornerShape(999.dp))
                                                .clickable { onKw(word); doSearch(query = word) }
                                                .padding(horizontal = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                (rank + 1).toString().padStart(2, '0'),
                                                color = designAccentTextColor(), fontSize = 10.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Medium
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(word, color = onBg, fontSize = TypeScale.BodySmall.fontSize /* SW-12-14 .5 归档：原 12.5.sp → BodySmall 13 */, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // r656：「最近搜索」区块已移除，改为搜索栏聚焦时的下拉框（SearchHistoryDropdown）。
            }
            if (searched && kw.isNotBlank()) {
                item(key = "search_result_head") {
                    // r658c #10（用户 10-19）：结果页搜索栏可直接点按原地重新输入（IME 搜索即重搜）；
                    // 移除右侧「类别 ▾」切换按钮与下方结果分类 tab（分类入口改由「综合」各区块的查看全部进入）。
                    val rfShape = RoundedCornerShape(999.dp)
                    var rq by remember { mutableStateOf(kw) }
                    var rqFocused by remember { mutableStateOf(false) }
                    LaunchedEffect(kw) { if (!rqFocused) rq = kw }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp).heightIn(min = 52.dp) /* r658d：离顶栏太近，上边距 4→14dp */
                            .shadow(8.dp, rfShape, ambientColor = Color(0xFF131915).copy(alpha = 0.06f), spotColor = Color(0xFF131915).copy(alpha = 0.08f))
                            .clip(rfShape).background(xyz.cncnlp.soundwave.ui.common.frostedCardFill(MaterialTheme.colorScheme.surface))
                            .border(1.dp, if (rqFocused) primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline, rfShape)
                            .padding(start = 18.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(DesignLineGlyphs.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = rq,
                            onValueChange = { rq = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = onBg, fontSize = TypeScale.Body.fontSize, fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboardController?.hide()
                                val q = rq.trim()
                                if (q.isNotEmpty()) { onKw(q); doSearch(query = q) }
                            }),
                            modifier = Modifier.weight(1f).onFocusChanged { rqFocused = it.isFocused }
                                .semantics { contentDescription = "编辑搜索：$kw" },
                            decorationBox = { inner ->
                                Box {
                                    if (rq.isBlank()) Text("搜索歌曲、专辑、艺人…", color = onBg.copy(alpha = 0.48f), fontSize = TypeScale.Body.fontSize, maxLines = 1)
                                    inner()
                                }
                            }
                        )
                        if (rq.isNotEmpty()) Text("清除", color = onBg.copy(alpha = 0.52f), fontSize = TypeScale.Micro.fontSize, modifier = Modifier.clickable { rq = "" }.padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                }
                // ACCEPT5（v8 稿搜索加载语言）：任一分区仍在加载时，搜索框下方出 EqBars 状态行；纯呈现，加载状态仍走既有 LoadableContent。
                val searchBusy = when (mode) {
                    "all" -> songContent is LoadableContent.Loading || playlistContent is LoadableContent.Loading ||
                        albumContent is LoadableContent.Loading || artistContent is LoadableContent.Loading
                    "pl" -> playlistContent is LoadableContent.Loading
                    else -> songContent is LoadableContent.Loading
                }
                if (searchBusy) item(key = "search_busy_status") {
                    SearchBusyStatusRow(
                        query = kw,
                        section = when (mode) {
                            "all" -> "综合"
                            "pl" -> "歌单"
                            "artist", "singer" -> "歌手"
                            "album" -> "专辑"
                            else -> "歌曲"
                        },
                        platform = platformName(src)
                    )
                }
                if (mode == "all") {
                    item(key = "search_all_sections") {
                        SearchAllSections(
                            query = kw,
                            songs = songResults,
                            playlists = plResults,
                            albums = albumResults,
                            artists = artistResults,
                            songState = songContent,
                            playlistState = playlistContent,
                            albumState = albumContent,
                            artistState = artistContent,
                            onShowAll = { target -> showAllFrom = "all"; onMode(target); doSearch(targetMode = target) },
                            onPlay = onPlay,
                            onOpenPlaylist = onOpenPl,
                            onOpenAlbum = onOpenAlbum,
                            onOpenArtist = onOpenSinger,
                            showPlatformName = crossPlatform
                        )
                    }
                } else if (mode == "pl") {
                    val (ld, er, et) = playlistContent.toEmptySlot("无歌单结果")
                    if (ld) item { xyz.cncnlp.soundwave.ui.common.SkeletonRows(count = 5, coverSize = 56.dp, coverShape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    else if (er.isNotEmpty()) item { SearchErrorHint(er, onRetrySearch) }
                    else if (plResults.isEmpty()) item { StateBlock(DesignLineGlyphs.Search, et) }
                    else {
                        item(key = "pl_head") { xyz.cncnlp.soundwave.ui.search.SearchSecHeader("歌单", plResults.size) }
                        items(plResults, key = { "pl:${it.source}:${it.id}" }, contentType = { "search_pl_row" }) { pl ->
                            xyz.cncnlp.soundwave.ui.search.SearchResultRow(
                                modifier = swAnimateItem(listReduceMotion),
                                cover = pl.cover, title = pl.name,
                                subtitle = listOf(platformName(pl.source), if (pl.songCount() > 0) "${pl.songCount()} 首" else "",
                                    if (pl.playCount > 0) "${formatPlayCount(pl.playCount)} 次播放" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                placeholder = Icons.Outlined.FormatListBulleted,
                                onClick = { SharedCoverTransition.arm(pl.id); onOpenPl(pl) },
                                coverModifier = Modifier.sharedCoverSource(pl.id)
                            ) {
                                PlaylistFavoriteMark(
                                    filled = isPlaylistFavorited(pl),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    iconSize = 18.dp,
                                    onClick = { onTogglePlaylistFavorite(pl) }
                                )
                            }
                        }
                    }
                } else if (mode == "artist" || mode == "singer") {
                    val (ld, er, et) = songContent.toEmptySlot("无歌手结果")
                    if (ld) item { xyz.cncnlp.soundwave.ui.common.SkeletonRows(count = 5, coverSize = 56.dp, circleCover = true, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    else if (er.isNotEmpty()) item { SearchErrorHint(er, onRetrySearch) }
                    else if (songResults.isEmpty()) item { StateBlock(DesignLineGlyphs.Search, et) }
                    else {
                        item(key = "artist_head") { xyz.cncnlp.soundwave.ui.search.SearchSecHeader("歌手", songResults.size) }
                        items(songResults, key = { "artist:${it.source}:${it.key()}" }, contentType = { "search_artist_row" }) { s ->
                            xyz.cncnlp.soundwave.ui.search.SearchResultRow(
                                modifier = swAnimateItem(listReduceMotion),
                                cover = s.cover, title = s.name, subtitle = platformName(s.source),
                                placeholder = Icons.Outlined.Person, onClick = { onOpenSinger(s) }, circle = true
                            )
                        }
                    }
                } else if (mode == "album") {
                    val (ld, er, et) = songContent.toEmptySlot("无专辑结果")
                    if (ld) item { xyz.cncnlp.soundwave.ui.common.SkeletonRows(count = 5, coverSize = 56.dp, coverShape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    else if (er.isNotEmpty()) item { SearchErrorHint(er, onRetrySearch) }
                    else if (songResults.isEmpty()) item { StateBlock(DesignLineGlyphs.Search, et) }
                    else {
                        item(key = "album_head") { xyz.cncnlp.soundwave.ui.search.SearchSecHeader("专辑", songResults.size) }
                        items(songResults, key = { "album:${it.source}:${it.key()}" }, contentType = { "search_album_row" }) { album ->
                            xyz.cncnlp.soundwave.ui.search.SearchResultRow(
                                modifier = swAnimateItem(listReduceMotion),
                                cover = album.cover, title = album.album.ifBlank { album.name },
                                subtitle = listOf(album.singer, platformName(album.source)).filter { it.isNotBlank() }.joinToString(" · "),
                                placeholder = Icons.Outlined.Album, onClick = { onOpenAlbum(album) }
                            )
                        }
                    }
                } else {
                    val (ld, er, et) = songContent.toEmptySlot("无歌曲结果")
                    if (ld) item { xyz.cncnlp.soundwave.ui.common.SkeletonRows(count = 5, coverSize = 56.dp, coverShape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                    else if (er.isNotEmpty()) item { SearchErrorHint(er, onRetrySearch) }
                    else if (songResults.isEmpty()) item { StateBlock(DesignLineGlyphs.Search, et) }
                    else {
                        item(key = "song_head") { xyz.cncnlp.soundwave.ui.search.SearchSecHeader("歌曲", songResults.size) }
                        itemsIndexed(songResults, key = { _, song -> "song:${song.source}:${song.key()}" }, contentType = { _, _ -> "search_song_row" }) { idx, song ->
                            // v5 .row（与今日推荐 / 详情曲目行同一组件）：72dp、56dp r14 封面、DM Mono 时长 + ⋯ 统一歌曲操作抽屉。
                            // SW-12-12：结果增删时平滑让位 / 淡入（key = source + Song.key()）。
                            Box(swAnimateItem(listReduceMotion)) {
                            TodayTrackRow(
                                song = song, index = idx + 1,
                                nowPlaying = song.key() == nowPlayingKey, isPlayingNow = isPlayingNow,
                                multiMode = false, selected = false, liked = isLiked(song), showDownload = true,
                                onLike = { onLike(song) }, onAdd = { onAdd(song) }, onAddNext = { onAddNext(song) },
                                onDownload = { onDownload(song) }, onDelete = null,
                                onDislike = { onDislikeSong(song) }, onSimilar = { onSimilarSong(song) },
                                onLongClick = { recommendMoreSong = song }, showIndex = false,
                                platformLabel = if (crossPlatform) platformName(song.source) else ""
                            ) { onPlay(song) }
                            }
                        }
                    }
                }
            }
            if (!searched || kw.isBlank()) {
                val suggestedSongs = searchRecommendationPreview(recommendedSongs)
                item(key = "search_recommendations") {
                    Column(Modifier.fillMaxWidth().staggerEnter(3, searchStagger).padding(top = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // r657 截图对照：与「探索声音 / 大家都在搜」同级标题（20sp 粗体），右侧刷新为原型的 36dp 描边圆钮。
                            Text("猜你喜欢", color = onBg, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.03).em, modifier = Modifier.weight(1f).semantics { heading() })
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).border(1.dp, onBg.copy(alpha = 0.12f), CircleShape)
                                    .clickable(enabled = !recommendationLoading) { onRefreshRecommendations() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "换一组推荐歌曲", tint = onBg, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (suggestedSongs.isNotEmpty()) {
                            HomeSongListShell {
                                suggestedSongs.forEachIndexed { index, song ->
                                    SongRow(
                                        song,
                                        index = index + 1,
                                        platformSource = song.source.ifBlank { src },
                                        uniformIndexColor = true,
                                        // r654：猜你喜欢右侧去掉序号，改为三点更多按钮。
                                        showIndex = false,
                                        showActions = true,
                                        showLike = false,
                                        flat = true,
                                        showDownload = false,
                                        liked = isLiked(song),
                                        nowPlaying = song.key() == nowPlayingKey,
                                        isPlayingNow = isPlayingNow,
                                        coverScale = 1f,
                                        tight = true,
                                        onLike = { onLike(song) },
                                        onAdd = { onAdd(song) },
                                        onDownload = { onDownload(song) },
                                        // 1.0.655：猜你喜欢「⋯」与今日推荐歌曲「⋯」同一抽屉（歌曲操作）。
                                        onMore = { recommendMoreSong = song }
                                    ) { onPlay(song) }
                                }
                            }
                        }
                    }
                }
            }
            // 搜索结果可滚到公共迷你播放器与导航栏上方，避免最后一行被遮挡。
            item { Spacer(Modifier.height(dockContentBottomPadding())) }
        }
        // r656：搜索记录下拉框——仅在输入框（空闲态搜索栏）获得焦点时浮在搜索栏正下方，不挤压下方内容。
        val historyItems = remember(searchHistory, kw) { xyz.cncnlp.soundwave.ui.search.searchHistoryDropdownItems(searchHistory, kw) }
        if (fieldFocused && (!searched || kw.isBlank()) && historyItems.isNotEmpty()) {
            xyz.cncnlp.soundwave.ui.search.SearchHistoryDropdown(
                items = historyItems,
                onPick = { h ->
                    onKw(h)
                    focusManager.clearFocus()
                    onSearchFocus(false)
                    keyboardController?.hide()
                    doSearch(query = h)
                    onHistoryClick(h)
                },
                onDelete = { h -> onHistoryDelete(h) },
                onClearAll = { onHistoryClear() },
                glass = if (historyFrosted) historyGlass else null,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp).zIndex(2f)
            )
        }
        }
    }
    recommendMoreSong?.let { s ->
        val close = { recommendMoreSong = null }
        DesignTrackActionSheet(
            song = s, liked = isLiked(s), showDownload = true, canDelete = false, onDismiss = close,
            // ACCEPT4（审计 §9 C 类）：prop 只执行动作；关闭走内容侧退场句柄，状态清除由 onDismiss(close) 在退场后承担。
            onSimilar = { onSimilarSong(s) }, onLike = { onLike(s) }, onAddNext = { onAddNext(s) },
            onAdd = { onAdd(s) }, onDownload = { onDownload(s) }, onDelete = close, onDislike = { onDislikeSong(s) }
        )
    }
}

/** ACCEPT5：搜索中状态行（v8 稿口径）——EqBars + 「正在搜索「q」· 分区 · 平台」，与骨架并行呈现，不用转圈。 */
@Composable
private fun SearchBusyStatusRow(query: String, section: String, platform: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        xyz.cncnlp.soundwave.ui.common.EqBars()
        Spacer(Modifier.width(9.dp))
        Text(
            "正在搜索「$query」· $section · $platform",
            color = Muted, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
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
