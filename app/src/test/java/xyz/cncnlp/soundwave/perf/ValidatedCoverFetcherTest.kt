package xyz.cncnlp.soundwave.perf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.SourceResult
import coil.request.Options
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ValidatedCoverFetcherTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun writeValidPng(file: File) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun remoteImagesResolveToValidatedLocalFiles() = runBlocking {
        val image = File(context.cacheDir, "validated-cover-test.png").also(::writeValidPng)
        val requested = mutableListOf<String>()
        val loader = ImageLoader.Builder(context).build()
        try {
            val fetcher = ValidatedCoverFetcherFactory { _, url ->
                requested += url
                image.absolutePath
            }.create(Uri.parse("https://covers.example/album.png"), Options(context), loader)
            assertNotNull(fetcher)
            val result = requireNotNull(fetcher).fetch()
            assertTrue(result is SourceResult)
            assertEquals(image.absolutePath, (result as SourceResult).source.file().toString())
            assertEquals(listOf("https://covers.example/album.png"), requested)
        } finally {
            loader.shutdown()
            image.delete()
        }
    }

    @Test
    fun failedValidationFailsClosedInsteadOfFallingThrough() = runBlocking {
        val requested = mutableListOf<String>()
        val loader = ImageLoader.Builder(context).build()
        try {
            val fetcher = ValidatedCoverFetcherFactory { _, url ->
                requested += url
                url // CacheManager's failure sentinel; the fetcher must fail closed.
            }.create(Uri.parse("https://covers.example/blocked.png"), Options(context), loader)
            assertNotNull(fetcher)
            try {
                requireNotNull(fetcher).fetch()
                throw AssertionError("failed validation should not return an image source")
            } catch (_: IOException) {
                // Expected: returning the original URL must not fall through to Coil's HTTP fetcher.
            }
            assertEquals(listOf("https://covers.example/blocked.png"), requested)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun imageLoaderSelectsValidatedFetcherForRemoteUri() = runBlocking {
        val image = File(context.cacheDir, "validated-cover-loader-test.png").also(::writeValidPng)
        val requested = mutableListOf<String>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(ValidatedCoverFetcherFactory { _, url ->
                    requested += url
                    image.absolutePath
                })
            }
            .build()
        try {
            val options = Options(context)
            val model = loader.components.map("https://covers.invalid/remote.png", options)
            assertTrue("Coil should map remote URLs to Uri", model is Uri)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(model, options, loader))
            val result = fetcher.fetch()
            assertTrue(result is SourceResult)
            assertEquals(image.absolutePath, (result as SourceResult).source.file().toString())
            assertEquals(listOf("https://covers.invalid/remote.png"), requested)
        } finally {
            loader.shutdown()
            image.delete()
        }
    }

    @Test
    fun applicationImageLoaderRoutesRemoteUrlsThroughValidatedFetcher() {
        val loader = AppPerformance.buildImageLoader(context)
        try {
            val options = Options(context)
            val model = loader.components.map("https://covers.invalid/remote.png", options)
            assertTrue("Coil should map remote URLs to Uri", model is Uri)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(model, options, loader))
            assertEquals("ValidatedCoverFetcher", fetcher.javaClass.simpleName)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun applicationImageLoaderValidatesCachedFileModelsBeforeDecode() = runBlocking {
        val image = File(context.cacheDir, "validated-file-model-${System.nanoTime()}.png").also(::writeValidPng)
        val loader = AppPerformance.buildImageLoader(context)
        try {
            val options = Options(context)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(image, options, loader))
            assertEquals("ValidatedCoverFileFetcher", fetcher.javaClass.simpleName)
            val result = fetcher.fetch() as SourceResult
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(image.absolutePath, result.source.file().toString())
        } finally {
            loader.shutdown()
            image.delete()
        }
    }

    @Test
    fun applicationImageLoaderRejectsOversizedCachedFilesBeforeDefaultDecoder() = runBlocking {
        val image = File(context.cacheDir, "oversized-file-model-${System.nanoTime()}.png")
        RandomAccessFile(image, "rw").use {
            it.setLength(xyz.cncnlp.soundwave.image.ImageDecodeLimits.MAX_COMPRESSED_BYTES_LONG + 1L)
        }
        val loader = AppPerformance.buildImageLoader(context)
        try {
            val options = Options(context)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(image, options, loader))
            try {
                fetcher.fetch()
                throw AssertionError("oversized cached image must fail closed")
            } catch (_: IOException) {
                // Expected: do not pass the file to Coil's stock decoder.
            }
        } finally {
            loader.shutdown()
            image.delete()
        }
    }

    @Test
    fun inlineDataUrisUseValidatedFilePathInsteadOfCoilsDecoder() = runBlocking {
        val image = File(context.cacheDir, "validated-inline-cover-test.png").also(::writeValidPng)
        val requested = mutableListOf<String>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(ValidatedCoverFetcherFactory(resolveInlineCover = { _, url ->
                    requested += url
                    image
                }))
            }
            .build()
        try {
            val options = Options(context)
            val uri = "data:image/png;base64,ZmFrZQ=="
            val model = loader.components.map(uri, options)
            assertTrue("Coil should map data URLs to Uri", model is Uri)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(model, options, loader))
            assertEquals("ValidatedInlineDataCoverFetcher", fetcher.javaClass.simpleName)
            val result = fetcher.fetch()
            assertTrue(result is SourceResult)
            assertEquals(image.absolutePath, (result as SourceResult).source.file().toString())
            assertEquals(listOf(uri), requested)
        } finally {
            loader.shutdown()
            image.delete()
        }
    }

    @Test
    fun oversizedInlineDataUriFailsBeforeCacheResolution() = runBlocking {
        val requested = mutableListOf<String>()
        val loader = ImageLoader.Builder(context)
            .components {
                add(ValidatedCoverFetcherFactory(resolveInlineCover = { _, url ->
                    requested += url
                    error("oversized data URL must be rejected before resolution")
                }))
            }
            .build()
        try {
            val oversized = "data:image/png;base64," + "A".repeat(xyz.cncnlp.soundwave.MAX_COVER_DATA_URL_CHARS)
            val options = Options(context)
            val model = loader.components.map(oversized, options)
            val (fetcher, _) = requireNotNull(loader.components.newFetcher(model, options, loader))
            try {
                fetcher.fetch()
                throw AssertionError("oversized inline cover should fail closed")
            } catch (_: IOException) {
                // Expected: must not fall through to Coil's default data URI decoder.
            }
            assertTrue(requested.isEmpty())
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun fileUrisUseBoundedFetcherWhileContentUrisKeepExistingFetcher() {
        val loader = ImageLoader.Builder(context).build()
        val image = File(context.cacheDir, "validated-file-uri-${System.nanoTime()}.png").also(::writeValidPng)
        try {
            val factory = ValidatedCoverFetcherFactory { _, _ -> error("resolver must not run") }
            val options = Options(context)
            assertEquals(
                "ValidatedCoverFileFetcher",
                factory.create(Uri.fromFile(image), options, loader)?.javaClass?.simpleName
            )
            assertNull(factory.create(Uri.parse("content://example.test/cover"), options, loader))
        } finally {
            image.delete()
            loader.shutdown()
        }
    }
}
