package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Stateless lyrics-page title/actions row; playback, animation, and artwork loading stay parent-owned. */
@Composable
internal fun PlayerLyricsPageHeader(
    songTitle: String,
    artistName: String,
    coverScale: Float,
    immersiveMode: Boolean,
    playing: Boolean,
    liked: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    moreIconTint: Color,
    artworkContent: @Composable (Modifier) -> Unit,
    onCoverClick: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onMore: () -> Unit,
    onArtistClick: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 28.dp)
            // 全屏歌词标题维持顶部布局；隐藏音量空间由底部标题/进度/传输控件填入。
            .offset(y = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(73.dp)
                .offset(x = (-4).dp)
                .graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                .shadow(11.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.20f))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onCoverClick),
            contentAlignment = Alignment.Center
        ) {
            artworkContent(Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(songTitle, color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee())
            Text(artistName, color = secondaryColor, fontSize = 14.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable(onClick = onArtistClick).basicMarquee())
        }
        Row(Modifier.padding(end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            if (immersiveMode) {
                PlayerRoundedIconButton(
                    onClick = onToggle,
                    imageVector = if (playing) DesignChromeGlyphs.PauseBold else DesignChromeGlyphs.PlaySolid,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = primaryColor,
                    buttonSize = 40.dp,
                    iconSize = 23.dp
                )
                Spacer(Modifier.width(6.dp))
                PlayerRoundedIconButton(
                    onClick = onNext,
                    imageVector = DesignChromeGlyphs.Next,
                    contentDescription = "下一首",
                    tint = primaryColor,
                    buttonSize = 40.dp,
                    iconSize = 24.dp
                )
            } else {
                // 普通歌词页仍保留喜欢和更多；沉浸歌词页改为可操作的播放/下一首。
                Icon(
                    if (liked) xyz.cncnlp.soundwave.ui.common.DesignGlyphs.HeartFilled else xyz.cncnlp.soundwave.ui.common.DesignGlyphs.HeartOutline,
                    if (liked) "取消喜欢：$songTitle" else "喜欢：$songTitle",
                    tint = primaryColor,
                    modifier = Modifier.size(28.dp).clickable(onClick = onLike).padding(3.dp)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    xyz.cncnlp.soundwave.ui.common.DesignGlyphs.MoreDots,
                    "更多：$songTitle",
                    tint = moreIconTint,
                    modifier = Modifier.size(29.dp).clickable(onClick = onMore).padding(2.dp)
                )
            }
        }
    }
}
