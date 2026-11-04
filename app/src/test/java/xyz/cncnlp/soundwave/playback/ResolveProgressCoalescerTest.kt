package xyz.cncnlp.soundwave.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ACCEPT6-FREEZE：解析进度发布节流的语义钉点——首条立即发布、窗口内只留最新、
 * 到窗发布最新（非过期值）、drain 取走待发尾巴。修复目标是慢解析期间 Compose
 * 状态写频率（每次写都会失效消费 status 的组合作用域），语义不变：用户最终看到的
 * 永远是最新一条尝试文案。
 */
class ResolveProgressCoalescerTest {
    private class FakeClock(var now: Long = 1_000L)

    private fun coalescer(clock: FakeClock, interval: Long = 350L) =
        ResolveProgressCoalescer(minIntervalMs = interval, clock = { clock.now })

    @Test
    fun firstOfferPublishesImmediately() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("尝试 A", c.offer("尝试 A"))
    }

    @Test
    fun burstWithinWindowPublishesNothingUntilWindowEnds() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("尝试 A", c.offer("尝试 A"))
        clock.now += 50
        assertNull(c.offer("尝试 B"))
        clock.now += 50
        assertNull(c.offer("尝试 C"))
        clock.now += 100
        assertNull(c.offer("尝试 D"))
    }

    @Test
    fun offerAfterWindowPublishesTheLatestNotTheStaleOne() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("尝试 A", c.offer("尝试 A"))
        clock.now += 50
        assertNull(c.offer("尝试 B"))
        clock.now += 50
        assertNull(c.offer("尝试 C"))
        clock.now += 300 // 距首发 400ms > 350ms 窗口
        assertEquals("尝试 D", c.offer("尝试 D"))
    }

    @Test
    fun windowEdgeExactlyAtIntervalPublishes() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("A", c.offer("A"))
        clock.now += 350
        assertEquals("B", c.offer("B"))
    }

    @Test
    fun drainPendingReturnsLatestOnce() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("A", c.offer("A"))
        clock.now += 10
        assertNull(c.offer("B"))
        clock.now += 10
        assertNull(c.offer("C"))
        assertEquals("C", c.drainPending())
        assertNull(c.drainPending())
    }

    @Test
    fun afterDrainNextOfferWaitsForANewWindow() {
        val clock = FakeClock()
        val c = coalescer(clock)
        assertEquals("A", c.offer("A"))
        clock.now += 10
        assertNull(c.offer("B"))
        assertEquals("B", c.drainPending())
        clock.now += 10
        assertNull(c.offer("C"))
        clock.now += 400
        assertEquals("D", c.offer("D"))
    }
}
