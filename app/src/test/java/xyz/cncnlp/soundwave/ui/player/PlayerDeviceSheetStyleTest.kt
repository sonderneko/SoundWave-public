package xyz.cncnlp.soundwave.ui.player

import android.media.MediaRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDeviceSheetStyleTest {
    @Test
    fun localAndSystemConnectorOptionsUseDistinctGlyphsAndSelectionRoles() {
        val localCurrent = playerLocalDeviceOptionStyle(isCurrent = true)
        val localAvailable = playerLocalDeviceOptionStyle(isCurrent = false)
        val connector = playerSystemOutputActionStyle()

        assertEquals(PlayerDeviceGlyph.LOCAL_PHONE, localCurrent.glyph)
        assertEquals(PlayerDeviceColorRole.PRIMARY, localCurrent.colorRole)
        assertEquals(PlayerDeviceColorRole.ON_SURFACE_VARIANT, localAvailable.colorRole)
        assertEquals(PlayerDeviceGlyph.SYSTEM_CONNECTOR, connector.glyph)
        assertEquals(PlayerDeviceColorRole.ON_SURFACE_VARIANT, connector.colorRole)
        assertNotEquals(localCurrent.glyph, connector.glyph)
    }

    @Test
    fun eachKnownDeviceCategoryHasItsOwnGlyph() {
        val glyphs = listOf(
            playerLocalDeviceOptionStyle(isCurrent = false).glyph,
            playerSystemOutputActionStyle().glyph,
            playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH, isCurrent = false).glyph,
            playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER, isCurrent = false).glyph,
            playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_TV, isCurrent = false).glyph,
            playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN, isCurrent = false).glyph,
            playerDlnaOptionStyle(isActive = false).glyph
        )

        assertEquals(glyphs.size, glyphs.distinct().size)
    }

    @Test
    fun systemRouteGlyphFollowsAndroidDeviceTypeAndSelectedState() {
        val bluetooth = playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH, isCurrent = true)
        val speaker = playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER, isCurrent = false)
        val television = playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_TV, isCurrent = false)
        val remote = playerSystemRouteOptionStyle(MediaRouter.RouteInfo.DEVICE_TYPE_UNKNOWN, isCurrent = false)

        assertEquals(PlayerDeviceGlyph.BLUETOOTH, bluetooth.glyph)
        assertEquals(PlayerDeviceColorRole.SECONDARY, bluetooth.colorRole)
        assertEquals(PlayerDeviceGlyph.SPEAKER, speaker.glyph)
        assertEquals(PlayerDeviceGlyph.TELEVISION, television.glyph)
        assertEquals(PlayerDeviceGlyph.REMOTE_OUTPUT, remote.glyph)
        assertTrue(listOf(bluetooth.glyph, speaker.glyph, television.glyph, remote.glyph).distinct().size == 4)
        listOf(speaker, television, remote).forEach {
            assertEquals(PlayerDeviceColorRole.ON_SURFACE_VARIANT, it.colorRole)
        }
    }

    @Test
    fun activeDlnaHasAnExplicitNonColorStatusAndSemanticColorRole() {
        val active = playerDlnaOptionStyle(isActive = true)
        val available = playerDlnaOptionStyle(isActive = false)

        assertEquals(PlayerDeviceGlyph.DLNA, active.glyph)
        assertEquals(active.glyph, available.glyph)
        assertEquals(PlayerDeviceColorRole.PRIMARY, active.colorRole)
        assertEquals(PlayerDeviceColorRole.ON_SURFACE_VARIANT, available.colorRole)
    }

    @Test
    fun interactiveDeviceOptionsKeepAtLeastFortyEightDpTargets() {
        assertTrue(PLAYER_DEVICE_MIN_TOUCH_TARGET_DP >= 48)
    }
}
