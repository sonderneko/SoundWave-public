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

class WebDavTotalRoundTripRegressionTest {
    @Test
    fun missingRemoteRoundTripsDailyStatsAndRepeatedSyncIsIdempotent() {
        val now = System.currentTimeMillis()
        val local = bundleWithStats(
            "local-device",
            now,
            ListenFact(song("shared-song"), 55L),
            ListenFact(song("second-song"), 8L)
        )
        val transport = RecordingWebDavTransport()
        val client = WebDavSyncClient(transport)

        val first = client.sync(WEBDAV_URL, USERNAME, PASSWORD, local, gateStateProvider = VERIFIED_EMPTY)
        assertTrue("$first", first is WebDavSyncResult.Success)
        first as WebDavSyncResult.Success
        assertTrue(first.remoteFileCreated)
        assertStats(first.bundle, now, totalSeconds = 63L, dailySeconds = 63L, songSeconds = mapOf(
            "wy:shared-song" to 55L,
            "wy:second-song" to 8L
        ))
        assertPutStats(transport, now, totalByDevice = mapOf("local-device" to 63L), songSeconds = mapOf(
            "wy:shared-song" to mapOf("local-device" to 55L),
            "wy:second-song" to mapOf("local-device" to 8L)
        ))

        // Read the first PUT back through the same in-memory transport. Shared device facts merge by
        // max, so syncing the same local snapshot a second time must not add the seconds again.
        val second = client.sync(WEBDAV_URL, USERNAME, PASSWORD, local, gateStateProvider = VERIFIED_EMPTY)
        assertTrue("$second", second is WebDavSyncResult.Success)
        second as WebDavSyncResult.Success
        assertFalse(second.remoteFileCreated)
        assertEquals(2, transport.putBodies.size)
        assertStats(second.bundle, now, totalSeconds = 63L, dailySeconds = 63L, songSeconds = mapOf(
            "wy:shared-song" to 55L,
            "wy:second-song" to 8L
        ))
        assertPutStats(transport, now, totalByDevice = mapOf("local-device" to 63L), songSeconds = mapOf(
            "wy:shared-song" to mapOf("local-device" to 55L),
            "wy:second-song" to mapOf("local-device" to 8L)
        ))
    }

    @Test
    fun existingRemoteOldSameDeviceAndAnotherDeviceStatsAreMergedWithoutLosingTotals() {
        val now = System.currentTimeMillis()
        val local = bundleWithStats(
            "local-device",
            now,
            ListenFact(song("shared-song"), 31L)
        )
        val remote = bundleWithStats(
            "local-device",
            now,
            ListenFact(song("shared-song"), 17L),
            ListenFact(song("other-device-song"), 11L, deviceId = "other-device")
        )
        val transport = RecordingWebDavTransport(initialRemoteBody = envelope(remote))

        val result = WebDavSyncClient(transport).sync(
            WEBDAV_URL, USERNAME, PASSWORD, local, gateStateProvider = VERIFIED_EMPTY
        )
        assertTrue("$result", result is WebDavSyncResult.Success)
        val success = result as WebDavSyncResult.Success
        assertFalse(success.remoteFileCreated)
        assertStats(success.bundle, now, totalSeconds = 42L, dailySeconds = 42L, songSeconds = mapOf(
            "wy:shared-song" to 31L,
            "wy:other-device-song" to 11L
        ))
        assertPutStats(transport, now, totalByDevice = mapOf(
            "local-device" to 31L,
            "other-device" to 11L
        ), songSeconds = mapOf(
            "wy:shared-song" to mapOf("local-device" to 31L),
            "wy:other-device-song" to mapOf("other-device" to 11L)
        ))
    }

    @Test
    fun remoteHttp500DoesNotMutateThePassedLocalBundle() {
        val now = System.currentTimeMillis()
        val local = bundleWithStats(
            "local-device",
            now,
            ListenFact(song("local-song"), 29L)
        )
        val existingRemote = envelope(bundleWithStats(
            "remote-device",
            now,
            ListenFact(song("remote-song"), 13L)
        ))
        val beforeCloudJson = local.toCloudJson(includeAvatar = false, includeHistory = true).toString()
        val beforeStatsJson = local.dailyListenStatsJson
        val transport = RecordingWebDavTransport(initialRemoteBody = existingRemote, putStatus = 500)

        val result = WebDavSyncClient(transport).sync(
            WEBDAV_URL, USERNAME, PASSWORD, local, gateStateProvider = VERIFIED_EMPTY
        )

        assertTrue("$result", result is WebDavSyncResult.Failure)
        assertEquals(beforeStatsJson, local.dailyListenStatsJson)
        assertEquals(beforeCloudJson, local.toCloudJson(includeAvatar = false, includeHistory = true).toString())
        assertEquals(1, transport.putBodies.size)
        assertEquals(existingRemote, transport.remoteBody)
    }

    private fun bundleWithStats(defaultDeviceId: String, nowMs: Long, vararg facts: ListenFact): LocalStore.UserBundle {
        val stats = facts.fold(ListenStatsDailyStore.emptyJson()) { current, fact ->
            ListenStatsDailyStore.record(
                rawJson = current,
                song = fact.song,
                deviceId = fact.deviceId ?: defaultDeviceId,
                seconds = fact.seconds,
                incrementCount = true,
                nowMs = nowMs
            )
        }
        return LocalStore.UserBundle(dailyListenStatsJson = stats)
    }

    private fun envelope(bundle: LocalStore.UserBundle): String {
        val user = bundle.toCloudJson(includeAvatar = false, includeHistory = true, includeDailyListenStats = true).apply {
            remove("enabledScripts")
            remove("scriptOrder")
        }
        return JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 1)
            .put("user", user)
            .toString()
    }

    private fun assertStats(
        bundle: LocalStore.UserBundle,
        nowMs: Long,
        totalSeconds: Long,
        dailySeconds: Long,
        songSeconds: Map<String, Long>
    ) {
        val summary = ListenStatsDailyStore.summary(bundle.dailyListenStatsJson, nowMs)
        val dayKey = ListeningCalendar.dayKey(nowMs)
        assertEquals(totalSeconds, summary.totalSeconds)
        assertEquals(dailySeconds, summary.dailySeconds[dayKey])
        songSeconds.forEach { (songKey, seconds) ->
            assertEquals("song seconds for $songKey", seconds, summary.songs[songKey]?.seconds)
        }
    }

    private fun assertPutStats(
        transport: RecordingWebDavTransport,
        nowMs: Long,
        totalByDevice: Map<String, Long>,
        songSeconds: Map<String, Map<String, Long>>
    ) {
        val uploaded = JSONObject(transport.putBodies.last())
        assertEquals("soundwave-webdav-sync", uploaded.getString("schema"))
        val daily = uploaded.getJSONObject("user").getJSONObject("dailyListenStats")
            .getJSONObject(ListeningCalendar.dayKey(nowMs))
        val uploadedTotals = daily.getJSONObject("totalByDevice")
        assertEquals(totalByDevice.keys, uploadedTotals.keys().asSequence().toSet())
        totalByDevice.forEach { (device, seconds) -> assertEquals(seconds, uploadedTotals.getLong(device)) }
        songSeconds.forEach { (songKey, expectedDevices) ->
            val devices = daily.getJSONObject("songs").getJSONObject(songKey).getJSONObject("devices")
            assertEquals(expectedDevices.keys, devices.keys().asSequence().toSet())
            expectedDevices.forEach { (device, seconds) ->
                assertEquals(seconds, devices.getJSONObject(device).getLong("sec"))
            }
        }
    }

    private fun song(id: String) = Song(id = id, name = id, singer = "Test Artist", source = "wy")

    private data class ListenFact(val song: Song, val seconds: Long, val deviceId: String? = null)

    private class RecordingWebDavTransport(
        initialRemoteBody: String? = null,
        private val putStatus: Int = 201
    ) : WebDavTransport {
        val requests = mutableListOf<WebDavHttpRequest>()
        val putBodies = mutableListOf<String>()
        var remoteBody: String? = initialRemoteBody
            private set

        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            requests += request
            return when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "GET" -> remoteBody?.let { WebDavHttpResponse(200, it, ETAG) } ?: WebDavHttpResponse(404)
                "PUT" -> {
                    val body = request.body?.toString(StandardCharsets.UTF_8) ?: ""
                    putBodies += body
                    if (putStatus in setOf(200, 201, 204)) remoteBody = body
                    WebDavHttpResponse(putStatus, etag = ETAG)
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }

    private companion object {
        const val WEBDAV_URL = "https://webdav.invalid/dav"
        const val USERNAME = "test-user"
        const val PASSWORD = "test-password"
        const val ETAG = "\"memory-v1\""
        val VERIFIED_EMPTY = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
    }
}
