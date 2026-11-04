package xyz.cncnlp.soundwave

import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.cache.AtomicCacheFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricCacheStorageTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun lyricReplacementIsCompleteAndExpiredEntryIsDeleted() {
        val key = "lyric-cache-test-${UUID.randomUUID()}"
        val directory = CacheManager.lyricsDir(context)
        val before = directory.listFiles().orEmpty().toSet()
        val previousWriteGate = CacheManager.optionalCacheWriteAllowedForTest
        CacheManager.optionalCacheWriteAllowedForTest = true
        try {
            CacheManager.saveLyric(context, key, "first-complete-value")
            val target = directory.listFiles().orEmpty().single { it !in before && it.extension == "lrc" }
            assertEquals("first-complete-value", CacheManager.loadLyric(context, key))

            CacheManager.saveLyric(context, key, "second-complete-value")
            assertEquals("second-complete-value", CacheManager.loadLyric(context, key))
            assertFalse(directory.listFiles().orEmpty().any { AtomicCacheFile.isStagingName(it) })

            assertTrue(target.setLastModified(System.currentTimeMillis() - CacheManager.LYRIC_CACHE_TTL_MS - 1L))
            assertEquals("", CacheManager.loadLyric(context, key))
            assertFalse("expired lyrics must not be reused", target.exists())
        } finally {
            directory.listFiles().orEmpty().filter { it !in before }.forEach { it.delete() }
            CacheManager.optionalCacheWriteAllowedForTest = previousWriteGate
        }
    }
}
