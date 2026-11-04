package xyz.cncnlp.soundwave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/** 批量删除详情歌单歌曲：一次更新状态/持久化，避免逐首重组导致卡顿或崩溃。 */
internal fun deleteSongsFromDetailBatch(
    songsToDelete: List<Song>,
    withFile: Boolean,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    getBundle: () -> LocalStore.UserBundle,
    setBundle: (LocalStore.UserBundle) -> Unit,
    getDetail: () -> Playlist?,
    setDetail: (Playlist?) -> Unit,
    setDetailSongs: (List<Song>) -> Unit,
    persist: () -> Unit
) {
    val d0 = getDetail() ?: return
    val keys = songsToDelete.map { it.key() }.toSet()
    val nextSongs = d0.songs.filterNot { it.key() in keys }.toMutableList()
    when (d0.id) {
        "liked" -> {
            val b = getBundle()
            b.removeFavoriteKeys(keys)
            val favs = b.favs.toMutableList()
            val pls = b.playlists.map { pl ->
                if (pl.id == "liked") pl.copy(songs = favs.toMutableList(), count = favs.size) else pl
            }.toMutableList()
            setBundle(b.copy(favs = favs, playlists = pls))
            setDetail(d0.copy(songs = favs.toMutableList(), count = favs.size))
            setDetailSongs(favs)
        }
        "history" -> setDetailSongs(nextSongs).also { setDetail(d0.copy(songs = nextSongs, count = nextSongs.size)) }
        "local" -> {
            // ACCEPT4（审计 §11）：本地歌单真源 = LocalLibraryStore——写真源 + 排除记录，bundle 镜像同步跟随；
            // 详情以真源结果为准（不再直设内存表，避免与真源分叉）。
            val next = LocalLibraryStore.removeSongs(context, keys)
            val b = getBundle()
            b.removeSongsFromPlaylist(d0.id, keys)
            setBundle(b.copy(playlists = b.playlists.toMutableList()))
            setDetail(d0.copy(songs = next.toMutableList(), count = next.size))
            setDetailSongs(next)
        }
        else -> {
            val b = getBundle()
            b.removeSongsFromPlaylist(d0.id, keys)
            val pls = b.playlists.toMutableList()
            val idx = pls.indexOfFirst { it.id == d0.id }
            if (idx >= 0) {
                setBundle(b.copy(playlists = pls))
                setDetail(pls[idx])
                setDetailSongs(nextSongs)
            }
        }
    }
    // ACCEPT5：队列同步不再靠这里的「整序全等」整表替换（真实使用中几乎不成立，且会冲掉手动插入/重排）；
    // persist() 经 MainActivity 包装层的歌单差量挂钩，由 PlaybackController 按来源标记条目级同步。
    persist()
    if (withFile) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        songsToDelete.forEach { s ->
            try {
                val u = s.resolvedUrl
                deleteLocalMediaResource(context, u)
            } catch (_: Exception) {}
        }
    }
}

/** 删除详情歌单/喜欢/历史中的歌曲；withFile=true 时同时删除本地文件。 */
internal fun deleteSongFromDetail(
    s: Song,
    withFile: Boolean,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    getBundle: () -> LocalStore.UserBundle,
    setBundle: (LocalStore.UserBundle) -> Unit,
    getDetail: () -> Playlist?,
    setDetail: (Playlist?) -> Unit,
    setDetailSongs: (List<Song>) -> Unit,
    getHistory: () -> List<Song>,
    setHistory: (List<Song>) -> Unit,
    persist: () -> Unit
) {
    val d0 = getDetail()
    if (d0 != null) when (d0.id) {
        "liked" -> {
            val b = getBundle()
            b.removeFavoriteKeys(setOf(s.key()))
            val nextFavs = b.favs.toMutableList()
            setBundle(b.copy(favs = nextFavs, playlists = b.playlists.toMutableList()))
            setDetail(d0.copy(songs = nextFavs.toMutableList(), count = nextFavs.size))
            setDetailSongs(nextFavs)
        }
        "history" -> {
            val h = getHistory().filterNot { it.key() == s.key() }
            setHistory(h)
            scope.launch(Dispatchers.IO) {
                val arr = org.json.JSONArray()
                h.forEach { arr.put(it.toJson()) }
                LocalStore.prefs(context).edit().putString("play_history_v1", arr.toString()).apply()
            }
            setDetail(d0.copy(songs = h.toMutableList(), count = h.size))
            setDetailSongs(h)
        }
        "local" -> {
            // ACCEPT4（审计 §11 断点修复）：本地歌单真源是 LocalLibraryStore + 扫描，旧实现只写 bundle 镜像——
            // 镜像缺该歌单/该曲目时删除石沉大海，详情回落读真源或下一次扫描即复活。现写真源 + 落排除记录
            // （重扫不复活），bundle 镜像同步跟随，详情以真源结果为准。
            val next = LocalLibraryStore.removeSongs(context, setOf(s.key()))
            val b = getBundle()
            b.removeSongsFromPlaylist(d0.id, setOf(s.key()))
            setBundle(b.copy(playlists = b.playlists.toMutableList()))
            setDetail(d0.copy(songs = next.toMutableList(), count = next.size))
            setDetailSongs(next)
        }
        else -> {
            val b = getBundle()
            b.removeSongsFromPlaylist(d0.id, setOf(s.key()))
            val pls = b.playlists.toMutableList()
            val idx = pls.indexOfFirst { it.id == d0.id }
            if (idx >= 0) {
                val songs = pls[idx].songs.toMutableList()
                setBundle(b.copy(playlists = pls))
                setDetail(pls[idx])
                setDetailSongs(songs)
            }
        }
    }
    // ACCEPT5：队列同步改走 persist() 的差量挂钩 + 来源标记机制（见 deleteSongsFromDetailBatch 注释），
    // 此处不再做全等门控的整表替换。历史页（id="history"）非歌单，不在来源同步域内。
    persist()
    if (withFile) {
        val u = s.resolvedUrl
        val ok = try { deleteLocalMediaResource(context, u) } catch (_: Exception) { false }
        AppNotice.attach(context.applicationContext)
        AppNotice.toast(if (ok) "已删除歌曲及本地文件" else "已从歌单删除（本地文件不可删）", AppNotice.Kind.NEUTRAL)
    }
}

/** A 波：从歌单移除前的曲目快照（供撤销用）。删文件不可逆、或不在歌单/喜欢详情内时返回 null。
 *  ACCEPT4：本地歌单快照读真源 LocalLibraryStore（bundle 镜像可能缺项，按镜像计数会把 removedCount 算成 0、吞掉撤销通知）。 */
internal fun removeSongsSnapshot(bundle: LocalStore.UserBundle, playlistId: String?, withFile: Boolean, context: android.content.Context): List<Song>? =
    when {
        withFile || playlistId == null -> null
        playlistId == "liked" -> bundle.favs.toList()
        playlistId == "local" -> LocalLibraryStore.songs(context)
        else -> bundle.playlists.firstOrNull { it.id == playlistId }?.songs?.toList()
    }

internal fun removeSongsCurrent(bundle: LocalStore.UserBundle, playlistId: String, context: android.content.Context): List<Song> =
    when (playlistId) {
        "liked" -> bundle.favs.toList()
        // ACCEPT4：本地歌单的「当前表」同为真源口径（撤销计数与恢复都以它为准）。
        "local" -> LocalLibraryStore.songs(context)
        else -> bundle.playlists.firstOrNull { it.id == playlistId }?.songs?.toList().orEmpty()
    }

/**
 * A 波：移除确有变化时发右侧带「撤销」的通知。撤销按快照恢复——liked 走 addFavorite 逆序回填
 * （与既有「清空喜欢」撤销同款），普通歌单整表回写（replacePlaylistSongs 同时清该歌单的删除墓碑），
 * 并同步当前详情页与持久化。
 */
internal fun emitRemoveSongsUndo(
    playlistId: String,
    beforeSongs: List<Song>,
    getBundle: () -> LocalStore.UserBundle,
    setBundle: (LocalStore.UserBundle) -> Unit,
    getDetail: () -> Playlist?,
    setDetail: (Playlist?) -> Unit,
    setDetailSongs: (List<Song>) -> Unit,
    persist: () -> Unit,
    context: android.content.Context,
    onRestored: () -> Unit
) {
    val removedCount = beforeSongs.size - removeSongsCurrent(getBundle(), playlistId, context).size
    if (removedCount <= 0) return
    AppNotice.undo(if (removedCount == 1) "已从歌单移除" else "已从歌单移除 $removedCount 首") {
        val b = getBundle()
        if (playlistId == "liked") {
            beforeSongs.reversed().forEach { if (!b.isLiked(it)) b.addFavorite(it) }
            setBundle(b.copy(favs = b.favs.toMutableList(), playlists = b.playlists.toMutableList()))
        } else if (playlistId == "local") {
            // ACCEPT4：本地歌单撤销 = 真源解除排除并回（restoreSongs）+ bundle 镜像按真源结果整表回写。
            val next = LocalLibraryStore.restoreSongs(context, beforeSongs)
            b.replacePlaylistSongs(playlistId, next)
            setBundle(b.copy(playlists = b.playlists.toMutableList()))
        } else {
            b.replacePlaylistSongs(playlistId, beforeSongs)
            setBundle(b.copy(playlists = b.playlists.toMutableList()))
        }
        if (getDetail()?.id == playlistId) {
            val restored = removeSongsCurrent(getBundle(), playlistId, context)
            setDetail(getDetail()?.copy(songs = restored.toMutableList(), count = restored.size))
            setDetailSongs(restored)
        }
        onRestored()
        persist()
    }
}
