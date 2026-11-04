package xyz.cncnlp.soundwave.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSearchRoutingTest {
    @Test
    fun `heart similar server search uses registry only for exact canonical platform IDs`() {
        listOf("wy", "kg", "kw", "tx", "mg").forEach { source ->
            assertEquals(source, heartSimilarSearchRegistrySourceOrNull(source))
        }

        listOf("", "netease", "qq", "WY", " wy", "wy ", "script:test", "cloud", "unknown")
            .forEach { source -> assertNull(heartSimilarSearchRegistrySourceOrNull(source)) }
    }
}
