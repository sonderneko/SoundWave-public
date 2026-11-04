package xyz.cncnlp.soundwave

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.source.direct.platformHttpGet
import java.net.URLEncoder

/** Anonymous metadata only: never interprets rate formats or VIP flags as playable URLs. */
internal class MiguMetadataCache(
    private val fetch: suspend (String) -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class Metadata(val songId: String, val copyrightId: String, val albumId: String)
    private data class Entry(val expiresAt: Long, val metadata: Metadata?)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun prepare(song: Song): Song {
        currentCoroutineContext().ensureActive()
        if (song.source != "mg" || song.id.isBlank() || song.id.length > 128 ||
            (song.miguSongId.isNotBlank() && song.copyrightId.isNotBlank())) return song
        // The same lock coalesces concurrent consumers; no detached scope survives cancellation.
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            val key = "${song.id}|${song.copyrightId}|${song.miguSongId}"
            val cached = entries[key]?.takeIf { it.expiresAt > now() }
            val metadata = if (cached != null) cached.metadata else {
                val value = try {
                    withTimeoutOrNull(2_500L) { parse(fetch(song.id), song) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                // Short negative cache prevents one failed request per script or quality.
                entries[key] = Entry(now() + if (value == null) 60_000L else 1_800_000L, value)
                while (entries.size > 128) entries.remove(entries.keys.first())
                value
            }
            if (metadata == null) song else song.copy(
                miguSongId = song.miguSongId.ifBlank { metadata.songId },
                copyrightId = song.copyrightId.ifBlank { metadata.copyrightId },
                albumId = song.albumId.ifBlank { metadata.albumId }
            )
        }
    }

    private fun parse(raw: String, song: Song): Metadata? {
        if (raw.length > 1_048_576) return null
        val root = JSONObject(raw)
        if (root.optString("code") != "000000") return null
        val rows = when (val resource = root.opt("resource")) {
            is JSONArray -> (0 until resource.length()).mapNotNull { resource.optJSONObject(it) }
            is JSONObject -> listOf(resource)
            else -> emptyList()
        }
        fun JSONObject.id(key: String): String = when (val value = opt(key)) {
            is String -> value.takeIf { it.isNotBlank() && it != "null" && it.length <= 128 }.orEmpty()
            is Number -> value.toString()
            else -> ""
        }
        val matches = rows.filter { row ->
            (song.id == row.id("contentId") || song.id == row.id("songId")) &&
                (song.copyrightId.isBlank() || song.copyrightId == row.id("copyrightId")) &&
                (song.miguSongId.isBlank() || song.miguSongId == row.id("songId")) &&
                row.id("songId").isNotBlank()
        }
        val row = matches.singleOrNull() ?: return null
        return Metadata(row.id("songId"), row.id("copyrightId"), row.id("albumId"))
    }
}

/** All resolver script consumers use this one bounded process-local metadata cache. */
internal object MiguScriptMetadata {
    private val cache = MiguMetadataCache(fetch = { resourceId ->
        platformHttpGet(
            "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceId=${URLEncoder.encode(resourceId, "UTF-8")}&resourceType=2&channel=0&format=2",
            referer = "https://music.migu.cn/", timeoutMs = 2_000L
        )
    })
    suspend fun prepare(song: Song): Song = cache.prepare(song)
}
