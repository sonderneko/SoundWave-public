package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.boundsInWindow
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
import xyz.cncnlp.soundwave.ui.player.PlayerImmersiveCoverHero
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xhdpi")
class Player667VisualTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun save(name:String,b:Bitmap){val d=System.getenv("SW_SCREENSHOT_DIR");if(d!=null)File(d,"667-visual-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 private fun mask(blackArt:Boolean){
  val background=if(blackArt)Color.White else Color.Black
  val artwork=if(blackArt)Color.Black else Color.White
  rule.setContent { Box(Modifier.fillMaxSize().background(background)) {
   PlayerImmersiveCoverHero(Modifier.size(300.dp,400.dp).testTag("hero"),ColorPainter(artwork),"test cover",onClick={})
  }}
  rule.waitForIdle()
  val rect=rule.onNodeWithTag("hero").fetchSemanticsNode().layoutInfo.coordinates.boundsInWindow()
  val v=rule.activity.window.decorView
  val full=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(full))
  val b=Bitmap.createBitmap(full,rect.left.toInt(),rect.top.toInt(),rect.width.toInt(),rect.height.toInt())
  val x=b.width/2
  fun channel(y:Int)=(b.getPixel(x,y) shr 16) and 255
  val samples=(b.height*0.66f).toInt() until b.height
  val values=samples.map{channel(it)}
  assertTrue("each fade row is monotonic",values.zipWithNext().all{(a,c)->if(blackArt)c>=a-1 else c<=a+1})
  assertTrue("no final crop line",kotlin.math.abs(channel(b.height-1)-(if(blackArt)255 else 0))<=1)
  assertTrue("fade spans many levels, not a hard edge",values.distinct().size>80)
  assertTrue("no abrupt fade step",values.zipWithNext().all{(a,c)->kotlin.math.abs(a-c)<=4})
  save(if(blackArt)"black-art-white-backdrop" else "white-art-black-backdrop",b)
 }
 @Test fun nativeFadeBlackArtWhiteBackground(){mask(true)}
 @Test fun nativeFadeWhiteArtBlackBackground(){mask(false)}
 private fun cards(dark:Boolean){
  rule.setContent { SoundwaveDesignTheme(isDark=dark) {
   Column(Modifier.fillMaxSize().background(if(dark) Color(0xff161616) else Color.White).padding(14.dp)) {
    Spacer(Modifier.height(16.dp))
    HomeDigitalAlbumCard(DiscoverCard("d","Album","",artist="Artist",count=6),Color(0xff667788)){}
    Spacer(Modifier.height(32.dp))
    Box(Modifier.width(300.dp).height(172.dp)) {
     HomeDebutCard(DiscoverCard("a","New album","",artist="Artist",count=2),0,Color(0xff667788),Modifier.padding(top=8.dp,bottom=14.dp)){}
    }
   }
  }};rule.waitForIdle()
  val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));save(if(dark)"cards-night" else "cards-light",b)
  rule.onNodeWithText("Album").assertExists();rule.onNodeWithText("New album").assertExists()
 }
 @Test fun wholeCardsLight(){cards(false)}
 @Test fun wholeCardsNight(){cards(true)}
}
