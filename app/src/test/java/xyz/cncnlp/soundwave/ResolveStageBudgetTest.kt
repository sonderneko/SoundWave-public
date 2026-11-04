package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCEPT4（修1）：阶段链截止感知切片。
 * 钉住：每个阶段（含跨脚本/跨平台转移腿）在任意链形下都分到正的时间片，
 * 末位截止恒等于链预算；尝试窗口不足最小切片时不开新枪。
 */
class ResolveStageBudgetTest {

    @Test
    fun offsetsAreCumulativeAndLastEqualsBudget() {
        val stages = listOf(
            SmartSourceStore.STAGE_ORIGIN,
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_CROSS_SCRIPT,
            SmartSourceStore.STAGE_CROSS_PLAT
        )
        val offsets = ResolveStageBudget.stageDeadlineOffsets(stages, 21_000L)
        assertEquals(stages.size, offsets.size)
        assertEquals(21_000L, offsets.last())
        for (i in 1 until offsets.size) {
            assertTrue("offsets must increase: $offsets", offsets[i] > offsets[i - 1])
        }
    }

    @Test
    fun transferLegsAlwaysReceiveAPositiveSlice() {
        // 只剩转移腿的链形（forceCross 等）同样要有可用切片，不能被切成 0。
        val stages = listOf(SmartSourceStore.STAGE_CROSS_SCRIPT, SmartSourceStore.STAGE_CROSS_PLAT)
        val offsets = ResolveStageBudget.stageDeadlineOffsets(stages, 21_000L)
        assertTrue(offsets[0] >= ResolveStageBudget.MIN_ATTEMPT_SLICE_MS)
        assertTrue(offsets[1] - offsets[0] >= ResolveStageBudget.MIN_ATTEMPT_SLICE_MS)
        assertEquals(21_000L, offsets[1])
    }

    @Test
    fun singleStageGetsWholeBudgetAndEmptyChainGetsNothing() {
        assertEquals(
            listOf(9_000L),
            ResolveStageBudget.stageDeadlineOffsets(listOf(SmartSourceStore.STAGE_QUALITY), 9_000L)
        )
        assertTrue(ResolveStageBudget.stageDeadlineOffsets(emptyList(), 9_000L).isEmpty())
        assertEquals(
            listOf(0L, 0L),
            ResolveStageBudget.stageDeadlineOffsets(
                listOf(SmartSourceStore.STAGE_ORIGIN, SmartSourceStore.STAGE_QUALITY), 0L
            )
        )
    }

    @Test
    fun attemptWindowCapsAtSoftTimeoutAndRefusesThinSlices() {
        assertEquals(6_000L, ResolveStageBudget.attemptWindowMs(10_000L, 6_000L))
        assertEquals(3_500L, ResolveStageBudget.attemptWindowMs(3_500L, 6_000L))
        assertEquals(2_000L, ResolveStageBudget.attemptWindowMs(2_000L, 6_000L))
        // 剩余不足最小切片：返回 0，调用方不开新枪（避免被压缩的伪超时）。
        assertEquals(0L, ResolveStageBudget.attemptWindowMs(1_999L, 6_000L))
        assertEquals(0L, ResolveStageBudget.attemptWindowMs(0L, 6_000L))
        assertEquals(0L, ResolveStageBudget.attemptWindowMs(-5_000L, 6_000L))
    }
}
