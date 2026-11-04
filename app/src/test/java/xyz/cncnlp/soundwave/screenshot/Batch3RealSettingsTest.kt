package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h915dp-xhdpi")
class Batch3RealSettingsTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun verify(dark:Boolean,large:Boolean) {
  val out=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(out!=null)
  val themes=mutableListOf<String>();val subs=mutableListOf<String?>();val toggles=mutableListOf<Boolean>()
  var ink=Color.Unspecified;var paper=Color.Unspecified
  MotionPolicy.setAppReduceMotion(rule.activity,large)
  rule.mainClock.autoAdvance=false
  rule.setContent {
   SoundwaveDesignTheme(isDark=dark) {
    val density=LocalDensity.current
    ink=MaterialTheme.colorScheme.onSurface;paper=MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalDensity provides Density(density.density,if(large)1.5f else 1f)) {
     Box(Modifier.fillMaxSize().background(paper)) {
      MePage(serverUrl="",preferQ="flac",loggedIn=false,user="",status="",sleepRemain=0L,
       onUrl={},onQuality={},onSleep={},themeMode=if(dark)"night" else "light",
       onThemeMode={themes+=it},settingsSub=null,onSettingsSub={subs+=it},autoPlayOnStart=false,onAutoPlay={toggles+=it})
     }
    }
   }
  }
  fun settle(){repeat(5){rule.mainClock.advanceTimeBy(200)};rule.waitForIdle()}
  settle()
  val name="settings-${if(dark)"dark" else "light"}-${if(large)"large-reduced" else "normal"}"
  fun save(suffix:String){val view=rule.activity.window.decorView;val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b));File(out,"$name-$suffix.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
  save("top")
  rule.onNodeWithContentDescription("切换浅色或夜间主题").performClick()
  Assert.assertEquals(listOf(if(dark)"light" else "night"),themes)
  rule.onNodeWithTag("settings_list").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,500f)};settle()
  val title=rule.onNodeWithText("启动 APP 自动播放",useUnmergedTree=true).assertIsDisplayed()
  val layouts=mutableListOf<TextLayoutResult>()
  title.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
  Assert.assertTrue(layouts.isNotEmpty());Assert.assertEquals(ink,layouts.single().layoutInput.style.color)
  Assert.assertFalse("title must not be clipped vertically",layouts.single().didOverflowHeight)
  title.performClick();Assert.assertEquals(listOf(true),toggles)
  rule.onNodeWithText("预解析").performClick();Assert.assertEquals(listOf<String?>("preload"),subs)
  save("rows")
  File(out,"$name-colors.txt").writeText("onSurface=${ink.toArgb()} background=${paper.toArgb()} fontScale=${if(large)1.5 else 1.0} appReduceMotion=${MotionPolicy.appReduceMotion(rule.activity)}\n")
  MotionPolicy.setAppReduceMotion(rule.activity,false)
 }
 @Test fun lightNormal()=verify(false,false)
 @Test fun darkNormal()=verify(true,false)
 @Test fun lightLargeReduced()=verify(false,true)
 @Test fun darkLargeReduced()=verify(true,true)
}
