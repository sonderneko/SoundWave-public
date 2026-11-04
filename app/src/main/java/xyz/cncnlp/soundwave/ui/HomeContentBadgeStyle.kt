package xyz.cncnlp.soundwave.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared home discover badge appearance, matching the existing NetEase digital-album pill. */
internal data class HomeContentBadgeStyle(
    val background: Color,
    val foreground: Color,
    val backgroundHeightScale: Float,
    val cornerRadiusHeightFraction: Float,
    val horizontalPadding: Dp,
    val verticalOutset: Dp
)

internal val HOME_CONTENT_BADGE_STYLE = HomeContentBadgeStyle(
    background = Color.White,
    foreground = Color.Black,
    backgroundHeightScale = 2f / 3f,
    cornerRadiusHeightFraction = 0.36f,
    horizontalPadding = 5.dp,
    verticalOutset = 2.dp
)
