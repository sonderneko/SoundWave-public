package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class LocalProfileBackupImportTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var originalLegacyPreferences: Map<String, *>
    private lateinit var originalSnapshotPreferences: Map<String, *>
    private lateinit var originalRegistryPreferences: Map<String, *>
    private lateinit var originalJournalPreferences: Map<String, *>
    private val touchedProfilePreferenceNames = linkedSetOf<String>()

    @Before
    fun clearOnlyFixtureStores() {
        val journal = context.getSharedPreferences("soundwave_inactive_import_journal", 0)
        originalJournalPreferences = journal.all.toMap()
        journal.edit().clear().commit()
        originalLegacyPreferences = LocalStore.prefs(context).all.toMap()
        LocalStore.prefs(context).edit().clear().commit()
        val snapshot = context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
        originalSnapshotPreferences = snapshot.all.toMap()
        snapshot.edit().clear().commit()
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        originalRegistryPreferences = registry.all.toMap()
        registry.edit().clear().commit()
    }

    @After
    fun restoreFixtureStores() {
        val journal = context.getSharedPreferences("soundwave_inactive_import_journal", 0)
        journal.edit().clear().commit()
        restore(journal, originalJournalPreferences)
        LocalStore.prefs(context).edit().clear().commit()
        restore(LocalStore.prefs(context), originalLegacyPreferences)
        val snapshot = context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
        snapshot.edit().clear().commit()
        restore(snapshot, originalSnapshotPreferences)
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        registry.edit().clear().commit()
        restore(registry, originalRegistryPreferences)
        touchedProfilePreferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        touchedProfilePreferenceNames.clear()
    }

    @Test
    fun emittedVersionOneFormatParsesToAccuratePreviewAndSafeBundle() {
        val prepared = LocalProfileBackupImporter.parse(backupBytes())
        assertEquals(1, prepared.preview.favoriteCount)
        assertEquals(1, prepared.preview.playlistCount)
        assertEquals(1, prepared.preview.playlistSongCount)
        assertEquals(1, prepared.preview.historyCount)
        assertEquals(listOf("Road mix"), prepared.preview.playlistNames)
        assertEquals(listOf("Favorite song — Fixture artist"), prepared.preview.songNames)
        assertEquals(listOf("favorite-01"), prepared.bundle.favs.map { it.id })
        assertEquals("https://images.example.com/favorite-01.jpg", prepared.bundle.favs.single().cover)
        assertTrue((prepared.bundle.favs + prepared.bundle.playlists.flatMap { it.songs } + prepared.bundle.history)
            .all { it.resolvedUrl.isBlank() })
    }

    @Test
    fun historyOnlyBackupShowsHistorySamplesInParserAndPersistedInactivePreview() {
        val root = JSONObject(backupText())
        root.getJSONObject("data").put("favs", JSONArray()).put("pls", JSONArray())
        val prepared = LocalProfileBackupImporter.parse(root.toString().toByteArray(StandardCharsets.UTF_8))
        assertEquals(0, prepared.preview.favoriteCount)
        assertEquals(0, prepared.preview.playlistCount)
        assertEquals(1, prepared.preview.historyCount)
        assertEquals(listOf("Played song — Fixture artist"), prepared.preview.songNames)

        val profileId = ProfileId.of("localbackup_history_only_fixture")
        touchedProfilePreferenceNames += profileId.preferencesName
        LocalStore.importLocalBackupToUnactivatedProfile(context, profileId, prepared, "2026-10-12T12:00:00Z")
        assertEquals(prepared.preview.songNames, LocalStore.readUnactivatedProfilePreview(context, profileId).songNames)
    }

    @Test
    fun rejectsUnknownSchemasVersionsDuplicateKeysAndUnknownFields() {
        val valid = backupText()
        assertRejected(JSONObject(valid).put("schema", "soundwave-server-snapshot").toString())
        assertRejected(JSONObject(valid).put("schema", "soundwave-backup").toString())
        assertRejected(JSONObject(valid).put("version", 2).toString())
        assertRejected(JSONObject(valid).put("version", "1").toString())
        assertRejected(valid.replaceFirst("\"version\":1", "\"version\":1.0"))
        assertRejected(JSONObject(valid).put("unexpected", true).toString())

        val extraData = JSONObject(valid)
        extraData.getJSONObject("data").put("profileId", "do-not-import")
        assertRejected(extraData.toString())

        val extraSong = JSONObject(valid)
        extraSong.getJSONObject("data").getJSONArray("favs").getJSONObject(0)
            .put("resolvedUrl", "file:///private/audio.mp3")
        assertRejected(extraSong.toString())

        assertRejected(valid.replaceFirst("\"version\":1", "\"version\":1,\"version\":1"))
        assertRejected(valid.replaceFirst("\"id\":\"favorite-01\"", "\"id\":\"favorite-01\",\"id\":\"other\""))
    }

    @Test
    fun rejectsSensitiveFieldsUnsafeUrlsAndDomainLikeSourceIds() {
        val dangerousKeys = listOf("password", "token", "scriptCode", "profileId", "localUri", "filePath", "playUrl")
        for (key in dangerousKeys) {
            val root = JSONObject(backupText())
            root.getJSONObject("data").getJSONArray("favs").getJSONObject(0).put(key, "sensitive")
            assertRejected(root.toString())
        }
        for (cover in listOf(
            "https://user:secret@images.example.com/a.jpg",
            "https://images.example.com/a.jpg?token=secret",
            "https://images.example.com/a.jpg#secret",
            "http://127.0.0.1/a.jpg",
            "content://private.provider/a.jpg",
            "file:///private/a.jpg"
        )) {
            val root = JSONObject(backupText())
            root.getJSONObject("data").getJSONArray("favs").getJSONObject(0).put("cover", cover)
            assertRejected(root.toString())
        }
        val domainId = JSONObject(backupText())
        domainId.getJSONObject("data").getJSONArray("favs").getJSONObject(0).put("id", "music.example.com")
        assertRejected(domainId.toString())
    }

    @Test
    fun acceptsOnlySafePublicCoverAndBoundsInputStringsAndCollections() {
        val safe = JSONObject(backupText())
        safe.getJSONObject("data").getJSONArray("favs").getJSONObject(0)
            .put("cover", "https://images.example.com/a/../favorite.jpg")
        val parsed = LocalProfileBackupImporter.parse(safe.toString().toByteArray(StandardCharsets.UTF_8))
        assertEquals("https://images.example.com/favorite.jpg", parsed.bundle.favs.single().cover)

        val tooLongString = JSONObject(backupText())
        tooLongString.getJSONObject("data").getJSONArray("favs").getJSONObject(0).put("name", "x".repeat(4_097))
        assertRejected(tooLongString.toString())
        assertRejected(ByteArray(LocalProfileBackupImporter.MAX_INPUT_BYTES + 1))

        val tooManyHistory = JSONObject(backupText())
        val history = JSONArray()
        repeat(501) { history.put(song("history-$it", "History $it")) }
        tooManyHistory.getJSONObject("data").put("history", history)
        assertRejected(tooManyHistory.toString())
    }

    @Test
    fun previewParsingHasNoWritesAndConfirmedImportIsNewInactiveProfileScopedData() {
        LocalStore.save(context, LocalStore.UserBundle(favs = mutableListOf(Song("legacy-fav", "Legacy", "Artist", "wy"))))
        LocalStore.pushHistory(context, Song("legacy-history", "Legacy history", "Artist", "wy"))
        val oldProfile = ProfileId.of("existing-profile-for-backup-import-test")
        touchedProfilePreferenceNames += oldProfile.preferencesName
        LocalStore.save(context, oldProfile, LocalStore.UserBundle(favs = mutableListOf(Song("old-fav", "Old profile", "Artist", "wy"))))

        val legacyBefore = LocalStore.prefs(context).all.toMap()
        val oldProfilePrefs = context.getSharedPreferences(oldProfile.preferencesName, Context.MODE_PRIVATE)
        val oldProfileBefore = oldProfilePrefs.all.toMap()
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        val registryBefore = registry.all.toMap()

        val prepared = LocalProfileBackupImporter.parse(backupBytes())
        assertEquals(legacyBefore, LocalStore.prefs(context).all)
        assertEquals(oldProfileBefore, oldProfilePrefs.all)
        assertEquals(registryBefore, registry.all)

        val importedId = ProfileId.of("localbackup_fixture_7b1d941c")
        touchedProfilePreferenceNames += importedId.preferencesName
        val metadata = LocalStore.importLocalBackupToUnactivatedProfile(
            context, importedId, prepared, "2026-10-12T12:00:00Z"
        )
        assertEquals("IMPORTED_LOCAL_NOT_ACTIVE", metadata.status)
        assertEquals(InactiveProfileBackupSerializer.SCHEMA, metadata.schema)
        assertEquals(InactiveProfileBackupSerializer.VERSION, metadata.version)
        assertNull(LocalStore.importedSnapshotMetadata(context, importedId))
        assertEquals(metadata, LocalStore.importedLocalBackupMetadata(context, importedId))
        assertTrue(LocalStore.importedLocalBackupProfiles(context).containsKey(importedId))
        assertEquals(6, context.getSharedPreferences(importedId.preferencesName, Context.MODE_PRIVATE).all.size)
        assertTrue(context.getSharedPreferences(importedId.preferencesName, Context.MODE_PRIVATE).all.keys
            .none { it.startsWith("snapshot_import_") })
        assertEquals(listOf("favorite-01"), LocalStore.load(context, importedId).favs.map { it.id })
        assertEquals(listOf("history-01"), LocalStore.loadHistory(context, importedId).map { it.id })
        assertEquals(1, LocalStore.readUnactivatedProfilePreview(context, importedId).favoriteCount)
        assertEquals(1, LocalProfileBackupImporter.parse(LocalStore.exportUnactivatedProfileBackup(context, importedId)).preview.favoriteCount)
        assertEquals(legacyBefore, LocalStore.prefs(context).all)
        assertEquals(oldProfileBefore, oldProfilePrefs.all)
        assertEquals(listOf("legacy-fav"), LocalStore.load(context).favs.map { it.id })
        assertEquals(listOf("old-fav"), LocalStore.load(context, oldProfile).favs.map { it.id })
    }

    @Test
    fun profileLocalImportDoesNotReopenIncompleteLegacyGate() {
        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
        val profileId = ProfileId.of("localbackup_gate_unchanged_fixture")
        touchedProfilePreferenceNames += profileId.preferencesName

        LocalStore.importLocalBackupToUnactivatedProfile(
            context,
            profileId,
            LocalProfileBackupImporter.parse(backupBytes()),
            "2026-10-12T12:00:00Z"
        )

        assertEquals(
            LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN,
            LegacyUnassignedSnapshot.accountSyncGateState(context)
        )
        assertFalse(LocalStore.prefs(context).contains(LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY))
        assertTrue(context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE).all.isNotEmpty())
    }

    @Test
    fun occupiedProfileIdsAreNeverOverwrittenAndPartialProfileWriteRollsBack() {
        val occupied = ProfileId.of("localbackup_occupied_fixture")
        touchedProfilePreferenceNames += occupied.preferencesName
        LocalStore.save(context, occupied, LocalStore.UserBundle(favs = mutableListOf(Song("keep", "Keep", "Artist", "wy"))))
        val occupiedPrefs = context.getSharedPreferences(occupied.preferencesName, Context.MODE_PRIVATE)
        val occupiedBefore = occupiedPrefs.all.toMap()
        val prepared = LocalProfileBackupImporter.parse(backupBytes())
        val collision = runCatching { LocalStore.importLocalBackupToUnactivatedProfile(context, occupied, prepared) }.exceptionOrNull()
        assertNotNull(collision)
        assertEquals(occupiedBefore, occupiedPrefs.all)

        val rollbackId = ProfileId.of("localbackup_rollback_fixture")
        touchedProfilePreferenceNames += rollbackId.preferencesName
        val legacyBefore = LocalStore.prefs(context).all.toMap()
        val registry = context.getSharedPreferences(REGISTRY_PREFS, Context.MODE_PRIVATE)
        val registryBefore = registry.all.toMap()
        val partialFailure = runCatching {
            LocalStore.importLocalBackupToUnactivatedProfile(context, rollbackId, prepared, beforeRegistryWrite = {
                throw IllegalStateException("test-only failure before index commit")
            })
        }.exceptionOrNull()
        assertNotNull(partialFailure)
        assertTrue(context.getSharedPreferences(rollbackId.preferencesName, Context.MODE_PRIVATE).all.isEmpty())
        assertEquals(registryBefore, registry.all)
        assertEquals(legacyBefore, LocalStore.prefs(context).all)
        assertNull(LocalStore.importedLocalBackupMetadata(context, rollbackId))
    }

    private fun assertRejected(text: String) {
        val failure = runCatching { LocalProfileBackupImporter.parse(text.toByteArray(StandardCharsets.UTF_8)) }.exceptionOrNull()
        assertNotNull("Expected local-backup parser to reject the fixture", failure)
    }

    private fun assertRejected(bytes: ByteArray) {
        val failure = runCatching { LocalProfileBackupImporter.parse(bytes) }.exceptionOrNull()
        assertNotNull("Expected local-backup parser to reject the fixture", failure)
    }

    private fun backupBytes(): ByteArray = InactiveProfileBackupSerializer.serialize(
        JSONObject()
            .put("favs", JSONArray().put(song("favorite-01", "Favorite song")))
            .put("pls", JSONArray().put(
                JSONObject()
                    .put("id", "playlist-01")
                    .put("nm", "Road mix")
                    .put("src", "wy")
                    .put("songs", JSONArray().put(song("playlist-song-01", "Playlist song")))
            ))
            .put("enabledScripts", JSONArray())
            .put("scriptOrder", JSONArray())
            .put("history", JSONArray()),
        JSONArray().put(song("history-01", "Played song").put("playedAt", System.currentTimeMillis()))
    )

    private fun backupText(): String = String(backupBytes(), StandardCharsets.UTF_8)

    private fun song(id: String, name: String) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("singer", "Fixture artist")
        .put("src", "wy")
        .put("cover", "https://images.example.com/$id.jpg")
        .put("album", "Fixture album")
        .put("albumId", "album-01")
        .put("duration", 210_000L)

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    companion object {
        private const val REGISTRY_PREFS = "soundwave_local_backup_profile_index_v1"
    }
}
