package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProfileScopedLocalStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val profileA = ProfileId.of("storage-test-profile-a")
    private val profileB = ProfileId.of("storage-test-profile-b")
    private val preferenceNames = listOf(
        "soundwave_userdata",
        profileA.preferencesName,
        profileB.preferencesName
    )
    private lateinit var originalPreferences: Map<String, Map<String, *>>

    @Before
    fun snapshotAndClearPreferences() {
        originalPreferences = preferenceNames.associateWith { prefs(it).all.toMap() }
        preferenceNames.forEach { prefs(it).edit().clear().commit() }
    }

    @After
    fun restorePreferences() {
        originalPreferences.forEach { (name, values) ->
            val editor = prefs(name).edit().clear()
            values.forEach { (key, value) -> putPreference(editor, key, value) }
            editor.commit()
        }
    }

    @Test
    fun twoProfilesAndTheirTwoHistoryStoresRemainIndependent() {
        val coreHistoryA = song("core-history-a")
        val standaloneHistoryA = song("standalone-history-a")
        val favoriteA = song("favorite-a")
        val favoriteB = song("favorite-b")

        LocalStore.save(
            context,
            profileA,
            LocalStore.UserBundle(
                favs = mutableListOf(favoriteA),
                history = mutableListOf(coreHistoryA)
            )
        )
        LocalStore.save(
            context,
            profileB,
            LocalStore.UserBundle(
                favs = mutableListOf(favoriteB),
                history = mutableListOf(song("core-history-b"))
            )
        )
        LocalStore.pushHistory(context, profileA, standaloneHistoryA)
        LocalStore.pushHistory(context, profileB, song("standalone-history-b"))

        assertEquals(listOf("favorite-a"), LocalStore.load(context, profileA).favs.map { it.id })
        assertEquals(listOf("favorite-b"), LocalStore.load(context, profileB).favs.map { it.id })
        assertEquals(listOf("core-history-a"), LocalStore.load(context, profileA).history.map { it.id })
        assertEquals(listOf("core-history-b"), LocalStore.load(context, profileB).history.map { it.id })
        assertEquals(listOf("standalone-history-a"), LocalStore.loadHistory(context, profileA).map { it.id })
        assertEquals(listOf("standalone-history-b"), LocalStore.loadHistory(context, profileB).map { it.id })
        assertTrue("profile APIs must not write legacy default storage", LocalStore.prefs(context).all.isEmpty())
        assertEquals(setOf("user_data_v1", "play_history_v1"), prefs(profileA.preferencesName).all.keys)
        assertEquals(setOf("user_data_v1", "play_history_v1"), prefs(profileB.preferencesName).all.keys)
    }

    @Test
    fun existingDefaultApisKeepTheirFixedPreferenceKeysAndSerializedOutput() {
        val bundleHistory = song("legacy-bundle-history")
        val standaloneHistory = song("legacy-play-history")
        val bundle = LocalStore.UserBundle(
            favs = mutableListOf(song("legacy-favorite")),
            history = mutableListOf(bundleHistory)
        )
        bundle.ensureDefaults()
        val expectedBundleJson = bundle.toCloudJson().toString()

        LocalStore.save(context, bundle)
        LocalStore.pushHistory(context, standaloneHistory)

        val legacyPrefs = LocalStore.prefs(context)
        assertEquals(setOf("user_data_v1", "play_history_v1", "provenance_invalidation_epoch_v1"), legacyPrefs.all.keys)
        assertEquals(expectedBundleJson, legacyPrefs.getString("user_data_v1", null))
        assertEquals(listOf("legacy-favorite"), LocalStore.load(context).favs.map { it.id })
        assertEquals(listOf("legacy-bundle-history"), LocalStore.load(context).history.map { it.id })
        assertEquals(listOf("legacy-play-history"), LocalStore.loadHistory(context).map { it.id })
        assertFalse(prefs(profileA.preferencesName).contains("user_data_v1"))
        assertFalse(prefs(profileA.preferencesName).contains("play_history_v1"))
    }

    @Test
    fun profileHistoryRemoveAndClearOnlyChangeTheNamedHistoryKey() {
        val removedA = song("profile-a-removed-history")
        val keptA = song("profile-a-kept-history")
        val keptB = song("profile-b-kept-history")
        val coreHistoryA = song("profile-a-core-history")

        LocalStore.save(context, profileA, LocalStore.UserBundle(history = mutableListOf(coreHistoryA)))
        LocalStore.pushHistory(context, profileA, removedA)
        LocalStore.pushHistory(context, profileA, keptA)
        LocalStore.pushHistory(context, profileB, keptB)

        LocalStore.removeHistory(context, profileA, removedA)

        assertEquals(listOf("profile-a-kept-history"), LocalStore.loadHistory(context, profileA).map { it.id })
        assertEquals(listOf("profile-b-kept-history"), LocalStore.loadHistory(context, profileB).map { it.id })
        assertEquals(listOf("profile-a-core-history"), LocalStore.load(context, profileA).history.map { it.id })

        LocalStore.clearHistory(context, profileA)

        assertTrue(LocalStore.loadHistory(context, profileA).isEmpty())
        assertEquals(listOf("profile-b-kept-history"), LocalStore.loadHistory(context, profileB).map { it.id })
        assertEquals(setOf("user_data_v1", "play_history_v1"), prefs(profileA.preferencesName).all.keys)
        assertEquals(setOf("play_history_v1"), prefs(profileB.preferencesName).all.keys)
        assertTrue("profile history APIs must not write legacy default storage", LocalStore.prefs(context).all.isEmpty())
    }

    @Test
    fun profileDownloadRecordMutationsStayScopedAndKeepLegacyStorageAndDirectorySetting() {
        val directoryPreferences = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        val originalDownloadTreeUri = directoryPreferences.getString("download_tree_uri", null)
        val legacyItem = download("legacy-default-download")
        LocalStore.addDownload(context, legacyItem)
        val legacyDownloadsJson = LocalStore.prefs(context).getString("downloads_v1", null)

        val a1 = download("profile-a-one")
        val a2 = download("profile-a-two")
        val a3 = download("profile-a-three")
        val b1 = download("profile-b-one")
        val b2 = download("profile-b-two")
        LocalStore.addDownload(context, profileA, a1)
        LocalStore.addDownload(context, profileA, a2)
        LocalStore.addDownload(context, profileA, a3)
        LocalStore.addDownload(context, profileB, b1)
        LocalStore.addDownload(context, profileB, b2)

        val updatedA2 = a2.copy(size = 222L, status = "downloading", progress = 0.5f)
        LocalStore.addDownload(context, profileA, updatedA2)
        assertEquals(listOf(a3.path, a2.path, a1.path), LocalStore.loadDownloads(context, profileA).map { it.path })
        assertEquals(222L, LocalStore.loadDownloads(context, profileA)[1].size)
        assertEquals(0.5f, LocalStore.loadDownloads(context, profileA)[1].progress, 0f)

        assertTrue(LocalStore.moveDownload(context, profileA, a3, 2))
        assertEquals(listOf(a2.path, a1.path, a3.path), LocalStore.loadDownloads(context, profileA).map { it.path })
        assertTrue(LocalStore.moveDownload(context, profileB, b1, -1))
        assertEquals(listOf(b1.path, b2.path), LocalStore.loadDownloads(context, profileB).map { it.path })

        LocalStore.removeDownload(context, profileA, a2)
        assertEquals(listOf(a1.path, a3.path), LocalStore.loadDownloads(context, profileA).map { it.path })
        LocalStore.removeDownload(context, profileB, b2)
        assertEquals(listOf(b1.path), LocalStore.loadDownloads(context, profileB).map { it.path })
        LocalStore.removeDownloads(context, profileA, listOf(a3))
        assertEquals(listOf(a1.path), LocalStore.loadDownloads(context, profileA).map { it.path })

        LocalStore.clearDownloads(context, profileB)
        assertTrue(LocalStore.loadDownloads(context, profileB).isEmpty())
        assertEquals(listOf(a1.path), LocalStore.loadDownloads(context, profileA).map { it.path })
        val b3 = download("profile-b-after-clear")
        LocalStore.addDownload(context, profileB, b3)

        val durableA = download("profile-a-durable")
        assertTrue(LocalStore.addDownloadDurably(context, profileA, durableA))
        assertEquals(listOf(durableA.path, a1.path), LocalStore.loadDownloads(context, profileA).map { it.path })
        LocalStore.clearDownloads(context, profileA)
        assertTrue(LocalStore.loadDownloads(context, profileA).isEmpty())
        assertEquals(listOf(b3.path), LocalStore.loadDownloads(context, profileB).map { it.path })

        assertEquals(listOf("legacy-default-download"), LocalStore.loadDownloads(context).map { it.song.id })
        assertEquals(legacyDownloadsJson, LocalStore.prefs(context).getString("downloads_v1", null))
        assertEquals(setOf("downloads_v1", "provenance_invalidation_epoch_v1"), LocalStore.prefs(context).all.keys)
        assertEquals(setOf("downloads_v1"), prefs(profileA.preferencesName).all.keys)
        assertEquals(setOf("downloads_v1"), prefs(profileB.preferencesName).all.keys)
        assertEquals(originalDownloadTreeUri, directoryPreferences.getString("download_tree_uri", null))
        assertFalse(prefs(profileA.preferencesName).contains("download_tree_uri"))
        assertFalse(prefs(profileB.preferencesName).contains("download_tree_uri"))
    }

    @Test
    fun unsafeProfileIdsAreRejectedBeforeTheyCanFormPreferenceNamespaces() {
        val invalidIds = listOf(
            "",
            "../escape",
            "profile/name",
            "profile.name",
            "含中文",
            "a".repeat(65)
        )

        invalidIds.forEach { rawId ->
            var rejected = false
            try {
                ProfileId.of(rawId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("unsafe profile ID should be rejected: $rawId", rejected)
        }
        assertTrue("valid bounded IDs remain usable", ProfileId.of("profile_01-A").value == "profile_01-A")
        assertTrue("profile namespace must not contain a path separator", profileA.preferencesName.none { it == '/' || it == '\\' })
    }

    private fun song(id: String) = Song(
        id = id, name = id, singer = "Artist", source = "wy", playedAt = System.currentTimeMillis()
    )

    private fun download(id: String) = DownloadItem(
        song = song(id),
        path = "/music/$id.mp3",
        size = 100L,
        time = 1_000L,
        quality = "320k",
        platform = "wy"
    )

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
