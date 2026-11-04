package xyz.cncnlp.soundwave.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.player.PlayerLyricsPageHeader
import xyz.cncnlp.soundwave.ui.player.PlayerImmersiveCoverLyricsHeader

/** Real composable entry/callback tests, no provider network or migration commit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="w412dp-h1100dp-xhdpi")
class Round6EntryRegressionTest {
 @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
 @Test fun standardLyricArtistClickInvokesCallback() {
  var clicks=0
  rule.setContent { SoundwaveDesignTheme(isDark=false) {
   PlayerLyricsPageHeader(songTitle="Test song",artistName="Test artist",coverScale=1f,
    immersiveMode=false,playing=false,liked=false,primaryColor=Color.Black,secondaryColor=Color.Gray,
    moreIconTint=Color.Black,artworkContent={},onCoverClick={},onToggle={},onNext={},onLike={},onMore={},onArtistClick={clicks++})
  } }
  rule.onNodeWithText("Test artist").performClick();rule.runOnIdle{assertEquals(1,clicks)}
 }
 @Test fun immersiveLyricArtistClickInvokesCallback() {
  var clicks=0
  rule.setContent { SoundwaveDesignTheme(isDark=true) {
   PlayerImmersiveCoverLyricsHeader("Test song","Test artist",Color.White,Color.Gray,null,false,{},{},{clicks++})
  } }
  rule.onNodeWithText("Test artist").performClick();rule.runOnIdle{assertEquals(1,clicks)}
 }
 @Test fun legacyMigrationExposesTwoRowsWithoutExpandedOptions() {
  rule.setContent { SoundwaveDesignTheme(isDark=false) { LocalProfileBackupImportAction {} } }
  rule.onNodeWithText("导入本机资料备份").assertIsDisplayed()
  rule.onNodeWithText("复制旧本机资料").assertIsDisplayed()
  rule.onNodeWithText("预览旧本机资料").assertDoesNotExist()
  rule.onNodeWithText("复制旧本机资料").performClick()
  rule.onNodeWithText("预览旧本机资料").assertIsDisplayed()
  rule.onNodeWithText("返回").performClick()
  rule.onNodeWithText("预览旧本机资料").assertDoesNotExist()
 }
 @Test fun artistMenuDismissesBeforeRouting() {
  val events=mutableListOf<String>()
  val song=Song(id="entry",name="Test song",singer="First / Second",source="wy")
  TrackNavigation.bind(artist={s,origin->events+="${s.singer}:$origin"},album={_,_->},currentTab={Tab.HOME})
  rule.setContent { SoundwaveDesignTheme(isDark=false) { Column {
   TrackActionsSheetContent(song,false,false,false,{},{},{},{},{},{},{},onNavigate={events+="dismiss"})
  } } }
  // M 波规格变更（ISSUE-SONGMENU §4.3）：「查看歌手」动作行已移入出处区，条目标题 = 主歌手名；点击语义与时序（先关弹层再路由）不变。
  rule.onNodeWithText("First").performClick()
  rule.runOnIdle{assertEquals(listOf("dismiss","First:HOME"),events)}
 }
}
