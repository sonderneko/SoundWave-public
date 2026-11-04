package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DetailResumePolicyTest {
    private fun song(
        id: String,
        name: String = id,
        singer: String = "Artist",
        source: String = "tx",
        playedAt: Long = 0L,
        cover: String = "",
        resolvedUrl: String = "",
    ) = Song(
        id = id,
        name = name,
        singer = singer,
        source = source,
        playedAt = playedAt,
        cover = cover,
        resolvedUrl = resolvedUrl,
    )

    @Test
    fun candidateUsesNewestRetainedHistoryRegardlessOfInputOrder() {
        val now = 2_000_000_000_000L
        val older = song("a", playedAt = now - 100L)
        val middle = song("b", playedAt = now - 75L)
        val newest = song("b", playedAt = now - 25L)
        val currentA = song("a", name = "Current A")
        val currentB = song("b", name = "Current B")

        assertSame(currentB, DetailResumePolicy.candidate(
            songs = listOf(currentA, currentB),
            history = listOf(newest, older, middle),
            nowMs = now,
        ))
    }

    @Test
    fun sameIdFromDifferentPlatformsDoesNotMatch() {
        val now = 2_000_000_000_000L

        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("shared-id", source = "wy")),
            history = listOf(song("shared-id", source = "tx", playedAt = now - 1L)),
            nowMs = now,
        ))
    }

    @Test
    fun sameTitleWithDifferentIdsDoesNotMatch() {
        val now = 2_000_000_000_000L

        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("catalog-id", name = "Same Song", singer = "Same Artist")),
            history = listOf(song("other-id", name = "Same Song", singer = "Same Artist", playedAt = now - 1L)),
            nowMs = now,
        ))
    }

    @Test
    fun removedTrackIsNotReturnedWhenAbsentFromCurrentDetailSongs() {
        val now = 2_000_000_000_000L

        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("still-present")),
            history = listOf(song("removed", playedAt = now - 1L)),
            nowMs = now,
        ))
    }

    @Test
    fun emptyInputsAndUnknownOrEmptyIdentitiesDoNotProduceCandidates() {
        val now = 2_000_000_000_000L
        val retained = now - 1L
        val valid = song("valid", playedAt = retained)

        assertNull(DetailResumePolicy.candidate(emptyList(), listOf(valid), now))
        assertNull(DetailResumePolicy.candidate(listOf(song("valid")), emptyList(), now))
        assertNull(DetailResumePolicy.candidate(emptyList(), emptyList(), now))
        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("unknown-source", source = "")),
            history = listOf(song("unknown-source", source = "", playedAt = retained)),
            nowMs = now,
        ))
        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("", source = "tx")),
            history = listOf(song("", source = "tx", playedAt = retained)),
            nowMs = now,
        ))
        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("   ", source = "tx")),
            history = listOf(song("   ", source = "tx", playedAt = retained)),
            nowMs = now,
        ))
        // Invalid history identities must not match a valid current catalog entry.
        assertNull(DetailResumePolicy.candidate(
            songs = listOf(song("valid", source = "tx")),
            history = listOf(song("valid", source = "", playedAt = retained)),
            nowMs = now,
        ))
    }

    @Test
    fun zeroFutureAndExpiredPlaybackTimesAreNotRetained() {
        val now = 2_000_000_000_000L
        val expired = now - PlaybackHistoryPolicy.LOCAL_RETENTION_MS - 1L
        val future = now + 1L

        listOf(0L, future, expired).forEach { playedAt ->
            assertNull("playedAt=$playedAt", DetailResumePolicy.candidate(
                songs = listOf(song("track")),
                history = listOf(song("track", playedAt = playedAt)),
                nowMs = now,
            ))
        }
    }

    @Test
    fun duplicateHistoryUsesNewestOccurrenceWhenChoosingAmongTracks() {
        val now = 2_000_000_000_000L
        val currentA = song("a")
        val currentB = song("b")
        val oldA = song("a", playedAt = now - 300L)
        val historyB = song("b", playedAt = now - 200L)
        val newestA = song("a", playedAt = now - 100L)

        assertSame(currentA, DetailResumePolicy.candidate(
            songs = listOf(currentB, currentA),
            history = listOf(oldA, historyB, newestA),
            nowMs = now,
        ))
    }

    @Test
    fun equalPlaybackTimesAreResolvedByStableSongKeyOrder() {
        val now = 2_000_000_000_000L
        val firstByKey = song("a", name = "Key A")
        val secondByKey = song("z", name = "Key Z")
        val historyA = song("a", playedAt = now - 100L)
        val historyZ = song("z", playedAt = now - 100L)

        assertSame(firstByKey, DetailResumePolicy.candidate(
            songs = listOf(secondByKey, firstByKey),
            history = listOf(historyZ, historyA),
            nowMs = now,
        ))
        assertSame(firstByKey, DetailResumePolicy.candidate(
            songs = listOf(firstByKey, secondByKey),
            history = listOf(historyA, historyZ),
            nowMs = now,
        ))
    }

    @Test
    fun candidateReturnsCurrentCatalogObjectAndMetadataRatherThanHistoryRecord() {
        val now = 2_000_000_000_000L
        val current = song(
            id = "track",
            name = "Updated title",
            singer = "Updated artist",
            source = "TX",
            cover = "current-cover",
            resolvedUrl = "https://current.example/audio",
        )
        val oldHistory = song(
            id = "track",
            name = "Old title",
            singer = "Old artist",
            source = "tx",
            playedAt = now - 1L,
            cover = "old-cover",
            resolvedUrl = "https://old.example/audio",
        )

        val result = DetailResumePolicy.candidate(listOf(current), listOf(oldHistory), now)

        assertSame(current, result)
        assertEquals("Updated title", result?.name)
        assertEquals("current-cover", result?.cover)
        assertEquals("https://current.example/audio", result?.resolvedUrl)
    }

    @Test
    fun labelIncludesNameAndNonEmptySinger() {
        assertEquals("Song - Singer", DetailResumePolicy.label(song("id", name = "Song", singer = "Singer")))
    }

    @Test
    fun labelOmitsEmptySinger() {
        assertEquals("Song", DetailResumePolicy.label(song("id", name = "Song", singer = "")))
    }
}
