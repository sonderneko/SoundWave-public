package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSystemOutputSnapshotTest {
    private data class Route(val name: String, val enabled: Boolean, val types: Int)

    @Test
    fun filtersDisabledAndNonLiveAudioRoutesButRetainsTheCurrentAndDefaultIdentities() {
        val local = Route("phone", enabled = true, types = 0b001)
        val wireless = Route("wireless", enabled = true, types = 0b100)
        val disabled = Route("disabled", enabled = false, types = 0b100)
        val videoOnly = Route("video", enabled = true, types = 0b010)

        val snapshot = PlayerSystemOutputSnapshotPolicy.create(
            routes = listOf(local, wireless, disabled, videoOnly),
            selectedRoute = wireless,
            defaultRoute = local,
            isEnabled = Route::enabled,
            supportedTypes = Route::types,
            liveAudioType = 0b100
        )

        assertEquals(listOf(wireless), snapshot.routes)
        assertTrue(snapshot.isSelected(wireless))
        assertFalse(snapshot.isSelected(local))
        assertFalse(snapshot.isDefaultRouteSelected())
    }

    @Test
    fun localDefaultIsMarkedSelectedWhenItIsTheCurrentRoute() {
        val local = Route("phone", enabled = true, types = 0b100)
        val snapshot = PlayerSystemOutputSnapshotPolicy.create(
            routes = listOf(local),
            selectedRoute = local,
            defaultRoute = local,
            isEnabled = Route::enabled,
            supportedTypes = Route::types,
            liveAudioType = 0b100
        )

        assertTrue(snapshot.isDefaultRouteSelected())
        assertTrue(snapshot.isSelected(local))
    }
}
