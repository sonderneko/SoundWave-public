package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

/** Cover-page artwork card; image loading, palette updates, and player state remain parent-owned. */
@Composable
internal fun PlayerCoverArtworkCard(
    modifier: Modifier,
    coverFraction: Float,
    coverScale: Float,
    fullBleedCoverPage: Boolean,
    hasArtwork: Boolean,
    coverLower: Color,
    playerBackground: Color,
    primaryColor: Color,
    resolving: Boolean,
    status: String,
    onClick: () -> Unit,
    artworkContent: @Composable (Modifier) -> Unit
) {
    Box(
        modifier.clickable(indication = null, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val coverShape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxWidth(coverFraction).aspectRatio(1f).offset(x = (-1).dp)
                .then(if (fullBleedCoverPage) Modifier else Modifier.graphicsLayer { scaleX = coverScale; scaleY = coverScale })
                .then(if (fullBleedCoverPage) Modifier else Modifier
                    .shadow(16.dp, coverShape, ambientColor = Color.Black.copy(alpha = 0.17f), spotColor = Color.Black.copy(alpha = 0.21f))
                    .clip(coverShape)
                    .background(primaryColor.copy(alpha = 0.12f)))
                .clickable(indication = null, interactionSource = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (!fullBleedCoverPage) {
                // ACCEPT5 收编：默认封面常驻垫底——无封面态此前是「♪」字符、加载中透 primary 12% 底；现统一黑胶母题封面。
                xyz.cncnlp.soundwave.DefaultLandscapeCover(Modifier.fillMaxSize())
            }
            if (hasArtwork && !fullBleedCoverPage) {
                artworkContent(Modifier.fillMaxSize())
            }
            if (!fullBleedCoverPage) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(0.24f).align(Alignment.BottomCenter).background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                coverLower.copy(alpha = 0.18f),
                                playerBackground.copy(alpha = 0.42f)
                            )
                        )
                    )
                )
            }
            if (resolving) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        VinylDiscSpinner(36.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(status.ifEmpty { "缓冲中" }, color = primaryColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
