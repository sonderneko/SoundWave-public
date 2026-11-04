package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One persistent cover slot, driven by one interruptible transition in the route owner.
 * The weighted space below absorbs its changing height, keeping metadata and controls anchored.
 * Image loading and the hero stay mounted across both directions; no playback state is recreated.
 */
@Composable
internal fun ApplePlayerCoverMorph(
    progress: Float,
    normalSize: Dp,
    immersiveHeight: Dp,
    artworkHeight: Dp,
    surfaceTopPx: Float,
    coverScale: Float,
    hasArtwork: Boolean,
    coverLower: Color,
    playerBackground: Color,
    primaryColor: Color,
    resolving: Boolean,
    status: String,
    onClick: () -> Unit,
    /** r674：渐隐中段衔接色，与常驻 Hero 相同，两层叠放时末端完全一致。 */
    bridgeColor: () -> Color = { Color.Unspecified },
    artworkContent: @Composable (Modifier) -> Unit
) {
    val p = progress.coerceIn(0f, 1f)
    val height = normalSize + (immersiveHeight - normalSize) * p
    Spacer(Modifier.height(52.dp + 16.dp * p).fillMaxWidth()
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick))
    var slotTopPx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).zIndex(-1f)
        .onGloballyPositioned { slotTopPx = it.positionInRoot().y - surfaceTopPx }
        .semantics { contentDescription = "封面，点按切换封面呈现" }
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)) {
        val width = normalSize + (maxWidth - normalSize) * p
        val imageHeight = normalSize + (artworkHeight - normalSize) * p
        val shape = RoundedCornerShape(20.dp * (1f - p))
        // One artwork node moves from its actual card bounds to the top-edge Hero bounds.
        // No second crop is crossfaded: interruption and reversal keep the same image geometry.
        Box(Modifier.align(Alignment.TopCenter)
            .offset { IntOffset(0, (-slotTopPx * p).roundToInt()) }
            .wrapContentSize(Alignment.TopCenter, unbounded = true).requiredSize(width, imageHeight)
            .semantics { contentDescription = "Apple连续封面图像" }
            .graphicsLayer {
                scaleX = coverScale + (1f - coverScale) * p
                scaleY = scaleX
            }
            .shadow(16.dp * (1f - p), shape,
                ambientColor = Color.Black.copy(alpha = .17f), spotColor = Color.Black.copy(alpha = .21f))
            .clip(shape).playerHeroFade(p, bridge = bridgeColor)
            .clickable(indication = null, interactionSource = null, onClick = onClick)) {
            artworkContent(Modifier.fillMaxSize())
        }
    }
}
