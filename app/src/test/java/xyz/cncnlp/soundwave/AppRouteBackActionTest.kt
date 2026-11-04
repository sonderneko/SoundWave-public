package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.HomePagerSelection
import xyz.cncnlp.soundwave.ui.HomeSectionNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Back priority and the actual Home-section route/pager reducer contract.
 */
class AppRouteBackActionTest {

    private val result = AppRoute.SearchResult("q", "song", "wy")

    @Test
    fun playerWinsOverEverything() {
        assertEquals(BackAction.ClosePlayer, AppRoute.backAction(Tab.PLAYER, true, "theme", result))
        assertEquals(BackAction.ClosePlayer, AppRoute.backAction(Tab.PLAYER, false, null, null))
    }

    @Test
    fun miniPlayerEntryPreservesTheOriginTabAndUsesTheExistingFullPlayerRoute() {
        Tab.values().filter { it != Tab.PLAYER }.forEach { origin ->
            val entry = playerEntryState(origin)
            assertEquals(origin, entry.previousTab)
            assertFalse(entry.showLyrics)
        }
    }

    @Test
    fun deepLinkEntryFromNonPlayerTabRecordsTheCurrentTab() {
        Tab.values().filter { it != Tab.PLAYER }.forEach { current ->
            assertEquals(current, playerEntryState(current, Tab.SETTINGS).previousTab)
        }
    }

    @Test
    fun playDeepLinkWhileInPlayerPreservesOriginForClosePlayer() {
        Tab.values().filter { it != Tab.PLAYER }.forEach { origin ->
            val entry = playerEntryState(Tab.PLAYER, origin)
            assertEquals(BackAction.ClosePlayer, AppRoute.backAction(Tab.PLAYER, false, null, null))
            assertEquals(origin, entry.previousTab)
        }
    }

    @Test
    fun playerEntryFallsBackToHomeForPlayerAsInvalidSavedOrigin() {
        assertEquals(Tab.HOME, playerEntryState(Tab.PLAYER, Tab.PLAYER).previousTab)
    }

    @Test
    fun detailBeatsSettingsAndSearch() {
        assertEquals(BackAction.CloseDetail, AppRoute.backAction(Tab.HOME, true, "theme", result))
        assertEquals(BackAction.CloseDetail, AppRoute.backAction(Tab.SEARCH, true, null, result))
    }

    @Test
    fun settingsBeatsSearchAndHome() {
        assertEquals(BackAction.CloseSettingsSub, AppRoute.backAction(Tab.HOME, false, "look", result))
        assertEquals(BackAction.CloseSettingsSub, AppRoute.backAction(Tab.SEARCH, false, "data", result))
    }

    @Test
    fun lyricsCategoryBackClosesTheSettingsRouteToItsRoot() {
        assertEquals(BackAction.CloseSettingsSub, AppRoute.backAction(Tab.SETTINGS, false, "lyrics", null))
        assertEquals(null, AppRoute.settingsBackTarget("lyrics"))
    }

    @Test
    fun searchResultClearsBeforeGoingHome() {
        assertEquals(BackAction.ClearSearch, AppRoute.backAction(Tab.SEARCH, false, null, result))
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.SEARCH, false, null, null))
    }

    @Test
    fun idleSearchBackRestoresOnlyContentTabsAndFallsBackForTransientTabs() {
        assertEquals(Tab.HOME, AppRoute.searchBackTarget(Tab.HOME))
        assertEquals(Tab.PLAYLISTS, AppRoute.searchBackTarget(Tab.PLAYLISTS))
        assertEquals(Tab.SETTINGS, AppRoute.searchBackTarget(Tab.SETTINGS))
        assertEquals(Tab.HOME, AppRoute.searchBackTarget(Tab.SEARCH))
        assertEquals(Tab.HOME, AppRoute.searchBackTarget(Tab.PLAYER))
    }

    @Test
    fun searchDeepLinkFromPlayerPreservesPlaylistForBothReturnFields() {
        val entry = AppRoute.searchEntryDecision(Tab.PLAYER, Tab.PLAYLISTS, Tab.SETTINGS)
        assertEquals(Tab.PLAYLISTS, entry.previousTab)
        assertEquals(Tab.PLAYLISTS, entry.lastContentTab)
    }

    @Test
    fun searchDeepLinkFromPlayerUsesLastContentWhenPreviousTabIsTransient() {
        val entry = AppRoute.searchEntryDecision(Tab.PLAYER, Tab.SEARCH, Tab.SETTINGS)
        assertEquals(Tab.SETTINGS, entry.previousTab)
        assertEquals(Tab.SETTINGS, entry.lastContentTab)
    }

    @Test
    fun searchDeepLinkFromPlayerFallsBackHomeWhenBothReturnFieldsAreTransient() {
        val entry = AppRoute.searchEntryDecision(Tab.PLAYER, Tab.PLAYER, Tab.SEARCH)
        assertEquals(Tab.HOME, entry.previousTab)
        assertEquals(Tab.HOME, entry.lastContentTab)
    }

    @Test
    fun searchDeepLinkFromHomeAndSettingsRetainsExistingEntryBehavior() {
        listOf(Tab.HOME, Tab.SETTINGS).forEach { currentTab ->
            val entry = AppRoute.searchEntryDecision(currentTab, Tab.PLAYLISTS, Tab.HOME)
            assertEquals(currentTab, entry.previousTab)
            assertEquals(currentTab, entry.lastContentTab)
        }
    }

    @Test
    fun searchDeepLinkAlreadyOnSearchRetainsExistingLastContentBehavior() {
        val entry = AppRoute.searchEntryDecision(Tab.SEARCH, Tab.PLAYER, Tab.SETTINGS)
        assertEquals(Tab.SEARCH, entry.previousTab)
        assertEquals(Tab.SETTINGS, entry.lastContentTab)
    }

    @Test
    fun playerSearchDeepLinkClearsResultsBeforeReturningToNormalizedTarget() {
        val entry = AppRoute.searchEntryDecision(Tab.PLAYER, Tab.PLAYLISTS, Tab.PLAYER)
        assertEquals(BackAction.ClearSearch, AppRoute.backAction(Tab.SEARCH, false, null, result))
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.SEARCH, false, null, null))
        assertEquals(Tab.PLAYLISTS, AppRoute.searchBackTarget(entry.lastContentTab))
    }

    @Test
    fun nonSearchTabsWithNothingOpenGoHome() {
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.HOME, false, null, null))
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.PLAYLISTS, false, null, null))
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.SETTINGS, false, null, null))
    }

    @Test
    fun searchResultOnlyMattersOnSearchTab() {
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.SEARCH, false, null, null))
        assertEquals(BackAction.BackToHome, AppRoute.backAction(Tab.HOME, false, null, result))
        assertEquals(BackAction.ClearSearch, AppRoute.backAction(Tab.SEARCH, false, null, AppRoute.SearchResult("x", "pl", "kg")))
    }

    @Test
    fun crossRouteReturnPreservesValidHomeSectionAndNormalizesInvalidSavedData() {
        assertEquals("hot", homeSectionOnRouteReturn(Tab.HOME, "playlists"))
        assertEquals("pl", homeSectionOnRouteReturn(Tab.HOME, "pl"))
        assertEquals("rank", homeSectionOnRouteReturn(Tab.SETTINGS, "rank"))
    }

    @Test
    fun bottomNavigationResetsOnlyOnAHomeRetapWithoutAnOpenDetail() {
        assertEquals("rank", homeSectionAfterBottomNavigation(Tab.HOME, Tab.PLAYLISTS, "rank", false))
        assertEquals("pl", homeSectionAfterBottomNavigation(Tab.HOME, Tab.SETTINGS, "pl", false))
        assertEquals("hot", homeSectionAfterBottomNavigation(Tab.HOME, Tab.HOME, "pl", false))
        assertEquals("pl", homeSectionAfterBottomNavigation(Tab.HOME, Tab.HOME, "pl", true))
        assertEquals("pl", homeSectionAfterBottomNavigation(Tab.PLAYLISTS, Tab.HOME, "pl", false))
    }

    @Test
    fun settlingHomeTabsKeepsPagerCompositionRevisionStable() {
        val pager = HomePagerSelection("hot")
        val revision = pager.pagerSyncRevision

        listOf("rank", "pl", "hot").forEach { section ->
            assertEquals(section, pager.onSectionSettled(section))
            assertEquals("local tab changes must preserve the existing PagerState", revision, pager.pagerSyncRevision)
        }
    }

    @Test
    fun homePageCallbackDrivesExternalPagerAndRouteReturnResyncsRetainedPage() {
        for (initialSection in listOf("hot", "rank", "pl")) {
            val expectedPage = HomeSectionNavigation.pageForSection(initialSection)
            val pager = HomePagerSelection("hot")
            var actualHomePagePager = 0
            var appliedRevision = -1
            fun recomposeHomePageFromRootSelection() {
                val pagerKey = pager.pagerSyncRevision
                if (pager.pagerSyncRevision != appliedRevision) {
                    // Mirrors key(revision) + initialPage: route entry reseeds, local tab changes keep the state.
                    actualHomePagePager = HomeSectionNavigation.externalPage(pager.renderedPageIndex, 3)
                    kotlinx.coroutines.runBlocking {
                        HomeSectionNavigation.syncExternalPager(
                            selectedPage = pager.renderedPageIndex,
                            pageCount = 3,
                            currentPage = actualHomePagePager,
                            settledPage = actualHomePagePager,
                            isScrollInProgress = false,
                            scrollToPage = { page -> actualHomePagePager = page }
                        )
                    }
                    appliedRevision = pager.pagerSyncRevision
                }
                assertEquals(pagerKey, appliedRevision)
            }

            // Real HomePage top-tab/swipe completion callback -> root event -> externally selected Pager page.
            actualHomePagePager = expectedPage // the local PagerState has already settled before notifying the root.
            assertEquals(
                initialSection,
                pager.onSectionSettled(HomeSectionNavigation.onPagerSettled(initialSection))
            )
            assertEquals(expectedPage, pager.renderedPageIndex)
            recomposeHomePageFromRootSelection()
            assertEquals("HomePage Pager must follow the external root page", expectedPage, actualHomePagePager)
            var currentTab = Tab.HOME

            for (destination in listOf(Tab.SEARCH, Tab.PLAYLISTS, Tab.SETTINGS)) {
                currentTab = destination
                actualHomePagePager = 0 // model a stale/restored local PagerState while the root owner remains authoritative
                pager.afterBottomNavigation(Tab.HOME, currentTab, detailOpen = false)
                recomposeHomePageFromRootSelection()
                currentTab = Tab.HOME
                assertEquals("section must survive leaving and re-entering Home", initialSection, pager.section)
                assertEquals("HomePage must render the saved Pager page", expectedPage, pager.renderedPageIndex)
                assertEquals("route entry revision must resync the retained PagerState", expectedPage, actualHomePagePager)
            }

            // Back/route return and Home under an open detail are non-reset transitions but still resync the Pager.
            actualHomePagePager = 0
            pager.onRouteReturn(Tab.HOME)
            recomposeHomePageFromRootSelection()
            pager.afterBottomNavigation(Tab.HOME, Tab.HOME, detailOpen = true)
            recomposeHomePageFromRootSelection()
            assertEquals(initialSection, pager.section)
            assertEquals(expectedPage, pager.renderedPageIndex)
            assertEquals(expectedPage, actualHomePagePager)

            // Only a Home retap while already on Home resets both root owner and rendered Pager to Hot.
            pager.afterBottomNavigation(Tab.HOME, Tab.HOME, detailOpen = false)
            recomposeHomePageFromRootSelection()
            assertEquals("hot", pager.section)
            assertEquals(0, pager.renderedPageIndex)
            assertEquals(0, actualHomePagePager)
        }
    }

    @Test
    fun restoredRootSelectionWinsOverAStaleSavedPagerState() {
        val restoredRoot = HomePagerSelection("rank")
        var restoredPagerPage = 2
        kotlinx.coroutines.runBlocking {
            HomeSectionNavigation.syncExternalPager(
                selectedPage = restoredRoot.renderedPageIndex,
                pageCount = 3,
                currentPage = restoredPagerPage,
                settledPage = restoredPagerPage,
                isScrollInProgress = false,
                scrollToPage = { page -> restoredPagerPage = page }
            )
        }
        assertEquals(1, restoredPagerPage)
    }

    @Test
    fun homePlaylistLibraryReturnHasNoIntermediateHotFrameAndOnlyVisibleHomeFramesAreRecorded() {
        val pager = HomePagerSelection("pl")
        var currentTab = Tab.HOME
        var actualHomePagerPage = 0 // stale retained local state before the route-owned page key is applied
        var appliedRouteKey: Int? = null
        val visibleHomeFrames = mutableListOf<Int>()
        fun renderHomeIfVisible() {
            if (currentTab != Tab.HOME) return
            val rootKey = pager.pagerSyncRevision
            if (rootKey != appliedRouteKey) {
                // Production HomePage only resets PagerState on route revision, not on local section changes.
                actualHomePagerPage = HomeSectionNavigation.externalPage(pager.renderedPageIndex, 3)
                appliedRouteKey = rootKey
            }
            visibleHomeFrames += actualHomePagerPage
        }
        renderHomeIfVisible() // Home / Playlist is visible.
        currentTab = Tab.PLAYLISTS // Library route: retained Home is hidden, so no stale frame is recorded.
        renderHomeIfVisible()
        assertEquals(listOf(2), visibleHomeFrames)
        pager.afterBottomNavigation(Tab.HOME, currentTab, detailOpen = false) // route entry preserves Playlist.
        currentTab = Tab.HOME
        renderHomeIfVisible() // first visible Home return frame is already the saved page.
        assertEquals(listOf(2, 2), visibleHomeFrames)
        assertEquals("pl", pager.section)
        assertEquals(2, pager.renderedPageIndex)
        pager.afterBottomNavigation(Tab.HOME, currentTab, detailOpen = false) // only a true Home retap resets Hot.
        renderHomeIfVisible()
        assertEquals(listOf(2, 2, 0), visibleHomeFrames)
        assertEquals("hot", pager.section)
    }

    @Test
    fun routeSyncCancelsAnInFlightPagerMotionTowardTheRootSelectedPage() {
        var renderedPagerPage = 1
        var requestedPage = -1
        kotlinx.coroutines.runBlocking {
            HomeSectionNavigation.syncExternalPager(
                selectedPage = 2,
                pageCount = 3,
                currentPage = renderedPagerPage,
                settledPage = 0,
                isScrollInProgress = true,
                scrollToPage = { page -> requestedPage = page; renderedPagerPage = page }
            )
        }
        assertEquals(2, requestedPage)
        assertEquals(2, renderedPagerPage)
    }
}
