package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import xyz.cncnlp.soundwave.BottomNav
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.MiniPlayer
import xyz.cncnlp.soundwave.PlayMode
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.Tab
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerActions
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerBackdrop
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerRoute
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerState
import java.io.File

/**
 * 1.0.655「默认」播放器（主页 / 歌词页 / 队列，浅色 + 深色）与圆角胶囊底栏 + 迷你条截图。
 * 仅设置 SW_SCREENSHOT_DIR 时运行，CI 跳过。封面取 SW_SCREENSHOT_COVERS_A（默认 /workspace/work/compare/a/dc）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class DefaultPlayerScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS_A") ?: "/workspace/work/compare/a/dc"
    private fun dc(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val glass = Song(id = "glass", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000)

    private fun state(): DefaultPlayerState {
        val lines = listOf(
            "Neon rivers under midnight" to "", "City lights dissolve into the blue" to "", "Static on the avenue" to "",
            "Every signal leads me back to you" to "每一道信号，都将我带回你身边", "Through the glass horizon, we move" to "",
            "Holding on to something new" to "", "Echoes fading out of view" to "", "Every signal leads me back to you" to ""
        ).mapIndexed { i, (t, tr) -> LyricLine(timeMs = i * 6000L, text = t, translation = tr) }
        val song = glass.copy(cover = dc("player"))
        val queue = listOf(
            song, Song("v", "Voltage Bloom", "Low Season", "wy", dc("up1")), Song("w", "Rain on Glass", "Low Season", "wy", dc("up2")),
            Song("a", "After dark, before dawn", "Soundwave", "wy", dc("cur3")), Song("n", "Night Drive: 02 AM", "Editor", "wy", dc("cur1")),
            Song("r", "After Rain", "Low Season", "wy", dc("cur2")), Song("p", "Paper Moon", "North Cinema", "wy", dc("debut")),
            Song("f", "First Light", "Mira Vale", "wy", dc("fs1"))
        )
        return DefaultPlayerState(
            song, queue, 0, playing = true, resolving = false, status = "", liked = true, playMode = PlayMode.SEQUENCE,
            progress = 0.42f, displayTime = 108f, duration = 258f, quality = "Lossless · 24 bit", volume = 0.6f, showVolume = true,
            lyrics = lines, lyricTimeMs = 19_000L, showTranslation = true
        )
    }

    private val noop = DefaultPlayerActions(
        {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, {}
    )

    private fun render(dark: Boolean, lyrics: Boolean, queue: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        val s = state()
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize()) {
                    DefaultPlayerBackdrop(s.song.cover, Modifier.matchParentSize())
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        DefaultPlayerRoute(
                            lyrics, queue, s, noop, rememberLazyListState(), androidx.compose.runtime.remember { MutableTransitionState(true) },
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        save(name)
    }

    @Test fun mainLight() = render(false, false, false, "default-main-light")
    @Test fun mainDark() = render(true, false, false, "default-main-dark")
    @Test fun lyricsLight() = render(false, true, false, "default-lyrics-light")
    @Test fun lyricsDark() = render(true, true, false, "default-lyrics-dark")
    @Test fun queueLight() = render(false, false, true, "default-queue-light")
    @Test fun queueDark() = render(true, false, true, "default-queue-dark")

    private fun capsule(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        val progress = mutableFloatStateOf(0.42f)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        MiniPlayer(glass.copy(cover = dc("player")), playing = true, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onClick = {}, onToggle = {}, style = "capsule")
                        Spacer(Modifier.height(8.dp))
                        BottomNav(Tab.HOME, dark = dark, style = "capsule") {}
                    }
                }
            }
        }
        save(name)
    }

    @Test fun capsuleLight() = capsule(false, "capsule-bar-light")
    @Test fun capsuleDark() = capsule(true, "capsule-bar-dark")
}
