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
import xyz.cncnlp.soundwave.sync.WebDavSyncConflictGate
import xyz.cncnlp.soundwave.sync.WebDavSyncResult
import xyz.cncnlp.soundwave.sync.WebDavTransport
import java.nio.charset.StandardCharsets

/**
 * Diagnostic repro: production-shaped manual account sync (accountDataKey + extras +
 * avatar + conflict gate) against several remote-document shapes. If any variant lands
 * in the generic catch, the patched catch prints the stack to stderr.
 */
class WebDavSyncGenericCatchReproTest {
    private val baseUrl = "https://dav.jianguoyun.com/dav"
    private val username = "someone@gmail.com"
    private val password = "app-password"
    private val owner = AccountDataIdentity.key(username)
    private val fileUrl = "$baseUrl/SoundWave/soundwave-account-v2-${owner.removePrefix("swown2_")}.json"

    private fun extrasJson(): JSONObject = JSONObject()
        .put("scripts", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("items", JSONArray())))
        .put("smartSource", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("smart_enabled", "1")))
        .put("settings", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("play_br", "flac")))
        .put("dislikes", JSONObject().put("updatedAt", 100L).put("data", JSONObject().put("keys", JSONArray()).put("identities", JSONArray())))

    private fun prodClient(transport: WebDavTransport, gate: WebDavSyncConflictGate? = null) = WebDavSyncClient(
        transport = transport,
        accountDataKey = owner,
        accountDataCurrent = { true },
        profileName = "本机资料",
        profileExtrasSync = WebDavProfileExtrasSync(
            capture = { extrasJson() },
            apply = {},
            isCurrent = { true }
        ),
        conflictGate = gate
    )

    private fun richLocal(): LocalStore.UserBundle {
        val now = System.currentTimeMillis()
        val stats = ListenStatsDailyStore.record(
            "{}", Song(id = "s1", name = "Song One", singer = "Artist", source = "wy"),
            "device-a", 120L, true, now
        )
        val favSongs = (1..40).map { Song(id = "fav$it", name = "Fav $it", singer = "Singer", source = "wy", syncAddedAt = now - it) }
        val bigPlaylistSongs = (1..300).map {
            Song(id = "p$it", name = "Track $it", singer = "Singer $it", source = "wy", cover = "https://example.com/c$it.jpg", syncAddedAt = now - it)
        }
        return LocalStore.UserBundle(
            favs = favSongs.toMutableList(),
            playlists = mutableListOf(
                Playlist(id = "liked", name = "我的喜欢", songs = mutableListOf()),
                Playlist(
                    id = "pl_imported", name = "Imported", source = "wy", remoteId = "12345",
                    songs = bigPlaylistSongs.toMutableList(), count = 300,
                    syncCreatedAt = now - 10000, syncOrder = 0, syncOrderAt = now - 5000,
                    favSyncOrder = 0, favSyncOrderAt = now - 4000, favorited = true
                ),
                Playlist(
                    id = "pl_second", name = "Second", source = "kg",
                    songs = mutableListOf(Song(id = "x1", name = "X", singer = "Y", source = "kg")),
                    syncCreatedAt = now - 9000, favSyncOrder = 1, favSyncOrderAt = now - 3000, favorited = true
                )
            ),
            history = (1..50).map {
                Song(id = "h$it", name = "Hist $it", singer = "S", source = "tx", playedAt = now - it * 60000L)
            }.toMutableList(),
            dailyListenStatsJson = stats,
            dailyListenStatsOwnerV2 = owner,
            todayStylePreferencesJson = """{"wy":{"tags":["纯音乐"],"enabled":true,"modifiedAt":123,"version":2}}"""
        )
    }

    /** A document as the current writer would produce it (round trip source). */
    private fun currentFormatRemote(): String {
        val transport = FakeDav()
        val seed = prodClient(transport)
        val r = seed.sync(baseUrl, username, password, richLocal(), verifiedGate())
        assertTrue("seed sync should succeed: $r", r is WebDavSyncResult.Success)
        return requireNotNull(transport.remote[fileUrl])
    }

    /** A document shaped like an older writer: no extras/name-enc, non-compact aliases, legacy stats root. */
    private fun legacyShapedRemote(): String {
        val now = System.currentTimeMillis()
        val user = JSONObject()
        val favs = JSONArray()
        favs.put(JSONObject().put("id", "oldfav").put("name", "Old Fav").put("singer", "A").put("source", "wy").put("src", "wy"))
        favs.put(JSONObject().put("_swDailyListenStatsV2", JSONObject().put("owner", owner).put("days", JSONObject())))
        user.put("favs", favs)
        val pls = JSONArray()
        val songs = JSONArray()
        for (i in 1..120) {
            songs.put(
                JSONObject()
                    .put("id", "os$i").put("nm", "Old Song $i").put("name", "Old Song $i")
                    .put("singer", "Old Singer").put("source", "wy").put("src", "wy")
                    .put("cover", "https://example.com/old$i.jpg").put("cv", "https://example.com/old$i.jpg")
            )
        }
        pls.put(
            JSONObject()
                .put("id", "pl_imported").put("nm", "Imported").put("name", "Imported")
                .put("cv", "").put("cover", "").put("local", false)
                .put("src", "wy").put("source", "wy").put("author", "").put("count", 300)
                .put("playCount", 3L).put("hid", false).put("hidden", false)
                .put("songs", songs)
                .put("_swCreatedAt", now - 20000).put("_swLibOrder", 0).put("_swLibOrderAt", now - 6000)
                .put("remoteId", "12345")
        )
        user.put("pls", pls)
        user.put("enabledScripts", JSONArray())
        user.put("scriptOrder", JSONArray())
        user.put("history", JSONArray())
        user.put("dailyListenStats", JSONObject().put("20260101", JSONObject()))
        return JSONObject()
            .put("schema", "soundwave-webdav-sync")
            .put("version", 2)
            .put("owner", owner)
            .put("updatedAt", now - 1000)
            .put("user", user)
            .toString()
    }

    @Test
    fun firstSyncMissingRemote() {
        val transport = FakeDav()
        val result = prodClient(transport).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT firstSync: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun syncAgainstCurrentFormatRemote() {
        val transport = FakeDav(mutableMapOf(fileUrl to currentFormatRemote()))
        val result = prodClient(transport).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT currentFormat: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun syncAgainstLegacyShapedRemote() {
        val transport = FakeDav(mutableMapOf(fileUrl to legacyShapedRemote()))
        val result = prodClient(transport).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT legacyShaped: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun syncWithConflictGateBothChangedConfirmed() {
        val transport = FakeDav(mutableMapOf(fileUrl to currentFormatRemote()))
        val gate = WebDavSyncConflictGate(
            lastSyncAt = { System.currentTimeMillis() - 5000 },
            localChangedAt = { System.currentTimeMillis() - 100 },
            confirm = { _, _ -> true }
        )
        val result = prodClient(transport, gate).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT conflictGate: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun syncWithPutConflictRetry() {
        val transport = FakeDav(mutableMapOf(fileUrl to legacyShapedRemote()), conflictOnceOnPut = true)
        val result = prodClient(transport).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT putConflictRetry: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    @Test
    fun syncWithGetMissingEtagFallback() {
        val transport = FakeDav(mutableMapOf(fileUrl to currentFormatRemote()), getEtag = null, propfindEtag = "\"pf-etag\"")
        val result = prodClient(transport).sync(baseUrl, username, password, richLocal(), verifiedGate())
        println("RESULT etagFallback: $result")
        assertTrue("$result", result is WebDavSyncResult.Success)
    }

    private fun verifiedGate() = { LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY }

    private class FakeDav(
        val remote: MutableMap<String, String> = mutableMapOf(),
        private val conflictOnceOnPut: Boolean = false,
        private val getEtag: String? = "\"etag-1\"",
        private val propfindEtag: String? = null
    ) : WebDavTransport {
        private var conflicted = false
        override fun execute(request: WebDavHttpRequest): WebDavHttpResponse {
            return when (request.method) {
                "PROPFIND" -> {
                    if (propfindEtag != null && request.url.endsWith(".json")) {
                        WebDavHttpResponse(
                            207,
                            "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:prop><d:getetag>$propfindEtag</d:getetag></d:prop></d:propstat></d:response></d:multistatus>"
                        )
                    } else WebDavHttpResponse(207)
                }
                "MKCOL" -> WebDavHttpResponse(405)
                "GET" -> remote[request.url]?.let { WebDavHttpResponse(200, it, getEtag) } ?: WebDavHttpResponse(404)
                "PUT" -> {
                    if (conflictOnceOnPut && !conflicted) {
                        conflicted = true
                        WebDavHttpResponse(412)
                    } else {
                        remote[request.url] = request.body?.toString(StandardCharsets.UTF_8).orEmpty()
                        WebDavHttpResponse(201, etag = "\"written\"")
                    }
                }
                else -> WebDavHttpResponse(405)
            }
        }
    }
}
