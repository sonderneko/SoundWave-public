package xyz.cncnlp.soundwave.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignFonts

/**
 * r657（v3 #2 / v4 #3）：封面左上角「耳机 + 播放量」毛玻璃胶囊（22dp 高、DM Mono 10sp、白字）。
 * 首页甄选歌单与歌单广场宽卡 / 宫格共用；播放量为 0 时不画。
 */
@Composable
internal fun CoverPlayCountPill(playCount: Long, modifier: Modifier = Modifier) {
    val plays = formatPlazaPlayCount(playCount)
    if (plays.isEmpty()) return
    val shape = RoundedCornerShape(99.dp)
    Row(
        modifier.height(22.dp).clip(shape).background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.20f), shape).padding(horizontal = 7.dp)
            .semantics { contentDescription = "播放量 $plays" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(DesignLineGlyphs.Headphones, null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(plays, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
