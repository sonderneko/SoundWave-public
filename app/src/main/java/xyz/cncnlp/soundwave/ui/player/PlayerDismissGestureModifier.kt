package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/** Owns vertical player-dismiss recognition while allowing lists and their controls to consume input first. */
@Composable
internal fun rememberPlayerDismissGestureModifier(
    songKey: String,
    screenHeightPx: Float,
    touchSlopPx: Float,
    dismissOffsetState: State<Float>,
    midPanel: String,
    queueListState: LazyListState,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: () -> Unit,
    onResetDismiss: () -> Unit,
    onReturnToPreviousMode: () -> Unit
): Modifier {
    val density = LocalDensity.current
    val latestMidPanel = rememberUpdatedState(midPanel)
    val latestOnDismissDrag = rememberUpdatedState(onDismissDrag)
    val latestOnDismissEnd = rememberUpdatedState(onDismissEnd)
    val latestOnResetDismiss = rememberUpdatedState(onResetDismiss)
    val latestOnReturnToPreviousMode = rememberUpdatedState(onReturnToPreviousMode)
    val queueDownSwipeThresholdPx = with(density) { 88.dp.toPx() }
    val dismissNestedConnection = remember(screenHeightPx, dismissOffsetState, queueListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val currentOffset = dismissOffsetState.value
                if (source != NestedScrollSource.UserInput || available.y <= 0f || currentOffset <= 0f) return Offset.Zero
                val consumedY = available.y.coerceAtMost(currentOffset)
                latestOnDismissDrag.value(-consumedY)
                return Offset(0f, consumedY)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                if (latestMidPanel.value == "queue") return Offset.Zero
                latestOnDismissDrag.value(-available.y)
                return Offset(0f, available.y)
            }
        }
    }

    // Final pass observes only unconsumed movement; clicks, lyric scroll and queue long-press reorder stay intact.
    return Modifier
        .nestedScroll(dismissNestedConnection)
        .pointerInput(songKey, screenHeightPx, touchSlopPx, queueDownSwipeThresholdPx) {
            awaitEachGesture {
                var endedNormally = false
                try {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.pressed }
                        ?: return@awaitEachGesture
                    var totalX = 0f
                    var totalY = 0f
                    var dismissGesture = false
                    var queueDownSwipePx = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            totalX += delta.x
                            totalY += delta.y
                            if (!dismissGesture && totalY > touchSlopPx && totalY > kotlin.math.abs(totalX) * 1.15f) {
                                dismissGesture = true
                            }
                            if (dismissGesture) {
                                if (latestMidPanel.value == "queue") {
                                    if (delta.y > 0f && !queueListState.canScrollBackward) queueDownSwipePx += delta.y
                                } else latestOnDismissDrag.value(delta.y)
                            }
                        }
                        if (!change.pressed) {
                            if (latestMidPanel.value == "queue" && queueDownSwipePx >= queueDownSwipeThresholdPx) {
                                latestOnReturnToPreviousMode.value()
                            }
                            endedNormally = true
                            break
                        }
                    }
                } finally {
                    if (dismissOffsetState.value > 0f) {
                        if (endedNormally) latestOnDismissEnd.value() else latestOnResetDismiss.value()
                    }
                }
            }
        }
}
