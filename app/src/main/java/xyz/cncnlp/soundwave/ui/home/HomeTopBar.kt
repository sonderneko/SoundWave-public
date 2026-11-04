package xyz.cncnlp.soundwave.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import xyz.cncnlp.soundwave.LayoutTokens
import xyz.cncnlp.soundwave.UnifiedTopGlyph
import xyz.cncnlp.soundwave.UserAvatarBadge

/** Stateless home header renderer; pager, platform and account behavior stay with the caller. */
@Composable
internal fun HomeTopBar(
    @Suppress("UNUSED_PARAMETER") primary: Color,
    currentSectionIndex: Int,
    sections: List<Pair<String, String>>,
    isDark: Boolean,
    loggedIn: Boolean,
    avatarPath: String,
    onPlatformClick: () -> Unit,
    onSectionClick: (Int) -> Unit,
    onLogin: () -> Unit,
    onAvatarLongClick: () -> Unit,
    showSections: Boolean = true,
) {
    val headerTint = MaterialTheme.colorScheme.surfaceVariant
    val selectedBackground = if (isDark) Color(0xFF3D4650.toInt()) else Color.White
    val selectedForeground = if (isDark) Color(0xFFF5F6F8.toInt()) else Color.Black
    Box(
        Modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to headerTint.copy(alpha = 0.72f),
                    0.40f to headerTint.copy(alpha = 0.32f),
                    1.0f to Color.Transparent,
                ),
            ),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(LayoutTokens.TopBarH.dp)
                .padding(start = 5.dp, end = (LayoutTokens.TopVisualEdge + 2).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clickable(onClick = onPlatformClick),
                contentAlignment = Alignment.Center,
            ) {
                UnifiedTopGlyph(MaterialTheme.colorScheme.onSurface)
            }
            if (showSections) {
                Row(
                    Modifier.weight(1f).padding(horizontal = 3.dp).offset(x = (-5).dp).height(42.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    sections.forEachIndexed { idx, pair ->
                        val selected = currentSectionIndex == idx
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 3.dp, vertical = 5.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) selectedBackground else Color.Transparent)
                                .clickable { onSectionClick(idx) },
                        ) {
                            Text(
                                pair.second,
                                color = if (selected) selectedForeground else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).height(42.dp).clickable { onSectionClick(0) }, contentAlignment = Alignment.Center) {
                    Text("首页", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                }
            }
            UserAvatarBadge(
                loggedIn = loggedIn,
                avatarPath = avatarPath,
                onClick = onLogin,
                onLongClick = onAvatarLongClick,
                sizeDp = LayoutTokens.AvatarDp,
            )
        }
    }
}
