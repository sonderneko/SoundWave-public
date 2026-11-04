package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * r656 part F 设置维护页截图（仅本地/手动）：未设置 SW_SCREENSHOT_DIR 时全部跳过。
 * 页面自带 LazyColumn，因此用高窗口一次渲染整页；浅色为主，另含设置深色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class R656PartFScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }
    private val coverDir: String get() = System.getenv("SW_SCREENSHOT_COVERS") ?: "/workspace/work/compare/covers"
    private fun cover(name: String) = File(coverDir, "$name.png").absolutePath.takeIf { File(it).exists() }.orEmpty()
    private val only: String? get() = System.getenv("SW_SHOT_ONLY")?.takeIf { it.isNotBlank() }

    private fun begin(name: String) {
        Assume.assumeTrue(outDir() != null)
        only?.let { Assume.assumeTrue(it.split(',').contains(name)) }
        Coil.setImageLoader(
            ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build()
        )
    }

    private fun save(name: String) {
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        rule.mainClock.advanceTimeBy(1500)
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

    /** 把 Dialog / Popup 等独立窗口（PixelCopy 只拷活动窗口）按屏幕位置叠加，带 dim 遮罩。 */
    private fun drawOtherWindows(bmp: Bitmap, activityDecor: android.view.View) {
        val views = runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") (f.get(inst) as List<android.view.View>).toList()
        }.getOrDefault(emptyList())
        val canvas = Canvas(bmp)
        views.filter { it !== activityDecor && it.isShown && it.width > 0 }.forEach { v ->
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
            if (lp != null && (lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                canvas.drawColor(android.graphics.Color.argb((lp.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
            }
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val base = IntArray(2); activityDecor.getLocationOnScreen(base)
            canvas.save(); canvas.translate((loc[0] - base[0]).toFloat(), (loc[1] - base[1]).toFloat()); v.draw(canvas); canvas.restore()
        }
    }

    private fun page(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }


    @Composable
    private fun header(content: @Composable () -> Unit, title: String = "设置详情") {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            AppPageHeader(title = title, loggedIn = true, showAvatar = true, avatarSizeDp = 42f, titleSizeSp = 31, serifTitle = true, designTitle = true, startPadding = 14.dp)
            Box(Modifier.fillMaxSize().padding(top = 3.dp)) { content() }
        }
    }

    private fun seedDiagnostics() {
        PlaybackDiagnostics.clear()
        val a = Song("1", "Glass Horizon", "Mira Vale", source = "wy")
        val b = Song("2", "Streetlight Bloom", "Low Season", source = "tx")
        val c = Song("3", "Folded Light", "North Cinema", source = "kg")
        PlaybackDiagnostics.markStart(a); PlaybackDiagnostics.markResolved(a, 412, false, "lx", "wy", "flac"); PlaybackDiagnostics.markPrepareStart(a.key()); PlaybackDiagnostics.markReady(a, false)
        PlaybackDiagnostics.markStart(b); PlaybackDiagnostics.markResolved(b, 38, true, "lx", "tx", "320k"); PlaybackDiagnostics.markReady(b, false)
        PlaybackDiagnostics.markStart(c); PlaybackDiagnostics.markFailure(c, "script")
    }

    @Test @Config(qualifiers = "w412dp-h1900dp-xhdpi")
    fun fCache() { begin("fCache"); seedDiagnostics(); page { header({ CacheManagePage() }) }; save("f-cache") }

    @Test @Config(qualifiers = "w412dp-h1900dp-xhdpi")
    fun fCacheDark() { begin("fCacheDark"); seedDiagnostics(); page(dark = true) { header({ CacheManagePage() }) }; save("f-cache-dark") }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun fCacheClearSheet() { begin("fCacheClearSheet"); page { header({ CacheManagePage() }) }; rule.onNodeWithText("清空", useUnmergedTree = true).performClick(); save("f-cache-clear-sheet") }

    @Test @Config(qualifiers = "w412dp-h1400dp-xhdpi")
    fun fCrash() { begin("fCrash"); page { header({ StartupCrashPage() }) }; save("f-crash") }

    @Test @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun fBackup() {
        begin("fBackup")
        page { header({ BackupRestorePage(webDavBundle = LocalStore.UserBundle(), onWebDavBundleApplied = {}) }) }
        save("f-backup")
    }

    @Test @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun fBackupDark() {
        begin("fBackupDark")
        page(dark = true) { header({ BackupRestorePage(webDavBundle = LocalStore.UserBundle(), onWebDavBundleApplied = {}) }) }
        save("f-backup-dark")
    }

    @Test @Config(qualifiers = "w412dp-h1800dp-xhdpi")
    fun fDataFlow() { begin("fDataFlow"); page { header({ DataFlowPage() }) }; rule.onNodeWithText("本机存储").performClick(); save("f-dataflow") }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun fAbout() { begin("fAbout"); page { header({ AboutAppPage(status = "已是最新 1.0.656", serverUrl = "", onCheckUpdate = {}, onOpenDataFlow = {}) }) }; save("f-about") }

    @Test @Config(qualifiers = "w412dp-h1300dp-xhdpi")
    fun fAboutDark() { begin("fAboutDark"); page(dark = true) { header({ AboutAppPage(status = "", serverUrl = "", onCheckUpdate = {}, onOpenDataFlow = {}) }) }; save("f-about-dark") }

    private fun historySongs(): List<Song> {
        val now = System.currentTimeMillis()
        val day = 24L * 3600_000L
        val names = listOf("Glass Horizon" to "Mira Vale", "Streetlight Bloom" to "Low Season", "Folded Light" to "North Cinema", "Velvet Signal" to "June Arcade", "Daybreak Relay" to "Mira Vale")
        return names.mapIndexed { i, (n, s) ->
            Song("h$i", n, s, source = "wy", album = "Neon Current", cover = cover("det$i"), playedAt = now - (if (i < 3) i * 3_600_000L else day + i * 3_600_000L))
        }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun fHistory() { begin("fHistory"); page { header({ PlayHistoryPage(history = historySongs()) }, title = "播放历史") }; save("f-history") }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun fHistorySwipe() {
        begin("fHistorySwipe")
        page { header({ PlayHistoryPage(history = historySongs()) }, title = "播放历史") }
        rule.onNodeWithText("Streetlight Bloom").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(-90f, 0f)); moveBy(androidx.compose.ui.geometry.Offset(-20f, 0f))
        }
        save("f-history-swipe")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun fHistoryDark() { begin("fHistoryDark"); page(dark = true) { header({ PlayHistoryPage(history = historySongs()) }, title = "播放历史") }; save("f-history-dark") }
}
