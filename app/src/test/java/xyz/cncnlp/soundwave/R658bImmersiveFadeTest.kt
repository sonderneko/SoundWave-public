package xyz.cncnlp.soundwave

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r658b #8：沉浸详情色带渐隐到透明（交给页面自身底色 / 磨砂），色标单调、首尾正确。 */
class R658bImmersiveFadeTest {
    private val band = immersiveDetailBandColor(Color(0xFFE05A6A))

    @Test
    fun bandFadeEndsFullyTransparentAndIsMonotonic() {
        val stops = immersiveDetailBandFadeStops(band)
        assertEquals(1f, stops.first().second.alpha, 0.001f)
        assertEquals(0f, stops.last().second.alpha, 0.001f)
        stops.toList().zipWithNext().forEach { (a, b) ->
            assertTrue(b.first > a.first)
            assertTrue(b.second.alpha <= a.second.alpha)
            assertEquals(band.red, b.second.red, 0.001f)
        }
    }

    @Test
    fun coverFadeRisesToSolidBand() {
        val stops = immersiveDetailCoverFadeStops(band)
        assertEquals(0f, stops.first().second.alpha, 0.001f)
        assertEquals(1f, stops.last().second.alpha, 0.001f)
        stops.toList().zipWithNext().forEach { (a, b) -> assertTrue(b.second.alpha >= a.second.alpha) }
    }
}
