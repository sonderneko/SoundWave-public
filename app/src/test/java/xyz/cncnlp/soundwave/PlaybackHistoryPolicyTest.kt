package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHistoryPolicyTest {
    @Test
    fun localCutoffIncludesExactlySevenDaysAndExcludesOlderUnknownAndFutureRecords() {
        val now = 2_000_000_000_000L
        val boundary = song("boundary", now - PlaybackHistoryPolicy.RETENTION_MS)
        val expired = song("expired", now - PlaybackHistoryPolicy.RETENTION_MS - 1L)
        val unknown = song("unknown", 0L)
        val future = song("future", now + 1L)
        val recent = song("recent", now - 1L)

        val kept = PlaybackHistoryPolicy.retainRecent(
            listOf(expired, unknown, future, boundary, recent),
            nowMs = now
        )

        assertEquals(listOf("recent", "boundary"), kept.map { it.id })
        assertTrue(PlaybackHistoryPolicy.isRetained(boundary, now))
        assertFalse(PlaybackHistoryPolicy.isRetained(expired, now))
    }

    @Test
    fun cloudCutoffIncludesThirtyDaysButExcludesOlderRecords() {
        val now = 2_000_000_000_000L
        val boundary = song("boundary", now - PlaybackHistoryPolicy.CLOUD_RETENTION_MS)
        val expired = song("expired", now - PlaybackHistoryPolicy.CLOUD_RETENTION_MS - 1L)
        val recent = song("recent", now - 1L)

        assertEquals(listOf("recent", "boundary"), PlaybackHistoryPolicy.retainForCloudSync(
            listOf(expired, boundary, recent), nowMs = now
        ).map { it.id })
        assertTrue(PlaybackHistoryPolicy.isRetainedForCloudSync(boundary, now))
        assertFalse(PlaybackHistoryPolicy.isRetainedForCloudSync(expired, now))
    }

    @Test
    fun cloudHistoryKeepsAtMostThreeHundredNewestUniqueSongs() {
        val now = 2_000_000_000_000L
        val songs = (0..304).map { index -> song("song-$index", now - index.toLong()) }

        val retained = PlaybackHistoryPolicy.retainForCloudSync(songs, nowMs = now)

        assertEquals(PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT, retained.size)
        assertEquals("song-0", retained.first().id)
        assertFalse(retained.any { it.id == "song-304" })
    }

    @Test
    fun historyDeletionTombstoneTargetsOnePlayAndExpiresAfterSyncWindow() {
        val now = 2_000_000_000_000L
        val deletedPlay = song("same-track", now - 2_000L)
        val replay = song("same-track", now - 1_000L)
        val deletionKey = PlaybackHistoryPolicy.historyEventKey(deletedPlay)
        val active = mapOf(deletionKey to now - 100L)

        assertTrue(deletionKey.matches(Regex("h1:[0-9a-f]{64}")))
        assertEquals(listOf(replay), PlaybackHistoryPolicy.withoutDeletedHistory(
            listOf(deletedPlay, replay), active, nowMs = now
        ))
        assertTrue(PlaybackHistoryPolicy.mergeHistoryDeletionTombstones(
            active,
            mapOf(deletionKey to now - 200L),
            nowMs = now
        ).containsKey(deletionKey))
        assertTrue(PlaybackHistoryPolicy.retainHistoryDeletionTombstones(
            mapOf(deletionKey to now - PlaybackHistoryPolicy.HISTORY_DELETE_TOMBSTONE_RETENTION_MS - 1L),
            nowMs = now
        ).isEmpty())
    }

    @Test
    fun localHistoryHasNoCloudThreeHundredSongLimit() {
        val now = 2_000_000_000_000L
        val songs = (0..304).map { index -> song("local-song-$index", now - index.toLong()) }

        val retained = PlaybackHistoryPolicy.retainRecent(songs, nowMs = now)

        assertEquals(305, retained.size)
        assertEquals(PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT, PlaybackHistoryPolicy.retainForCloudSync(songs, nowMs = now).size)
    }

    @Test
    fun duplicateIdentityKeepsNewestRecordAndStableNewestFirstOrdering() {
        val now = 2_000_000_000_000L
        val duplicateOld = song("same", now - 9_000L, name = "old")
        val duplicateNew = song("same", now - 1_000L, name = "new")
        val equalA = song("equal-a", now - 2_000L)
        val equalB = song("equal-b", now - 2_000L)

        val result = PlaybackHistoryPolicy.retainRecent(
            listOf(duplicateOld, duplicateNew, equalB, equalA),
            nowMs = now
        )

        assertEquals(listOf("same", "equal-a", "equal-b"), result.map { it.id })
        assertEquals("new", result.first().name)
    }

    private fun song(id: String, playedAt: Long, name: String = id) = Song(
        id = id,
        name = name,
        singer = "Artist",
        source = "wy",
        playedAt = playedAt
    )
}
