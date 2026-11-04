package xyz.cncnlp.soundwave.screenshot
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
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
import xyz.cncnlp.soundwave.ui.player.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h800dp-xhdpi")
class Batch9SeekQueueTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){MotionPolicy.setAppReduceMotion(rule.activity,false)}
 private fun seek(default:Boolean, cancel:Boolean=false, restart:Boolean=false, remove:Boolean=false){
  var ratio by mutableStateOf(0.25f);var duration by mutableStateOf(180f);var visible by mutableStateOf(true)
  var dragging=false;var starts=0;var ends=0;val taps=mutableListOf<Float>();val values=mutableListOf<Float>()
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().padding(top=40.dp)){
   if(visible) Box(Modifier.testTag("seek")){
    if(default) DefaultPlayerSeekBar(ratio,defaultPlayerColors(),onSeekTap={taps.add(it)},onDragStart={dragging=true;starts++},onDrag={ratio=it;values.add(it)},onDragEnd={dragging=false;ends++},onDragCancel={dragging=false})
    else PlayerProgressBlock(ratio,60f,duration,"",dragging,{dragging=true;starts++},{ratio=it;values.add(it)},{dragging=false;ends++},{taps.add(it)},{},true,onDragCancel={dragging=false})
   }
  }}}
  val node=rule.onNodeWithTag("seek")
  node.performTouchInput{click(Offset(width*.5f,10f))}
  rule.runOnIdle{assertEquals(1,taps.size);assertEquals(.5f,taps.single(),.02f)}
  node.performTouchInput{down(Offset(width*.2f,10f));moveTo(Offset(width*.5f,10f),200);moveTo(Offset(width*.8f,10f),200)}
  rule.runOnIdle{assertEquals(1,starts);assertTrue(values.isNotEmpty());assertEquals(0,ends)}
  if(restart) rule.runOnIdle{duration=200f}
  else if(remove) rule.runOnIdle{visible=false}
  else node.performTouchInput{if(cancel)cancel() else up()}
  rule.runOnIdle{
   assertEquals(if(cancel||restart||remove)0 else 1,ends)
   assertFalse("Gesture termination must release preview state",dragging)
   assertEquals(1,taps.size)
  }
 }
 @Test fun classicCancelDoesNotCommitSeek()=seek(false,true)
 @Test fun classicUpCommitsExactlyOnce()=seek(false)
 @Test fun defaultCancelDoesNotCommitSeek()=seek(true,true)
 @Test fun defaultUpCommitsExactlyOnce()=seek(true)
 @Test fun classicDurationRestartDoesNotCommitSeek()=seek(false,restart=true)
 @Test fun classicRemovalReleasesPreviewWithoutSeek()=seek(false,remove=true)
 @Test fun defaultRemovalReleasesPreviewWithoutSeek()=seek(true,remove=true)
 // r672：Apple 专用整页队列（PlayerQueuePanel）已删除，所有样式共用「默认 / 标准」底部抽屉队列。
 private fun queue(dark:Boolean,large:Boolean){
  MotionPolicy.setAppReduceMotion(rule.activity,large)
  val songs=listOf(Song("b9a","First track","Artist A"),Song("b9b","Second track","Artist B"));var picked=-1
  val state=DefaultPlayerState(songs[0],songs,0,playing=true,resolving=false,status="",liked=false,playMode=PlayMode.SEQUENCE,
   progress=0f,displayTime=0f,duration=180f,quality="",volume=.5f,showVolume=false,lyrics=emptyList(),lyricTimeMs=0L,showTranslation=false)
  val actions=DefaultPlayerActions({},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{},{_,_,i->picked=i},{_,_->},{},{},{})
  rule.setContent{SoundwaveDesignTheme(isDark=dark){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,if(large)1.5f else 1f)){
   Box(Modifier.fillMaxSize().background(Color(0xFF24342F)).padding(top=30.dp)){
    DefaultPlayerQueuePage(state,actions,rememberLazyListState())
   }
  }}}
  rule.onNodeWithText("播放队列").assertIsDisplayed()
  rule.onNodeWithText("UP NEXT · 2 首").assertIsDisplayed()
  rule.onNodeWithContentDescription("定位当前播放歌曲").assertIsDisplayed()
  rule.waitForIdle();val v=rule.activity.window.decorView
  val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}
  System.getenv("SW_SCREENSHOT_DIR")?.let{dir->File(dir,"queue-$dark-$large.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
  rule.onNodeWithContentDescription("Second track - Artist B").performClick();rule.runOnIdle{assertEquals(1,picked)}
 }
 @Test fun lightQueue()=queue(false,false)
 @Test fun darkQueue()=queue(true,false)
 @Test fun lightLargeReducedQueue()=queue(false,true)
 @Test fun darkLargeReducedQueue()=queue(true,true)
}
