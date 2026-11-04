package xyz.cncnlp.soundwave.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusCallbackGateTest {
    @Test fun queuedCallbackIsDiscardedAfterOwnerRelease() {
        val gate = AudioFocusCallbackGate()
        var applied = 0
        val queuedCallback = { gate.dispatch { applied += 1 } }

        assertTrue(gate.close())
        assertFalse(queuedCallback())
        assertEquals(0, applied)
        assertFalse(gate.close())
    }

    @Test fun activeCallbackRunsExactlyOnce() {
        val gate = AudioFocusCallbackGate()
        var applied = 0

        assertTrue(gate.dispatch { applied += 1 })
        assertEquals(1, applied)
        assertTrue(gate.close())
        assertFalse(gate.dispatch { applied += 1 })
        assertEquals(1, applied)
    }
}
