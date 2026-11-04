package xyz.cncnlp.soundwave

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
class LocalHistoryRetentionTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var previous: Map<String, *>

    @Before
    fun isolateHistory() {
        previous = LocalStore.prefs(context).all.toMap()
        LocalStore.prefs(context).edit().clear().commit()
    }

    @After
    fun restoreHistory() {
        val editor = LocalStore.prefs(context).edit().clear()
        previous.forEach { (key, value) ->
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

    @Test
    fun localHistoryLoadFiltersExpiredUnknownAndDuplicateRowsAndNextWritePersistsCleanup() {
        val now = System.currentTimeMillis()
        val boundary = song("boundary", now - PlaybackHistoryPolicy.RETENTION_MS + 1_000L)
        val expired = song("expired", now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
        val duplicateOld = song("duplicate", now - 5_000L, "old")
        val duplicateNew = song("duplicate", now - 1_000L, "new")
        val recent = song("recent", now - 500L)
        val unknown = song("unknown", 0L)
        val raw = JSONArray().put(boundary.toJson()).put(expired.toJson())
            .put(duplicateOld.toJson()).put(duplicateNew.toJson())
            .put(recent.toJson()).put(unknown.toJson()).toString()
        LocalStore.prefs(context).edit().putString("play_history_v1", raw).commit()

        val loaded = LocalStore.loadHistory(context)
        assertEquals(listOf("recent", "duplicate", "boundary"), loaded.map { it.id })
        assertEquals("new", loaded.first { it.id == "duplicate" }.name)
        val cleanedOnRead = JSONArray(LocalStore.prefs(context).getString("play_history_v1", "[]"))
        assertEquals(
            listOf("recent", "duplicate", "boundary"),
            (0 until cleanedOnRead.length()).map { cleanedOnRead.getJSONObject(it).getString("id") }
        )

        LocalStore.pushHistory(context, song("fresh", now))
        val stored = JSONArray(LocalStore.prefs(context).getString("play_history_v1", "[]"))
        val storedIds = (0 until stored.length()).map { stored.getJSONObject(it).getString("id") }

        assertEquals(listOf("fresh", "recent", "duplicate", "boundary"), storedIds)
    }

    @Test
    fun loadingBundlePhysicallyPrunesOnlyItsHistoryField() {
        val now = System.currentTimeMillis()
        val favorite = song("favorite", now)
        val playlistSong = song("playlist-song", now)
        val recent = song("recent-bundle-history", now)
        val stale = song("stale-bundle-history", now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(favorite),
            playlists = mutableListOf(Playlist("keep-list", "Keep list", songs = mutableListOf(playlistSong))),
            history = mutableListOf(recent)
        )
        LocalStore.save(context, bundle)
        val preferences = LocalStore.prefs(context)
        val original = JSONObject(requireNotNull(preferences.getString("user_data_v1", null)))
        val beforeFavorites = original.getJSONArray("favs").toString()
        val beforePlaylists = original.getJSONArray("pls").toString()
        val corruptHistory = JSONArray().put(stale.toJson()).put(song("unknown-bundle-history", 0L).toJson())
            .put(recent.toJson())
        preferences.edit().putString("user_data_v1", original.put("history", corruptHistory).toString()).commit()

        val loaded = LocalStore.load(context)

        assertEquals(listOf("favorite"), loaded.favs.map { it.id })
        assertTrue(loaded.playlists.any { it.id == "keep-list" })
        assertEquals(listOf("recent-bundle-history"), loaded.history.map { it.id })
        val cleaned = JSONObject(requireNotNull(preferences.getString("user_data_v1", null)))
        assertEquals(beforeFavorites, cleaned.getJSONArray("favs").toString())
        assertEquals(beforePlaylists, cleaned.getJSONArray("pls").toString())
        assertEquals(listOf("recent-bundle-history"), (0 until cleaned.getJSONArray("history").length())
            .map { cleaned.getJSONArray("history").getJSONObject(it).getString("id") })
    }

    @Test
    fun genericBackupRestorePhysicallyPrunesBundleHistoryBeforeWriting() {
        val now = System.currentTimeMillis()
        val recent = song("backup-recent", now)
        val stale = song("backup-stale", now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
        val bundle = LocalStore.UserBundle(history = mutableListOf(stale, recent))

        LocalStore.saveForGenericBackupRestore(context, bundle)

        val stored = JSONObject(requireNotNull(LocalStore.prefs(context).getString("user_data_v1", null)))
            .getJSONArray("history")
        assertEquals(listOf("backup-recent"), (0 until stored.length()).map { stored.getJSONObject(it).getString("id") })
    }

    @Test
    fun namedProfileLoadsPruneBothBundleAndIndependentHistoryStorage() {
        val profileId = ProfileId.of("retention_profile_${System.nanoTime()}")
        val preferences = context.getSharedPreferences(profileId.preferencesName, 0)
        preferences.edit().clear().commit()
        try {
            val now = System.currentTimeMillis()
            val favorite = song("profile-favorite", now)
            val playlistSong = song("profile-playlist-song", now)
            val recent = song("profile-recent-history", now)
            val stale = song("profile-expired-history", now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
            LocalStore.save(context, profileId, LocalStore.UserBundle(
                favs = mutableListOf(favorite),
                playlists = mutableListOf(Playlist("profile-keep-list", "Keep", songs = mutableListOf(playlistSong))),
                history = mutableListOf(recent)
            ))
            val bundleRaw = JSONObject(requireNotNull(preferences.getString("user_data_v1", null)))
                .put("history", JSONArray().put(stale.toJson()).put(recent.toJson()))
            preferences.edit().putString("user_data_v1", bundleRaw.toString())
                .putString("play_history_v1", JSONArray().put(stale.toJson()).put(recent.toJson()).toString())
                .commit()

            val loaded = LocalStore.load(context, profileId)
            val independent = LocalStore.loadHistory(context, profileId)

            assertEquals(listOf("profile-favorite"), loaded.favs.map { it.id })
            assertTrue(loaded.playlists.any { it.id == "profile-keep-list" })
            assertEquals(listOf("profile-recent-history"), loaded.history.map { it.id })
            assertEquals(listOf("profile-recent-history"), independent.map { it.id })
            assertEquals(listOf("profile-recent-history"), JSONObject(requireNotNull(
                preferences.getString("user_data_v1", null)
            )).getJSONArray("history").let { rows ->
                (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
            })
            assertEquals(listOf("profile-recent-history"), JSONArray(
                requireNotNull(preferences.getString("play_history_v1", null))
            ).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } })
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun song(id: String, playedAt: Long, name: String = id) = Song(
        id = id,
        name = name,
        singer = "Artist",
        source = "wy",
        playedAt = playedAt
    )
}
