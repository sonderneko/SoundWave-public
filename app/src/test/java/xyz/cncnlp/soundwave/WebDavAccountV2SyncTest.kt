package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.nio.charset.StandardCharsets

class WebDavAccountV2SyncTest {
    private val baseUrl = "https://account-v2.invalid/dav"
    private val username = "webdav-user"
    private val password = "webdav-password"

    @Test
    fun accountsRoundTripIntoDifferentOwnedV2SidecarsWithoutReadingLegacyFile() {
        val userA = AccountDataIdentity.key("Alice")
        val userB = AccountDataIdentity.key("alice")
        assertFalse("identity hashing preserves case", userA == userB)
        val legacyRootUrl = "$baseUrl/soundwave-sync.json"
        val legacyAppUrl = "$baseUrl/SoundWave/soundwave-sync.json"
        val legacyDocument = JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 1)
            .put("user", JSONObject().put("dailyListenStats", JSONObject().put("legacy-day", 99)))
            .toString()
        val transport = AccountRemote(mapOf(legacyRootUrl to legacyDocument, legacyAppUrl to legacyDocument))
        val now = System.currentTimeMillis()
        val song = Song(id = "stats-song", name = "Stats", singer = "Artist", source = "wy")
        val genuineStats = ListenStatsDailyStore.record("{}", song, "device-a", 37L, true, now)
        val localA = bundle("only-account-a", userA, genuineStats)
        val foreignStats = ListenStatsDailyStore.record("{}", song, "device-b", 91L, true, now)
        val localB = bundle("only-account-b", AccountDataIdentity.key("Bob"), foreignStats)

        val resultA = client(transport, userA).sync(baseUrl, username, password, localA, verifiedGate())
        val resultB = client(transport, userB).sync(baseUrl, username, password, localB, verifiedGate())
        // An empty local snapshot for Alice must restore and retain her own remote stats.
        val secondA = client(transport, userA).sync(
            baseUrl, username, password, bundle("only-account-a", userA), verifiedGate()
        )

        assertTrue("$resultA", resultA is WebDavSyncResult.Success)
        assertTrue("$resultB", resultB is WebDavSyncResult.Success)
        assertTrue("$secondA", secondA is WebDavSyncResult.Success)
        val urlA = scopedUrl(userA)
        val urlB = scopedUrl(userB)
        assertTrue(transport.remote.containsKey(urlA))
        assertTrue(transport.remote.containsKey(urlB))
        assertFalse(urlA == urlB)
        assertEquals(2, transport.remote.keys.count { it.contains("soundwave-account-v2-") })

        val envelopeA = JSONObject(requireNotNull(transport.remote[urlA]))
        val envelopeB = JSONObject(requireNotNull(transport.remote[urlB]))
        assertEquals(2, envelopeA.getInt("version"))
        assertEquals(userA, envelopeA.getString("owner"))
        assertEquals(2, envelopeB.getInt("version"))
        assertEquals(userB, envelopeB.getString("owner"))
        assertTrue(envelopeA.getJSONObject("user").getJSONArray("favs").toString().contains("only-account-a"))
        assertFalse(envelopeA.getJSONObject("user").getJSONArray("favs").toString().contains("only-account-b"))
        assertTrue(envelopeB.getJSONObject("user").getJSONArray("favs").toString().contains("only-account-b"))
        assertFalse(envelopeB.getJSONObject("user").getJSONArray("favs").toString().contains("only-account-a"))

        val statsA = envelopeA.getJSONObject("user").getJSONObject("dailyListenStatsV2")
        val statsB = envelopeB.getJSONObject("user").getJSONObject("dailyListenStatsV2")
        assertEquals(userA, statsA.getString("owner"))
        assertEquals(userB, statsB.getString("owner"))
        assertTrue("foreign local stats are discarded", statsB.getJSONObject("days").length() == 0)
        assertEquals(37L, todayTotal((secondA as WebDavSyncResult.Success).bundle.dailyListenStatsJson, "device-a"))
        assertEquals(0L, todayTotal((resultB as WebDavSyncResult.Success).bundle.dailyListenStatsJson, "device-b"))
        assertFalse(envelopeA.getJSONObject("user").has("dailyListenStats"))
        assertFalse(envelopeB.getJSONObject("user").has("dailyListenStats"))

        assertEquals(legacyDocument, transport.remote[legacyRootUrl])
        assertEquals(legacyDocument, transport.remote[legacyAppUrl])
        assertFalse("account mode must not fetch or touch either v1 file", transport.requests.any {
            it.url == legacyRootUrl || it.url == legacyAppUrl
        })
    }

    @Test
    fun accountV2RejectsSidecarOwnedByAnotherAccountWithoutPut() {
        val requestedOwner = AccountDataIdentity.key("requested")
        val actualOwner = AccountDataIdentity.key("other")
        val url = scopedUrl(requestedOwner)
        val wrongOwnerDocument = JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 2)
            .put("owner", actualOwner)
            .put("user", bundle("remote-private", actualOwner).toCloudJson(includeAvatar = false))
            .toString()
        val transport = AccountRemote(mapOf(url to wrongOwnerDocument))

        val result = client(transport, requestedOwner).sync(
            baseUrl, username, password, bundle("local-private", requestedOwner), verifiedGate()
        )

        assertTrue("owner mismatch is rejected: $result", result is WebDavSyncResult.Failure)
        assertTrue(transport.requests.any { it.method == "GET" && it.url == url })
        assertFalse(transport.requests.any { it.method == "PUT" })
        assertEquals(wrongOwnerDocument, transport.remote[url])
    }

    @Test
    fun accountV2ConflictRetryStaysOnTheScopedAndOwnerVerifiedFile() {
        val owner = AccountDataIdentity.key("retry-user")
        val url = scopedUrl(owner)
        val ownedRemote = JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 2)
            .put("owner", owner)
            .put("user", bundle("remote-before-conflict", owner).toCloudJson(includeAvatar = false))
            .toString()
        val transport = AccountRemote(mapOf(url to ownedRemote), conflictOnceOnPut = true)

        val result = client(transport, owner).sync(
            baseUrl, username, password, bundle("local-conflict-edit", owner), verifiedGate()
        )

        assertTrue("conflict retry should merge successfully: $result", result is WebDavSyncResult.Success)
        val fileRequests = transport.requests.filter { it.method == "GET" || it.method == "PUT" }
        assertTrue(fileRequests.isNotEmpty())
        assertTrue(fileRequests.all { it.url == url })
        assertEquals(2, fileRequests.count { it.method == "PUT" })
        val finalEnvelope = JSONObject(requireNotNull(transport.remote[url]))
        assertEquals(2, finalEnvelope.getInt("version"))
        assertEquals(owner, finalEnvelope.getString("owner"))
        val favs = finalEnvelope.getJSONObject("user").getJSONArray("favs").toString()
        assertTrue(favs.contains("remote-before-conflict"))
        assertTrue(favs.contains("local-conflict-edit"))
    }

    @Test
    fun accountSessionExpirationAfterGetStopsBeforePut() {
        val transport = AccountRemote()
        val key = AccountDataIdentity.key("Frank")
        val client = client(transport, key, isCurrent = { transport.requests.none { it.method == "GET" } })

        val result = client.sync(
            baseUrl, username, password, bundle("must-not-upload", key), verifiedGate()
        )

        assertTrue("session expiration after the scoped GET must fail closed: $result", result is WebDavSyncResult.Failure)
        assertTrue(transport.requests.any { it.method == "GET" && it.url == scopedUrl(key) })
        assertFalse(transport.requests.any { it.method == "PUT" })
    }

    private fun client(
        transport: WebDavTransport,
        key: String,
        isCurrent: () -> Boolean = { true }
    ) = WebDavSyncClient(transport = transport, accountDataKey = key, accountDataCurrent = isCurrent)

    private fun verifiedGate() = {
        LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY
    }

    private fun scopedUrl(key: String) =
        "$baseUrl/SoundWave/soundwave-account-v2-${key.removePrefix("swown2_")}.json"

    private fun todayTotal(raw: String, deviceId: String): Long {
        val days = JSONObject(raw)
        val today = ListeningCalendar.dayKey(System.currentTimeMillis())
        return days.optJSONObject(today)?.optJSONObject("totalByDevice")?.optLong(deviceId, 0L) ?: 0L
    }

    private fun bundle(id: String, owner: String? = null, stats: String = "{}"): LocalStore.UserBundle =
        LocalStore.UserBundle(
            favs = mutableListOf(Song(id = id, name = id, singer = "Artist", source = "wy")),
            dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = owner
        )

    private class AccountRemote(
        initial: Map<String, String> = emptyMap(),
        private val conflictOnceOnPut: Boolean = false
    ) : WebDavTransport {
        val requests = mutableListOf<WebDavHttpRequest>()
        val remote = initial.toMutableMap()
        private var hasReturnedConflict = false

        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            requests += request
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> remote[request.url]?.let { WebDavHttpResponse(200, it, "\"account-etag\"") }
                    ?: WebDavHttpResponse(404)
                "PUT" -> {
                    if (conflictOnceOnPut && !hasReturnedConflict) {
                        hasReturnedConflict = true
                        WebDavHttpResponse(412)
                    } else {
                        remote[request.url] = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
                        WebDavHttpResponse(201, etag = "\"written-etag\"")
                    }
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }
}
