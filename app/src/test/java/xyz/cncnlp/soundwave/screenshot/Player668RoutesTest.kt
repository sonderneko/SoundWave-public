package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
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
class Player668RoutesTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private var seek=-1f
 private var toggles=0
 private var queueSelections=0
 private var show by mutableStateOf(false)
 private val playbackTime=mutableStateOf(20f)
 private fun render(dark:Boolean,fontScale:Float=1f,layout:String="apple",solid:Int?=null,immersive:Boolean=false) {
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
    FullPlayer(song=song,queueSnapshot=listOf(song),queueCurrentIndexState=remember{mutableStateOf(0)},playing=true,
      progressState=remember{mutableStateOf(.12f)},currentTimeState=playbackTime,duration=180f,
      resolvingState=remember{mutableStateOf(false)},statusState=remember{mutableStateOf("")},lyrics=lines,lyricIdx=2,showLyrics=show,liked=false,playMode=PlayMode.SEQUENCE,sleepRemain=0L,
      playerLayout=layout,dismissOffsetHolder=remember { createPlayerDismissOffsetHolderForTest() },
      onQueueItem={_,_,_->queueSelections++},onToggleLyrics={show=!show;toggles++},onSeek={seek=it},onPrev={},onToggle={},onNext={},onLike={},onMode={},onSleep={},onDownload={})
   }}
  };settle()
 }
 private fun settle(){repeat(4){rule.waitForIdle();Thread.sleep(120);shadowOf(Looper.getMainLooper()).idle()}}
 private fun save(n:String){settle();val v=rule.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getenv("SW_SCREENSHOT_DIR"),"668-route-$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
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

 private fun frame(n:String): Bitmap {
  rule.waitForIdle()
  val v=rule.activity.window.decorView
  return Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also { b ->
   v.draw(Canvas(b)); File(System.getenv("SW_SCREENSHOT_DIR"),"668-frame-$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
  }
 }
 @Test fun appleCoverAnimationBothDirectionsAndRapidReversal() {
  render(false)
  val cover=rule.onNodeWithContentDescription("封面，点按切换封面呈现")
  val normalY=rule.onNodeWithText("Glass Horizon").fetchSemanticsNode().boundsInRoot.top
  val normalH=cover.fetchSemanticsNode().boundsInRoot.height
  val artwork=rule.onNodeWithContentDescription("Apple连续封面图像",useUnmergedTree=true)
  val normalArtwork=artwork.fetchSemanticsNode().boundsInRoot
  frame("normal")
  rule.mainClock.autoAdvance=false
  cover.performClick();rule.mainClock.advanceTimeBy(160);rule.waitForIdle()
  val midY=rule.onNodeWithText("Glass Horizon").fetchSemanticsNode().boundsInRoot.top
  val midH=cover.fetchSemanticsNode().boundsInRoot.height
  frame("enter-160ms")
  val midArtwork=artwork.fetchSemanticsNode().boundsInRoot
  rule.mainClock.advanceTimeBy(500);rule.waitForIdle()
  val immersiveY=rule.onNodeWithText("Glass Horizon").fetchSemanticsNode().boundsInRoot.top
  val immersiveH=cover.fetchSemanticsNode().boundsInRoot.height
  frame("immersive")
  val heroArtwork=artwork.fetchSemanticsNode().boundsInRoot
  assertTrue("one artwork expands across full screen width",heroArtwork.width > normalArtwork.width + 20f)
  assertTrue("hero reaches top instead of a clipped translated strip",heroArtwork.top < 4f && heroArtwork.height > 350f*rule.activity.resources.displayMetrics.density)
  assertTrue("artwork has real intermediate top geometry",midArtwork.top < normalArtwork.top && midArtwork.top > heroArtwork.top)
  assertTrue("cover moves through intermediate geometry", midH > minOf(normalH,immersiveH)+1 && midH < maxOf(normalH,immersiveH)-1)
  assertEquals("metadata remains anchored during morph",normalY,midY,1f)
  assertEquals(normalY,immersiveY,1f)
  assertTrue(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
  // The hero image is translated above its animated parent. Tap a point visibly on that
  // protruding artwork but outside the parent's hit bounds; the preceding spacer must
  // keep the whole cover toggleable even where normal parent hit testing cannot reach.
  val parentTop=cover.fetchSemanticsNode().boundsInRoot.top
  val rootBounds=rule.onRoot(useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
  val protrudingTap=Offset(heroArtwork.center.x-rootBounds.left,parentTop-10f-rootBounds.top)
  assertTrue("tap point is on the visible hero artwork",heroArtwork.top < parentTop-10f && heroArtwork.bottom > parentTop-10f)
  assertTrue("tap point is above the moving container",parentTop-10f < cover.fetchSemanticsNode().boundsInRoot.top)
  rule.onRoot(useUnmergedTree=true).performTouchInput { click(protrudingTap) }
  rule.waitForIdle()
  assertFalse("tapping protruding cover reverses the persistent presentation",PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
  rule.mainClock.advanceTimeBy(160);frame("exit-160ms")
  val exitH=cover.fetchSemanticsNode().boundsInRoot.height
  assertTrue("exit also moves through intermediate geometry",exitH > minOf(normalH,immersiveH)+1 && exitH < maxOf(normalH,immersiveH)-1)
  assertEquals(normalY,rule.onNodeWithText("Glass Horizon").fetchSemanticsNode().boundsInRoot.top,1f)
  cover.performClick();rule.mainClock.advanceTimeBy(80);frame("rapid-reverse")
  cover.performClick();rule.mainClock.advanceTimeBy(600);rule.waitForIdle();frame("normal-restored")
  assertFalse(PlayerPresentationPreferences(LocalStore.prefs(rule.activity)).immersiveCover("apple"))
  assertEquals(normalY,rule.onNodeWithText("Glass Horizon").fetchSemanticsNode().boundsInRoot.top,1f)
  rule.mainClock.autoAdvance=true
 }
 @Test fun compactDefaultLyricsAndTailFramesKeepHeaderAndCoverSlotsStable() {
  render(false,layout="default")
  frame("default-normal")
  rule.mainClock.autoAdvance=false
  rule.onNodeWithContentDescription("Glass Horizon 封面，点按查看歌词").performClick()
  rule.mainClock.advanceTimeBy(320);frame("default-enter-320ms")
  val mini=rule.onNodeWithContentDescription("Glass Horizon 封面，点按返回")
  val y=mini.fetchSemanticsNode().boundsInRoot.top
  rule.mainClock.advanceTimeBy(64);frame("default-enter-384ms")
  assertEquals(y,mini.fetchSemanticsNode().boundsInRoot.top,1f)
  rule.mainClock.advanceTimeBy(32);frame("default-enter-416ms")
  rule.mainClock.advanceTimeBy(200);frame("default-lyrics-final")
  val bounds=mini.fetchSemanticsNode().boundsInRoot
  val density=rule.activity.resources.displayMetrics.density
  assertTrue("default mini cover stays near top, not below an immersive hero",bounds.top < 130f*density)
  assertEquals(52f*density,bounds.width,2f)
  rule.onNodeWithContentDescription("沉浸歌词封面").assertDoesNotExist()
  rule.onNodeWithText("Let the music stay").assertExists()
  mini.performClick();rule.mainClock.advanceTimeBy(600);frame("default-restored")
  rule.mainClock.autoAdvance=true
 }
 @Test fun standardImmersiveLyricsRetainsLargeHeroAndSeparateMetadataRow() {
  render(false,layout=PLAYER_LAYOUT_COVER_ONLY)
  rule.onNodeWithContentDescription("Glass Horizon 沉浸封面，点按查看歌词").performClick();settle()
  rule.onNodeWithContentDescription("沉浸歌词封面").assertExists()
  rule.onNodeWithContentDescription("Glass Horizon 封面，点按返回").assertDoesNotExist()
  val title=rule.onAllNodesWithText("Glass Horizon").fetchSemanticsNodes().last().boundsInRoot
  assertTrue("standard title remains below large artwork",title.top > 200f*rule.activity.resources.displayMetrics.density)
  frame("standard-immersive-lyrics-reference")
 }
 @Test fun aboutHidesPersonalProjectAndBuildKeepsVersionUpdateAndLicenses() {
  FrostedPrefs.themeMode="light"
  rule.setContent { SoundwaveDesignTheme(isDark=false) { AboutAppPage(status="",serverUrl="",onCheckUpdate={},onOpenDataFlow={}) } }
  rule.onNodeWithText("检查更新").assertExists()
  rule.onNodeWithText("构建号").assertDoesNotExist()
  rule.onNodeWithText("项目地址").assertDoesNotExist()
  rule.onNodeWithText("版本号").performScrollTo().assertExists()
  rule.onNodeWithText("开源许可").performScrollTo().assertExists()
  frame("about-licenses")
 }
 @Test fun aboutNightUsesMatchingThemeAndReadableRows() {
  FrostedPrefs.themeMode="night"
  rule.setContent { SoundwaveDesignTheme(isDark=true) { AboutAppPage(status="",serverUrl="",onCheckUpdate={},onOpenDataFlow={}) } }
  rule.onNodeWithText("版本号").performScrollTo().assertExists()
  rule.onNodeWithText("开源许可").performScrollTo().assertExists()
  frame("about-night-licenses")
 }
 @After fun reset(){FrostedPrefs.resetForTest();FrostedPrefs.themeMode="light"}
 @Test fun whiteThemeBlackCoverStandard(){ render(false,layout=PLAYER_LAYOUT_COVER_ONLY,solid=0xFF000000.toInt());save("standard-white-black-art") }
 @Test fun nightThemeWhiteCoverStandard(){ render(true,layout=PLAYER_LAYOUT_COVER_ONLY,solid=0xFFFFFFFF.toInt());save("standard-night-white-art") }
 @Test fun whiteThemeBlackCoverApple(){ render(false,solid=0xFF000000.toInt(),immersive=true);save("apple-white-black-art") }
 @Test fun nightThemeWhiteCoverApple(){ render(true,solid=0xFFFFFFFF.toInt(),immersive=true);save("apple-night-white-art") }
}
