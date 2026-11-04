package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
import xyz.cncnlp.soundwave.ui.home.ChartCatalogStrip
import xyz.cncnlp.soundwave.ui.home.ChartHeroCard
import xyz.cncnlp.soundwave.ui.home.PlaylistPlazaHero
import xyz.cncnlp.soundwave.ui.home.homePlaylistGridRows
import java.io.File

/** r656 Part A 截图（仅设置 SW_SCREENSHOT_DIR 时运行）：首页/详情顶距、排行榜头卡、歌单广场、搜索下拉 / 歌曲操作抽屉。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class R656PartAScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private fun dc(name: String) = File("/workspace/work/compare/a/dc", "$name.png").absolutePath.takeIf { File(it).exists() }
        ?: File("/workspace/work/compare/covers", "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    private fun begin() {
        Assume.assumeTrue(outDir() != null)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
    }

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1200)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var ok = false
        runCatching {
            PixelCopy.request(rule.activity.window, bmp, { r -> ok = r == PixelCopy.SUCCESS }, Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
        }
        if (!ok) view.draw(Canvas(bmp))
        drawOtherWindows(bmp, view)
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun drawOtherWindows(bmp: Bitmap, activityDecor: android.view.View) {
        val views = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(inst) as List<android.view.View>).toList()
        }.getOrDefault(emptyList())
        val canvas = Canvas(bmp)
        views.filter { it !== activityDecor && it.isShown && it.width > 0 }.forEach { v ->
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            canvas.drawColor(android.graphics.Color.argb(80, 0, 0, 0))
            canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(canvas); canvas.restore()
        }
    }

    private fun song(i: Int, name: String) = Song("s$i", name, "Mira Vale", "wy", dc("new${i % 10}"), album = "Neon Current", duration = 200_000)

    /** 首页今日推荐 vs 歌单详情封面：二者距页头均为 DetailHeroTopGap(14dp)。 */
    @Test fun heroGaps() {
        begin()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppPageHeader(title = "首页", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26, serifTitle = true, designTitle = true, startPadding = 14.dp, trailing = { HomePlatformButton {} })
                    Spacer(Modifier.height(DetailHeroTopGap))
                    androidx.compose.foundation.layout.Box(Modifier.height(190.dp)) { HomeDailyHero(List(18) { song(it, "T$it") }) {} }
                    TodayDesignHeader(onBack = {}, onSearch = {}, loggedIn = false, avatarPath = "", onLogin = {}, title = "歌单详情", showBack = true) // r657 第三轮：详情页恢复返回按钮
                    Spacer(Modifier.height(DetailHeroTopGap))
                    PlaylistDesignHero("After dark, before dawn.", "Playlist · Soundwave", listOf("18 首"), dc("cur3"), null, false, true, true, {}, {}, {})
                }
            }
        }
        save("r656a-hero-gaps")
    }

    @Test fun chart() {
        begin()
        val boards = listOf(
            RankBoard("飙升榜", "wy", id = "b1", cover = dc("ch1"), count = 100),
            RankBoard("新歌榜", "wy", id = "b2", cover = dc("ch2"), count = 100),
            RankBoard("原创榜", "wy", id = "b3", cover = dc("cur1"), count = 100),
            RankBoard("古典榜", "wy", id = "b4", cover = dc("cur2"), count = 3)
        )
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppPageHeader(title = "排行榜", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26, serifTitle = true, designTitle = true, startPadding = 14.dp, trailing = { HomePlatformButton {} })
                    ChartCatalogStrip(boards, "b1", rememberLazyListState()) {}
                    ChartHeroCard(boards[3], "网易云", onSwitch = {})
                    ChartHeroCard(boards[0], "网易云", onSwitch = {})
                }
            }
        }
        save("r656a-chart")
    }

    @Test @Config(qualifiers = "w412dp-h1300dp-xhdpi")
    fun plaza() {
        begin()
        val pls = listOf(
            Playlist("p1", "After dark, before dawn.", dc("cur3"), author = "Soundwave", count = 18, playCount = 1_509_000),
            Playlist("p2", "Night Drive: 02 AM", dc("cur1"), author = "Editor", count = 24, playCount = 382_000_000),
            Playlist("p3", "After Rain", dc("cur2"), author = "Low Season", count = 32, playCount = 9_800),
            Playlist("p4", "Glass Horizon", dc("fs2"), author = "Mira Vale", count = 12, playCount = 25_000),
            Playlist("p5", "Paper Moon", dc("debut"), author = "North Cinema", count = 40)
        )
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AppPageHeader(title = "歌单广场", loggedIn = false, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 26, serifTitle = true, designTitle = true, startPadding = 14.dp, trailing = { HomePlatformButton {} })
                    val primary = MaterialTheme.colorScheme.primary
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { PlaylistPlazaHero(pls.take(3), "网易云") {} }
                        homePlaylistGridRows(pls, "wy", "网易云", primary, Color(0xFF111511), onPlayPlaylist = {}) {}
                    }
                }
            }
        }
        // 长按网格第一张（固定显示播放键），确认右下角播放键位置与首页甄选歌单一致。
        rule.onNodeWithText("Night Drive: 02 AM").performTouchInput { longClick() }
        save("r656a-plaza")
    }

    @Test fun searchHistoryDropdown() {
        begin()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SearchPage(
                    songResults = emptyList(), plResults = emptyList(), loggedIn = false, user = "",
                    onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
                    hotKeywords = mapOf("wy" to listOf("晴天", "稻香", "夜曲", "七里香")),
                    searchHistory = listOf("Glass Horizon", "Streetlight Bloom", "Velvet Signal", "周杰伦")
                )
            }
        }
        rule.onNodeWithText("搜索歌曲、专辑、艺人…").performClick()
        save("r656a-search-dropdown")
    }

    @Test fun searchSongSheet() {
        begin()
        val songs = (0 until 6).map { song(it, "Glass Horizon $it") }
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                SearchPage(
                    songResults = songs, plResults = emptyList(), loggedIn = false, user = "", kw = "Glass", searched = true, mode = "song",
                    onLogin = {}, onSearch = { _, _, _ -> }, onPlay = {}, onOpenPl = {},
                    songContent = LoadableContent.Available(songs)
                )
            }
        }
        rule.onAllNodesWithContentDescription("更多", substring = true).onFirst().performClick()
        save("r656a-search-song-sheet")
    }
}
