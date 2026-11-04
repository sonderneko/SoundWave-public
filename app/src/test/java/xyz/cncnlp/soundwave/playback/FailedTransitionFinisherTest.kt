package xyz.cncnlp.soundwave.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 可靠性 F3：失败终止只收尾当前事务；过期事务不得释放新事务的唤醒锁或暂停新歌。 */
class FailedTransitionFinisherTest {
    private class Fake {
        var intent = 1
        var wakeHeld = true
        var paused = false
        var playingSnapshot = true
        val queued = ArrayDeque<() -> Unit>()
        val finisher = FailedTransitionFinisher(
            isIntentCurrent = { it == intent },
            pausePlayer = { paused = true },
            releaseTransitionWake = { wakeHeld = false },
            publishStopped = { playingSnapshot = false },
            runOnPlayerThread = { queued.addLast(it) }
        )
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst()() }
    }

    @Test
    fun repeatedFailureStopPausesPublishesFalseAndReleasesWake() {
        val f = Fake()
        assertTrue(f.finisher.finish(1))
        f.drain()
        assertTrue(f.paused)
        assertFalse(f.playingSnapshot)
        assertFalse(f.wakeHeld)
    }

    @Test
    fun staleFailureBeforeDispatchDoesNothing() {
        val f = Fake()
        f.intent = 2 // 用户已点新歌：新事务持有锁
        assertFalse(f.finisher.finish(1))
        f.drain()
        assertFalse(f.paused)
        assertTrue(f.playingSnapshot)
        assertTrue(f.wakeHeld)
    }

    @Test
    fun intentChangingBetweenDispatchAndRunDoesNotReleaseTheNewTransactionsWake() {
        val f = Fake()
        assertTrue(f.finisher.finish(1))
        f.intent = 2 // 排队期间新意图开始并重新持锁
        f.wakeHeld = true
        f.drain()
        assertFalse(f.paused)
        assertTrue(f.wakeHeld)
        assertTrue(f.playingSnapshot)
    }

    @Test
    fun wakeIsReleasedEvenIfPausingThePlayerThrows() {
        var released = 0
        val finisher = FailedTransitionFinisher(
            isIntentCurrent = { true },
            pausePlayer = { throw IllegalStateException("player released") },
            releaseTransitionWake = { released++ },
            publishStopped = {},
            runOnPlayerThread = { runCatching(it) }
        )
        finisher.finish(7)
        assertEquals(1, released)
    }
}
