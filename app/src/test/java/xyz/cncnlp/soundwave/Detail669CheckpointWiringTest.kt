package xyz.cncnlp.soundwave

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
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
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import java.io.File

/** Real 600-row LibraryPage navigation and persistent checkpoint observation.
 * Compose virtual time proves debounce/restore behavior, not handset frame times. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w411dp-h860dp-xhdpi")
class Detail669CheckpointWiringTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 private val pl=Playlist("checkpoint669A","Checkpoint fixture",source="wy",count=600)
 private val songs=(0 until 600).map{Song("row$it","Checkpoint track $it","Fixture","local",duration=180000)}
 private var shown by mutableStateOf(true)
 private var writes=0
 private lateinit var prefs:SharedPreferences
 private val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="i_${pl.id}"){writes++;metric("preference-index="+prefs.getInt(key,-1),writes,0f)}}
 private fun render(){
  prefs=rule.activity.getSharedPreferences("list_scroll_v1",Context.MODE_PRIVATE)
  prefs.edit().clear().commit();prefs.registerOnSharedPreferenceChangeListener(listener)
  FrostedPrefs.resetForTest();FrostedPrefs.themeMode="light"
  Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
  rule.mainClock.autoAdvance=false
  rule.setContent {SoundwaveDesignTheme(isDark=false){Box(Modifier.fillMaxSize()){
   if(shown) LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=pl,detailSongs=songs,onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={})
  }}}
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
 }
 private fun switchPage(value:Boolean){
  rule.runOnIdle{shown=value};shadowOf(Looper.getMainLooper()).idle()
  rule.mainClock.advanceTimeBy(64);rule.waitForIdle()
  shadowOf(Looper.getMainLooper()).idle();rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
 }
 private fun list()=rule.onNode(hasScrollAction())
 private fun visibleTracks(): Set<String> = rule.onAllNodes(hasText("Checkpoint track",substring=true)).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text].map { it.text } }.filter { it.startsWith("Checkpoint track") }.toSet()
 private fun position():Float = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
 @After fun close(){if(::prefs.isInitialized)prefs.unregisterOnSharedPreferenceChangeListener(listener);FrostedPrefs.resetForTest()}
 @Test fun rapidScrollDoesNotCheckpointEveryRowAndSavesAfterSettling(){
  render();writes=0
  for(i in listOf(20,40,60,80,100,120)) {list().performScrollToIndex(i);rule.mainClock.advanceTimeByFrame();rule.waitForIdle()}
  assertEquals("six changes within 250ms must not write six guarded preference transactions",0,writes)
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  assertEquals(120,ListScrollStore.index(rule.activity,pl.id));assertEquals(1,writes)
  metric("six-scroll-changes",writes,position())
 }
 @Test fun leavingBeforeDebounceSavesAndImmediateReentryRestores(){
  render();list().performScrollToIndex(250);rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
  assertTrue("pre-leave must actually scroll to target",position()>=250*500f)
  val expected=visibleTracks();assertTrue(expected.isNotEmpty());metric("before-dispose",writes,position())
  switchPage(false)
  rule.onAllNodes(hasScrollAction()).assertCountEquals(0)
  assertEquals("dispose must preserve the unsent last position",250,ListScrollStore.index(rule.activity,pl.id))
  switchPage(true)
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  assertEquals("new page must show same deep-list rows",expected,visibleTracks())
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  assertEquals(250,ListScrollStore.index(rule.activity,pl.id))
  metric("dispose-reentry",writes,position())
 }
 @Test fun fiveRapidMountCyclesPreservePositionAndRemainInteractive(){
  render();list().performScrollToIndex(350);rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
  val expected=visibleTracks();assertTrue(expected.isNotEmpty())
  repeat(5){
   switchPage(false)
   rule.onAllNodes(hasScrollAction()).assertCountEquals(0)
   assertEquals(350,ListScrollStore.index(rule.activity,pl.id))
   switchPage(true)
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
   assertEquals("reentry $it must show same rows",expected,visibleTracks())
  }
  list().performScrollToIndex(10);rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  assertEquals(10,ListScrollStore.index(rule.activity,pl.id));metric("five-mount-cycles",writes,position())
 }
 @Test fun actualSortChangeStillReturnsToTop(){
  render();list().performScrollToIndex(250);rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  assertTrue(position()>=250*500f)
  rule.onAllNodesWithContentDescription("排序").onLast().performClick()
  rule.mainClock.advanceTimeBy(400);rule.waitForIdle()
  rule.onNodeWithText("按标题").performClick()
  rule.mainClock.advanceTimeBy(1500);rule.waitForIdle()
  assertEquals("real sort change must return to first item",0f,position(),1f)
  assertEquals(0,ListScrollStore.index(rule.activity,pl.id))
  metric("actual-sort-change-top",writes,position())
 }
 private fun metric(name:String,count:Int,pos:Float){System.getenv("SW_WIRING_OUT")?.let{File(it).appendText("$name writes=$count scrollAxis=$pos\n")}}
}
