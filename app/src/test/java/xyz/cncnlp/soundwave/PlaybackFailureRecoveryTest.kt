package xyz.cncnlp.soundwave

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFailureRecoveryTest {
    private val failedSong = Song(id = "failed", name = "Failed", singer = "Artist", source = "wy")

    @Test
    fun songOrGenerationMismatchIsRejectedBeforeRecoveryStarts() = runTest {
        var currentSong: Song? = failedSong
        var generation = 7
        var clears = 0
        var recoveries = 0
        val recovery = recovery(
            currentSong = { currentSong },
            currentGeneration = { generation },
            clearSongCache = { clears++ },
        )

        val staleFailures = listOf(
            "qq:other" to generation,
            failedSong.key() to generation + 1,
        )
        staleFailures.forEach { (songKey, failedGeneration) ->
            recovery.currentFailedSong(songKey, failedGeneration)?.let { song ->
                recovery.afterCacheClear(song, songKey, failedGeneration) { recoveries++ }
            }
        }
        runCurrent()

        assertEquals(0, clears)
        assertEquals(0, recoveries)
    }

    @Test
    fun currentFailureRecoversAfterCacheClear() = runTest {
        var cleared: Song? = null
        var recovered: Song? = null
        val recovery = recovery(
            currentSong = { failedSong },
            currentGeneration = { 12 },
            clearSongCache = { cleared = it },
        )

        val current = recovery.currentFailedSong(failedSong.key(), 12)
        assertSame(failedSong, current)
        recovery.afterCacheClear(requireNotNull(current), failedSong.key(), 12) { recovered = it }
        runCurrent()

        assertSame(failedSong, cleared)
        assertSame(failedSong, recovered)
    }

    @Test
    fun duplicateFailureCallbackIsClaimedOncePerPlaybackIntent() = runTest {
        val recovery = recovery(
            currentSong = { failedSong },
            currentGeneration = { 12 },
            clearSongCache = {},
        )

        assertTrue(recovery.claimFailure(failedSong.key(), generation = 12, intent = 4))
        assertEquals(false, recovery.claimFailure(failedSong.key(), generation = 12, intent = 4))
        assertTrue(recovery.claimFailure(failedSong.key(), generation = 13, intent = 4))
        assertTrue(recovery.claimFailure(failedSong.key(), generation = 12, intent = 5))
    }

    @Test
    fun switchingSongWhileCacheIsClearedSuppressesTheOldRetry() = runTest {
        val nextSong = Song(id = "next", name = "Next", singer = "Artist", source = "wy")
        var currentSong: Song? = failedSong
        var generation = 21
        var recoveries = 0
        val clearStarted = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        val recovery = recovery(
            currentSong = { currentSong },
            currentGeneration = { generation },
            clearSongCache = {
                clearStarted.complete(Unit)
                finishClear.await()
            },
        )

        val current = requireNotNull(recovery.currentFailedSong(failedSong.key(), generation))
        recovery.afterCacheClear(current, failedSong.key(), generation) { recoveries++ }
        runCurrent()
        assertTrue(clearStarted.isCompleted)

        currentSong = nextSong
        generation++
        finishClear.complete(Unit)
        runCurrent()

        assertEquals(0, recoveries)
    }

    private fun TestScope.recovery(
        currentSong: () -> Song?,
        currentGeneration: () -> Int,
        clearSongCache: suspend (Song) -> Unit,
    ) = PlaybackFailureRecovery(
        scope = backgroundScope,
        currentSong = currentSong,
        currentGeneration = currentGeneration,
        clearSongCache = clearSongCache,
    )
}
