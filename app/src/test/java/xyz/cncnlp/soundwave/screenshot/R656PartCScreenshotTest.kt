package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
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
import xyz.cncnlp.soundwave.DlnaDevice
import xyz.cncnlp.soundwave.LyricFloatLineView
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.LyricWord
import xyz.cncnlp.soundwave.MiniPlayer
import xyz.cncnlp.soundwave.NotificationLyricGlyph
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.Tab
import xyz.cncnlp.soundwave.ui.common.DesignSheetPanel
import xyz.cncnlp.soundwave.ui.common.ParticleStyle
import xyz.cncnlp.soundwave.ui.common.particleBurst
import xyz.cncnlp.soundwave.ui.common.rememberParticleBurstState
import xyz.cncnlp.soundwave.ui.player.PlayerDeviceSheetContent
import xyz.cncnlp.soundwave.ui.player.PlayerDlnaPanelSnapshot
import xyz.cncnlp.soundwave.ui.player.PlayerDlnaSessionSnapshot
import xyz.cncnlp.soundwave.ui.player.PlayerQualitySheetContent
import xyz.cncnlp.soundwave.ui.player.PlayerSystemOutputSnapshot
import java.io.File

/**
 * r656 part C：胶囊底栏 + 迷你条、播放音质 / 播放设备抽屉、粒子帧、通知「词」图标、悬浮歌词卡拉 OK。
 * 仅设置 SW_SCREENSHOT_DIR 时运行（输出到 /workspace/work/compare/r656/c/），CI 跳过。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class R656PartCScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS_A") ?: "/workspace/work/compare/a/dc"
    private fun dc(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()
    private val glass get() = Song(id = "glass", name = "Glass Horizon", singer = "Mira Vale", source = "wy", album = "Neon Current", duration = 258_000, cover = dc("player"))

    private fun loader() = Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())

    private fun save(name: String, settle: Boolean = true) {
        if (settle) repeat(4) { rule.waitForIdle(); Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(outDir(), "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun capsule(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        loader()
        val progress = mutableFloatStateOf(0.42f)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        MiniPlayer(glass, playing = false, progressState = progress, resolvingState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }, onClick = {}, onToggle = {}, style = "capsule")
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

    private fun quality(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        loader()
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
                    DesignSheetPanel { PlayerQualitySheetContent(glass, "flac24bit", "flac", onPick = {}) }
                }
            }
        }
        save(name)
    }

    @Test fun qualityLight() = quality(false, "player-quality-light")
    @Test fun qualityDark() = quality(true, "player-quality-dark")

    private fun device(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        loader()
        val tv = DlnaDevice("客厅电视", "http://192.168.1.20/desc.xml", "")
        val box = DlnaDevice("书房音箱", "http://192.168.1.21/desc.xml", "")
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.BottomCenter) {
                    DesignSheetPanel {
                        PlayerDeviceSheetContent(
                            PlayerSystemOutputSnapshot<android.media.MediaRouter.RouteInfo>(emptyList(), null, null),
                            PlayerDlnaPanelSnapshot(listOf(tv, box), false, PlayerDlnaSessionSnapshot(tv, "Glass Horizon", 63_000, 258_000, true, 0L)),
                            {}, {}, {}, {}, {}, {}, {}, {}, songTitle = glass.name, songCover = glass.cover
                        )
                    }
                }
            }
        }
        save(name)
    }

    @Test fun deviceLight() = device(false, "player-device-light")
    @Test fun deviceDark() = device(true, "player-device-dark")

    @Test fun particleFrames() {
        Assume.assumeTrue(outDir() != null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                val a = rememberParticleBurstState(); val b = rememberParticleBurstState(); val c = rememberParticleBurstState()
                LaunchedEffect(Unit) { a.burst(); b.burst(); c.burst() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    Row {
                        val acc = SoundwaveDesignPalette.Accent
                        Box(Modifier.particleBurst(a, acc, acc.copy(alpha = 0.55f), ParticleStyle.Hearts, count = 10, radius = 48.dp).size(46.dp).background(MaterialTheme.colorScheme.surface))
                        Spacer(Modifier.width(110.dp))
                        Box(Modifier.particleBurst(b, acc, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), ParticleStyle.Ring, count = 12, radius = 62.dp, particleSize = 2.5.dp).size(76.dp).background(acc))
                        Spacer(Modifier.width(110.dp))
                        Box(Modifier.particleBurst(c, acc, acc.copy(alpha = 0.5f), ParticleStyle.Sparkles, count = 8, radius = 38.dp).size(42.dp).background(MaterialTheme.colorScheme.surface))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(220)
        save("particles-mid", settle = false)
        rule.mainClock.advanceTimeBy(1200)
        save("particles-end", settle = false)
    }

    @Test fun notificationGlyphs() {
        Assume.assumeTrue(outDir() != null)
        val states = listOf(false to false, true to false, true to true)
        val sheet = Bitmap.createBitmap(112 * 3 + 40, 152, Bitmap.Config.ARGB_8888)
        val cv = Canvas(sheet)
        cv.drawColor(android.graphics.Color.rgb(40, 40, 44))
        states.forEachIndexed { i, (on, lock) -> cv.drawBitmap(NotificationLyricGlyph.render(on, lock), 10f + i * 122f, 20f, null) }
        File(outDir(), "notif-lyric-glyphs.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun floatingLyricKaraoke() {
        Assume.assumeTrue(outDir() != null)
        val ctx = rule.activity
        val words = listOf(LyricWord(0, 600, "Every "), LyricWord(600, 1100, "signal "), LyricWord(1100, 1700, "leads "), LyricWord(1700, 2200, "me "), LyricWord(2200, 2800, "back "), LyricWord(2800, 3400, "to "), LyricWord(3400, 4200, "you"))
        val line = LyricLine(0, words.joinToString("") { it.text }, words = words)
        val out = Bitmap.createBitmap(820, 360, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(android.graphics.Color.rgb(90, 96, 110))
        listOf(0L to 0, 1400L to 120, 3600L to 240).forEach { (pos, y) ->
            val v = LyricFloatLineView(ctx)
            v.applyStyle(18f, android.graphics.Color.rgb(42, 120, 255), 1f, true)
            v.setLine(line, "Through the glass horizon, we move", pos, 0L, playing = false)
            v.measure(View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            cv.save(); cv.translate(20f, y + 10f); v.draw(cv); cv.restore()
        }
        File(outDir(), "floating-lyric-karaoke.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
