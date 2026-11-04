package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileContext

/** Authored before rollback implementation; JVM execution deferred. */
@RunWith(RobolectricTestRunner::class)
class InactiveRollbackBoundaryTest {
    @Test fun unknownOrLegacyOwnerNeverDeletesAnyNamespace() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(LocalStore.rollbackInactiveLocalCopy(app, ProfileContext.legacy(0)))
        assertFalse(LocalStore.rollbackInactiveLocalCopy(app, ProfileContext.recorded(ProfileId.of("unknown-copy"), 0)))
    }
}
