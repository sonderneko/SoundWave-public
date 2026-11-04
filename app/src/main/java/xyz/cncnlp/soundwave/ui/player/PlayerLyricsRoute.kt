package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.IMMERSIVE_LYRICS_ESTIMATED_ROW_HEIGHT_DP
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.immersiveLyricsActiveScrollOffsetPx
import xyz.cncnlp.soundwave.immersiveLyricsEndContentPaddingPx
import xyz.cncnlp.soundwave.immersiveLyricsTopContentPaddingPx
import xyz.cncnlp.soundwave.immersiveLyricsVisibleRowCount
import xyz.cncnlp.soundwave.immersiveLyricsViewportStartIndex
import xyz.cncnlp.soundwave.playerLyricsActiveScrollOffsetPx
import xyz.cncnlp.soundwave.playerLyricsCenteredScrollOffsetPx
import xyz.cncnlp.soundwave.playerLyricsFirstLineTopPaddingPx
import xyz.cncnlp.soundwave.playerLyricsLastLineBottomPaddingPx
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

private sealed interface PlayerLyricsScrollEvent {
    data class Drag(val active: Boolean) : PlayerLyricsScrollEvent
    data class Position(val isScrollInProgress: Boolean, val centeredIndex: Int?) : PlayerLyricsScrollEvent
}

/** Owns lyric viewport measurement and scrolling; playback, routing and user selection remain caller-owned. */
@Composable
internal fun PlayerLyricsRoute(
    lyrics: List<LyricLine>,
    lyricWindowIndices: IntRange,
    activeIndex: Int,
    selectedIndex: Int,
    browseState: PlayerLyricBrowseState,
    manualBrowsing: Boolean,
    currentTimeMs: Long,
    duration: Float,
    lyricOffset: Int,
    lyricLayout: Boolean,
    immersiveMode: Boolean,
    immersiveCoverLyricsMode: Boolean,
    fullLyricsPageScrollMode: Boolean,
    lyricScrollTrackKey: String,
    lyricScrollRouteKey: String,
    lyricScale: Float,
    lyricFontSp: Int,
    lyricImmersiveSp: Int,
    lyricGapDp: Int,
    lyricImmersiveGapDp: Int,
    lyricBlurDp: Int,
    lyricHighlightHex: String,
    lyricUnhighlightHex: String,
    lyricBold: Boolean,
    lyricShowTranslation: Boolean?,
    lyricShowRomanization: Boolean,
    lyricWordKaraoke: Boolean,
    lyricWordLiftEnabled: Boolean,
    reduceMotion: Boolean,
    horizontalAlignment: Alignment.Horizontal,
    textAlignment: TextAlign,
    onBeginBrowsing: () -> Unit,
    onSelect: (Int) -> Unit,
    onSettleBrowsing: (Int) -> Unit,
    onPlay: (LyricLine) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val density = LocalDensity.current
    var viewportPx by remember { mutableIntStateOf(0) }
    var activeItemPx by remember { mutableIntStateOf(0) }
    var selectedItemPx by remember { mutableIntStateOf(0) }
    val browseLineIndex = if (manualBrowsing) selectedIndex else activeIndex
    val anchorItemPx = if (manualBrowsing) selectedItemPx.takeIf { it > 0 } ?: activeItemPx else activeItemPx
    val halfPadDp = with(density) {
        if (viewportPx > 0) ((viewportPx - anchorItemPx).coerceAtLeast(0) / 2).toDp() else 0.dp
    }
    val immersiveCoverAnchorLinePx = anchorItemPx.takeIf { it > 0 } ?: with(density) {
        IMMERSIVE_LYRICS_ESTIMATED_ROW_HEIGHT_DP.dp.toPx().toInt()
    }
    val immersiveCoverTopPadDp = with(density) {
        immersiveLyricsTopContentPaddingPx(viewportPx, immersiveCoverAnchorLinePx, browseLineIndex).toDp()
    }
    val immersiveCoverBottomPadDp = with(density) {
        immersiveLyricsEndContentPaddingPx(viewportPx, immersiveCoverAnchorLinePx).toDp()
    }
    val immersiveCoverScrollOffsetPx = immersiveLyricsActiveScrollOffsetPx(
        viewportHeightPx = viewportPx,
        activeLineHeightPx = immersiveCoverAnchorLinePx,
        currentIndex = browseLineIndex
    )
    val lyricPageTopPaddingDp = with(density) {
        playerLyricsFirstLineTopPaddingPx(viewportPx, anchorItemPx, browseLineIndex).toDp()
    }
    val lyricPageBottomPaddingDp = with(density) {
        playerLyricsLastLineBottomPaddingPx(viewportPx, anchorItemPx, browseLineIndex, lyrics.size).toDp()
    }
    val lyricPageVisibleRows = if (fullLyricsPageScrollMode) with(density) {
        immersiveLyricsVisibleRowCount(viewportHeightDp = viewportPx.toDp().value, activeLineHeightDp = anchorItemPx.toDp().value)
    } else 0
    val lyricPageInitialScrollIndex = if (fullLyricsPageScrollMode) {
        immersiveLyricsViewportStartIndex(lyrics.size, browseLineIndex, lyricPageVisibleRows)
    } else 0
    val lastLyricSlot = lyricWindowIndices.count() - 1
    val initialLyricIndex = if (browseLineIndex >= 0) {
        when {
            manualBrowsing -> browseLineIndex
            immersiveCoverLyricsMode -> (activeIndex - 1).coerceAtLeast(0)
            fullLyricsPageScrollMode -> lyricPageInitialScrollIndex
            else -> (activeIndex - lyricWindowIndices.first).coerceIn(0, lastLyricSlot)
        }
    } else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialLyricIndex)
    val lyricsContentPadding = when {
        browseLineIndex < 0 -> PaddingValues(vertical = 12.dp)
        immersiveCoverLyricsMode -> PaddingValues(top = immersiveCoverTopPadDp, bottom = immersiveCoverBottomPadDp)
        fullLyricsPageScrollMode -> PaddingValues(top = lyricPageTopPaddingDp, bottom = lyricPageBottomPaddingDp)
        else -> PaddingValues(vertical = halfPadDp)
    }
    var lastLyricScrollRouteKey by remember { mutableStateOf("") }
    var lastLyricScrollIndex by remember { mutableIntStateOf(-1) }
    var lastLyricScrollViewportPx by remember { mutableIntStateOf(-1) }
    var lastLyricScrollRowHeightPx by remember { mutableIntStateOf(-1) }

    // Only an actual LazyList drag arms manual browsing; programmatic or geometry-driven scrolling is ignored.
    val latestBeginBrowsing by rememberUpdatedState(onBeginBrowsing)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestSettleBrowsing by rememberUpdatedState(onSettleBrowsing)
    val latestLyrics by rememberUpdatedState(lyrics)
    val latestLyricWindowIndices by rememberUpdatedState(lyricWindowIndices)
    LaunchedEffect(listState) {
        var userDragActive = false
        var userScrollArmed = false
        var listScrollInProgress = listState.isScrollInProgress

        fun centeredLyricIndex(): Int? {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val slot = info.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2f - center)
            }?.index ?: return null
            val index = latestLyricWindowIndices.first + slot
            return index.takeIf { it in latestLyrics.indices }
        }

        val dragEvents = listState.interactionSource.interactions
            .filter { it is DragInteraction.Start || it is DragInteraction.Stop || it is DragInteraction.Cancel }
            .map { interaction -> PlayerLyricsScrollEvent.Drag(interaction is DragInteraction.Start) }
        val positionEvents = snapshotFlow {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val slot = info.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2f - center)
            }?.index
            val index = slot?.let { latestLyricWindowIndices.first + it }
                ?.takeIf { it in latestLyrics.indices }
            PlayerLyricsScrollEvent.Position(listState.isScrollInProgress, index)
        }.distinctUntilChanged()

        merge(dragEvents, positionEvents).collect { event ->
            val centeredIndex = when (event) {
                is PlayerLyricsScrollEvent.Drag -> {
                    userDragActive = event.active
                    if (event.active && !userScrollArmed) {
                        userScrollArmed = true
                        latestBeginBrowsing()
                    }
                    centeredLyricIndex()
                }
                is PlayerLyricsScrollEvent.Position -> {
                    listScrollInProgress = event.isScrollInProgress
                    event.centeredIndex
                }
            }

            if (!userScrollArmed) return@collect
            if (userDragActive || listScrollInProgress) {
                centeredIndex?.let(latestSelect)
            } else {
                latestSettleBrowsing(centeredIndex ?: centeredLyricIndex() ?: -1)
                userScrollArmed = false
            }
        }
    }

    // Natural playback keeps the existing mode-specific anchor; it never steals focus during manual browsing.
    LaunchedEffect(
        activeIndex,
        viewportPx,
        activeItemPx,
        lyricWindowIndices,
        lyrics.size,
        immersiveCoverLyricsMode,
        lyricScrollTrackKey,
        fullLyricsPageScrollMode,
        lyricScrollRouteKey,
        manualBrowsing
    ) {
        if (lyrics.isEmpty()) {
            lastLyricScrollRouteKey = ""
            lastLyricScrollIndex = -1
            return@LaunchedEffect
        }
        if (manualBrowsing || activeIndex < 0) return@LaunchedEffect
        try {
            if (immersiveCoverLyricsMode) {
                listState.animateScrollToItem(activeIndex, immersiveCoverScrollOffsetPx)
            } else if (fullLyricsPageScrollMode) {
                val routeChanged = lyricScrollRouteKey != lastLyricScrollRouteKey || lastLyricScrollIndex < 0
                val geometryChanged = viewportPx != lastLyricScrollViewportPx || activeItemPx != lastLyricScrollRowHeightPx
                when {
                    routeChanged -> listState.scrollToItem(
                        activeIndex,
                        playerLyricsActiveScrollOffsetPx(viewportPx, activeItemPx, activeIndex)
                    )
                    activeIndex != lastLyricScrollIndex -> listState.animateScrollToItem(
                        activeIndex,
                        playerLyricsActiveScrollOffsetPx(viewportPx, activeItemPx, activeIndex)
                    )
                    geometryChanged -> listState.animateScrollToItem(
                        activeIndex,
                        playerLyricsActiveScrollOffsetPx(viewportPx, activeItemPx, activeIndex)
                    )
                }
                lastLyricScrollRouteKey = lyricScrollRouteKey
                lastLyricScrollIndex = activeIndex
                lastLyricScrollViewportPx = viewportPx
                lastLyricScrollRowHeightPx = activeItemPx
            } else {
                listState.animateScrollToItem(initialLyricIndex, 0)
            }
        } catch (_: Exception) {
        }
    }

    // Row taps and settled manual scrolling both snap the chosen lyric to the same central anchor.
    LaunchedEffect(
        manualBrowsing,
        selectedIndex,
        viewportPx,
        selectedItemPx,
        activeItemPx,
        lyricWindowIndices,
        lyricScrollRouteKey
    ) {
        if (!manualBrowsing || selectedIndex !in lyrics.indices || listState.isScrollInProgress) return@LaunchedEffect
        val rowHeight = selectedItemPx.takeIf { it > 0 } ?: activeItemPx
        val slot = (selectedIndex - lyricWindowIndices.first).coerceIn(0, lyricWindowIndices.count() - 1)
        try {
            listState.animateScrollToItem(
                slot,
                playerLyricsCenteredScrollOffsetPx(viewportPx, rowHeight)
            )
        } catch (_: Exception) {
        }
    }

    PlayerLyricsViewport(
        lyrics = lyrics,
        lyricWindowIndices = lyricWindowIndices,
        listState = listState,
        userScrollEnabled = fullLyricsPageScrollMode || immersiveMode || activeIndex < 0,
        contentPadding = lyricsContentPadding,
        modifier = Modifier.fillMaxSize()
            .onSizeChanged { viewportPx = it.height }
            .padding(horizontal = if (immersiveMode) 24.dp else 28.dp),
        activeIndex = activeIndex,
        selectedIndex = selectedIndex,
        browseState = browseState,
        currentTimeMs = currentTimeMs,
        duration = duration,
        lyricOffset = lyricOffset,
        lyricLayout = lyricLayout,
        immersiveMode = immersiveMode,
        lyricScale = lyricScale,
        lyricFontSp = lyricFontSp,
        lyricImmersiveSp = lyricImmersiveSp,
        lyricGapDp = lyricGapDp,
        lyricImmersiveGapDp = lyricImmersiveGapDp,
        lyricBlurDp = lyricBlurDp,
        lyricHighlightHex = lyricHighlightHex,
        lyricUnhighlightHex = lyricUnhighlightHex,
        lyricBold = lyricBold,
        lyricShowTranslation = lyricShowTranslation,
        lyricShowRomanization = lyricShowRomanization,
        lyricWordKaraoke = lyricWordKaraoke,
        lyricWordLiftEnabled = lyricWordLiftEnabled,
        reduceMotion = reduceMotion,
        horizontalAlignment = horizontalAlignment,
        textAlignment = textAlignment,
        onItemSizeChanged = { index, sizePx ->
            if (index == activeIndex && activeItemPx != sizePx) activeItemPx = sizePx
            if (index == selectedIndex && selectedItemPx != sizePx) selectedItemPx = sizePx
        },
        onSelect = onSelect,
        onPlay = onPlay,
        onLongPress = onLongPress
    )
}
