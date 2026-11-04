package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.sync.ProfileRemoteBindings
import xyz.cncnlp.soundwave.sync.WebDavSavedCredentials
import xyz.cncnlp.soundwave.migration.ProfileContext

/** Source-only authored regression suite. No real network, accounts, files or media requests. */
@RunWith(RobolectricTestRunner::class)
class LocalFirstStorageIntegrationTest {
    private fun app() = RuntimeEnvironment.getApplication()

    @Test fun expiredLeaseCannotOpenOrWritePreferences() {
        val context = app()
        val current = ProfileWorkIdentity.runtime()
        val expired = ProfileContext.legacy(current.generation + 1L)
        // Never rotate the production singleton in JVM tests: that poisons unrelated static stores.
        assertThrows(IllegalStateException::class.java) {
            ContextPreferences.open(context, "test_stale_editor", 0, expired)
                .edit().putString("value", "late").apply()
        }
        assertFalse(context.getSharedPreferences("test_stale_editor", 0).contains("value"))
    }

    @Test fun copiedDomainsAreIndependentAndRollbackPreservesLegacyAndOtherProfile() {
        val context = app()
        val a = ProfileId.of("copy_test_a")
        val b = ProfileId.of("copy_test_b")
        val legacy = context.getSharedPreferences("soundwave", 0)
        legacy.edit().putString("play_br", "flac").putString("pass", "not-copied").commit()
        val values = SelectedDomainCopy.project(LocalCopyDomain.SETTINGS, legacy.all)
        SelectedDomainCopy.copy(context, a, mapOf(LocalCopyDomain.SETTINGS to values))
        SelectedDomainCopy.copy(context, b, mapOf(LocalCopyDomain.SETTINGS to values))
        assertTrue(SelectedDomainCopy.available(context, a))
        val pa = context.getSharedPreferences(SelectedDomainCopy.namespace(a, LocalCopyDomain.SETTINGS), 0)
        val pb = context.getSharedPreferences(SelectedDomainCopy.namespace(b, LocalCopyDomain.SETTINGS), 0)
        assertFalse(pa.getString("payload", "")!!.contains("not-copied"))
        assertTrue(SelectedDomainCopy.rollback(context, a))
        assertTrue(pa.all.isEmpty())
        assertFalse(pb.all.isEmpty())
        assertEquals("flac", legacy.getString("play_br", null))
        assertEquals("not-copied", legacy.getString("pass", null))
    }

    @Test fun occupiedDestinationIsNeverClaimedOrDeleted() {
        val context = app()
        val id = ProfileId.of("occupied_test")
        val target = context.getSharedPreferences(SelectedDomainCopy.namespace(id, LocalCopyDomain.SETTINGS), 0)
        target.edit().putString("existing", "preserve").commit()
        assertThrows(IllegalStateException::class.java) {
            SelectedDomainCopy.copy(context, id, mapOf(LocalCopyDomain.SETTINGS to mapOf("play_br" to "flac")))
        }
        assertTrue(SelectedDomainCopy.rollback(context, id))
        assertEquals("preserve", target.getString("existing", null))
    }

    @Test fun interruptedCopyRemainsQuarantinedUntilExplicitRollback() {
        val context = app()
        val id = ProfileId.of("interrupted_test")
        val journal = context.getSharedPreferences("soundwave_copy_domain_journal", 0)
        journal.edit().putString(id.value, "PREPARED:SETTINGS,STATS").commit()
        context.getSharedPreferences(SelectedDomainCopy.namespace(id, LocalCopyDomain.SETTINGS), 0)
            .edit().putString("payload", "{}").commit()
        assertFalse(SelectedDomainCopy.available(context, id))
        assertTrue(SelectedDomainCopy.rollback(context, id))
        assertFalse(journal.contains(id.value))
    }

    @Test fun unreadyProfileCannotReadMalformedCredentialsOrOptIn() {
        val context = app()
        val id = ProfileId.of("remote_not_ready")
        val token = ProfileContext.recorded(id, 1L)
        // A wrong type would throw if the credential namespace was read before readiness.
        val p = context.getSharedPreferences("soundwave_profile_webdav_${id.value}", 0)
        p.edit().putInt("binding", 3).commit()
        assertNull(ProfileRemoteBindings.load(context, token))
        assertFalse(ProfileRemoteBindings.optIn(context, token,
            WebDavSavedCredentials("https://example.invalid", "test", "test"), true))
        assertEquals(3, p.getInt("binding", -1))
    }

    @Test fun oldWorkerOwnerIsLegacyAndCannotUseRuntimeCheckpointPermission() {
        val legacy = ProfileWorkIdentity.decode(null, 0L)!!
        assertTrue(ProfileWorkIdentity.isWork(legacy))
        assertFalse(ProfileWorkIdentity.isRuntime(legacy))
        assertNull(ProfileWorkIdentity.decode(null, 9L))
        assertNull(ProfileWorkIdentity.decode("profile:other", 0L))
        assertNull(PlaybackStore.load(app(), legacy))
    }
}
