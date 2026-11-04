package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
import xyz.cncnlp.soundwave.ui.home.FoldedCoverCard
import java.io.File

/**
 * 设计稿对照截图（仅本地/手动）：未设置 SW_SCREENSHOT_DIR 时全部跳过，CI 单测不受影响。
 * 渲染首页与今日推荐详情的真实 Compose 组件（与 HomePage / LibraryPage 同序组合），浅色主题，412dp 宽、xhdpi（2x）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class DesignScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS") ?: "/workspace/work/compare/covers"
    private fun cover(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    private fun setUpImages() {
        val ctx = rule.activity
        Coil.setImageLoader(
            ImageLoader.Builder(ctx).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build()
        )
    }

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
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

    private lateinit var scroll: androidx.compose.foundation.ScrollState

    /** 在 915dp 真实视口中逐屏滚动截图并拼接，避免超高窗口导致阴影光源失真。 */
    private fun saveScrolled(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        val view = rule.activity.window.decorView
        val vh = view.height
        val max = scroll.maxValue
        val total = max + vh
        val out = Bitmap.createBitmap(view.width, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var y = 0
        while (true) {
            val target = y.coerceAtMost(max)
            rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(target) } }
            repeat(3) { rule.waitForIdle(); Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
            val frame = Bitmap.createBitmap(view.width, vh, Bitmap.Config.ARGB_8888)
            var ok = false
            runCatching {
                PixelCopy.request(rule.activity.window, frame, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
                shadowOf(Looper.getMainLooper()).idle()
            }
            if (!ok) view.draw(Canvas(frame))
            canvas.drawBitmap(frame, 0f, target.toFloat(), null)
            if (target >= max) break
            y += vh
        }
        File(outDir(), "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val detCovers by lazy { (0..7).map { cover("det$it") } }
    private fun song(i: Int, name: String, singer: String, c: String = detCovers[i % 8], dur: Long = 0) =
        Song(id = "s$i$name", name = name, singer = singer, source = "wy", cover = c, duration = dur)

    private val dailySongs by lazy {
        listOf(
            song(0, "A Long Story", "Jannik", dur = 198_000), song(1, "A Story of Fate", "Jannik", dur = 223_000),
            song(2, "Despacito (cover: Luis Fonsi)", "兔裹煎蛋卷", dur = 241_000), song(3, "NIXA旋律NO.2", "兔裹煎蛋卷", dur = 176_000),
            song(4, "99°C溺亡", "无Marblue,小五沉沉沉", dur = 205_000), song(5, "The brilliance（火华）", "依旧在雨中等你", dur = 262_000),
            song(6, "Immature Rain（青涩的雨）", "依旧在雨中等你", dur = 219_000), song(7, "Something For Nothing (宿命)", "三无Marblue", dur = 247_000)
        ) + (8 until 18).map { song(it, "Daily Track ${it + 1}", "Various", dur = 200_000) }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun home() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        val hot = listOf("我不难过" to "孙燕姿", "海屿你" to "马也_Crabbit", "如果呢" to "郑润泽", "甲乙丙丁 (你我怎么两清)" to "李佳薇", "明知故犯" to "Max李玄",
            "唯一" to "告五人", "孤勇者" to "陈奕迅", "晴天" to "周杰伦", "泡沫" to "G.E.M.邓紫棋", "起风了" to "买辣椒也用券")
            .mapIndexed { i, (n, s) -> song(i + 3, n, s) }
        val news = listOf("自由的你" to "G.E.M.邓紫棋", "睡前别想太多" to "李雨霏_晚饭", "牵我的手好吗" to "吴琳珂Moske", "失物招领" to "刘学义",
            "浅粉红 baby pink" to "Gareth.T", "月声尽，心灯明" to "鸣潮先约电台,jixwang,Aki", "雪少年(山外山天外天)" to "SUS.", "浅粉红 pale pink" to "Gareth.T",
            "人生剩下的难题" to "廖俊涛", "花骨朵" to "洛天依Official,亞細亞").mapIndexed { i, (n, s) -> song(i + 5, n, s) }
        val pls = listOf(
            Triple("兔裹煎蛋卷", "清宵绮梦 · 6 首", cover("pl1")), Triple("凯尔特民谣 | 美丽的爱尔兰风笛", "云音乐官方歌单", cover("pl2")),
            Triple("拉丁舞曲 | 热情似火", "云音乐官方歌单", cover("pl3")), Triple("A Long Story", "Jannik", cover("hero"))
        )
        val digital = DiscoverCard(id = "d1", name = "DEAD:ALIVE", artist = "成韩彬", cover = cover("det7"), kind = "digital", count = 12)
        val debuts = listOf(
            DiscoverCard(id = "n1", name = "タイヤの痕と踏まれたタンポポ", artist = "まねきぼうず / 可不", cover = cover("det5"), kind = "new_album", count = 1),
            DiscoverCard(id = "n2", name = "Something For Nothing", artist = "三无Marblue", cover = cover("det7"), kind = "new_album", count = 8)
        )
        val artists = listOf("林俊杰", "陈奕迅", "孙燕姿", "薛之谦", "周深").mapIndexed { i, n -> n to detCovers[(i + 2) % 8] }
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                scroll = androidx.compose.foundation.rememberScrollState()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(scroll)) {
                    AppPageHeader(
                        title = "首页", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26,
                        serifTitle = true, designTitle = true, startPadding = 14.dp,
                        trailing = { HomePlatformButton {} }
                    )
                    Spacer(Modifier.height(20.dp))
                    HomeDailyHero(dailySongs) {}
                    HomeSectionHeader("甄选歌单", link = "View all", onTitle = {}, onLink = {})
                    LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pls) { (n, sub, c) ->
                            HomeCuratedCard(n, sub, cover = { _ -> FoldedCoverCard(c, 123.dp, MaterialTheme.colorScheme.primary, "wy", n, onClick = {}) })
                        }
                    }
                    HomeSectionHeader("热门歌曲", link = "SWIPE FOR MORE", linkSizeSp = 10f, topGap = 24.dp, onLink = {})
                    HomeHotSongsPager(hot, "wy", "", false, {}, { _, _ -> })
                    HomeFeatureCarousel(
                        active = false, currentSong = dailySongs[0], playlistCover = cover("pl1"), chartCover = cover("det0"),
                        playlistCoverBack = cover("pl2"), chartCoverBack = cover("det1"),
                        onPlaylists = {}, onCharts = {}, onHeart = {}
                    )
                    HomeSectionHeader("新歌速递", link = "New releases", topGap = 8.dp, onLink = {})
                    HomeNewSongList { news.forEachIndexed { i, s -> HomeNewSongRow(s, i + 1, "网易云", false) {} } }
                    HomeSectionHeader("数字专辑", link = "SWIPE FOR MORE", linkSizeSp = 10f)
                    LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(listOf(digital, digital.copy(id = "d2", name = "Glass Horizon", count = 1, cover = cover("det1")))) { HomeDigitalAlbumCard(it, Color(0xFFB0563C)) {} }
                    }
                    HomeSectionHeader("新碟首发", link = "New albums")
                    val pager = rememberPagerState(pageCount = { debuts.size })
                    HorizontalPager(state = pager, pageSize = PageSize.Fixed(361.dp), contentPadding = PaddingValues(start = 14.dp, end = 14.dp), pageSpacing = 14.dp, modifier = Modifier.fillMaxWidth().height(160.dp)) { i ->
                        HomeDebutCard(debuts[i], i, Color(0xFF8F8B70), Modifier.padding(bottom = 10.dp)) {}
                    }
                    HomePagerDots(debuts.size, 0, Modifier.padding(top = 2.dp))
                    HomeSectionHeader("热门歌手", link = "SWIPE FOR MORE", linkSizeSp = 10f)
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artists) { (n, a) -> HomeArtistCard(n, a) }
                    }
                    Spacer(Modifier.height(HomeDesignBottomInset))
                }
            }
        }
        saveScrolled("app-home")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun today() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                scroll = androidx.compose.foundation.rememberScrollState()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(scroll)) {
                    TodayDesignHeader(onBack = {}, onSearch = {}, loggedIn = false, avatarPath = "", onLogin = {})
                    Spacer(Modifier.height(20.dp))
                    TodayRecommendationHero(
                        songs = dailySongs, songCount = 18, cover = cover("det0").ifBlank { "" }, coverModel = null, favorited = false,
                        onToggleFavorite = {}, onPlayAll = {}, onAddAll = {}, onAddNextAll = {}, onDownloadAll = {}
                    )
                    TodayToolbar(18, multiMode = false, selectedCount = 0, allSelected = false, onDownload = {}, onSort = {}, onToggleSelect = {}, onBatch = {})
                    dailySongs.take(8).forEachIndexed { i, s ->
                        TodayTrackRow(
                            s, i + 1, nowPlaying = i == 0, isPlayingNow = false, multiMode = false, selected = false, liked = false, showDownload = true,
                            onLike = {}, onAdd = {}, onAddNext = {}, onDownload = {}, onDelete = null, onDislike = {}, onSimilar = {}, onLongClick = {}, onClick = {}
                        )
                    }
                    Spacer(Modifier.height(HomeDesignBottomInset))
                }
            }
        }
        saveScrolled("app-today")
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun todayDark() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        rule.setContent {
            SoundwaveDesignTheme(isDark = true) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    TodayDesignHeader(onBack = {}, onSearch = {}, loggedIn = false, avatarPath = "", onLogin = {})
                    Spacer(Modifier.height(20.dp))
                    TodayRecommendationHero(dailySongs, 18, cover("det0"), null, true, {}, {}, {}, {}, {})
                    TodayToolbar(18, false, 0, false, {}, {}, {}, {})
                    dailySongs.take(5).forEachIndexed { i, s ->
                        TodayTrackRow(s, i + 1, i == 0, false, false, false, false, true, {}, {}, {}, {}, null, {}, {}, {}, onClick = {})
                    }
                    HomeDailyHero(dailySongs) {}
                }
            }
        }
        save("app-today-dark")
    }
}
