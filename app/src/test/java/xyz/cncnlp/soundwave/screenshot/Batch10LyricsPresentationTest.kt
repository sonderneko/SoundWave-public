package xyz.cncnlp.soundwave.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
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
class Batch10LyricsPresentationTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @After fun reset(){MotionPolicy.setAppReduceMotion(rule.activity,false)}
 private fun shot(name:String){
  rule.waitForIdle();val v=rule.activity.window.decorView
  val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}
  System.getenv("SW_SCREENSHOT_DIR")?.let{dir->File(dir,"$name.png").apply{parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 }
 private fun viewport(dark:Boolean,large:Boolean=false,dynamic:Boolean=false,immersive:Boolean=false){
  MotionPolicy.setAppReduceMotion(rule.activity,large)
  val lines=(0..39).map{LyricLine(it*2000L,"Line $it 歌词","译文 $it","roma $it")}
  var browse by mutableStateOf(PlayerLyricBrowseState(selectedIndex=1,isBrowsing=true,hasManuallyScrolled=true))
  var selected=-1;var longPressed=-1;val seeks=mutableListOf<Float>()
  lateinit var state:androidx.compose.foundation.lazy.LazyListState
  rule.setContent{SoundwaveDesignTheme(isDark=dark){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,if(large)1.5f else 1f)){
   state=rememberLazyListState()
   Box(Modifier.fillMaxSize().background(if(dynamic) Color(0xFF25344C) else Color(0xFF24342F)).padding(12.dp)){
    PlayerLyricsViewport(lyrics=lines,lyricWindowIndices=lines.indices,listState=state,userScrollEnabled=true,contentPadding=PaddingValues(vertical=16.dp),modifier=Modifier.fillMaxSize().testTag("lyrics"),activeIndex=1,selectedIndex=browse.selectedIndex?:1,browseState=browse,currentTimeMs=2500L,duration=80f,lyricOffset=500,lyricLayout=true,immersiveMode=immersive,lyricScale=1f,lyricFontSp=if(large)28 else 10,lyricImmersiveSp=PlayerLyricsSettingsModel.immersiveFontFor(if(large)28 else 10),lyricGapDp=8,lyricImmersiveGapDp=20,lyricBlurDp=0,lyricHighlightHex=if(dynamic)"#FFCB83" else "",lyricUnhighlightHex=if(dynamic)"#8FB7EF" else "",lyricBold=true,lyricShowTranslation=true,lyricShowRomanization=true,lyricWordKaraoke=false,lyricWordLiftEnabled=false,reduceMotion=large,horizontalAlignment=Alignment.CenterHorizontally,textAlignment=TextAlign.Center,onItemSizeChanged={_,_->},onSelect={selected=it;browse=playerLyricSelectIndex(browse,it)},onPlay={line->playerLyricSeekProgress(line.timeMs,80f,500L)?.let{seeks.add(it)};browse=playerLyricResumePlaybackFollow(browse)},onLongPress={longPressed=it})
   }
  }}}
  val layouts=mutableListOf<TextLayoutResult>()
  rule.onNodeWithText("Line 1 歌词").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
  rule.runOnIdle{
   val style=layouts.single().layoutInput.style
   // 全页统一（2026-11-04）：当前行字号 = 基础原值（旧 J 波 ×CurrentLineScale 已摘除）；沉浸/普通期望同源（immersiveFontFor 现为原值直用）。
  assertEquals(if(immersive)PlayerLyricsSettingsModel.immersiveFontFor(if(large)28 else 10).toFloat() else playerLyricPresentationLineFontSp(if(large)28f else 10f,focused=true,immersive=false,compactPreview=false),style.fontSize.value,0f)
   assertEquals(if(dynamic)Color(0xFFFFCB83) else Color.White,style.color)
   assertTrue(seeks.isEmpty())
  }
  shot("lyrics-$dark-$large-$dynamic-$immersive")
  rule.onNodeWithText("Line 2 歌词").performClick()
  if(immersive){
   // 现行契约（player-mode-coordinator 守卫 + 提交 51a2979）：点行只选中、不直接 seek，
   // 沉浸模式不显示行内播放钮；seek 只走非沉浸模式的行内播放钮。
   rule.runOnIdle{assertEquals(2,selected);assertTrue(seeks.isEmpty());assertTrue(browse.isBrowsing)}
   rule.onNodeWithContentDescription("从 00:04 播放此句").assertDoesNotExist()
  }else{
   rule.runOnIdle{assertEquals(2,selected);assertTrue(seeks.isEmpty())}
   rule.onNodeWithContentDescription("从 00:04 播放此句").performClick()
   rule.runOnIdle{assertEquals(listOf(3.5f/80f),seeks);assertFalse(browse.isBrowsing)}
  }
  rule.onNodeWithText("Line 2 歌词").performTouchInput{longClick()}
  rule.runOnIdle{assertEquals(2,longPressed)}
  rule.onNodeWithTag("lyrics").performTouchInput{swipeUp()}
  rule.runOnIdle{assertTrue(state.firstVisibleItemIndex>0);assertEquals(if(immersive) 0 else 1,seeks.size)}
 }
 @Test fun lightMinimumFont()=viewport(false)
 @Test fun darkMinimumFont()=viewport(true)
 @Test fun lightLargeDynamicReduced()=viewport(false,true,true)
 @Test fun darkImmersiveDynamicReduced()=viewport(true,true,true,true)
 @Test fun compactEntryAndTranslationVisibility(){
  var entry=0;var selected=0
  rule.setContent{SoundwaveDesignTheme(isDark=false){Box(Modifier.fillMaxSize().background(Color(0xFF24342F))){
   PlayerLyricRow(line=LyricLine(2000L,"Preview lyric","Hidden translation","Hidden roma"),nextLine=null,index=0,activeIndex=0,selectedIndex=0,browseState=PlayerLyricBrowseState(),currentTimeMs=2000L,duration=80f,lyricOffset=0,lyricLayout=false,immersiveMode=false,lyricScale=1f,lyricFontSp=10,lyricImmersiveSp=32,lyricGapDp=8,lyricImmersiveGapDp=20,lyricBlurDp=0,lyricHighlightHex="",lyricUnhighlightHex="",lyricBold=false,lyricShowTranslation=false,lyricShowRomanization=false,lyricWordKaraoke=false,lyricWordLiftEnabled=false,reduceMotion=false,horizontalAlignment=Alignment.CenterHorizontally,textAlignment=TextAlign.Center,compactPreview=true,seekAvailable=true,onItemSizeChanged={},onSelect={selected++},onPlay={},onLongPress={},onCompactPreviewTap={entry++})
  }}}
  rule.onNodeWithText("Hidden translation").assertDoesNotExist();rule.onNodeWithText("Hidden roma").assertDoesNotExist()
  shot("compact")
  rule.onNodeWithText("Preview lyric").performClick();rule.runOnIdle{assertEquals(1,entry);assertEquals(0,selected)}
 }
 @Test fun reducedWordTimelineRendersAndKeepsKaraoke(){
  val line=LyricLine(0L,"First second",words=listOf(LyricWord(0L,2000L,"First "),LyricWord(2000L,4000L,"second")))
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().background(Color(0xFF24342F))){
   PlayerLyricRow(line=line,nextLine=null,index=0,activeIndex=0,selectedIndex=0,browseState=PlayerLyricBrowseState(),currentTimeMs=1000L,duration=80f,lyricOffset=0,lyricLayout=true,immersiveMode=false,lyricScale=1f,lyricFontSp=24,lyricImmersiveSp=32,lyricGapDp=8,lyricImmersiveGapDp=20,lyricBlurDp=0,lyricHighlightHex="#FFCB83",lyricUnhighlightHex="",lyricBold=true,lyricShowTranslation=false,lyricShowRomanization=false,lyricWordKaraoke=true,lyricWordLiftEnabled=true,reduceMotion=true,horizontalAlignment=Alignment.CenterHorizontally,textAlignment=TextAlign.Center,seekAvailable=false,onItemSizeChanged={},onSelect={},onPlay={},onLongPress={})
  }}}
  rule.onNodeWithTag("lyric-word-0",useUnmergedTree=true).assertExists();rule.onNodeWithTag("lyric-word-1",useUnmergedTree=true).assertExists()
  assertFalse(playerShouldLiftCurrentWords(true,true,true));assertTrue(playerShouldRenderWordKaraoke(true,true,true))
  shot("reduced-karaoke")
 }
 @Test fun actualRoutePlaybackFollowThenManualBrowseAndSeek(){
  val lines=(0..39).map{LyricLine(it*2000L,"Line $it 歌词","译文 $it","roma $it")}
  var active by mutableIntStateOf(1)
  var browse by mutableStateOf(PlayerLyricBrowseState())
  var selected=-1;var longPressed=-1;var begins=0;var settles=0;val seeks=mutableListOf<Float>()
  rule.setContent{SoundwaveDesignTheme(isDark=true){Box(Modifier.fillMaxSize().background(Color(0xFF24342F)).testTag("route")){
    PlayerLyricsRoute(lyrics=lines,lyricWindowIndices=lines.indices,manualBrowsing=browse.isBrowsing,immersiveCoverLyricsMode=false,fullLyricsPageScrollMode=true,lyricScrollTrackKey="b10",lyricScrollRouteKey="full",activeIndex=active,selectedIndex=browse.selectedIndex?:active,browseState=browse,currentTimeMs=2500L,duration=80f,lyricOffset=500,lyricLayout=true,immersiveMode=false,lyricScale=1f,lyricFontSp=18,lyricImmersiveSp=32,lyricGapDp=8,lyricImmersiveGapDp=20,lyricBlurDp=0,lyricHighlightHex="",lyricUnhighlightHex="",lyricBold=true,lyricShowTranslation=true,lyricShowRomanization=true,lyricWordKaraoke=false,lyricWordLiftEnabled=false,reduceMotion=true,horizontalAlignment=Alignment.CenterHorizontally,textAlignment=TextAlign.Center,onBeginBrowsing={begins++;browse=playerLyricBeginBrowsing(browse,active)},onSettleBrowsing={settles++;browse=playerLyricSettleBrowsing(browse,it)},onSelect={selected=it;browse=playerLyricSelectIndex(browse,it)},onPlay={line->playerLyricSeekProgress(line.timeMs,80f,500L)?.let{seeks.add(it)};browse=playerLyricResumePlaybackFollow(browse)},onLongPress={longPressed=it})
  }}}
  rule.onNodeWithText("Line 1 歌词").assertIsDisplayed()
  rule.runOnIdle{active=12};rule.waitForIdle()
  rule.onNodeWithText("Line 12 歌词").assertIsDisplayed()
  rule.runOnIdle{assertEquals(0,begins);assertTrue(seeks.isEmpty())}
  shot("route-follow-12")
  rule.onNodeWithTag("route").performTouchInput{swipeUp()};rule.waitForIdle()
  var index=-1
  rule.runOnIdle{assertTrue(begins>0);assertTrue(settles>0);assertTrue(browse.isBrowsing);index=browse.selectedIndex!!;assertTrue(seeks.isEmpty())}
  rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index);rule.waitForIdle()
  rule.onNodeWithContentDescription("从 ${playerLyricTimeLabel(lines[index].timeMs)} 播放此句").performClick()
  rule.runOnIdle{assertEquals(1,seeks.size);assertEquals((index*2f-.5f)/80f,seeks.single(),0f);assertFalse(browse.isBrowsing)}
 }

}
