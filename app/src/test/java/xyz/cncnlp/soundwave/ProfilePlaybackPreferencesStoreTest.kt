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
class ProfilePlaybackPreferencesStoreTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profileA = ProfileId.of("playback-preferences-profile-a")
    private val profileB = ProfileId.of("playback-preferences-profile-b")

    @Test
    fun missingProfileSettingsUseCurrentDefaultsWithoutReadingLegacyPreferences() {
        val legacyPreferences = context.storedPreferences("soundwave")
        legacyPreferences.edit()
            .putString("play_br", "128k")
            .putString("down_br", "flac")
            .putBoolean("auto_play_on_start", true)
            .putBoolean("prefetch_next", false)
            .putInt("prefetch_interval_sec", 18)
            .putInt("prefetch_count", 4)
            .putFloat("play_speed", 1.5f)
            .putInt("eq_preset", 3)
            .putString("session_token", "legacy-session-sentinel")
            .putString("server_url", "https://legacy.example.invalid")
            .putString("theme_mode", "dark")
            .putString("download_tree_uri", "content://legacy/tree")
            .commit()
        val legacyBefore = legacyPreferences.all.toMap()

        assertEquals(ProfilePlaybackPreferences(), ProfilePlaybackPreferencesStore.load(context, profileA))
        assertTrue(context.storedPreferences(profileA.preferencesName).all.isEmpty())
        assertEquals(listOf(profileA.preferencesName), context.requestedPreferenceNames)
        assertFalse("profile access must not request legacy global preferences", "soundwave" in context.requestedPreferenceNames)
        assertEquals("old global settings and protected values remain unchanged", legacyBefore, legacyPreferences.all)
    }

    @Test
    fun profilesRoundTripIndependentlyAndWriteOnlyTheProfilePreferenceWhitelist() {
        val settingsA = ProfilePlaybackPreferences(
            playQuality = "flac",
            downloadQuality = "128k",
            autoPlayOnStart = true,
            prefetchNext = false,
            prefetchIntervalSec = 23,
            prefetchCount = 5,
            playSpeed = 1.25f,
            equalizerPreset = 5,
        )
        val settingsB = ProfilePlaybackPreferences(
            playQuality = "atmos",
            downloadQuality = "320k",
            autoPlayOnStart = false,
            prefetchNext = true,
            prefetchIntervalSec = 2,
            prefetchCount = 1,
            playSpeed = 0.75f,
            equalizerPreset = 2,
        )

        ProfilePlaybackPreferencesStore.save(context, profileA, settingsA)
        ProfilePlaybackPreferencesStore.save(context, profileB, settingsB)

        assertEquals(settingsA, ProfilePlaybackPreferencesStore.load(context, profileA))
        assertEquals(settingsB.copy(playQuality = "hires"), ProfilePlaybackPreferencesStore.load(context, profileB))
        assertEquals("hires", context.storedPreferences(profileB.preferencesName).getString("play_br", null))
        assertEquals(WHITELIST_KEYS, context.storedPreferences(profileA.preferencesName).all.keys)
        assertEquals(WHITELIST_KEYS, context.storedPreferences(profileB.preferencesName).all.keys)
        assertEquals(
            setOf(profileA.preferencesName, profileB.preferencesName),
            context.requestedPreferenceNames.toSet(),
        )
        assertNoGlobalOnlyKeys(context.storedPreferences(profileA.preferencesName).all.keys)
        assertNoGlobalOnlyKeys(context.storedPreferences(profileB.preferencesName).all.keys)
    }

    @Test
    fun loadAndSaveKeepCurrentQualityAndNumericBounds() {
        val preferences = context.storedPreferences(profileA.preferencesName)
        preferences.edit()
            .putString("play_br", "unsupported-quality")
            .putString("down_br", "")
            .putBoolean("auto_play_on_start", true)
            .putBoolean("prefetch_next", false)
            .putInt("prefetch_interval_sec", 99)
            .putInt("prefetch_count", 0)
            .putFloat("play_speed", Float.NaN)
            .putInt("eq_preset", 9)
            .commit()

        assertEquals(
            ProfilePlaybackPreferences(
                autoPlayOnStart = true,
                prefetchNext = false,
                prefetchIntervalSec = 30,
                prefetchCount = 1,
                equalizerPreset = 5,
            ),
            ProfilePlaybackPreferencesStore.load(context, profileA),
        )

        ProfilePlaybackPreferencesStore.save(
            context,
            profileA,
            ProfilePlaybackPreferences(
                playQuality = "unknown",
                downloadQuality = "hires",
                prefetchIntervalSec = -20,
                prefetchCount = 8,
                playSpeed = 4f,
                equalizerPreset = -1,
            ),
        )
        assertEquals(
            ProfilePlaybackPreferences(
                downloadQuality = "hires",
                prefetchIntervalSec = 1,
                prefetchCount = 5,
                playSpeed = 2f,
                equalizerPreset = 0,
            ),
            ProfilePlaybackPreferencesStore.load(context, profileA),
        )
    }

    @Test
    fun legacyKeysAndProtectedGlobalSessionServerThemeAndSafUriValuesRemainUnchanged() {
        val global = context.storedPreferences("soundwave")
        global.edit()
            .putString("play_br", "320k")
            .putString("down_br", "flac24bit")
            .putBoolean("auto_play_on_start", false)
            .putBoolean("prefetch_next", true)
            .putInt("prefetch_interval_sec", 6)
            .putInt("prefetch_count", 2)
            .putFloat("play_speed", 1f)
            .putInt("eq_preset", 0)
            .putString("session_token", "must-not-change")
            .putString("server_url", "https://server.example.invalid")
            .putString("theme_mode", "light")
            .putString("download_tree_uri", "content://provider/tree/document")
            .commit()
        val before = global.all.toMap()

        val updated = ProfilePlaybackPreferences(
            playQuality = "master",
            downloadQuality = "flac",
            autoPlayOnStart = true,
            prefetchIntervalSec = 12,
            playSpeed = 1.5f,
            equalizerPreset = 4,
        )
        ProfilePlaybackPreferencesStore.save(context, profileA, updated)
        assertEquals(updated, ProfilePlaybackPreferencesStore.load(context, profileA))

        assertEquals("legacy no-ID key/value path is preserved", before, global.all)
        assertEquals(setOf(profileA.preferencesName), context.requestedPreferenceNames.toSet())
        assertFalse("store must not open the global settings file", "soundwave" in context.requestedPreferenceNames)
        assertNoGlobalOnlyKeys(context.storedPreferences(profileA.preferencesName).all.keys)
    }

    @Test
    fun invalidProfileIdsAreRejectedBeforeAnyPreferenceNamespaceIsRequested() {
        listOf("", "../escape", "profile/name", "profile.name", "含中文", "a".repeat(65)).forEach { rawId ->
            assertThrows("unsafe profile ID: $rawId", IllegalArgumentException::class.java) {
                ProfileId.of(rawId)
            }
        }
        assertTrue(context.requestedPreferenceNames.isEmpty())
    }

    private fun assertNoGlobalOnlyKeys(keys: Set<String>) {
        keys.forEach { key ->
            assertFalse("session keys must remain global: $key", key.contains("session", ignoreCase = true))
            assertFalse("server keys must remain global: $key", key.contains("server", ignoreCase = true))
            assertFalse("theme keys must remain global: $key", key.contains("theme", ignoreCase = true))
            assertFalse("SAF URI keys must remain global: $key", key.contains("uri", ignoreCase = true))
            assertFalse("allow_mix controls one PlayerService and remains global: $key", key == "allow_mix")
        }
    }

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "profile-playback-preferences-test-${System.nanoTime()}"
        val requestedPreferenceNames = mutableListOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(
                namespaced(name).also { requestedPreferenceNames += name },
                mode,
            )

        fun storedPreferences(name: String): SharedPreferences =
            super.getSharedPreferences(namespaced(name), Context.MODE_PRIVATE)

        private fun namespaced(name: String) = "$namespace-$name"
    }

    private companion object {
        val WHITELIST_KEYS = setOf(
            "play_br",
            "down_br",
            "auto_play_on_start",
            "prefetch_next",
            "prefetch_interval_sec",
            "prefetch_count",
            "play_speed",
            "eq_preset",
            "download_mt",
            "save_ext_lyric",
            "save_ext_cover",
            "download_name_tpl",
        )
    }
}
