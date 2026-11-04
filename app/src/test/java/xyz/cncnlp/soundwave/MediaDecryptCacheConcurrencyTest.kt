package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class MediaDecryptCacheConcurrencyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `overlapping same-key prepares share one writer and the second uses the completed cache`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloads = AtomicInteger()
        val activeWriters = AtomicInteger()
        val maxWriters = AtomicInteger()
        val downloader: suspend (String, File) -> Boolean = { _, out ->
            downloads.incrementAndGet()
            val active = activeWriters.incrementAndGet()
            maxWriters.updateAndGet { maxOf(it, active) }
            try {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                out.writeText("cipher")
                true
            } finally {
                activeWriters.decrementAndGet()
            }
        }

        try {
            val first = async(Dispatchers.IO) { prepare(key, lock, downloader) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { prepare(key, lock, downloader) }
            awaitReferences(lock, key, 2)
            assertEquals(1, downloads.get())
            assertEquals(1, activeWriters.get())
            release.countDown()

            val paths = awaitAll(first, second)
            assertNotNull(paths[0])
            assertEquals(paths[0], paths[1])
            assertEquals(1, downloads.get())
            assertEquals(1, maxWriters.get())
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            release.countDown()
            clean(key)
        }
    }

    @Test
    fun `different cache keys can download concurrently`() = runBlocking {
        val firstKey = uniqueKey()
        val secondKey = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val bothEntered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val activeWriters = AtomicInteger()
        val maxWriters = AtomicInteger()
        val downloader: suspend (String, File) -> Boolean = { _, out ->
            val active = activeWriters.incrementAndGet()
            maxWriters.updateAndGet { maxOf(it, active) }
            try {
                bothEntered.countDown()
                check(bothEntered.await(5, TimeUnit.SECONDS))
                check(release.await(5, TimeUnit.SECONDS))
                out.writeText("cipher")
                true
            } finally {
                activeWriters.decrementAndGet()
            }
        }

        try {
            val first = async(Dispatchers.IO) { prepare(firstKey, lock, downloader) }
            val second = async(Dispatchers.IO) { prepare(secondKey, lock, downloader) }
            assertTrue("different keys should reach download together", bothEntered.await(5, TimeUnit.SECONDS))
            assertEquals(2, maxWriters.get())
            release.countDown()
            val results = awaitAll(first, second)
            assertTrue(results.all { it != null })
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            release.countDown()
            clean(firstKey)
            clean(secondKey)
        }
    }

    @Test
    fun `punctuation and long prefix collisions keep separate files and locks`() = runBlocking {
        val keyPairs = listOf(
            "wy:A:B|320k" to "wy:A_B|320k",
            ("x".repeat(70) + "first") to ("x".repeat(70) + "second")
        )

        keyPairs.forEach { (firstKey, secondKey) ->
            val lock = KeyedOperationLock<String>()
            val bothDownloadsEntered = CountDownLatch(2)
            suspend fun prepareTagged(key: String, tag: String): String? = MediaDecryptCache.prepareWith(
                context,
                "https://media.invalid/$tag.mflac",
                key,
                "mflac",
                lock,
                { _, out ->
                    bothDownloadsEntered.countDown()
                    check(bothDownloadsEntered.await(3, TimeUnit.SECONDS))
                    out.writeText(tag)
                    true
                },
                { input, output, _ -> output.writeBytes(input.readBytes()); "flac" }
            )

            try {
                val first = async(Dispatchers.IO) { prepareTagged(firstKey, "first") }
                val second = async(Dispatchers.IO) { prepareTagged(secondKey, "second") }
                val paths = awaitAll(first, second)
                assertEquals("both distinct full keys should enter their writers", 0L, bothDownloadsEntered.count)
                assertNotNull(paths[0])
                assertNotNull(paths[1])
                assertNotEquals(paths[0], paths[1])
                assertEquals("first", File(paths[0]!!).readText())
                assertEquals("second", File(paths[1]!!).readText())
                assertEquals(0, lock.trackedKeysForTests())
            } finally {
                clean(firstKey)
                clean(secondKey)
            }
        }
    }

    @Test
    fun `cache lookup rejects a filename with only an extended hash stem`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val cacheId = MediaDecryptCache.cacheFileId(key)
        val falseHit = File(mediaDir(), "dec_${cacheId}.flac.flac").apply {
            assertTrue(parentFile!!.mkdirs() || parentFile!!.isDirectory)
            writeText("wrong")
        }
        val downloads = AtomicInteger()

        try {
            val result = MediaDecryptCache.prepareWith(
                context,
                "https://media.invalid/test.mflac",
                key,
                "mflac",
                lock,
                { _, out -> downloads.incrementAndGet(); out.writeText("cipher"); true },
                { _, out, _ -> out.writeText("correct"); "flac" }
            )
            assertNotNull(result)
            assertEquals("correct", File(result!!).readText())
            assertEquals(1, downloads.get())
        } finally {
            clean(key)
            falseHit.delete()
        }
    }

    @Test
    fun `encrypted format detection uses only the normalized final path extension`() {
        assertEquals("mflac", MediaDecryptCache.encryptedExtOf("https://media.invalid/song.MFLAC?token=abc"))
        assertEquals("mflac", MediaDecryptCache.encryptedExtOf("https://media.invalid/song.QMCFLAC"))
        assertEquals("mgg", MediaDecryptCache.encryptedExtOf("https://media.invalid/song.QMCOGG#fragment"))
        assertNull(MediaDecryptCache.encryptedExtOf("https://media.invalid/song.flac?name=track.mflac"))
        assertNull(MediaDecryptCache.encryptedExtOf("https://media.invalid/song.mflac/stream"))
        assertNull(MediaDecryptCache.encryptedExtOf("https://media.invalid/song.flac?token=abc"))
    }

    @Test
    fun `cancelling a waiting duplicate leaves the active preparation intact`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val downloads = AtomicInteger()
        val downloader: suspend (String, File) -> Boolean = { _, out ->
            downloads.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            out.writeText("cipher")
            true
        }

        try {
            val active = async(Dispatchers.IO) { prepare(key, lock, downloader) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiting = async(Dispatchers.IO) { prepare(key, lock, downloader) }
            awaitReferences(lock, key, 2)
            waiting.cancel()
            val cancellation = runCatching { waiting.await() }.exceptionOrNull()
            assertTrue(cancellation is CancellationException)
            awaitReferences(lock, key, 1)
            assertTrue("cancelling a waiter must not cancel the writer", active.isActive)
            assertEquals(1, downloads.get())

            release.countDown()
            val result = active.await()
            assertNotNull(result)
            assertTrue(File(result!!).isFile)
            assertEquals(1, downloads.get())
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            release.countDown()
            clean(key)
        }
    }

    @Test
    fun `failed download cleans staging so a later attempt can succeed`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val attempts = AtomicInteger()
        val downloader: suspend (String, File) -> Boolean = { _, out ->
            if (attempts.incrementAndGet() == 1) {
                out.writeText("partial")
                false
            } else {
                out.writeText("cipher")
                true
            }
        }

        try {
            assertNull(prepare(key, lock, downloader))
            assertStagingClean(key)
            assertEquals(0, lock.trackedKeysForTests())

            val result = prepare(key, lock, downloader)
            assertNotNull(result)
            assertTrue(File(result!!).isFile)
            assertEquals(2, attempts.get())
            assertStagingClean(key)
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            clean(key)
        }
    }

    @Test
    fun `failed atomic install removes a partial final plaintext and releases its lease`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val target = File(mediaDir(), "dec_${MediaDecryptCache.cacheFileId(key)}.flac")

        try {
            val result = MediaDecryptCache.prepareWithLease(
                context,
                "https://media.invalid/test.mflac",
                key,
                "mflac",
                lock,
                { _, out -> out.writeText("cipher"); true },
                { _, out, _ -> out.writeText("plaintext"); "flac" },
                installDecryptedOutput = { _, finalFile ->
                    finalFile.writeText("partial-plaintext")
                    throw java.io.IOException("injected atomic rename failure")
                }
            )

            assertNull(result)
            assertFalse("partial plaintext must not survive under the final cache name", target.exists())
            assertTrue("failed finalization must release the cache lease", MediaCacheLeaseManager.deleteIfUnleased(target))
            assertStagingClean(key)
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            clean(key)
        }
    }

    @Test
    fun `existing plaintext cache bypasses downloader and decryptor`() = runBlocking {
        val key = uniqueKey()
        val lock = KeyedOperationLock<String>()
        val cached = File(mediaDir(), "dec_${MediaDecryptCache.cacheFileId(key)}.flac")
        assertTrue(cached.parentFile!!.mkdirs() || cached.parentFile!!.isDirectory)
        cached.writeText("cached")
        val workCalls = AtomicInteger()
        val downloader: suspend (String, File) -> Boolean = { _, _ -> workCalls.incrementAndGet(); false }
        val decryptor: (File, File, String) -> String? = { _, _, _ -> workCalls.incrementAndGet(); null }

        try {
            assertEquals(cached.absolutePath, prepare(key, lock, downloader, decryptor))
            assertEquals(0, workCalls.get())
            assertEquals(0, lock.trackedKeysForTests())
        } finally {
            clean(key)
        }
    }

    private suspend fun prepare(
        key: String,
        lock: KeyedOperationLock<String>,
        downloader: suspend (String, File) -> Boolean,
        decryptor: (File, File, String) -> String? = { input, output, _ ->
            output.writeBytes(input.readBytes() + "-plain".toByteArray())
            "flac"
        }
    ): String? = MediaDecryptCache.prepareWith(
        context,
        "https://media.invalid/test.mflac",
        key,
        "mflac",
        lock,
        downloader,
        decryptor
    )

    private suspend fun awaitReferences(lock: KeyedOperationLock<String>, key: String, expected: Int) {
        withTimeout(5_000) {
            while (lock.referencesForTests(MediaDecryptCache.cacheFileId(key)) != expected) yield()
        }
    }

    private fun uniqueKey(): String = "mdc_${UUID.randomUUID().toString().replace("-", "")}"

    private fun mediaDir(): File = File(context.cacheDir, "media_decrypt")

    private fun clean(key: String) {
        val prefix = "dec_${MediaDecryptCache.cacheFileId(key)}."
        mediaDir().listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun assertStagingClean(key: String) {
        val prefix = "dec_${MediaDecryptCache.cacheFileId(key)}"
        assertFalse(File(mediaDir(), "${prefix}.mflac.part").exists())
        assertFalse(File(mediaDir(), "${prefix}.mflac.plain.part").exists())
    }
}
