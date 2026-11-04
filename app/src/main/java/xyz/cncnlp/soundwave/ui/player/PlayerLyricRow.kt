package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.KaraokeLine
import xyz.cncnlp.soundwave.LocalLyricFontFamily
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.shouldShowLyricTranslation
import xyz.cncnlp.soundwave.PLAYER_LYRICS_PREVIEW_TEXT_MAX_LINES
import xyz.cncnlp.soundwave.playerLyricsPreviewFontSp
import xyz.cncnlp.soundwave.playerShouldLiftCurrentWords
import xyz.cncnlp.soundwave.playerShouldRenderWordKaraoke
import xyz.cncnlp.soundwave.playerLyricsTextMaxLines
import xyz.cncnlp.soundwave.ThemeTokens.PlayerLyrics
import xyz.cncnlp.soundwave.themeColor
import xyz.cncnlp.soundwave.WhiteGlyph
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared lyric row for the full-page timeline and the compact lyrics-style preview. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerLyricRow(
    line: LyricLine,
    nextLine: LyricLine?,
    index: Int,
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
    compactPreview: Boolean = false,
    seekAvailable: Boolean,
    onItemSizeChanged: (Int) -> Unit,
    onSelect: (Int) -> Unit,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onCompactPreviewTap: (() -> Unit)? = null
) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val active = index == activeIndex
    val focused = playerLyricShouldUsePlaybackHighlight(index, activeIndex)
    val played = index < activeIndex
    val base = if (compactPreview) {
        playerLyricsPreviewFontSp(active).toFloat()
    } else {
        playerLyricRenderFontSp(immersiveMode, lyricFontSp, lyricImmersiveSp, lyricScale)
    }
    val gapForLine = playerLyricPresentationGap(compactPreview, immersiveMode, lyricGapDp, lyricImmersiveGapDp)
    val lineHeightSp = playerLyricPresentationLineHeight(base, gapForLine, compactPreview)
    // J 波：当前行字号放大（呈现层），行高同比例放大避免裁切；译文/罗马音仍以 base 派生。
    val lineFontSp = playerLyricPresentationLineFontSp(base, focused, immersiveMode, compactPreview)
    val lineTextLineHeightSp = if (lineFontSp != base && base > 0f) lineHeightSp * (lineFontSp / base) else lineHeightSp
    val focusIndex = selectedIndex.takeIf { it >= 0 } ?: activeIndex
    val distance = if (focusIndex < 0) Int.MAX_VALUE else kotlin.math.abs(index - focusIndex)
    val lyricAlpha = playerLyricPresentationAlpha(focusIndex, distance, focused, played)
    val neighborBlur = playerLyricPresentationBlur(lyricBlurDp, near = true)
    val farBlur = playerLyricPresentationBlur(lyricBlurDp, near = false)
    val lyricColor = themeColor(
        if (focused) lyricHighlightHex else lyricUnhighlightHex,
        if (focused) PlayerLyrics.Ink else PlayerLyrics.InactiveFallback
    ).copy(alpha = lyricAlpha)
    val showPlayControls = !immersiveMode && playerLyricShouldShowRowActions(
        state = browseState,
        index = index,
        seekAvailable = seekAvailable
    )
    val showSelectedBackground = !immersiveMode && playerLyricShouldShowSelectionBackground(browseState, index)
    val reserveSeekSlots = !immersiveMode && playerLyricShouldReserveRowActionSlots(browseState, compactPreview)
    val lineModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(if (compactPreview) PlayerLyrics.PreviewRadius else PlayerLyrics.RowRadius))
        .background(if (showSelectedBackground) (if (compactPreview) PlayerLyrics.PreviewSelectedFill else PlayerLyrics.SelectedFill) else Color.Transparent)
        .onSizeChanged { onItemSizeChanged(it.height) }
        .then(
            when {
                focusIndex < 0 || focused || lyricBlurDp <= 0 -> Modifier
                distance == 1 -> Modifier.blur(neighborBlur.dp)
                else -> Modifier.blur(farBlur.dp)
            }
        )
        .combinedClickable(
            onClickLabel = if (compactPreview && onCompactPreviewTap != null) "进入沉浸歌词模式" else null,
            onClick = {
                if (compactPreview && onCompactPreviewTap != null) onCompactPreviewTap()
                else onSelect(index)
            },
            onLongClickLabel = "选择歌词",
            onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongPress() }
        )

    Box(lineModifier) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = if (reserveSeekSlots) PlayerLyrics.ActionGutter else PlayerLyrics.TextInset)
                .padding(vertical = playerLyricPresentationVerticalInset(gapForLine, compactPreview, immersiveMode).dp),
            horizontalAlignment = horizontalAlignment
        ) {
            if (playerShouldRenderWordKaraoke(
                    active = active,
                    enabled = lyricWordKaraoke,
                    hasWordTimeline = line.hasWordTimeline()
                ) || (active && playerShouldLiftCurrentWords(lyricWordLiftEnabled, line.hasWordTimeline(), reduceMotion))
            ) {
                KaraokeLine(
                    line = line,
                    next = nextLine,
                    curMs = currentTimeMs,
                    color = lyricColor,
                    fontSize = lineFontSp.sp,
                    lineHeightSp = lineTextLineHeightSp,
                    textAlign = textAlignment,
                    startAlign = false,
                    highlightWords = lyricWordKaraoke,
                    liftWords = playerShouldLiftCurrentWords(
                        enabled = lyricWordLiftEnabled,
                        hasWordTimeline = line.hasWordTimeline(),
                        systemReduceMotion = reduceMotion
                    )
                )
            } else {
                Text(
                    line.text,
                    color = lyricColor,
                    fontSize = lineFontSp.sp,
                    fontFamily = LocalLyricFontFamily.current,
                    fontWeight = if (focused && lyricBold) FontWeight.Bold else FontWeight.Normal,
                    lineHeight = lineTextLineHeightSp.sp,
                    textAlign = textAlignment,
                    overflow = if (compactPreview) TextOverflow.Ellipsis else TextOverflow.Visible,
                    softWrap = true,
                    maxLines = playerLyricsTextMaxLines(
                        fullLyricsPage = lyricLayout,
                        standardMaxLines = if (compactPreview) PLAYER_LYRICS_PREVIEW_TEXT_MAX_LINES else PlayerLyrics.StandardMaxLines
                    )
                )
            }
            if (shouldShowLyricTranslation(line, lyricShowTranslation)) {
                Text(
                    line.translation,
                    color = lyricColor.copy(alpha = PlayerLyrics.TranslationAlpha),
                    fontSize = (base * PlayerLyrics.TranslationScale).sp,
                    lineHeight = (lineHeightSp * PlayerLyrics.TranslationLineHeightScale).sp,
                    textAlign = textAlignment,
                    maxLines = playerLyricsTextMaxLines(lyricLayout, PlayerLyrics.AuxiliaryMaxLines)
                )
            }
            if (lyricShowRomanization && line.romanization.isNotBlank()) {
                Text(
                    line.romanization,
                    color = lyricColor.copy(alpha = PlayerLyrics.RomanizationAlpha),
                    fontSize = (base * PlayerLyrics.RomanizationScale).sp,
                    lineHeight = (lineHeightSp * PlayerLyrics.RomanizationLineHeightScale).sp,
                    textAlign = textAlignment,
                    maxLines = playerLyricsTextMaxLines(lyricLayout, PlayerLyrics.AuxiliaryMaxLines)
                )
            }
        }
        if (showPlayControls) {
            Text(
                playerLyricTimeLabel(line.timeMs).orEmpty(),
                modifier = Modifier.align(Alignment.CenterStart).width(PlayerLyrics.TimestampWidth).padding(start = PlayerLyrics.TimestampInset),
                color = PlayerLyrics.TimestampInk,
                fontSize = PlayerLyrics.TimestampSize,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Box(
                Modifier.align(Alignment.CenterEnd).size(PlayerLyrics.PlayTarget)
                    .clickable(role = Role.Button, onClick = onPlay)
                    .semantics { contentDescription = "从 ${playerLyricTimeLabel(line.timeMs)} 播放此句" },
                contentAlignment = Alignment.Center
            ) {
                WhiteGlyph("play_round", PlayerLyrics.PlayGlyph, PlayerLyrics.Ink)
            }
        }
    }
}

internal fun playerLyricTimeLabel(timeMs: Long): String? {
    if (timeMs < 0L) return null
    val totalSeconds = timeMs / 1_000L
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
