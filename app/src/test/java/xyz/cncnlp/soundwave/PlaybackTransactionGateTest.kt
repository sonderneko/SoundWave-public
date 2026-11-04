package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PlaybackTransactionGateTest {
    private data class VisiblePlaybackState(
        val songKey: String,
        val resolvedUrl: String,
        val resolving: Boolean,
        val status: String
    )

    @Test
    fun `a newer play transaction invalidates the older transaction`() {
        val gate = PlaybackTransactionGate()

        val first = gate.begin("wy:first", queueRevision = 10L)
        val second = gate.begin("wy:second", queueRevision = 11L)

        assertFalse(gate.isCurrent(first, "wy:first", 10L))
        assertTrue(gate.isCurrent(second, "wy:second", 11L))
    }

    @Test
    fun `queue revision change invalidates an otherwise current transaction`() {
        val gate = PlaybackTransactionGate()
        val transaction = gate.begin("wy:track", queueRevision = 8L)

        assertFalse(gate.isCurrent(transaction, "wy:track", 9L))
    }

    @Test
    fun `queue edit can rebase the same active song onto its new revision`() {
        val gate = PlaybackTransactionGate()
        val transaction = gate.begin("wy:track", queueRevision = 8L)

        assertTrue(gate.rebaseCurrentQueueRevision("wy:track", 8L, 9L))
        assertFalse(gate.isCurrent(transaction, "wy:track", 8L))
        assertTrue(gate.isCurrent(transaction, "wy:track", 9L))
        assertFalse(gate.rebaseCurrentQueueRevision("wy:other", 9L, 10L))
    }

    @Test
    fun `stale async search cannot rebind over a newer playback intent`() {
        val gate = PlaybackTransactionGate()
        val search = gate.begin("wy:current", queueRevision = 10L)
        val newerIntent = gate.begin("wy:newer", queueRevision = 11L)

        assertEquals(null, gate.rebindIfCurrent(search, "wy:current", 10L, "wy:late-similar"))
        assertTrue(gate.isCurrent(newerIntent, "wy:newer", 11L))
        assertFalse(gate.hasCurrent("wy:late-similar", 10L))
    }

    @Test
    fun `current async search can atomically bind its discovered target`() {
        val gate = PlaybackTransactionGate()
        val search = gate.begin("wy:current", queueRevision = 10L)

        val target = requireNotNull(gate.rebindIfCurrent(search, "wy:current", 10L, "wy:similar"))

        assertFalse(gate.isCurrent(search, "wy:current", 10L))
        assertTrue(gate.isCurrent(target, "wy:similar", 10L))
    }

    @Test
    fun `a transaction requires its original song identity`() {
        val gate = PlaybackTransactionGate()
        val transaction = gate.begin("wy:first", queueRevision = 3L)

        assertFalse(gate.isCurrent(transaction, "wy:second", 3L))
    }

    @Test
    fun `active identity query reflects the current foreground transaction`() {
        val gate = PlaybackTransactionGate()
        gate.begin("wy:track", queueRevision = 9L)

        assertTrue(gate.hasCurrent("wy:track", 9L))
        assertFalse(gate.hasCurrent("wy:other", 9L))
        gate.cancel()
        assertFalse(gate.hasCurrent("wy:track", 9L))
    }

    @Test
    fun `cancelling current transaction invalidates it without affecting a later transaction`() {
        val gate = PlaybackTransactionGate()
        val first = gate.begin("wy:first", queueRevision = 1L)
        gate.cancel()
        val second = gate.begin("wy:second", queueRevision = 2L)

        assertFalse(gate.isCurrent(first, "wy:first", 1L))
        assertTrue(gate.isCurrent(second, "wy:second", 2L))
    }

    @Test
    fun `late resolver for A cannot overwrite B playback state`() {
        val gate = PlaybackTransactionGate()
        val revision = AtomicLong(1L)
        val visibleState = AtomicReference(
            VisiblePlaybackState(
                songKey = "wy:A",
                resolvedUrl = "",
                resolving = true,
                status = "正在解析 A"
            )
        )
        val aTicket = gate.begin("wy:A", revision.get())
        val aStarted = CountDownLatch(1)
        val allowALateResponse = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val aResolver = executor.submit {
                aStarted.countDown()
                check(allowALateResponse.await(5, TimeUnit.SECONDS))
                val resolvedA = "https://audio.example/A"
                val stateAtCommit = visibleState.get()
                if (gate.isCurrent(aTicket, stateAtCommit.songKey, revision.get())) {
                    visibleState.set(
                        stateAtCommit.copy(
                            resolvedUrl = resolvedA,
                            resolving = false,
                            status = ""
                        )
                    )
                }
            }

            assertTrue("A 取链应先进入等待态", aStarted.await(5, TimeUnit.SECONDS))

            revision.set(2L)
            val bTicket = gate.begin("wy:B", revision.get())
            val bState = VisiblePlaybackState(
                songKey = "wy:B",
                resolvedUrl = "https://audio.example/B",
                resolving = true,
                status = "正在解析 B"
            )
            visibleState.set(bState)

            allowALateResponse.countDown()
            aResolver.get(5, TimeUnit.SECONDS)

            assertEquals("迟到的 A 不得覆盖 B 的歌曲、媒体链接或处理中状态", bState, visibleState.get())
            assertFalse(gate.isCurrent(aTicket, visibleState.get().songKey, revision.get()))
            assertTrue(gate.isCurrent(bTicket, visibleState.get().songKey, revision.get()))
        } finally {
            allowALateResponse.countDown()
            executor.shutdownNow()
        }
    }
}
