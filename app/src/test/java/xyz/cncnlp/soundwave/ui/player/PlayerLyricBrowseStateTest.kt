package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricBrowseStateTest {
    @Test
    fun playbackCannotTakeFocusBackWhileTheUserBrowsesLyrics() {
        val browsing = playerLyricSelectIndex(PlayerLyricBrowseState(), 8)
        assertEquals(8, playerLyricFocusedIndex(browsing, activeIndex = 3))
        assertEquals(browsing, playerLyricPlaybackIndexChanged(browsing, activeIndex = 4))
        assertEquals(8, playerLyricFocusedIndex(browsing, activeIndex = 4))
    }

    @Test
    fun manualScrollSelectsTheCenteredLineAndKeepsItFocused() {
        val browsing = playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2)
        val settled = playerLyricSelectIndex(browsing, index = 6)
        assertTrue(settled.isBrowsing)
        assertEquals(6, playerLyricFocusedIndex(settled, activeIndex = 2))
    }

    @Test
    fun longPressEntersSelectionModeWithoutChangingPlaybackFocusOrRequestingSeek() {
        val selecting = playerLyricOpenSelection(PlayerLyricBrowseState(), index = 5)
        assertTrue(selecting.isSelecting)
        assertTrue(selecting.isBrowsing)
        assertEquals(5, selecting.selectedIndex)
        assertEquals(5, playerLyricFocusedIndex(selecting, activeIndex = 1))
        assertEquals(selecting, playerLyricPlaybackIndexChanged(selecting, activeIndex = 9))
    }

    @Test
    fun closingSelectionKeepsThePlayerSubmodeBrowseSelectionAndFocusIntact() {
        val selecting = playerLyricOpenSelection(PlayerLyricBrowseState(), index = 7)
        val returned = playerLyricCloseSelection(selecting)
        assertFalse(returned.isSelecting)
        assertTrue(returned.isBrowsing)
        assertEquals(7, returned.selectedIndex)
        assertEquals(7, playerLyricFocusedIndex(returned, activeIndex = 1))
    }

    @Test
    fun pressingPlayResumesPlaybackFollowingAndClearsManualSelection() {
        val browsing = playerLyricSelectIndex(
            playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2),
            index = 7
        )
        val resumed = playerLyricResumePlaybackFollow(browsing)
        assertFalse(resumed.isBrowsing)
        assertFalse(resumed.isSelecting)
        assertFalse(resumed.hasManuallyScrolled)
        assertFalse(resumed.isUserScrollActive)
        assertNull(resumed.selectedIndex)
        assertFalse(playerLyricShouldShowRowActions(resumed, index = 7, seekAvailable = true))
        assertFalse(playerLyricShouldShowSelectionBackground(resumed, index = 7))
        assertEquals(2, playerLyricFocusedIndex(resumed, activeIndex = 2))
    }

    @Test
    fun invalidIndicesDoNotCreateManualSelection() {
        val state = playerLyricSelectIndex(PlayerLyricBrowseState(), index = -1)
        assertEquals(PlayerLyricBrowseState(), state)
    }

    @Test
    fun initialPlaybackFollowingHidesRowActionsAndSelectedBackground() {
        val following = playerLyricPlaybackIndexChanged(PlayerLyricBrowseState(), activeIndex = 3)
        assertEquals(3, following.selectedIndex)
        assertFalse(following.hasManuallyScrolled)
        assertFalse(playerLyricShouldShowRowActions(following, index = 3, seekAvailable = true))
        assertFalse(playerLyricShouldShowSelectionBackground(following, index = 3))
    }

    @Test
    fun manualScrollShowsActionsWithoutBackgroundWhileTheUserIsMoving() {
        val browsing = playerLyricSelectIndex(
            playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2),
            index = 6
        )
        assertTrue(browsing.isUserScrollActive)
        assertTrue(playerLyricShouldShowRowActions(browsing, index = 6, seekAvailable = true))
        assertFalse(playerLyricShouldShowSelectionBackground(browsing, index = 6))
        assertFalse(playerLyricShouldShowRowActions(browsing, index = 5, seekAvailable = true))
        assertFalse(playerLyricShouldShowRowActions(browsing, index = 7, seekAvailable = true))
        assertFalse(playerLyricShouldShowRowActions(browsing, index = 6, seekAvailable = false))
    }

    @Test
    fun settledManualScrollShowsBackgroundForTheSelectedLineAndKeepsActions() {
        val scrolling = playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2)
        val settled = playerLyricSettleBrowsing(scrolling, index = 6)
        assertFalse(settled.isUserScrollActive)
        assertTrue(playerLyricShouldShowSelectionBackground(settled, index = 6))
        assertFalse(playerLyricShouldShowSelectionBackground(settled, index = 2))
        assertTrue(playerLyricShouldShowRowActions(settled, index = 6, seekAvailable = true))
    }

    @Test
    fun resumingManualScrollImmediatelyHidesSettledBackground() {
        val settled = playerLyricSettleBrowsing(
            playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2),
            index = 6
        )
        val scrollingAgain = playerLyricBeginBrowsing(settled, focusedIndex = 6)
        assertTrue(scrollingAgain.isUserScrollActive)
        assertFalse(playerLyricShouldShowSelectionBackground(scrollingAgain, index = 6))
        assertTrue(playerLyricShouldShowRowActions(scrollingAgain, index = 6, seekAvailable = true))
    }

    @Test
    fun tappingALyricCanSelectItWithoutRevealingManualScrollActions() {
        val selected = playerLyricSelectIndex(PlayerLyricBrowseState(), index = 4)
        assertTrue(playerLyricShouldShowSelectionBackground(selected, index = 4))
        assertFalse(playerLyricShouldShowRowActions(selected, index = 4, seekAvailable = true))
    }

    @Test
    fun longPressSelectionDoesNotRevealRowActions() {
        val selecting = playerLyricOpenSelection(PlayerLyricBrowseState(), index = 5)
        assertTrue(selecting.isSelecting)
        assertFalse(selecting.hasManuallyScrolled)
        assertFalse(playerLyricShouldShowRowActions(selecting, index = 5, seekAvailable = true))
        val returned = playerLyricCloseSelection(selecting)
        assertFalse(playerLyricShouldShowRowActions(returned, index = 5, seekAvailable = true))
    }

    @Test
    fun exitingLyricsPageClearsManualBrowseActionsAndSelectionBackground() {
        val browsing = playerLyricSettleBrowsing(
            playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2),
            index = 6
        )
        val exited = playerLyricExitLyricsPage(browsing)
        assertEquals(PlayerLyricBrowseState(), exited)
        assertFalse(playerLyricShouldShowRowActions(exited, index = 6, seekAvailable = true))
        assertFalse(playerLyricShouldShowSelectionBackground(exited, index = 6))
        assertTrue(browsing.hasManuallyScrolled)
    }

    @Test
    fun manualSelectionKeepsSeekSlotsFixedAndDoesNotTurnSelectedLyricIntoPlaybackHighlight() {
        val browsing = playerLyricBeginBrowsing(PlayerLyricBrowseState(), focusedIndex = 2)
        val selected = playerLyricSelectIndex(browsing, index = 7)

        assertTrue(playerLyricShouldReserveRowActionSlots(browsing, compactPreview = false))
        assertTrue(playerLyricShouldReserveRowActionSlots(selected, compactPreview = false))
        assertFalse(playerLyricShouldReserveRowActionSlots(selected, compactPreview = true))
        assertFalse(playerLyricShouldUsePlaybackHighlight(index = 7, activeIndex = 2))
        assertTrue(playerLyricShouldUsePlaybackHighlight(index = 2, activeIndex = 2))
        assertEquals(selected, playerLyricPlaybackIndexChanged(selected, activeIndex = 11))
    }

    @Test
    fun longPressActionSheetDoesNotRevealSeekControlsOrReserveManualScrollSlots() {
        val selecting = playerLyricOpenSelection(PlayerLyricBrowseState(), index = 5)

        assertFalse(playerLyricShouldShowRowActions(selecting, index = 5, seekAvailable = true))
        assertFalse(playerLyricShouldReserveRowActionSlots(selecting, compactPreview = false))
        assertFalse(playerLyricShouldUsePlaybackHighlight(index = 5, activeIndex = 1))
    }
}
