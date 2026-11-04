package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import xyz.cncnlp.soundwave.ui.player.PlayerBottomControlChromeRenderer
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h800dp-xhdpi")
class Batch8PlayerControlsTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){MotionPolicy.setAppReduceMotion(rule.activity,false)}
 private fun bitmap():Bitmap{rule.waitForIdle();val v=rule.activity.window.decorView;return Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}
 private fun save(b:Bitmap,name:String){val dir=System.getenv("SW_SCREENSHOT_DIR")?:return;File(dir,"$name.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 private fun sample(dark:Boolean,large:Boolean,volumeVisible:Boolean,mode:Boolean){
  val c=IntArray(5)
  MotionPolicy.setAppReduceMotion(rule.activity,large)
  rule.setContent{SoundwaveDesignTheme(isDark=dark){val d=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(d.density,if(large)1.5f else 1f)){
    Column(Modifier.fillMaxSize().background(Color(0xFF24342F)).padding(top=80.dp)){
     PlayerBottomControlChromeRenderer(hiddenVolumeShift=0.dp,progress=0.42f,dragRatio=0.6f,displayTime=60f,duration=180f,playbackAudioFormat=null,dragging=false,
      onDragStart={},onDrag={},onDragEnd={},onSeekTap={},onQuality={},playing=false,playMode=PlayMode.SHUFFLE,volume=0.4f,onVolume={},showVolume=volumeVisible,volumePressed=false,onVolumePressed={},
      onPrev={},onToggle={c[0]++},onNext={},onLyrics={c[1]++},onDevice={c[2]++},onQueue={c[3]++},modeAsBottomAction=mode,onMode={c[4]++},immersiveSelected=true,deviceOn=false,queueOn=true,showDeviceAndQueueActions=true)
    }
   }
  }}
  rule.onNodeWithContentDescription("播放").assertIsDisplayed();save(bitmap(),"controls-$dark-$large-$volumeVisible-$mode")
  rule.onNodeWithContentDescription("播放").performClick()
  rule.onNodeWithContentDescription(if(mode)"播放顺序" else "沉浸歌词").performClick()
  rule.onNodeWithContentDescription("播放设备").performClick();rule.onNodeWithContentDescription("播放队列").performClick()
  rule.runOnIdle{assertArrayEquals(intArrayOf(1,if(mode)0 else 1,1,1,if(mode)1 else 0),c)}
  rule.onNodeWithContentDescription("播放").performTouchInput{down(center);advanceEventTime(30);cancel()}
  rule.runOnIdle{assertEquals(1,c[0])}
 }
 @Test fun lightVolume()=sample(false,false,true,false)
 @Test fun darkLargeVolume()=sample(true,true,true,false)
 @Test fun darkHiddenVolumeMode()=sample(true,false,false,true)
 @Test fun lightLargeHiddenVolume()=sample(false,true,false,false)
 @Test fun progressTapDragAndCancelStayWithCaller(){
  var ratio by mutableStateOf(0.25f);var started=0;var ended=0;var quality=0;val taps=mutableListOf<Float>();val drags=mutableListOf<Float>()
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().background(Color.Black).padding(top=40.dp)){
   Box(Modifier.testTag("progress")){PlayerProgressBlock(ratio,60f,180f,"FLAC",false,{started++},{ratio=it;drags.add(it)},{ended++},{taps.add(it)},{quality++},true)}
  }}}
  val bar=rule.onNodeWithTag("progress")
  bar.performTouchInput{click(Offset(width*0.5f,10f))}
  rule.runOnIdle{assertEquals(1,taps.size);assertEquals(0.5f,taps.single(),0.02f)}
  bar.performTouchInput{down(Offset(width*0.3f,10f));moveTo(Offset(width*0.6f,10f),300);moveTo(Offset(width*0.9f,10f),300);cancel()}
  rule.runOnIdle{assertEquals(1,started);assertEquals(1,ended);assertTrue(drags.size>=2);assertTrue(drags.last()>drags.first());assertTrue(drags.all{it in 0f..1f});assertEquals(1,taps.size)}
  rule.onNodeWithText("FLAC").performClick();rule.runOnIdle{assertEquals(1,quality)}
 }
 @Test fun volumeDragUsesCurrentStateAndCancellationReleases(){
  var volume by mutableStateOf(0.25f);val values=mutableListOf<Float>();val pressed=mutableListOf<Boolean>()
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().background(Color.Black).padding(top=40.dp)){
   Box(Modifier.testTag("volume")){AppleMusicControls(false,PlayMode.SEQUENCE,volume,{volume=it;values.add(it)},true,false,{pressed.add(it)},{},{},{},{},{},{},immersiveSelected=false)}
  }}}
  val y=87f*rule.activity.resources.displayMetrics.density
  rule.onNodeWithTag("volume").performTouchInput{click(Offset(width*0.5f,y))}
  rule.runOnIdle{assertEquals(1,values.size);assertEquals(0.5f,values.last(),0.02f)}
  rule.onNodeWithTag("volume").performTouchInput{down(Offset(width*0.3f,y));moveTo(Offset(width*0.6f,y),300);moveTo(Offset(width*0.9f,y),300);cancel()}
  rule.runOnIdle{assertEquals(listOf(true,false),pressed);assertTrue(values.size>=3);assertTrue(values.last()>values[1]);assertTrue(values.all{it in 0f..1f})}
 }
 @Test fun reducedMotionBottomActionDoesNotScale(){
  MotionPolicy.setAppReduceMotion(rule.activity,true)
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().background(Color.Black).padding(60.dp)){
   AppleMusicBottomAction(xyz.cncnlp.soundwave.R.drawable.ic_player_action_play_queue,"队列",selected=true,onClick={})
  }}}
  rule.mainClock.autoAdvance=false
  val before=bitmap();save(before,"reduce-before-press")
  rule.onNodeWithContentDescription("队列").performTouchInput{down(center)}
  rule.mainClock.advanceTimeBy(1000);val pressed=bitmap();save(pressed,"reduce-during-press")
  rule.onNodeWithContentDescription("队列").performTouchInput{cancel()}
  val a=IntArray(before.width*before.height);val b=IntArray(a.size)
  before.getPixels(a,0,before.width,0,0,before.width,before.height);pressed.getPixels(b,0,pressed.width,0,0,pressed.width,pressed.height)
  assertArrayEquals("Reduced-motion bottom action should keep its glyph transform unchanged",a,b)
 }
}
