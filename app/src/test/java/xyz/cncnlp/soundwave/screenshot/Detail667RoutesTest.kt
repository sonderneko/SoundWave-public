package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w412dp-h1100dp-xhdpi")
class Detail667RoutesTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private fun detail(dark:Boolean){
  LocalStore.prefs(rule.activity).edit().clear().commit();FrostedPrefs.resetForTest()
  val f=File(rule.activity.cacheDir,"detail667.png")
  val b=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888);Canvas(b).drawColor(0xff667788.toInt());f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
  val song=Song("row667","First track","Local artist","wy",cover=f.absolutePath)
  val pl=Playlist("fixture667","Detail fixture",source="wy",cover=f.absolutePath,author="Local author",count=2)
  var loading by mutableStateOf(true);var plays=0
  Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
  rule.setContent { SoundwaveDesignTheme(isDark=dark){Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
   LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=pl,detailSongs=listOf(song,song.copy(id="row668",name="Second track")),detailLoading=loading,onOpen={},onBack={},onPlay={_,_->plays++},onLogin={},onImport={},detailCoverStyle=DetailCoverStyles.DEFAULT)
  }}}
  rule.waitForIdle()
  val before=rule.onNodeWithText("First track").fetchSemanticsNode().boundsInRoot
  val moreBefore=rule.onNodeWithContentDescription("更多：First track").fetchSemanticsNode().boundsInRoot
  rule.runOnIdle{loading=false};rule.mainClock.advanceTimeBy(600);rule.waitForIdle()
  assertEquals(before,rule.onNodeWithText("First track").fetchSemanticsNode().boundsInRoot)
  assertEquals(moreBefore,rule.onNodeWithContentDescription("更多：First track").fetchSemanticsNode().boundsInRoot)
  rule.onNodeWithText("First track").performClick();rule.runOnIdle{assertEquals(1,plays)}
  val v=rule.activity.window.decorView;val out=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(out))
  System.getenv("SW_SCREENSHOT_DIR")?.let{File(it,"667-detail-default-${if(dark)"night" else "light"}.png").outputStream().use{o->out.compress(Bitmap.CompressFormat.PNG,100,o)}}
 }
 @After fun reset(){FrostedPrefs.resetForTest()}
 @Test fun defaultDetailStableAndInteractiveLight(){detail(false)}
 @Test fun defaultDetailStableAndInteractiveNight(){detail(true)}
}
