package xyz.cncnlp.soundwave.ui.player

/** User browsing is independent of the line currently driven by playback. */
internal data class PlayerLyricBrowseState(
    val selectedIndex: Int? = null,
    val isBrowsing: Boolean = false,
    val isSelecting: Boolean = false,
    val hasManuallyScrolled: Boolean = false,
    val isUserScrollActive: Boolean = false
)

internal fun playerLyricFocusedIndex(state: PlayerLyricBrowseState, activeIndex: Int): Int =
    if (state.isBrowsing) state.selectedIndex ?: activeIndex else activeIndex

internal fun playerLyricPlaybackIndexChanged(
    state: PlayerLyricBrowseState,
    activeIndex: Int
): PlayerLyricBrowseState = if (state.isBrowsing) {
    state
} else {
    state.copy(selectedIndex = activeIndex.takeIf { it >= 0 })
}

internal fun playerLyricBeginBrowsing(
    state: PlayerLyricBrowseState,
    focusedIndex: Int
): PlayerLyricBrowseState = state.copy(
    selectedIndex = focusedIndex.takeIf { it >= 0 } ?: state.selectedIndex,
    isBrowsing = true,
    hasManuallyScrolled = true,
    isUserScrollActive = true
)

internal fun playerLyricSelectIndex(
    state: PlayerLyricBrowseState,
    index: Int
): PlayerLyricBrowseState = if (index < 0) state else state.copy(
    selectedIndex = index,
    isBrowsing = true
)

/** A real user scroll only reveals the selected background after the viewport settles. */
internal fun playerLyricSettleBrowsing(
    state: PlayerLyricBrowseState,
    index: Int
): PlayerLyricBrowseState = if (index < 0) {
    state.copy(isUserScrollActive = false)
} else {
    state.copy(
        selectedIndex = index,
        isBrowsing = true,
        isUserScrollActive = false
    )
}

internal fun playerLyricShouldShowSelectionBackground(
    state: PlayerLyricBrowseState,
    index: Int
): Boolean = state.isBrowsing && !state.isUserScrollActive && state.selectedIndex == index

internal fun playerLyricOpenSelection(
    state: PlayerLyricBrowseState,
    index: Int
): PlayerLyricBrowseState = playerLyricSelectIndex(state, index).copy(isSelecting = true)

internal fun playerLyricCloseSelection(state: PlayerLyricBrowseState): PlayerLyricBrowseState =
    state.copy(isSelecting = false)

/** The play button relinquishes manual focus so subsequent playback can drive the viewport again. */
internal fun playerLyricResumePlaybackFollow(state: PlayerLyricBrowseState): PlayerLyricBrowseState =
    playerLyricExitLyricsPage(state)

internal fun playerLyricExitLyricsPage(state: PlayerLyricBrowseState): PlayerLyricBrowseState =
    state.copy(
        selectedIndex = null,
        isBrowsing = false,
        isSelecting = false,
        hasManuallyScrolled = false,
        isUserScrollActive = false
    )

internal fun playerLyricShouldShowRowActions(
    state: PlayerLyricBrowseState,
    index: Int,
    seekAvailable: Boolean
): Boolean = state.hasManuallyScrolled && state.selectedIndex == index && seekAvailable

/** Reserve identical seek-control gutters for every full-page row throughout a manual browse. */
internal fun playerLyricShouldReserveRowActionSlots(
    state: PlayerLyricBrowseState,
    compactPreview: Boolean
): Boolean = !compactPreview && state.hasManuallyScrolled

/** Manual selection changes the target row, never which lyric is visually driven by playback. */
internal fun playerLyricShouldUsePlaybackHighlight(index: Int, activeIndex: Int): Boolean =
    index == activeIndex
