package xyz.cncnlp.soundwave.ui.player

import android.content.Context
import android.graphics.drawable.Drawable
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.CacheManager

/**
 * r656-G（P0-1 / P-04）：播放器封面取色的唯一后台入口。
 *
 * - 用 allowHardware(false) + 64px 的独立 Coil 请求拿软件位图（从 Coil 磁盘缓存解码，几毫秒），
 *   在 Dispatchers.Default 上做 32×32 聚类，主线程零像素工作；
 * - 结果按封面 URL 放进 LRU(64)：切回上一首、播放器重组、三处 onSuccess 都不再重复计算；
 * - 该请求不写 Coil 内存缓存，避免 64px 小图顶掉大封面的缓存项。
 */
internal object CoverToneCache {
    private const val SAMPLE_REQUEST_PX = 64
    private val cache = object : LinkedHashMap<String, CoverBackgroundTones>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CoverBackgroundTones>?): Boolean = size > 64
    }
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<CoverBackgroundTones?>>()
    private val computeCount = AtomicInteger(0)

    /** 测试/诊断：真正执行取色计算的次数。 */
    val computations: Int get() = computeCount.get()

    /** 可替换的加载器（单测注入）；默认走应用 ImageLoader 的软件位图小图请求。 */
    @Volatile internal var loader: suspend (Context, Any) -> Drawable? = { context, model ->
        val request = ImageRequest.Builder(context)
            .data(model)
            .size(SAMPLE_REQUEST_PX, SAMPLE_REQUEST_PX)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        context.imageLoader.execute(request).drawable
    }

    fun cached(cover: String): CoverBackgroundTones? = synchronized(cache) { cache[cover] }

    suspend fun tonesFor(context: Context, cover: String?): CoverBackgroundTones? {
        if (cover.isNullOrBlank()) return null
        cached(cover)?.let { return it }

        val pending = CompletableDeferred<CoverBackgroundTones?>()
        val existing = inFlight.putIfAbsent(cover, pending)
        if (existing != null) return existing.await()

        try {
            // A previous request may have populated the cache between the first lookup
            // and in-flight registration; reuse it rather than starting duplicate work.
            cached(cover)?.let {
                pending.complete(it)
                return it
            }
            // Cancellation must propagate to the caller. runCatching would swallow
            // CancellationException and turn a cancelled composition into a normal cache miss.
            val tones = try {
                val drawable = withContext(Dispatchers.IO) {
                    loader(context, CacheManager.coverModel(context, cover) ?: cover)
                }
                if (drawable == null) {
                    pending.complete(null)
                    return null
                }
                withContext(Dispatchers.Default) {
                    computeCount.incrementAndGet()
                    sampleCoverBackgroundAnyThread(drawable)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (tones != null) synchronized(cache) { cache[cover] = tones }
            pending.complete(tones)
            return tones
        } catch (cancelled: CancellationException) {
            pending.complete(null)
            throw cancelled
        } finally {
            // Even a fatal failure must release peer waiters; do not swallow it.
            pending.complete(null)
            inFlight.remove(cover, pending)
        }
    }

    internal fun clearForTest() {
        synchronized(cache) { cache.clear() }
        inFlight.values.forEach { it.complete(null) }
        inFlight.clear()
        computeCount.set(0)
    }
}
