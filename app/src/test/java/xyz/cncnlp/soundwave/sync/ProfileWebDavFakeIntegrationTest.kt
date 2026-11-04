package xyz.cncnlp.soundwave.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import xyz.cncnlp.soundwave.LocalStore
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot

/** In-memory transport only; authored, not JVM-executed. Never grants production readiness. */
class ProfileWebDavFakeIntegrationTest {
    private val document = "a".repeat(32)
    private fun remote(id: String = document) = JSONObject()
        .put("schema", "soundwave-webdav-sync").put("version", 1).put("documentId", id)
        .put("user", LocalStore.UserBundle().also { it.ensureDefaults() }.toCloudJson()).toString()
    private fun run(transport: WebDavTransport, authorize: () -> Boolean = { true }, history: () -> List<Song> = { emptyList() }) =
        WebDavSyncClient(transport).sync("https://example.invalid/dav", "fake", "fake", LocalStore.UserBundle(),
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.UNKNOWN },
            documentId = document, profileAuthorization = authorize, profileHistory = history)

    @Test fun denialBeforeFirstRequestSendsNothing() {
        var requests = 0
        assertTrue(run(WebDavTransport { requests++; WebDavHttpResponse(500) }, { false }) is WebDavSyncResult.Failure)
        assertEquals(0, requests)
    }
    @Test fun revokeAfterDirectoryCheckPreventsGetAndPut() {
        var authorized = true
        val requests = mutableListOf<String>()
        val result = run(WebDavTransport {
            requests += it.method
            authorized = false
            WebDavHttpResponse(207)
        }, { authorized })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(listOf("PROPFIND"), requests)
    }
    @Test fun missingDocumentUsesCreateConditionAndIndependentHistory() {
        val requests = mutableListOf<WebDavHttpRequest>()
        val result = run(WebDavTransport {
            requests += it
            when (it.method) { "PROPFIND" -> WebDavHttpResponse(207); "GET" -> WebDavHttpResponse(404); else -> WebDavHttpResponse(201, etag = "new") }
        }, history = { listOf(Song(id = "history-only", name = "History", singer = "Artist", source = "wy", playedAt = System.currentTimeMillis())) })
        assertTrue(result is WebDavSyncResult.Success)
        assertEquals("If-None-Match" to "*", requests.last().conditionHeader)
        assertTrue(String(requests.last().body!!, Charsets.UTF_8).contains("history-only"))
        assertEquals("new", (result as WebDavSyncResult.Success).etag)
    }
    @Test fun secondConflictIsNotRetriedAgain() {
        val methods = mutableListOf<String>()
        val result = run(WebDavTransport {
            methods += it.method
            when (it.method) { "PROPFIND" -> WebDavHttpResponse(207); "GET" -> WebDavHttpResponse(404); else -> WebDavHttpResponse(412) }
        })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "GET", "PUT", "GET"), methods)
    }
    @Test fun presentDocumentWithoutEtagIsNotOverwritten() {
        val requests = mutableListOf<WebDavHttpRequest>()
        val result = run(WebDavTransport {
            requests += it
            when (it.method) { "PROPFIND" -> WebDavHttpResponse(207); "GET" -> WebDavHttpResponse(200, remote()); else -> WebDavHttpResponse(204) }
        })
        assertTrue(result is WebDavSyncResult.Failure)
        assertTrue((result as WebDavSyncResult.Failure).message.contains("ETag"))
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET", "PROPFIND"), requests.map { it.method })
    }
    @Test fun conflictRetriesOnlyOnceWithFreshEtag() {
        var puts = 0
        var gets = 0
        val conditions = mutableListOf<Pair<String, String>?>()
        val result = run(WebDavTransport {
            when (it.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> { gets++; WebDavHttpResponse(200, remote(), "etag-$gets") }
                else -> { puts++; conditions += it.conditionHeader; WebDavHttpResponse(412) }
            }
        })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(2, puts)
        assertEquals(2, gets)
        assertEquals(listOf("If-Match" to "etag-1", "If-Match" to "etag-2"), conditions)
    }
    @Test fun wrongDocumentOwnerNeverPut() {
        val methods = mutableListOf<String>()
        val result = run(WebDavTransport {
            methods += it.method
            if (it.method == "PROPFIND") WebDavHttpResponse(207) else WebDavHttpResponse(200, remote("b".repeat(32)))
        })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET"), methods)
    }
    @Test fun malformedRemoteDoesNotPutOrApply() {
        val methods = mutableListOf<String>()
        val result = run(WebDavTransport {
            methods += it.method
            if (it.method == "PROPFIND") WebDavHttpResponse(207) else WebDavHttpResponse(200, "{broken")
        })
        assertTrue(result is WebDavSyncResult.Failure)
        assertEquals(listOf("PROPFIND", "PROPFIND", "GET"), methods)
    }
    @Test fun transportExceptionDiagnosticDoesNotLeakCredentials() {
        val result = run(WebDavTransport { error("fake-password secret-address") }) as WebDavSyncResult.Failure
        assertFalse(result.message.contains("fake-password"))
        assertFalse(result.message.contains("secret-address"))
    }
}
