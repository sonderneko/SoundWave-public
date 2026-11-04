package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.io.File

/** Calls the actual Home entry composables, with local image fixture. Not a handset screenshot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h960dp-xhdpi")
class Player666HomeShadowScreenshotTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun render(dark:Boolean, group:Int) {
  val output=System.getenv("SW_SCREENSHOT_DIR");Assume.assumeTrue(output!=null)
  val image=Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888)
  val c=Canvas(image);val p=android.graphics.Paint()
  p.shader=android.graphics.LinearGradient(0f,0f,500f,500f,intArrayOf(0xFFEBD7C1.toInt(),0xFF5A9E99.toInt(),0xFF937CAC.toInt()),null,android.graphics.Shader.TileMode.CLAMP);c.drawRect(0f,0f,500f,500f,p)
  p.shader=null;p.color=0xFFE2A75E.toInt();c.drawCircle(230f,200f,110f,p)
  val file=File(rule.activity.cacheDir,"home-shadow-fixture.png");file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
  val uri=file.toURI().toString();val song=Song("fixture","Fixture song","Fixture artist","local",cover=uri)
  val card=DiscoverCard("fixture","Fixture album",cover=uri,artist="Fixture artist",count=8)
  Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
  rule.setContent{SoundwaveDesignTheme(isDark=dark){Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(vertical=18.dp)){
   if(group==0){
    Text("甄选歌单 / 热门歌手",Modifier.padding(horizontal=18.dp))
    Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.SpaceAround){
     HomeCuratedCard("Fixture playlist","Local fixture",cover={m->Image(image.asImageBitmap(),null,m,contentScale=ContentScale.Crop)},cardWidth=123.dp)
     HomeArtistCard("Fixture artist",uri)
    }
    Text("热门歌曲",Modifier.padding(horizontal=18.dp));HomeHotSongsPager(listOf(song),"local","",false,{}, {_,_->})
    Text("新歌速递",Modifier.padding(horizontal=18.dp));HomeNewSongList{HomeNewSongRow(song,1,"local",false,{})}
    Text("数字专辑",Modifier.padding(18.dp));Box(Modifier.padding(horizontal=18.dp)){HomeDigitalAlbumCard(card,Color(0xFF945C7C),onClick={})}
    Text("新碟首发",Modifier.padding(18.dp));Box(Modifier.fillMaxWidth().padding(horizontal=18.dp).height(150.dp)){HomeDebutCard(card,0,Color(0xFF657D63),onClick={})}
   }else{
    Text("首页歌单广场 / 排行榜 / 心动模式（真实轮播）",Modifier.padding(horizontal=14.dp))
    HomeFeatureCarousel(false,song,uri,uri,{},{},{},playlistCoverBack=uri,chartCoverBack=uri)
   }
  }}}
  settle()
  save(output!!,"666-shadows-${if(dark)"dark" else "light"}-$group.png")
  if(group==1){
   // The real carousel's three existing selectors are the only 8dp/26dp wide clickable nodes.
   val candidates=rule.onAllNodes(hasClickAction(),useUnmergedTree=true).fetchSemanticsNodes().filter{it.boundsInRoot.height < 30f}
   for(i in 1..2){val node=candidates.getOrNull(i);if(node!=null){rule.onNode(SemanticsMatcher("selector") {it.id==node.id},useUnmergedTree=true).performClick();settle();save(output,"666-shadows-${if(dark)"dark" else "light"}-feature-$i.png")}}
  }
 }
 private fun settle(){repeat(6){rule.waitForIdle();Thread.sleep(120);shadowOf(Looper.getMainLooper()).idle()}}
 private fun save(output:String,n:String){val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(output,n).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun homeLight()=render(false,0)
 @Test fun homeDark()=render(true,0)
 @Test fun featureLight()=render(false,1)
 @Test fun featureDark()=render(true,1)
}
