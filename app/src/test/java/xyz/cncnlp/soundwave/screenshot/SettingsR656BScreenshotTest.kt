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
import xyz.cncnlp.soundwave.ui.settings.*

/**
 * r656 part B 设置页截图（第 7/8/9 项）（仅本地/手动）：未设置 SW_SCREENSHOT_DIR 时全部跳过。
 * 页面自带 LazyColumn，因此用高窗口一次渲染整页；浅色为主，另含设置深色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SettingsR656BScreenshotTest {
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

    private val covers by lazy { (0..7).map { cover("det$it") } }

    @Composable
    private fun Settings(dark: Boolean = false) {
        MePage(
            serverUrl = "", preferQ = "flac", loggedIn = true, user = "Amanda", status = "", sleepRemain = 30 * 60_000L,
            onUrl = {}, onQuality = {}, onSleep = {},
            themeMode = if (dark) "night" else "light"
        )
    }

    @Composable
    private fun Hub(sub: String, scripts: List<Script> = emptyList(), downloads: List<DownloadItem> = emptyList(), dark: Boolean = false) {
        SettingsHub(
            settingsSub = sub, serverUrl = "", preferQ = "flac", loggedIn = true, user = "Amanda", status = "", sleepRemain = 0L,
            scripts = scripts, onUrl = {}, onQuality = {}, onSleep = {},
            onScriptsChange = {}, onImportCode = {}, themeMode = if (dark) "night" else "light", downloads = downloads,
            webDavBundle = LocalStore.UserBundle(), onWebDavBundleApplied = {}
        )
    }

    private fun sheet(dark: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        page(dark) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                xyz.cncnlp.soundwave.ui.common.DesignSheetPanel(content = content)
            }
        }
    }

    private val scripts = listOf(
        Script("sc1", "LX 音源 · 主线", "全平台 · 无损优先", "2.4.1", "lx", code = "x"),
        Script("sc2", "六音源", "wy / tx / kg", "1.2.0", "six", code = "x"),
        Script("sc3", "备用音源", "需重新导入", "0.9", "", code = "", enabled = false)
    )

    private fun dl(i: Int, name: String, singer: String, status: String, progress: Float = 1f, error: String = "") = DownloadItem(
        song = Song(id = "d$i", name = name, singer = singer, source = "wy", cover = covers[i % 8], actualQuality = "flac"),
        path = "/music/d$i.flac", size = 32L * 1024 * 1024 + i * 1000, time = 0L, status = status, progress = progress, error = error,
        quality = "flac", platform = "wy", taskId = "t$i"
    )
    private val downloads by lazy {
        listOf(
            dl(0, "Glass Horizon", "Mira Vale", "downloading", 0.46f), dl(1, "Streetlight Bloom", "Low Season", "paused", 0.2f),
            dl(2, "Folded Light", "North Cinema", "failed", 0f, "取链失败"), dl(3, "Velvet Signal", "June Arcade", "done"),
            dl(4, "Daybreak Relay", "Mira Vale", "done")
        )
    }

    @Test @Config(qualifiers = "w412dp-h2700dp-xhdpi")
    fun settingsRoot() { begin("settingsRoot"); page { Settings() }; save("settings-root") }

    @Test @Config(qualifiers = "w412dp-h2700dp-xhdpi")
    fun settingsRootDark() { begin("settingsRootDark"); page(true) { Settings(true) }; save("settings-root-dark") }

    @Test fun sheetQuality() { begin("sheetQuality"); sheet { PlaybackQualitySheetContent("flac") {} }; save("sheet-quality") }
    @Test fun sheetSleep() { begin("sheetSleep"); sheet { SleepTimerSheetContent(45 * 60_000L, {}, {}) }; save("sheet-sleep") }
    @Test fun sheetSpeed() { begin("sheetSpeed"); sheet { PlaybackSpeedSheetContent(1.25f) {} }; save("sheet-speed") }
    @Test fun sheetEq() { begin("sheetEq"); sheet { EqualizerSheetContent(listOf("关闭", "流行", "摇滚", "爵士", "古典", "人声"), 2) {} }; save("sheet-eq") }
    @Test fun sheetFocus() { begin("sheetFocus"); sheet { AudioFocusSheetContent(xyz.cncnlp.soundwave.playback.AudioFocusSettings(), {}, {}) }; save("sheet-focus") }
    @Test fun sheetFocusDark() { begin("sheetFocusDark"); sheet(true) { AudioFocusSheetContent(xyz.cncnlp.soundwave.playback.AudioFocusSettings(xyz.cncnlp.soundwave.playback.AudioFocusMode.MIX, false), {}, {}) }; save("sheet-focus-dark") }
    @Test fun sheetSchedule() { begin("sheetSchedule"); sheet { ScriptScheduleSheetContent(true, 90, { _, _ -> }, {}) }; save("sheet-schedule") }

    @Test @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun smart() { begin("smart"); page { Hub("smart", scripts) }; save("smart") }

    @Test @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun smartDark() { begin("smartDark"); page(true) { Hub("smart", scripts, dark = true) }; save("smart-dark") }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun scriptsPage() { begin("scriptsPage"); page { Hub("scripts", scripts) }; save("scripts") }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun scriptsPageDark() { begin("scriptsPageDark"); page(true) { Hub("scripts", scripts, dark = true) }; save("scripts-dark") }

    @Test
    fun scriptDetail() {
        begin("scriptDetail")
        page { Hub("scripts", scripts) }
        rule.onNodeWithContentDescription("详情 LX 音源 · 主线").performClick()
        save("script-detail")
    }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun downloadsPage() { begin("downloadsPage"); page { Hub("downloads", downloads = downloads) }; save("downloads") }

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun downloadsDark() { begin("downloadsDark"); page(true) { Hub("downloads", downloads = downloads, dark = true) }; save("downloads-dark") }

    @Test
    fun downloadsFolderSheet() {
        begin("downloadsFolderSheet")
        page { Hub("downloads", downloads = downloads) }
        rule.onNodeWithContentDescription("下载目录").performClick()
        save("downloads-folder-sheet")
    }
}
