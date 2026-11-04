package xyz.cncnlp.soundwave

/** Full detail-cache contents plus the write timestamp; legacy arrays have savedAt = 0 and are stale. */
internal data class DetailCacheSnapshot(
    val songs: List<Song>,
    val savedAt: Long,
) {
    fun isFresh(ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        savedAt > 0L && ttlMs > 0L && now - savedAt <= ttlMs
}

internal sealed class DetailCacheResolution<out T> {
    data class CacheHit(val songs: List<Song>) : DetailCacheResolution<Nothing>()
    data class Refreshed<T>(val cachedSongs: List<Song>, val value: T) : DetailCacheResolution<T>()
}

/** Publishes any cached songs immediately, skips I/O only for a non-empty fresh entry, and honors explicit refresh. */
internal suspend fun <T> resolveDetailCache(
    cache: DetailCacheSnapshot,
    ttlMs: Long,
    now: Long = System.currentTimeMillis(),
    forceRefresh: Boolean = false,
    publishCached: (List<Song>) -> Unit = {},
    fetchDetail: suspend () -> T,
): DetailCacheResolution<T> {
    val cachedSongs = cache.songs
    if (cachedSongs.isNotEmpty()) publishCached(cachedSongs)
    if (cachedSongs.isNotEmpty() && cache.isFresh(ttlMs, now) && !forceRefresh) {
        return DetailCacheResolution.CacheHit(cachedSongs)
    }
    return DetailCacheResolution.Refreshed(cachedSongs, fetchDetail())
}
