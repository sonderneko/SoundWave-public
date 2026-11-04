package xyz.cncnlp.soundwave

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.collectionUrl
import xyz.cncnlp.soundwave.sync.parseWebDavEtag
import xyz.cncnlp.soundwave.sync.webDavAppDirectoryUrl
import xyz.cncnlp.soundwave.sync.webDavLegacyDirectoryUrl
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.0.655：WebDAV 服务器兼容性（MockWebServer + 生产 OkHttp 传输）。
 * 坚果云：/dav/ 根下 PUT 文件返回 404；子目录需先 MKCOL（已存在返回 405）；目录内 PUT 201。
 */
class WebDavServerCompatTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDavServer

    @Before
    fun start() {
        dav = FakeDavServer()
        server = MockWebServer()
        server.dispatcher = dav
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        // qemu 模拟的 x86_64 JVM 不支持关闭监听套接字时的线程信号（NativeThread.signal），忽略关闭异常。
        runCatching { server.shutdown() }
    }

    private val baseUrl: String get() = "http://127.0.0.1:${server.port}/dav/"
    private val client = WebDavSyncClient(allowLoopbackHttpForTests = true)
    private val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
    private val expectedAuth = "Basic " + Base64.getEncoder()
        .encodeToString("nut@example.com:app-password".toByteArray(StandardCharsets.UTF_8))

    @Test
    fun jianguoyunRootPut404IsAvoidedByCreatingAppDirectory() {
        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        assertTrue((result as WebDavSyncResult.Success).remoteFileCreated)
        assertFalse(result.migratedLegacyFile)
        assertEquals(
            listOf(
                "PROPFIND /dav/",
                "PROPFIND /dav/SoundWave/",
                "MKCOL /dav/SoundWave/",
                "GET /dav/SoundWave/soundwave-sync.json",
                "GET /dav/soundwave-sync.json",
                "PUT /dav/SoundWave/soundwave-sync.json"
            ),
            dav.log
        )
        assertTrue("never PUT at the Jianguoyun root", dav.log.none { it == "PUT /dav/soundwave-sync.json" })
        assertTrue("Basic auth is sent preemptively on every request", dav.auth.all { it == expectedAuth })
        val put = dav.recorded.single { it.method == "PUT" }
        assertTrue(put.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("*", put.getHeader("If-None-Match"))
        val stored = JSONObject(requireNotNull(dav.files["/dav/SoundWave/soundwave-sync.json"]))
        assertEquals("soundwave-webdav-sync", stored.getString("schema"))
        assertEquals("local-a", stored.getJSONObject("user").getJSONArray("favs").getJSONObject(0).getString("id"))
    }

    @Test
    fun secondSyncReusesDirectoryAndUsesIfMatchWithoutLegacyRead() {
        assertTrue(client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate) is WebDavSyncResult.Success)
        dav.log.clear()
        dav.recorded.clear()

        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-b"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        assertFalse((result as WebDavSyncResult.Success).remoteFileCreated)
        assertEquals(
            listOf(
                "PROPFIND /dav/",
                "PROPFIND /dav/SoundWave/",
                "GET /dav/SoundWave/soundwave-sync.json",
                "PUT /dav/SoundWave/soundwave-sync.json"
            ),
            dav.log
        )
        val put = dav.recorded.single { it.method == "PUT" }
        assertEquals("\"v1\"", put.getHeader("If-Match"))
        val favs = JSONObject(requireNotNull(dav.files["/dav/SoundWave/soundwave-sync.json"]))
            .getJSONObject("user").getJSONArray("favs").toString()
        assertTrue(favs.contains("local-a") && favs.contains("local-b"))
    }

    @Test
    fun legacyRootFileIsMergedIntoAppDirectoryAndLeftInPlace() {
        val legacy = document("legacy-remote")
        dav.files["/dav/soundwave-sync.json"] = legacy

        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        result as WebDavSyncResult.Success
        assertTrue(result.migratedLegacyFile)
        assertTrue(result.bundle.favs.map { it.id }.containsAll(listOf("local-a", "legacy-remote")))
        val migrated = requireNotNull(dav.files["/dav/SoundWave/soundwave-sync.json"])
        assertTrue(migrated.contains("legacy-remote") && migrated.contains("local-a"))
        assertEquals("legacy file is never modified or deleted", legacy, dav.files["/dav/soundwave-sync.json"])
        assertTrue(dav.log.none { it.startsWith("PUT /dav/soundwave") || it.startsWith("DELETE") })
    }

    @Test
    fun connectionTestVerifiesWriteWithProbeFileAndCleansUp() {
        val error = client.testConnection(baseUrl, "nut@example.com", "app-password", gateStateProvider = gate)

        assertNull("${dav.log}", error)
        assertEquals("PROPFIND /dav/", dav.log[0])
        assertEquals("PROPFIND /dav/SoundWave/", dav.log[1])
        assertEquals("MKCOL /dav/SoundWave/", dav.log[2])
        assertTrue(dav.log[3], Regex("PUT /dav/SoundWave/soundwave-probe-[a-f0-9]{16}\\.txt").matches(dav.log[3]))
        assertEquals(dav.log[3].replace("PUT", "DELETE"), dav.log[4])
        assertEquals(5, dav.log.size)
        assertTrue("probe removed", dav.files.keys.none { it.contains("probe") })
        assertTrue(dav.collections.contains("/dav/SoundWave/"))
    }

    @Test
    fun connectionTestReportsReadOnlyAccountAsWriteFailure() {
        dav.denyPut = true
        val error = client.testConnection(baseUrl, "nut@example.com", "app-password", gateStateProvider = gate)

        assertTrue("$error", error.orEmpty().contains("写入测试") && error.orEmpty().contains("HTTP 403"))
        assertFalse(error.orEmpty().contains("app-password"))
    }

    @Test
    fun mkcol405IsTreatedAsExistingDirectory() {
        // 某些服务对已存在目录的 PROPFIND 不可见却拒绝 MKCOL 405（并发创建等）。
        dav.hideCollectionsFromPropfind = true
        dav.collections += "/dav/SoundWave/"

        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        assertTrue(dav.log.contains("MKCOL /dav/SoundWave/"))
        assertTrue(dav.files.containsKey("/dav/SoundWave/soundwave-sync.json"))
    }

    @Test
    fun missingParentOnMkcolIsReportedClearly() {
        dav.mkcolStatusOverride = 409
        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate)

        assertTrue(result is WebDavSyncResult.Failure)
        val message = (result as WebDavSyncResult.Failure).message
        assertTrue(message, message.contains("MKCOL") && message.contains("HTTP 409") && message.contains("上级目录不存在"))
        assertTrue(dav.log.none { it.startsWith("PUT") })
    }

    @Test
    fun nextcloudStyleServerWithoutGetEtagFallsBackToPropfindGetetag() {
        dav.collections += "/dav/SoundWave/"
        dav.files["/dav/SoundWave/soundwave-sync.json"] = document("remote-a")
        dav.etags["/dav/SoundWave/soundwave-sync.json"] = 1
        dav.omitEtagOnGet = true

        val result = client.sync(baseUrl, "nut@example.com", "app-password", bundle("local-a"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        assertTrue(dav.log.contains("PROPFIND /dav/SoundWave/soundwave-sync.json"))
        assertEquals("\"v1\"", dav.recorded.single { it.method == "PUT" }.getHeader("If-Match"))
    }

    @Test
    fun baseUrlAlreadyPointingAtSoundWaveFolderIsNotNested() {
        dav.collections += "/dav/SoundWave/"
        val result = client.sync("http://127.0.0.1:${server.port}/dav/SoundWave", "nut@example.com", "app-password", bundle("x"), gate)

        assertTrue("$result; ${dav.log}", result is WebDavSyncResult.Success)
        assertEquals(
            listOf("PROPFIND /dav/SoundWave/", "GET /dav/SoundWave/soundwave-sync.json", "PUT /dav/SoundWave/soundwave-sync.json"),
            dav.log
        )
    }

    @Test
    fun lockedAndConcurrentConflictMessagesAreClear() {
        dav.putStatusOverride = 423
        val locked = client.sync(baseUrl, "nut@example.com", "app-password", bundle("x"), gate)
        assertTrue((locked as WebDavSyncResult.Failure).message.let { it.contains("HTTP 423") && it.contains("锁定") })

        dav.putStatusOverride = 412
        dav.files["/dav/SoundWave/soundwave-sync.json"] = document("remote")
        dav.etags["/dav/SoundWave/soundwave-sync.json"] = 7
        dav.log.clear()
        val conflict = client.sync(baseUrl, "nut@example.com", "app-password", bundle("x"), gate)
        val message = (conflict as WebDavSyncResult.Failure).message
        assertTrue(message, message.contains("HTTP 412") && message.contains("其他设备"))
        assertEquals("one re-read/merge retry only", 2, dav.log.count { it.startsWith("PUT") })
    }

    @Test
    fun urlHelpersJoinWithExactlyOneSlash() {
        assertEquals("https://dav.jianguoyun.com/dav/", collectionUrl("https://dav.jianguoyun.com/dav"))
        assertEquals("https://dav.jianguoyun.com/dav/SoundWave/", webDavAppDirectoryUrl("https://dav.jianguoyun.com/dav"))
        assertEquals("https://dav.jianguoyun.com/dav/", webDavLegacyDirectoryUrl("https://dav.jianguoyun.com/dav"))
        assertEquals("https://h/remote.php/dav/files/u/SoundWave/", webDavAppDirectoryUrl("https://h/remote.php/dav/files/u/SoundWave"))
        assertNull(webDavLegacyDirectoryUrl("https://h/remote.php/dav/files/u/soundwave"))
        assertEquals("\"abc\"", parseWebDavEtag("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop><d:getetag>&quot;abc&quot;</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"))
        assertEquals("W/\"x\"", parseWebDavEtag("<D:getetag xmlns:D=\"DAV:\">W/\"x\"</D:getetag>"))
        assertNull(parseWebDavEtag("<d:multistatus/>"))
    }

    private fun bundle(vararg ids: String) = LocalStore.UserBundle(
        favs = ids.mapIndexed { i, id -> Song(id = id, name = id, singer = "A", source = "wy", syncAddedAt = (i + 1) * 100L) }.toMutableList()
    )

    private fun document(vararg ids: String): String {
        val user = bundle(*ids).toCloudJson(includeAvatar = false).apply { remove("enabledScripts"); remove("scriptOrder") }
        return JSONObject().put("schema", "soundwave-webdav-sync").put("version", 1).put("user", user).toString()
    }

    /** Minimal in-memory WebDAV server with Jianguoyun semantics. */
    private class FakeDavServer : Dispatcher() {
        val log = CopyOnWriteArrayList<String>()
        val auth = CopyOnWriteArrayList<String?>()
        val recorded = CopyOnWriteArrayList<RecordedRequest>()
        val files = ConcurrentHashMap<String, String>()
        val etags = ConcurrentHashMap<String, Int>()
        val collections: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf("/dav/"))
        @Volatile var denyPut = false
        @Volatile var hideCollectionsFromPropfind = false
        @Volatile var omitEtagOnGet = false
        @Volatile var mkcolStatusOverride: Int? = null
        @Volatile var putStatusOverride: Int? = null
        private val version = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            val method = request.method.orEmpty()
            log += "$method $path"
            auth += request.getHeader("Authorization")
            recorded += request
            if (request.getHeader("Authorization") == null) return MockResponse().setResponseCode(401)
            return when (method) {
                "PROPFIND" -> when {
                    path.endsWith("/") && path in collections && (!hideCollectionsFromPropfind || path == "/dav/") ->
                        MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>")
                    files.containsKey(path) -> MockResponse().setResponseCode(207).setBody(
                        "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop>" +
                            "<d:getetag>&quot;v${etags[path]}&quot;</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"
                    )
                    else -> MockResponse().setResponseCode(404)
                }
                "MKCOL" -> {
                    mkcolStatusOverride?.let { return MockResponse().setResponseCode(it) }
                    if (path in collections) MockResponse().setResponseCode(405)
                    else if (parentOf(path) !in collections) MockResponse().setResponseCode(409)
                    else { collections += path; MockResponse().setResponseCode(201) }
                }
                "GET" -> files[path]?.let { body ->
                    MockResponse().setResponseCode(200).setBody(body).apply {
                        if (!omitEtagOnGet) setHeader("ETag", "\"v${etags[path]}\"")
                    }
                } ?: MockResponse().setResponseCode(404)
                "PUT" -> {
                    putStatusOverride?.let { return MockResponse().setResponseCode(it) }
                    // 坚果云：根目录不允许直接创建文件；父目录必须已存在。
                    if (parentOf(path) == "/dav/" || parentOf(path) !in collections) return MockResponse().setResponseCode(404)
                    if (denyPut) return MockResponse().setResponseCode(403)
                    val ifNoneMatch = request.getHeader("If-None-Match")
                    val ifMatch = request.getHeader("If-Match")
                    if (ifNoneMatch == "*" && files.containsKey(path)) return MockResponse().setResponseCode(412)
                    if (ifMatch != null && ifMatch != "\"v${etags[path]}\"") return MockResponse().setResponseCode(412)
                    val created = !files.containsKey(path)
                    files[path] = request.body.readUtf8()
                    etags[path] = version.incrementAndGet()
                    MockResponse().setResponseCode(if (created) 201 else 204).setHeader("ETag", "\"v${etags[path]}\"")
                }
                "DELETE" -> if (files.remove(path) != null) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
                else -> MockResponse().setResponseCode(405)
            }
        }

        private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/') + "/"
    }
}
