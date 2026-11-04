package xyz.cncnlp.soundwave.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared narrow next-track silhouette; 48dp touch area stays unchanged. */
@Composable
internal fun PlayerLyricsNextButton(tint: Color, onNext: () -> Unit) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    DefaultPlayerIconButton(DefaultPlayerGlyphs.Next, "下一首", tint,
        size = 48.dp, iconSize = 26.dp, iconWidthFraction = 0.88f, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onNext() })
}
