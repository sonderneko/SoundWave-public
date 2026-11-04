package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ProbeYieldGate 语义：播放优先 = 探测在检查点暂停等待、稳定后原地恢复，
 * 而不是被整单中止。只有等待累计超限（或任务真取消）才抛 CancellationException，
 * 落回既有 SKIPPED 记账。
 */
class ProbeYieldGateTest {

    private class Rig {
        val epoch = AtomicLong(0L)
        val busy = AtomicBoolean(false)
        var sleeps = 0
    }

    private fun Rig.gate(nowMs: () -> Long) = ProbeYieldGate(
        epoch = { epoch.get() },
        playbackBusy = { busy.get() },
        nowMs = nowMs,
        sleeper = { sleeps++; delay(it) }
    )

    @Test
    fun checkpointReturnsImmediatelyWhenPlaybackIdle() = runTest {
        val rig = Rig()
        val gate = rig.gate { testScheduler.currentTime }
        gate.checkpoint()
        assertEquals(0, rig.sleeps)
    }

    @Test
    fun checkpointWaitsForSettleThenResumesAndAdoptsEpoch() = runTest {
        val rig = Rig()
        val gate = rig.gate { testScheduler.currentTime }
        // 用户点歌：纪元变化 + 解析通道忙碌。
        rig.epoch.incrementAndGet()
        rig.busy.set(true)
        var resumedAt = -1L
        val job = async {
            gate.checkpoint()
            resumedAt = testScheduler.currentTime
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse("解析忙碌期间不得放行探测", job.isCompleted)
        // 解析在 t=1000 结束；还须观察满一个稳定窗才放行。
        rig.busy.set(false)
        advanceTimeBy(ProbeYieldGate.SETTLE_MS)
        runCurrent()
        assertFalse("稳定窗未满不得放行", job.isCompleted)
        advanceTimeBy(ProbeYieldGate.POLL_MS * 2)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(resumedAt >= 1_000 + ProbeYieldGate.SETTLE_MS)
        // 新纪元已被领用：后续检查点立即放行，不再重复等待。
        val sleepsBefore = rig.sleeps
        gate.checkpoint()
        assertEquals(sleepsBefore, rig.sleeps)
    }

    @Test
    fun checkpointWaitsOutBusyPlaybackEvenWithoutEpochChange() = runTest {
        val rig = Rig()
        val gate = rig.gate { testScheduler.currentTime }
        rig.busy.set(true)
        val job = async { gate.checkpoint() }
        advanceTimeBy(600)
        runCurrent()
        assertFalse(job.isCompleted)
        rig.busy.set(false)
        advanceTimeBy(ProbeYieldGate.SETTLE_MS + ProbeYieldGate.POLL_MS * 2)
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun checkpointGivesUpAfterCumulativeWaitBudget() = runTest {
        val rig = Rig()
        // 进入检查点时已有一轮抢占在途（纪元已变 + 通道忙碌）。
        rig.epoch.incrementAndGet()
        rig.busy.set(true)
        val gate = rig.gate { testScheduler.currentTime }
        // 播放侧每 800ms 翻转一次忙碌状态：空闲窗永远短于稳定窗，探测等不到放行，
        // 等待累计超限后按 SKIPPED 语义放弃。
        backgroundScope.launch {
            while (true) {
                delay(800)
                rig.busy.set(!rig.busy.get())
            }
        }
        var thrown: Throwable? = null
        val job = async {
            try {
                gate.checkpoint()
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        advanceTimeBy(ProbeYieldGate.MAX_TOTAL_WAIT_MS + 10_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun checkpointHonoursRealCancellationPromptly() = runTest {
        val rig = Rig()
        val gate = rig.gate { testScheduler.currentTime }
        rig.busy.set(true)
        val job = launch { gate.checkpoint() }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(job.isActive)
        job.cancel()
        advanceTimeBy(ProbeYieldGate.POLL_MS * 2)
        runCurrent()
        assertTrue(job.isCancelled)
    }
}
