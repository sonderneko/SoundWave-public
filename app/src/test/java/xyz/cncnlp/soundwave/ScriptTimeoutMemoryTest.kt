package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT4（修2）：软超时短记忆的击发纪律。
 * 一次超时不降权（瞬时抖动），窗口内两次才降权；窗口滑过自动恢复；
 * 成功清账；键隔离（不同脚本互不连坐）。
 */
class ScriptTimeoutMemoryTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun core(clock: FakeClock) = ScriptTimeoutMemoryCore(nowMs = { clock.now })

    @Test
    fun singleTimeoutDoesNotDegrade() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        assertFalse(memory.isDegraded("script-a"))
        assertEquals(1, memory.strikeCount("script-a"))
    }

    @Test
    fun twoTimeoutsInsideWindowDegrade() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        clock.advance(30_000L)
        memory.recordTimeout("script-a")
        assertTrue(memory.isDegraded("script-a"))
    }

    @Test
    fun degradationExpiresWithWindow() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        memory.recordTimeout("script-a")
        assertTrue(memory.isDegraded("script-a"))
        clock.advance(ScriptTimeoutMemoryCore.WINDOW_MS + 1L)
        assertFalse(memory.isDegraded("script-a"))
        assertEquals(0, memory.strikeCount("script-a"))
    }

    @Test
    fun strikesOutsideWindowDoNotAccumulate() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        clock.advance(ScriptTimeoutMemoryCore.WINDOW_MS + 1L)
        memory.recordTimeout("script-a")
        assertFalse(memory.isDegraded("script-a"))
    }

    @Test
    fun clearOnSuccessResetsStrikes() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        memory.recordTimeout("script-a")
        assertTrue(memory.isDegraded("script-a"))
        memory.clear("script-a")
        assertFalse(memory.isDegraded("script-a"))
        assertEquals(0, memory.strikeCount("script-a"))
    }

    @Test
    fun keysAreIsolated() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordTimeout("script-a")
        memory.recordTimeout("script-a")
        assertTrue(memory.isDegraded("script-a"))
        assertFalse(memory.isDegraded("script-b"))
    }
}
