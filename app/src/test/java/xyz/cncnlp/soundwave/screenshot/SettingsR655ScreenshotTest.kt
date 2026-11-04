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
 * r655 part B 设置页截图（仅本地/手动）：未设置 SW_SCREENSHOT_DIR 时全部跳过。
 * 页面自带 LazyColumn，因此用高窗口一次渲染整页；浅色为主，另含设置深色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SettingsR655ScreenshotTest {
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
    private fun Settings(sub: String? = null, dark: Boolean = false) {
        MePage(
            serverUrl = "", preferQ = "无损", loggedIn = true, user = "Amanda", status = "", sleepRemain = 0L,
            onUrl = {}, onQuality = {}, onSleep = {},
            themeMode = if (dark) "night" else "light", settingsSub = sub
        )
    }

    @Test @Config(qualifiers = "w412dp-h2700dp-xhdpi")
    fun r655Settings() { begin("r655Settings"); page { Settings() }; save("b-settings") }

    @Test @Config(qualifiers = "w412dp-h2700dp-xhdpi")
    fun r655SettingsDark() { begin("r655SettingsDark"); page(dark = true) { Settings(dark = true) }; save("b-settings-dark") }

    private fun sub(name: String, h: String = "") { begin("r655Sub_$name"); page { Settings(name) }; save("b-sub-$name") }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun r655SubPreload() = sub("preload")
    @Test @Config(qualifiers = "w412dp-h2200dp-xhdpi") fun r655SubLyrics() = sub("lyrics")
    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun r655SubTheme() = sub("theme")
    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun r655SubDock() = sub("dock")
    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun r655SubSearch() = sub("search")
    @Test @Config(qualifiers = "w412dp-h1100dp-xhdpi") fun r655SubDownload() = sub("download")

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun r655SubDark() { begin("r655SubDark"); page(dark = true) { Settings("preload", dark = true) }; save("b-sub-preload-dark") }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun r655Picker() {
        begin("r655Picker")
        page { Settings("preload") }
        rule.onNodeWithText("1 首").performClick()
        save("b-ov-picker")
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun r655AccountSheet() {
        begin("r655AccountSheet")
        page { Settings(); xyz.cncnlp.soundwave.ui.common.ProfileSheet(userName = "Amanda", avatarPath = "", onDismiss = {}, onChangeAvatar = {}, onRenameUser = {}, onLogoutWebDav = {}) }
        save("b-ov-account-sheet")
    }

    @Composable
    private fun Hub(sub: String, history: List<Song> = emptyList(), scripts: List<Script> = emptyList()) {
        SettingsHub(
            settingsSub = sub, serverUrl = "", preferQ = "无损", loggedIn = true, user = "Amanda", status = "", sleepRemain = 0L,
            scripts = scripts, onUrl = {}, onQuality = {}, onSleep = {},
            onScriptsChange = {}, onImportCode = {}, themeMode = "light", historySongs = history,
            webDavBundle = LocalStore.UserBundle(), onWebDavBundleApplied = {}
        )
    }

    private val now = System.currentTimeMillis()
    private fun hsong(i: Int, name: String, singer: String, album: String, ago: Long) =
        Song(id = "h$i", name = name, singer = singer, album = album, source = "wy", cover = covers[i % 8], duration = 200_000, playedAt = now - ago)

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun r655History() {
        begin("r655History")
        val h = listOf(
            hsong(0, "Glass Horizon", "Mira Vale", "Neon Current", 20 * 60_000L), hsong(1, "Streetlight Bloom", "Low Season", "After Rain", 90 * 60_000L),
            hsong(2, "Folded Light", "North Cinema", "Paper Moon", 4 * 3600_000L), hsong(3, "Velvet Signal", "June Arcade", "Soft Static", 26 * 3600_000L),
            hsong(4, "Daybreak Relay", "Mira Vale", "Neon Current", 30 * 3600_000L)
        )
        page { Hub("history", history = h) }
        save("b-history")
    }

    @Test @Config(qualifiers = "w412dp-h2000dp-xhdpi")
    fun r655Stats() {
        begin("r655Stats")
        page { Hub("stats") }
        save("b-stats")
    }

    private val scripts = listOf(
        Script("sc1", "LX 音源 · 主线", "全平台 · 无损优先", "2.4.1", "lx", code = "x"),
        Script("sc2", "六音源", "wy / tx / kg", "1.2.0", "six", code = "x"),
        Script("sc3", "备用音源", "需重新导入", "0.9", "", code = "", enabled = false)
    )

    @Test @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun r655Scripts() { begin("r655Scripts"); page { Hub("scripts", scripts = scripts) }; save("b-scripts") }

    @Test @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun r655Smart() { begin("r655Smart"); page { Hub("smart", scripts = scripts) }; save("b-smart") }

    @Test @Config(qualifiers = "w412dp-h1700dp-xhdpi")
    fun r655SmartDark() { begin("r655SmartDark"); page(dark = true) { Hub("smart", scripts = scripts) }; save("b-smart-dark") }

    /** 所有设置图标一览（校验同风格、不重复）。 */
    @Test @Config(qualifiers = "w412dp-h1000dp-xhdpi")
    fun r655Glyphs() {
        begin("r655Glyphs")
        page {
            androidx.compose.foundation.layout.Column(Modifier.padding(10.dp)) {
                xyz.cncnlp.soundwave.ui.settings.SettingsLineGlyphs.pathSpecs.keys.chunked(6).forEach { row ->
                    androidx.compose.foundation.layout.Row {
                        row.forEach { k ->
                            androidx.compose.foundation.layout.Column(Modifier.width(65.dp).padding(4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                androidx.compose.material3.Icon(xyz.cncnlp.soundwave.ui.settings.SettingsLineGlyphs.of(k), null, Modifier.size(26.dp))
                                androidx.compose.material3.Text(k, fontSize = 6.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        save("b-glyphs")
    }
}
