package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class Round2SafetyTest {
    @Test fun boundedCoverAcceptsExactLimitAndRejectsOversize() {
        assertArrayEquals(ByteArray(128), BoundedCoverReader.read(ByteArrayInputStream(ByteArray(128)),128))
        try { BoundedCoverReader.read(ByteArrayInputStream(ByteArray(129)),128); fail("oversize accepted") } catch (_: IOException) {}
        assertEquals(0, BoundedCoverReader.read(ByteArrayInputStream(ByteArray(0)),0).size)
    }
    @Test fun restoredImmersiveStyleIsAvailableAndLegacyValuesNormalize() {
        assertEquals(DetailCoverStyles.IMMERSIVE_HERO, DetailCoverStyles.normalize(DetailCoverStyles.IMMERSIVE))
        assertTrue(DetailCoverStyles.CHOICES.any { it.first == DetailCoverStyles.IMMERSIVE_HERO })
        assertEquals(DetailCoverStyles.IMMERSIVE_HERO, DetailCoverStyles.next(DetailCoverStyles.STANDARD))
    }
    @Test fun probeQueueAndCancellationDoNotOccupyPlaybackSlots() {
        val probeTasks = java.util.ArrayDeque<Runnable>()
        val playTasks = java.util.ArrayDeque<Runnable>()
        val probe = ScriptRequestScheduler(1,4,java.util.concurrent.Executor { probeTasks.add(it) })
        val play = ScriptRequestScheduler(8,16,java.util.concurrent.Executor { playTasks.add(it) })
        val probeOwner = Any()
        val playOwner = Any()
        var playbackDelivered = false
        repeat(5) { i -> assertTrue(probe.submit(probeOwner,"$i",{}, onFailure={ { throw it } }) { { } }) }
        assertFalse(probe.submit(probeOwner,"overflow",{}, onFailure={ { throw it } }) { { } })
        assertTrue(play.submit(playOwner,"current",{}, onFailure={ { throw it } }) { { playbackDelivered=true } })
        probe.cancelOwner(probeOwner)
        while (playTasks.isNotEmpty()) playTasks.removeFirst().run()
        assertTrue(playbackDelivered)
        assertEquals(0,play.snapshot().tracked)
    }
    @Test fun coverClientRejectsLocalTargetsAndStreamsWithAHardLimit() {
        val temp = java.io.File.createTempFile("cover-safety", ".part")
        try {
            assertFalse(CoverNetworkClient.downloadToFile("http://127.0.0.1/private",temp))
            assertFalse(CoverNetworkClient.downloadToFile("http://169.254.169.254/latest/meta-data",temp))
            assertFalse(CoverNetworkClient.downloadToFile("https://user:password@example.invalid/a",temp))
            val out=java.io.ByteArrayOutputStream()
            assertEquals(128L,CoverNetworkClient.copyLimited(ByteArrayInputStream(ByteArray(128)),out,128L))
            try { CoverNetworkClient.copyLimited(ByteArrayInputStream(ByteArray(129)),out,128L); fail("limit not enforced") } catch (_: IOException) { }
        } finally { temp.delete() }
    }
}
