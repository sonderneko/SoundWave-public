package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.sync.WebDavHttpRequest
import xyz.cncnlp.soundwave.sync.WebDavHttpResponse
import xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync
import xyz.cncnlp.soundwave.sync.WebDavSyncClient
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.nio.charset.StandardCharsets
import java.util.Calendar

/** Heavy-user payload probe: does a plausible large library push the merged doc past the 5MB write cap? */
class WebDavSyncPayloadSizeReproTest {
    private val baseUrl = "https://dav.jianguoyun.com/dav"
    private val username = "someone@gmail.com"
    private val password = "app-password"
    private val owner = AccountDataIdentity.key(username)
    private val fileUrl = "$baseUrl/SoundWave/soundwave-account-v2-${owner.removePrefix("swown2_")}.json"

    private fun statsJson(days: Int, songsPerDay: Int): String {
        var json = "{}"
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.JANUARY, 1)
        for (d in 0 until days) {
            val dayStart = cal.timeInMillis + 12L * 3600_000
            for (s in 0 until songsPerDay) {
                json = ListenStatsDailyStore.record(
                    json,
                    Song(
                        id = "song${d}_$s", name = "一首比较长的歌曲名字 $s", singer = "歌手名字 $s",
                        source = listOf("wy", "tx", "kg", "kw", "mg")[s % 5],
                        cover = "https://p1.music.126.net/abcdefghijklmnopqrstuvwxyz0123456789/109951165000000000.jpg"
                    ),
                    "device-a", 200L, true, dayStart + s * 1000L
                )
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return json
    }

    private fun heavyLocal(stats: String, playlistSongs: Int, playlistCount: Int): LocalStore.UserBundle {
        val now = System.currentTimeMillis()
        val playlists = mutableListOf(Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()))
        for (p in 0 until playlistCount) {
            playlists += Playlist(
                id = "pl$p", name = "Imported Playlist $p", source = "wy", remoteId = "${1000 + p}",
                songs = (0 until playlistSongs).map {
                    Song(
                        id = "p${p}s$it", name = "Playlist Track Name $it", singer = "Some Singer $it",
                        source = "wy", album = "Some Album $it",
                        cover = "https://p1.music.126.net/abcdefghijklmnopqrstuvwxyz0123456789/109951165000000000.jpg",
                        duration = 240000L, syncAddedAt = now - it
                    )
                }.toMutableList(),
                count = playlistSongs, syncCreatedAt = now - 10000 - p, syncOrder = p, syncOrderAt = now - 5000
            )
        }
        return LocalStore.UserBundle(
            favs = (0 until 80).map {
                Song(id = "fav$it", name = "Fav Song $it", singer = "Singer", source = "wy", syncAddedAt = now - it)
            }.toMutableList(),
            playlists = playlists,
            history = (0 until 300).map {
                Song(id = "h$it", name = "Hist $it", singer = "S", source = "tx", playedAt = now - it * 60000L)
            }.toMutableList(),
            dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = owner
        )
    }

    private fun extrasWithScripts(count: Int, codeChars: Int): WebDavProfileExtrasSync {
        val code = "x".repeat(codeChars)
        val items = JSONArray()
        for (i in 0 until count) {
            items.put(JSONObject().put("id", "script$i").put("name", "Script $i").put("code", code).put("enabled", true))
        }
        val root = JSONObject()
            .put("scripts", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("items", items)))
            .put("smartSource", JSONObject().put("updatedAt", 100L).put("data", JSONObject()))
            .put("settings", JSONObject().put("updatedAt", 100L).put("data", JSONObject()))
            .put("dislikes", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("keys", JSONArray()).put("identities", JSONArray())))
        return WebDavProfileExtrasSync(capture = { root }, apply = {}, isCurrent = { true })
    }

    @Test
    fun heavyUserPayloadSize() {
        val stats = statsJson(days = 30, songsPerDay = 80)
        println("STATS-PROJECTION-BYTES: ${stats.toByteArray(StandardCharsets.UTF_8).size}")
        val transport = FakeDav()
        val client = WebDavSyncClient(
            transport = transport,
            accountDataKey = owner,
            accountDataCurrent = { true },
            profileName = "本机资料",
            profileExtrasSync = extrasWithScripts(count = 12, codeChars = 30000)
        )
        val result = client.sync(
            baseUrl, username, password,
            heavyLocal(stats, playlistSongs = 400, playlistCount = 6),
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        )
        println("RESULT heavy: $result")
        println("REMOTE-DOC-BYTES: ${transport.remote[fileUrl]?.toByteArray(StandardCharsets.UTF_8)?.size}")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    private class FakeDav : WebDavTransport {
        val remote = mutableMapOf<String, String>()
        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse = when (request.method) {
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
