package xyz.cncnlp.soundwave

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheManagerCacheInvalidationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val ownedFiles = mutableListOf<File>()

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
        ownedFiles.forEach { it.delete() }
        CacheManager.resetCoverModelCacheForTest()
    }

    @Test
    fun successfulInstallInvalidatesPreviouslyMemoizedRemoteUrl() {
        val url = "https://fixture.invalid/${UUID.randomUUID()}.png"
        val normalized = normalizeCoverUrl(url)
        assertEquals(normalized, CacheManager.coverModel(context, url))
        CacheManager.coverDownloadActionForTest = { _, destination, _, _ ->
            destination.writeBytes(validPng())
            true
        }

        val installedPath = CacheManager.ensureCover(context, url)
        val installedFile = File(installedPath)
        ownedFiles += installedFile

        assertTrue(installedFile.isFile)
        val model = CacheManager.coverModel(context, url)
        assertTrue("successful install must replace the memoized URL with a disk file", model is File)
        assertEquals(installedFile.absolutePath, (model as File).absolutePath)
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
