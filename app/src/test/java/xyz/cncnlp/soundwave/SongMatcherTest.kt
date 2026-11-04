package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMatcherTest {
    private fun song(
        id: String,
        name: String,
        singer: String,
        album: String = "",
        duration: Long = 180_000L
    ) = Song(id = id, name = name, singer = singer, album = album, duration = duration)

    @Test
    fun exactIdentityWithArtistsInDifferentOrderIsAccepted() {
        val source = song("a", "Stay (Live)", "Artist A / Artist B", "Live at Home")
        val candidate = song("b", "stay【live】", "Artist B、Artist A", "Live at Home")

        assertEquals(100, SongMatcher.score(source, candidate))
    }

    @Test
    fun differentArtistsAreRejectedEvenWhenTitleMatches() {
        val source = song("a", "Stay", "Artist A")
        val candidate = song("b", "Stay", "Artist B")

        assertNull(SongMatcher.score(source, candidate))
    }

    @Test
    fun differentVersionMarkersAreRejected() {
        val source = song("a", "Stay (Live)", "Artist A")
        val candidate = song("b", "Stay", "Artist A", "Studio Version")

        assertNull(SongMatcher.score(source, candidate))
    }

    @Test
    fun durationDriftBeyondSevenSecondsIsRejected() {
        val source = song("a", "Stay", "Artist A", duration = 180_000L)
        val candidate = song("b", "Stay", "Artist A", duration = 187_001L)

        assertNull(SongMatcher.score(source, candidate))
    }

    @Test
    fun missingDurationIsRejectedForAutomaticCrossSourcePlayback() {
        val source = song("a", "Stay", "Artist A", duration = 0L)
        val candidate = song("b", "Stay", "Artist A", duration = 180_000L)

        assertEquals("duration_missing", SongMatcher.rejectReason(source, candidate))
        assertNull(SongMatcher.score(source, candidate))
    }
}
