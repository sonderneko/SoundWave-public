package xyz.cncnlp.soundwave

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real BackHandler dispatcher and real BottomNav; no AppRoot/account/runtime startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class Stage14NavigationComposeTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeBackUsesLatestEffectsAfterRecompositionWithoutStackingHandlers() {
        val first=LegacyNavigationHarness().apply {downloadPickSong=Song(id="1",name="a",singer="s")}
        val next=LegacyNavigationHarness().apply {downloadPickSong=Song(id="1",name="a",singer="s");detailOpen=true}
        val selected=mutableStateOf(first.productionEvents())
        rule.setContent { AppRootNavigationBackHandler(selected.value) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(first.downloadPickSong)
        rule.runOnIdle { selected.value=next.productionEvents() }
        rule.waitForIdle()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(next.downloadPickSong);assertTrue(next.detailOpen)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertFalse(next.detailOpen);assertTrue(first.events.isEmpty())
    }
    @Test fun actualDefaultBottomNavigationClicksApplyProductionEvents() = clickTabs("default")
    @Test fun actualCapsuleBottomNavigationClicksApplyProductionEvents() = clickTabs("capsule")

    private fun clickTabs(style:String) {
        val h=LegacyNavigationHarness().apply {tab=Tab.HOME;detailOpen=true;plDetailLoading=true;settingsSub="theme"}
        val events=h.productionEvents()
        rule.setContent {SoundwaveDesignTheme(isDark=false){
            AppRootNavigationBackHandler(events)
            BottomNav(tab=h.tab,dark=false,style=style,onTab=events::selectMainTab)
        }}
        rule.onNodeWithContentDescription("首页").performClick()
        rule.runOnIdle {
            assertFalse(h.detailOpen);assertNull(h.settingsSub);assertFalse(h.plDetailLoading)
            assertEquals("pl",h.homePagerSelection.section)
        }
        rule.onNodeWithContentDescription("首页").performClick()
        rule.runOnIdle {assertEquals("hot",h.homePagerSelection.section)}
        rule.onNodeWithContentDescription("音乐库").performClick()
        rule.runOnIdle {assertEquals(Tab.PLAYLISTS,h.tab);assertEquals(Tab.PLAYLISTS,h.lastContentTab)}
        rule.onNodeWithContentDescription("搜索").performClick()
        rule.runOnIdle {assertEquals(Tab.SEARCH,h.tab);assertFalse(h.searchDockFocus)}
        rule.runOnIdle {rule.activity.onBackPressedDispatcher.onBackPressed()}
        rule.runOnIdle {assertEquals(Tab.PLAYLISTS,h.tab);assertFalse(h.events.contains("prompt"))}
    }
}
