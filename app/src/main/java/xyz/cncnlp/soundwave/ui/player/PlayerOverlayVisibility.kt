package xyz.cncnlp.soundwave.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import xyz.cncnlp.soundwave.PlayerDismissOffsetHolder
import xyz.cncnlp.soundwave.playerDetailUnderlayShouldDraw
import xyz.cncnlp.soundwave.playerDismissGestureRevealsSource
import xyz.cncnlp.soundwave.playerDismissSurfaceOffsetPx
import xyz.cncnlp.soundwave.playerRouteUnderlayShouldDraw
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.PLAYER_SHEET_EXIT_DURATION_MS
import xyz.cncnlp.soundwave.PLAYER_SHEET_HOST_EXIT_TAIL_MS
import xyz.cncnlp.soundwave.playerOverlayVisible

internal data class PlayerOverlayVisibilityState(
    val sheetVisible: Boolean,
)

/** Keep the source route available through entry, exit and active drag, but not under a settled full-screen sheet. */
internal fun playerUnderlayShouldDraw(
    rawLayout: String,
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetPx: Float = 0f
): Boolean = playerRouteUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)

internal fun playerOutputRouteMonitoringEnabled(outputDeviceSheetVisible: Boolean): Boolean =
    outputDeviceSheetVisible

internal fun Modifier.drawPlayerUnderlayDuringTransition(
    rawLayout: String,
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetHolder: PlayerDismissOffsetHolder
): Modifier = drawWithContent {
    val sourceContent = this
    val dismissOffsetPx = playerDismissSurfaceOffsetPx(dismissOffsetHolder.state.floatValue, size.height)
    if (playerUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)) {
        if (playerDismissGestureRevealsSource(currentState, targetState, dismissOffsetPx)) {
            clipRect(left = 0f, top = 0f, right = size.width, bottom = dismissOffsetPx) { sourceContent.drawContent() }
        } else drawContent()
    }
}

internal fun Modifier.drawPlayerDetailUnderlayDuringTransition(
    rawLayout: String,
    currentState: Boolean,
    targetState: Boolean,
    dismissOffsetHolder: PlayerDismissOffsetHolder
): Modifier = drawWithContent {
    val sourceContent = this
    val dismissOffsetPx = playerDismissSurfaceOffsetPx(dismissOffsetHolder.state.floatValue, size.height)
    if (playerDetailUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)) {
        if (playerDismissGestureRevealsSource(currentState, targetState, dismissOffsetPx)) {
            clipRect(left = 0f, top = 0f, right = size.width, bottom = dismissOffsetPx) { sourceContent.drawContent() }
        } else drawContent()
    }
}

/** Keep system-bar/Dock host coverage alive through the slide and host-restoration tail. */
@Composable
internal fun rememberPlayerOverlayVisibility(
    isPlayerRoute: Boolean,
    currentState: Boolean,
    targetState: Boolean,
): PlayerOverlayVisibilityState {
    var exitTail by remember { mutableStateOf(false) }
    var previousRouteIsPlayer by remember { mutableStateOf(isPlayerRoute) }
    var exitJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isPlayerRoute) {
        val leavingPlayer = previousRouteIsPlayer && !isPlayerRoute
        previousRouteIsPlayer = isPlayerRoute
        if (isPlayerRoute) {
            exitJob?.cancel()
            exitJob = null
            exitTail = false
        } else if (leavingPlayer) {
            exitJob?.cancel()
            exitTail = true
            exitJob = scope.launch {
                delay(PLAYER_SHEET_EXIT_DURATION_MS.toLong() + PLAYER_SHEET_HOST_EXIT_TAIL_MS)
                exitTail = false
                exitJob = null
            }
        }
    }

    return PlayerOverlayVisibilityState(
        sheetVisible = playerOverlayVisible(currentState, targetState) || exitTail,
    )
}
