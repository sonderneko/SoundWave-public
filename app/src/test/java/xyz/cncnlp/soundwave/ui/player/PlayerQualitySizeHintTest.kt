package xyz.cncnlp.soundwave.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQualitySizeHintTest {
    @Test fun constantBitrateTiersEstimateFromDuration() {
        // 240s × 320 kb/s = 9,600,000 B ≈ 9.2 MiB
        assertEquals("≈ 9.2 MB", playerQualitySizeHint("320k", 240_000L))
        assertEquals("≈ 3.7 MB", playerQualitySizeHint("128k", 240_000L))
    }

    @Test fun variableBitrateOrUnknownDurationShowsNothing() {
        assertEquals("", playerQualitySizeHint("flac", 240_000L))
        assertEquals("", playerQualitySizeHint("master", 240_000L))
        assertEquals("", playerQualitySizeHint("320k", 0L))
    }
}
