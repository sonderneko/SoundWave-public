package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import java.io.File

/**
 * r672 player fixes on the actual FullPlayer routes (local artwork fixture; Robolectric native graphics, not a handset).
 * Frames are captured with a paused clock through each transition and compared numerically:
 * no white flash in the status-bar band, no hard seam under the immersive cover, Apple lyrics hero present when the
 * main cover is not immersive, Apple queue = the shared bottom sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class Player672RoutesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var show by mutableStateOf(false)
    private val playbackTime = mutableStateOf(20f)

    private fun render(layout: String, immersive: Boolean = false, dark: Boolean = false) {
        val out = System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(out != null)
        LocalStore.prefs(rule.activity).edit().clear().commit()
        if (immersive) PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).setImmersiveCover(layout, true)
        FrostedPrefs.themeMode = if (dark) "night" else "light"
        val cover = File(rule.activity.cacheDir, "route-art-672.png")
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = android.graphics.Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 600f, 600f, intArrayOf(0xFFEAA082.toInt(), 0xFF568F9B.toInt(), 0xFF2B3A55.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 600f, 600f, p)
        p.shader = null; p.color = 0xFFE9BC63.toInt(); c.drawCircle(350f, 230f, 115f, p)
        cover.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val song = Song("fixture672", "Glass Horizon", "Mira Vale", "local", cover = cover.toURI().toString(), duration = 180000)
        val lines = listOf("A quiet moment", "Find the morning light", "Let the music stay", "Follow every heartbeat", "Home is where we go")
            .mapIndexed { i, t -> LyricLine(i * 10000L, t) }
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        val dismissHolder = PlayerDismissOffsetHolder()
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                FullPlayer(song = song, queueSnapshot = listOf(song), queueCurrentIndexState = remember { mutableStateOf(0) }, playing = true,
                    progressState = remember { mutableStateOf(.12f) }, currentTimeState = playbackTime, duration = 180f,
                    resolvingState = remember { mutableStateOf(false) }, statusState = remember { mutableStateOf("") }, lyrics = lines, lyricIdx = 2, showLyrics = show, liked = false, playMode = PlayMode.SEQUENCE, sleepRemain = 0L,
                    playerLayout = layout, dismissOffsetHolder = dismissHolder,
                    onQueueItem = { _, _, _ -> }, onToggleLyrics = { show = !show }, onSeek = {}, onPrev = {}, onToggle = {}, onNext = {}, onLike = {},
                    onMode = {}, onSleep = {}, onDownload = {})
            }
        }
        settle()
    }

    private fun settle() { repeat(4) { rule.waitForIdle(); Thread.sleep(120); shadowOf(Looper.getMainLooper()).idle() } }

    private fun frame(name: String): Bitmap {
        val v = rule.activity.window.decorView
        return Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b ->
            v.draw(Canvas(b))
            File(System.getenv("SW_SCREENSHOT_DIR"), "672-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun lum(c: Int): Float = 0.299f * AColor.red(c) + 0.587f * AColor.green(c) + 0.114f * AColor.blue(c)

    /** Mean luminance of a horizontal band (fractions of height). */
    private fun bandMean(b: Bitmap, from: Float, to: Float): Float {
        var sum = 0.0; var n = 0
        val y0 = (b.height * from).toInt(); val y1 = (b.height * to).toInt().coerceAtMost(b.height)
        for (y in y0 until y1 step 2) for (x in 0 until b.width step 4) { sum += lum(b.getPixel(x, y)); n++ }
        return (sum / n).toFloat()
    }

    /** Largest luminance jump between consecutive rows of a thin left-edge strip (avoids the text column; median rejects motes). */
    private fun maxRowStep(b: Bitmap, from: Float, to: Float): Float {
        val xs = 2 until (b.width * 0.05f).toInt().coerceAtLeast(8)
        var prev = -1f; var max = 0f
        for (y in (b.height * from).toInt() until (b.height * to).toInt()) {
            val m = xs.map { lum(b.getPixel(it, y)) }.sorted().let { it[it.size / 2] }
            if (prev >= 0f) max = maxOf(max, kotlin.math.abs(m - prev))
            prev = m
        }
        return max
    }

    /** Toggle with a paused clock and capture frames across the transition. */
    private fun transition(name: String, action: () -> Unit): List<Bitmap> {
        rule.mainClock.autoAdvance = false
        val frames = mutableListOf(frame("$name-0"))
        action()
        var t = 0L
        listOf(16L, 32L, 48L, 64L, 96L, 128L, 160L, 200L, 260L, 340L, 460L, 700L).forEach { at ->
            rule.mainClock.advanceTimeBy(at - t); t = at
            frames += frame("$name-$at")
        }
        rule.mainClock.autoAdvance = true
        settle()
        frames += frame("$name-end")
        return frames
    }

    @Test fun standardImmersiveCoverToLyricsHasNoStatusFlashNoSeam() {
        render(PLAYER_LAYOUT_COVER_ONLY)
        val open = transition("standard-open") { rule.onNodeWithContentDescription("Glass Horizon 沉浸封面，点按查看歌词").performClick() }
        assertTrue(show)
        val back = transition("standard-back") { rule.onNodeWithContentDescription("返回封面").performClick() }
        assertFalse(show)
        // Status-bar band (top 3%) is the shared persistent cover in every frame: no white flash mid cross-fade.
        val top = (open + back).map { bandMean(it, 0f, 0.03f) }
        assertTrue("status band must stay stable, got $top", top.max() - top.min() < 12f)
        // Cover body band (8%..30%) is the same image on both pages: no jump/dip during the cross-fade.
        val body = (open + back).map { bandMean(it, 0.08f, 0.30f) }
        assertTrue("cover body must stay stable, got $body", body.max() - body.min() < 12f)
        // Settled lyrics page: no hard horizontal seam where the cover ends (the old opaque fade ended at the Hero
        // height, 411dp here ≈ 51%). Scan stops just above the lyric viewport, whose Offscreen edge fade is a
        // separate, pre-existing layer that Robolectric's software renderer draws with a 1px artifact row.
        val lyricTop = rule.onNodeWithText("A quiet moment").fetchSemanticsNode().boundsInRoot.top
        val step = maxRowStep(open.last(), 0.30f, (lyricTop - 30f) / open.last().height)
        assertTrue("cover bottom must fade without a seam, max row step $step", step < 10f)
        val stepMain = maxRowStep(back.last(), 0.30f, 0.80f)
        assertTrue("main cover bottom must fade without a seam, max row step $stepMain", stepMain < 10f)
    }

    @Test fun defaultCoverMorphFramesBothDirections() {
        render("default")
        transition("default-open") { rule.onNodeWithContentDescription("Glass Horizon 封面，点按查看歌词").performClick() }
        assertTrue(show)
        val back = transition("default-back") { rule.onNodeWithContentDescription("返回封面").performClick() }
        assertFalse(show)
        // The final frames of the return must already be at rest (no settle jump at the tail).
        val a = bandMean(back[back.size - 2], 0.05f, 0.6f); val z = bandMean(back.last(), 0.05f, 0.6f)
        assertTrue("return tail must not jump: $a vs $z", kotlin.math.abs(a - z) < 2f)
    }

    @Test fun appleNonImmersiveLyricsShowsImmersiveHero() {
        render("apple")
        assertFalse(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
        rule.onNodeWithContentDescription("沉浸歌词").performClick(); settle()
        rule.onNodeWithText("Let the music stay").assertExists()
        rule.onNodeWithContentDescription("Glass Horizon 封面").assertExists()
        val lyrics = frame("apple-plain-lyrics")
        // The top band is artwork, not the blank backdrop: compare against the fixture's dark lower-left tone.
        assertTrue("lyrics top area must carry the cover", bandMean(lyrics, 0.05f, 0.25f) < 200f)
    }

    @Test fun appleImmersiveLyricsReturnHasNoFullScreenFlash() {
        render("apple", immersive = true)
        rule.onNodeWithContentDescription("沉浸歌词").performClick(); settle()
        val back = transition("apple-back") { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() } }
        rule.onNodeWithContentDescription("沉浸歌词").assertExists()
        // The persistent hero keeps the cover band constant while the page content fades out / in.
        val hero = back.map { bandMean(it, 0.05f, 0.25f) }
        assertTrue("cover band must not flash, got $hero", hero.max() - hero.min() < 12f)
        val whole = back.map { bandMean(it, 0f, 1f) }
        assertTrue("whole screen must not flash, got $whole", whole.max() - whole.min() < 20f)
    }

    @Test fun appleQueueIsTheSharedBottomSheetOverTheCurrentPage() {
        render("apple", immersive = true)
        rule.onNodeWithContentDescription("播放队列").performClick(); settle()
        rule.onNodeWithText("UP NEXT · 1 首").assertExists()
        rule.onNodeWithContentDescription("清空播放队列（保留当前歌曲）").assertExists()
        // The Apple cover page stays underneath the sheet (no separate full-page queue route).
        rule.onNodeWithContentDescription("沉浸歌词").assertExists()
        frame("apple-queue-sheet")
        rule.onNodeWithContentDescription("关闭播放队列").performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 40f)) }
        rule.mainClock.advanceTimeBy(300); settle()
        rule.onNodeWithText("UP NEXT · 1 首").assertDoesNotExist()
        assertTrue(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
    }

    @After fun reset() { FrostedPrefs.resetForTest(); FrostedPrefs.themeMode = "light" }
}
