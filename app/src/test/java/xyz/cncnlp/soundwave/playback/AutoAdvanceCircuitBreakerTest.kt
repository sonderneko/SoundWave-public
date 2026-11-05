package xyz.cncnlp.soundwave.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PB-F2：连播失败熔断——阈值常量、最小样本、滑动窗口、成功重置与策略集成。 */
class AutoAdvanceCircuitBreakerTest {

    @Test
    fun `threshold constant is thirty percent`() {
        assertEquals(0.30, AutoAdvanceCircuitBreaker.FAILURE_RATE_THRESHOLD, 0.0)
        assertEquals(10, AutoAdvanceCircuitBreaker.WINDOW_SIZE)
        assertEquals(4, AutoAdvanceCircuitBreaker.MIN_SAMPLES)
    }

    @Test
    fun `does not trip below minimum samples`() {
        val breaker = AutoAdvanceCircuitBreaker()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES - 1) {
            assertFalse(breaker.recordFailure())
        }
        assertFalse(breaker.isTripped())
        assertEquals(1.0, breaker.failureRate(), 0.0)
    }

    @Test
    fun `trips at minimum samples when failure rate is at or above threshold`() {
        val breaker = AutoAdvanceCircuitBreaker()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES - 1) { breaker.recordFailure() }
        assertTrue(breaker.recordFailure())
        assertTrue(breaker.isTripped())
    }

    @Test
    fun `stays tripped until a success resets the count`() {
        val breaker = AutoAdvanceCircuitBreaker()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES) { breaker.recordFailure() }
        assertTrue(breaker.isTripped())
        // 熔断后继续失败不自愈。
        assertTrue(breaker.recordFailure())
        breaker.recordSuccess()
        assertFalse(breaker.isTripped())
        assertEquals(0, breaker.sampleCount())
        assertEquals(0.0, breaker.failureRate(), 0.0)
        // 重置后重新计数：最小样本内不再熔断。
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES - 1) {
            assertFalse(breaker.recordFailure())
        }
    }

    @Test
    fun `window trims to the most recent ten attempts`() {
        val breaker = AutoAdvanceCircuitBreaker()
        repeat(AutoAdvanceCircuitBreaker.WINDOW_SIZE + 5) { breaker.recordFailure() }
        assertEquals(AutoAdvanceCircuitBreaker.WINDOW_SIZE, breaker.sampleCount())
    }

    @Test
    fun `policy resolve failures trip the breaker and stop auto advance`() {
        val policy = PlaybackRecoveryPolicy()
        var last: RecoveryDecision? = null
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES) { i ->
            last = policy.onResolveFailure("song:$i")
        }
        assertEquals(RecoveryAction.STOP, last!!.action)
        assertTrue(last.circuitTripped)
        assertTrue(policy.autoAdvanceBreaker.isTripped())
    }

    @Test
    fun `policy healthy playback resets the breaker`() {
        val policy = PlaybackRecoveryPolicy()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES) { i -> policy.onResolveFailure("song:$i") }
        assertTrue(policy.autoAdvanceBreaker.isTripped())
        policy.onHealthyPlayback("song:ok")
        assertFalse(policy.autoAdvanceBreaker.isTripped())
        assertEquals(0, policy.autoAdvanceBreaker.sampleCount())
    }

    @Test
    fun `policy user intent resets the breaker`() {
        val policy = PlaybackRecoveryPolicy()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES) { i -> policy.onResolveFailure("song:$i") }
        assertTrue(policy.autoAdvanceBreaker.isTripped())
        policy.onUserIntent()
        assertFalse(policy.autoAdvanceBreaker.isTripped())
    }

    @Test
    fun `near end skips do not feed the breaker`() {
        val policy = PlaybackRecoveryPolicy()
        repeat(AutoAdvanceCircuitBreaker.MIN_SAMPLES + 2) {
            val d = policy.onPlaybackError("tail", positionMs = 237_500L, durationMs = 240_000L)
            assertEquals(RecoveryAction.SKIP_NEXT, d.action)
            assertTrue(d.nearEnd)
        }
        assertFalse(policy.autoAdvanceBreaker.isTripped())
        assertEquals(0, policy.autoAdvanceBreaker.sampleCount())
    }
}
