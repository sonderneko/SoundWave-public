package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.ThemeTokens

/**
 * Backdrop overlay layers drawn above the blurred cover artwork (or the
 * empty-cover gradient) and below the foreground content: dark masks,
 * palette gradients, the Apple white-frost veil and the status-bar scrim.
 * Extracted from FullPlayer; layer order and values are unchanged.
 */
@Composable
internal fun BoxScope.FullPlayerBackdropLayers(
    coverLayers: Boolean,
    emptyCoverLayers: Boolean,
    immersiveCoverLayout: Boolean,
    presentationMode: String,
    appleWhiteFrost: Boolean,
    fullBleedArtwork: Boolean,
    playerMask: Float,
    coverTop: Color,
    coverLower: Color
) {
    if (coverLayers) {
        if ((!immersiveCoverLayout || presentationMode == "apple") && !appleWhiteFrost) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (fullBleedArtwork && presentationMode != "apple") 0.18f else playerMask)))
            if (fullBleedArtwork && presentationMode != "apple") {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.16f),
                                0.30f to coverTop.copy(alpha = 0.16f),
                                0.68f to coverLower.copy(alpha = 0.34f),
                                1f to ThemeTokens.PlayerBackground
                            )
                        )
                    )
                )
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color.Transparent, Color(0xCC101014.toInt())))))
            }
        }
    } else if (emptyCoverLayers) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(ThemeTokens.StageDeep, ThemeTokens.StageDeepest, ThemeTokens.StageDeepest, ThemeTokens.PlayerBackground))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.03f), Color.Transparent))
            )
        )
    }
    if (appleWhiteFrost) {
        // Background only: no overlay over the foreground hero/cover/lyrics.
        // Neutral white veil preserves artwork hue without the former dark masks.
        Box(Modifier.matchParentSize().background(Color.White.copy(alpha = if (android.os.Build.VERSION.SDK_INT >= 31) 0.78f else 0.92f)))
    }
    if (fullBleedArtwork && presentationMode != "apple") {
        // 状态栏同层的渐暗 scrim 向全屏封面背景淡出，不形成封面卡片边缘或白线。
        Box(
            Modifier.fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent)))
        )
    }
}
