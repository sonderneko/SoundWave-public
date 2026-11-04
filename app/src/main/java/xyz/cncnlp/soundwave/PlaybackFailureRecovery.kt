package xyz.cncnlp.soundwave

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps a playback failure from recovering after its song or Service generation has gone stale.
 * The cache clear is asynchronous; identity must be checked again before the caller retries.
 */
internal class PlaybackFailureRecovery(
    private val scope: CoroutineScope,
    private val currentSong: () -> Song?,
    private val currentGeneration: () -> Int,
    private val clearSongCache: suspend (Song) -> Unit,
) {
    private data class FailureIdentity(val songKey: String, val generation: Int, val intent: Int)
    private var lastClaimedFailure: FailureIdentity? = null

    fun currentFailedSong(songKey: String, generation: Int): Song? =
        currentSong()?.takeIf { it.key() == songKey && currentGeneration() == generation }

    /** A repeated callback for one failed media intent must not consume recovery budget twice. */
    @Synchronized
    fun claimFailure(songKey: String, generation: Int, intent: Int): Boolean {
        val identity = FailureIdentity(songKey, generation, intent)
        if (lastClaimedFailure == identity) return false
        lastClaimedFailure = identity
        return true
    }

    fun afterCacheClear(
        failedSong: Song,
        songKey: String,
        generation: Int,
        recover: suspend (Song) -> Unit,
    ) {
        scope.launch {
            clearSongCache(failedSong)
            if (currentFailedSong(songKey, generation) == null) return@launch
            recover(failedSong)
        }
    }
}
