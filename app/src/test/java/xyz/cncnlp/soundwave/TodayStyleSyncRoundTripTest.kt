package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.recommend.StylePreferenceSync
import xyz.cncnlp.soundwave.sync.CloudUserMerge
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TodayStyleSyncRoundTripTest {
    private fun preferences(vararg entries: Pair<String, String>): String =
        JSONObject().apply { entries.forEach { (platform, value) -> put(platform, JSONObject(value)) } }.toString()

    @Test
    fun stylePreferencesRoundTripThroughLegacyCompatibleFavoritesMarker() {
        val original = LocalStore.UserBundle(
            favs = mutableListOf(Song(id = "song-1", name = "Song", singer = "Artist", source = "wy")),
            todayStylePreferencesJson = preferences(
                "wy" to """{"tags":["ambient","calm"],"enabled":true,"modifiedAt":1234,"version":2}"""
            )
        )

        val encoded = original.syncSnapshot().toCloudJson()
        val favs = encoded.getJSONArray("favs")
        val marker = (0 until favs.length()).mapNotNull { favs.optJSONObject(it) }
            .first { it.has("_swTodayStylePreferences") }

        assertFalse(marker.has("id"))
        assertEquals(1, original.favs.size)
        assertEquals(1, LocalStore.UserBundle.fromCloud(encoded).favs.size)
        assertEquals(
            StylePreferenceSync.canonical(original.todayStylePreferencesJson),
            LocalStore.UserBundle.fromCloud(encoded).todayStylePreferencesJson
        )
        assertEquals(
            StylePreferenceSync.canonical(original.todayStylePreferencesJson),
            original.syncSnapshot().todayStylePreferencesJson
        )
    }

    @Test
    fun legacyBundleWithoutMarkerDefaultsToEmptyPreferences() {
        val restored = LocalStore.UserBundle.fromCloud(
            JSONObject().put("favs", org.json.JSONArray().put(
                JSONObject().put("id", "legacy-song").put("name", "Legacy").put("source", "wy")
            ))
        )

        assertEquals("{}", restored.todayStylePreferencesJson)
        assertEquals(1, restored.favs.size)
        assertFalse(restored.toCloudJson().getJSONArray("favs").toString().contains("_swTodayStylePreferences"))
    }

    @Test
    fun mergeUsesTimestampAndLocalWinsMissingOrEqualTimestamps() {
        val local = preferences(
            "wy" to """{"tags":["local-tie"],"enabled":false,"modifiedAt":10,"version":1}""",
            "qq" to """{"tags":["local-older"],"enabled":true,"modifiedAt":4,"version":99}""",
            "kg" to """{"tags":["local-undated"],"enabled":false,"version":100}""",
            "bili" to """{"tags":["local-only"],"enabled":true,"modifiedAt":2,"version":1}"""
        )
        val remote = preferences(
            "wy" to """{"tags":["remote-tie"],"enabled":true,"modifiedAt":10,"version":50}""",
            "qq" to """{"tags":["remote-newer"],"enabled":false,"modifiedAt":5,"version":1}""",
            "kg" to """{"tags":["remote-dated"],"enabled":true,"modifiedAt":8,"version":1000}""",
            "netease" to """{"tags":["remote-only"],"enabled":true,"modifiedAt":1,"version":1}"""
        )

        val merged = CloudUserMerge.merge(
            LocalStore.UserBundle(todayStylePreferencesJson = local),
            LocalStore.UserBundle(todayStylePreferencesJson = remote)
        )
        val values = JSONObject(merged.todayStylePreferencesJson)

        assertEquals("local-tie", values.getJSONObject("wy").getJSONArray("tags").getString(0))
        assertEquals("remote-newer", values.getJSONObject("qq").getJSONArray("tags").getString(0))
        assertEquals("local-undated", values.getJSONObject("kg").getJSONArray("tags").getString(0))
        assertTrue(values.has("bili"))
        assertTrue(values.has("netease"))
    }

    @Test
    fun compactSyncPayloadCarriesStylePreferencesMarker() {
        val bundle = LocalStore.UserBundle(todayStylePreferencesJson = preferences(
            "wy" to """{"tags":["focus"],"enabled":true,"modifiedAt":12,"version":3}"""
        ))

        // 账号保存客户端已删除：载荷即 WebDAV 账号文档共用的同一份云序列化。
        val data = bundle.syncSnapshot().toCloudJson(
            includeAvatar = false, includeHistory = true,
            historyLimit = PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT,
            includeDailyListenStats = true, includeLocalUris = false)
        val marker = data.getJSONArray("favs").getJSONObject(0).getJSONObject("_swTodayStylePreferences")

        assertEquals("focus", marker.getJSONObject("wy").getJSONArray("tags").getString(0))
        assertEquals(
            StylePreferenceSync.canonical(bundle.todayStylePreferencesJson),
            LocalStore.UserBundle.fromCloud(data).todayStylePreferencesJson
        )
    }

    @Test
    fun webDavPayloadAndReadbackRetainStylePreferences() {
        var remoteBody: String? = null
        var etag = 0
        val transport = WebDavTransport { request: WebDavHttpRequest ->
            when (request.method) {
                "PROPFIND" -> WebDavHttpResponse(207)
                "MKCOL" -> WebDavHttpResponse(201)
                "GET" -> remoteBody?.let { WebDavHttpResponse(200, it, "\"e$etag\"") } ?: WebDavHttpResponse(404)
                "PUT" -> {
                    remoteBody = request.body?.toString(StandardCharsets.UTF_8)
                    etag++
                    WebDavHttpResponse(201, etag = "\"e$etag\"")
                }
                else -> WebDavHttpResponse(405)
            }
        }
        val client = WebDavSyncClient(transport)
        val gate = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        val local = LocalStore.UserBundle(todayStylePreferencesJson = preferences(
            "wy" to """{"tags":["focus"],"enabled":true,"modifiedAt":12,"version":3}"""
        ))

        val first = client.sync("https://dav.example.com/dav", "u", "p", local, gateStateProvider = gate)
        assertTrue("$first", first is WebDavSyncResult.Success)
        val uploaded = JSONObject(remoteBody!!).getJSONObject("user")
        assertEquals("focus", uploaded.getJSONArray("favs").getJSONObject(0)
            .getJSONObject("_swTodayStylePreferences").getJSONObject("wy")
            .getJSONArray("tags").getString(0))

        val second = client.sync("https://dav.example.com/dav", "u", "p", LocalStore.UserBundle(), gateStateProvider = gate)
        assertTrue("$second", second is WebDavSyncResult.Success)
        assertEquals(
            StylePreferenceSync.canonical(local.todayStylePreferencesJson),
            (second as WebDavSyncResult.Success).bundle.todayStylePreferencesJson
        )
    }

    @Test
    fun inactiveProfileBackupSerializerAndImporterKeepTheMarker() {
        val bundle = LocalStore.UserBundle(todayStylePreferencesJson = preferences(
            "wy" to """{"tags":["focus"],"enabled":true,"modifiedAt":12,"version":3}"""
        ))
        val bytes = InactiveProfileBackupSerializer.serialize(
            bundle.toCloudJson(includeAvatar = true, includeHistory = true, includeLocalUris = true),
            JSONArray()
        )

        val imported = LocalProfileBackupImporter.read(ByteArrayInputStream(bytes)).bundle

        assertEquals(
            StylePreferenceSync.canonical(bundle.todayStylePreferencesJson),
            imported.todayStylePreferencesJson
        )
        assertTrue(imported.favs.isEmpty())
    }
}
