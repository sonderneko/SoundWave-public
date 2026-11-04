package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MaintenancePageThemeColorsTest {
    @Test
    fun maintenanceControlsFollowMaterialRolesInLightAndNightThemes() {
        val lightScheme = lightScheme()
        val nightScheme = darkScheme()
        val light = maintenancePageThemeColors(lightScheme)
        val night = maintenancePageThemeColors(nightScheme)

        for ((scheme, colors) in listOf(lightScheme to light, nightScheme to night)) {
            assertEquals(scheme.onSurface, colors.onSurface)
            assertEquals(scheme.onSurfaceVariant, colors.onSurfaceVariant)
            assertEquals(scheme.primary, colors.primary)
            assertEquals(scheme.error, colors.error)
        }

        assertNotEquals(light.onSurface, night.onSurface)
        assertNotEquals(light.onSurfaceVariant, night.onSurfaceVariant)
        assertNotEquals(light.primary, night.primary)
        assertNotEquals(light.error, night.error)
    }
}
