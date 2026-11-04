package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class MediaCacheLeaseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `multiple owners protect a file until the final lease closes`() {
        val file = File(context.cacheDir, "media_decrypt/lease-${uniqueId()}.flac")
        assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.writeText("playable")
        val first = MediaCacheLeaseManager.acquireExisting(file)!!
        val second = MediaCacheLeaseManager.acquireExisting(file)!!

        try {
            assertFalse(MediaCacheLeaseManager.deleteIfUnleased(file))
            first.close()
            assertFalse(MediaCacheLeaseManager.deleteIfUnleased(file))
            second.close()
            assertTrue(MediaCacheLeaseManager.deleteIfUnleased(file))
        } finally {
            first.close()
            second.close()
            file.parentFile?.delete()
        }
    }

    @Test
    fun `local playback lease prevents download replacement and manual deletion`() {
        val file = File(context.cacheDir, "download-lease-${uniqueId()}.mp3").apply { writeText("audio") }
        val item = DownloadItem(
            song = Song(id = "lease-${uniqueId()}", name = "Fixture", singer = "Local", source = "wy"),
            path = file.absolutePath,
            size = file.length(),
            time = 1L
        )
        val lease = MediaCacheLeaseManager.acquireForPlayback(file.toURI().toString())!!
        try {
            assertFalse("a download replacement must not unlink media attached to a player",
                DownloadHelper.deleteOutputPath(context, file.absolutePath, "new-output.mp3"))
            DownloadHelper.deleteArtifact(context, item)
            assertTrue("manual download deletion must preserve an active local file", file.isFile)
        } finally { lease.close() }

        assertTrue(DownloadHelper.deleteOutputPath(context, file.absolutePath, "new-output.mp3"))
        assertFalse(file.exists())
    }

    @Test
    fun `content uri playback lease fences provider deletion until close`() {
        val uri = "content://fixture.media/audio/42"
        val lease = MediaCacheLeaseManager.acquireForPlayback(uri)!!
        var deleted = false
        try {
            assertFalse(MediaCacheLeaseManager.deleteContentUriIfUnleased(uri) { deleted = true; true })
            assertFalse(deleted)
        } finally { lease.close() }
        assertTrue(MediaCacheLeaseManager.deleteContentUriIfUnleased(uri) { deleted = true; true })
        assertTrue(deleted)
    }

    @Test
    fun `clear reports live staging and playback leases then succeeds after release`() = runBlocking {
        PlayerService.resetSimpleCacheForTests()
        val key = "lease_${uniqueId()}"
        val entered = CountDownLatch(1)
        val continueDownload = CountDownLatch(1)
        val lock = KeyedOperationLock<String>()
        val task = async(Dispatchers.IO) {
            MediaDecryptCache.prepareWithLease(
                context,
                "https://media.invalid/test.mflac",
                key,
                "mflac",
                lock,
                { _, out ->
                    out.writeText("partial-cipher")
                    entered.countDown()
                    check(continueDownload.await(5, TimeUnit.SECONDS))
                    out.writeText("cipher")
                    true
                },
                { input, output, _ ->
                    output.writeBytes(input.readBytes() + "-plain".toByteArray())
                    "flac"
                }
            )
        }
        var playbackLease: MediaCacheLease? = null
        try {
            assertTrue("download should reach its protected staging file", entered.await(5, TimeUnit.SECONDS))
            val duringDownload = CacheManager.clearAll(context)
            assertEquals("both reserved staging paths must be reported", 2, duringDownload.skippedActiveFiles)
            val cacheId = MediaDecryptCache.cacheFileId(key)
            assertTrue(File(context.cacheDir, "media_decrypt/dec_${cacheId}.mflac.part").exists())

            continueDownload.countDown()
            playbackLease = withTimeout(5_000) { task.await() }
            assertNotNull(playbackLease)
            assertEquals("cipher-plain", playbackLease!!.file.readText())
            val completedFile = playbackLease!!.file

            val whilePlaying = CacheManager.clearAll(context)
            assertEquals(1, whilePlaying.skippedActiveFiles)
            assertTrue("an attached playback file must survive manual clear", playbackLease!!.file.exists())

            playbackLease!!.close()
            playbackLease = null
            val afterRelease = CacheManager.clearAll(context)
            assertEquals(0, afterRelease.skippedActiveFiles)
            assertFalse("a later clear should remove the released file", completedFile.exists())
        } finally {
            continueDownload.countDown()
            playbackLease?.close()
            if (task.isActive) task.cancel()
            PlayerService.resetSimpleCacheForTests()
        }
    }

    @Test
    fun `lease acquisition and unlink are atomic against each other`() {
        val root = File(context.cacheDir, "lease-race-${uniqueId()}").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(40) { index ->
                val file = File(root, "$index.bin").apply { writeText("media") }
                val start = CountDownLatch(1)
                val acquired = AtomicReference<MediaCacheLease?>()
                val acquire = pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    acquired.set(MediaCacheLeaseManager.acquireExisting(file))
                }
                val unlink = pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    MediaCacheLeaseManager.deleteIfUnleased(file)
                }
                start.countDown()
                acquire.get(5, TimeUnit.SECONDS)
                unlink.get(5, TimeUnit.SECONDS)

                val lease = acquired.get()
                if (lease == null) {
                    assertFalse("unlink-first means acquisition must observe a missing file", file.exists())
                } else {
                    try {
                        assertTrue("acquire-first means the file stays present", file.isFile)
                        assertFalse(MediaCacheLeaseManager.deleteIfUnleased(file))
                    } finally {
                        lease.close()
                    }
                    assertTrue(MediaCacheLeaseManager.deleteIfUnleased(file))
                }
            }
        } finally {
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }

    private fun uniqueId(): String = UUID.randomUUID().toString().replace("-", "")
}
