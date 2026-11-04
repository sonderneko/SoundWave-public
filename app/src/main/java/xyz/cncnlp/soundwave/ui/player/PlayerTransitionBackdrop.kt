package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.IMMERSIVE_PLAYER_BACKDROP_ALPHA
import xyz.cncnlp.soundwave.IMMERSIVE_PLAYER_BACKDROP_BLUR_DP
import xyz.cncnlp.soundwave.IMMERSIVE_PLAYER_BACKDROP_SCALE
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.immersivePlayerBackdropScrimAlpha
import xyz.cncnlp.soundwave.immersivePlayerBackdropTintAlpha

/** Draw the same loaded artwork in the moving backdrop and bounded hero to keep their first frame in sync. */
@Composable
internal fun PlayerTransitionBackdrop(
    coverPainter: Painter?,
    coverPalette: CoverBackgroundTones,
    coverLower: Color,
    modifier: Modifier = Modifier
) {
    val coverArtworkLuminance = coverPalette.artworkLuminance
    Box(modifier.background(ThemeTokens.PlayerBackground)) {
        if (coverPainter != null) {
            Image(
                painter = coverPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    clip = false
                    alpha = IMMERSIVE_PLAYER_BACKDROP_ALPHA
                    scaleX = IMMERSIVE_PLAYER_BACKDROP_SCALE
                    scaleY = IMMERSIVE_PLAYER_BACKDROP_SCALE
                }.blur(IMMERSIVE_PLAYER_BACKDROP_BLUR_DP.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to coverPalette.top.copy(alpha = immersivePlayerBackdropTintAlpha(0f)),
                        0.22f to coverPalette.top.copy(alpha = immersivePlayerBackdropTintAlpha(0.22f)),
                        0.48f to coverPalette.top.copy(alpha = immersivePlayerBackdropTintAlpha(0.48f)),
                        0.74f to coverLower.copy(alpha = immersivePlayerBackdropTintAlpha(0.74f)),
                        1f to coverLower.copy(alpha = immersivePlayerBackdropTintAlpha(1f))
                    )
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = immersivePlayerBackdropScrimAlpha(0f, coverArtworkLuminance)),
                        0.22f to Color.Black.copy(alpha = immersivePlayerBackdropScrimAlpha(0.22f, coverArtworkLuminance)),
                        0.48f to Color.Black.copy(alpha = immersivePlayerBackdropScrimAlpha(0.48f, coverArtworkLuminance)),
                        0.74f to Color.Black.copy(alpha = immersivePlayerBackdropScrimAlpha(0.74f, coverArtworkLuminance)),
                        1f to Color.Black.copy(alpha = immersivePlayerBackdropScrimAlpha(1f, coverArtworkLuminance))
                    )
                )
            )
        )
    }
}
