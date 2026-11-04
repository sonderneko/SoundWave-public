package xyz.cncnlp.soundwave.cache

import androidx.media3.datasource.cache.Cache
import xyz.cncnlp.soundwave.MediaCacheTrimResult

/** Clears resources behind a read fence so no new CacheDataSource can race the key snapshot. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun clearMedia3CacheResources(
    cache: Cache,
    leases: Media3CacheReadLeaseRegistry
): Int = leases.withClearFence { fence ->
    val skippedKeys = linkedSetOf<String>()
    cache.keys.toList().forEach { key ->
        if (!fence.removeIfUnleased(key) { cache.removeResource(key) }) skippedKeys += key
    }
    val remainingKeys = cache.keys
    check(remainingKeys.all { it in skippedKeys }) {
        "Media cache clear left an unprotected resource behind"
    }
    skippedKeys.size
}

/** Trims least-recently-touched resources while atomically skipping active readers. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun trimMedia3Cache(
    cache: Cache,
    targetBytes: Long,
    leases: Media3CacheReadLeaseRegistry,
    onResourceEvicted: () -> Unit
): MediaCacheTrimResult {
    val target = targetBytes.coerceAtLeast(0L)
    var remaining = cache.cacheSpace
    if (remaining <= target) return MediaCacheTrimResult(target, remaining, 0, 0)

    data class CachedResource(val key: String, val touched: Long)
    val victims = cache.keys.mapNotNull { key ->
        val spans = cache.getCachedSpans(key)
        if (spans.isEmpty()) null else CachedResource(key, spans.minOf { it.lastTouchTimestamp })
    }.sortedBy { it.touched }
    val skippedKeys = linkedSetOf<String>()
    var failedResources = 0

    for (victim in victims) {
        if (remaining <= target) break
        try {
            val before = remaining
            val removed = leases.removeIfUnleased(victim.key) { cache.removeResource(victim.key) }
            if (!removed) {
                if (leases.isActive(victim.key)) skippedKeys += victim.key
                continue
            }
            remaining = cache.cacheSpace
            if (remaining < before) onResourceEvicted()
        } catch (_: Exception) {
            failedResources++
        }
    }
    remaining = cache.cacheSpace
    if (remaining > target) {
        cache.keys.filterTo(skippedKeys) { leases.isActive(it) }
    }
    return MediaCacheTrimResult(target, remaining, skippedKeys.size, failedResources)
}
