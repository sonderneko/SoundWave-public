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

/**
 * Hostile remote-document fuzz: mutate one field at a time of a valid current-format
 * document and assert the sync outcome is never the opaque generic banner.
 */
class WebDavSyncRemoteFuzzTest {
    private val baseUrl = "https://dav.jianguoyun.com/dav"
    private val username = "someone@gmail.com"
    private val password = "app-password"
    private val owner = AccountDataIdentity.key(username)
    private val fileUrl = "$baseUrl/SoundWave/soundwave-account-v2-${owner.removePrefix("swown2_")}.json"
    private val genericBanner = "WebDAV 同步流程异常；远端结果未确认。请先检查远端同步文件，再决定是否重试。"

    private fun extrasJson(): JSONObject = JSONObject()
        .put("scripts", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("items", JSONArray())))
        .put("smartSource", JSONObject().put("updatedAt", 100L).put("data", JSONObject()))
        .put("settings", JSONObject().put("updatedAt", 100L).put("data", JSONObject()))
        .put("dislikes", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("keys", JSONArray()).put("identities", JSONArray())))

    private fun client(transport: WebDavTransport) = WebDavSyncClient(
        transport = transport,
        accountDataKey = owner,
        accountDataCurrent = { true },
        profileName = "本机资料",
        profileExtrasSync = WebDavProfileExtrasSync(capture = { extrasJson() }, apply = {}, isCurrent = { true })
    )

    private fun localBundle(): LocalStore.UserBundle {
        val now = System.currentTimeMillis()
        val stats = ListenStatsDailyStore.record(
            "{}", Song(id = "s1", name = "Song One", singer = "Artist", source = "wy"), "device-a", 120L, true, now
        )
        return LocalStore.UserBundle(
            favs = mutableListOf(Song(id = "fav1", name = "Fav", singer = "S", source = "wy", syncAddedAt = now)),
            playlists = mutableListOf(
                Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()),
                Playlist(
                    id = "pl1", name = "P1", source = "wy", remoteId = "42",
                    songs = mutableListOf(Song(id = "a", name = "A", singer = "B", source = "wy")),
                    count = 1, syncCreatedAt = now - 1000, favSyncOrder = 0, favSyncOrderAt = now - 500, favorited = true
                )
            ),
            history = mutableListOf(Song(id = "h1", name = "H", singer = "S", source = "tx", playedAt = now)),
            dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = owner
        )
    }

    private fun seedDoc(): JSONObject {
        val transport = FakeDav()
        val result = client(transport).sync(
            baseUrl, username, password, localBundle(),
            gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
        )
        assertTrue("seed: $result", result is WebDavSyncResult.Success)
        return JSONObject(requireNotNull(transport.remote[fileUrl]))
    }

    private fun mutations(doc: JSONObject): List<Pair<String, JSONObject>> {
        val specs = mutableListOf<Pair<String, (JSONObject) -> Unit>>()
        fun spec(name: String, block: (JSONObject) -> Unit) { specs += name to block }
        fun user(d: JSONObject) = d.getJSONObject("user")
        fun firstPlaylistWithSongs(d: JSONObject): JSONObject {
            val pls = user(d).getJSONArray("pls")
            for (i in 0 until pls.length()) {
                val p = pls.optJSONObject(i)
                if (p != null && p.optJSONArray("songs") != null) return p
            }
            throw IllegalStateException("no playlist with songs")
        }
        fun firstSongFav(d: JSONObject): JSONObject {
            val favs = user(d).getJSONArray("favs")
            for (i in 0 until favs.length()) {
                val f = favs.optJSONObject(i)
                if (f != null && f.has("id")) return f
            }
            throw IllegalStateException("no song fav")
        }
        spec("user-as-string") { it.put("user", "junk") }
        spec("user-as-array") { it.put("user", JSONArray()) }
        spec("updatedAt-as-string") { it.put("updatedAt", "yesterday") }
        spec("profileNameEnc-garbage") { it.put("profileNameEnc", "!!!not-base64!!!") }
        spec("profileNameEnc-json-num") { it.put("profileNameEnc", 42) }
        spec("avatars-value-number") { it.put("accountAvatars", JSONObject().put("swav1_" + "a".repeat(64), 123)) }
        spec("avatars-huge-string") { it.put("accountAvatars", JSONObject().put("swav1_" + "b".repeat(64), "A".repeat(600_000))) }
        spec("extras-data-as-array") { it.getJSONObject("profileExtras").getJSONObject("settings").put("data", JSONArray().put(1)) }
        spec("extras-section-as-string") { it.getJSONObject("profileExtras").put("settings", "junk") }
        spec("extras-updatedAt-string") { it.getJSONObject("profileExtras").getJSONObject("settings").put("updatedAt", "soon") }
        spec("extras-as-array") { it.put("profileExtras", JSONArray()) }
        spec("favs-entry-string") { user(it).getJSONArray("favs").put(0, "junk") }
        spec("favs-as-object") { user(it).put("favs", JSONObject()) }
        spec("fav-song-fields-wrong-types") {
            val fav = firstSongFav(it)
            fav.put("duration", "long").put("syncAddedAt", "never").put("playedAt", "x").put("name", 42)
        }
        spec("pls-entry-string") { user(it).getJSONArray("pls").put(0, "junk") }
        spec("pls-songs-as-string") { firstPlaylistWithSongs(it).put("songs", "junk") }
        spec("pls-favOrder-as-object") { firstPlaylistWithSongs(it).put("_swFavOrder", JSONObject()) }
        spec("pls-count-as-string") { firstPlaylistWithSongs(it).put("count", "many") }
        spec("history-entry-number") { user(it).getJSONArray("history").put(0, 7) }
        spec("stats-days-value-string") {
            val days = it.getJSONObject("dailyListenStatsV2").getJSONObject("days")
            days.put(days.keys().next(), "junk")
        }
        spec("stats-root-as-array") { it.put("dailyListenStatsV2", JSONArray()) }
        spec("stats-legacy-as-string") { user(it).put("dailyListenStats", "junk") }
        spec("counters-value-string") { user(it).put("songPlayCountCounters", JSONObject().put("wy:x", "lots")) }
        spec("deletedFavs-entry-string") { user(it).put("deletedFavorites", JSONArray().put("junk")) }
        spec("deletedPlaylists-entry-number") { user(it).put("deletedPlaylists", JSONArray().put(3)) }
        spec("tombstones-as-array") { user(it).put("historyDeletionTombstones", JSONArray()) }
        spec("todayStyle-as-number") { user(it).put("todayStylePreferences", 9) }
        spec("docid-as-number") { it.put("documentId", 5) }
        val out = mutableListOf<Pair<String, JSONObject>>()
        for ((name, block) in specs) {
            val copy = JSONObject(doc.toString())
            try {
                block(copy)
                out += name to copy
            } catch (_: Exception) {
                println("mutation $name SKIPPED (anchor absent)")
            }
        }
        return out
    }

    @Test
    fun hostileRemoteDocsNeverHitGenericBanner() {
        val seed = seedDoc()
        println("SEED-KEYS: ${seed.keys().asSequence().toList()}")
        val hits = mutableListOf<String>()
        for ((name, doc) in mutations(seed)) {
            val transport = FakeDav(mutableMapOf(fileUrl to doc.toString()))
            val result = client(transport).sync(
                baseUrl, username, password, localBundle(),
                gateStateProvider = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }
            )
            if (result is WebDavSyncResult.Failure && result.message.startsWith(genericBanner)) {
                hits += name
                println("GENERIC-BANNER-HIT: $name")
            } else {
                println("mutation $name -> ${result.javaClass.simpleName}")
            }
        }
        assertTrue("mutations landing in generic catch: $hits", hits.isEmpty())
    }

    private class FakeDav(val remote: MutableMap<String, String> = mutableMapOf()) : WebDavTransport {
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
