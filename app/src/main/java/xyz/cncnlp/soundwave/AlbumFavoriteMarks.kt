package xyz.cncnlp.soundwave

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 显式收藏专辑的标记。
 *
 * 专辑与歌单收藏共用 `fav_<source>_<remoteId>` 收藏条目（togglePlaylistFavorite），数据模型里没有类型字段；
 * 音乐库「收藏的歌单与专辑」只能把用户在专辑详情里主动收藏的条目当作专辑，绝不能再从收藏歌曲或
 * 导入歌单曲目的 album 字段推导专辑。这里：
 *  - 打开专辑详情时登记专辑身份（仅内存，进程内有效）；
 *  - 收藏/取消收藏时，若该条目是已登记专辑，把收藏 id 写入/移出当前资料的 `album_favorite_ids`。
 */
internal object AlbumFavoriteMarks {
    private const val KEY = "album_favorite_ids"
    private val openedAlbums: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun albumKey(source: String, id: String) = "${source.ifBlank { "wy" }}:$id"

    /** 专辑详情入口调用：记住该 (平台, 专辑 id) 是专辑而不是歌单。 */
    fun noteAlbum(source: String, id: String) {
        if (id.isNotBlank()) openedAlbums += albumKey(source, id)
    }

    fun isKnownAlbum(pl: Playlist, fallbackSource: String): Boolean {
        val source = pl.source.ifBlank { fallbackSource }
        val remoteId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }
        return albumKey(source, remoteId) in openedAlbums || albumKey(source, pl.id) in openedAlbums
    }

    fun marks(context: Context): Set<String> = runCatching {
        ContextPreferences.open(context, "soundwave", 0).getStringSet(KEY, emptySet()).orEmpty().toSet()
    }.getOrDefault(emptySet())

    fun setMarked(context: Context, favoriteId: String, marked: Boolean) {
        runCatching {
            val prefs = ContextPreferences.open(context, "soundwave", 0)
            val next = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
            if (marked) next += favoriteId else next -= favoriteId
            prefs.edit().putStringSet(KEY, next).apply()
        }
    }
}
