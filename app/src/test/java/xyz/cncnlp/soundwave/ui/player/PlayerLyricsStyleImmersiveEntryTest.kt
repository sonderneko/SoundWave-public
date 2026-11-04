package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.playerLyricsStylePreviewTapShouldEnterImmersive
import xyz.cncnlp.soundwave.playerShowsLyricsPage

class PlayerLyricsStyleImmersiveEntryTest {
    @Test
    fun onlyLyricsStyleCoverPreviewRowsAreImmersiveEntryTargets() {
        assertTrue(playerLyricsStylePreviewTapShouldEnterImmersive("lyrics", compactPreview = true))
        assertFalse(playerLyricsStylePreviewTapShouldEnterImmersive("lyrics", compactPreview = false))
        assertFalse(playerLyricsStylePreviewTapShouldEnterImmersive("apple", compactPreview = true))
        assertFalse(playerLyricsStylePreviewTapShouldEnterImmersive("immersive_cover", compactPreview = true))
    }

    @Test
    fun immersiveEntryAndReturnReuseTheSelectedLyricAndRestoreTheLyricsStyleCover() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val midPanelState = mutableStateOf("")
        val immersiveModeState = mutableStateOf(false)
        val forcedLyricsDismissedState = mutableStateOf(false)
        val chromeVisibleState = mutableStateOf(true)
        val interactionTickState = mutableIntStateOf(0)
        val queueVisibilityState = MutableTransitionState(false)
        val queueReturnImmersiveState = mutableStateOf(false)
        val queueReturnShowLyricsState = mutableStateOf(false)
        val queueReturnForcedDismissedState = mutableStateOf(false)
        var showLyrics = false
        val browseState = playerLyricSelectIndex(PlayerLyricBrowseState(), index = 6)
        val selectedBeforeEntry = playerLyricFocusedIndex(browseState, activeIndex = 2)

        fun coordinator() = FullPlayerPlaybackCoordinator(
            scope = scope,
            midPanelState = midPanelState,
            immersiveModeState = immersiveModeState,
            forcedLyricsDismissedState = forcedLyricsDismissedState,
            chromeVisibleState = chromeVisibleState,
            interactionTickState = interactionTickState,
            queueVisibilityState = queueVisibilityState,
            queueReturnImmersiveState = queueReturnImmersiveState,
            queueReturnShowLyricsState = queueReturnShowLyricsState,
            queueReturnForcedDismissedState = queueReturnForcedDismissedState,
            layout = "lyrics",
            layoutForcesLyrics = false,
            lyricsStyleLayout = true,
            immersiveCoverLayout = false,
            lyricLayout = playerShowsLyricsPage("lyrics", immersiveModeState.value, showLyrics, forcedLyricsDismissedState.value),
            showLyrics = showLyrics,
            playing = true,
            onToggleLyrics = { showLyrics = !showLyrics }
        )

        try {
            coordinator().openLyricsImmersive()
            assertTrue(immersiveModeState.value)
            assertTrue(showLyrics)
            assertTrue(playerShowsLyricsPage("lyrics", immersiveModeState.value, showLyrics, forcedLyricsDismissedState.value))
            assertEquals(selectedBeforeEntry, playerLyricFocusedIndex(browseState, activeIndex = 2))

            assertTrue(coordinator().returnToPreviousPlayerMode())
            assertFalse(immersiveModeState.value)
            assertFalse(showLyrics)
            assertFalse(playerShowsLyricsPage("lyrics", immersiveModeState.value, showLyrics, forcedLyricsDismissedState.value))
            assertEquals(selectedBeforeEntry, playerLyricFocusedIndex(browseState, activeIndex = 2))
        } finally {
            scope.cancel()
        }
    }
}
