package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.ui.player.PlayerImmersiveCoverHero
import java.io.File

/**
 * r674 第 4.5 项：沉浸封面交界自查——4 种封面（亮 / 暗 / 高饱和 / 白底）× 浅色（Apple 白磨砂）/ 深色背板。
 * 背板 = 封面底部取色按播放器同一公式推出的背景色；逐行取中线 ±40% 宽度平均亮度，检查：
 *  末端白线（交界附近比下方背景更亮的峰）、相邻行亮度跳变、交界行与背景差。数值写入 SW_SCREENSHOT_DIR/r674-fade-metrics.csv。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class R674CoverFadeVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun cover(kind: String): Pair<Bitmap, Color> {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint()
        val (top, bottom) = when (kind) {
            "bright" -> 0xFFFFE9A8.toInt() to 0xFFF6F1E6.toInt()
            "dark" -> 0xFF0B1630.toInt() to 0xFF1A1020.toInt()
            "saturated" -> 0xFFFF1E6E.toInt() to 0xFF2A3BFF.toInt()
            else -> 0xFF202020.toInt() to 0xFFFFFFFF.toInt() // white-bottom：上深下纯白（截图里的雾白底封面）
        }
        p.shader = LinearGradient(0f, 0f, 0f, 300f, top, bottom, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, 300f, 300f, p)
        p.shader = null; p.color = if (kind == "white-bottom") 0xFF7A2030.toInt() else 0x66FFFFFF
        for (i in 0 until 6) c.drawCircle(40f + i * 45f, 90f + (i % 2) * 40f, 14f, p) // 纹理
        return b to Color(bottom)
    }

    private fun lum(px: Int): Float { val r = (px shr 16) and 255; val g = (px shr 8) and 255; val bl = px and 255; return 0.2126f * r + 0.7152f * g + 0.0722f * bl }

    private fun run(kind: String, dark: Boolean): String {
        val (bmp, lower) = cover(kind)
        // 与 FullPlayer.appleFadeBridge 同一公式：浅色（白磨砂）= 底部取色 + 78% 白；深色 = 底部取色压暗 30%。
        val bg = if (dark) lerp(lower, Color.Black, 0.30f) else lerp(lower, Color.White, 0.78f)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(bg)) {
                PlayerImmersiveCoverHero(Modifier.size(360.dp, 420.dp).testTag("hero"), BitmapPainter(bmp.asImageBitmap()), "c", fadeBridgeColor = { bg }, statusScrimAlpha = { 0f }, onClick = {})
            }
        }
        rule.waitForIdle()
        val rect = rule.onNodeWithTag("hero").fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
        val v = rule.activity.window.decorView
        val full = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(full))
        val top = rect.top.toInt(); val bottom = rect.bottom.toInt()
        val shot = Bitmap.createBitmap(full, 0, top, full.width, (bottom - top + 120).coerceAtMost(full.height - top))
        System.getenv("SW_SCREENSHOT_DIR")?.let { d -> File(d, "r674-fade-$kind-${if (dark) "dark" else "light"}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val x0 = (full.width * 0.1f).toInt(); val x1 = (full.width * 0.9f).toInt()
        fun rowL(y: Int) = (x0 until x1 step 3).map { lum(full.getPixel(it, y)) }.average().toFloat()
        val h = bottom - top
        val from = top + (h * 0.50f).toInt()
        val rows = (from until (bottom + 60).coerceAtMost(full.height)).map { rowL(it) }
        val bgL = lum(bg.toArgb())
        val maxStep = rows.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(a - b) }
        // 末端白线：交界 ±24 行内比背景亮出的峰值（相对背景，正值 = 更白）。
        val edgeIdx = bottom - from
        val window = rows.subList((edgeIdx - 24).coerceAtLeast(0), (edgeIdx + 24).coerceAtMost(rows.size))
        val overshoot = window.maxOf { it - maxOf(window.first(), bgL) }.coerceAtLeast(0f)
        val edgeDiff = kotlin.math.abs(rows[edgeIdx.coerceIn(0, rows.size - 1)] - bgL)
        val tailDiff = kotlin.math.abs(rows[(edgeIdx - 6).coerceIn(0, rows.size - 1)] - bgL)
        assertTrue("$kind/$dark adjacent-row jump $maxStep", maxStep <= 3.0f)
        assertTrue("$kind/$dark edge row differs from backdrop $edgeDiff", edgeDiff <= 1.0f)
        assertTrue("$kind/$dark residue just above edge $tailDiff", tailDiff <= 1.0f)
        assertTrue("$kind/$dark white line overshoot $overshoot", overshoot <= 1.5f)
        return "%s,%s,%.2f,%.2f,%.2f,%.2f".format(kind, if (dark) "dark" else "light", maxStep, edgeDiff, tailDiff, overshoot)
    }

    private fun record(line: String) {
        println("R674_FADE " + line)
        System.getenv("SW_SCREENSHOT_DIR")?.let { File(it, "r674-fade-metrics.csv").appendText(line + "\n") }
    }
    @Test fun brightLight() = record(run("bright", false))
    @Test fun brightDark() = record(run("bright", true))
    @Test fun darkLight() = record(run("dark", false))
    @Test fun darkDark() = record(run("dark", true))
    @Test fun saturatedLight() = record(run("saturated", false))
    @Test fun saturatedDark() = record(run("saturated", true))
    @Test fun whiteBottomLight() = record(run("white-bottom", false))
    @Test fun whiteBottomDark() = record(run("white-bottom", true))
}
