package xyz.cncnlp.soundwave.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.NotificationLyricGlyph

class ParticleBurstMathTest {
    @Test fun travelIsMonotonicEaseOutFromZeroToOne() {
        assertEquals(0f, particleTravel(0f), 1e-4f)
        assertEquals(1f, particleTravel(1f), 1e-4f)
        var last = -1f
        for (i in 0..20) { val v = particleTravel(i / 20f); assertTrue(v >= last); last = v }
        assertTrue("ease-out: halfway time covers most distance", particleTravel(0.5f) > 0.8f)
    }

    @Test fun alphaFadesInThenOutAndEndsInvisible() {
        assertEquals(0f, particleAlpha(0f), 1e-4f)
        assertEquals(1f, particleAlpha(0.15f), 1e-4f)
        assertEquals(0f, particleAlpha(1f), 1e-4f)
    }

    @Test fun notificationLyricWordColourDiffersBetweenOnAndOff() {
        assertTrue(NotificationLyricGlyph.wordColor(true) != NotificationLyricGlyph.wordColor(false))
        // 关闭态半透明：系统统一着色后仍可区分。
        assertTrue((NotificationLyricGlyph.wordColor(false) ushr 24) < 0xFF)
        assertEquals(0xFF, NotificationLyricGlyph.wordColor(true) ushr 24)
    }
}
