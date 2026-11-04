package xyz.cncnlp.soundwave.ui.home

import kotlinx.coroutines.CancellationException
import xyz.cncnlp.soundwave.DetailCacheResolution
import xyz.cncnlp.soundwave.DetailCacheSnapshot
import xyz.cncnlp.soundwave.HomeFeedCache
import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.resolveDetailCache

/** Loads only the selected rank's detail; Compose owns selection, loading and displayed-song state. */
internal suspend fun loadSelectedRankDetail(
    platform: String,
    board: RankBoard?,
    loadCached: suspend (id: String, source: String) -> DetailCacheSnapshot,
    fetchDetail: suspend (id: String, source: String) -> List<Song>,
    saveDetail: suspend (id: String, source: String, songs: List<Song>) -> Unit,
    forceRefresh: Boolean = false,
    now: Long = System.currentTimeMillis(),
    publishCached: (List<Song>) -> Unit = {},
): List<Song> {
    if (board == null) return emptyList()

    val source = board.source.ifBlank { platform }
    val cached = try {
        loadCached(board.id, source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        DetailCacheSnapshot(emptyList(), 0L)
    }
    val resolution = try {
        resolveDetailCache(
            cache = cached,
            ttlMs = HomeFeedCache.ResourceTtl.RANK_DETAIL_MS,
            now = now,
            forceRefresh = forceRefresh,
            publishCached = publishCached,
        ) { fetchDetail(board.id, source) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        if (cached.songs.isNotEmpty()) return cached.songs
        return board.songs
    }
    when (resolution) {
        is DetailCacheResolution.CacheHit -> return resolution.songs
        is DetailCacheResolution.Refreshed -> {
            val detail = resolution.value
            if (detail.isEmpty()) return resolution.cachedSongs.ifEmpty { board.songs }
            try {
                saveDetail(board.id, source, detail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A successful response remains usable if persisting its cache fails.
            }
            return detail
        }
    }
}
