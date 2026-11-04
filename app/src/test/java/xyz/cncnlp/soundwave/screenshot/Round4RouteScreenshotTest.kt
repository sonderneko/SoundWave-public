package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import xyz.cncnlp.soundwave.ui.player.PlayerLyricActionsSheet
import java.io.File

/** Real LibraryPage entry + synthetic local image: no provider/account requests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="w412dp-h1100dp-xhdpi")
class Round4RouteScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun render(name:String, dark:Boolean=false, content:@Composable ()->Unit) {
        val dir=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(dir!=null)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        rule.setContent { SoundwaveDesignTheme(isDark=dark) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        repeat(5) { rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(100) }
        val view=rule.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir,"$name.png").apply {parentFile.mkdirs()}.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun daily(platform:String, missing:Boolean=false) {
        val dir=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(dir!=null)
        val image=File(rule.activity.cacheDir,"round4-art.png")
        val bitmap=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(0xFF558B79.toInt())
        val paint=Paint().apply {color=0xFFF8D596.toInt()}; canvas.drawCircle(730f,280f,240f,paint)
        paint.color=0xFF243B49.toInt(); canvas.drawRect(0f,650f,1080f,1080f,paint)
        image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val songs=listOf(Song(id="first",name="First track",singer="Test artist",source=platform,cover=""),Song(id="second",name="Second track",singer="Test artist",source=platform,cover=if(missing) "" else image.absolutePath))
        val detail=todayRecommendationDetail(platform,songs)
        Assert.assertTrue(isTodayRecommendationDetail(detail))
        Assert.assertEquals(if(missing) "" else image.absolutePath,detail.cover)
        render("daily-$platform-${if(missing) "missing" else "cover"}") {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=detail,detailSongs=songs,onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={})
        }
        rule.onNodeWithText("播放全部",useUnmergedTree=true).assertExists()
    }
    @Test fun dailyWy()=daily("wy")
    @Test fun dailyQq()=daily("tx")
    @Test fun dailyKg()=daily("kg")
    @Test fun dailyKw()=daily("kw")
    @Test fun dailyMg()=daily("mg")
    @Test fun dailyWyMissing()=daily("wy",true)
    @Test fun dailyQqMissing()=daily("tx",true)
    @Test fun dailyKgMissing()=daily("kg",true)
    @Test fun dailyKwMissing()=daily("kw",true)
    @Test fun dailyMgMissing()=daily("mg",true)

    private fun immersive(dark:Boolean) {
        val bmp=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp); c.drawColor(0xFF558B79.toInt()); val p=Paint().apply {color=0xFFF8D596.toInt()}; c.drawCircle(730f,280f,240f,p)
        val file=File(rule.activity.cacheDir,"immersive-route.png"); file.outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        val song=Song(id="test",name="Test track",singer="Test artist",source="wy",cover=file.absolutePath)
        val detail=Playlist(id="fixture-playlist",name="沉浸封面测试歌单",source="wy",cover=file.absolutePath)
        render("immersive-route-${if(dark) "dark" else "light"}",dark) {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=detail,detailSongs=listOf(song),onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={},detailCoverStyle=DetailCoverStyles.IMMERSIVE_HERO)
        }
        rule.onNodeWithText("播放全部",useUnmergedTree=true).assertExists()
    }
    @Test fun immersiveRouteLight()=immersive(false)
    @Test fun immersiveRouteDark()=immersive(true)
    private fun lyrics(dark:Boolean, selected:Boolean=false) {
        render("lyric-actions-${if(dark) "dark" else "light"}${if(selected) "-selected" else ""}",dark) {
            ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    PlayerLyricActionsSheet(selectedLine=if(selected) LyricLine(83000,"在旋律里，遇见每一个自己", "Find yourself in the melody", "zai xuan lv li") else null,showTranslation=selected,showRomanization=selected,hasLyricCorrection=selected,onDismiss={},onCorrection={},onToggleTranslation={},onToggleRomanization={},lyricFontSp=18,lyricGapDp=9,lyricBlurDp=2,lyricOffset=0,onLyricFont={},onLyricGap={},onLyricBlur={},onLyricOffset={},onToggleFloating={})
                    AppOverlayHostLayer()
                }
            }
        }
        rule.onNodeWithText("距离模糊").assertDoesNotExist()
        rule.onNodeWithText("翻译").assertExists()
        rule.onNodeWithText("罗马音").assertExists()
        rule.onNodeWithText("歌词纠错").assertExists()
        rule.onNodeWithText("悬浮歌词").assertExists()
    }
    @Test fun lyricActionsLight()=lyrics(false)
    @Test fun lyricActionsDark()=lyrics(true)
    @Test fun lyricActionsSelectedLight()=lyrics(false,true)
    @Test fun lyricActionsSelectedDark()=lyrics(true,true)
}
