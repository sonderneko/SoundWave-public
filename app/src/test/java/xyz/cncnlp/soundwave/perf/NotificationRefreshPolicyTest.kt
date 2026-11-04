package xyz.cncnlp.soundwave.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRefreshPolicyTest {
    private fun mayCommit(
        serviceIsCurrent: Boolean = true,
        requestToken: String = "song-a#4",
        currentToken: String = "song-a#4",
        requestSongKey: String? = "song-a",
        currentSongKey: String? = "song-a",
        requestGeneration: Int = 4,
        currentGeneration: Int = 4,
    ): Boolean = NotificationRefreshPolicy.mayCommitCover(
        serviceIsCurrent = serviceIsCurrent,
        requestToken = requestToken,
        currentToken = currentToken,
        requestSongKey = requestSongKey,
        currentSongKey = currentSongKey,
        requestGeneration = requestGeneration,
        currentGeneration = currentGeneration,
    )

    @Test fun currentServiceSongAndGenerationMayCommit() {
        assertTrue(mayCommit())
    }

    @Test fun destroyedOrReplacedServiceCannotCommit() {
        assertFalse(mayCommit(serviceIsCurrent = false))
    }

    @Test fun changedNotificationTokenCannotCommit() {
        assertFalse(mayCommit(currentToken = "song-b#5"))
    }

    @Test fun changedSongKeyCannotCommit() {
        assertFalse(mayCommit(currentSongKey = "song-b"))
    }

    @Test fun changedPlaybackGenerationCannotCommit() {
        assertFalse(mayCommit(currentGeneration = 5))
    }
}
