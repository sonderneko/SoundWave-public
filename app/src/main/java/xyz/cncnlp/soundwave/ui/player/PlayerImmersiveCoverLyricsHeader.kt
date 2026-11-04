package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP

/** Pure immersive-cover lyrics header; page, color, and playback state stay parent-owned. */
@Composable
internal fun PlayerImmersiveCoverLyricsHeader(
    songTitle: String,
    artistName: String,
    primaryColor: Color,
    secondaryColor: Color,
    surfaceColor: Color?,
    playing: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onArtistClick: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = IMMERSIVE_LYRICS_HEADER_ROW_HEIGHT_DP.dp)
            .padding(horizontal = 16.dp)
            .then(
                if (surfaceColor != null) Modifier.background(surfaceColor, RoundedCornerShape(18.dp))
                else Modifier
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(songTitle, color = primaryColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee())
            Text(artistName, color = secondaryColor, fontSize = 15.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().clickable(onClick = onArtistClick).basicMarquee())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerRoundedIconButton(
                onClick = onToggle,
                imageVector = if (playing) DesignChromeGlyphs.PauseBold else DesignChromeGlyphs.PlaySolid,
                contentDescription = if (playing) "暂停" else "播放",
                tint = primaryColor,
                buttonSize = 40.dp,
                iconSize = 23.dp
            )
            Spacer(Modifier.width(4.dp))
            PlayerLyricsNextButton(primaryColor, onNext)
        }
    }
}
