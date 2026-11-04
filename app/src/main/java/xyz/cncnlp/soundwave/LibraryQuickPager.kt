package xyz.cncnlp.soundwave

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryQuickPager(
    liked: Playlist?,
    local: Playlist?,
    favs: List<Song>,
    onOpen: (Playlist) -> Unit,
    onPlay: (Song, List<Song>, String) -> Unit,
    onLikedMenu: (Playlist) -> Unit,
    onLocalMenu: (Playlist) -> Unit = {}
) {
    val context = LocalContext.current
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { page ->
            val playlist = if (page == 0) liked ?: Playlist("liked", "喜欢", local = true, songs = favs.toMutableList())
            else local ?: Playlist("local", "本地歌单", local = true)
            val cover = libraryCoverUrl(
                playlist.cover,
                (if (page == 0) favs else playlist.songs).map { it.cover }
            ).orEmpty()
            var coverModel by remember(cover) {
                mutableStateOf<Any?>(if (libraryCoverHasImage(cover)) CacheManager.coverModel(context, cover) ?: cover else null)
            }
            LaunchedEffect(cover) {
                if (libraryCoverHasImage(cover)) {
                    val cached = withContext(Dispatchers.IO) { CacheManager.ensureCover(context, cover) }
                    coverModel = CacheManager.coverModel(context, cached) ?: cached
                }
            }
            val openMenu = if (page == 0) onLikedMenu else onLocalMenu
            val title = if (page == 0) "我的喜欢" else "本地歌单"
            val count = if (page == 0) favs.size else playlist.songs.size
            val songs = if (page == 0) favs else playlist.songs
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.62f), RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = { onOpen(playlist) },
                        onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); openMenu(playlist) }
                    )
                    .padding(9.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    LibraryCoverArtwork(
                        cover = cover,
                        fallbackKind = if (page == 0) LibraryDefaultCoverKind.LIKED else LibraryDefaultCoverKind.LOCAL_PLAYLIST,
                        contentDescription = "${title}封面",
                        modifier = Modifier.fillMaxSize(),
                        imageModel = coverModel
                    )
                    if (page == 0) {
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(36.dp)
                                .clickable(enabled = songs.isNotEmpty()) {
                                    songs.firstOrNull()?.let { onPlay(it, songs, playlist.id) }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                xyz.cncnlp.soundwave.ui.common.DesignGlyphs.PlayTriangle,
                                contentDescription = "播放$title",
                                tint = Color.White,
                                modifier = Modifier.size(23.dp)
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 5.dp)
                        .padding(horizontal = 5.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count 首",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
