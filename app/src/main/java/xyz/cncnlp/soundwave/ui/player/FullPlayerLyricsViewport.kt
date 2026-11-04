package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.LyricLine
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * Stateless lyric-list renderer. FullPlayer retains list/scroll state, viewport
 * measurements, route decisions, and seek/navigation event dispatch.
 */
@Composable
internal fun PlayerLyricsViewport(
    lyrics: List<LyricLine>,
    lyricWindowIndices: IntRange,
    listState: LazyListState,
    userScrollEnabled: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier,
    activeIndex: Int,
    selectedIndex: Int,
    browseState: PlayerLyricBrowseState,
    currentTimeMs: Long,
    duration: Float,
    lyricOffset: Int,
    lyricLayout: Boolean,
    immersiveMode: Boolean,
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
    onItemSizeChanged: (index: Int, sizePx: Int) -> Unit,
    onSelect: (Int) -> Unit,
    onPlay: (LyricLine) -> Unit,
    onLongPress: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
        userScrollEnabled = userScrollEnabled,
        contentPadding = contentPadding,
        modifier = modifier,
        horizontalAlignment = horizontalAlignment
    ) {
        items(
            count = lyricWindowIndices.count(),
            key = { slot ->
                val index = lyricWindowIndices.first + slot
                "${lyrics[index].timeMs}:$index"
            }
        ) { slot ->
            val index = lyricWindowIndices.first + slot
            PlayerLyricRow(
                line = lyrics[index],
                nextLine = lyrics.getOrNull(index + 1),
                index = index,
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
                seekAvailable = playerLyricSeekProgress(lyrics[index].timeMs, duration, lyricOffset.toLong()) != null,
                onItemSizeChanged = { sizePx -> onItemSizeChanged(index, sizePx) },
                onSelect = onSelect,
                onPlay = { onPlay(lyrics[index]) },
                onLongPress = { onLongPress(index) }
            )
        }
    }
}
