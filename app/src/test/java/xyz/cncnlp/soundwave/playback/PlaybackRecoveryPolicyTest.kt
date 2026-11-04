package xyz.cncnlp.soundwave.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    private var now = 1_000_000L
    private fun policy(maxSkips: Int = 3) = PlaybackRecoveryPolicy(maxSkips) { now }

    @Test
    fun midSongDropoutRefreshesSameSourceAndResumesNearFailurePoint() {
        val p = policy()
        val d = p.onPlaybackError("wy:1", positionMs = 95_000L, durationMs = 240_000L)
        assertEquals(RecoveryAction.REFRESH_SAME_SOURCE, d.action)
        assertEquals(93_000L, d.resumeAtMs)
        assertEquals(1, d.attempt)
    }

    @Test
    fun startupFailureGoesStraightToSmartSourceSwitchFromZero() {
        val d = policy().onPlaybackError("wy:1", positionMs = 800L, durationMs = 0L)
        assertEquals(RecoveryAction.SWITCH_SOURCE, d.action)
        assertEquals(0L, d.resumeAtMs)
    }

    @Test
    fun shortTrackStartupAndEarlyErrorsAreNotNaturalEnds() {
        listOf(0L to 1L, 0L to 3_000L, 800L to 2_000L, 1_000L to 4_000L).forEach { (position, duration) ->
            val d = policy().onPlaybackError("short", position, duration)
            assertEquals("$position/$duration must recover, not silently advance", RecoveryAction.SWITCH_SOURCE, d.action)
            assertFalse(d.nearEnd)
        }
    }

    @Test
    fun repeatedShortTrackStartupFailuresStillExhaustTheSkipBudget() {
        val p = policy(maxSkips = 1)
        assertEquals(RecoveryAction.SWITCH_SOURCE, p.onPlaybackError("short:a", 0L, 2_000L).action)
        assertEquals(RecoveryAction.SKIP_NEXT, p.onPlaybackError("short:a", 0L, 2_000L).action)
        assertEquals(1, p.consecutiveSkipsForTests())
        assertEquals(RecoveryAction.SWITCH_SOURCE, p.onPlaybackError("short:b", 0L, 2_000L).action)
        assertEquals(RecoveryAction.STOP, p.onPlaybackError("short:b", 0L, 2_000L).action)
    }

    @Test
    fun shortTrackActualTailStillAdvancesWithoutConsumingSkipBudget() {
        val p = policy(maxSkips = 1)
        val d = p.onPlaybackError("short", 1_750L, 2_000L)
        assertEquals(RecoveryAction.SKIP_NEXT, d.action)
        assertTrue(d.nearEnd)
        assertEquals(0, p.consecutiveSkipsForTests())
    }

    @Test
    fun tailWindowIsCappedAtHalfTheDurationAndKeepsTheLongTrackBoundary() {
        listOf(3_000L to 1_500L, 6_000L to 3_000L, 240_000L to 237_000L).forEach { (duration, tailStart) ->
            assertFalse(policy().onPlaybackError("before-tail", tailStart - 1L, duration).nearEnd)
            assertTrue(policy().onPlaybackError("at-tail", tailStart, duration).nearEnd)
        }
    }

    @Test
    fun secondFailureAfterRefreshSwitchesSourceThenSkips() {
        val p = policy()
        assertEquals(RecoveryAction.REFRESH_SAME_SOURCE, p.onPlaybackError("kw:9", 60_000L, 200_000L).action)
        val second = p.onPlaybackError("kw:9", 61_000L, 200_000L)
        assertEquals(RecoveryAction.SWITCH_SOURCE, second.action)
        assertEquals(59_000L, second.resumeAtMs)
        assertEquals(RecoveryAction.SKIP_NEXT, p.onPlaybackError("kw:9", 0L, 0L).action)
    }

    @Test
    fun startupSwitchFailureSkipsOnSecondAttempt() {
        val p = policy()
        assertEquals(RecoveryAction.SWITCH_SOURCE, p.onPlaybackError("tx:2", 0L, 0L).action)
        assertEquals(RecoveryAction.SKIP_NEXT, p.onPlaybackError("tx:2", 0L, 0L).action)
    }

    @Test
    fun failureNearTheEndIsTreatedAsFinishedAndDoesNotConsumeSkipBudget() {
        val p = policy(maxSkips = 1)
        val d = p.onPlaybackError("kg:3", positionMs = 238_500L, durationMs = 240_000L)
        assertEquals(RecoveryAction.SKIP_NEXT, d.action)
        assertTrue(d.nearEnd)
        assertEquals(0, p.consecutiveSkipsForTests())
    }

    @Test
    fun consecutiveSkipLimitStopsInsteadOfDrainingTheWholeQueue() {
        val p = policy(maxSkips = 3)
        repeat(3) { i -> assertEquals(RecoveryAction.SKIP_NEXT, p.onResolveFailure("s:$i").action) }
        assertEquals(RecoveryAction.STOP, p.onResolveFailure("s:4").action)
    }

    @Test
    fun healthyPlaybackResetsTheSkipBudget() {
        val p = policy(maxSkips = 2)
        p.onResolveFailure("a")
        p.onResolveFailure("b")
        p.onHealthyPlayback("c")
        assertEquals(RecoveryAction.SKIP_NEXT, p.onResolveFailure("d").action)
    }

    @Test
    fun userIntentResetsBudgetAndAttempts() {
        val p = policy(maxSkips = 1)
        p.onResolveFailure("a")
        assertEquals(RecoveryAction.STOP, p.onResolveFailure("b").action)
        p.onUserIntent()
        assertEquals(RecoveryAction.SKIP_NEXT, p.onResolveFailure("c").action)
    }

    @Test
    fun sameSongFailingAgainLongAfterRecoveryGetsAFreshBudget() {
        val p = policy()
        assertEquals(RecoveryAction.REFRESH_SAME_SOURCE, p.onPlaybackError("wy:7", 30_000L, 600_000L).action)
        now += PlaybackRecoveryPolicy.ATTEMPT_WINDOW_MS + 1
        // 长歌第二次直链过期：仍然先原平台续播，而不是直接跳歌。
        assertEquals(RecoveryAction.REFRESH_SAME_SOURCE, p.onPlaybackError("wy:7", 400_000L, 600_000L).action)
    }

    @Test
    fun differentSongStartsItsOwnAttemptCount() {
        val p = policy()
        p.onPlaybackError("a", 0L, 0L)
        val d = p.onPlaybackError("b", 20_000L, 180_000L)
        assertEquals(1, d.attempt)
        assertEquals(RecoveryAction.REFRESH_SAME_SOURCE, d.action)
    }

    @Test
    fun automaticResolveFailureAdvancesOnlyWhenPolicyAndQueueAllowIt() {
        assertEquals(ResolveFailureDisposition.ADVANCE, resolveFailureDisposition(RecoveryAction.SKIP_NEXT, 2))
        assertEquals(ResolveFailureDisposition.STOP, resolveFailureDisposition(RecoveryAction.SKIP_NEXT, 1))
        assertEquals(ResolveFailureDisposition.STOP, resolveFailureDisposition(RecoveryAction.STOP, 3))
    }

    @Test
    fun loadRetryFailsFastOnExpiredLinksAndBacksOffOnServerErrors() {
        assertEquals(C.TIME_UNSET, PlaybackLoadRetry.delayMs(403, 1, 0L))
        assertEquals(C.TIME_UNSET, PlaybackLoadRetry.delayMs(404, 1, 0L))
        assertEquals(C.TIME_UNSET, PlaybackLoadRetry.delayMs(410, 1, 0L))
        assertEquals(2_000L, PlaybackLoadRetry.delayMs(429, 2, 0L))
        assertEquals(3_000L, PlaybackLoadRetry.delayMs(503, 3, 0L))
        assertEquals(4_000L, PlaybackLoadRetry.delayMs(502, 9, 0L))
        // 纯网络错误沿用 Media3 默认退避，但重试次数放宽。
        assertEquals(2_000L, PlaybackLoadRetry.delayMs(null, 3, 2_000L))
        assertTrue(PlaybackLoadRetry.MIN_RETRIES > 3)
    }
}
