package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** 播放页背景装饰（自 FullPlayer 迁出）：低对比声波圆环，不占布局、不接收触摸。 */
@Composable
internal fun PlayerSoundRingsDecor(modifier: Modifier = Modifier) {
    Box(modifier.drawBehind {
        val center = Offset(size.width * 0.5f, size.height * 0.36f)
        val unit = size.minDimension
        for (i in 0..4) {
            drawCircle(
                color = Color.White.copy(alpha = 0.022f - i * 0.002f),
                radius = unit * (0.19f + i * 0.047f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    })
}
