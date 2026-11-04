package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class UnactivatedProfilePreviewTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var originalLegacyPreferences: Map<String, *>
    private val touchedProfilePreferenceNames = linkedSetOf<String>()

    @Before
    fun snapshotAndClearLegacyPreferences() {
        originalLegacyPreferences = LocalStore.prefs(context).all.toMap()
        LocalStore.prefs(context).edit().clear().commit()
    }

    @After
    fun restorePreferences() {
        LocalStore.prefs(context).edit().clear().commit()
        restore(LocalStore.prefs(context), originalLegacyPreferences)
        touchedProfilePreferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        touchedProfilePreferenceNames.clear()
    }

    @Test
    fun previewReadsOnlyVerifiedInactiveProfileAndDoesNotWriteEitherStore() {
        val filesDir = Files.createTempDirectory("unactivated-profile-preview").toFile()
        try {
            val profileId = importProfile(filesDir)
            LocalStore.save(
                context,
                LocalStore.UserBundle(favs = mutableListOf(Song("legacy-fav", "Legacy", "Artist", "wy")))
            )
            LocalStore.pushHistory(context, Song("legacy-history", "Legacy history", "Artist", "wy", playedAt = System.currentTimeMillis()))
            val profilePreferences = context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)
            val profileBefore = profilePreferences.all.toMap()
            val legacyBefore = LocalStore.prefs(context).all.toMap()

            val preview = LocalStore.readUnactivatedProfilePreview(context, profileId)

            assertEquals(1, preview.favoriteCount)
            assertEquals(1, preview.playlistCount)
            assertEquals(1, preview.playlistSongCount)
            assertEquals(1, preview.historyCount)
            assertEquals(listOf("Mix"), preview.playlistNames)
            assertEquals(listOf("Imported favorite — Artist"), preview.songNames)
            assertEquals(profileBefore, profilePreferences.all)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)
            assertEquals(listOf("legacy-fav"), LocalStore.load(context).favs.map { it.id })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun missingOrCorruptProfileFailsInsteadOfReturningLegacyOrEmptyData() {
        LocalStore.save(
            context,
            LocalStore.UserBundle(favs = mutableListOf(Song("legacy-fav", "Legacy", "Artist", "wy")))
        )
        val legacyBefore = LocalStore.prefs(context).all.toMap()
        val missingProfileId = ProfileId.of("missing-imported-profile")
        touchedProfilePreferenceNames += missingProfileId.preferencesName

        val missingFailure = runCatching {
            LocalStore.readUnactivatedProfilePreview(context, missingProfileId)
        }.exceptionOrNull()
        assertTrue("missing import metadata must be an error", missingFailure is IllegalArgumentException)

        val filesDir = Files.createTempDirectory("unactivated-profile-preview-corrupt").toFile()
        try {
            val profileId = importProfile(filesDir)
            val profilePreferences = context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)
            profilePreferences.edit().putString("user_data_v1", "{broken").commit()
            val corruptFailure = runCatching {
                LocalStore.readUnactivatedProfilePreview(context, profileId)
            }.exceptionOrNull()
            assertTrue("corrupt profile JSON must be an error", corruptFailure is IllegalArgumentException)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)
            assertEquals(listOf("legacy-fav"), LocalStore.load(context).favs.map { it.id })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun invalidProfileIdentifiersAreRejectedBeforePreviewLookup() {
        val failure = runCatching { ProfileId.of("../legacy") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun importProfile(filesDir: java.io.File): ProfileId {
        val data = JSONObject()
            .put("favs", JSONArray().put(songJson("imported-favorite", "Imported favorite")))
            .put("pls", JSONArray().put(
                JSONObject()
                    .put("id", "mix")
                    .put("nm", "Mix")
                    .put("songs", JSONArray().put(songJson("mix-song", "Mix song")))
            ))
            .put("history", JSONArray().put(songJson("imported-history", "Imported history").put("playedAt", System.currentTimeMillis())))
        // 服务器快照子系统已随账号波次删除：夹具改走本机备份导入（同一未启用资料写入路径）。
        val envelope = JSONObject()
            .put("schema", InactiveProfileBackupSerializer.SCHEMA)
            .put("version", InactiveProfileBackupSerializer.VERSION)
            .put("data", data)
        val prepared = LocalProfileBackupImporter.parse(envelope.toString().toByteArray(Charsets.UTF_8))
        val profileId = ProfileId.of("localbackup_" + java.util.UUID.randomUUID().toString().replace("-", ""))
        touchedProfilePreferenceNames += profileId.preferencesName
        val metadata = LocalStore.importLocalBackupToUnactivatedProfile(context, profileId, prepared)
        assertEquals("IMPORTED_LOCAL_NOT_ACTIVE", metadata.status)
        return profileId
    }

    private fun songJson(id: String, name: String) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("singer", "Artist")
        .put("src", "wy")

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
}
