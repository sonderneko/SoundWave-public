package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.motion.SharedCoverTransition
import xyz.cncnlp.soundwave.ui.motion.sharedCoverSource

import androidx.compose.ui.unit.em
import androidx.compose.foundation.border
import xyz.cncnlp.soundwave.ui.common.cssShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage


/**
 * 搜索歌曲行副标题：歌手 [· 平台] · 专辑。
 * 跨平台混排（showPlatformName）时在歌手右侧带平台名（用户 2026-11-04 裁定：与歌手同行同规格、
 * " · " 分隔，对齐同页歌单/专辑行惯例）；单平台时与旧串逐字一致。歌手为空时平台名居首顶替（旧行为）。
 */
internal fun searchSongSubtitle(singer: String, source: String, album: String, showPlatformName: Boolean): String =
    listOf(singer, if (showPlatformName || singer.isBlank()) platformName(source) else "", album)
        .filter { it.isNotBlank() }.joinToString(" · ")

/** BestMatch 大卡副标题：歌手 [· 平台] · 时长，规则同 [searchSongSubtitle]。 */
internal fun searchBestMatchSubtitle(singer: String, source: String, durationLabel: String, showPlatformName: Boolean): String =
    listOf(singer, if (showPlatformName || singer.isBlank()) platformName(source) else "", durationLabel)
        .filter { it.isNotBlank() }.joinToString(" · ")

@Composable
internal fun SearchAllSections(
    query: String,
    songs: List<Song>,
    playlists: List<Playlist>,
    albums: List<Song>,
    artists: List<Song>,
    songState: LoadableContent<List<Song>>,
    playlistState: LoadableContent<List<Playlist>>,
    albumState: LoadableContent<List<Song>>,
    artistState: LoadableContent<List<Song>>,
    onShowAll: (String) -> Unit,
    onPlay: (Song) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenAlbum: (Song) -> Unit,
    onOpenArtist: (Song) -> Unit,
    showPlatformName: Boolean = false
) {
    val palette = MaterialTheme.colorScheme
    val onSurface = palette.onBackground
    val muted = palette.onSurfaceVariant
    val distinctAlbums = albums.distinctBy { "${it.source}|${it.albumId.ifBlank { it.album }}" }
        .filter { it.album.isNotBlank() || it.albumId.isNotBlank() }
    val distinctArtists = artists.distinctBy { "${it.source}|${it.name}" }
        .filter { it.name.isNotBlank() }

    // r658b #15：综合结果页按原型 pResults 重做——Best match 卡 → 歌曲（3 行）→ 歌单 / 专辑 132dp 横滑 → 歌手 76dp 圆形头像。
    Column(Modifier.fillMaxWidth()) {
        songs.firstOrNull()?.let { best ->
            Spacer(Modifier.height(16.dp))
            SearchBestMatchCard(best, query, onClick = { onPlay(best) }, onPlay = { onPlay(best) }, showPlatformName = showPlatformName)
        }

        ResultSectionHeader("歌曲", songs.size, hideCount = songState is LoadableContent.Loading && songs.isEmpty(), onClick = { onShowAll("song") })
        SearchSectionStatus(songState, songs.isNotEmpty(), "暂无歌曲结果", SearchSkeletonKind.SongRows)
        songs.take(3).forEach { song ->
            xyz.cncnlp.soundwave.ui.search.SearchResultRow(
                cover = song.cover,
                title = song.name.ifBlank { "未知歌曲" },
                titleText = highlightQuery(song.name.ifBlank { "未知歌曲" }, query),
                subtitle = searchSongSubtitle(song.singer, song.source, song.album, showPlatformName),
                placeholder = Icons.Outlined.MusicNote,
                onClick = { onPlay(song) },
                trailing = {
                    val d = searchDurationLabel(song.duration.toLong())
                    if (d.isNotBlank()) Text(d, color = muted, fontSize = 11.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            )
        }

        ResultSectionHeader("歌单", playlists.size, hideCount = playlistState is LoadableContent.Loading && playlists.isEmpty(), onClick = { onShowAll("pl") })
        SearchSectionStatus(playlistState, playlists.isNotEmpty(), "暂无歌单结果", SearchSkeletonKind.RailCards)
        if (playlists.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp)) {
            items(playlists.take(8), key = { "playlist:${it.source}:${it.id}" }) { playlist ->
                SearchRailItem(
                    cover = playlist.cover, title = playlist.name,
                    sub = listOf(platformName(playlist.source), if (playlist.count > 0) "${playlist.count} 首" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    coverModifier = Modifier.sharedCoverSource(playlist.id)
                ) { SharedCoverTransition.arm(playlist.id); onOpenPlaylist(playlist) }
            }
        }

        ResultSectionHeader("专辑", distinctAlbums.size, hideCount = albumState is LoadableContent.Loading && distinctAlbums.isEmpty(), onClick = { onShowAll("album") })
        SearchSectionStatus(albumState, distinctAlbums.isNotEmpty(), "暂无专辑结果", SearchSkeletonKind.RailCards)
        if (distinctAlbums.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp)) {
            items(distinctAlbums.take(8), key = { "album:${it.source}:${it.albumId.ifBlank { it.album }}" }) { album ->
                SearchRailItem(
                    cover = album.cover, title = album.album.ifBlank { album.name },
                    sub = album.singer.ifBlank { platformName(album.source) }
                ) { onOpenAlbum(album) }
            }
        }

        ResultSectionHeader("歌手", distinctArtists.size, hideCount = artistState is LoadableContent.Loading && distinctArtists.isEmpty(), onClick = { onShowAll("artist") })
        SearchSectionStatus(artistState, distinctArtists.isNotEmpty(), "暂无歌手结果", SearchSkeletonKind.ArtistCircles)
        if (distinctArtists.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp)) {
            items(distinctArtists.take(10), key = { "artist:${it.source}:${it.name}" }) { artist ->
                Column(Modifier.width(84.dp).clickable { onOpenArtist(artist) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    CoverImage(
                        artist.cover, "歌手头像",
                        Modifier.size(76.dp).cssShadow(CircleShape, Color(0xFF131915).copy(alpha = if (palette.background.luminance() < 0.5f) 0.45f else 0.18f), 24.dp, 10.dp).clip(CircleShape)
                    )
                    Text(
                        artist.name, color = onSurface, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 16.sp,
                        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 9.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 原型 Best match 卡：.card 128dp / r28、右上珊瑚 20% 径向光；88dp r18 封面 + 眉题 + 24sp 衬线标题 + 12sp 歌手 · 时长；右侧 46dp 珊瑚播放钮。 */
@Composable
private fun SearchBestMatchCard(song: Song, query: String, onClick: () -> Unit, onPlay: () -> Unit, showPlatformName: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val dark = colors.background.luminance() < 0.5f
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(128.dp)
            // r673（用户 10-22：搜索结果顶部歌曲大卡去掉边缘线条）：阴影只画在卡片外侧（excludeInterior），并内收 6dp（spread -6），
            // 磨砂半透明底色下不再透出阴影、卡片上沿也不再贴一条 3dp 深色带；卡片本身无描边。
            .cssShadow(shape, Color(0xFF131915).copy(alpha = if (dark) 0.35f else 0.07f), 26.dp, 10.dp, spread = (-6).dp, excludeInterior = true)
            .clip(shape)
            .background(xyz.cncnlp.soundwave.ui.common.frostedCardFill(colors.surface))
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width, 0f), radius = size.width * 0.75f
                ))
            }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            song.cover, "歌曲封面",
            Modifier.size(88.dp).cssShadow(RoundedCornerShape(18.dp), Color(0xFF131915).copy(alpha = if (dark) 0.5f else 0.22f), 30.dp, 14.dp).clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("BEST MATCH · 歌曲", color = accent, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1)
            Text(
                song.name.ifBlank { query }, color = colors.onBackground, fontSize = 24.sp, lineHeight = 30.sp,
                fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.04).em,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                searchBestMatchSubtitle(song.singer, song.source, searchDurationLabel(song.duration.toLong()), showPlatformName),
                color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(46.dp).accentGlow(alpha = 0.45f, blur = 14.dp, offsetY = 6.dp).clip(CircleShape).background(accent)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.PlaySolid, "播放：${song.name}", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** 原型 .rail .it：132dp r18 封面（shadow-cover）+ 700 14/18 标题 + 11.5sp 灰副标题。 */
@Composable
private fun SearchRailItem(
    cover: String,
    title: String,
    sub: String,
    coverModifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        CoverImage(
            cover, title,
            Modifier.size(132.dp).then(coverModifier).cssShadow(shape, Color(0xFF131915).copy(alpha = if (dark) 0.5f else 0.22f), 30.dp, 14.dp).clip(shape)
        )
        Text(title, color = colors.onBackground, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        if (sub.isNotBlank()) Text(sub, color = colors.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 原型 .sec：700 20sp 标题 + 右侧 DM Mono 11sp「NN · 查看全部 ›」。ACCEPT5：加载中且尚无结果时不显计数（原恒显「00」是假数字）。 */
@Composable
private fun ResultSectionHeader(title: String, count: Int, hideCount: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 12.dp).height(30.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = colors.onBackground, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
            letterSpacing = (-0.03).em, maxLines = 1, modifier = Modifier.weight(1f))
        Text(if (hideCount) "查看全部" else "${count.toString().padStart(2, '0')} · 查看全部", color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
            fontWeight = FontWeight.Medium, letterSpacing = 0.04.em, maxLines = 1)
        Icon(xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.Chevron, null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp).size(16.dp))
    }
}

/** 标题中与搜索词相同的片段标珊瑚色（原型 hl()）。 */
internal fun highlightQuery(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val q = query.trim()
    val i = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    return androidx.compose.ui.text.buildAnnotatedString {
        if (i < 0) { append(text); return@buildAnnotatedString }
        append(text.substring(0, i))
        pushStyle(androidx.compose.ui.text.SpanStyle(color = SoundwaveDesignPalette.Accent))
        append(text.substring(i, i + q.length))
        pop()
        append(text.substring(i + q.length))
    }
}

/** 时长标签 m:ss（毫秒；≤0 时为空）。 */
internal fun searchDurationLabel(ms: Long): String {
    if (ms <= 0L) return ""
    val total = ms / 1000L
    return "%d:%02d".format(total / 60L, total % 60L)
}

/** 搜索分区骨架形态（验收 B 波）：按各分区真实结果形态出骨架，与通用 SkeletonKit 同体系。 */
private enum class SearchSkeletonKind { SongRows, RailCards, ArtistCircles }

@Composable
private fun SearchSectionStatus(state: LoadableContent<*>, hasResults: Boolean, emptyLabel: String, skeleton: SearchSkeletonKind = SearchSkeletonKind.SongRows) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (hasResults) {
        if (state is LoadableContent.Stale<*>) Text("已显示缓存 · ${state.error}", color = muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        return
    }
    when (state) {
        is LoadableContent.Loading -> xyz.cncnlp.soundwave.ui.common.SkeletonGroup(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            when (skeleton) {
                SearchSkeletonKind.SongRows -> Column {
                    repeat(2) {
                        xyz.cncnlp.soundwave.ui.common.SkeletonRow(
                            coverSize = 52.dp,
                            coverShape = RoundedCornerShape(12.dp),
                            titleFraction = 0.62f,
                            subtitleFraction = 0.38f
                        )
                        if (it == 0) Spacer(Modifier.height(16.dp))
                    }
                }
                SearchSkeletonKind.RailCards -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Column(Modifier.width(132.dp)) {
                            xyz.cncnlp.soundwave.ui.common.SkeletonCoverBlock(Modifier.size(132.dp), RoundedCornerShape(18.dp))
                            Spacer(Modifier.height(8.dp))
                            xyz.cncnlp.soundwave.ui.common.SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(11.dp), RoundedCornerShape(4.dp))
                        }
                    }
                }
                SearchSkeletonKind.ArtistCircles -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(4) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            xyz.cncnlp.soundwave.ui.common.SkeletonCoverBlock(Modifier.size(64.dp), androidx.compose.foundation.shape.CircleShape)
                            Spacer(Modifier.height(8.dp))
                            xyz.cncnlp.soundwave.ui.common.SkeletonBlock(Modifier.width(44.dp).height(10.dp), RoundedCornerShape(4.dp))
                        }
                    }
                }
            }
        }
        is LoadableContent.Unavailable -> Text(state.error, color = muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
        is LoadableContent.Stale<*> -> Text(state.error, color = muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
        is LoadableContent.Empty -> Text(emptyLabel, color = muted.copy(alpha = 0.72f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
        is LoadableContent.Available<*> -> Text(emptyLabel, color = muted.copy(alpha = 0.72f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable
private fun CoverImage(url: String, description: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        // 统一封面占位垫底：未加载 / 失败 / 无封面同款，图片成功后盖住（语义描述只挂一处）。
        // ACCEPT5 收编：此前按类型传 glyph（歌单/专辑/歌手）走字形圆盘分支、不是黑胶母题；现统一默认母题（歌手在圆形裁切内即圆形黑胶）。
        DefaultCoverPlaceholder(
            Modifier.fillMaxSize(),
            contentDescription = if (url.isBlank()) description else null
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = CacheManager.coverModel(androidx.compose.ui.platform.LocalContext.current, url) ?: url,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}
