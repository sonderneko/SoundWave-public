package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.MotionPolicy

/** Three rounded vertical bars centered on artwork; pause/reduced motion never create a ticker. */
@Composable
internal fun PlayerSongEqualizer(active: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    val animate = active && !reduceMotion
    val phase = if (animate) {
        rememberInfiniteTransition(label = "songCoverEqualizer").animateFloat(
            0f, 1f, infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse), label = "bars"
        )
    } else null
    Canvas(modifier.size(24.dp).semantics { contentDescription = if (active) "正在播放" else "已暂停" }) {
        val p = phase?.value ?: 0.5f
        val heights = listOf(0.35f + p * 0.5f, 1f - p * 0.45f, 0.7f - p * 0.4f)
        val width = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val start = (size.width - width * 3 - gap * 2) / 2
        heights.forEachIndexed { index, fraction ->
            val h = size.height * fraction
            drawRoundRect(Color.White, Offset(start + index * (width + gap), (size.height - h) / 2), Size(width, h), CornerRadius(width / 2))
        }
    }
}
