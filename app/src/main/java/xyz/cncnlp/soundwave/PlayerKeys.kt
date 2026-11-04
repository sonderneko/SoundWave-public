package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PlayerMiddleAction(label: String, kind: String, onClick: () -> Unit, tint: Color) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.20f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WhiteGlyph(kind, 18.dp, tint)
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun PlayerFiveKeys(
    playing: Boolean,
    playMode: PlayMode,
    onMode: () -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSource: () -> Unit,
    onQueue: () -> Unit = {},
    light: Boolean,
    liked: Boolean = false,
    onLike: () -> Unit = {},
    onDownload: () -> Unit = {},
    onMore: () -> Unit = {}
) {
    val tint = MaterialTheme.colorScheme.primary
    val secondaryTint = MaterialTheme.colorScheme.secondary
    val iconTint = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    val buttonBg = tint.copy(alpha = 0.12f)
    // ❤喜欢  ⏮上一首  ▶播放/暂停  ⏭下一首  🔀模式  ⬇下载  ⋯更多
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // 喜欢
        Box(Modifier.size(40.dp).clickable { onLike() }, contentAlignment = Alignment.Center) {
            WhiteGlyph(if (liked) "heart_filled" else "heart", 26.dp, tint)
        }
        // 上一首
        Box(Modifier.size(40.dp).clickable { onPrev() }, contentAlignment = Alignment.Center) {
            WhiteGlyph("prev", 32.dp, tint)
        }
        // 播放/暂停（主键更大）
        Box(Modifier.size(56.dp).clickable { onToggle() }, contentAlignment = Alignment.Center) {
            WhiteGlyph(if (playing) "pause" else "play", 48.dp, tint)
        }
        // 下一首
        Box(Modifier.size(40.dp).clickable { onNext() }, contentAlignment = Alignment.Center) {
            WhiteGlyph("next", 32.dp, tint)
        }
        // 播放模式
        Box(Modifier.size(40.dp).clickable { onMode() }, contentAlignment = Alignment.Center) {
            WhiteGlyph(
                when (playMode) {
                    PlayMode.HEART -> "sparkles_filled"
                    PlayMode.LOOP_ONE -> "one"
                    PlayMode.SHUFFLE -> "shuffle"
                    else -> "loop"
                },
                26.dp, tint
            )
        }
        // 下载
        Box(Modifier.size(40.dp).clickable { onDownload() }, contentAlignment = Alignment.Center) {
            WhiteGlyph("download", 26.dp, tint)
        }
        // 播放队列
        Box(Modifier.size(40.dp).clickable { onQueue() }, contentAlignment = Alignment.Center) {
            WhiteGlyph("queue", 26.dp, tint)
        }
        // 更多（来源/三点）
        Box(Modifier.size(40.dp).clickable { onMore() }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(26.dp)) {
                val r = 3.dp.toPx()
                val y = size.height / 2
                val xs = listOf(size.width * 0.2f, size.width * 0.5f, size.width * 0.8f)
                xs.forEach { x -> drawCircle(tint, r, Offset(x, y)) }
            }
        }
    }
}
