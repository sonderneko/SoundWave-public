package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.Song

/** Pure footer presentation. Playback, menu, like, seek, and volume state remain owned by FullPlayer. */
@Composable
internal fun FullPlayerFooterRenderer(
    song: Song,
    liked: Boolean,
    white: Color,
    soft: Color,
    menuIconWhite: Boolean,
    hiddenVolumeShift: Dp,
    onLike: () -> Unit,
    onMore: () -> Unit,
    controls: @Composable () -> Unit
) {
    val artistContext = LocalContext.current
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    // 原有 -8dp 基准统一上移 6dp，歌词页与播放队列的进度/控制区同步。
    Column(Modifier.offset(y = (-14).dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 8.dp)
                .offset(y = hiddenVolumeShift),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(song.name, color = white, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee())
                Spacer(Modifier.height(4.dp))
                Text(song.singer.ifEmpty { "未知歌手" }, color = soft, fontSize = 15.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable { xyz.cncnlp.soundwave.TrackNavigation.openArtist(artistContext, song) }.basicMarquee())
            }
            Row(Modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if (liked) "取消喜欢：${song.name}" else "喜欢：${song.name}",
                    tint = white,
                    modifier = Modifier.size(28.dp).pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_ICON).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        if (!liked) xyz.cncnlp.soundwave.ui.common.AppHaptics.confirm(pressHapticView) else xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView)
                        onLike()
                    }.padding(3.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.MoreHoriz,
                    "更多：${song.name}",
                    tint = if (menuIconWhite) Color.White else white,
                    modifier = Modifier.size(28.dp).clickable { onMore() }.padding(2.dp)
                )
            }

        }
        Spacer(Modifier.height(6.dp))
        controls()
    }
}
