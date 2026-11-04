package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProfileDownloadPreferencesStoreTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profileA = ProfileId.of("download-preferences-a")
    private val profileB = ProfileId.of("download-preferences-b")

    @Test
    fun twoProfilesKeepQualityTemplatesAndDownloadOptionsIndependent() {
        val legacy = prefs("soundwave")
        legacy.edit()
            .putString("down_br", "flac")
            .putBoolean("download_mt", false)
            .putBoolean("save_ext_lyric", false)
            .putBoolean("save_ext_cover", false)
            .putString("download_name_tpl", "album_title")
            .putString("download_tree_uri", "content://saf.example/tree/global-downloads")
            .putString("pass", "test-session-secret-placeholder")
            .commit()
        val legacyBefore = legacy.all.toMap()

        ProfilePlaybackPreferencesStore.save(
            context,
            profileA,
            ProfilePlaybackPreferences(
                downloadQuality = "master",
                downloadMultiThread = false,
                saveExtLyric = false,
                saveExtCover = true,
                downloadFilenameTemplate = "title_only",
            ),
        )
        ProfilePlaybackPreferencesStore.save(
            context,
            profileB,
            ProfilePlaybackPreferences(
                downloadQuality = "128k",
                downloadMultiThread = true,
                saveExtLyric = true,
                saveExtCover = false,
                downloadFilenameTemplate = "album_title",
            ),
        )

        val loadedA = ProfilePlaybackPreferencesStore.load(context, profileA)
        val loadedB = ProfilePlaybackPreferencesStore.load(context, profileB)
        assertEquals("master", loadedA.downloadQuality)
        assertFalse(loadedA.downloadMultiThread)
        assertFalse(loadedA.saveExtLyric)
        assertTrue(loadedA.saveExtCover)
        assertEquals("title_only", loadedA.downloadFilenameTemplate)
        assertEquals("128k", loadedB.downloadQuality)
        assertTrue(loadedB.downloadMultiThread)
        assertTrue(loadedB.saveExtLyric)
        assertFalse(loadedB.saveExtCover)
        assertEquals("album_title", loadedB.downloadFilenameTemplate)

        assertEquals("Legacy download settings, SAF URI, and session secret must remain untouched", legacyBefore, legacy.all)
        listOf(profileA, profileB).forEach { profileId ->
            val profilePreferences = prefs(profileId.preferencesName)
            assertFalse(profilePreferences.contains("download_tree_uri"))
            assertFalse(profilePreferences.contains("pass"))
            assertFalse(profilePreferences.contains("session_token"))
        }
    }

    @Test
    fun emptyProfileUsesDeclaredDefaultsWithoutReadingLegacyPreferences() {
        val legacy = prefs("soundwave")
        legacy.edit()
            .putString("down_br", "master")
            .putBoolean("download_mt", false)
            .putBoolean("save_ext_lyric", false)
            .putBoolean("save_ext_cover", false)
            .putString("download_name_tpl", "album_title")
            .putString("download_tree_uri", "content://saf.example/tree/legacy")
            .putString("pass", "test-session-secret-placeholder")
            .commit()
        val legacyBefore = legacy.all.toMap()
        context.requestedPreferenceNames.clear()

        val defaults = ProfilePlaybackPreferencesStore.load(context, profileA)

        assertEquals("320k", defaults.downloadQuality)
        assertTrue(defaults.downloadMultiThread)
        assertTrue(defaults.saveExtLyric)
        assertTrue(defaults.saveExtCover)
        assertEquals("title_singer", defaults.downloadFilenameTemplate)
        assertTrue(prefs(profileA.preferencesName).all.isEmpty())
        assertEquals(setOf(profileA.preferencesName), context.requestedPreferenceNames.toSet())
        assertEquals(legacyBefore, legacy.all)
    }

    @Test
    fun unsupportedValuesNormalizeToFixedSafeOptionsWithoutTemplateEvaluation() {
        ProfilePlaybackPreferencesStore.save(
            context,
            profileA,
            ProfilePlaybackPreferences(
                downloadQuality = "${'$'}{secret-or-code}",
                downloadFilenameTemplate = "${'$'}{1 + 1}",
            ),
        )
        val normalized = ProfilePlaybackPreferencesStore.load(context, profileA)
        assertEquals("320k", normalized.downloadQuality)
        assertEquals("title_singer", normalized.downloadFilenameTemplate)

        ProfilePlaybackPreferencesStore.save(
            context,
            profileA,
            ProfilePlaybackPreferences(
                downloadQuality = "flac24bit",
                downloadFilenameTemplate = "title_singer",
            ),
        )
        val literalChoices = ProfilePlaybackPreferencesStore.load(context, profileA)
        assertEquals("flac24bit", literalChoices.downloadQuality)
        assertEquals("title_singer", literalChoices.downloadFilenameTemplate)
        assertEquals("title_singer", prefs(profileA.preferencesName).getString("download_name_tpl", null))
    }

    @Test
    fun malformedStoredTypesFallBackWithoutThrowing() {
        prefs(profileA.preferencesName).edit()
            .putInt("down_br", 320)
            .putString("download_mt", "enabled")
            .putInt("save_ext_lyric", 1)
            .putString("save_ext_cover", "true")
            .putBoolean("download_name_tpl", false)
            .commit()

        val loaded = ProfilePlaybackPreferencesStore.load(context, profileA)
        assertEquals("320k", loaded.downloadQuality)
        assertTrue(loaded.downloadMultiThread)
        assertTrue(loaded.saveExtLyric)
        assertTrue(loaded.saveExtCover)
        assertEquals("title_singer", loaded.downloadFilenameTemplate)
    }

    @Test
    fun invalidProfileIdentifiersAreRejectedBeforePreferencesAreAddressed() {
        listOf("", "../escape", "profile/name", "profile.name", "含中文", "a".repeat(65)).forEach { rawId ->
            assertThrows("unsafe profile ID: $rawId", IllegalArgumentException::class.java) {
                ProfileId.of(rawId)
            }
        }
        assertTrue(context.requestedPreferenceNames.isEmpty())
    }

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "profile-download-preferences-test-${System.nanoTime()}"
        val requestedPreferenceNames = mutableListOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(name.also { requestedPreferenceNames += it }.let { "$namespace-$it" }, mode)
    }
}
