package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncConflictGate
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.nio.charset.StandardCharsets

/**
 * The generic sync catch must stay honest (remote state uncertain) AND self-diagnosing:
 * it names the failing step + exception class, and never leaks credentials or the URL.
 * The payload-size guard gets its own actionable message instead of the opaque banner.
 */
class WebDavSyncDiagnosticTest {
    private val baseUrl = "https://dav.jianguoyun.com/dav"
    private val username = "someone@gmail.com"
    private val password = "app-password"
    private val owner = AccountDataIdentity.key(username)
    private val banner = "WebDAV 同步流程异常；远端结果未确认。请先检查远端同步文件，再决定是否重试。"

    private fun localBundle(): LocalStore.UserBundle {
        val now = System.currentTimeMillis()
        return LocalStore.UserBundle(
            favs = mutableListOf(Song(id = "f1", name = "F", singer = "S", source = "wy", syncAddedAt = now)),
            playlists = mutableListOf(Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf())),
            history = mutableListOf(),
            dailyListenStatsJson = "{}",
            dailyListenStatsOwnerV2 = owner
        )
    }

    private fun extras(capture: () -> JSONObject) = WebDavProfileExtrasSync(capture = capture, apply = {}, isCurrent = { true })

    private fun goodExtras(): JSONObject = JSONObject()
        .put("scripts", JSONObject().put("updatedAt", 1L).put("data", JSONObject()))
        .put("smartSource", JSONObject().put("updatedAt", 1L).put("data", JSONObject()))
        .put("settings", JSONObject().put("updatedAt", 1L).put("data", JSONObject()))
        .put("dislikes", JSONObject().put("updatedAt", 1L).put("data", JSONObject()))

    private fun client(transport: WebDavTransport, extrasSync: WebDavProfileExtrasSync?) = WebDavSyncClient(
        transport = transport,
        accountDataKey = owner,
        accountDataCurrent = { true },
        profileName = "本机资料",
        profileExtrasSync = extrasSync
    )

    private fun gate() = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

    @Test
    fun extrasCaptureThrowReportsStepAndExceptionClass() {
        val transport = FakeDav()
        val result = client(transport, extras { throw IllegalStateException("sensitive local detail") })
            .sync(baseUrl, username, password, localBundle(), gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Failure)
        val failure = result as WebDavSyncResult.Failure
        assertTrue(failure.message, failure.message.startsWith(banner))
        assertTrue(failure.message, failure.message.contains("失败步「读取本机扩展数据」"))
        assertTrue(failure.message, failure.message.contains("IllegalStateException"))
        assertTrue("generic catch must stay uncertain", failure.remoteStateUncertain)
        assertFalse(failure.message, failure.message.contains(password))
        assertFalse(failure.message, failure.message.contains(username))
        assertFalse(failure.message, failure.message.contains("jianguoyun"))
        assertFalse(failure.message, failure.message.contains("sensitive local detail"))
        assertFalse("no PUT may be attempted", transport.methods.contains("PUT"))
    }

    @Test
    fun historyProviderThrowReportsMergeStep() {
        val transport = FakeDav()
        val throwingClient = WebDavSyncClient(
            transport = transport,
            localPlaybackHistory = { throw RuntimeException("boom") },
            accountDataKey = owner,
            accountDataCurrent = { true },
            profileName = "本机资料",
            profileExtrasSync = extras { goodExtras() }
        )
        val result = throwingClient.sync(baseUrl, username, password, localBundle(), gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Failure)
        val failure = result as WebDavSyncResult.Failure
        assertTrue(failure.message, failure.message.contains("失败步「合并本地与远端数据」"))
        assertTrue(failure.message, failure.message.contains("RuntimeException"))
        assertFalse(failure.message, failure.message.contains("boom"))
    }

    @Test
    fun conflictConfirmThrowReportsGateStep() {
        val seedTransport = FakeDav()
        val seed = client(seedTransport, extras { goodExtras() })
            .sync(baseUrl, username, password, localBundle(), gateStateProvider = gate())
        assertTrue("seed: $seed", seed is WebDavSyncResult.Success)
        val transport = FakeDav(seedTransport.remote)
        val now = System.currentTimeMillis()
        val gate = WebDavSyncConflictGate(
            lastSyncAt = { now - 10_000 },
            localChangedAt = { now - 100 },
            confirm = { _, _ -> throw IllegalStateException("ui gone") }
        )
        val conflictClient = WebDavSyncClient(
            transport = transport,
            accountDataKey = owner,
            accountDataCurrent = { true },
            profileName = "本机资料",
            profileExtrasSync = extras { goodExtras() },
            conflictGate = gate
        )
        val result = conflictClient.sync(baseUrl, username, password, localBundle(), gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Failure)
        val failure = result as WebDavSyncResult.Failure
        assertTrue(failure.message, failure.message.contains("失败步「等待冲突确认」"))
        assertFalse(failure.message, failure.message.contains("ui gone"))
    }

    @Test
    fun oversizeInlineImageCoverGetsItsDesignedMessage() {
        // Root-cause regression: a custom cover stored as a data:image URI over the
        // 512 KiB cloud cap throws CloudInlineImageTooLargeException during cloud
        // serialization; it must surface its own guidance, not the generic banner.
        val transport = FakeDav()
        val hugeCover = "data:image/jpeg;base64," + "A".repeat(600 * 1024)
        val big = localBundle().copy(
            playlists = mutableListOf(
                Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()),
                Playlist(
                    id = "big", name = "Big", source = "wy",
                    songs = mutableListOf(Song(id = "s", name = "S", singer = "A", source = "wy", cover = hugeCover))
                )
            )
        )
        val result = client(transport, extras { goodExtras() })
            .sync(baseUrl, username, password, big, gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Failure)
        val failure = result as WebDavSyncResult.Failure
        assertTrue(failure.message, failure.message.contains("自定义封面 data:image 超过云同步上限"))
        assertTrue(failure.message, failure.message.contains("请压缩图片或改用公开图片链接"))
        assertFalse(failure.message, failure.message.startsWith(banner))
        assertFalse("rejected before PUT: remote untouched", failure.remoteStateUncertain)
        assertFalse("no PUT may be attempted", transport.methods.contains("PUT"))
    }

    @Test
    fun underCapInlineImageCoverSyncsFine() {
        val transport = FakeDav()
        val okCover = "data:image/jpeg;base64," + "A".repeat(100 * 1024)
        val bundle = localBundle().copy(
            playlists = mutableListOf(
                Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()),
                Playlist(
                    id = "ok", name = "Ok", source = "wy",
                    songs = mutableListOf(Song(id = "s", name = "S", singer = "A", source = "wy", cover = okCover))
                )
            )
        )
        val result = client(transport, extras { goodExtras() })
            .sync(baseUrl, username, password, bundle, gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun oversizePayloadGetsSpecificMessageAndNoPut() {
        val transport = FakeDav()
        val longName = "歌".repeat(200)
        val longCover = "https://example.com/" + "c".repeat(200) + ".jpg"
        val playlists = mutableListOf(Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()))
        for (p in 0 until 16) {
            playlists += Playlist(
                id = "pl$p", name = "P$p", source = "wy", remoteId = "${2000 + p}",
                songs = (0 until 500).map {
                    Song(
                        id = "p${p}s$it", name = longName, singer = longName, source = "wy",
                        album = longName, cover = longCover, duration = 240000L
                    )
                }.toMutableList(),
                count = 500
            )
        }
        val big = localBundle().copy(playlists = playlists)
        val result = client(transport, extras { goodExtras() })
            .sync(baseUrl, username, password, big, gateStateProvider = gate())
        assertTrue("$result", result is WebDavSyncResult.Failure)
        val failure = result as WebDavSyncResult.Failure
        assertTrue(failure.message, failure.message.contains("超过单文件上限"))
        assertFalse(failure.message, failure.message.startsWith(banner))
        assertFalse("oversize is rejected before PUT: remote untouched", failure.remoteStateUncertain)
        assertFalse("no PUT may be attempted", transport.methods.contains("PUT"))
    }

    private class FakeDav(val remote: MutableMap<String, String> = mutableMapOf()) : WebDavTransport {
        val methods = mutableListOf<String>()
        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            methods += request.method
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "MKCOL" -> WebDavHttpResponse(405)
                "GET" -> remote[request.url]?.let { WebDavHttpResponse(200, it, "\"e1\"") } ?: WebDavHttpResponse(404)
                "PUT" -> {
                    remote[request.url] = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
                    WebDavHttpResponse(201, etag = "\"written\"")
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }
}
