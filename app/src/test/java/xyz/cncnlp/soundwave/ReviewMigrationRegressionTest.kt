package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Authored fake-storage regressions; no compiler/JVM execution is claimed. */
@RunWith(RobolectricTestRunner::class)
class ReviewMigrationRegressionTest {
    private fun app() = RuntimeEnvironment.getApplication()
    private fun prepared(): PreparedLocalProfileBackup {
        val bundle = LocalStore.UserBundle().also { it.ensureDefaults() }.toCloudJson()
        return LocalProfileBackupImporter.parse(InactiveProfileBackupSerializer.serialize(bundle, org.json.JSONArray()))
    }
    @Test fun processLeaseChangeCannotCancelDurableDownloadOrPeriodicOwner() {
        val context = app()
        val fromProcessA = ProfileWorkIdentity.decode(ProfileWorkIdentity.LEGACY_OWNER, 400L)!!
        val epoch = ProfileWorkIdentity.cancellationEpoch(context)
        assertTrue(ProfileWorkIdentity.isPersistentWork(context, fromProcessA, epoch))
        assertFalse(ProfileWorkIdentity.isRuntime(fromProcessA))
        assertNull(ProfileWorkIdentity.decode("profile:never-rebind", 400L))
    }
    @Test fun durableCancellationEpochRejectsOldWorkAndCommitAfterRestart() {
        val context = app()
        val prefs = context.getSharedPreferences("soundwave_persistent_work_identity", 0)
        val previous = prefs.getLong("cancellation_epoch", 0L)
        try {
            val owner = ProfileWorkIdentity.persistentWork()
            val original = ProfileWorkIdentity.cancellationEpoch(context)
            val next = ProfileWorkIdentity.cancelPersistentWork(context)
            assertEquals(original + 1L, next)
            assertFalse(ProfileWorkIdentity.isPersistentWork(context, owner, original))
            var wrote = false
            assertNull(ProfileWorkIdentity.commitPersistentWork(context, owner, original) { wrote = true })
            assertFalse(wrote)
            assertTrue(ProfileWorkIdentity.isPersistentWork(context,
                ProfileWorkIdentity.decode(ProfileWorkIdentity.LEGACY_OWNER, 999L)!!, next))
        } finally { prefs.edit().putLong("cancellation_epoch", previous).commit() }
    }
    @Test fun importerNeverAdoptsPreexistingAuxiliaryJournals() {
        for (phase in listOf("PREPARED", "COMPLETE")) {
            val context = app()
            val id = ProfileId.of("review_existing_${phase.lowercase()}")
            val journal = context.getSharedPreferences("soundwave_copy_domain_journal", 0)
            val payload = context.getSharedPreferences(SelectedDomainCopy.namespace(id, LocalCopyDomain.SETTINGS), 0)
            val raw = "$phase:SETTINGS"
            journal.edit().putString(id.value, raw).commit()
            payload.edit().putString("payload", "{\"play_br\":\"old\"}").commit()
            assertThrows(IllegalStateException::class.java) {
                LocalStore.importLocalBackupToUnactivatedProfile(context, id, prepared())
            }
            assertEquals(raw, journal.getString(id.value, null))
            assertEquals("{\"play_br\":\"old\"}", payload.getString("payload", null))
            assertTrue(context.getSharedPreferences(id.preferencesName, 0).all.isEmpty())
            assertFalse(context.getSharedPreferences("soundwave_inactive_import_journal", 0).contains(id.value))
        }
    }
    @Test fun rollbackResumesAfterEveryDestructivePhaseWithoutCatalogAuthorization() {
        val context = app()
        for (phase in InactiveRollbackJournal.Phase.values().filter { it != InactiveRollbackJournal.Phase.INTENT }) {
            val id = ProfileId.of("review_rollback_${phase.name.lowercase()}")
            LocalStore.importLocalBackupToUnactivatedProfile(context, id, prepared(),
                auxiliaryDomains = mapOf(LocalCopyDomain.SETTINGS to mapOf("play_br" to "flac")))
            val token = xyz.cncnlp.soundwave.migration.ProfileCatalog.inactive(context, id)!!
            assertFalse(LocalStore.rollbackInactiveLocalCopy(context, token) { reached ->
                if (reached == phase) error("fake crash after durable phase")
            })
            assertNull(xyz.cncnlp.soundwave.migration.ProfileCatalog.inactive(context, id))
            val receipt = InactiveRollbackJournal.read(context, id)!!
            assertEquals(phase, receipt.phase)
            assertTrue(receipt.token().matches(token))
            assertFalse(LocalStore.rollbackInactiveLocalCopy(context,
                xyz.cncnlp.soundwave.migration.ProfileContext.recorded(id, token.generation + 1L)))
            assertTrue(InactiveRollbackJournal.pending(context).contains(id))
            assertTrue(LocalStore.rollbackInactiveLocalCopy(context, receipt.token()))
            assertFalse(InactiveRollbackJournal.contains(context, id))
            assertTrue(context.getSharedPreferences(id.preferencesName, 0).all.isEmpty())
        }
    }
    @Test fun dailyStatsProjectionPreservesActualProducerAndCalendarActivity() {
        val context = app()
        val preferences = ContextPreferences.open(context, "listen_stats_v1", 0, ProfileWorkIdentity.runtime())
        val before = preferences.all.toMap()
        try {
            preferences.edit().clear().commit()
            val song = Song(id = "review_daily", name = "Calendar", singer = "Fake", source = "wy")
            assertTrue("daily stats must be written under the current runtime identity", ListenStats.addDuration(context, song, 45L))
            val projected = SelectedDomainCopy.project(LocalCopyDomain.STATS, preferences.all)
            val actualDays = preferences.all.filterKeys { it.startsWith("day_sec_") }
            assertTrue(actualDays.isNotEmpty())
            actualDays.forEach { (key, value) -> assertEquals(value, projected[key]) }
            assertEquals(ListenStats.monthDailySeconds(context).filter { it > 0L }.sum(),
                projected.filterKeys { it.startsWith("day_sec_") }.values.filterIsInstance<Long>().sum())
        } finally {
            val editor = preferences.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
            } }
            editor.commit()
        }
    }
    @Test fun lyricDiskKeySurvivesSessionRenewalAndRetainsFixedOwner() {
        val song = Song(id = "review_cache", name = "Offline", singer = "Fake", source = "wy")
        val a = xyz.cncnlp.soundwave.migration.ProfileContext.legacy(51L)
        val b = xyz.cncnlp.soundwave.migration.ProfileContext.legacy(91L)
        val key = LyricSourceLoader.persistentLyricKey(song, a)
        assertEquals(key, LyricSourceLoader.persistentLyricKey(song, b))
        assertNotEquals(key, LyricSourceLoader.persistentLyricKey(song,
            xyz.cncnlp.soundwave.migration.ProfileContext.recorded(ProfileId.of("other_cache"), 51L)))
        val context = app()
        org.junit.Assume.assumeTrue(
            "optional lyric cache writes require at least 192 MiB free storage",
            CacheManager.availableBytes(context) >= 192L * 1024 * 1024
        )
        CacheManager.saveLyric(context, key, "[00:01.00]cached offline lyric")
        assertEquals("[00:01.00]cached offline lyric", CacheManager.loadLyric(context, LyricSourceLoader.persistentLyricKey(song, b)))
    }
    @Test fun mismatchedAuxiliaryReceiptCannotDeleteExistingCopy() {
        val context = app()
        val id = ProfileId.of("review_aux_receipt")
        SelectedDomainCopy.copy(context, id, mapOf(LocalCopyDomain.SETTINGS to mapOf("play_br" to "flac")), "original-operation")
        assertTrue(SelectedDomainCopy.rollback(context, id, "different-operation"))
        assertTrue(SelectedDomainCopy.available(context, id))
        assertFalse(context.getSharedPreferences(SelectedDomainCopy.namespace(id, LocalCopyDomain.SETTINGS), 0).all.isEmpty())
        assertTrue(SelectedDomainCopy.rollback(context, id, "original-operation"))
    }
    @Test fun statisticsProjectionCopiesRealCalendarKeysOnly() {
        val input = mapOf("day_sec_20240229" to 40L, "day_sec_20240230" to 8L,
            "day_sec_20241301" to 8L, "day_sec_20240101" to -1L, "daily_sec_v1" to "guessed")
        assertEquals(mapOf("day_sec_20240229" to 40L), SelectedDomainCopy.project(LocalCopyDomain.STATS, input))
    }
}
