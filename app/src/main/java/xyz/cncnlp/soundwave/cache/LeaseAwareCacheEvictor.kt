package xyz.cncnlp.soundwave.cache

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import xyz.cncnlp.soundwave.CacheManager
import java.util.TreeSet

/** LRU eviction that never unlinks a cache key while a CacheDataSource owns it. */
@androidx.media3.common.util.UnstableApi
internal class LeaseAwareCacheEvictor(
    maxBytes: Long,
    private val leases: Media3CacheReadLeaseRegistry,
    private val onSkippedActiveKeys: (Int) -> Unit = {}
) : CacheEvictor {
    @Volatile private var maxBytes = maxBytes.coerceAtLeast(0L)
    private val leastRecentlyUsed = TreeSet<CacheSpan> { left, right ->
        val timestampOrder = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        if (timestampOrder != 0) timestampOrder else left.compareTo(right)
    }
    private var currentSize = 0L

    fun setMaxBytes(value: Long) {
        maxBytes = value.coerceAtLeast(0L)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evictCache(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (leastRecentlyUsed.add(span)) currentSize += span.length
        evictCache(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (leastRecentlyUsed.remove(span)) currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        val skippedActiveKeys = linkedSetOf<String>()
        while (exceedsLimit(requiredSpace)) {
            var removedSpan = false
            val candidates = leastRecentlyUsed.toList()
            for (span in candidates) {
                val sizeBefore = currentSize
                val removed = leases.removeIfUnleased(span.key) { cache.removeSpan(span) }
                if (!removed) {
                    if (leases.isActive(span.key)) skippedActiveKeys += span.key
                    continue
                }
                if (currentSize == sizeBefore && leastRecentlyUsed.remove(span)) {
                    currentSize -= span.length
                }
                if (currentSize < sizeBefore) {
                    CacheManager.recordEvictLru()
                    removedSpan = true
                    break
                }
            }
            if (!removedSpan) break
        }
        if (skippedActiveKeys.isNotEmpty()) onSkippedActiveKeys(skippedActiveKeys.size)
    }

    private fun exceedsLimit(requiredSpace: Long): Boolean {
        val limit = maxBytes
        return requiredSpace > limit || currentSize > limit - requiredSpace
    }
}
