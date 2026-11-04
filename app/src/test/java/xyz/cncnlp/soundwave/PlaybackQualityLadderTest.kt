package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the playback quality contract behind the "every song lands on 320k"
 * report: the first script request of a resolve must be the user's effective
 * maximum (global value, per-platform override when set), and any downgrade
 * may only walk the ladder one descending tier at a time after a failure.
 */
class PlaybackQualityLadderTest {

    @Test
    fun cascadeCoversEveryUserSelectableTier() {
        for ((tier, _) in USER_AUDIO_QUALITY_OPTIONS) {
            assertEquals("cascade must start at the requested tier: $tier", tier, Qualities.cascade(tier).first())
        }
        assertEquals(
            listOf("flac24bit", "flac", "320k", "128k"),
            Qualities.cascade("flac24bit")
        )
    }

    @Test
    fun globalFlacWithoutPlatformOverrideLeadsWithFlac() {
        val ladder = scriptStageQualities("flac", fallback128k = true)
        assertEquals(listOf("flac", "320k", "128k"), ladder)
        // First request is FLAC; the first downgrade step after a failure is 320k.
        assertEquals("flac", ladder.first())
        assertEquals("320k", ladder[1])
    }

    @Test
    fun highTiersDowngradeThroughFlacBefore320k() {
        // Regression pin: the old fast-path shot [pref, 320k] in the ORIGIN stage,
        // so master/hires/flac24bit requests landed on 320k without ever trying
        // flac — the tier most scripts actually declare.
        assertEquals(
            listOf("master", "flac", "320k", "128k"),
            scriptStageQualities("master", fallback128k = true)
        )
        assertEquals(
            listOf("hires", "flac", "320k", "128k"),
            scriptStageQualities("hires", fallback128k = true)
        )
        assertEquals(
            listOf("flac24bit", "flac", "320k", "128k"),
            scriptStageQualities("flac24bit", fallback128k = true)
        )
        assertEquals(
            listOf("master", "flac"),
            scriptStageQualities("master", fallback128k = true).take(2)
        )
    }

    @Test
    fun ladderNeverExceedsTheRequestedCap() {
        val ladder = scriptStageQualities("320k", fallback128k = true)
        assertEquals(listOf("320k", "128k"), ladder)
        assertFalse("320k cap must not leak the cascade's trailing flac upgrade", ladder.contains("flac"))
    }

    @Test
    fun fallback128kDisabledKeeps128kOutOfTheLadder() {
        assertEquals(listOf("flac", "320k"), scriptStageQualities("flac", fallback128k = false))
        assertEquals(listOf("320k"), scriptStageQualities("320k", fallback128k = false))
        assertEquals(
            listOf("master", "flac", "320k"),
            scriptStageQualities("master", fallback128k = false)
        )
    }

    @Test
    fun lowTierRequestsKeepLegacyCascadeOrder() {
        // ≤192k requests keep cascade's historical upward escape order untouched.
        assertEquals(listOf("128k", "320k", "192k"), scriptStageQualities("128k", fallback128k = true))
        assertEquals(listOf("128k", "320k", "192k"), scriptStageQualities("128k", fallback128k = false))
        assertEquals(listOf("192k", "128k", "320k"), scriptStageQualities("192k", fallback128k = true))
    }

    @Test
    fun blankPreferenceFallsBackTo320k() {
        assertEquals(listOf("320k", "128k"), scriptStageQualities("", fallback128k = true))
    }
}

@RunWith(RobolectricTestRunner::class)
class PlaybackQualityCompositionTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profile = ProfileId.of("quality-composition-profile")

    @Test
    fun platformUnsetUsesGlobalAsFirstRequest() {
        val effective = SmartSourceStore.platformPlayQuality(context, profile, "wy", "flac")
        assertEquals("flac", effective)
        assertEquals("flac", scriptStageQualities(effective, fallback128k = true).first())
    }

    @Test
    fun platformOverrideWinsOverGlobalAsFirstRequest() {
        SmartSourceStore.setPlatformPlayQuality(context, profile, "tx", "320k")
        val effective = SmartSourceStore.platformPlayQuality(context, profile, "tx", "flac")
        assertEquals("320k", effective)
        assertEquals("320k", scriptStageQualities(effective, fallback128k = true).first())
    }

    @Test
    fun platformHighTierStillDowngradesThroughFlac() {
        SmartSourceStore.setPlatformPlayQuality(context, profile, "kg", "master")
        val effective = SmartSourceStore.platformPlayQuality(context, profile, "kg", "320k")
        assertEquals("master", effective)
        assertEquals(
            listOf("master", "flac", "320k", "128k"),
            scriptStageQualities(effective, fallback128k = true)
        )
    }

    /** All app preferences are remapped to a unique Robolectric-only namespace. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "quality-composition-test-${System.nanoTime()}"

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$namespace-$name", mode)
    }
}
