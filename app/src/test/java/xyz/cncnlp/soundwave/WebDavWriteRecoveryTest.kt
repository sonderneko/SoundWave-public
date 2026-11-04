package xyz.cncnlp.soundwave

import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.*
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Production client + production OkHttp; all state and credentials below are fixtures. */
class WebDavWriteRecoveryTest {
    private fun bundle(id: String) = LocalStore.UserBundle(favs = mutableListOf(
        Song(id, id, "fixture", "wy", syncAddedAt = 100L)))
    private fun document(id: String) = JSONObject().put("schema", "soundwave-webdav-sync")
        .put("version", 1).put("user", bundle(id).toCloudJson(includeAvatar = false)).toString()
    private val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

    private fun scenario(firstPut: MockResponse, verify: (WebDavSyncResult, List<RecordedRequest>, String?, LocalStore.UserBundle, String) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var remote: String? = null
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when(request.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207)
                    "GET" -> MockResponse().setResponseCode(200).setHeader("ETag", "\"fixture-v1\"").setBody(document("remote"))
                    "PUT" -> {
                        remote = request.body.clone().readUtf8() // server has accepted and retained the complete request
                        if (requests.count { it.method == "PUT" } == 1) firstPut
                        else MockResponse().setResponseCode(204).setHeader("ETag", "\"fixture-v2\"")
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val local = bundle("local")
            val before = local.toCloudJson(includeAvatar = false).toString()
            val result = WebDavSyncClient(allowLoopbackHttpForTests = true).sync(
                "http://127.0.0.1:${server.port}/dav/", "fixture-user", "fixture-secret", local, gate)
            verify(result, requests.toList(), remote, local, before)
        } finally { server.shutdown() }
    }

    @Test fun acceptedPutWithLostResponseNeverReplaysOrReportsSuccess() {
        scenario(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) { result, requests, remote, local, before ->
            assertNotNull("fixture server retained a complete upload", remote)
            assertEquals("one application upload, no hidden HTTP replay", 1, requests.count { it.method == "PUT" })
            assertTrue("lost response is not confirmed success: $result", result is WebDavSyncResult.Failure)
            val message = (result as WebDavSyncResult.Failure).message
            assertTrue(message.contains("远端可能已接收"))
            assertFalse(message.contains("fixture-secret"))
            assertFalse(message.contains("fixture-user"))
            assertFalse(message.contains("127.0.0.1"))
            assertEquals(before, local.toCloudJson(includeAvatar = false).toString())
        }
    }

    @Test fun put503WithImmediateRetryHeaderDoesNotTriggerHiddenReplay() {
        scenario(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) { result, requests, _, local, before ->
            assertEquals("503 must return to the sync client instead of hidden replay", 1, requests.count { it.method == "PUT" })
            assertTrue("503 is not a confirmed success: $result", result is WebDavSyncResult.Failure)
            assertTrue((result as WebDavSyncResult.Failure).message.contains("503"))
            assertEquals(before, local.toCloudJson(includeAvatar = false).toString())
        }
    }

    @Test fun acknowledgedPutRemainsSuccessfulAndConditional() {
        scenario(MockResponse().setResponseCode(204).setHeader("ETag", "\"fixture-v2\"")) { result, requests, remote, local, before ->
            assertTrue(result is WebDavSyncResult.Success)
            assertEquals("\"fixture-v2\"", (result as WebDavSyncResult.Success).etag)
            val put = requests.single { it.method == "PUT" }
            assertEquals("\"fixture-v1\"", put.getHeader("If-Match"))
            val merged = LocalStore.UserBundle.fromCloud(JSONObject(remote!!).getJSONObject("user"))
            assertEquals(setOf("local", "remote"), merged.favs.map { it.id }.toSet())
            assertEquals(before, local.toCloudJson(includeAvatar = false).toString())
        }
    }
}
