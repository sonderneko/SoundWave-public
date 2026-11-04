package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs

@Composable
internal fun DetailResumeBar(
    song: Song,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = "继续上次播放", onClick = onResume),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(DesignGlyphs.PlayTriangle, null, tint = foreground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("继续播放：", color = foreground, fontSize = 14.sp, maxLines = 1)
            Text(
                DetailResumePolicy.label(song), color = foreground.copy(alpha = 0.6f),
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, "关闭继续播放", tint = foreground.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}
