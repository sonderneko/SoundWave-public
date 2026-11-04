package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import java.io.File

/** FullPlayer actual route, local artwork/lyrics fixture; not a handset or real-service test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w360dp-h800dp-xhdpi")
class Player666RoutesTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private var seek=-1f
 private var toggles=0
 private var show by mutableStateOf(false)
 private fun render(dark:Boolean,fontScale:Float=1f,layout:String="apple") {
  val out=System.getenv("SW_SCREENSHOT_DIR");Assume.assumeTrue(out!=null)
  LocalStore.prefs(rule.activity).edit().clear().commit()
  FrostedPrefs.themeMode=if(dark)"night" else "light";FrostedPrefs.pageFrosted=true
  val cover=File(rule.activity.cacheDir,"route-art.png")
  val b=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=android.graphics.Paint()
  p.shader=android.graphics.LinearGradient(0f,0f,600f,600f,intArrayOf(0xFFEAA082.toInt(),0xFF568F9B.toInt(),0xFFEBD7B1.toInt()),null,android.graphics.Shader.TileMode.CLAMP);c.drawRect(0f,0f,600f,600f,p)
  p.shader=null;p.color=0xFFE9BC63.toInt();c.drawCircle(350f,230f,115f,p);cover.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
  val song=Song("fixture666","Glass Horizon","Mira Vale","local",cover=cover.toURI().toString(),duration=180000)
  val lines=listOf("A quiet moment","Find the morning light","Let the music stay","Follow every heartbeat","Home is where we go").mapIndexed{i,t->LyricLine(i*10000L,t)}
  Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
  rule.setContent { val d=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)){ SoundwaveDesignTheme(isDark=dark){
    FullPlayer(song=song,queueSnapshot=listOf(song),queueCurrentIndexState=remember{mutableStateOf(0)},playing=true,
      progressState=remember{mutableStateOf(.12f)},currentTimeState=remember{mutableStateOf(20f)},duration=180f,
      resolvingState=remember{mutableStateOf(false)},statusState=remember{mutableStateOf("")},lyrics=lines,lyricIdx=2,showLyrics=show,liked=false,playMode=PlayMode.SEQUENCE,sleepRemain=0L,
      playerLayout=layout,dismissOffsetHolder=remember { createPlayerDismissOffsetHolderForTest() },
      onToggleLyrics={show=!show;toggles++},onSeek={seek=it},onPrev={},onToggle={},onNext={},onLike={},onMode={},onSleep={},onDownload={})
   }}
  };settle()
 }
 private fun settle(){repeat(4){rule.waitForIdle();Thread.sleep(120);shadowOf(Looper.getMainLooper()).idle()}}
 private fun save(n:String){settle();val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getenv("SW_SCREENSHOT_DIR"),"666-route-$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 @After fun reset(){FrostedPrefs.resetForTest();FrostedPrefs.themeMode="light"}
 @Test fun appleCoverToggleAndQueue(){
  render(false);save("apple-white-cover")
  rule.onNodeWithContentDescription("封面，点按切换封面呈现").performClick();settle()
  assertEquals(0,toggles);assertTrue(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"));save("apple-white-immersive-cover")
  rule.onNodeWithContentDescription("封面，点按切换封面呈现").performClick();settle()
  assertFalse(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"));assertEquals(0,toggles)
  rule.onNodeWithContentDescription("播放队列").performClick();save("apple-default-queue")
  rule.onNodeWithText("播放队列",substring=true).assertExists()
 }
 @Test fun appleLyricsClickAndLongPress(){
  render(true);rule.onNodeWithContentDescription("沉浸歌词").performClick();save("apple-night-lyrics")
  rule.onNodeWithText("Let the music stay").performClick();settle();assertEquals(20f/180f,seek,.001f)
  rule.onNodeWithText("Let the music stay").performTouchInput{longClick()};settle()
  rule.onNodeWithText("当前歌词").assertExists();save("apple-lyrics-actions")
 }
 @Test @Config(qualifiers="w320dp-h740dp-xhdpi") fun appleNarrowLargeFont(){
  render(false,1.3f);save("apple-white-narrow-large")
  rule.onNodeWithContentDescription("沉浸歌词").performClick();save("apple-white-narrow-large-lyrics")
  rule.onNodeWithText("Let the music stay").assertExists()
 }
 @Test fun defaultLyricsRetained(){
  render(false,layout="default");rule.onNodeWithContentDescription("Glass Horizon 封面，点按查看歌词").performClick();settle();assertEquals(1,toggles)
  save("default-lyrics");rule.onNodeWithText("Let the music stay").assertExists()
 }
}
