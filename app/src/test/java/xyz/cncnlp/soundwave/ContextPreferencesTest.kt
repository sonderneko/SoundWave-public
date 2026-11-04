package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.migration.RuntimeProvenanceInventory

/** Authored before preference-boundary integration; NOT executed in this source-only phase. */
@RunWith(RobolectricTestRunner::class)
class ContextPreferencesTest {
    @Test fun mutationInvalidatesBeforeUnderlyingApply() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = ContextPreferences.open(app, "soundwave_test_context", 0, ProfileWorkIdentity.runtime())
        val before = RuntimeProvenanceInventory.readEpoch(app)!!
        prefs.edit().putString("selection", "legacy").apply()
        assertTrue(RuntimeProvenanceInventory.readEpoch(app)!! > before)
        assertEquals("legacy", prefs.getString("selection", null))
    }
    @Test fun unrecordedIdentityCannotOpenOrWriteNamespace() {
        val app = RuntimeEnvironment.getApplication()
        val token = ProfileContext.recorded(ProfileId.of("not-recorded-test"), 1)
        assertThrows(IllegalStateException::class.java) {
            ContextPreferences.open(app, "soundwave", 0, token)
        }
    }
    @Test fun unknownDeviceNamespaceCannotBecomeProfileData() {
        assertNull(ContextPreferences.profileNamespace("arbitrary-device-resource", ProfileId.of("one")))
        assertNotEquals(ContextPreferences.profileNamespace("soundwave_scripts", ProfileId.of("one")),
            ContextPreferences.profileNamespace("soundwave_scripts", ProfileId.of("two")))
    }
}
