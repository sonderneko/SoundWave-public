package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import xyz.cncnlp.soundwave.ui.home.*
import java.io.File

/** Actual reusable home/detail components, synthetic local data; not a phone/network test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w390dp-h845dp-xhdpi")
class Playlist666InteractionTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun save(name:String){rule.waitForIdle();val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));System.getenv("SW_SCREENSHOT_DIR")?.let{File(it,"666-$name.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
 @After fun reset(){FrostedPrefs.resetForTest()}
 @Test fun curatedPlayConsumesTapWhileCoverStillNavigates(){
  var opens=0;var plays=0
  rule.setContent { SoundwaveDesignTheme(isDark=false){
   HomeCuratedCard("Curated fixture","Playlist",cardWidth=160.dp,
    cover={Box(Modifier.testTag("curated-cover")){FoldedCoverCard("",160.dp,Color.Blue,source="wy",itemId="fixture",onClick={opens++})}}){
    CoverRevealPlay(1f,"播放：Curated fixture"){plays++}
   }
  }}
  rule.onNodeWithContentDescription("播放：Curated fixture").performTouchInput{click()}
  rule.runOnIdle{assertEquals(1,plays);assertEquals(0,opens)}
  rule.onNodeWithTag("curated-cover").performTouchInput{click(androidx.compose.ui.geometry.Offset(20f,20f))}
  rule.runOnIdle{assertEquals(1,plays);assertEquals(1,opens)};save("curated-direct-play")
 }
 @Test fun plazaRevealPlayDoesNotOpenAndCardRetainsNavigation(){
  var opens=0;var plays=0
  val pl=Playlist("123456","Plaza fixture",source="wy")
  rule.setContent { SoundwaveDesignTheme(isDark=false){LazyColumn{homePlaylistGridRows(listOf(pl),"wy","网易云",Color.Blue,Color.Black,onPlayPlaylist={plays++},onOpenPlaylist={opens++})}}}
  rule.onNodeWithText("Plaza fixture").performTouchInput{longClick()};rule.mainClock.advanceTimeBy(300);rule.waitForIdle()
  rule.onNodeWithContentDescription("播放：Plaza fixture").performTouchInput{click()}
  rule.runOnIdle{assertEquals(1,plays);assertEquals(0,opens)}
  rule.onNodeWithText("Plaza fixture").performClick()
  rule.runOnIdle{assertEquals(1,plays);assertEquals(1,opens)};save("plaza-direct-play")
 }
 private fun detail(dark:Boolean){
  var favorites=0;var more=0;var searches=0;var plays=0
  rule.setContent { SoundwaveDesignTheme(isDark=dark){
   val glass=rememberLocalGlassState()
   Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
    Column(Modifier.fillMaxSize().localGlassSource(glass)){
     ImmersiveDetailHero("Local detail fixture","Artist",listOf("12 tracks"),"",null,Color(0xFF79A8AA),false,true,true,{favorites++},{plays++},null,{more++})
    }
    ImmersiveDetailFloatingBar(true,{}, {more++},glass=glass,title="歌单详情",onSearch={searches++})
   }
  }}
  rule.onNodeWithContentDescription("收藏").performClick();rule.runOnIdle{assertEquals(1,favorites)}
  rule.onNodeWithContentDescription("更多操作").performClick();rule.runOnIdle{assertEquals(1,more)}
  rule.onNodeWithContentDescription("搜索曲目").performClick();rule.runOnIdle{assertEquals(1,searches)}
  save(if(dark)"detail-frost-night" else "detail-frost-white")
 }
 @Test fun detailWhiteFrostKeepsActions()=detail(false)
 @Test fun detailNightFrostKeepsActions()=detail(true)
}
