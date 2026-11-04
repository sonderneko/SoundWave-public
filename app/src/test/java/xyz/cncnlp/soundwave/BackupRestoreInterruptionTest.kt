package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.util.concurrent.CancellationException

/** Calls the same BackupStore entrypoint as BackupRestorePage; failures occur in real storage calls. */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreInterruptionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun song(id: String) = Song(id, id, "fixture", "wy", playedAt = System.currentTimeMillis() - 1000)
    private fun backup() = JSONObject().put("schema", "soundwave-backup").put("version", 1)
        .put("user", JSONObject().put("favs", JSONArray().put(song("restored-favorite").toJson())))
        .put("history", JSONArray().put(song("restored-history").toJson()))
        .put("downloads", JSONArray().put(JSONObject().put("song", song("restored-download").toJson()).put("path", "/fixture/restored.mp3")))
        .put("settings", JSONObject().put("theme_mode", "dark"))
        .put("scripts", JSONObject().put("script_meta", "[{\"id\":\"fixture-script\",\"enabled\":true}]"))

    private val names = listOf("soundwave", "soundwave_scripts", "soundwave_userdata", "soundwave_legacy_local", "listen_stats_v1", "avatar_map", "backup_restore_recovery_v1")
    private lateinit var originals: Map<String, Map<String, *>>
    @After fun cleanup() {
        LocalStore.flushPendingWrites()
        originals.forEach { (name, values) ->
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            } }
            edit.commit()
        }
    }
    @Before fun prepare() {
        originals = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        LocalStore.flushPendingWrites()
        names.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        LegacyUnassignedSnapshot.capture(context)
        context.getSharedPreferences("soundwave", 0).edit().putString("theme_mode", "light")
            .putString("user", "fixture-owner").putString("pass", "fixture-local-secret").commit()
        LocalStore.save(context, LocalStore.UserBundle(favs = mutableListOf(song("old-favorite"))))
        LocalStore.replaceHistory(context, listOf(song("old-history")))
        LocalStore.addDownload(context, DownloadItem(song = song("old-download"), path = "/fixture/old.mp3", size = 0L, time = 1L))
        LocalStore.flushPendingWrites()
    }

    private fun interruptScripts(failure: RuntimeException) = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            if (name != "soundwave_scripts") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor = throw failure
            }
        }
    }

    private fun assertBlockedBeforeCredentialsOrNetwork() {
        var credentialReads = 0
        var requests = 0
        val client = WebDavSyncClient(WebDavTransport { requests++; WebDavHttpResponse(204) })
        val result = client.syncWithCredentials(LocalStore.load(context), {
            credentialReads++
            WebDavSavedCredentials("https://fixture.invalid/dav", "fixture-user", "fixture-secret")
        }, { LegacyUnassignedSnapshot.explicitManualSyncGateState(context) })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(WEB_DAV_LEGACY_DATA_BLOCKED_MESSAGE, (result as WebDavSyncResult.Failure).message)
        assertEquals(0, credentialReads)
        assertEquals(0, requests)
    }

    private fun assertInterrupted(failure: RuntimeException) {
        val json = backup()
        val input = json.toString()
        val originalSnapshot = context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, 0)
            .getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null)
        val actual = runCatching { BackupStore.restore(interruptScripts(failure), json) }.exceptionOrNull()
        assertSame("restore must not turn cancellation/failure into success", failure, actual)
        // This is intentionally NOT an atomic transaction or a claim that old data was rolled back.
        assertEquals(listOf("restored-favorite"), LocalStore.load(context).favs.map { it.id })
        assertEquals(listOf("restored-history"), LocalStore.loadHistory(context).map { it.id })
        assertEquals(listOf("restored-download"), LocalStore.loadDownloads(context).map { it.song.id })
        assertEquals("dark", context.getSharedPreferences("soundwave", 0).getString("theme_mode", null))
        assertTrue(context.getSharedPreferences("soundwave_userdata", 0)
            .getBoolean(LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY, false))
        assertEquals(LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN, LegacyUnassignedSnapshot.accountSyncGateState(context))
        assertBlockedBeforeCredentialsOrNetwork()
        assertEquals(originalSnapshot, context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, 0)
            .getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null))
        assertEquals("fixture-owner", context.getSharedPreferences("soundwave", 0).getString("user", null))
        assertEquals("fixture-local-secret", context.getSharedPreferences("soundwave", 0).getString("pass", null))
        assertEquals(input, json.toString())
        // Explicit local retry after the injected failure is removed is deterministic, with no duplicates.
        BackupStore.resume(context, BackupRestoreRecovery.review(context))
        assertEquals(listOf("restored-favorite"), LocalStore.load(context).favs.map { it.id })
        assertEquals(listOf("restored-history"), LocalStore.loadHistory(context).map { it.id })
        assertEquals(listOf("restored-download"), LocalStore.loadDownloads(context).map { it.song.id })
        assertBlockedBeforeCredentialsOrNetwork()
    }

    @Test fun lateRestoreFailureRetainsPartialStateButNeverUploadsIt() = assertInterrupted(IllegalStateException("fixture late failure"))
    @Test fun lateRestoreCancellationRetainsPartialStateAndPropagatesSameException() = assertInterrupted(CancellationException("fixture cancelled"))

    @Test fun invalidBackupPreservesAllExistingDomainsAndDoesNotStartTransport() {
        val before = BackupStore.export(context).toString()
        val json = backup().put("settings", JSONObject().put("lyrics_font_size", "invalid"))
        // Invalid schema is a deterministic validation failure, not a storage-failure simulation.
        json.put("schema", "invalid-fixture")
        assertTrue(runCatching { BackupStore.restore(context, json) }.isFailure)
        assertEquals(before, BackupStore.export(context).toString())
    }
}
