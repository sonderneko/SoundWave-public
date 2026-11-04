package xyz.cncnlp.soundwave

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import xyz.cncnlp.soundwave.ThemeTokens.MiniPlayer as MiniTokens
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DockMetrics

/** Pure metadata selection. Playback/resolving state and all callbacks stay owned by the caller. */
internal fun miniPlayerSubtitle(song: Song, resolving: Boolean, compact: Boolean, showLyrics: Boolean, lyricLine: String): String = when {
    resolving -> "缓冲中…"
    compact && showLyrics && lyricLine.isNotBlank() -> lyricLine
    else -> listOf(song.singer.ifBlank { "未知歌手" }, song.album.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** One layout contract shared by the visible mini, its dock host and content avoidance. */
internal data class MiniPlayerPresentation(
    val capsule: Boolean,
    val embedded: Boolean,
    val compactEmbedded: Boolean,
    val baseHeight: Dp,
    val height: Dp,
    val coverSize: Dp,
    val textGap: Dp,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle
)

@Composable
internal fun rememberMiniPlayerPresentation(
    style: String = "default", compact: Boolean = false, fullWidth: Boolean = false
): MiniPlayerPresentation {
    val capsule = style == "capsule"
    val embedded = compact && !fullWidth
    val compactEmbedded = embedded && !capsule
    val baseHeight = when {
        capsule -> DockMetrics.CapsuleMiniHeight
        compactEmbedded -> DockCompactMiniH
        else -> DockMetrics.MiniHeight
    }
    val titleSize = when { compactEmbedded -> MiniTokens.CompactTitleSize; capsule -> MiniTokens.CapsuleTitleSize; else -> MiniTokens.TitleSize }
    val titleLine = when { compactEmbedded -> MiniTokens.CompactTitleLine; capsule -> MiniTokens.CapsuleTitleLine; else -> MiniTokens.TitleLine }
    val subSize = when { compactEmbedded -> MiniTokens.CompactSubtitleSize; capsule -> MiniTokens.CapsuleSubtitleSize; else -> MiniTokens.SubtitleSize }
    val subLine = when { compactEmbedded -> MiniTokens.CompactSubtitleLine; capsule -> MiniTokens.CapsuleSubtitleLine; else -> MiniTokens.SubtitleLine }
    val inherited = LocalTextStyle.current
    val titleStyle = inherited.copy(fontSize = titleSize, lineHeight = titleLine, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope)
    val subtitleStyle = inherited.copy(fontSize = subSize, lineHeight = subLine, fontFamily = DesignFonts.Manrope)
    val gap = if (compactEmbedded) MiniTokens.CompactTextGap else MiniTokens.TextGap
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Use Compose's own font metrics/density conversion, not fontScale * sp. Include CJK fallback
    // as well as Latin ascenders/descenders. Normal-size geometry is a minimum, not a text ceiling.
    val titleHeight = measurer.measure("国Ag", titleStyle, maxLines = 1).size.height
    val subtitleHeight = measurer.measure("国Ag", subtitleStyle, maxLines = 1).size.height
    val textHeight = with(density) { (titleHeight + subtitleHeight).toDp() } + gap + MiniTokens.VerticalPadding * 2
    val height = maxOf(baseHeight, textHeight)
    val coverSize = when {
        capsule -> DockMetrics.CapsuleMiniHeight - MiniTokens.CapsuleCoverInset
        compactEmbedded -> MiniTokens.CompactCover
        else -> MiniTokens.Cover
    }
    return MiniPlayerPresentation(capsule, embedded, compactEmbedded, baseHeight, height, coverSize, gap, titleStyle, subtitleStyle)
}

/** Keep the host stable across song visibility, content tabs and full-player transitions. */
internal fun miniPlayerDockHostHeight(presentation: MiniPlayerPresentation): Dp =
    PLAYER_DOCK_HOST_HEIGHT_DP.dp + (presentation.height - DockMetrics.MiniHeight).coerceAtLeast(0.dp)
