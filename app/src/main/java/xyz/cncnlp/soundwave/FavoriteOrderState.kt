package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 收藏自有顺序的 AppRoot 作用域状态持有者（2026-11-05 自 MainActivity 抽出，与 PlaybackController 同范式）：
 * [FavoriteOrderSync] 戳记为权威、prefs 只作兜底/镜像，与在线池 libOrder 同一镜像法但互不读写；
 * 歌单组/专辑组按 [AlbumFavoriteMarks] 分开各一份。无戳记时的回退排列 = 今天的画面：
 * 歌单组沿池序带相对序、专辑组沿 bundle 原序，升级不跳位。行为与数据格式与原地实现逐字一致。
 */
class FavoriteOrderState(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val getPlaylists: () -> List<Playlist>,
    private val getPoolOrder: () -> List<String>,
    private val onPlaylistsReordered: (List<Playlist>) -> Unit,
    private val persist: () -> Unit,
) {
    val playlistOrderState = mutableStateOf(
        prefs.getString("lib_fav_order_pl_v1", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    )
    val albumOrderState = mutableStateOf(
        prefs.getString("lib_fav_order_album_v1", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    )
    var playlistOrder: List<String>
        get() = playlistOrderState.value
        set(v) { playlistOrderState.value = v }
    var albumOrder: List<String>
        get() = albumOrderState.value
        set(v) { albumOrderState.value = v }

    /** 戳记键：任一歌单的收藏标记或 fav 戳对变化即触发 [syncFromStamps] 重算。 */
    val stampKey: List<String>
        get() = getPlaylists().map { "${it.id}:${it.favorited}:${it.favSyncOrderAt}:${it.favSyncOrder}" }

    fun groupOrder(albums: Boolean): List<String> {
        val marks = AlbumFavoriteMarks.marks(context)
        val indexed = getPlaylists().withIndex()
            .filter { (_, pl) -> pl.favorited && (pl.id in marks) == albums }
        val candidates = if (albums) {
            indexed.map { it.value }
        } else {
            val bandIndex = PlaylistOrderSync.displayOrder(getPlaylists(), getPoolOrder())
                .withIndex().associate { (i, id) -> id to i }
            indexed
                .sortedWith(compareBy<IndexedValue<Playlist>> { bandIndex[it.value.id] ?: Int.MAX_VALUE }.thenBy { it.index })
                .map { it.value }
        }
        return FavoriteOrderSync.displayOrder(candidates, if (albums) albumOrder else playlistOrder)
    }

    /** 按戳记重算两组顺序并镜像回 prefs（原 AppRoot LaunchedEffect 体）。 */
    fun syncFromStamps() {
        val restoredPl = groupOrder(false)
        if (restoredPl != playlistOrder) {
            playlistOrder = restoredPl
            prefs.edit().putString("lib_fav_order_pl_v1", restoredPl.joinToString(",")).apply()
        }
        val restoredAlbums = groupOrder(true)
        if (restoredAlbums != albumOrder) {
            albumOrder = restoredAlbums
            prefs.edit().putString("lib_fav_order_album_v1", restoredAlbums.joinToString(",")).apply()
        }
    }

    fun reorder(order: List<String>, albums: Boolean) {
        val marks = AlbumFavoriteMarks.marks(context)
        val memberIds = getPlaylists()
            .filter { it.favorited && (it.id in marks) == albums }
            .mapTo(hashSetOf()) { it.id }
        if (memberIds.isEmpty()) return
        val previousOrder = groupOrder(albums)
        val reorderedPlaylists = FavoriteOrderSync.withOrder(getPlaylists(), memberIds, order)
        val restoredOrder = FavoriteOrderSync.displayOrder(
            reorderedPlaylists.filter { it.id in memberIds }, emptyList()
        )
        if (restoredOrder == previousOrder) return
        onPlaylistsReordered(reorderedPlaylists)
        if (albums) {
            albumOrder = restoredOrder
            prefs.edit().putString("lib_fav_order_album_v1", restoredOrder.joinToString(",")).apply()
        } else {
            playlistOrder = restoredOrder
            prefs.edit().putString("lib_fav_order_pl_v1", restoredOrder.joinToString(",")).apply()
        }
        // Same guarded save path as the pools: only after a completed drag, never during a gesture.
        persist()
    }
}
