package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.BottomNav
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.MiniPlayer
import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.Tab
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.SpiderRefreshIndicator
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerActions
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerBackdrop
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerRoute
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerState
import java.io.File
import kotlin.math.abs

/**
 * r657 截图：
 *  - 底栏回归（v4 原型胶囊组完全透明）：两种底栏样式 × 浅 / 深 × 「底栏与迷你条磨砂」开 / 关，页面「透明磨砂背景」始终打开，
 *    背后铺高对比条纹；逐像素采样胶囊组 / 通栏 / 迷你条底色，断言关闭时 = 实色表面、打开时仍以表面色为主（≥80%）；
 *  - 默认播放器（主页 / 歌词页，浅 / 深，默认背景与磨砂取色）；
 *  - 蜘蛛下拉刷新（下拉中 / 到位 / 刷新中，浅 / 深）。
 * 仅设置 SW_SCREENSHOT_DIR 时运行，CI 跳过。封面取 SW_SCREENSHOT_COVERS_A（默认 /workspace/work/compare/a/dc）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class R657ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS_A") ?: "/workspace/work/compare/a/dc"
    private fun dc(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    @After fun reset() = FrostedPrefs.resetForTest()

    private fun capture(name: String): Bitmap {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun loader() = Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())

    private val glass = Song(id = "glass", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000)

    // ───────────── 底栏 / 迷你条回归 ─────────────

    private fun dock(style: String, dark: Boolean, dockFrosted: Boolean) {
        Assume.assumeTrue(outDir() != null)
        loader()
        FrostedPrefs.resetForTest(page = true, tint = true, dock = dockFrosted)
        val progress = mutableFloatStateOf(0.42f)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // 背后高对比内容：红 / 蓝条纹 + 文字，任何透出都会在采样里体现。
                    Column(Modifier.fillMaxSize()) {
                        repeat(40) { i ->
                            Row(Modifier.fillMaxWidth().height(24.dp).background(if (i % 2 == 0) Color(0xFFFF0000) else Color(0xFF0000FF))) {
                                Text("列表内容 $i 列表内容 列表内容", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        Box(Modifier.testTag("mini")) {
                            MiniPlayer(glass.copy(cover = dc("player")), playing = true, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onClick = {}, onToggle = {}, style = style)
                        }
                        Spacer(Modifier.height(if (style == "capsule") 8.dp else 14.dp))
                        Box(Modifier.testTag("nav")) { BottomNav(Tab.HOME, dark = dark, style = style) {} }
                    }
                }
            }
        }
        val name = "r657-dock-$style-${if (dark) "dark" else "light"}-frost-${if (dockFrosted) "on" else "off"}"
        val bmp = capture(name)
        val surface = if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface
        val nav = rule.onNodeWithTag("nav").fetchSemanticsNode().boundsInWindow
        val mini = rule.onNodeWithTag("mini").fetchSemanticsNode().boundsInWindow
        val density = rule.activity.resources.displayMetrics.density
        println("DOCK_BOUNDS $name bitmap=${bmp.width}x${bmp.height} nav=$nav mini=$mini density=$density")
        val samples = mutableListOf<Pair<String, Pair<Int, Int>>>()
        if (style == "capsule") {
            // 胶囊组：避开首页选中色、图标、搜索描边和播放按钮，采样真实底材（选中首页圆底之外）的胶囊表面；本体在系统导航栏之上、底部 8dp 外距。
            val pillLeft = nav.left + 10 * density
            val pillWidth = nav.width - (10 + 10 + 18 + 60) * density
            val bodyBottom = nav.bottom - 8 * density
            val y = (bodyBottom - 30 * density).toInt()
            samples += "capsule-group" to ((pillLeft + pillWidth * 0.48f).toInt() to y)
            samples += "capsule-group-2" to ((pillLeft + pillWidth * 0.78f).toInt() to y)
            samples += "search-circle-edge" to ((nav.right - 40 * density).toInt() to (y - 20 * density).toInt())
        } else {
            samples += "default-bar" to ((nav.left + nav.width / 8f).toInt() to (nav.top + 4 * density).toInt())
            samples += "default-bar-2" to ((nav.left + nav.width * 3f / 8f).toInt() to (nav.top + 4 * density).toInt())
        }
        samples += "mini-bar" to ((mini.right - 100 * density).toInt() to ((mini.top + mini.bottom) / 2f).toInt())
        samples.forEach { (label, xy) ->
            val px = bmp.getPixel(xy.first.coerceIn(0, bmp.width - 1), xy.second.coerceIn(0, bmp.height - 1))
            val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
            val sr = (surface.red * 255).toInt(); val sg = (surface.green * 255).toInt(); val sb = (surface.blue * 255).toInt()
            val diff = maxOf(abs(r - sr), abs(g - sg), abs(b - sb))
            val limit = if (dockFrosted) 70 else 8 // 关闭：实色；打开：≥80% 表面色 + 至多 20% 背后内容 / 取色
            assertTrue("$name $label shows content through the dock: rgb($r,$g,$b) vs surface rgb($sr,$sg,$sb) diff=$diff", diff <= limit)
        }
    }

    @Test fun dockCapsuleLightOff() = dock("capsule", false, false)
    @Test fun dockCapsuleLightOn() = dock("capsule", false, true)
    @Test fun dockCapsuleDarkOff() = dock("capsule", true, false)
    @Test fun dockCapsuleDarkOn() = dock("capsule", true, true)
    @Test fun dockDefaultLightOff() = dock("default", false, false)
    @Test fun dockDefaultLightOn() = dock("default", false, true)
    @Test fun dockDefaultDarkOff() = dock("default", true, false)
    @Test fun dockDefaultDarkOn() = dock("default", true, true)

    // ───────────── 默认播放器 / 歌词页 ─────────────

    private fun playerState(heart: Boolean = false): DefaultPlayerState {
        val lines = listOf(
            "Neon rivers under midnight" to "", "City lights dissolve into the blue" to "", "Static on the avenue" to "",
            "Every signal leads me back to you" to "每一道信号，都将我带回你身边", "Through the glass horizon, we move" to "",
            "Holding on to something new" to "", "Echoes fading out of view" to "", "Every signal leads me back to you" to ""
        ).mapIndexed { i, (t, tr) -> LyricLine(timeMs = i * 6000L, text = t, translation = tr) }
        val song = glass.copy(cover = dc("player"))
        val queue = listOf(
            song, Song("v", "Voltage Bloom", "Low Season", "wy", dc("up1")), Song("w", "Rain on Glass", "Low Season", "wy", dc("up2")),
            Song("a", "After dark, before dawn", "Soundwave", "wy", dc("cur3"))
        )
        return DefaultPlayerState(
            song, queue, 0, playing = true, resolving = false, status = "", liked = true,
            playMode = if (heart) PlayMode.HEART else PlayMode.SEQUENCE,
            progress = 0.42f, displayTime = 108f, duration = 258f, quality = "FLAC · 44.1 kHz", volume = 0.6f, showVolume = false,
            lyrics = lines, lyricTimeMs = 21_000L, showTranslation = true, sourceLabel = if (heart) "" else "今日推荐"
        )
    }

    private val noop = DefaultPlayerActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, {}
    )

    private fun player(dark: Boolean, lyrics: Boolean, frosted: Boolean, heart: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        loader()
        FrostedPrefs.resetForTest(page = frosted, tint = true)
        val s = playerState(heart)
        FrostedPrefs.cover = s.song.cover
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize()) {
                    DefaultPlayerBackdrop(s.song.cover, Modifier.matchParentSize())
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        DefaultPlayerRoute(lyrics, false, s, noop, rememberLazyListState(), remember { MutableTransitionState(false) }, Modifier.weight(1f))
                    }
                }
            }
        }
        capture(name)
    }

    @Test fun playerLight() = player(false, false, false, false, "r657-player-light")
    @Test fun playerDark() = player(true, false, false, false, "r657-player-dark")
    @Test fun playerHeartLight() = player(false, false, false, true, "r657-player-heart-light")
    @Test fun playerFrostedLight() = player(false, false, true, false, "r657-player-frosted-light")
    @Test fun lyricsLight() = player(false, true, false, false, "r657-lyrics-light")
    @Test fun lyricsDark() = player(true, true, false, false, "r657-lyrics-dark")

    // ───────────── 蜘蛛下拉刷新 ─────────────

    private fun spider(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    listOf(0.35f to false, 1f to false, 1f to true).forEach { (pull, refreshing) ->
                        Box(Modifier.weight(1f).height(140.dp)) {
                            SpiderRefreshIndicator(pull, refreshing, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, reduceMotion = true)
                        }
                    }
                }
            }
        }
        capture(name)
    }

    @Test fun spiderLight() = spider(false, "r657-spider-light")
    @Test fun spiderDark() = spider(true, "r657-spider-dark")
}
