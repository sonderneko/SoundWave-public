package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.PLAYER_LYRICS_PREVIEW_LINE_GAP_DP
import xyz.cncnlp.soundwave.PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP
import xyz.cncnlp.soundwave.PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP
import xyz.cncnlp.soundwave.playerLyricsPreviewIndices
import xyz.cncnlp.soundwave.playerLyricsPreviewMaxHeightDp
import xyz.cncnlp.soundwave.playerLyricsStylePreviewTapShouldEnterImmersive

/** Stateless Lyrics Style preview; the player owns browse state, seeking, and route changes. */
@Composable
internal fun ColumnScope.PlayerLyricsStylePreview(
    lyrics: List<LyricLine>,
    activeIndex: Int,
    browseState: PlayerLyricBrowseState,
    layout: String,
    coverFraction: Float,
    displayTime: Float,
    duration: Float,
    lyricOffset: Int,
    lyricScale: Float,
    lyricFontSp: Int,
    lyricImmersiveSp: Int,
    lyricGapDp: Int,
    lyricImmersiveGapDp: Int,
    lyricBlurDp: Int,
    lyricHighlightHex: String,
    lyricUnhighlightHex: String,
    lyricBold: Boolean,
    lyricShowTranslation: Boolean,
    lyricShowRomanization: Boolean,
    lyricWordKaraoke: Boolean,
    lyricWordLiftEnabled: Boolean,
    reduceMotion: Boolean,
    white: Color,
    onOpenLyrics: () -> Unit,
    onSelect: (Int) -> Unit,
    onPlay: (LyricLine) -> Unit,
    onLongPress: (Int) -> Unit,
    onCompactPreviewTap: (Int) -> Unit
) {
    Spacer(Modifier.height(PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP.dp))
    if (lyrics.isEmpty()) {
        Box(
            Modifier.fillMaxWidth(coverFraction).height(112.dp).align(Alignment.CenterHorizontally).offset(y = 8.dp),
            contentAlignment = Alignment.Center
        ) { Text("暂无歌词", color = white.copy(alpha = 0.68f), fontSize = 15.sp) }
    } else {
        val previewFocusIndex = playerLyricFocusedIndex(browseState, activeIndex)
        val previewIndices = playerLyricsPreviewIndices(lyrics.size, previewFocusIndex)
        val previewMaxHeight = playerLyricsPreviewMaxHeightDp(LocalConfiguration.current.screenHeightDp).dp
        Column(
            Modifier.fillMaxWidth(coverFraction).align(Alignment.CenterHorizontally).padding(vertical = 2.dp).offset(y = 8.dp)
                .heightIn(max = previewMaxHeight)
                .verticalScroll(rememberScrollState())
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onOpenLyrics() },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PLAYER_LYRICS_PREVIEW_LINE_GAP_DP.dp)
        ) {
            previewIndices.forEach { lineIndex ->
                val line = lyrics[lineIndex]
                key(line.timeMs, lineIndex) {
                    PlayerLyricRow(
                        line = line,
                        nextLine = lyrics.getOrNull(lineIndex + 1),
                        index = lineIndex,
                        activeIndex = activeIndex,
                        selectedIndex = previewFocusIndex,
                        browseState = browseState,
                        currentTimeMs = (displayTime * 1000f).toLong() + lyricOffset,
                        duration = duration,
                        lyricOffset = lyricOffset,
                        lyricLayout = false,
                        immersiveMode = false,
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
                        horizontalAlignment = Alignment.CenterHorizontally,
                        textAlignment = TextAlign.Center,
                        compactPreview = true,
                        seekAvailable = playerLyricSeekProgress(line.timeMs, duration, lyricOffset.toLong()) != null,
                        onItemSizeChanged = {},
                        onSelect = onSelect,
                        onPlay = { onPlay(line) },
                        onLongPress = { onLongPress(lineIndex) },
                        onCompactPreviewTap = if (
                            playerLyricsStylePreviewTapShouldEnterImmersive(layout, compactPreview = true)
                        ) {
                            { onCompactPreviewTap(lineIndex) }
                        } else null
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP.dp))
}
