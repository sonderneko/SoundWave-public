package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import xyz.cncnlp.soundwave.source.direct.kgFillAlbumCovers
import java.util.concurrent.ConcurrentHashMap

/**
 * 专辑封面兜底回补（accept6-singercover）：歌曲自带封面为空、但带真实专辑 ID 时，
 * 按 (平台, 专辑 ID) 取专辑封面回填。这是兜底链此前缺失的中间一环——
 * 歌曲封面 → 专辑封面（本类）→ 默认黑胶母题。不拿歌手头像冒充专辑封面
 * （批27 反冒充口径：歌手页不拿专辑图冒充头像，反之亦然）。
 *
 * 纪律：全程异步（调用方在旁路协程里调，永不阻塞解析/播放）；进程内缓存——
 * 同专辑命中不重复取；失败记 10 分钟负缓存防反复打空；同专辑并发只放行一发。
 * kg 优先走轻量的 album/info 单查（kgFillAlbumCovers），其余平台走专辑详情
 * （ApiClient.albumDetail，其 cover 即专辑封面）。
 */
internal object AlbumCoverBackfill {

    private val platformSources = setOf("wy", "tx", "kg", "kw", "mg")

    private const val NEGATIVE_TTL_MS = 10L * 60L * 1000L
    private const val POSITIVE_CAP = 256

    private val positive = ConcurrentHashMap<String, String>()
    private val negativeAtMs = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 可回补时返回专辑 ID，否则 null（已有封面 / 无专辑 / 专辑 ID 为占位 / 非平台来源）。 */
    fun targetAlbumId(song: Song): String? {
        if (song.cover.isNotBlank()) return null
        if (song.source !in platformSources) return null
        val albumId = song.albumId.trim()
        if (albumId.isEmpty() || albumId == "0") return null
        return albumId
    }

    suspend fun coverFor(song: Song): String? = coverFor(song, ::fetchAlbumCover)

    internal suspend fun coverFor(
        song: Song,
        fetcher: suspend (Song, String) -> String?,
    ): String? {
        val albumId = targetAlbumId(song) ?: return null
        val key = "${song.source}:$albumId"
        positive[key]?.let { return it }
        val failedAt = negativeAtMs[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < NEGATIVE_TTL_MS) return null
        if (!inFlight.add(key)) return null
        try {
            val fetched = try {
                fetcher(song, albumId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val cover = fetched?.takeIf { it.isNotBlank() }
                ?.let { normalizeCoverUrl(it) }
                ?.takeIf { it.isNotBlank() }
            if (cover == null) {
                negativeAtMs[key] = System.currentTimeMillis()
                return null
            }
            if (positive.size >= POSITIVE_CAP) positive.clear()
            positive[key] = cover
            negativeAtMs.remove(key)
            return cover
        } finally {
            inFlight.remove(key)
        }
    }

    private suspend fun fetchAlbumCover(song: Song, albumId: String): String? {
        if (song.source == "kg") {
            val filled = runCatching { kgFillAlbumCovers(listOf(song)) }.getOrNull()
                ?.firstOrNull()?.cover
            if (!filled.isNullOrBlank()) return filled
        }
        return runCatching { ApiClient.albumDetail(albumId, song.source)?.cover }.getOrNull()
    }

    internal fun resetForTest() {
        positive.clear()
        negativeAtMs.clear()
        inFlight.clear()
    }
}
