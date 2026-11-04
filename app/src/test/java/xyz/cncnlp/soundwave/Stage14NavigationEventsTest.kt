package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*

internal fun LegacyNavigationHarness.productionEvents(): MainNavigationEvents = MainNavigationEvents(
    snapshot = { MainNavigationSnapshot(tab, prevTab, lastContentTab, detailOpen, settingsSub,
        searchResult, downloadPickSong != null, lastBackAt) },
    effects = MainNavigationEffects(
        dismissDownloadPicker = { downloadPickSong = null; downloadPickQuality = "" },
        returnToTopLevelTab = ::returnToTopLevelTab,
        closeDetail = ::closeDetail,
        setSettingsSub = { settingsSub = it },
        clearSearch = { searchResult = null; searchKw = "" },
        finishActivity = { events += "finish" },
        showExitPrompt = { lastBackAt = it; events += "prompt" },
        setLastContentTab = { lastContentTab = it },
        clearSearchDockFocus = { searchDockFocus = false },
        restoreHomeSection = { target, current, detail ->
            events += "home:${homePagerSelection.afterBottomNavigation(target, current, detail)}"
        },
        applyMainTab = { tab = it; settingsSub = null; detailOpen = false; plDetailLoading = false }
    )
)

class Stage14NavigationEventsTest {
    @Test fun completeBackCombinationMatrixMatchesExactPreviousHandlers() {
        var cases = 0
        for (tab in Tab.values()) for (previous in Tab.values()) for (origin in Tab.values())
        for (mask in 0 until 32) for (delta in listOf(1599L, 1600L)) {
            fun fixture() = LegacyNavigationHarness().apply {
                this.tab = tab; prevTab = previous; lastContentTab = origin
                detailOpen = mask and 1 != 0
                settingsSub = if (mask and 2 != 0) "theme" else null
                searchResult = if (mask and 4 != 0) AppRoute.SearchResult("q", "song", "wy") else null
                downloadPickSong = if (mask and 16 != 0) Song(id="1",name="a",singer="s") else null
                lastBackAt = 10000L; now = 10000L + delta
            }
            val old = fixture(); val current = fixture()
            old.legacyBack(); current.productionEvents().back(current.now)
            assertEquals("case $cases tab=$tab mask=$mask delta=$delta", old.snapshot(), current.snapshot())
            assertEquals(old.homePagerSelection.pagerSyncRevision, current.homePagerSelection.pagerSyncRevision)
            cases++
        }
        assertEquals(8000, cases)
    }
    @Test fun allBottomTabAndDetailCombinationsMatchPreviousEffectsAndHomeRevision() {
        for (tab in Tab.values()) for (target in Tab.values()) for (detail in listOf(false,true)) {
            fun fixture() = LegacyNavigationHarness().apply {
                this.tab=tab;detailOpen=detail;settingsSub="theme";plDetailLoading=true
            }
            val old=fixture();val current=fixture()
            old.legacyBottom(target); current.productionEvents().selectMainTab(target)
            assertEquals(old.snapshot(), current.snapshot())
            assertEquals(old.homePagerSelection.pagerSyncRevision,current.homePagerSelection.pagerSyncRevision)
        }
    }
    @Test fun sameEventObjectReadsCurrentStateRatherThanRetainingStaleRoute() {
        val h=LegacyNavigationHarness()
        val events=h.productionEvents()
        h.tab=Tab.PLAYER;h.detailOpen=true
        h.downloadPickSong=Song(id="1",name="a",singer="s")
        events.back(h.now);assertNull(h.downloadPickSong);assertEquals(Tab.PLAYER,h.tab)
        events.back(h.now);assertEquals(Tab.PLAYLISTS,h.tab);assertTrue(h.detailOpen)
        events.back(h.now);assertFalse(h.detailOpen)
        h.tab=Tab.SEARCH;h.lastContentTab=Tab.SETTINGS
        h.searchResult=AppRoute.SearchResult("q","song","wy")
        events.back(h.now);assertNull(h.searchResult);assertEquals(Tab.SEARCH,h.tab)
        events.back(h.now);assertEquals(Tab.SETTINGS,h.tab)
        assertFalse(h.events.contains("finish"));assertFalse(h.events.contains("prompt"))
    }
    @Test fun bottomEffectsRunBeforeClearingDetailAndDoNotChangePreviousPlayerRoute() {
        val h=LegacyNavigationHarness().apply { tab=Tab.HOME;detailOpen=true;plDetailLoading=true }
        val events=h.productionEvents()
        events.selectMainTab(Tab.HOME)
        assertEquals("pl",h.homePagerSelection.section);assertFalse(h.detailOpen)
        events.selectMainTab(Tab.HOME)
        assertEquals("hot",h.homePagerSelection.section)
        assertEquals(2,h.homePagerSelection.pagerSyncRevision)
        assertEquals(Tab.PLAYLISTS,h.prevTab)
        events.selectMainTab(Tab.SEARCH);assertEquals(Tab.HOME,h.lastContentTab);assertFalse(h.searchDockFocus)
    }
}
