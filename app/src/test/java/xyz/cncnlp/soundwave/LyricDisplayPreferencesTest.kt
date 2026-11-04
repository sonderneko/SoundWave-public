package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.player.LyricDisplayPreferences
import xyz.cncnlp.soundwave.ui.player.resolveLyricDisplayPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayPreferencesTest {
    @Test
    fun missingPreferencesDefaultBothDisplayOptionsToOff() {
        assertEquals(
            LyricDisplayPreferences(translation = false, romanization = false),
            resolveLyricDisplayPreferences(storedTranslation = null, storedRomanization = null),
        )
    }

    @Test
    fun explicitlySavedValuesArePreservedIndependently() {
        assertEquals(
            LyricDisplayPreferences(translation = true, romanization = false),
            resolveLyricDisplayPreferences(storedTranslation = true, storedRomanization = false),
        )
        assertEquals(
            LyricDisplayPreferences(translation = false, romanization = true),
            resolveLyricDisplayPreferences(storedTranslation = false, storedRomanization = true),
        )
        assertEquals(
            LyricDisplayPreferences(translation = false, romanization = false),
            resolveLyricDisplayPreferences(storedTranslation = false, storedRomanization = false),
        )
    }
}
