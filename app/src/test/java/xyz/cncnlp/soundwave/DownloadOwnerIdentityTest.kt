package xyz.cncnlp.soundwave

import org.junit.Test
import org.junit.Assert.*
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/** Stable task identity and backward-compatibility regression tests. */
class DownloadOwnerIdentityTest {
    @Test fun javaHashCollisionsCannotAliasNewDownloadJobs() {
        val a = Song(id = "Aa", name = "a", singer = "s", source = "wy")
        val b = Song(id = "BB", name = "b", singer = "s", source = "wy")
        assertNotEquals(DownloadScheduler.taskId(a, "320k"), DownloadScheduler.taskId(b, "320k"))
    }

    @Test fun idlessSongsThatCollideInPlaybackKeyGetDistinctDownloadTasks() {
        val spaced = Song(id = "", name = "A B", singer = "Artist", source = "wy", duration = 180_000L)
        val compact = spaced.copy(name = "AB")

        assertEquals(spaced.key(), compact.key())
        assertNotEquals(DownloadScheduler.taskId(spaced, "320k"), DownloadScheduler.taskId(compact, "320k"))
        assertNotEquals(
            DownloadScheduler.taskId(spaced.copy(source = ""), "320k"),
            DownloadScheduler.taskId(spaced.copy(source = "wy"), "320k")
        )
    }

    @Test fun providerIdIsStableAcrossMetadataEditsAndPreviouslyQueuedV2IdsRemainAccepted() {
        val song = Song(id = "track-7", name = "Original", singer = "Artist", source = "TX")
        assertEquals(DownloadScheduler.taskId(song, "flac"), DownloadScheduler.taskId(song.copy(name = "Renamed"), "flac"))

        val previousV2 = xyz.cncnlp.soundwave.download.DownloadTaskIdentity.previousV2TaskId(song, "flac")
        assertTrue(DownloadScheduler.isSupportedTaskId(previousV2, song, "flac"))
        assertTrue(DownloadScheduler.isSupportedTaskId(DownloadScheduler.oldTaskId(song, "flac"), song, "flac"))
    }

    @Test fun unknownOwnerIsNeverReboundToRuntime() {
        assertNull(ProfileWorkIdentity.decode("profile:unknown", 0))
        assertNull(ProfileWorkIdentity.decode("bad-owner", 0))
        assertNotNull(ProfileWorkIdentity.decode(null, 0))
    }
}
