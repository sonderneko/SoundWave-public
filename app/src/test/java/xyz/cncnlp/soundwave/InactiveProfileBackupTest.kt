package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class InactiveProfileBackupTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var originalLegacyPreferences: Map<String, *>
    private val touchedProfilePreferenceNames = linkedSetOf<String>()

    @Before
    fun clearLegacyStore() {
        originalLegacyPreferences = LocalStore.prefs(context).all.toMap()
        LocalStore.prefs(context).edit().clear().commit()
    }

    @After
    fun restoreStores() {
        LocalStore.prefs(context).edit().clear().commit()
        restore(LocalStore.prefs(context), originalLegacyPreferences)
        touchedProfilePreferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        touchedProfilePreferenceNames.clear()
    }

    @Test
    fun serializerEmitsOnlyVersionedAllowlistedSchemaAndRetainsRequiredData() {
        val song = songJson("favorite-01", "Private song")
            .put("playedAt", 99L)
            .put("_swAddedAt", 41L)
            .put("resolvedUrl", "file:///private/download.mp3")
        val playlist = JSONObject()
            .put("id", "playlist-01")
            .put("nm", "Road mix")
            .put("name", "Road mix")
            .put("cv", "https://images.example.com/covers/mix.jpg")
            .put("cover", "https://images.example.com/covers/mix.jpg")
            .put("local", true)
            .put("src", "wy")
            .put("source", "wy")
            .put("hid", true)
            .put("hidden", true)
            .put("remoteId", "remote-mix-01")
            .put("favorited", true)
            .put("count", 900)
            .put("playCount", 901L)
            .put("userPlayCount", 902L)
            .put("_swCreatedAt", 10L)
            .put("_swLibOrder", 2)
            .put("_swLibOrderAt", 12L)
            .put("author", "not-needed-author")
            .put("description", "not-needed-description")
            .put("songs", JSONArray().put(songJson("playlist-song", "Playlist song")))
        val bundle = JSONObject()
            .put("favs", JSONArray().put(song))
            .put("pls", JSONArray()
                .put(playlist)
                .put(JSONObject().put("_swDeletedPlaylists", JSONObject().put("deleted-playlist", 77L)))
                .put(JSONObject().put("_swDeletedPlaylistSongs", JSONObject().put(
                    "playlist-01", JSONObject().put("wy:removed-song", 78L)
                )))
            )
            .put("enabledScripts", JSONArray())
            .put("scriptOrder", JSONArray())
            .put("history", JSONArray())
            .put("avatar", "SHOULD_NOT_EXPORT_AVATAR")
            .put("profileId", "SHOULD_NOT_EXPORT_PROFILE_ID")
        val playedAt = System.currentTimeMillis()
        val independentHistory = JSONArray().put(songJson("history-01", "Played song").put("playedAt", playedAt))

        val root = JSONObject(String(InactiveProfileBackupSerializer.serialize(bundle, independentHistory), StandardCharsets.UTF_8))
        assertEquals(setOf("schema", "version", "data"), keys(root))
        assertEquals(InactiveProfileBackupSerializer.SCHEMA, root.getString("schema"))
        assertEquals(InactiveProfileBackupSerializer.VERSION, root.getInt("version"))
        val data = root.getJSONObject("data")
        assertEquals(setOf("favs", "pls", "history"), keys(data))

        val favorite = data.getJSONArray("favs").getJSONObject(0)
        assertEquals(setOf("id", "name", "singer", "src", "cover", "album", "albumId", "duration", "playedAt", "_swAddedAt"), keys(favorite))
        assertEquals("favorite-01", favorite.getString("id"))
        assertEquals("wy", favorite.getString("src"))
        assertEquals(41L, favorite.getLong("_swAddedAt"))
        assertFalse(favorite.has("resolvedUrl"))

        val playlists = data.getJSONArray("pls")
        val safePlaylist = playlists.getJSONObject(0)
        assertEquals(
            setOf("id", "songs", "nm", "cv", "local", "src", "hid", "remoteId", "favorited", "_swCreatedAt", "_swLibOrder", "_swLibOrderAt"),
            keys(safePlaylist)
        )
        assertEquals("Road mix", safePlaylist.getString("nm"))
        assertEquals("https://images.example.com/covers/mix.jpg", safePlaylist.getString("cv"))
        assertEquals(2, safePlaylist.getInt("_swLibOrder"))
        assertEquals(12L, safePlaylist.getLong("_swLibOrderAt"))
        assertEquals(77L, playlists.getJSONObject(1).getJSONObject("_swDeletedPlaylists").getLong("deleted-playlist"))
        assertEquals(78L, playlists.getJSONObject(2).getJSONObject("_swDeletedPlaylistSongs").getJSONObject("playlist-01").getLong("wy:removed-song"))

        val history = data.getJSONArray("history").getJSONObject(0)
        assertEquals("history-01", history.getString("id"))
        assertEquals(playedAt, history.getLong("playedAt"))
        assertFalse(history.has("_swAddedAt"))
        assertFalse(root.toString().contains("SHOULD_NOT_EXPORT"))
        assertFalse(root.toString().contains("not-needed-author"))
        assertFalse(root.toString().contains("not-needed-description"))
    }

    @Test
    fun serializerDropsExpiredAndUnknownTimestampHistoryButKeepsRecentHistory() {
        val now = System.currentTimeMillis()
        val bundle = JSONObject()
            .put("favs", JSONArray())
            .put("pls", JSONArray())
            .put("enabledScripts", JSONArray())
            .put("scriptOrder", JSONArray())
        val history = JSONArray()
            .put(songJson("expired-history", "Expired").put("playedAt", now - PlaybackHistoryPolicy.RETENTION_MS - 1L))
            .put(songJson("unknown-history", "Unknown").put("playedAt", 0L))
            .put(songJson("recent-history", "Recent").put("playedAt", now))

        val root = JSONObject(String(InactiveProfileBackupSerializer.serialize(bundle, history), StandardCharsets.UTF_8))
        val retained = root.getJSONObject("data").getJSONArray("history")

        assertEquals(1, retained.length())
        assertEquals("recent-history", retained.getJSONObject(0).getString("id"))
    }

    @Test
    fun serializerStripsCredentialsPathsOriginsAndUnsafeCoverUris() {
        val dangerousSong = songJson("song-02", "Fixture title")
            .put("src", "private-server.example.com")
            .put("source", "private-server.example.com")
            .put("cover", "https://img.example.com/cover.jpg?token=SIGNED_TOKEN")
            .put("resolvedUrl", "file:///private/audio.mp3")
            .put("localUri", "content://private.provider/tree/secret")
            .put("filePath", "/private/downloads/song.mp3")
            .put("password", "DO_NOT_EXPORT_PASSWORD")
            .put("token", "DO_NOT_EXPORT_TOKEN")
            .put("username", "DO_NOT_EXPORT_USERNAME")
            .put("serverOrigin", "https://DO_NOT_EXPORT_ORIGIN.example.com")
            .put("vaultText", "DO_NOT_EXPORT_VAULT")
            .put("scriptCode", "DO_NOT_EXPORT_SCRIPT")
        val playlist = JSONObject()
            .put("id", "playlist-02")
            .put("nm", "Fixture playlist")
            .put("cv", "https://user:password@img.example.com/cover.jpg")
            .put("local", true)
            .put("src", "wy")
            .put("songs", JSONArray().put(songJson("song-03", "Fragment cover")
                .put("cover", "https://img.example.com/cover.jpg#secret-fragment"))
                .put(songJson("song-04", "Local cover").put("cover", "content://provider/cover"))
                .put(songJson("song-05", "Loopback cover").put("cover", "http://127.0.0.1/cover.jpg"))
                .put(songJson("song-05b", "Alternate loopback cover").put("cover", "http://0177.0.0.1/cover.jpg"))
                .put(songJson("song-06", "Queryless cover").put("cover", "https://cdn.example.org/artwork.jpg")))
        val bundle = JSONObject()
            .put("favs", JSONArray().put(dangerousSong))
            .put("pls", JSONArray().put(playlist))
            .put("enabledScripts", JSONArray())
            .put("scriptOrder", JSONArray())
            .put("history", JSONArray())
            .put("account", "DO_NOT_EXPORT_ACCOUNT")
            .put("serverOrigin", "https://DO_NOT_EXPORT_ROOT_ORIGIN.example.com")
        val output = JSONObject(String(
            InactiveProfileBackupSerializer.serialize(bundle, JSONArray().put(dangerousSong)),
            StandardCharsets.UTF_8
        ))
        val text = output.toString()
        for (secret in listOf(
            "DO_NOT_EXPORT_PASSWORD", "DO_NOT_EXPORT_TOKEN", "DO_NOT_EXPORT_USERNAME", "DO_NOT_EXPORT_ORIGIN",
            "DO_NOT_EXPORT_VAULT", "DO_NOT_EXPORT_SCRIPT", "DO_NOT_EXPORT_ACCOUNT", "SIGNED_TOKEN",
            "private-server.example.com", "private/audio.mp3", "private.provider", "private/downloads"
        )) assertFalse("unexpected sensitive value: $secret", text.contains(secret))

        val exportedSong = output.getJSONObject("data").getJSONArray("favs").getJSONObject(0)
        assertFalse(exportedSong.has("src"))
        assertFalse(exportedSong.has("cover"))
        assertFalse(exportedSong.has("resolvedUrl"))
        assertFalse(exportedSong.has("localUri"))
        assertFalse(exportedSong.has("filePath"))

        val exportedPlaylist = output.getJSONObject("data").getJSONArray("pls").getJSONObject(0)
        assertFalse(exportedPlaylist.has("cv"))
        val exportedSongs = exportedPlaylist.getJSONArray("songs")
        for (index in 0..3) assertFalse(exportedSongs.getJSONObject(index).has("cover"))
        assertEquals("https://cdn.example.org/artwork.jpg", exportedSongs.getJSONObject(4).getString("cover"))

        val domainIdentityBundle = JSONObject(bundle.toString())
        domainIdentityBundle.getJSONArray("favs").getJSONObject(0).put("id", "private-server.example.com")
        assertNotNull(runCatching {
            InactiveProfileBackupSerializer.serialize(domainIdentityBundle, JSONArray())
        }.exceptionOrNull())
    }

    @Test
    fun exportRevalidatesProfileAndDoesNotWriteToProfileOrLegacyPreferences() {
        val filesDir = Files.createTempDirectory("inactive-profile-export").toFile()
        try {
            val profileId = importProfile(filesDir)
            LocalStore.save(context, LocalStore.UserBundle(favs = mutableListOf(Song("legacy-fav", "Legacy", "Artist", "wy"))))
            val profilePreferences = context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)
            val profileBefore = profilePreferences.all.toMap()
            val legacyBefore = LocalStore.prefs(context).all.toMap()

            val bytes = LocalStore.exportUnactivatedProfileBackup(context, profileId)
            val text = String(bytes, StandardCharsets.UTF_8)
            assertTrue(text.contains(InactiveProfileBackupSerializer.SCHEMA))
            assertFalse(text.contains(profileId.value))
            assertFalse(text.contains("snapshot-origin.example.com"))
            assertFalse(text.contains("private-user"))
            assertEquals(profileBefore, profilePreferences.all)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)
            assertEquals(listOf("legacy-fav"), LocalStore.load(context).favs.map { it.id })
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun staleMetadataUnexpectedKeysAndDamagedContentsAreRejectedWithoutMutation() {
        val filesDir = Files.createTempDirectory("inactive-profile-stale-export").toFile()
        try {
            val profileId = importProfile(filesDir)
            val profilePreferences = context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)
            val legacyBefore = LocalStore.prefs(context).all.toMap()

            profilePreferences.edit().putString("snapshot_import_status_v1", "ACTIVE").commit()
            assertRejectedWithoutMutation(profileId, profilePreferences, legacyBefore)

            profilePreferences.edit().putString("snapshot_import_status_v1", "IMPORTED_LOCAL_NOT_ACTIVE").putString("unexpected_key", "fixture").commit()
            assertRejectedWithoutMutation(profileId, profilePreferences, legacyBefore)

            profilePreferences.edit().remove("unexpected_key").putString("user_data_v1", "{broken").commit()
            assertRejectedWithoutMutation(profileId, profilePreferences, legacyBefore)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun safCancellationAndWriteOrCloseFailuresLeaveBothStoresUntouched() {
        val filesDir = Files.createTempDirectory("inactive-profile-saf-export").toFile()
        try {
            val profileId = importProfile(filesDir)
            LocalStore.save(context, LocalStore.UserBundle(favs = mutableListOf(Song("legacy-fav", "Legacy", "Artist", "wy"))))
            val profilePreferences = context.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)
            val payload = LocalStore.exportUnactivatedProfileBackup(context, profileId)
            val profileBefore = profilePreferences.all.toMap()
            val legacyBefore = LocalStore.prefs(context).all.toMap()

            var opened = false
            val cancelled = InactiveProfileBackupDocumentWriter.writeSelectedDestination(null, payload) {
                opened = true
                ByteArrayOutputStream()
            }
            assertFalse(cancelled)
            assertFalse(opened)
            assertEquals(profileBefore, profilePreferences.all)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)

            val destination = Uri.parse("content://fixture.documents/document/1")
            val writeFailure = runCatching {
                InactiveProfileBackupDocumentWriter.writeSelectedDestination(destination, payload) {
                    object : OutputStream() {
                        override fun write(value: Int) { throw IOException("fixture write failure") }
                    }
                }
            }.exceptionOrNull()
            assertNotNull(writeFailure)
            assertTrue(writeFailure is IOException)
            assertEquals(profileBefore, profilePreferences.all)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)

            val closeFailure = runCatching {
                InactiveProfileBackupDocumentWriter.writeSelectedDestination(destination, payload) {
                    object : ByteArrayOutputStream() {
                        override fun close() { throw IOException("fixture close failure") }
                    }
                }
            }.exceptionOrNull()
            assertNotNull(closeFailure)
            assertTrue(closeFailure is IOException)
            assertEquals(profileBefore, profilePreferences.all)
            assertEquals(legacyBefore, LocalStore.prefs(context).all)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun assertRejectedWithoutMutation(
        profileId: ProfileId,
        preferences: SharedPreferences,
        legacyBefore: Map<String, *>
    ) {
        val before = preferences.all.toMap()
        val failure = runCatching { LocalStore.exportUnactivatedProfileBackup(context, profileId) }.exceptionOrNull()
        assertNotNull("stale or damaged profile must be rejected", failure)
        assertEquals(before, preferences.all)
        assertEquals(legacyBefore, LocalStore.prefs(context).all)
        assertEquals(emptyList<String>(), LocalStore.load(context).favs.map { it.id })
    }

    private fun importProfile(filesDir: java.io.File): ProfileId {
        val data = JSONObject()
            .put("favs", JSONArray().put(songJson("favorite-01", "Imported favorite").put("cover", "https://images.example.com/favorite.jpg")))
            .put("pls", JSONArray().put(
                JSONObject()
                    .put("id", "mix-01")
                    .put("nm", "Mix")
                    .put("songs", JSONArray().put(songJson("mix-song-01", "Mix song")))
            ))
            .put("history", JSONArray().put(songJson("history-01", "Imported history").put("playedAt", System.currentTimeMillis())))
        // 服务器快照子系统已随账号波次删除：夹具改走本机备份导入（同一未启用资料写入路径）。
        val envelope = JSONObject()
            .put("schema", InactiveProfileBackupSerializer.SCHEMA)
            .put("version", InactiveProfileBackupSerializer.VERSION)
            .put("data", data)
        val prepared = LocalProfileBackupImporter.parse(envelope.toString().toByteArray(Charsets.UTF_8))
        val profileId = ProfileId.of("localbackup_" + java.util.UUID.randomUUID().toString().replace("-", ""))
        touchedProfilePreferenceNames += profileId.preferencesName
        LocalStore.importLocalBackupToUnactivatedProfile(context, profileId, prepared)
        return profileId
    }

    private fun songJson(id: String, name: String) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("singer", "Fixture artist")
        .put("src", "wy")
        .put("cover", "https://images.example.com/$id.jpg")
        .put("album", "Fixture album")
        .put("albumId", "album-01")
        .put("duration", 210_000L)

    private fun keys(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

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
