package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptProbeSchedulerTest {
    @Test
    fun normalizeIntervalMinutes_clampsToProductBounds() {
        val cases = mapOf(
            1 to 30,
            29 to 30,
            30 to 30,
            180 to 180,
            181 to 181,
            360 to 360,
            720 to 720,
            1440 to 1440,
            1441 to 1440,
            0 to 30,
            -1 to 30,
        )

        cases.forEach { (input, expected) ->
            assertEquals("input=$input", expected, ScriptProbeScheduler.normalizeIntervalMinutes(input))
        }
    }
}
