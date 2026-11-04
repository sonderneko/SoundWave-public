package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import xyz.cncnlp.soundwave.cache.LeaseAwareCacheEvictor
import xyz.cncnlp.soundwave.cache.LeasedCacheDataSource
import xyz.cncnlp.soundwave.cache.Media3CacheReadLease
import xyz.cncnlp.soundwave.cache.Media3CacheReadLeaseRegistry
import xyz.cncnlp.soundwave.cache.clearMedia3CacheResources
import xyz.cncnlp.soundwave.cache.trimMedia3Cache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@androidx.media3.common.util.UnstableApi
class Media3CacheReadLeaseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `real cache span open cannot race manual resource clear`() {
        val root = cacheDirectory("span-clear")
        val cache = SimpleCache(root, LeastRecentlyUsedCacheEvictor(Long.MAX_VALUE), StandaloneDatabaseProvider(context))
        val leases = Media3CacheReadLeaseRegistry()
        val payload = ByteArray(8192) { (it % 239).toByte() }
        val uri = Uri.parse("https://cache-race.invalid/audio/${uniqueId()}")
        val key = CacheKeyFactory.DEFAULT.buildCacheKey(DataSpec.Builder().setUri(uri).build())
        val cachedFile = writeSpan(cache, key, payload)
        val spanOpenEntered = CountDownLatch(1)
        val continueSpanOpen = CountDownLatch(1)
        val readerFactory = DataSource.Factory {
            BlockingOpenDataSource(FileDataSource(), spanOpenEntered, continueSpanOpen)
        }
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
            .setCacheReadDataSourceFactory(readerFactory)
            .setUpstreamDataSourceFactory(DataSource.Factory {
                FailingDataSource(openFailure = IOException("the seeded cache span must be used"))
            })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val source = LeasedCacheDataSource.Factory(cacheDataSource, CacheKeyFactory.DEFAULT, leases).createDataSource()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertNotNull(cachedFile)
            val read = executor.submit<ByteArray> {
                try {
                    source.open(DataSpec.Builder().setUri(uri).build())
                    val output = ByteArray(payload.size)
                    var offset = 0
                    while (offset < output.size) {
                        val count = source.read(output, offset, output.size - offset)
                        if (count == C.RESULT_END_OF_INPUT) break
                        offset += count
                    }
                    output.copyOf(offset)
                } finally {
                    source.close()
                }
            }
            assertTrue("CacheDataSource should select the committed span before the barrier", spanOpenEntered.await(5, TimeUnit.SECONDS))
            assertTrue("the active span key must already hold a read lease", leases.isActive(key))

            val skipped = clearMedia3CacheResources(cache, leases)
            assertEquals("the production cache-clear operation must report the active resource", 1, skipped)
            assertTrue("clear must leave the selected span linked", cachedFile!!.isFile)
            assertTrue(cache.cacheSpace > 0L)

            continueSpanOpen.countDown()
            assertArrayEquals(payload, read.get(5, TimeUnit.SECONDS))
            assertFalse(leases.isActive(key))
            assertEquals(0, clearMedia3CacheResources(cache, leases))
            assertEquals(0L, cache.cacheSpace)
        } finally {
            continueSpanOpen.countDown()
            executor.shutdownNow()
            cache.release()
            root.deleteRecursively()
        }
    }

    @Test
    fun `automatic lru leaves active resource over budget then trims after release`() {
        val root = cacheDirectory("lru-lease")
        val leases = Media3CacheReadLeaseRegistry()
        var lruSkippedActiveKeys = 0
        val evictor = LeaseAwareCacheEvictor(8L, leases) { lruSkippedActiveKeys += it }
        val cache = SimpleCache(root, evictor, StandaloneDatabaseProvider(context))
        val activeKey = "active-${uniqueId()}"
        val inactiveKey = "inactive-${uniqueId()}"
        val lease = leases.acquire(activeKey)
        try {
            val activeFile = writeSpan(cache, activeKey, ByteArray(8) { 1 })
            writeSpan(cache, inactiveKey, ByteArray(8) { 2 })
            assertTrue(activeFile!!.isFile)
            assertEquals("the active resource may temporarily exceed the budget", 8L, cache.cacheSpace)
            assertTrue(cache.keys.contains(activeKey))
            assertFalse(cache.keys.contains(inactiveKey))
            assertTrue(lruSkippedActiveKeys > 0)

            val deferredTrim = trimMedia3Cache(cache, 0L, leases) {}
            assertFalse(deferredTrim.complete)
            assertEquals(1, deferredTrim.skippedActiveResources)
            assertEquals(8L, cache.cacheSpace)

            lease.close()
            val trim = trimMedia3Cache(cache, 0L, leases) {}
            assertTrue(trim.complete)
            assertEquals(0L, cache.cacheSpace)
        } finally {
            lease.close()
            cache.release()
            root.deleteRecursively()
        }
    }

    @Test
    fun `resource removal and lease acquisition serialize without a deletion gap`() {
        val leases = Media3CacheReadLeaseRegistry()
        val key = "atomic-${uniqueId()}"
        val removalEntered = CountDownLatch(1)
        val finishRemoval = CountDownLatch(1)
        val acquireStarted = CountDownLatch(1)
        val acquireThread = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val removal = pool.submit<Boolean> {
                leases.removeIfUnleased(key) {
                    removalEntered.countDown()
                    assertTrue(finishRemoval.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(removalEntered.await(5, TimeUnit.SECONDS))
            val acquiring = pool.submit<Media3CacheReadLease> {
                acquireThread.set(Thread.currentThread())
                acquireStarted.countDown()
                leases.acquire(key)
            }
            assertTrue(acquireStarted.await(5, TimeUnit.SECONDS))
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (acquireThread.get().state != Thread.State.WAITING && System.nanoTime() < waitDeadline) {
                Thread.yield()
            }
            assertEquals("acquire must be waiting behind the in-progress unlink", Thread.State.WAITING, acquireThread.get().state)
            assertFalse("new readers must wait until unlink completes", acquiring.isDone)
            finishRemoval.countDown()
            assertTrue(removal.get(5, TimeUnit.SECONDS))
            val lease = acquiring.get(5, TimeUnit.SECONDS)
            try {
                assertTrue(leases.isActive(key))
            } finally {
                lease.close()
            }
            assertFalse(leases.isActive(key))
        } finally {
            finishRemoval.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `open and read errors plus close cancellation release cache leases`() {
        val uri = Uri.parse("https://lease-error.invalid/${uniqueId()}")
        val spec = DataSpec.Builder().setUri(uri).build()
        val key = CacheKeyFactory.DEFAULT.buildCacheKey(spec)

        val openLeases = Media3CacheReadLeaseRegistry()
        val openFailure = LeasedCacheDataSource(FailingDataSource(openFailure = IOException("open")), CacheKeyFactory.DEFAULT, openLeases)
        assertTrue(runCatching { openFailure.open(spec) }.exceptionOrNull() is IOException)
        assertFalse(openLeases.isActive(key))

        val readLeases = Media3CacheReadLeaseRegistry()
        val readFailure = LeasedCacheDataSource(FailingDataSource(readFailure = IOException("read")), CacheKeyFactory.DEFAULT, readLeases)
        readFailure.open(spec)
        assertTrue(readLeases.isActive(key))
        assertTrue(runCatching { readFailure.read(ByteArray(1), 0, 1) }.exceptionOrNull() is IOException)
        assertFalse("read failure must close the delegate and release its lease", readLeases.isActive(key))

        val cancelledLeases = Media3CacheReadLeaseRegistry()
        val cancelled = LeasedCacheDataSource(FailingDataSource(), CacheKeyFactory.DEFAULT, cancelledLeases)
        cancelled.open(spec)
        assertTrue(cancelledLeases.isActive(key))
        cancelled.close()
        assertFalse("player cancellation closes the DataSource and releases its lease", cancelledLeases.isActive(key))
    }

    @Test
    fun `multiple readers keep one resource active until the final close`() {
        val leases = Media3CacheReadLeaseRegistry()
        val key = "readers-${uniqueId()}"
        val first = leases.acquire(key)
        val second = leases.acquire(key)
        try {
            assertTrue(leases.isActive(key))
            assertFalse(leases.removeIfUnleased(key) {})
            first.close()
            assertTrue(leases.isActive(key))
            second.close()
            assertFalse(leases.isActive(key))
        } finally {
            first.close()
            second.close()
        }
    }

    private fun writeSpan(cache: SimpleCache, key: String, bytes: ByteArray): File? {
        val hole = cache.startReadWrite(key, 0L, bytes.size.toLong())
        return try {
            val file = cache.startFile(key, 0L, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            cache.getCachedSpans(key).singleOrNull()?.file
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    private fun cacheDirectory(prefix: String): File =
        File(context.cacheDir, "$prefix-${uniqueId()}").apply { check(mkdirs() || isDirectory) }

    private fun uniqueId(): String = UUID.randomUUID().toString().replace("-", "")

    private class BlockingOpenDataSource(
        private val delegate: DataSource,
        private val entered: CountDownLatch,
        private val proceed: CountDownLatch
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
            return delegate.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
        override fun getUri(): Uri? = delegate.uri
        override fun close() = delegate.close()
    }

    private class FailingDataSource(
        private val openFailure: IOException? = null,
        private val readFailure: IOException? = null
    ) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            openFailure?.let { throw it }
            return 1L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            return C.RESULT_END_OF_INPUT
        }
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
