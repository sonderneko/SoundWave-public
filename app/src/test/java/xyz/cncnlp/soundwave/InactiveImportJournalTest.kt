package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Crash quarantine tests authored before journal integration. JVM NOT RUN. */
@RunWith(RobolectricTestRunner::class)
class InactiveImportJournalTest {
    @Test fun rollbackJournalPhasesAreExplicitAndResumable() {
        assertEquals(listOf("INTENT", "REGISTRY_REMOVED", "AUXILIARY_CLEARED", "CORE_CLEARED"),
            InactiveRollbackJournal.Phase.values().map { it.name })
    }
    @Test fun preparedImportIsQuarantinedUntilExactCompletion() {
        val app = RuntimeEnvironment.getApplication()
        val id = ProfileId.of("journal-test-one")
        assertTrue(InactiveImportJournal.prepare(app, id))
        assertFalse(InactiveImportJournal.isAvailable(app, id))
        assertTrue(InactiveImportJournal.complete(app, id))
        assertTrue(InactiveImportJournal.isAvailable(app, id))
    }
    @Test fun corruptJournalNeverMeansAbsentOrAvailable() {
        val app = RuntimeEnvironment.getApplication()
        val id = ProfileId.of("journal-test-corrupt")
        app.getSharedPreferences("soundwave_inactive_import_journal", 0).edit().putString(id.value, "broken").commit()
        assertFalse(InactiveImportJournal.isAvailable(app, id))
    }
}
