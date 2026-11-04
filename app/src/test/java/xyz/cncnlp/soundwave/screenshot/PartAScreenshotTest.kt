package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
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
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.home.FoldedCoverCard
import xyz.cncnlp.soundwave.ui.home.PlaylistPlazaHero
import xyz.cncnlp.soundwave.ui.home.homePlaylistGridRows
import androidx.compose.foundation.lazy.LazyColumn
import java.io.File

/**
 * Part A 设计稿对照截图（首页剩余项 + 全局 Dock + 播放器）。仅设置 SW_SCREENSHOT_DIR 时运行，CI 跳过。
 * 封面使用设计稿内嵌图片（SW_SCREENSHOT_COVERS_A，默认 /workspace/work/compare/a/dc）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PartAScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS_A") ?: "/workspace/work/compare/a/dc"
    private fun dc(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()
    private fun old(name: String) = File("/workspace/work/compare/covers", "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    private fun setUpImages() {
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
    }

    private fun capture(): Bitmap {
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var ok = false
        runCatching {
            PixelCopy.request(rule.activity.window, bmp, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (!ok) view.draw(Canvas(bmp))
        return bmp
    }

    private fun settle() {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
    }

    private fun save(name: String) {
        settle()
        File(outDir(), "$name.png").outputStream().use { capture().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private lateinit var scroll: androidx.compose.foundation.ScrollState

    private fun saveScrolled(name: String) {
        settle()
        val view = rule.activity.window.decorView
        val vh = view.height
        val max = scroll.maxValue
        val out = Bitmap.createBitmap(view.width, max + vh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var y = 0
        while (true) {
            val target = y.coerceAtMost(max)
            rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(target) } }
            repeat(3) { rule.waitForIdle(); Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
            rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
            canvas.drawBitmap(capture(), 0f, target.toFloat(), null)
            if (target >= max) break
            y += vh
        }
        File(outDir(), "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val glass = Song(id = "glass", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000)

    @Composable
    private fun HomeTop() {
        AppPageHeader(
            title = "首页", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26,
            serifTitle = true, designTitle = true, startPadding = 14.dp,
            trailing = { HomePlatformButton {} }
        )
        Spacer(Modifier.height(20.dp))
        HomeDailyHero(List(18) { Song("d$it", "Track $it", "Artist", "wy", dc("player")) }) {}
        HomeSectionHeader("甄选歌单", link = "View all", onTitle = {}, onLink = {})
        LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(Triple("Neon Current", "Mira Vale · 2026", dc("cur1")), Triple("After Rain", "Low Season · 2025", dc("cur2")), Triple("Paper Moon", "North Cinema · 2026", dc("cur3")))) { (n, sub, c) ->
                HomeCuratedCard(n, sub, cover = { _ -> FoldedCoverCard(c, 123.dp, MaterialTheme.colorScheme.primary, "wy", n, onClick = {}) })
            }
        }
    }

    private fun dock(name: String, dark: Boolean, navStyle: String, miniStyle: String, playing: Boolean = false) {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        val progress = mutableStateOf(0.42f)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HomeTop() }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        MiniPlayer(
                            song = glass.copy(cover = dc("mini")), playing = playing, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) },
                            style = miniStyle, onClick = {}, onToggle = {}
                        )
                        Spacer(Modifier.height(if (navStyle == "capsule") 8.dp else DockMetrics.MiniGap))
                        BottomNav(Tab.HOME, dark = dark, style = navStyle) {}
                    }
                }
            }
        }
        save(name)
    }

    @Test fun dockDefault() = dock("app-dock-default", false, "default", "default")
    @Test fun dockCapsule() = dock("app-dock-capsule", false, "capsule", "capsule", playing = true)
    @Test fun dockDark() = dock("app-dock-dark", true, "default", "default", playing = true)

    @Test
    fun home() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        val news = (0 until 10).map { Song("n$it", "New Song ${it + 1}", "Artist $it", "wy", old("new$it")) }
        val hot = (0 until 10).map { Song("h$it", "Hot Song ${it + 1}", "Artist $it", "wy", old("hot${it % 5}")) }
        val debuts = listOf(
            DiscoverCard(id = "n1", name = "After Rain", artist = "Low Season", cover = dc("cur2"), kind = "new_album", count = 12),
            DiscoverCard(id = "n2", name = "Paper Moon", artist = "North Cinema", cover = dc("debut"), kind = "new_album", count = 8)
        )
        val digital = DiscoverCard(id = "d1", name = "Low Season", artist = "After Rain", cover = dc("cur2"), kind = "digital", count = 12)
        val artists = listOf("Mira Vale" to dc("cur1"), "Low Season" to dc("cur2"), "North Cinema" to dc("cur3"), "June Arcade" to dc("fs2"))
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(scroll)) {
                    HomeTop()
                    HomeSectionHeader("热门歌曲", link = "SWIPE FOR MORE", linkSizeSp = 10f, topGap = 24.dp, onLink = {})
                    HomeHotSongsPager(hot, "wy", "", false, {}, { _, _ -> })
                    HomeFeatureCarousel(
                        active = false, currentSong = glass.copy(cover = dc("heart")), playlistCover = dc("fs2"), chartCover = dc("ch2"),
                        playlistCoverBack = dc("fs1"), chartCoverBack = dc("ch1"), onPlaylists = {}, onCharts = {}, onHeart = {}
                    )
                    HomeSectionHeader("新歌速递", link = "New releases", topGap = 8.dp, onLink = {})
                    HomeNewSongList { news.forEachIndexed { i, s -> HomeNewSongRow(s, i + 1, "网易云", false) {} } }
                    HomeSectionHeader("数字专辑", link = "SWIPE FOR MORE", linkSizeSp = 10f)
                    LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(listOf(digital, digital.copy(id = "d2", cover = dc("cur1")))) { HomeDigitalAlbumCard(it, Color(0xFFB0563C)) {} }
                    }
                    HomeSectionHeader("新碟首发", link = "New albums")
                    val pager = rememberPagerState(initialPage = 1, pageCount = { debuts.size })
                    HorizontalPager(state = pager, pageSize = PageSize.Fixed(330.dp), contentPadding = PaddingValues(start = 14.dp, end = 14.dp), pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth().height(160.dp)) { i ->
                        HomeDebutCard(debuts[i], i, if (i == 1) Color(0xFF8D7872) else Color(0xFF8F8B70), Modifier.padding(bottom = 10.dp)) {}
                    }
                    HomePagerDots(4, 1, Modifier.padding(top = 2.dp))
                    HomeSectionHeader("热门歌手", link = "SWIPE FOR MORE", linkSizeSp = 10f)
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(artists) { (n, a) -> HomeArtistCard(n, a) {} }
                    }
                    Spacer(Modifier.height(HomeDesignBottomInset))
                }
            }
        }
        saveScrolled("app-home")
    }

    /** 歌单广场 → 排行榜 → 心动模式（关）→ 心动模式（开）。依赖 active=true 的 4.2s 自动轮播。 */
    @Test
    fun carousel() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        val heartOn = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    HomeFeatureCarousel(
                        active = true, currentSong = glass.copy(cover = dc("heart")), playlistCover = dc("fs2"), chartCover = dc("ch2"),
                        playlistCoverBack = dc("fs1"), chartCoverBack = dc("ch1"), onPlaylists = {}, onCharts = {}, onHeart = {},
                        heartActive = heartOn.value
                    )
                }
            }
        }
        save("app-carousel-0")
        rule.mainClock.advanceTimeBy(4400); save("app-carousel-1")
        rule.mainClock.advanceTimeBy(4400); save("app-carousel-2")
        heartOn.value = true; save("app-carousel-2-on")
    }

    /** 歌单广场页：头图（3 个推荐轮换）+ 探索歌单网格（耳机播放量、珊瑚播放键）。 */
    @Test
    fun plaza() {
        Assume.assumeTrue(outDir() != null)
        setUpImages()
        val pls = listOf(
            Playlist("p1", "After dark, before dawn.", dc("cur3"), author = "Soundwave", count = 18, playCount = 1_509_000),
            Playlist("p2", "Night Drive: 02 AM", dc("cur1"), author = "Editor", count = 24, playCount = 382_000_000),
            Playlist("p3", "After Rain", dc("cur2"), author = "Low Season", count = 32, playCount = 9_800),
            Playlist("p4", "Glass Horizon Collection With A Very Long Title", dc("fs2"), author = "Mira Vale", count = 12, playCount = 25_000),
            Playlist("p5", "Paper Moon", dc("debut"), author = "North Cinema", count = 40, playCount = 0)
        )
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppPageHeader(
                        title = "歌单广场", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26,
                        serifTitle = true, designTitle = true, startPadding = 14.dp, trailing = { HomePlatformButton {} }
                    )
                    val primary = MaterialTheme.colorScheme.primary
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { PlaylistPlazaHero(pls.take(3), "网易云") {} }
                        homePlaylistGridRows(pls, "wy", "网易云", primary, Color(0xFF111511)) {}
                    }
                }
            }
        }
        save("app-plaza")
    }
    // 1.0.655：旧 StandardPlayerPage / DesignLyricsPage 已删除；新「默认」播放器截图见 DefaultPlayerScreenshotTest。
}
