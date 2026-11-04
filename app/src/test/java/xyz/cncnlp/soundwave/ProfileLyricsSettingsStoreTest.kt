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
class ProfileLyricsSettingsStoreTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profileA = ProfileId.of("lyrics-settings-profile-a")
    private val profileB = ProfileId.of("lyrics-settings-profile-b")

    @Test fun tenSpRoundTripsWithoutChangingAnotherProfile() {
        ProfileLyricsSettingsStore.save(context, profileA, ProfileLyricsSettings(normalFontSp=10))
        ProfileLyricsSettingsStore.save(context, profileB, ProfileLyricsSettings(normalFontSp=24))
        assertEquals(10,ProfileLyricsSettingsStore.load(context,profileA).normalFontSp)
        assertEquals(24,ProfileLyricsSettingsStore.load(context,profileB).normalFontSp)
    }

    @Test
    fun missingProfileSettingsUseCurrentDefaultsAndDoNotReadGlobalPreferences() {
        assertEquals(ProfileLyricsSettings(), ProfileLyricsSettingsStore.load(context, profileA))

        assertTrue(context.storedPreferences(profileA.preferencesName).all.isEmpty())
        assertEquals(listOf(profileA.preferencesName), context.requestedPreferenceNames)
        assertFalse("profile access must not consult legacy global preferences", "soundwave" in context.requestedPreferenceNames)
    }

    @Test
    fun everyWhitelistedFieldRoundTripsAndProfilesRemainIndependent() {
        val settingsA = ProfileLyricsSettings(
            normalFontSp = 24,
            normalLineHeightDp = 15,
            immersiveFontSp = 44,
            immersiveLineHeightDp = 36,
            bold = false,
            distanceBlurDp = 4,
            showTranslation = true,
            showRomanization = false,
            highlightColor = "#11AA33",
            unhighlightColor = "#778899",
            offsetMs = -1_234,
            wordKaraokeEnabled = false,
        )
        val settingsB = ProfileLyricsSettings(
            normalFontSp = 30,
            normalLineHeightDp = 30,
            immersiveFontSp = 16,
            immersiveLineHeightDp = 8,
            bold = true,
            distanceBlurDp = 0,
            showTranslation = false,
            showRomanization = true,
            highlightColor = "#ABCDEF",
            unhighlightColor = "#123456",
            offsetMs = 28_000,
            wordKaraokeEnabled = true,
        )

        ProfileLyricsSettingsStore.save(context, profileA, settingsA)
        ProfileLyricsSettingsStore.save(context, profileB, settingsB)

        assertEquals(settingsA, ProfileLyricsSettingsStore.load(context, profileA))
        assertEquals(settingsB, ProfileLyricsSettingsStore.load(context, profileB))
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
    fun existingReadNormalizationAndWriteBoundsRemainConsistent() {
        context.storedPreferences(profileA.preferencesName).edit()
            .putInt("lyric_font_sp", 13)
            .putInt("lyric_gap_dp", 80)
            .putInt("lyric_immersive_sp", 100)
            .putInt("lyric_blur_dp", 3)
            .putInt("lyric_offset_ms", -40_000)
            .commit()

        val loaded = ProfileLyricsSettingsStore.load(context, profileA)
        assertEquals("ordinary values retain the legacy raw integer read", 13, loaded.normalFontSp)
        assertEquals("ordinary line height retains the legacy raw integer read", 80, loaded.normalLineHeightDp)
        assertEquals("immersive font is clamped as before", 56, loaded.immersiveFontSp)
        assertEquals("missing immersive gap uses the existing normal-gap fallback then clamps", 64, loaded.immersiveLineHeightDp)
        assertEquals("blur snaps to the existing 0/2/4/8 choices", 2, loaded.distanceBlurDp)
        assertEquals("offset is clamped as before", -30_000, loaded.offsetMs)

        ProfileLyricsSettingsStore.save(
            context,
            profileA,
            loaded.copy(
                immersiveFontSp = 1,
                immersiveLineHeightDp = 100,
                distanceBlurDp = 3,
                offsetMs = 40_000,
            ),
        )
        val saved = ProfileLyricsSettingsStore.load(context, profileA)
        assertEquals(14, saved.immersiveFontSp)
        assertEquals(64, saved.immersiveLineHeightDp)
        assertEquals(2, saved.distanceBlurDp)
        assertEquals(30_000, saved.offsetMs)
        assertEquals(13, saved.normalFontSp)
        assertEquals(80, saved.normalLineHeightDp)
    }

    @Test
    fun liftKeepsItsIndependentLegacyKeyAndKaraokeSeedMigrationWithinTheProfile() {
        val preferences = context.storedPreferences(profileA.preferencesName)
        preferences.edit().putBoolean("lyric_word_karaoke", false).commit()

        assertFalse(ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))
        assertFalse("missing lift key is migrated using its established legacy key", preferences.getBoolean("lyric_word_lift", true))

        preferences.edit().remove("lyric_word_lift").putBoolean("lyric_word_karaoke", true).commit()
        assertTrue(ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))

        preferences.edit().putBoolean("lyric_word_karaoke", false).putBoolean("lyric_word_lift", true).commit()
        assertTrue("an explicit independent lift value wins over karaoke", ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))
        preferences.edit().putBoolean("lyric_word_karaoke", true).putBoolean("lyric_word_lift", false).commit()
        assertFalse(ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))

        ProfileLyricsSettingsStore.saveWordLiftEnabled(context, profileA, true)
        assertTrue(ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))
    }

    @Test
    fun legacyGlobalKeysAndNoIdCallersRemainUntouched() {
        val legacyPreferences = context.storedPreferences("soundwave")
        legacyPreferences.edit()
            .putInt("lyric_font_sp", 26)
            .putInt("lyric_gap_dp", 21)
            .putBoolean("lyric_word_karaoke", false)
            .putBoolean("lyric_word_lift", false)
            .putString("lyric_font_family", "legacy-font-choice")
            .putString("global_font_family", "legacy-global-font")
            .putString("lyric_priority", "internal")
            .putBoolean("lyric_float", true)
            .putString("server_url", "https://server.example.invalid")
            .commit()
        val originalLegacyValues = legacyPreferences.all.toMap()

        assertEquals(ProfileLyricsSettings(), ProfileLyricsSettingsStore.load(context, profileA))
        assertTrue("profile lift defaults from profile karaoke, not global legacy keys", ProfileLyricsSettingsStore.loadWordLiftEnabled(context, profileA))
        ProfileLyricsSettingsStore.save(context, profileA, ProfileLyricsSettings(normalFontSp = 22))
        ProfileLyricsSettingsStore.saveWordLiftEnabled(context, profileA, false)

        assertEquals("existing no-ID preference values stay type/value compatible", originalLegacyValues, legacyPreferences.all)
        assertEquals(26, legacyPreferences.getInt("lyric_font_sp", -1))
        assertEquals("https://server.example.invalid", legacyPreferences.getString("server_url", null))
        assertFalse("profile APIs never request the legacy no-ID preference file", "soundwave" in context.requestedPreferenceNames)
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
            assertFalse("session keys must remain global: $key", key.startsWith("session", ignoreCase = true))
            assertFalse("server keys must remain global: $key", key.contains("server", ignoreCase = true))
            assertFalse("font-family keys must remain global: $key", key.contains("font_family", ignoreCase = true))
            assertFalse("theme keys must remain global: $key", key.contains("theme", ignoreCase = true))
            assertFalse("lyric priority is service-global: $key", key == "lyric_priority")
            assertFalse("floating lyric state/style remains global: $key", key.startsWith("lyric_float"))
        }
    }

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "profile-lyrics-settings-test-${System.nanoTime()}"
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
            "lyric_font_sp",
            "lyric_gap_dp",
            "lyric_immersive_sp",
            "lyric_immersive_gap_dp",
            "lyric_bold",
            "lyric_blur_dp",
            "lyric_show_translation",
            "lyric_show_romanization",
            "lyric_highlight_color",
            "lyric_unhighlight_color",
            "lyric_offset_ms",
            "lyric_word_karaoke",
        )
    }
}
