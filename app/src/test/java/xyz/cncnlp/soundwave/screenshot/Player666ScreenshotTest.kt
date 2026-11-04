package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import xyz.cncnlp.soundwave.ui.player.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xhdpi")
class Player666ScreenshotTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun save(n:String){rule.waitForIdle();val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));System.getenv("SW_SCREENSHOT_DIR")?.let{File(it,"666-$n.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
 @After fun reset(){FrostedPrefs.themeMode="light";FrostedPrefs.resetForTest();MotionPolicy.setAppReduceMotion(rule.activity,false)}
 private fun frost(theme:String,tint:Boolean){
  rule.setContent{SoundwaveDesignTheme(isDark=theme=="night"){
   SideEffect {FrostedPrefs.themeMode=theme;FrostedPrefs.pageFrosted=true;FrostedPrefs.coverTint=tint;FrostedPrefs.tone=Color(0xFF173428)}
   Box(Modifier.fillMaxSize().background(if(theme=="night")Color(0xFF171717) else Color.White)){FrostedPageBackdrop(Modifier.fillMaxSize(),theme=="night")}
  }}
  rule.runOnIdle{assertEquals(theme=="light",FrostedPrefs.pageActive);assertTrue(FrostedPrefs.pageFrosted)}
  save("frost-$theme-$tint")
 }
 @Test fun whiteTint()=frost("light",true)
 @Test fun whiteNeutral()=frost("light",false)
 @Test fun nightUnchanged()=frost("night",true)
 @Test fun adaptiveUnchanged()=frost("dark",true)
 @Test fun nextButtonNarrowVisualKeeps48DpTapTarget(){
  var taps=0
  rule.setContent{SoundwaveDesignTheme(isDark=false){PlayerLyricsNextButton(Color.Black,{taps++})}}
  rule.onNodeWithContentDescription("下一首").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
  rule.runOnIdle{assertEquals(1,taps)}
 }
 @Test fun interruptedCoverZoomRemainsContinuous(){
  var playing by mutableStateOf(false);lateinit var observed:State<Float>
  fun sample()=observed.value
  val state=DefaultPlayerState(Song("fixture","Song","Artist","local"),emptyList(),0,false,false,"",false,PlayMode.SEQUENCE,0f,0f,100f,"",0.5f,false,emptyList(),0L,false)
  MotionPolicy.setAppReduceMotion(rule.activity,false)
  rule.mainClock.autoAdvance=false
  rule.setContent{observed=rememberDefaultCoverScale(state.copy(playing=playing))}
  rule.mainClock.advanceTimeBy(32);rule.waitForIdle();val start=sample()
  rule.runOnIdle{playing=true};rule.waitForIdle();rule.mainClock.advanceTimeByFrame();rule.waitForIdle();repeat(6){rule.mainClock.advanceTimeBy(32);rule.waitForIdle();println("COVER_STEP $it value=${sample()} time=${rule.mainClock.currentTime}")};val mid=sample()
  rule.runOnIdle{playing=false};rule.mainClock.advanceTimeBy(16);rule.waitForIdle();val interrupt=sample()
  println("COVER_TRACE start=$start mid=$mid interrupt=$interrupt time=${rule.mainClock.currentTime}")
  assertTrue("mid=$mid must exceed start=$start",mid>start);assertTrue("interrupt=$interrupt mid=$mid",kotlin.math.abs(interrupt-mid)<.02f)
  rule.mainClock.advanceTimeBy(1800);rule.waitForIdle();assertEquals(.92f,sample(),.001f)
 }
}
