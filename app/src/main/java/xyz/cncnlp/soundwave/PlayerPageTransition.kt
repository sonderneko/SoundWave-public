package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** r674：标准 / Apple 播放器 封面页 ↔ 歌词页 过渡参数（SW-07-02 / SW-07-05；减少动画时由调用方直接切换，SW-08-03）。 */
internal const val PLAYER_PAGE_EXIT_MS = 140
internal const val PLAYER_PAGE_ENTER_MS = 260
internal const val PLAYER_PAGE_SHIFT_DP = 14f
/** 拖动条占位高度：上 8dp + 条高 5dp + 下 4dp，与旧的列内拖动条完全同高，页面内容不位移。 */
internal const val PLAYER_DRAG_HANDLE_SLOT_DP = 17f

/**
 * 页面纵向位移：旧页（[incoming]=false）沿离开方向移出，新页从反方向归位。
 * [dir] = +1 进入歌词（旧页上移、新页自下浮入），-1 返回封面（相反）。[alpha] 为当前页透明度 0..1。
 */
internal fun playerPageShiftPx(alpha: Float, incoming: Boolean, dir: Float, shiftPx: Float): Float {
    val t = 1f - alpha.coerceIn(0f, 1f)
    return if (incoming) dir * t * shiftPx else -dir * t * shiftPx
}

/** 顶部拖动条：常驻层，不随封面页 / 歌词页过渡淡出淡入（r674 Apple 顶部闪烁修复）。非 inline，保持 FullPlayer dex 方法体积。 */
@Composable
internal fun PlayerDragHandle(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(40.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.35f))
                .clickable(onClick = onClick)
        )
    }
}
