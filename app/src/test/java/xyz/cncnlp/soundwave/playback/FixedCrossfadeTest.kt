package xyz.cncnlp.soundwave.playback

import org.junit.Assert.*
import org.junit.Test

class FixedCrossfadeTest {
    private class Voice(var playing: Boolean = true, var volume: Float = 1f, var releases: Int = 0)
    private class Harness {
        var time = 0L
        var scale = 1f
        val old = Voice()
        val next = Voice()
        var current = next
        val tasks = mutableListOf<Pair<Long, Runnable>>()
        val fade = FixedCrossfade<Voice>(
            now = { time }, schedule = { r, delay -> tasks += time + delay to r },
            unschedule = { r -> tasks.removeAll { it.second === r } },
            isCurrent = { it === current }, isPlaying = { it.playing }, volumeScale = { scale },
            setVolume = { p, v -> check(p.releases == 0) { "touching a released player" }; p.volume = v },
            release = { it.releases++; it.playing = false }
        )
        fun advance(ms: Long) {
            val end = time + ms
            while (tasks.isNotEmpty() && tasks.minOf { it.first } <= end) {
                val nextTask = tasks.minBy { it.first }
                tasks.remove(nextTask)
                time = nextTask.first
                nextTask.second.run()
            }
            time = end
        }
    }

    @Test fun disabledReleasesOldAndStartsAtFullVolume() {
        val h = Harness()
        h.fade.start(h.old, h.next, 0)
        assertEquals(1, h.old.releases)
        assertEquals(1f, h.next.volume, 0f)
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun fixedLinearFadeReachesBothEndpointsAndReleasesOnce() {
        val h = Harness()
        h.fade.start(h.old, h.next, 1_000)
        assertEquals(1f, h.old.volume, 0f)
        assertEquals(0f, h.next.volume, 0f)
        h.advance(520)
        assertEquals(0.48f, h.next.volume, 0.001f)
        assertEquals(1f, h.old.volume + h.next.volume, 0.001f)
        h.advance(1_000)
        assertEquals(1, h.old.releases)
        assertEquals(1f, h.next.volume, 0f)
        assertFalse(h.fade.active)
        assertTrue(h.tasks.isEmpty())
    }

    @Test fun incomingStartupDoesNotFadeOutgoingUntilActuallyPlaying() {
        val h = Harness()
        h.next.playing = false
        h.fade.start(h.old, h.next, 1_000)
        h.advance(800)
        assertEquals(1f, h.old.volume, 0f)
        assertEquals(0f, h.next.volume, 0f)
        h.next.playing = true
        h.advance(520)
        assertEquals(0.48f, h.next.volume, 0.001f)
    }

    @Test fun startupTimeoutCannotLeaveAnOrphanedOutgoingPlayer() {
        val h = Harness()
        h.next.playing = false
        h.fade.start(h.old, h.next, 3_000)
        h.advance(1_600)
        assertEquals(0, h.old.releases)
        assertTrue(h.fade.retainingOutgoing)
        h.advance(3_000)
        assertEquals(1, h.old.releases)
        assertEquals(1f, h.next.volume, 0f)
        assertFalse(h.fade.active)
    }

    @Test fun duckingAppliesToBothGainsWithoutJumpingToFullVolume() {
        val h = Harness()
        h.fade.start(h.old, h.next, 1_000)
        h.advance(520)
        h.scale = 0.2f
        h.fade.refreshVolume()
        assertEquals(0.2f, h.old.volume + h.next.volume, 0.001f)
        h.advance(1_000)
        assertEquals(0.2f, h.next.volume, 0f)
    }

    @Test fun pauseStopSeekErrorAndShutdownCancellationInvalidatesEvenQueuedCallbacks() {
        repeat(5) {
            val h = Harness()
            h.fade.start(h.old, h.next, 3_000)
            h.advance(500)
            val alreadyQueued = h.tasks.first().second
            h.fade.cancel()
            h.next.playing = false
            alreadyQueued.run()
            h.advance(5_000)
            assertEquals(1, h.old.releases)
            assertEquals(1f, h.next.volume, 0f)
            assertFalse(h.next.playing)
            assertFalse(h.fade.active)
            assertTrue(h.tasks.isEmpty())
        }
    }

    @Test fun rapidSkipCannotChangeOrReleaseTheNewCurrentPlayer() {
        val h = Harness()
        h.fade.start(h.old, h.next, 3_000)
        h.advance(300)
        val stale = h.tasks.first().second
        h.fade.cancel()
        val third = Voice()
        h.current = third
        h.fade.start(h.next, third, 3_000)
        stale.run()
        h.advance(4_000)
        assertEquals(1, h.old.releases)
        assertEquals(1, h.next.releases)
        assertEquals(0, third.releases)
        assertEquals(1f, third.volume, 0f)
    }

    @Test fun bufferingPreservesOutgoingUntilIncomingActuallyResumes() {
        val h = Harness()
        h.fade.start(h.old, h.next, 3_000)
        h.advance(400)
        h.next.playing = false
        h.advance(40)
        assertEquals(0, h.old.releases)
        assertEquals(1f, h.old.volume, 0f)
        assertTrue(h.fade.retainingOutgoing)
        h.next.playing = true
        h.advance(40)
        assertEquals(1, h.old.releases)
        assertEquals(1f, h.next.volume, 0f)
    }

    @Test fun incomingErrorKeepsTailAudibleButExplicitPauseStillReleasesIt() {
        val h = Harness()
        h.fade.start(h.old, h.next, 3_000)
        h.advance(400)
        h.next.playing = false
        h.fade.holdOutgoingForRecovery()
        h.advance(1_000)
        assertEquals(0, h.old.releases)
        assertEquals(1f, h.old.volume, 0f)
        h.scale = 0.2f
        h.fade.refreshVolume()
        assertEquals(0.2f, h.old.volume, 0f)
        h.fade.cancel()
        h.advance(5_000)
        assertEquals(1, h.old.releases)
        assertEquals(0.2f, h.next.volume, 0f)
    }

    @Test fun outgoingNaturalEndWhileRecoveringReleasesItsResources() {
        val h = Harness()
        h.fade.start(h.old, h.next, 3_000)
        h.next.playing = false
        h.fade.holdOutgoingForRecovery()
        h.old.playing = false
        h.advance(40)
        assertEquals(1, h.old.releases)
        assertFalse(h.fade.active)
    }

    @Test fun ownershipChangeCannotWriteToStaleIncoming() {
        val h = Harness()
        h.fade.start(h.old, h.next, 3_000)
        h.current = Voice()
        h.advance(40)
        assertEquals(1, h.old.releases)
        assertEquals(0f, h.next.volume, 0f)
        assertFalse(h.fade.active)
    }

    private fun eligible(
        duration: Long = 180_000, position: Long = 176_000, next: Long = 120_000,
        fade: Long = 5_000, speed: Float = 1f, playing: Boolean = true,
        prepared: Boolean = true, sequential: Boolean = true, blocked: Boolean = false
    ) = FixedCrossfadePolicy.shouldStart(duration, position, next, fade, speed, playing, prepared, sequential, blocked)

    @Test fun earlyTriggerRequiresRealOverlapAndKnownLongEnoughTracks() {
        assertTrue(eligible())
        assertFalse(eligible(fade = 0))
        assertFalse(eligible(position = 170_000))
        assertFalse(eligible(position = 180_000))
        assertFalse(eligible(duration = -1))
        assertFalse(eligible(next = -1))
        assertFalse(eligible(duration = 8_000, position = 6_000))
        assertFalse(eligible(next = 8_000))
    }

    @Test fun pauseUnreadySleepHoldStaleAndNonSequentialNeverPrecut() {
        assertFalse(eligible(playing = false))
        assertFalse(eligible(prepared = false))
        assertFalse(eligible(sequential = false))
        assertFalse(eligible(blocked = true))
    }

    @Test fun playbackSpeedChangesMediaWindowAndClampsDurationToRemainingAudio() {
        assertTrue(eligible(position = 172_000, speed = 2f))
        assertFalse(eligible(position = 176_000, speed = 0.5f))
        assertFalse(eligible(speed = Float.NaN))
        assertEquals(2_000L, FixedCrossfadePolicy.duration(5_000, 4_000, 2f))
        assertEquals(5_000L, FixedCrossfadePolicy.duration(5_000, 20_000, 1f))
        assertEquals(0L, FixedCrossfadePolicy.duration(5_000, -1, 1f))
    }
}
