package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h800dp-xhdpi")
class Batch7MiniPlayerTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){ FrostedPrefs.resetForTest();MotionPolicy.setAppReduceMotion(rule.activity,false) }
 private fun capture(name:String){
  val dir=System.getenv("SW_SCREENSHOT_DIR") ?: return
  repeat(3){rule.waitForIdle();shadowOf(Looper.getMainLooper()).idle();Thread.sleep(60)}
  val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
  File(dir,"$name.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
 }
 private fun sample(dark:Boolean,large:Boolean,style:String,compact:Boolean){
  val counts=IntArray(3)
  FrostedPrefs.resetForTest(page=false,tint=true,dock=false)
  MotionPolicy.setAppReduceMotion(rule.activity,large)
  rule.setContent{SoundwaveDesignTheme(isDark=dark){
   val d=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(d.density,if(large)1.5f else 1f)){
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top=40.dp)){
     MiniPlayer(song=Song(id="b7",name="微风",singer="歌手",source="wy",album="专辑"),playing=false,progressState=remember{mutableStateOf(0.42f)},resolvingState=remember{mutableStateOf(false)},compact=compact,style=style,onClick={counts[0]++},onToggle={counts[1]++},onNext={counts[2]++})
    }
   }
  }}
  rule.onNodeWithText("微风").assertIsDisplayed();capture("mini-$style-$dark-$large-$compact")
  rule.onNodeWithContentDescription("播放：微风").performClick()
  rule.onNodeWithText("微风").performClick()
  rule.runOnIdle{assertEquals(1,counts[0]);assertEquals(1,counts[1]);assertEquals(0,counts[2])}
  // A cancelled press must not accidentally toggle playback.
  rule.onNodeWithContentDescription("播放：微风").performTouchInput{down(center);advanceEventTime(30);cancel()}
  rule.mainClock.advanceTimeBy(1000);rule.runOnIdle{assertEquals(1,counts[1])}
 }
 @Test fun defaultLight()=sample(false,false,"default",false)
 @Test fun defaultDarkLargeCompact()=sample(true,true,"default",true)
 @Test fun capsuleDark()=sample(true,false,"capsule",false)
 @Test fun capsuleLightLargeCompact()=sample(false,true,"capsule",true)
}
