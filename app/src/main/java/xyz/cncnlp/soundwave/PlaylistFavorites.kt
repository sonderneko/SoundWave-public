package xyz.cncnlp.soundwave

import android.content.Context
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

object PlaylistFavorites {
    private var source = "wy"
    private var playlists: List<Playlist> = emptyList()
    private var onChanged: ((MutableList<Playlist>) -> Unit)? = null
    private var context: Context? = null

    fun bind(source: String, playlists: List<Playlist>, onChanged: (MutableList<Playlist>) -> Unit, context: Context) {
        this.source = source
        this.playlists = playlists
        this.onChanged = onChanged
        this.context = context
    }

    fun isFavorited(pl: Playlist): Boolean = playlistFavoriteId(pl, source) in playlists.map { it.id }

    fun toggle(pl: Playlist) {
        togglePlaylistFavorite(pl, source, playlists, { updated -> onChanged?.invoke(updated) }, context ?: return)
    }
}

internal fun playlistFavoriteId(pl: Playlist, fallbackSource: String): String {
    val source = pl.source.ifBlank { fallbackSource }
    val remoteId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }
    return "fav_${source}_${remoteId}"
}

internal fun playlistIsFavorited(pl: Playlist, fallbackSource: String, playlists: List<Playlist>): Boolean =
    playlists.any { it.id == playlistFavoriteId(pl, fallbackSource) }

/**
 * 收藏条目回源用的远程 id：优先条目自带的 remoteId；旧条目缺失时从
 * `fav_<source>_<remoteId>` 解析（平台 id 不含下划线，第一段下划线后即远程 id）。
 * 非收藏条目返回空串。
 */
internal fun playlistFavoriteRemoteId(pl: Playlist): String {
    if (pl.remoteId.isNotBlank()) return pl.remoteId
    if (!pl.id.startsWith("fav_")) return ""
    return pl.id.removePrefix("fav_").substringAfter('_', "")
}

/** 收藏条目且能解析出远程 id、来源是已知平台——音乐库打开时需要按书签语义回源水合。 */
internal fun Playlist.isRemoteFavoriteEntry(): Boolean =
    id.startsWith("fav_") && playlistFavoriteRemoteId(this).isNotBlank() &&
        source.ifEmpty { "wy" } in MusicSourceRegistry.platformIds

internal fun togglePlaylistFavorite(
    pl: Playlist,
    fallbackSource: String,
    playlists: List<Playlist>,
    onPlaylists: (MutableList<Playlist>) -> Unit,
    context: Context
) {
    val key = playlistFavoriteId(pl, fallbackSource)
    val updated = playlists.toMutableList()
    val index = updated.indexOfFirst { it.id == key }
    if (index >= 0) {
        updated.removeAt(index)
        AlbumFavoriteMarks.setMarked(context, key, false)
    } else {
        if (AlbumFavoriteMarks.isKnownAlbum(pl, fallbackSource)) AlbumFavoriteMarks.setMarked(context, key, true)
        val source = pl.source.ifBlank { fallbackSource }
        val remoteId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }
        updated += pl.copy(
            id = key,
            remoteId = remoteId,
            // 收藏的是远程歌单/专辑（书签 + 快照），local 必须沿用来源对象的真值：
            // 旧版硬编码 true 让详情页把远程收藏误标「本地」、按本地语义关停下载/心动，
            // 且掩盖了它应当按 remoteId 回源的身份。在线导入歌单同为 local=false。
            local = pl.local,
            source = source,
            favorited = true,
            syncCreatedAt = System.currentTimeMillis()
        )
    }
    onPlaylists(updated)
}
