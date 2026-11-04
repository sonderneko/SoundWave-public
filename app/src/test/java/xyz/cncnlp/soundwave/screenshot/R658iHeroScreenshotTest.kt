package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.SoundwaveDesignTheme
import xyz.cncnlp.soundwave.TodayRecommendationHero
import java.io.File

/** Synthetic artwork only: no remote music/cover service or private files. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h850dp-xhdpi")
class R658iHeroScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(bright: Boolean, immersive: Boolean = false, home: Boolean = false) {
        val output = System.getenv("SW_SCREENSHOT_DIR")
        Assume.assumeTrue(output != null)
        val image = Bitmap.createBitmap(512, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 512f, 800f,
                if (bright) intArrayOf(0xFFFFEAC2.toInt(), 0xFFF3B9C1.toInt(), 0xFFD8EDDD.toInt())
                else intArrayOf(0xFF123C49.toInt(), 0xFF376251.toInt(), 0xFF203754.toInt()), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 512f, 800f, paint)
        paint.shader = null
        paint.color = if (bright) 0xFFE1B768.toInt() else 0xFF52998B.toInt()
        for (y in 40..800 step 110) canvas.drawCircle((y % 430 + 30).toFloat(), y.toFloat(), 45f, paint)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        val fixtureFile=File(rule.activity.cacheDir,"hero-666-${bright}.png");fixtureFile.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        val songs = listOf(Song(id = "mock", name = "Synthetic artwork", singer = "Local test", source = "local",cover=fixtureFile.toURI().toString()))
        rule.setContent {
            SoundwaveDesignTheme(isDark = !bright) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if(home) xyz.cncnlp.soundwave.HomeDailyHero(songs,onPlayAll={},onOpen={}) else if (immersive) xyz.cncnlp.soundwave.ImmersiveDetailHero(
                        title="渐变测试歌单", subtitle="Local fixture", meta=listOf("18 首", "模拟封面"),
                        cover="synthetic",coverModel=image,tint=androidx.compose.ui.graphics.Color(0xFF52998B),
                        favorited=false,showFavorite=true,playEnabled=true,onToggleFavorite={},onPlayAll={},onDownload=null,onMore={}
                    ) else TodayRecommendationHero(songs, 18, "synthetic", image, false, {}, {}, {}, {}, {}, onShuffle = {})
                }
            }
        }
        repeat(6) { rule.waitForIdle(); Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output, (if(home) "r666-home-" else if (immersive) "r658i-immersive-" else "r658i-hero-") + (if (bright) "bright.png" else "dark.png")).apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun homeBrightCover()=render(true,home=true)
    @Test fun homeDarkCover()=render(false,home=true)
    @Test fun brightCover() = render(true)
    @Test fun darkCover() = render(false)
    @Test fun immersiveBrightCover() = render(true,true)
    @Test fun immersiveDarkCover() = render(false,true)
}
