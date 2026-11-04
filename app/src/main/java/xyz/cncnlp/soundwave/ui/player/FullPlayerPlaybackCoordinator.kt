package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.playerChromeVisibleAfterLyricsEntry
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates the existing cover, immersive-lyrics, and queue subroutes.
 * FullPlayer creates and owns every state holder; this class only applies the
 * same route transitions and playback callbacks at a separate coordination boundary.
 */
internal class FullPlayerPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val midPanelState: MutableState<String>,
    private val immersiveModeState: MutableState<Boolean>,
    private val forcedLyricsDismissedState: MutableState<Boolean>,
    private val chromeVisibleState: MutableState<Boolean>,
    private val interactionTickState: MutableIntState,
    private val queueVisibilityState: MutableTransitionState<Boolean>,
    private val queueReturnImmersiveState: MutableState<Boolean>,
    private val queueReturnShowLyricsState: MutableState<Boolean>,
    private val queueReturnForcedDismissedState: MutableState<Boolean>,
    private val layout: String,
    private val layoutForcesLyrics: Boolean,
    private val lyricsStyleLayout: Boolean,
    private val immersiveCoverLayout: Boolean,
    private val lyricLayout: Boolean,
    private val showLyrics: Boolean,
    private val playing: Boolean,
    private val onToggleLyrics: () -> Unit,
    /** r672：Apple 队列改为覆盖在当前页上的共享底部抽屉，打开队列时保留下面的歌词 / 封面页，不切回封面。 */
    private val keepSubmodeUnderQueue: Boolean = false,
    private val coverImmersiveState: MutableState<Boolean>? = null,
    private val appleCoverMode: Boolean = false,
    private val onSetImmersiveCover: (Boolean) -> Unit = {}
) {
    fun returnToPreviousPlayerMode(): Boolean {
        return when {
            midPanelState.value == "queue" -> {
                queueVisibilityState.targetState = false
                chromeVisibleState.value = true
                interactionTickState.intValue++
                scope.launch {
                    delay(280)
                    if (midPanelState.value == "queue" && !queueVisibilityState.targetState) {
                        midPanelState.value = ""
                        immersiveModeState.value = queueReturnImmersiveState.value
                        forcedLyricsDismissedState.value = queueReturnForcedDismissedState.value
                        if (lyricsStyleLayout && showLyrics != queueReturnShowLyricsState.value) onToggleLyrics()
                    }
                }
                true
            }
            immersiveModeState.value -> {
                immersiveModeState.value = false
                if (layoutForcesLyrics) forcedLyricsDismissedState.value = true
                else if (showLyrics) onToggleLyrics()
                chromeVisibleState.value = true
                interactionTickState.intValue++
                true
            }
            lyricLayout -> {
                if (layoutForcesLyrics) forcedLyricsDismissedState.value = true else onToggleLyrics()
                chromeVisibleState.value = true
                interactionTickState.intValue++
                true
            }
            else -> false
        }
    }

    fun openLyricsImmersive() {
        if (immersiveModeState.value) {
            returnToPreviousPlayerMode()
            return
        }
        midPanelState.value = ""
        // Keep the existing entry rule so auto-hidden chrome does not flash on this route.
        chromeVisibleState.value = playerChromeVisibleAfterLyricsEntry(layout, playing)
        interactionTickState.intValue++
        forcedLyricsDismissedState.value = false
        immersiveModeState.value = true
        // The temporary player route must not rewrite the selected layout preference.
        if (!layoutForcesLyrics && !showLyrics) onToggleLyrics()
    }

    fun toggleCoverLyricsMode() {
        if (midPanelState.value == "queue" || immersiveModeState.value) {
            returnToPreviousPlayerMode()
            return
        }
        if (appleCoverMode) {
            val state = coverImmersiveState ?: return
            state.value = !state.value
            onSetImmersiveCover(state.value)
            return
        }
        if (lyricsStyleLayout || immersiveCoverLayout) {
            if (immersiveModeState.value) returnToPreviousPlayerMode()
            else openLyricsImmersive()
            return
        }
        if (returnToPreviousPlayerMode()) return
        if (layoutForcesLyrics) forcedLyricsDismissedState.value = false else onToggleLyrics()
        chromeVisibleState.value = true
        interactionTickState.intValue++
    }

    fun openQueueInLyrics() {
        // Save the player submode so closing the queue restores the exact prior lyrics state.
        queueReturnImmersiveState.value = immersiveModeState.value
        queueReturnShowLyricsState.value = showLyrics
        queueReturnForcedDismissedState.value = forcedLyricsDismissedState.value
        midPanelState.value = "queue"
        queueVisibilityState.targetState = true
        if (!keepSubmodeUnderQueue) immersiveModeState.value = false
        chromeVisibleState.value = true
        interactionTickState.intValue++
    }
}
