package xyz.cncnlp.soundwave.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT6 启动页保险丝判定：硬上限 1.5s 为用户裁定值，逐项钉死——
 * 就绪即放行、未就绪只留到上限、上限点起无条件放行。
 */
class SplashGatePolicyTest {

    @Test
    fun `cap is the user ruled 1500ms`() {
        assertEquals(1_500L, SplashGatePolicy.MAX_HOLD_MS)
    }

    @Test
    fun `keeps splash while not ready and within cap`() {
        assertTrue(SplashGatePolicy.shouldKeepOnScreen(ready = false, elapsedMs = 0L))
        assertTrue(SplashGatePolicy.shouldKeepOnScreen(ready = false, elapsedMs = 1_499L))
    }

    @Test
    fun `fuse releases at and beyond cap even when never ready`() {
        assertFalse(SplashGatePolicy.shouldKeepOnScreen(ready = false, elapsedMs = 1_500L))
        assertFalse(SplashGatePolicy.shouldKeepOnScreen(ready = false, elapsedMs = 60_000L))
    }

    @Test
    fun `ready releases immediately regardless of elapsed`() {
        assertFalse(SplashGatePolicy.shouldKeepOnScreen(ready = true, elapsedMs = 0L))
        assertFalse(SplashGatePolicy.shouldKeepOnScreen(ready = true, elapsedMs = 800L))
    }
}
