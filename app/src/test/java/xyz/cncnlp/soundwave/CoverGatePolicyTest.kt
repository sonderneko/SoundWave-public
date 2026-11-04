package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT5-covergate 纯逻辑单测：失败负缓存（CoverFailureCache）与队列预热选序
 * （queueCoverPrewarmIndices）。ensureCover 接线验证见 CoverNegativeCacheWiringTest。
 */
class CoverGatePolicyTest {

    // ---------- CoverFailureCache ----------

    @Test
    fun failureIsRememberedWithinTtlAndForgottenAfter() {
        val cache = CoverFailureCache(ttlMs = 1_000L)
        assertFalse(cache.isRecentlyFailed("u", 10_000L))
        cache.recordFailure("u", 10_000L)
        assertTrue(cache.isRecentlyFailed("u", 10_000L))
        assertTrue(cache.isRecentlyFailed("u", 10_999L))
        assertFalse(cache.isRecentlyFailed("u", 11_000L))
    }

    @Test
    fun repeatedFailureInsideWindowDoesNotExtendBlackout() {
        val cache = CoverFailureCache(ttlMs = 1_000L)
        cache.recordFailure("u", 100L)
        cache.recordFailure("u", 900L) // 窗内重复失败不刷新时间戳
        assertTrue(cache.isRecentlyFailed("u", 1_099L))
        assertFalse("blackout ends 1000ms after the FIRST failure", cache.isRecentlyFailed("u", 1_100L))
        cache.recordFailure("u", 1_100L) // 过期后再次失败重新起算
        assertTrue(cache.isRecentlyFailed("u", 2_099L))
        assertFalse(cache.isRecentlyFailed("u", 2_100L))
    }

    @Test
    fun successClearsFailureAndClockSkewStaysConservative() {
        val cache = CoverFailureCache(ttlMs = 1_000L)
        cache.recordFailure("u", 5_000L)
        assertTrue("clock moving backwards must not reopen a fresh failure", cache.isRecentlyFailed("u", 4_500L))
        cache.recordSuccess("u")
        assertFalse(cache.isRecentlyFailed("u", 5_000L))
        cache.recordSuccess("never-failed") // 不抛
    }

    @Test
    fun pruneDropsExpiredFirstThenOldestBeyondCap() {
        val cache = CoverFailureCache(ttlMs = 1_000L, maxEntries = 2)
        cache.recordFailure("a", 0L)
        cache.recordFailure("b", 100L)
        cache.recordFailure("c", 200L) // 超限：无过期项，淘汰最旧 a
        assertEquals(2, cache.sizeForTest())
        assertFalse(cache.isRecentlyFailed("a", 200L))
        assertTrue(cache.isRecentlyFailed("b", 200L))
        assertTrue(cache.isRecentlyFailed("c", 200L))
        cache.recordFailure("d", 5_000L) // b、c 均已过期，先清过期
        assertEquals(1, cache.sizeForTest())
        assertTrue(cache.isRecentlyFailed("d", 5_000L))
    }

    // ---------- queueCoverPrewarmIndices ----------

    @Test
    fun prewarmIndicesEmptyAndInvalidInputs() {
        assertEquals(emptyList<Int>(), queueCoverPrewarmIndices(0, 0))
        assertEquals(emptyList<Int>(), queueCoverPrewarmIndices(10, 0, limit = 0))
        // 当前下标非法 → 只取队列头
        assertEquals((0 until 24).toList(), queueCoverPrewarmIndices(178, -1))
        assertEquals((0 until 24).toList(), queueCoverPrewarmIndices(178, 178))
    }

    @Test
    fun prewarmIndicesSmallQueueCoversAllRingOrdered() {
        assertEquals(listOf(2, 3, 1, 4, 0), queueCoverPrewarmIndices(5, 2))
        assertEquals(listOf(0, 1, 2), queueCoverPrewarmIndices(3, 0))
    }

    @Test
    fun prewarmIndicesNeighborhoodFirstThenHeadFill() {
        val picked = queueCoverPrewarmIndices(178, 90)
        assertEquals(24, picked.size)
        assertEquals(picked.size, picked.distinct().size)
        assertEquals(90, picked.first())
        // 邻域 16 个：90 起环形展开（+1、-1 交替）
        assertEquals(
            listOf(90, 91, 89, 92, 88, 93, 87, 94, 86, 95, 85, 96, 84, 97, 83, 98),
            picked.subList(0, 16)
        )
        // 其后队列头补齐 8 个
        assertEquals((0 until 8).toList(), picked.subList(16, 24))
    }

    @Test
    fun prewarmIndicesAtQueueEdges() {
        val atHead = queueCoverPrewarmIndices(178, 0)
        assertEquals((0 until 24).toList(), atHead)
        val atTail = queueCoverPrewarmIndices(178, 177)
        assertEquals(24, atTail.size)
        assertEquals(177, atTail.first())
        assertTrue(atTail.containsAll((0 until 8).toList()))
        assertTrue(atTail.all { it in 0 until 178 })
    }
}
