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
class Player667RoutesTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private var seek=-1f
 private var toggles=0
 private var queueSelections=0
 private var show by mutableStateOf(false)
 private val playbackTime=mutableStateOf(20f)
 private fun render(dark:Boolean,fontScale:Float=1f,layout:String="apple",solid:Int?=null,immersive:Boolean=false,empty:Boolean=false) {
  val out=System.getenv("SW_SCREENSHOT_DIR");Assume.assumeTrue(out!=null)
  LocalStore.prefs(rule.activity).edit().clear().commit()
  if(immersive) PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).setImmersiveCover(layout,true)
  FrostedPrefs.themeMode=if(dark)"night" else "light";FrostedPrefs.pageFrosted=true
  val cover=File(rule.activity.cacheDir,"route-art.png")
  val b=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=android.graphics.Paint()
  p.shader=android.graphics.LinearGradient(0f,0f,600f,600f,intArrayOf(0xFFEAA082.toInt(),0xFF568F9B.toInt(),0xFFEBD7B1.toInt()),null,android.graphics.Shader.TileMode.CLAMP);c.drawRect(0f,0f,600f,600f,p)
  if(solid!=null){p.shader=null;p.color=solid;c.drawRect(0f,0f,600f,600f,p)}
  p.shader=null;p.color=0xFFE9BC63.toInt();if(solid==null)c.drawCircle(350f,230f,115f,p);cover.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
  val song=Song("fixture666","Glass Horizon","Mira Vale","local",cover=cover.toURI().toString(),duration=180000)
  val lines=listOf("A quiet moment","Find the morning light","Let the music stay","Follow every heartbeat","Home is where we go").mapIndexed{i,t->LyricLine(i*10000L,t)}
  Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
  rule.setContent { val d=LocalDensity.current
   CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)){ SoundwaveDesignTheme(isDark=dark){
    FullPlayer(song=if(empty)null else song,queueSnapshot=listOf(song),queueCurrentIndexState=remember{mutableStateOf(0)},playing=true,
      progressState=remember{mutableStateOf(.12f)},currentTimeState=playbackTime,duration=180f,
      resolvingState=remember{mutableStateOf(false)},statusState=remember{mutableStateOf("")},lyrics=lines,lyricIdx=2,showLyrics=show,liked=false,playMode=PlayMode.SEQUENCE,sleepRemain=0L,
      playerLayout=layout,dismissOffsetHolder=remember { createPlayerDismissOffsetHolderForTest() },
      onBack={toggles++},onQueueItem={_,_,_->queueSelections++},onToggleLyrics={show=!show;toggles++},onSeek={seek=it},onPrev={},onToggle={},onNext={},onLike={},onMode={},onSleep={},onDownload={})
   }}
  };settle()
 }
 private fun settle(){repeat(4){rule.waitForIdle();Thread.sleep(120);shadowOf(Looper.getMainLooper()).idle()}}
 private fun save(n:String){settle();val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getenv("SW_SCREENSHOT_DIR"),"667-route-$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun appleQueueReturnsToAppleAndSelectsWithoutChangingCoverPreference() {
  render(false,immersive=true)
  rule.onNodeWithContentDescription("播放队列").performClick();settle();save("apple-queue")
  // r672：Apple 队列 = 共享底部抽屉（DefaultPlayerQueuePage），行的无障碍描述为「歌名 - 歌手，正在播放」。
  rule.onNodeWithText("UP NEXT · 1 首").assertExists()
  rule.onNodeWithContentDescription("Glass Horizon - Mira Vale",substring=true).performClick();settle()
  assertEquals(1,queueSelections)
  // The coordinator intentionally delays queue restoration by 280ms. In Compose tests,
  // wall-clock sleeps do not advance the recomposer's coroutine scheduler once the exit
  // animation is idle. Drive that exact timer, rather than allowing a longer blind wait.
  rule.mainClock.advanceTimeBy(280)
  rule.waitForIdle()
  rule.onNodeWithContentDescription("沉浸歌词").assertExists()
  assertTrue(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
  save("apple-after-queue")
 }
 @Test fun appleLyricsUsesSharedDefaultAnimationFlow() {
  render(false,immersive=true)
  rule.onNodeWithContentDescription("沉浸歌词").performClick();settle()
  rule.onNodeWithText("Let the music stay").assertExists()
  save("apple-immersive-lyrics")
  val before=rule.onNodeWithText("Follow every heartbeat").fetchSemanticsNode().boundsInRoot.top
  rule.runOnIdle { playbackTime.value=30.5f }
  rule.mainClock.advanceTimeBy(1200);rule.waitForIdle()
  val after=rule.onNodeWithText("Follow every heartbeat").fetchSemanticsNode().boundsInRoot.top
  assertTrue("real lyric follow must move the next line toward anchor", after < before)
  rule.onNodeWithText("Follow every heartbeat").performClick();settle()
  assertEquals(30f/180f,seek,0.001f)
  save("apple-lyrics-follow-next")
 }
 @Test fun emptyPlayerCanReturnWithoutResettingPlaybackData() {
  render(false,empty=true)
  rule.onNodeWithText("选择歌曲开始播放").assertExists()
  rule.onNodeWithText("返回").performClick()
  assertEquals(1,toggles)
  save("669-empty-with-return")
 }
 @After fun reset(){FrostedPrefs.resetForTest();FrostedPrefs.themeMode="light"}
 @Test fun whiteThemeBlackCoverStandard(){ render(false,layout=PLAYER_LAYOUT_COVER_ONLY,solid=0xFF000000.toInt());save("standard-white-black-art") }
 @Test fun nightThemeWhiteCoverStandard(){ render(true,layout=PLAYER_LAYOUT_COVER_ONLY,solid=0xFFFFFFFF.toInt());save("standard-night-white-art") }
 @Test fun whiteThemeBlackCoverApple(){ render(false,solid=0xFF000000.toInt(),immersive=true);save("apple-white-black-art") }
 @Test fun nightThemeWhiteCoverApple(){ render(true,solid=0xFFFFFFFF.toInt(),immersive=true);save("apple-night-white-art") }
}
