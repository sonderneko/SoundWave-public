package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPageThemeColorsTest {
    @Test
    fun nightSchemeMapsLibrarySurfacesAndActionsToSemanticRoles() {
        val scheme = darkScheme()
        val colors = libraryPageThemeColors(scheme)

        assertEquals(NightPalette.Background, colors.background)
        assertEquals(NightPalette.PrimaryText, colors.onBackground)
        assertEquals(NightPalette.Surface, colors.surface)
        assertEquals(NightPalette.PrimaryText, colors.onSurface)
        assertEquals(NightPalette.SurfaceVariant, colors.surfaceVariant)
        assertEquals(NightPalette.SecondaryText, colors.onSurfaceVariant)
        assertEquals(NightPalette.Accent, colors.primary)
        assertEquals(NightPalette.OnAccent, colors.onPrimary)
        assertEquals(NightPalette.Outline, colors.outline)
        assertNotEquals(androidx.compose.ui.graphics.Color.White, colors.surface)
    }

    @Test
    fun actionAndSheetRolesFollowBothActiveColorSchemes() {
        val light = libraryPageThemeColors(lightScheme())
        val dark = libraryPageThemeColors(darkScheme())

        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.surfaceVariant, dark.surfaceVariant)
        assertNotEquals(light.primary, dark.primary)
        assertNotEquals(light.onPrimary, dark.onPrimary)

        for ((scheme, colors) in listOf(lightScheme() to light, darkScheme() to dark)) {
            assertEquals(scheme.primary, colors.primary)
            assertEquals(scheme.onPrimary, colors.onPrimary)
            assertEquals(scheme.surface, colors.surface)
            assertEquals(scheme.onSurface, colors.onSurface)
            assertEquals(scheme.surfaceVariant, colors.surfaceVariant)
            assertEquals(scheme.onSurfaceVariant, colors.onSurfaceVariant)
            assertTrue(contrastRatio(colors.onPrimary, colors.primary) >= 4.5)
            assertTrue(contrastRatio(colors.onSurface, colors.surface) >= 4.5)
            assertTrue(contrastRatio(colors.onSurfaceVariant, colors.surfaceVariant) >= 4.5)
        }
    }

    private fun contrastRatio(
        foreground: androidx.compose.ui.graphics.Color,
        background: androidx.compose.ui.graphics.Color
    ): Double {
        val foregroundLuminance = foreground.luminance().toDouble()
        val backgroundLuminance = background.luminance().toDouble()
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
