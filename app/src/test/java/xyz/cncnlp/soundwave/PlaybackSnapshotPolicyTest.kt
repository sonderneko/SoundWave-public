package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.playback.PlaybackSnapshotPolicy
import xyz.cncnlp.soundwave.playback.SnapshotSongAction

class PlaybackSnapshotPolicyTest {
    @Test
    fun `stale A snapshot during B resolution preserves B song and queue`() {
        val decision = PlaybackSnapshotPolicy.decide(
            currentSongKey = "wy:B",
            snapshotSongKey = "wy:A",
            resolving = true,
            acceptedSnapshotGeneration = 12,
            snapshotGeneration = 11,
            controllerQueueIsNotEmpty = true
        )

        assertEquals(SnapshotSongAction.IGNORE_SERVICE_CHANGE, decision.songAction)
        assertTrue(decision.preserveControllerQueue)
    }

    @Test
    fun `idle controller may hydrate and accept non-older service songs`() {
        val hydration = PlaybackSnapshotPolicy.decide(
            currentSongKey = null,
            snapshotSongKey = "wy:A",
            resolving = false,
            acceptedSnapshotGeneration = -1,
            snapshotGeneration = 0,
            controllerQueueIsNotEmpty = false
        )
        val serviceChange = PlaybackSnapshotPolicy.decide(
            currentSongKey = "wy:B",
            snapshotSongKey = "wy:C",
            resolving = false,
            acceptedSnapshotGeneration = 12,
            snapshotGeneration = 12,
            controllerQueueIsNotEmpty = true
        )

        assertEquals(SnapshotSongAction.HYDRATE_EMPTY, hydration.songAction)
        assertEquals(SnapshotSongAction.ACCEPT_SERVICE_CHANGE, serviceChange.songAction)
        assertFalse(serviceChange.preserveControllerQueue)
    }

    @Test
    fun `older service generation cannot replace a song when not resolving`() {
        val decision = PlaybackSnapshotPolicy.decide(
            currentSongKey = "wy:B",
            snapshotSongKey = "wy:A",
            resolving = false,
            acceptedSnapshotGeneration = 12,
            snapshotGeneration = 11,
            controllerQueueIsNotEmpty = false
        )

        assertEquals(SnapshotSongAction.IGNORE_SERVICE_CHANGE, decision.songAction)
    }
}
