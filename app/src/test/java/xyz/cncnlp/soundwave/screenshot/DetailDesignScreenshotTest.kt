package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import java.io.File

/**
 * 详情页（歌单 / 专辑 / 榜单）与排行榜、歌单广场的设计稿对照截图（part B）。
 * 仅设置 SW_SCREENSHOT_DIR 时运行；CI 单测自动跳过。渲染真实 LibraryPage 详情分支。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class DetailDesignScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS") ?: "/workspace/work/compare/covers"
    private fun cover(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    private fun setUpImages() {
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
    }

    private fun settle() {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun save(name: String) {
        settle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var ok = false
        runCatching {
            PixelCopy.request(rule.activity.window, bmp, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (!ok) view.draw(Canvas(bmp))
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val c by lazy { listOf("det0", "det1", "det2", "det3", "det4", "det5", "det6", "det7").map { cover(it) } }
    private fun song(i: Int, name: String, singer: String, album: String = "", dur: Long = 0, cv: String = c[i % 8]) =
        Song(id = "b$i$name", name = name, singer = singer, album = album, source = "wy", cover = cv, duration = dur)

    private val playlistSongs by lazy {
        listOf(
            song(0, "Glass Horizon", "Mira Vale", dur = 258_000), song(1, "Streetlight Bloom", "Low Season", dur = 222_000),
            song(2, "Folded Light", "North Cinema", dur = 267_000), song(3, "Velvet Signal", "June Arcade", dur = 302_000),
            song(4, "City Without Sleep", "Mira Vale · Leven", dur = 311_000), song(5, "Last Train Home", "Low Season", dur = 238_000),
            song(6, "海屿你", "马也_Crabbit", dur = 241_000), song(7, "如果呢", "郑润泽", dur = 205_000)
        ) + (8 until 18).map { song(it, "Night Track ${it + 1}", "Various", dur = 236_000) }
    }
    private val albumSongs by lazy {
        listOf("Glass Horizon" to 258_000L, "Voltage Bloom" to 226_000L, "City Without Sleep" to 311_000L, "Blue Transmission" to 242_000L,
            "Daybreak Relay" to 384_000L, "Neon Rain" to 201_000L, "Afterglow" to 233_000L, "Signal Lost" to 260_000L, "Current" to 199_000L)
            .mapIndexed { i, (n, d) -> song(i, n, if (i == 2) "Mira Vale · Leven" else "Mira Vale", album = "Neon Current", dur = d, cv = c[1]) }
    }
    private val chartSongs by lazy {
        listOf(
            song(0, "Glass Horizon", "Mira Vale", "Neon Current"), song(1, "Streetlight Bloom", "Low Season", "After Rain"),
            song(2, "Folded Light", "North Cinema", "Paper Moon"), song(3, "Velvet Signal", "June Arcade", "Soft Static"),
            song(4, "Daybreak Relay", "Mira Vale", "Neon Current"), song(5, "Last Train Home", "Low Season", "After Rain")
        ) + (6 until 50).map { song(it, "Chart Track ${it + 1}", "Various", "Singles") }
    }

    private val playlist = Playlist(id = "pl_night_drive", name = "Night Drive: 02 AM", cover = "", source = "wy", author = "清宵绮梦", count = 18)
    private val album = Playlist(id = "album_wy_neon", name = "Neon Current", source = "wy", author = "Mira Vale", count = 9)
    private val chart = Playlist(id = "rank_19723756", name = "上升榜", source = "wy", author = "官方榜单", description = "过去 24 小时播放增幅最快的作品", count = 50)

    private fun detail(
        pl: Playlist, songs: List<Song>, dark: Boolean = false, currentKey: String = "", fromHome: Boolean = false,
        albums: List<ArtistAlbum> = emptyList()
    ) {
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryPage(
                        bundle = LocalStore.UserBundle(), history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                        detail = pl.copy(cover = if (pl === playlist) cover("pl1") else if (pl === album) cover("det1") else cover("det0")),
                        detailSongs = songs, onOpen = {}, onBack = {}, onPlay = { _, _ -> }, onLogin = {}, onImport = {},
                        showPlaylistFavorite = true, currentKey = currentKey, detailFromHome = fromHome,
                        loadSingerAlbums = { _, _ -> albums }
                    )
                }
            }
        }
    }

    private val todayPl by lazy { Playlist(id = "today_recommendation", name = "今日推荐", cover = cover("det0"), songs = playlistSongs.toMutableList(), local = true, source = "wy", count = 18) }

    @Test @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun todayDetail() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(todayPl, playlistSongs, currentKey = playlistSongs[0].key())
        save("app-today-detail")
    }

    @Test
    fun todaySelectAll() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(todayPl, playlistSongs, currentKey = playlistSongs[0].key())
        settle()
        rule.onAllNodesWithContentDescription("多选")[0].performClick()
        settle()
        rule.onNodeWithText("全选").performClick()
        save("app-today-selectall")
    }

    @Test
    fun todayMoreSheet() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        sheetOverlay("app-st-today-more") {
            DesignTodayActionSheetContent(cover("det0"), 18, true, {}, {}, {}, {})
        }
    }

    @Test @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun playlistFromHome() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(playlist, playlistSongs, currentKey = playlistSongs[0].key(), fromHome = true)
        save("app-playlist-home")
    }

    @Test
    fun playlistSearchMode() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(playlist, playlistSongs, currentKey = playlistSongs[0].key(), fromHome = true)
        settle()
        rule.onAllNodesWithContentDescription("搜索曲目")[0].performClick()
        save("app-playlist-search")
    }

    @Test @Config(qualifiers = "w412dp-h1300dp-xhdpi")
    fun singerDetail() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        val singer = Playlist(id = "singer_wy_6452", name = "Mira Vale", source = "wy", author = "Mira Vale", remoteId = "6452", count = 18, description = "歌手")
        val albums = listOf(
            ArtistAlbum("1", "百里芭蕉百里花", c[1], "2026.08.05", 1, "wy"), ArtistAlbum("2", "声声慢", c[2], "2026.07.02", 2, "wy"),
            ArtistAlbum("3", "飞花流去", c[3], "2026.05.15", 1, "wy"), ArtistAlbum("4", "Neon Current", c[4], "2026.01.01", 9, "wy")
        )
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryPage(
                        bundle = LocalStore.UserBundle(), history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                        detail = singer.copy(cover = cover("det5")), detailSongs = playlistSongs, onOpen = {}, onBack = {}, onPlay = { _, _ -> },
                        onLogin = {}, onImport = {}, loadSingerAlbums = { _, _ -> albums }
                    )
                }
            }
        }
        save("app-singer")
    }

    @Test @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun playlistDetail() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(playlist, playlistSongs, currentKey = playlistSongs[0].key())
        save("app-playlist")
    }

    @Test @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun albumDetail() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(album, albumSongs)
        save("app-detail")
    }

    @Test @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun chartDetail() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(chart, chartSongs)
        save("app-chart-detail")
    }

    @Test @Config(qualifiers = "w412dp-h1600dp-xhdpi")
    fun playlistDetailDark() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        detail(playlist, playlistSongs, dark = true, currentKey = playlistSongs[0].key())
        save("app-playlist-dark")
    }

    /**
     * 交互态（多选 / 排序浮层）：直接组合详情页同款组件并固定状态，避免手势与弹窗窗口在 Robolectric 中不可截图；
     * 与设计稿状态图一致，工具条滚到视口 90dp 处。
     */
    private fun stateScreen(
        name: String,
        kind: DetailDesignKind,
        songs: List<Song>,
        selected: Set<Int>,
        multi: Boolean,
        sortOpen: Boolean = false,
        currentIndex: Int = -1
    ) {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var toolbarY = 0
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                scroll = androidx.compose.foundation.rememberScrollState()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        TodayDesignHeader({}, {}, false, "", {}, title = when (kind) { DetailDesignKind.Chart -> "榜单详情"; DetailDesignKind.Album -> "专辑详情"; else -> "歌单详情" })
                        Spacer(Modifier.height(20.dp))
                        when (kind) {
                            DetailDesignKind.Playlist -> PlaylistDesignHero("Night Drive: 02 AM", "Playlist · 清宵绮梦", listOf("18 tracks", "1 HR 13 MIN", "网易云"), cover("pl1"), null, false, true, true, {}, {}, {})
                            DetailDesignKind.Album -> AlbumDesignHero("Neon Current", "Album · Mira Vale · 网易云", cover("det1"), null, false, true, true, {}, {}, {})
                            DetailDesignKind.Chart -> {
                                ChartDetailDesignHead("上升榜", true, {}, {}, {})
                                ChartDetailBanner("网易云 · Top Chart", "上升榜", "过去 24 小时播放增幅最快的作品", "")
                            }
                        }
                        Box(Modifier.onGloballyPositioned { toolbarY = it.positionInParent().y.toInt() }) {
                            DetailDesignToolbar(
                                title = if (kind == DetailDesignKind.Chart) "榜单曲目" else "曲目", countLabel = "${if (kind == DetailDesignKind.Chart) 50 else songs.size} 首",
                                multiMode = multi, selectedCount = selected.size, allSelected = false, sortMode = "default", sortMenuOpen = false,
                                showDownload = true, onDownload = {}, onSortClick = {}, onSortPick = {}, onSortDismiss = {}, onEnterSelect = {}, onToggleAll = {}, onDone = {}
                            )
                        }
                        if (kind == DetailDesignKind.Album) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
                        songs.take(8).forEachIndexed { i, s ->
                            if (kind == DetailDesignKind.Chart) ChartDetailTrackRow(s, i + 1, i == currentIndex, false, multi, i in selected, false, true, {}, {}, {}, {}, null, {}, {}, {}, onClick = {})
                            else TodayTrackRow(
                                s, i + 1, i == currentIndex, false, multi, i in selected, false, true, {}, {}, {}, {}, null, {}, {}, {},
                                showIndex = kind == DetailDesignKind.Album, showCover = kind != DetailDesignKind.Album, onClick = {}
                            )
                        }
                        Spacer(Modifier.height(HomeDesignBottomInset + 400.dp))
                    }
                    if (multi) DetailSelectionActionBar(selected.isNotEmpty(), true, false, {}, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
                    if (sortOpen) Box(Modifier.align(Alignment.TopEnd).padding(top = 90.dp + 24.dp + 6.dp + 32.dp + 8.dp, end = 14.dp)) {
                        DesignSortMenuCard("default") {}
                    }
                }
            }
        }
        settle()
        val target = toolbarY - (90f * rule.activity.resources.displayMetrics.density).toInt()
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(target.coerceAtLeast(0)) } }
        save(name)
    }

    @Test fun playlistSelect() { Assume.assumeTrue(outDir() != null); setUpImages(); stateScreen("app-st-playlist-select", DetailDesignKind.Playlist, playlistSongs, setOf(1, 2), multi = true, currentIndex = 0) }
    @Test fun chartSelect() { Assume.assumeTrue(outDir() != null); setUpImages(); stateScreen("app-st-chart-select", DetailDesignKind.Chart, chartSongs, setOf(0), multi = true) }
    @Test fun albumSelect() { Assume.assumeTrue(outDir() != null); setUpImages(); stateScreen("app-st-album-select", DetailDesignKind.Album, albumSongs, setOf(0, 1), multi = true) }
    @Test fun playlistSortMenu() { Assume.assumeTrue(outDir() != null); setUpImages(); stateScreen("app-st-playlist-sort", DetailDesignKind.Playlist, playlistSongs, emptySet(), multi = false, sortOpen = true, currentIndex = 0) }

    private fun sheetOverlay(name: String, content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryPage(
                        bundle = LocalStore.UserBundle(), history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                        detail = playlist.copy(cover = cover("pl1")), detailSongs = playlistSongs, onOpen = {}, onBack = {}, onPlay = { _, _ -> },
                        onLogin = {}, onImport = {}, showPlaylistFavorite = true, currentKey = playlistSongs[0].key()
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)), contentAlignment = Alignment.BottomCenter) {
                        DesignSheetSurface(content = content)
                    }
                }
            }
        }
        save(name)
    }

    @Test
    fun trackMoreSheet() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        val s = playlistSongs[0].copy(album = "Neon Current")
        sheetOverlay("app-st-playlist-more") {
            DesignTrackActionSheetContent(s, liked = false, showDownload = true, canDelete = false, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun playlistMoreSheet() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        sheetOverlay("app-st-playlist-heromore") {
            DesignPlaylistActionSheetContent(
                "Playlist actions", "歌单操作", cover("pl1"), "Night Drive: 02 AM", "18 首 · 1 hr 14 min",
                favorited = false, showFavorite = true, showDownload = true, {}, {}, {}, {}, {}
            )
        }
    }

    private val boards by lazy {
        listOf(
            RankBoard(id = "19723756", name = "飙升榜", source = "wy", cover = c[0], count = 100, songs = chartSongs.take(3).toMutableList()),
            RankBoard(id = "3778678", name = "热歌榜", source = "wy", cover = c[1], count = 100, songs = chartSongs.take(3).toMutableList()),
            RankBoard(id = "3779629", name = "新歌榜", source = "wy", cover = c[2], count = 100, songs = chartSongs.take(3).toMutableList()),
            RankBoard(id = "2884035", name = "原创榜", source = "wy", cover = c[3], count = 100, songs = chartSongs.take(3).toMutableList())
        )
    }

    @Test @Config(qualifiers = "w412dp-h1300dp-xhdpi")
    fun charts() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppPageHeader(title = "排行榜", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26, serifTitle = true, designTitle = true, startPadding = 14.dp)
                    LazyColumn {
                        item { xyz.cncnlp.soundwave.ui.home.ChartCatalogStrip(boards, boards[0].id, androidx.compose.foundation.lazy.rememberLazyListState()) {} }
                        item { xyz.cncnlp.soundwave.ui.home.ChartHeroCard(boards[0], "网易云", onSelect = {}) {} }
                        item { xyz.cncnlp.soundwave.ui.home.ChartListHeader(false, 0, false, {}, {}, {}) }
                        itemsIndexed(chartSongs.take(8)) { i, s -> xyz.cncnlp.soundwave.ui.home.ChartRankRow(s, i + 1, i == 1) {} }
                    }
                }
            }
        }
        save("app-charts")
    }

    @Test
    fun chartSwitchSheet() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column {
                        AppPageHeader(title = "排行榜", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26, serifTitle = true, designTitle = true, startPadding = 14.dp)
                        xyz.cncnlp.soundwave.ui.home.ChartCatalogStrip(boards, boards[0].id, androidx.compose.foundation.lazy.rememberLazyListState()) {}
                        xyz.cncnlp.soundwave.ui.home.ChartHeroCard(boards[0], "网易云") {}
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)), contentAlignment = Alignment.BottomCenter) {
                        DesignSheetSurface {
                            // r655：统一设计抽屉 + 一行 3 个封面网格。
                            xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("网易云 · Charts", "选择榜单")
                            Spacer(Modifier.height(18.dp))
                            xyz.cncnlp.soundwave.ui.home.ChartPickerGrid(boards + boards.map { it.copy(id = it.id + "b", name = it.name + "二") }, boards[0].id) {}
                        }
                    }
                }
            }
        }
        save("app-st-charts-switch")
    }

    @Test @Config(qualifiers = "w412dp-h1100dp-xhdpi")
    fun chartsSelect() {
        Assume.assumeTrue(outDir() != null); setUpImages()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LazyColumn {
                        item { xyz.cncnlp.soundwave.ui.home.ChartHeroCard(boards[1], "网易云", onSelect = {}) {} }
                        item { xyz.cncnlp.soundwave.ui.home.ChartListHeader(true, 2, false, {}, {}, {}) }
                        itemsIndexed(chartSongs.take(8)) { i, s -> xyz.cncnlp.soundwave.ui.home.ChartRankRow(s, i + 1, false, multiMode = true, selected = i in setOf(0, 2)) {} }
                    }
                    DetailSelectionActionBar(true, true, false, {}, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        save("app-charts-select")
    }

    @Test @Config(qualifiers = "w412dp-h420dp-xhdpi")
    fun genreRail() {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 30.dp)) {
                    xyz.cncnlp.soundwave.ui.search.SearchGenreRail(onPick = {})
                    Box(Modifier.padding(horizontal = 14.dp)) { DetailCompactSearchField("", {}, Modifier.fillMaxWidth()) }
                }
            }
        }
        save("app-genre-rail")
    }
}
