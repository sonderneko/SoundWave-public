package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT6（qqslow）：慢速语义空返短记忆的击发纪律。
 * 快速空返不记账（正常未命中）；窗口内两次慢空返才降级；窗口滑过自动
 * 恢复；成功清账；键隔离（不同脚本/平台互不连坐）。
 */
class ScriptSlowEmptyMemoryTest {

    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun core(clock: FakeClock) = ScriptSlowEmptyMemoryCore(nowMs = { clock.now })

    @Test
    fun fastEmptyIsNotRecorded() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 800L)
        memory.recordSlowEmpty("script-a", ScriptSlowEmptyMemoryCore.SLOW_FLOOR_MS - 1L)
        assertEquals(0, memory.strikeCount("script-a"))
        assertFalse(memory.shouldSkip("script-a"))
    }

    @Test
    fun singleSlowEmptyDoesNotSkip() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 3_300L)
        assertFalse(memory.shouldSkip("script-a"))
        assertEquals(1, memory.strikeCount("script-a"))
    }

    @Test
    fun twoSlowEmptiesInsideWindowSkip() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 3_300L)
        clock.advance(60_000L)
        memory.recordSlowEmpty("script-a", 4_100L)
        assertTrue(memory.shouldSkip("script-a"))
    }

    @Test
    fun skipExpiresWithWindow() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 3_300L)
        memory.recordSlowEmpty("script-a", 3_300L)
        assertTrue(memory.shouldSkip("script-a"))
        clock.advance(ScriptSlowEmptyMemoryCore.WINDOW_MS + 1L)
        assertFalse(memory.shouldSkip("script-a"))
        assertEquals(0, memory.strikeCount("script-a"))
    }

    @Test
    fun strikesOutsideWindowDoNotAccumulate() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 3_300L)
        clock.advance(ScriptSlowEmptyMemoryCore.WINDOW_MS + 1L)
        memory.recordSlowEmpty("script-a", 3_300L)
        assertFalse(memory.shouldSkip("script-a"))
        assertEquals(1, memory.strikeCount("script-a"))
    }

    @Test
    fun clearOnSuccessResets() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a", 3_300L)
        memory.recordSlowEmpty("script-a", 3_300L)
        assertTrue(memory.shouldSkip("script-a"))
        memory.clear("script-a")
        assertFalse(memory.shouldSkip("script-a"))
    }

    @Test
    fun keysAreIsolated() {
        val clock = FakeClock()
        val memory = core(clock)
        memory.recordSlowEmpty("script-a|tx", 3_300L)
        memory.recordSlowEmpty("script-a|tx", 3_300L)
        assertTrue(memory.shouldSkip("script-a|tx"))
        assertFalse(memory.shouldSkip("script-a|kw"))
        assertFalse(memory.shouldSkip("script-b|tx"))
    }
}
