package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDismissDecisionTest {
    @Test
    fun thresholdBoundaryReturnsToPreviousModeFromImmersiveOrLyricsSubpage() {
        assertEquals(
            PlayerDismissDecision.RETURN_TO_PREVIOUS_MODE,
            playerDismissDecision(100f, 100f, immersiveMode = true, lyricPage = false)
        )
        assertEquals(
            PlayerDismissDecision.RETURN_TO_PREVIOUS_MODE,
            playerDismissDecision(100f, 100f, immersiveMode = false, lyricPage = true)
        )
    }

    @Test
    fun ordinaryPlayerClosesAtOrAboveThreshold() {
        assertEquals(
            PlayerDismissDecision.CLOSE_PLAYER,
            playerDismissDecision(100f, 100f, immersiveMode = false, lyricPage = false)
        )
        assertEquals(
            PlayerDismissDecision.CLOSE_PLAYER,
            playerDismissDecision(101f, 100f, immersiveMode = false, lyricPage = false)
        )
    }

    @Test
    fun belowThresholdResetsEvenWhenCurrentlyOnASubpage() {
        assertEquals(
            PlayerDismissDecision.RESET_OFFSET,
            playerDismissDecision(99f, 100f, immersiveMode = true, lyricPage = true)
        )
    }

    @Test
    fun belowThresholdOrdinaryPlayerResetsOffset() {
        assertEquals(
            PlayerDismissDecision.RESET_OFFSET,
            playerDismissDecision(99f, 100f, immersiveMode = false, lyricPage = false)
        )
    }
}
