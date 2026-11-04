package xyz.cncnlp.soundwave

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedSnapshotIoTest {
    @Test
    fun encodedSnapshotIsReadableFromCacheAndRoundTripsAllFeedResources() = runTest {
        val io = HomeFeedSnapshotIo(StandardTestDispatcher(testScheduler))
        val song = Song("song-1", "Track", "Artist", source = "wy", cover = "cover-url")
        val sourceState = FeedSourceState(
            source = "wy",
            fetchedAt = 100L,
            refreshedAt = 95L,
            hotCount = 1,
            rankCount = 1,
            playlistCount = 1,
            featuredPlaylistFetchedAt = 91L,
            hotSongsFetchedAt = 92L,
            rankBoardsFetchedAt = 93L,
            hotKeywordsFetchedAt = 94L
        )
        val snapshot = HomeFeedCache.Snapshot(
            hot = mapOf("wy" to listOf(song)),
            rank = mapOf("wy" to listOf(RankBoard("Top", source = "wy", songs = listOf(song), id = "rank-1"))),
            pl = mapOf("wy" to listOf(Playlist("pl-1", "Mix", source = "wy", songs = mutableListOf(song)))),
            keywords = mapOf("wy" to listOf("one", "two")),
            discover = mapOf("wy" to listOf(DiscoverCard("disc-1", "New", source = "wy", songId = "song-1"))),
            sourceStates = mapOf("wy" to sourceState)
        )
        val stored = arrayOfNulls<String>(1)
        val savedAt = 123_456L

        val save = launch {
            io.save(snapshot, writeRaw = { stored[0] = it }) {
                HomeFeedCache.encodeSnapshot(it, savedAt)
            }
        }
        runCurrent()
        save.join()
        val load = async { io.load { stored[0] } }
        runCurrent()
        val restored = load.await()

        assertEquals("cache hit restores the stored data", savedAt, restored.savedAt)
        assertEquals(snapshot.hot, restored.hot)
        assertEquals(snapshot.rank, restored.rank)
        assertEquals(snapshot.pl, restored.pl)
        assertEquals(snapshot.keywords, restored.keywords)
        assertEquals(snapshot.discover, restored.discover)
        assertEquals(snapshot.sourceStates, restored.sourceStates)
    }

    @Test
    fun newerSnapshotSupersedesAnOlderWriteQueuedForTheIoDispatcher() = runTest {
        val io = HomeFeedSnapshotIo(StandardTestDispatcher(testScheduler))
        val writtenSnapshots = mutableListOf<HomeFeedCache.Snapshot>()
        val older = HomeFeedCache.Snapshot(hot = mapOf("wy" to listOf(Song("old", "Old", "Artist"))))
        val newer = HomeFeedCache.Snapshot(hot = mapOf("wy" to listOf(Song("new", "New", "Artist"))))

        val oldWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            io.save(older, writeRaw = { writtenSnapshots += HomeFeedCache.decodeSnapshot(it) }) {
                HomeFeedCache.encodeSnapshot(it, savedAt = 1L)
            }
        }
        val newWrite = launch(start = CoroutineStart.UNDISPATCHED) {
            io.save(newer, writeRaw = { writtenSnapshots += HomeFeedCache.decodeSnapshot(it) }) {
                HomeFeedCache.encodeSnapshot(it, savedAt = 2L)
            }
        }
        runCurrent()
        joinAll(oldWrite, newWrite)

        assertEquals("superseded pending write is skipped", 1, writtenSnapshots.size)
        assertEquals(2L, writtenSnapshots.single().savedAt)
        assertEquals("new", writtenSnapshots.single().hot.getValue("wy").single().id)
    }

    @Test
    fun cancelledCallerLifecycleDoesNotStartQueuedReadOrWrite() = runTest {
        val io = HomeFeedSnapshotIo(StandardTestDispatcher(testScheduler))
        var readStarted = false
        var writeStarted = false
        val snapshot = HomeFeedCache.Snapshot(hot = mapOf("wy" to listOf(Song("song", "Song", "Artist"))))

        val readJob = launch(start = CoroutineStart.UNDISPATCHED) {
            io.load {
                readStarted = true
                "{}"
            }
        }
        val writeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            io.save(snapshot, writeRaw = { writeStarted = true })
        }
        readJob.cancel()
        writeJob.cancel()
        runCurrent()
        joinAll(readJob, writeJob)

        assertFalse("cancelled screen scope must not begin a queued cache read", readStarted)
        assertFalse("cancelled screen scope must not begin a queued cache write", writeStarted)
        assertTrue(readJob.isCancelled)
        assertTrue(writeJob.isCancelled)
    }
}
