package xyz.cncnlp.soundwave

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaCacheClearTest {
    @Test
    fun `cold start clear opens persistent cache and preserves covers`() {
        val context: Context = RuntimeEnvironment.getApplication()
        PlayerService.resetSimpleCacheForTests()
        assertFalse(PlayerService.hasSimpleCacheForTests())

        val exoDir = CacheManager.exoAudioDir(context)
        if (exoDir.exists()) assertTrue(exoDir.deleteRecursively())
        assertTrue(exoDir.mkdirs() || exoDir.isDirectory)

        val key = "https://cache-test.invalid/audio/cold-start"
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val seedCache = newCache(context, exoDir)
        val holeSpan = seedCache.startReadWrite(key, 0L, payload.size.toLong())
        try {
            val spanFile = seedCache.startFile(key, 0L, payload.size.toLong())
            spanFile.writeBytes(payload)
            seedCache.commitFile(spanFile, payload.size.toLong())
            assertEquals(payload.size.toLong(), seedCache.cacheSpace)
        } finally {
            seedCache.releaseHoleSpan(holeSpan)
            seedCache.release()
        }

        // 模拟播放器服务从未启动：磁盘缓存已提交，但进程内 simpleCache 为空。
        PlayerService.resetSimpleCacheForTests()
        assertFalse(PlayerService.hasSimpleCacheForTests())

        val ordinary = File(context.cacheDir, "ordinary-temp.bin").apply { writeText("temporary") }
        val lyric = File(CacheManager.lyricsDir(context), "stale.lrc").apply { writeText("lyric") }
        val audio = File(CacheManager.audioDir(context), "stale.audio").apply { writeText("audio") }
        val cover = File(CacheManager.coversDir(context), "keep-cover.bin").apply { writeText("cover") }
        val avatar = File(CacheManager.avatarDir(context), "keep-avatar.bin").apply { writeText("avatar") }

        try {
            CacheManager.clearAll(context)

            assertTrue("clear must open the production SimpleCache instance", PlayerService.hasSimpleCacheForTests())
            assertEquals(0L, PlayerService.mediaCacheBytes())
            assertTrue("SimpleCache must retain its directory", exoDir.isDirectory)
            assertFalse(ordinary.exists())
            assertFalse(lyric.exists())
            assertFalse(audio.exists())
            assertTrue(cover.exists())
            assertTrue(avatar.exists())

            PlayerService.resetSimpleCacheForTests()
            val verifyCache = newCache(context, exoDir)
            try {
                assertEquals(0L, verifyCache.cacheSpace)
                assertTrue(verifyCache.keys.isEmpty())
            } finally {
                verifyCache.release()
            }
        } finally {
            PlayerService.resetSimpleCacheForTests()
        }
    }

    @Test
    fun `cache initialization failure propagates before reporting all caches cleared`() {
        val context: Context = RuntimeEnvironment.getApplication()
        PlayerService.resetSimpleCacheForTests()
        val exoDir = CacheManager.exoAudioDir(context)
        if (exoDir.exists()) assertTrue(exoDir.deleteRecursively())
        assertTrue(exoDir.parentFile?.isDirectory == true)
        exoDir.writeText("not a directory")
        val ordinary = File(context.cacheDir, "must-remain-on-failure.bin").apply { writeText("temporary") }

        try {
            val failure = runCatching { CacheManager.clearAll(context) }.exceptionOrNull()
            assertNotNull("invalid SimpleCache path must be reported to the caller", failure)
            assertTrue("the caller must not proceed as though clearAll succeeded", ordinary.exists())
            assertTrue(exoDir.isFile)
        } finally {
            exoDir.delete()
            PlayerService.resetSimpleCacheForTests()
        }
    }

    private fun newCache(context: Context, directory: File): SimpleCache =
        SimpleCache(directory, LeastRecentlyUsedCacheEvictor(Long.MAX_VALUE), StandaloneDatabaseProvider(context))
}
