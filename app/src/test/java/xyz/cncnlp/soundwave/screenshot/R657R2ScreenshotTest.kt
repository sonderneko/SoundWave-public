package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.BottomNav
import xyz.cncnlp.soundwave.LibraryPage
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.MiniPlayer
import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.Playlist
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.Tab
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.GlassHost
import xyz.cncnlp.soundwave.ui.common.LocalDockContentInset
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerActions
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerBackdrop
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerRoute
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerState
import xyz.cncnlp.soundwave.ui.player.DetailTransitionMiniDock
import xyz.cncnlp.soundwave.ui.player.PlayerQualitySheetContent
import java.io.File

/**
 * r657 第二轮验证截图（磨砂开 / 关 × 浅 / 深 × 360 / 411）。派生自 R657FixScreenshotTest。
 * r657 修复验证截图：音乐库 / 歌单详情（静止 + 滚动后）/ 默认播放器，360dp 与 411dp × 浅 / 深 × 「底栏与迷你条磨砂」开 / 关。
 * 页面挂真实 LibraryPage / DefaultPlayerRoute，迷你条 + 底栏按 AppRoot 结构叠在内容根（GlassHost 模糊源）之上。
 * 用 PixelCopy（robolectric.pixelCopyRenderMode=hardware）截图，让背景模糊走硬件画布。仅设置 SW_SCREENSHOT_DIR 时运行。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class R657R2ScreenshotBase(private val widthTag: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private fun cover(name: String): String {
        val a = File(System.getenv("SW_SCREENSHOT_COVERS") ?: "/workspace/work/compare/covers", "$name.png")
        val b = File(System.getenv("SW_SCREENSHOT_COVERS_A") ?: "/workspace/work/compare/a/dc", "$name.png")
        return (if (a.exists()) a else b).takeIf { it.exists() }?.absolutePath.orEmpty()
    }

    @After fun reset() = FrostedPrefs.resetForTest()

    /** 本轮各截图是否打开「透明磨砂背景」（页面层封面模糊）。 */
    protected var pf = false

    /** 暂停自动推进时钟：黑胶 / 胶囊封面等无限动画不会让 waitForIdle 卡死，时间由 settle() 手动推进。 */
    private fun loader() = run { rule.mainClock.autoAdvance = false; loader0() }
    private fun loader0() = Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())

    private fun settle() {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle(); rule.mainClock.advanceTimeBy(300) }
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
        File(outDir(), "r2-$name-$widthTag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val covers by lazy { (0..7).map { cover("det$it") } }
    private fun song(i: Int, name: String, singer: String, dur: Long = 236_000) =
        Song(id = "f$i", name = name, singer = singer, source = "wy", cover = covers[i % 8], duration = dur, album = "Album")

    private val nowPlaying by lazy { Song(id = "glass", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000, cover = cover("player")) }

    private fun bundle(): LocalStore.UserBundle {
        val favs = (0 until 67).map { song(it, "Liked $it", "Artist", dur = 236_000) }
        fun pl(id: String, name: String, n: Int, c: Int) = Playlist(
            id = id, name = name, cover = covers[c], songs = (0 until n).map { song(it, "T$it", "A") }.toMutableList(), source = "wy", count = n
        )
        return LocalStore.UserBundle(
            favs = favs.toMutableList(),
            playlists = mutableListOf(
                Playlist("liked", "我的喜欢", local = true, songs = favs.toMutableList()),
                Playlist("local", "本地歌单", local = true),
                pl("imp1", "我的歌单", 177, 3), pl("imp2", "纯音乐", 525, 1), pl("imp3", "电流经过夏天", 32, 0), pl("imp4", "柔软噪点", 16, 2)
            )
        )
    }

    private val playlistSongs by lazy {
        listOf(
            song(0, "Threnody", "Goldmund"), song(1, "Kinderscene op. 15", "Robert Schumann"), song(2, "Après un rêve, Op. 7, No. 1", "Gabriel Fauré"),
            song(3, "Velvet Signal", "June Arcade"), song(4, "City Without Sleep", "Mira Vale"), song(5, "Last Train Home", "Low Season"),
            song(6, "海屿你", "马也_Crabbit"), song(7, "如果呢", "郑润泽")
        ) + (8 until 30).map { song(it, "Night Track ${it + 1}", "Various") }
    }

    /** 与 AppRoot 同构：内容根（模糊源）+ Scaffold bottomBar 位置的迷你条 / 底栏。 */
    @Composable
    private fun Root(dark: Boolean, detailDock: Boolean, tab: Tab = Tab.PLAYLISTS, content: @Composable () -> Unit) {
        val progress = remember { mutableFloatStateOf(0.42f) }
        SoundwaveDesignTheme(isDark = dark) {
            val inset = DockMetrics.BarNavHeight + DockMetrics.MiniHeight + DockMetrics.MiniGap + DockMetrics.ContentExtra
            androidx.compose.runtime.CompositionLocalProvider(LocalDockContentInset provides inset) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().then(GlassHost.source)) {
                        if (pf) xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop(Modifier.fillMaxSize(), dark)
                        content()
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        if (detailDock) {
                            DetailTransitionMiniDock(nowPlaying, true, progress, androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, "", {}, {}, {}, {}, {})
                        } else {
                            MiniPlayer(nowPlaying, playing = true, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onClick = {}, onToggle = {})
                            xyz.cncnlp.soundwave.ui.common.DockMiniGap(capsule = false, dark = dark)
                            BottomNav(tab, dark = dark) {}
                        }
                    }
                }
            }
        }
    }

    private fun library(dark: Boolean, dock: Boolean) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true, dock = dock); FrostedPrefs.cover = cover("pl1")
        val b = bundle()
        rule.setContent {
            Root(dark, detailDock = false) {
                LibraryPage(
                    bundle = b, history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                    detail = null, detailSongs = emptyList(), onOpen = {}, onBack = {}, onPlay = { _, _ -> },
                    onLogin = {}, onImport = {}, playingNow = true, currentKey = b.favs[0].key()
                )
            }
        }
        save("library-${if (dark) "dark" else "light"}${fz()}")
    }

    private fun detail(dark: Boolean, dock: Boolean, scrolled: Boolean, pageFrost: Boolean = false) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pageFrost || pf, tint = true, dock = dock)
        FrostedPrefs.cover = cover("pl1")
        val pl = Playlist(id = "pl_wy_night", name = "晚安小夜曲 | 就让今天在轻柔的音乐里结束吧", cover = cover("pl1"), source = "wy", author = "云音乐官方歌单", count = 78)
        rule.setContent {
            Root(dark, detailDock = true) {
                LibraryPage(
                    bundle = LocalStore.UserBundle(), history = emptyList(), downloads = emptyList(), loggedIn = false, user = "",
                    detail = pl, detailSongs = playlistSongs, onOpen = {}, onBack = {}, onPlay = { _, _ -> }, onLogin = {}, onImport = {},
                    showPlaylistFavorite = true, detailFromHome = true
                )
            }
        }
        if (scrolled) {
            settle()
            runCatching { rule.onNode(hasScrollAction()).performScrollToIndex(3) }
        }
        save("detail${if (scrolled) "-scrolled" else ""}-${if (dark) "dark" else "light"}${fz()}")
    }

    private val noop = DefaultPlayerActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, {}
    )

    private fun player(dark: Boolean, heart: Boolean, queueOpen: Boolean = false) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true)
        val lines = listOf("Neon rivers in the night", "Holding on to something new").mapIndexed { i, t -> LyricLine(timeMs = i * 6000L, text = t, translation = "") }
        val queue = listOf(nowPlaying, Song("v", "Voltage Bloom", "Low Season", "wy", cover("up1")), Song("w", "Rain on Glass", "Low Season", "wy", cover("up2")))
        val s = DefaultPlayerState(
            nowPlaying.copy(name = "我很明月不照我"), queue, 0, playing = true, resolving = false, status = "", liked = heart,
            playMode = if (heart) PlayMode.HEART else PlayMode.SEQUENCE,
            progress = 0.42f, displayTime = 109f, duration = 258f, quality = "FLAC · 44.1 kHz", volume = 0.6f, showVolume = false,
            lyrics = lines, lyricTimeMs = 6_000L, showTranslation = false, sourceLabel = if (heart) "" else "今日推荐"
        )
        FrostedPrefs.cover = s.song.cover
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize()) {
                    DefaultPlayerBackdrop(s.song.cover, Modifier.matchParentSize())
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        DefaultPlayerRoute(false, queueOpen, s, noop, rememberLazyListState(), remember { MutableTransitionState(queueOpen) }, Modifier.weight(1f))
                    }
                }
            }
        }
        save("player${if (heart) "-heart" else ""}${if (queueOpen) "-queue" else ""}-${if (dark) "dark" else "light"}${fz()}")
    }

    /** 背后铺高对比条纹 + 文字：磨砂开时迷你条 / 底栏应是模糊后的色块（或 94% 实色），绝不能透出清晰文字。 */
    private fun stripes(dark: Boolean, dock: Boolean, capsule: Boolean) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true, dock = dock); FrostedPrefs.cover = cover("pl1")
        val progress = mutableFloatStateOf(0.42f)
        val style = if (capsule) "capsule" else "default"
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxSize().then(GlassHost.source)) {
                        repeat(40) { i ->
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(24.dp).background(if (i % 2 == 0) androidx.compose.ui.graphics.Color(0xFFFF0000) else androidx.compose.ui.graphics.Color(0xFF0000FF))) {
                                androidx.compose.material3.Text("列表内容 $i 透出检测 Après un rêve", color = androidx.compose.ui.graphics.Color.White, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        MiniPlayer(nowPlaying, playing = true, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onClick = {}, onToggle = {}, style = style)
                        Spacer(Modifier.height(if (capsule) 8.dp else DockMetrics.MiniGap))
                        BottomNav(Tab.HOME, dark = dark, style = style) {}
                    }
                }
            }
        }
        save("stripes-$style-${if (dark) "dark" else "light"}-dock${if (dock) "on" else "off"}")
    }


    // ---- r657 修复第二轮：首页 / 搜索 / 设置 / 播放历史 / 抽屉，与原型同屏对照 ----
    private fun tagOf(dark: Boolean) = if (dark) "dark" else "light"
    private fun fz() = if (pf) "-frost" else ""

    private fun pageShot(name: String, dark: Boolean, tab: Tab, content: @Composable () -> Unit) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true, dock = false); FrostedPrefs.cover = cover("pl1")
        rule.setContent { Root(dark, detailDock = false, tab = tab) { content() } }
        save("$name-${tagOf(dark)}${fz()}")
    }

    private fun home(dark: Boolean) {
        val hot = listOf("Glass Horizon" to "Mira Vale", "Streetlight Bloom" to "Low Season", "Folded Light" to "North Cinema", "Velvet Signal" to "June Arcade", "City Without Sleep" to "Mira Vale")
            .mapIndexed { i, (n, a) -> song(i, n, a) }
        val pls = listOf("Night Drive: 02 AM" to 120000, "After Rain" to 9800, "Paper Moon" to 25000, "雨落之后" to 4100)
            .mapIndexed { i, (n, c) -> Playlist(id = "hp$i", name = n, cover = covers[(i + 3) % 8], source = "wy", playCount = c.toLong(), author = "Editor") }
        // 今日推荐英雄卡：预置本期缓存（与原型同一张封面），否则夹具没有日推/热歌榜只能截到兜底纯墨色。
        run {
            val ctx = rule.activity
            val daily = listOf(nowPlaying) + hot
            xyz.cncnlp.soundwave.HomeFeedCache.saveDetail(ctx, xyz.cncnlp.soundwave.TodayRecommendationSlot.cacheId("wy"), "wy", daily)
            xyz.cncnlp.soundwave.HomePageStateStore.putString(ctx, xyz.cncnlp.soundwave.TodayRecommendationSlot.slotStateKey("wy"),
                xyz.cncnlp.soundwave.TodayRecommendationSlot.slotKey(System.currentTimeMillis()))
        }
        pageShot("home", dark, Tab.HOME) {
            xyz.cncnlp.soundwave.HomePage(
                feedState = xyz.cncnlp.soundwave.HomeFeedRenderState(
                    hot = hot, ranks = emptyList(), plReco = pls,
                    hotContent = xyz.cncnlp.soundwave.LoadableContent.Available(hot), plContent = xyz.cncnlp.soundwave.LoadableContent.Available(pls)
                ),
                history = hot, loggedIn = false, user = "", platform = "wy", onPlatform = {}, onLogin = {},
                onPlay = { _, _ -> }, onOpenPl = {}, currentSong = nowPlaying, nowPlayingKey = nowPlaying.key(), isPlayingNow = true
            )
        }
    }

    private fun searchGenre(dark: Boolean) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true, dock = false); FrostedPrefs.cover = cover("pl1")
        rule.setContent { Root(dark, detailDock = false, tab = Tab.SEARCH) { searchContent() } }
        settle()
        runCatching { rule.onNode(hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("探索声音", substring = true)) }
        save("search-genre-${tagOf(dark)}${fz()}")
    }

    @Composable private fun searchContent() = xyz.cncnlp.soundwave.SearchPage(
            songResults = emptyList(), plResults = emptyList(), loggedIn = false, user = "",
            onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
            hotKeywords = mapOf("wy" to listOf("失眠飞行", "City Pop", "雨天歌单", "Mira Vale", "深夜氛围", "新碟首发")),
            searchHistory = listOf("Glass Horizon", "Streetlight Bloom"),
            recommendedSongs = listOf(song(0, "Glass Horizon", "Mira Vale"), song(1, "Streetlight Bloom", "Low Season"), song(2, "Velvet Signal", "June Arcade"))
        )

    private fun search(dark: Boolean) = pageShot("search", dark, Tab.SEARCH) {
        xyz.cncnlp.soundwave.SearchPage(
            songResults = emptyList(), plResults = emptyList(), loggedIn = false, user = "",
            onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
            hotKeywords = mapOf("wy" to listOf("失眠飞行", "City Pop", "雨天歌单", "Mira Vale", "深夜氛围", "新碟首发")),
            searchHistory = listOf("Glass Horizon", "Streetlight Bloom"),
            recommendedSongs = listOf(song(0, "Glass Horizon", "Mira Vale"), song(1, "Streetlight Bloom", "Low Season"), song(2, "Velvet Signal", "June Arcade"))
        )
    }

    private fun hub(sub: String?, dark: Boolean) {
        val now = System.currentTimeMillis()
        val hist = listOf("Glass Horizon" to "Mira Vale", "Streetlight Bloom" to "Low Season", "Folded Light" to "North Cinema", "Velvet Signal" to "June Arcade", "City Without Sleep" to "Mira Vale")
            .mapIndexed { i, (n, a) -> song(i, n, a).copy(playedAt = now - (if (i < 4) i * 600_000L else 26 * 3_600_000L)) }
        pageShot(if (sub == null) "settings" else sub, dark, Tab.SETTINGS) {
            xyz.cncnlp.soundwave.SettingsHub(
                settingsSub = sub, serverUrl = "", preferQ = "flac", loggedIn = true, user = "Amanda", status = "", sleepRemain = 30 * 60_000L,
                scripts = emptyList(), onUrl = {}, onQuality = {}, onSleep = {},
                onScriptsChange = {}, onImportCode = {}, themeMode = if (dark) "night" else "light",
                webDavBundle = LocalStore.UserBundle(), onWebDavBundleApplied = {}, historySongs = hist, playSeconds = 12 * 3600L + 1800L
            )
        }
    }

    private fun sheet(name: String, dark: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Assume.assumeTrue(outDir() != null); loader()
        FrostedPrefs.resetForTest(page = pf, tint = true, dock = false); FrostedPrefs.cover = cover("pl1")
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (pf) xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop(Modifier.fillMaxSize(), dark)
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.BottomCenter) {
                        xyz.cncnlp.soundwave.ui.common.DesignSheetPanel(content = content)
                    }
                }
            }
        }
        save("sheet-$name-${tagOf(dark)}${fz()}")
    }

    private fun sheetActions(dark: Boolean) = sheet("actions", dark) {
        xyz.cncnlp.soundwave.DesignTrackActionSheetContent(nowPlaying.copy(actualQuality = "flac"), liked = false, showDownload = true, canDelete = false, {}, {}, {}, {}, {}, {}, {})
    }
    private fun sheetQuality(dark: Boolean) = sheet("quality", dark) {
        PlayerQualitySheetContent(nowPlaying, "flac24bit", "flac", onPick = {})
    }
    private fun sheetDevice(dark: Boolean) = sheet("device", dark) {
        val tv = xyz.cncnlp.soundwave.DlnaDevice("客厅电视", "http://192.168.1.20/desc.xml", "")
        xyz.cncnlp.soundwave.ui.player.PlayerDeviceSheetContent(
            xyz.cncnlp.soundwave.ui.player.PlayerSystemOutputSnapshot<android.media.MediaRouter.RouteInfo>(emptyList(), null, null),
            xyz.cncnlp.soundwave.ui.player.PlayerDlnaPanelSnapshot(listOf(tv), false, null),
            {}, {}, {}, {}, {}, {}, {}, {}, songTitle = nowPlaying.name, songCover = nowPlaying.cover
        )
    }


    /** 蜘蛛下拉刷新逐帧：下拉 30% / 70% / 100%（阈值）/ 松手回弹冲过头 / 刷新中 / 完成爬回中。 */
    private fun spiderFrames(dark: Boolean) {
        Assume.assumeTrue(outDir() != null); loader()
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                val cs = MaterialTheme.colorScheme
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(cs.background)) {
                    @Composable fun cell(content: @Composable () -> Unit) = Box(Modifier.weight(1f).fillMaxSize()) { content() }
                    listOf(0.3f, 0.7f, 1f).forEach { p ->
                        cell { xyz.cncnlp.soundwave.ui.common.SpiderRefreshIndicator(p, false, cs.onSurface, cs.primary, reduceMotion = true, muted = cs.onSurfaceVariant) }
                    }
                    cell { xyz.cncnlp.soundwave.ui.common.SpiderRefreshFrame(63f, cs.onSurface, cs.primary, cs.onSurfaceVariant, "正在刷新…", sway = 7f) }
                    cell { xyz.cncnlp.soundwave.ui.common.SpiderRefreshIndicator(1f, true, cs.onSurface, cs.primary, reduceMotion = true, muted = cs.onSurfaceVariant) }
                    cell { xyz.cncnlp.soundwave.ui.common.SpiderRefreshFrame(22f, cs.onSurface, cs.primary, cs.onSurfaceVariant, "已是最新内容") }
                }
            }
        }
        save("spider-${tagOf(dark)}")
    }
    // ---- r657 第二轮：每页 × 浅 / 深 × 磨砂关 / 开 ----
    private fun both(dark: Boolean, frost: Boolean, f: () -> Unit) { pf = frost; f() }
    @Test fun detailLight() = both(false, false) { detail(false, false, false) }
    @Test fun detailDark() = both(true, false) { detail(true, false, false) }
    @Test fun detailLightFrost() = both(false, true) { detail(false, true, false) }
    @Test fun detailDarkFrost() = both(true, true) { detail(true, true, false) }
    @Test fun detailScrolledLight() = both(false, false) { detail(false, false, true) }
    @Test fun detailScrolledDark() = both(true, false) { detail(true, false, true) }
    @Test fun detailScrolledLightFrost() = both(false, true) { detail(false, true, true) }
    @Test fun detailScrolledDarkFrost() = both(true, true) { detail(true, true, true) }
    @Test fun playerLight() = both(false, false) { player(false, false) }
    @Test fun playerDark() = both(true, false) { player(true, false) }
    @Test fun playerLightFrost() = both(false, true) { player(false, false) }
    @Test fun playerDarkFrost() = both(true, true) { player(true, false) }
    @Test fun settingsLight() = both(false, false) { hub(null, false) }
    @Test fun settingsDark() = both(true, false) { hub(null, true) }
    @Test fun settingsLightFrost() = both(false, true) { hub(null, false) }
    @Test fun settingsDarkFrost() = both(true, true) { hub(null, true) }
    @Test fun libraryLight() = both(false, false) { library(false, false) }
    @Test fun libraryDark() = both(true, false) { library(true, false) }
    @Test fun libraryLightFrost() = both(false, true) { library(false, true) }
    @Test fun libraryDarkFrost() = both(true, true) { library(true, true) }
    @Test fun searchGenreLight() = both(false, false) { searchGenre(false) }
    @Test fun searchGenreDark() = both(true, false) { searchGenre(true) }
    @Test fun searchGenreLightFrost() = both(false, true) { searchGenre(false) }
    @Test fun searchGenreDarkFrost() = both(true, true) { searchGenre(true) }
    @Test fun sheetQualityLight() = both(false, false) { sheetQuality(false) }
    @Test fun sheetQualityDark() = both(true, false) { sheetQuality(true) }
    @Test fun sheetActionsLightFrost() = both(false, true) { sheetActions(false) }
    @Test fun sheetActionsDarkFrost() = both(true, true) { sheetActions(true) }
    // ---- r657 第三轮：设计稿逐屏核对补充 ----
    @Test fun homeLight() = both(false, false) { home(false) }
    @Test fun homeDark() = both(true, false) { home(true) }
    @Test fun homeLightFrost() = both(false, true) { home(false) }
    @Test fun searchLight() = both(false, false) { search(false) }
    @Test fun searchDark() = both(true, false) { search(true) }
    @Test fun historyLight() = both(false, false) { hub("history", false) }
    @Test fun historyDark() = both(true, false) { hub("history", true) }
    @Test fun downloadsLight() = both(false, false) { hub("downloads", false) }
    @Test fun subThemeLight() = both(false, false) { hub("theme", false) }
    @Test fun subDockLight() = both(false, false) { hub("dock", false) }
    @Test fun subLyricsDark() = both(true, false) { hub("lyrics", true) }
    @Test fun playerHeartLight() = both(false, false) { player(false, true) }
    @Test fun playerQueueLight() = both(false, false) { player(false, false, queueOpen = true) }
    @Test fun playerQueueDark() = both(true, false) { player(true, false, queueOpen = true) }
    @Test fun sheetActionsLight() = both(false, false) { sheetActions(false) }
    @Test fun sheetActionsDark() = both(true, false) { sheetActions(true) }
    @Test fun sheetDeviceLight() = both(false, false) { sheetDevice(false) }
    @Test fun sheetQualityLightFrost() = both(false, true) { sheetQuality(false) }
}

@Config(sdk = [34], qualifiers = "w360dp-h780dp-xhdpi")
class R657R2Screenshot360Test : R657R2ScreenshotBase("360")

@Config(sdk = [34], qualifiers = "w411dp-h860dp-xhdpi")
class R657R2Screenshot411Test : R657R2ScreenshotBase("411")
