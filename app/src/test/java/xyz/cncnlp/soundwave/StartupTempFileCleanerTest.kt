package xyz.cncnlp.soundwave

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.cncnlp.soundwave.startup.StartupTempFileCleaner

class StartupTempFileCleanerTest {
    @Rule @JvmField val temporaryFolder = TemporaryFolder()

    @Test
    fun `startup cleanup removes only abandoned decrypt staging files`() {
        val root = temporaryFolder.newFolder("startup-cleanup")
        val mediaDir = File(root, "cache/media_decrypt").apply { assertTrue(mkdirs()) }
        val downloadDir = File(root, "external/Music/SoundWave").apply { assertTrue(mkdirs()) }
        val coverDir = File(root, "files/covers").apply { assertTrue(mkdirs()) }
        val lyricDir = File(root, "files/lyrics").apply { assertTrue(mkdirs()) }

        val encryptedPart = File(mediaDir, "dec_song-key.mflac.part").apply { writeText("cipher") }
        val plaintextPart = File(mediaDir, "dec_song-key.mflac.plain.part").apply { writeText("partial") }
        val cacheId = MediaDecryptCache.cacheFileId("startup-cleanup-key")
        val hashedEncryptedPart = File(mediaDir, "dec_${cacheId}.tkm.part").apply { writeText("cipher") }
        val hashedPlaintextPart = File(mediaDir, "dec_${cacheId}.tkm.plain.part").apply { writeText("partial") }
        val activePart = File(mediaDir, "dec_active.mflac.part").apply { writeText("cipher") }
        val activeLease = MediaCacheLeaseManager.acquireExisting(activePart)!!
        val cachedAudio = File(mediaDir, "dec_song-key.flac").apply { writeText("audio") }
        val unsupportedMediaPart = File(mediaDir, "dec_song-key.mp3.part").apply { writeText("keep") }

        val encryptedDownloadTemp = File.createTempFile("sw-task_123-", ".mflac.plain.part", downloadDir)
        val regularDownloadTemp = File.createTempFile("sw-task_123-", ".mp3.plain.part", downloadDir)
        val completedAudio = File(downloadDir, "Song-Singer.mp3").apply { writeText("audio") }
        val resumablePart = File(downloadDir, ".Song-Singer-task_123.mflac.part").apply { writeText("resume") }
        val abandonedCoverPart = File(coverDir, "cover-0123456789ab-abcd1234.part").apply { writeText("partial") }
        val activeCoverPart = File(coverDir, "cover-abcdef012345-active123.part").apply { writeText("partial") }
        val activeCoverLease = MediaCacheLeaseManager.acquireExisting(activeCoverPart)!!
        val unrelatedCoverPart = File(coverDir, "other-image.part").apply { writeText("keep") }
        val abandonedLyricPart = File(lyricDir, "sw-cache-12345678.part").apply { writeText("partial") }
        val completedLyric = File(lyricDir, "lyric-v3-complete.lrc").apply { writeText("complete") }
        val unrelatedName = File(downloadDir, "sw-task_123.mflac.plain.part").apply { writeText("keep") }
        val unrelatedExtension = File(downloadDir, "sw-task_123-123456.xyz.plain.part").apply { writeText("keep") }
        val matchingDirectory = File(downloadDir, "sw-task_123-123456.mflac.plain.part").apply { assertTrue(mkdirs()) }
        val nestedTemp = File(matchingDirectory, "sw-task_123-123456.mp3.plain.part").apply { writeText("keep") }

        val removed = try {
            StartupTempFileCleaner.cleanup(mediaDir, downloadDir, coverDir, lyricDir)
        } finally {
            activeLease.close()
            activeCoverLease.close()
        }

        assertEquals(8, removed)
        assertFalse(encryptedPart.exists())
        assertFalse(plaintextPart.exists())
        assertFalse(hashedEncryptedPart.exists())
        assertFalse(hashedPlaintextPart.exists())
        assertTrue("startup cleanup must leave a staging file owned by an active decrypt", activePart.isFile)
        assertTrue("startup cleanup must leave a staging file protected by a lease", activeCoverPart.isFile)
        assertEquals(2, StartupTempFileCleaner.cleanup(mediaDir, downloadDir, coverDir, lyricDir))
        assertFalse(activePart.exists())
        assertFalse(activeCoverPart.exists())
        assertFalse(abandonedCoverPart.exists())
        assertFalse(abandonedLyricPart.exists())
        assertTrue(unrelatedCoverPart.isFile)
        assertTrue(completedLyric.isFile)
        assertFalse(encryptedDownloadTemp.exists())
        assertFalse(regularDownloadTemp.exists())
        assertTrue(cachedAudio.isFile)
        assertTrue(unsupportedMediaPart.isFile)
        assertTrue(completedAudio.isFile)
        assertTrue(resumablePart.isFile)
        assertTrue(unrelatedName.isFile)
        assertTrue(unrelatedExtension.isFile)
        assertTrue(matchingDirectory.isDirectory)
        assertTrue(nestedTemp.isFile)
    }
}
