package xyz.cncnlp.soundwave.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ThemeBootstrapTest {
    private fun preferences() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("theme-bootstrap-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test
    fun legacyGlassModeBecomesSystemAutoWithoutDeletingOldVisualPreferences() {
        val prefs = preferences()
        prefs.edit()
            .putString("theme_mode", "glass")
            .putBoolean("dark_theme", true)
            .putString("theme_accent", "#FF00FF")
            .putString("theme_background_start", "#123456")
            .putFloat("glass_card_opacity", 0.17f)
            .putFloat("glass_bg_blur", 18f)
            .commit()

        val result = bootstrapTheme(prefs)

        assertEquals("auto", result.themeMode)
        assertEquals(true, prefs.getBoolean("dark_theme", false))
        assertEquals("auto", prefs.getString("theme_mode", null))
        assertEquals("#FF00FF", prefs.getString("theme_accent", null))
        assertEquals("#123456", prefs.getString("theme_background_start", null))
        assertEquals(0.17f, prefs.getFloat("glass_card_opacity", 0f), 0.0001f)
        assertEquals(18f, prefs.getFloat("glass_bg_blur", 0f), 0.0001f)
    }

    @Test
    fun supportedThemeModesLoadAndDarkAliasNormalizes() {
        listOf("light" to "light", "night" to "night", "auto" to "auto", "dark" to "night").forEach { (stored, expected) ->
            val prefs = preferences()
            prefs.edit().putString("theme_mode", stored).commit()
            assertEquals(expected, bootstrapTheme(prefs).themeMode)
            assertEquals(expected, prefs.getString("theme_mode", null))
        }
    }
}
