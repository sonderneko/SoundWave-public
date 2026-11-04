package xyz.cncnlp.soundwave

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import xyz.cncnlp.soundwave.playback.PlaybackMetadataWork

class PlaybackMetadataWorkTest {
    @Test fun blockedMetadataDoesNotBlockCallerAndStaleReadIsDiscarded() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = AtomicBoolean(true)
        val caller = Thread.currentThread()
        var workThread: Thread? = null
        val pending = async {
            PlaybackMetadataWork.runIfCurrent(current::get) {
                workThread = Thread.currentThread()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "old history"
            }
        }
        yield()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNotSame(caller, workThread)
            // Caller event loop remains runnable while storage is deliberately blocked.
            var navigation = false
            launch { navigation = true; current.set(false) }.join()
            assertTrue(navigation)
            assertFalse(pending.isCompleted)
        } finally { release.countDown() }
        assertNull(pending.await())
    }
    @Test fun staleWorkDoesNotTouchStorage() = runBlocking {
        var writes = 0
        assertNull(PlaybackMetadataWork.runIfCurrent({ false }) { ++writes })
        assertEquals(0, writes)
    }
    @Test fun currentWorkReturnsSnapshot() = runBlocking {
        assertEquals("current", PlaybackMetadataWork.runIfCurrent({ true }) { "current" })
    }
}
