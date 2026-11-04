package xyz.cncnlp.soundwave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWordLiftPreferenceTest {
    @Test
    fun explicitLiftPreferenceWinsAndLegacyKaraokeOnlySeedsMigration() {
        assertTrue(initialLyricWordLiftPreference(explicitLift = null, karaokeEnabled = true))
        assertFalse(initialLyricWordLiftPreference(explicitLift = null, karaokeEnabled = false))
        assertTrue(initialLyricWordLiftPreference(explicitLift = true, karaokeEnabled = false))
        assertFalse(initialLyricWordLiftPreference(explicitLift = false, karaokeEnabled = true))
    }
}
