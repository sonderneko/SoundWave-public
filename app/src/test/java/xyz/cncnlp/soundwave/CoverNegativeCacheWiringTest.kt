package xyz.cncnlp.soundwave

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ACCEPT5-covergate 接线验证：负缓存真正挂在 CacheManager.ensureCover 的下载路径上
 * （Robolectric + coverDownloadActionForTest 假缝计数）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverNegativeCacheWiringTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        CacheManager.resetCoverModelCacheForTest()
        CacheManager.coverDownloadActionForTest = null
        CacheManager.optionalCacheWriteAllowedForTest = true
    }

    @After
    fun tearDown() {
        CacheManager.coverDownloadActionForTest = null
        CacheManager.optionalCacheWriteAllowedForTest = null
        CacheManager.resetCoverModelCacheForTest()
    }

    @Test
    fun failedDownloadIsNotRetriedWithinNegativeCacheWindow() {
        val url = "https://fixture.invalid/${UUID.randomUUID()}.png"
        val attempts = AtomicInteger(0)
        CacheManager.coverDownloadActionForTest = { _, _, _, _ ->
            attempts.incrementAndGet()
            false
        }
        val first = CacheManager.ensureCover(context, url)
        assertEquals(normalizeCoverUrl(url), first)
        assertEquals(1, attempts.get())

        val second = CacheManager.ensureCover(context, url)
        assertEquals(normalizeCoverUrl(url), second)
        assertEquals("negative cache must suppress the retry storm", 1, attempts.get())

        CacheManager.coverFailureCache.clear()
        CacheManager.ensureCover(context, url)
        assertEquals("after the cache is cleared the URL is retried", 2, attempts.get())
    }

    @Test
    fun successfulDownloadAfterFailureInstallsAndSubsequentCallsHitDisk() {
        val url = "https://fixture.invalid/${UUID.randomUUID()}.png"
        val attempts = AtomicInteger(0)
        CacheManager.coverDownloadActionForTest = { _, destination, _, _ ->
            if (attempts.incrementAndGet() == 1) {
                false
            } else {
                destination.writeBytes(validPng())
                true
            }
        }
        CacheManager.ensureCover(context, url)
        assertEquals(1, attempts.get())
        CacheManager.coverFailureCache.clear() // 模拟 TTL 到期
        val installed = CacheManager.ensureCover(context, url)
        assertEquals(2, attempts.get())
        val file = File(installed)
        assertTrue(file.isFile)
        try {
            // 已落盘且新鲜：后续调用直接命中文件，不再触发下载。
            assertEquals(installed, CacheManager.ensureCover(context, url))
            assertEquals(2, attempts.get())
        } finally {
            file.delete()
        }
    }

    private fun validPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
