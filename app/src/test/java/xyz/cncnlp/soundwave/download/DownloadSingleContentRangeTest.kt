package xyz.cncnlp.soundwave.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.cncnlp.soundwave.DownloadHelper

/** Exercises the actual single-stream writer without MediaExtractor/SAF finalization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadSingleContentRangeTest {
    private val prefix = "p".repeat(400)
    private val tail = "t".repeat(600)

    @Test
    fun validResumeAppendsExactlyTheRequestedTail() = withServer { server, file ->
        file.writeText(prefix)
        server.enqueue(partial("bytes 400-999/1000", tail))
        assertEquals(1000L, download(server, file, 400L))
        assertEquals(prefix + tail, file.readText())
        val request = server.takeRequest()
        assertEquals("bytes=400-", request.getHeader("Range"))
        assertEquals("\"same\"", request.getHeader("If-Range"))
    }

    @Test
    fun sameEtagButWrongStartTotalOrMissingRangeFailsBeforeWriting() {
        listOf("bytes 0-599/1000", "bytes 400-999/1001", "bytes 400-998/1000", null).forEach { range ->
            withServer { server, file ->
                file.writeText(prefix)
                server.enqueue(partial(range, tail))
                assertThrows(IOException::class.java) { download(server, file, 400L) }
                assertEquals(prefix, file.readText())
            }
        }
    }

    @Test
    fun unknownLengthBodyMustStillMatchContentRangeExactly() {
        listOf(tail.dropLast(1), tail + "x").forEach { body ->
            withServer { server, file ->
                file.writeText(prefix)
                server.enqueue(partial("bytes 400-999/1000", body))
                assertThrows(IOException::class.java) { download(server, file, 400L) }
                // Overlong data must never become a plausible future resume prefix.
                assertTrue(file.length() <= 1000L)
            }
        }
    }

    @Test
    fun offsetZeroRejectsPartialEntityButAcceptsComplete206() {
        listOf("bytes 400-999/1000", "bytes 0-599/1000").forEach { range ->
            withServer { server, file ->
                file.writeText("old")
                server.enqueue(partial(range, tail))
                assertThrows(IOException::class.java) { download(server, file, 0L) }
                assertEquals("old", file.readText())
            }
        }
        withServer { server, file ->
            server.enqueue(partial("bytes 0-999/1000", prefix + tail))
            assertEquals(1000L, download(server, file, 0L))
            assertEquals(prefix + tail, file.readText())
        }
    }

    @Test
    fun ignoredRange200TruncatesAndReturnsActualResponseTotalNotProbeTotal() = withServer { server, file ->
        file.writeText(prefix)
        val replacement = "new".repeat(200)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"new\"").setBody(replacement))
        assertEquals(600L, download(server, file, 400L))
        assertEquals(replacement, file.readText())
        assertEquals(DownloadResumePolicy.Fingerprint(600L, "\"new\"", "a".repeat(64)),
            DownloadResumePolicy.decode(File(file.path + ".resume").readText()))
    }

    @Test
    fun unknownLength200DoesNotReuseStaleProbeLength() = withServer { server, file ->
        file.writeText(prefix)
        server.enqueue(MockResponse().setResponseCode(200).setChunkedBody(tail, 71))
        assertEquals(600L, download(server, file, 400L))
        assertEquals(tail, file.readText())
    }

    private fun partial(range: String?, body: String): MockResponse = MockResponse()
        .setResponseCode(206).setHeader("ETag", "\"same\"").setChunkedBody(body, 71)
        .apply { if (range != null) setHeader("Content-Range", range) }

    private fun withServer(test: (MockWebServer, File) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val file = File.createTempFile("single-range-", ".part")
            try { test(server, file) } finally {
                file.delete()
                File(file.path + ".resume").delete()
            }
        }
    }

    // Keep the production writer private; this test is intentionally pinned to its boundary.
    private fun download(server: MockWebServer, file: File, offset: Long): Any? {
        val remoteClass = Class.forName("xyz.cncnlp.soundwave.DownloadHelper\$RemoteObject")
        val constructor = remoteClass.declaredConstructors.single { it.parameterCount == 6 }
            .apply { isAccessible = true }
        val remote = constructor.newInstance(1000L, "\"same\"", "", true, 200, "audio/mpeg")
        val method = DownloadHelper::class.java.declaredMethods.single { it.name == "downloadSingle" }
            .apply { isAccessible = true }
        val registry = DownloadHelper::class.java.getDeclaredField("activeCalls")
            .apply { isAccessible = true }.get(DownloadHelper)
        val begin = registry.javaClass.getDeclaredMethod("begin", String::class.java)
            .apply { isAccessible = true }
        val session = begin.invoke(registry, "content-range-test")
        val finish = registry.javaClass.getDeclaredMethod("finish", session.javaClass)
            .apply { isAccessible = true }
        try {
            return method.invoke(DownloadHelper, ApplicationProvider.getApplicationContext<Context>(), null,
                server.url("/audio").toString(), file, remote, offset, session, "content-range-test", null, { false }, null,
                DownloadResumePolicy.MIN_DEADLINE_MS, "a".repeat(64))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        } finally {
            finish.invoke(registry, session)
        }
    }
}
