package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.recommend.StyleTagCatalog
import xyz.cncnlp.soundwave.ui.today.StylePickerDialog
import java.io.File

/** r655 part E：今日推荐工具条（风格推荐 / 换一批 / 排序 / 多选）+ 风格推荐弹窗截图；未设置 SW_SCREENSHOT_DIR 时跳过。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class TodayStylePickerScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun outDir(): File? = System.getenv("SW_SCREENSHOT_DIR")?.let { File(it).apply { mkdirs() } }

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


    private val groups = listOf(
        StyleTagCatalog.Group("语种", listOf("华语", "欧美", "日语", "韩语", "粤语")),
        StyleTagCatalog.Group("风格", listOf("流行", "摇滚", "民谣", "电子", "说唱", "爵士", "R&B/Soul", "轻音乐", "古风", "后摇")),
        StyleTagCatalog.Group("场景", listOf("夜晚", "学习", "工作", "运动", "驾车", "旅行", "咖啡馆")),
        StyleTagCatalog.Group("情感", listOf("怀旧", "清新", "浪漫", "治愈", "放松", "安静")),
    )

    private fun shot(dark: Boolean, name: String) {
        Assume.assumeTrue(outDir() != null)
        rule.setContent {
            SoundwaveDesignTheme(isDark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.statusBarsPadding()) {
                        DetailDesignToolbar(
                            title = "为你选了 18 首", countLabel = "", multiMode = false, selectedCount = 0, allSelected = false,
                            sortMode = "default", sortMenuOpen = false, showDownload = false, onDownload = {}, onSortClick = {},
                            onSortPick = {}, onSortDismiss = {}, onEnterSelect = {}, onToggleAll = {}, onDone = {},
                            leadingTools = {
                                xyz.cncnlp.soundwave.ui.today.TodayExtraTool(xyz.cncnlp.soundwave.ui.today.TodayExtraGlyph.Style, "风格推荐") {}
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(7f)))
                                xyz.cncnlp.soundwave.ui.today.TodayExtraTool(xyz.cncnlp.soundwave.ui.today.TodayExtraGlyph.Refresh, "换一批", enabled = !dark) {}
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(7f)))
                            }
                        )
                    }
                    StylePickerDialog(
                        platformLabel = "网易云", groups = groups, selected = listOf("摇滚", "夜晚"), loading = false,
                        onToggle = {}, onClear = {}, onDismiss = {}, onConfirm = {}
                    )
                }
            }
        }
        save(name)
    }

    @Test fun stylePickerLight() = shot(false, "today-style-picker")
    @Test fun stylePickerDark() = shot(true, "today-style-picker-dark")
}
