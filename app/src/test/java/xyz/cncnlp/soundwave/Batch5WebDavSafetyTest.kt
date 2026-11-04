package xyz.cncnlp.soundwave

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

/** Real WebDavSyncClient and production OkHttp on loopback; no real account or remote files. */
class Batch5WebDavSafetyTest {
    private val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
    private fun bundle(vararg ids: String) = LocalStore.UserBundle(favs = ids.mapIndexed { index, id ->
        Song(id = id, name = id, singer = "test", source = "wy", syncAddedAt = (index + 1) * 100L)
    }.toMutableList())
    private fun document(vararg ids: String) = JSONObject().put("schema", "soundwave-webdav-sync")
        .put("version", 1).put("user", bundle(*ids).toCloudJson(includeAvatar = false)).toString()
    private fun etag(value: String) = MockResponse().setResponseCode(207)
        .setBody("<d:prop xmlns:d=\"DAV:\"><d:getetag>&quot;$value&quot;</d:getetag></d:prop>")
    private fun ok(body: String, tag: String? = null) = MockResponse().setResponseCode(200).setBody(body)
        .also { if (tag != null) it.setHeader("ETag", tag) }
    private fun ids(result: WebDavSyncResult) = (result as WebDavSyncResult.Success).bundle.favs.map { it.id }.toSet()
    private fun LocalStore.UserBundle.frozen() = toCloudJson(includeAvatar = false).toString()

    private fun withServer(action: (RecordedRequest, List<RecordedRequest>) -> MockResponse,
                           verify: (WebDavSyncResult, List<RecordedRequest>, LocalStore.UserBundle, String) -> Unit) {
        val log = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                log += request
                if (request.method == "PROPFIND" && request.path!!.endsWith("/")) return MockResponse().setResponseCode(207)
                return action(request, log)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val local = bundle("local")
            val before = local.frozen()
            val result = WebDavSyncClient(allowLoopbackHttpForTests = true).sync(
                "http://127.0.0.1:${server.port}/dav/", "fake-user", "fake-password", local, gate)
            verify(result, log, local, before)
        } finally { runCatching { server.shutdown() } }
    }

    @Test fun fallbackTagMustBeBoundToFreshBodyBeforeMerging() {
        withServer({ r, log -> when (r.method) {
            "GET" -> if (log.count { it.method == "GET" } == 1) ok(document("old")) else ok(document("new"))
            "PROPFIND" -> etag("v2")
            "PUT" -> MockResponse().setResponseCode(204)
            else -> MockResponse().setResponseCode(500)
        } }) { result, log, local, before ->
            assertEquals(setOf("local", "new"), ids(result))
            val reads = log.filter { it.method == "GET" }
            assertEquals(2, reads.size)
            assertEquals("\"v2\"", reads[1].getHeader("If-Match"))
            val put = log.single { it.method == "PUT" }
            assertEquals("\"v2\"", put.getHeader("If-Match"))
            assertTrue(put.body.readUtf8().contains("new"))
            assertEquals(before, local.frozen())
        }
    }

    @Test fun fallbackConditionalReadConflictStopsBeforePut() = fallbackRejectedRead(412)
    @Test fun fallbackConditionalReadDeletionDoesNotBecomeBlindCreate() = fallbackRejectedRead(404)
    private fun fallbackRejectedRead(status: Int) {
        withServer({ r, log -> when (r.method) {
            "GET" -> if (log.count { it.method == "GET" } == 1) ok(document("old")) else MockResponse().setResponseCode(status)
            "PROPFIND" -> etag("v2")
            else -> MockResponse().setResponseCode(204)
        } }) { result, log, local, before ->
            assertTrue("$result", result is WebDavSyncResult.Failure)
            assertEquals(2, log.count { it.method == "GET" })
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun fallbackMismatchedReturnedTagStopsBeforePut() {
        withServer({ r, log -> when (r.method) {
            "GET" -> if (log.count { it.method == "GET" } == 1) ok(document("old")) else ok(document("other"), "\"v3\"")
            "PROPFIND" -> etag("v2")
            else -> MockResponse().setResponseCode(204)
        } }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun fallbackMalformedFreshDocumentStopsBeforePut() {
        withServer({ r, log -> when (r.method) {
            "GET" -> if (log.count { it.method == "GET" } == 1) ok(document("old")) else ok("{broken")
            "PROPFIND" -> etag("v2")
            else -> MockResponse().setResponseCode(204)
        } }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun conflictRetryAlsoPinsFallbackBodyAndTag() {
        withServer({ r, log -> when (r.method) {
            "GET" -> when (log.count { it.method == "GET" }) {
                1 -> ok(document("v1-song"), "\"v1\"")
                2 -> ok(document("stale-v2-song"))
                else -> ok(document("latest-v3-song"))
            }
            "PROPFIND" -> etag("v3")
            "PUT" -> MockResponse().setResponseCode(if (log.count { it.method == "PUT" } == 1) 412 else 204)
            else -> MockResponse().setResponseCode(500)
        } }) { result, log, local, before ->
            assertEquals(setOf("local", "latest-v3-song"), ids(result))
            assertEquals(listOf("\"v1\"", "\"v3\""), log.filter { it.method == "PUT" }.map { it.getHeader("If-Match") })
            assertEquals(3, log.count { it.method == "GET" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun getWithEtagKeepsSingleReadAndConditionalWrite() {
        withServer({ r, _ -> if (r.method == "GET") ok(document("remote"), "\"v1\"") else MockResponse().setResponseCode(204) }) { result, log, local, before ->
            assertEquals(setOf("local", "remote"), ids(result))
            assertEquals(1, log.count { it.method == "GET" })
            assertEquals("\"v1\"", log.single { it.method == "PUT" }.getHeader("If-Match"))
            assertEquals(before, local.frozen())
        }
    }

    @Test fun absentEtagNeverBlindlyWrites() {
        withServer({ r, _ -> if (r.method == "GET") ok(document("remote")) else MockResponse().setResponseCode(207) }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun oversizedResponseThroughProductionTransportCannotPutOrMutateInput() {
        withServer({ _, _ -> ok("x".repeat(5 * 1024 * 1024 + 1), "\"v1\"") }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun damagedJsonCannotPutOrMutateInput() {
        withServer({ _, _ -> ok("{broken", "\"v1\"") }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertTrue(log.none { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun failedPutDoesNotReturnMergedSuccessOrMutateInput() {
        withServer({ r, _ -> if (r.method == "GET") ok(document("remote"), "\"v1\"") else MockResponse().setResponseCode(500) }) { result, log, local, before ->
            assertTrue(result is WebDavSyncResult.Failure)
            assertEquals(1, log.count { it.method == "PUT" })
            assertEquals(before, local.frozen())
        }
    }

    @Test fun cancellationInFallbackPropfindIsPropagatedNotMissingEtag() {
        var puts = 0
        val cancellation = CancellationException("test fallback cancellation")
        val transport = WebDavTransport { r -> when {
            r.method == "GET" -> WebDavHttpResponse(200, document("remote"))
            r.method == "PROPFIND" && !r.url.endsWith("/") -> throw cancellation
            r.method == "PUT" -> { puts++; WebDavHttpResponse(204) }
            else -> WebDavHttpResponse(207)
        } }
        try {
            WebDavSyncClient(transport).sync("https://example.invalid/dav", "fake", "fake", bundle("local"), gate)
            fail("CancellationException must propagate")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertEquals(0, puts)
    }

    @Test fun cancelledProfileAuthorizationSendsNoRequestsAndPropagates() {
        val cancellation = CancellationException("test authorization cancellation")
        var requests = 0
        try {
            WebDavSyncClient(WebDavTransport { requests++; WebDavHttpResponse(207) }).sync(
                "https://example.invalid/dav", "fake", "fake", bundle("local"), gate,
                documentId = "a".repeat(32), profileAuthorization = { throw cancellation })
            fail("CancellationException must propagate")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertEquals(0, requests)
    }

    @Test fun cancelledProbeCleanupIsNotConnectionSuccess() {
        val cancellation = CancellationException("test cleanup cancellation")
        val methods = mutableListOf<String>()
        try {
            WebDavSyncClient(WebDavTransport { r ->
                methods += r.method
                when (r.method) {
                    "PROPFIND" -> WebDavHttpResponse(207)
                    "PUT" -> WebDavHttpResponse(201)
                    else -> throw cancellation
                }
            }).testConnection("https://example.invalid/dav", "fake", "fake")
            fail("CancellationException must propagate")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertEquals(listOf("PROPFIND", "PROPFIND", "PUT", "DELETE"), methods)
    }
}
