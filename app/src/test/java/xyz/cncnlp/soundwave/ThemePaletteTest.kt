package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePaletteTest {
    @Test
    fun nightSchemeUsesUnifiedPaletteWithReadableContrast() {
        val scheme = darkScheme()

        assertEquals(NightPalette.Background, scheme.background)
        assertEquals(NightPalette.Surface, scheme.surface)
        assertEquals(NightPalette.SurfaceVariant, scheme.surfaceVariant)
        assertEquals(NightPalette.PrimaryText, scheme.onBackground)
        assertEquals(NightPalette.SecondaryText, scheme.onSurfaceVariant)
        assertEquals(NightPalette.Accent, scheme.primary)
        assertEquals(NightPalette.OnAccent, scheme.onPrimary)

        assertTrue(contrastRatio(scheme.onBackground, scheme.background) >= 7.0)
        assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= 7.0)
        assertTrue(contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
        assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
    }

    @Test
    fun playerUsesTheSelectedMaterialPaletteWithoutCustomAccentOverrides() {
        val globalDark = darkScheme()
        val playerDark = playerScheme("night", isDark = true)
        val systemDark = playerScheme("auto", isDark = true)
        val systemLight = playerScheme("auto", isDark = false)

        assertEquals(globalDark.background, playerDark.background)
        assertEquals(globalDark.surface, playerDark.surface)
        assertEquals(globalDark.primary, playerDark.primary)
        assertEquals(globalDark.onPrimary, playerDark.onPrimary)
        assertEquals(globalDark.primary, systemDark.primary)
        assertEquals(lightScheme().background, systemLight.background)
        assertEquals(lightScheme().primary, systemLight.primary)
    }

    @Test
    fun themeModeSupportsLightNightAndSystemAutoOnly() {
        assertEquals("light", normalizeThemeMode("light"))
        assertEquals("night", normalizeThemeMode("night"))
        assertEquals("night", normalizeThemeMode("dark"))
        assertEquals("auto", normalizeThemeMode("auto"))
        assertEquals("auto", normalizeThemeMode("glass"))

        assertFalse(resolveIsDark("light", systemDark = true))
        assertTrue(resolveIsDark("night", systemDark = false))
        assertTrue(resolveIsDark("auto", systemDark = true))
        assertFalse(resolveIsDark("auto", systemDark = false))
        assertTrue(resolveIsDark("glass", systemDark = true))
        assertFalse(resolveIsDark("glass", systemDark = false))
    }

    @Test
    fun lightSchemeUsesUnifiedWarmPalette() {
        // 验收二轮 E8（用户直接裁定）：双色系取消，根主题 primary 的注册值由老蓝
        // #2A5CAA 改为珊瑚 #FF5C45；onPrimary 随对比契约改墨字。其余注册值逐字保留。
        val light = lightScheme()

        assertEquals(Color(0xFFFAFAFA.toInt()), light.background)
        assertEquals(Color.White, light.surface)
        assertEquals(Color(0xFFFF5C45.toInt()), light.primary)
        assertEquals(Color(0xFF101010.toInt()), light.onPrimary)
        assertEquals(Color(0xFF101010.toInt()), light.onBackground)
    }

    @Test
    fun settingsSheetSemanticRolesStayReadableAcrossLightAndNightThemes() {
        val light = lightScheme()
        val night = darkScheme()

        assertNotEquals(light.surface, night.surface)
        assertNotEquals(light.surfaceVariant, night.surfaceVariant)
        assertNotEquals(light.primary, night.primary)
        for (scheme in listOf(light, night)) {
            assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= 4.5)
            assertTrue(contrastRatio(scheme.onSurface, scheme.surfaceVariant) >= 4.5)
            assertTrue(contrastRatio(scheme.onSurfaceVariant, scheme.surfaceVariant) >= 4.5)
            assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = foreground.luminance().toDouble()
        val backgroundLuminance = background.luminance().toDouble()
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
