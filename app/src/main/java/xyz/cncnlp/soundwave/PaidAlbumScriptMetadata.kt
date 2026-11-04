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

/** Immutable KG catalog facts only. These are not account state or playback entitlement. */
data class KgCatalogQuality(val hash: String, val bytes: Long)

data class KgAlbumMetadata(
    val platform: String,
    val albumId: String,
    val currentHash: String,
    val audioId: String,
    val albumAudioId: String = "",
    val standard: KgCatalogQuality? = null,
    val high: KgCatalogQuality? = null,
    val lossless: KgCatalogQuality? = null
) {
    internal fun validatedFor(song: Song): KgAlbumMetadata? {
        if (platform != "kg" || song.source != "kg" || albumId != song.albumId || !ID.matches(albumId)) return null
        val hash = song.hash.ifBlank { song.id }
        if (!HASH.matches(hash) || !HASH.matches(currentHash) || !hash.equals(currentHash, true)) return null
        if (!ID.matches(audioId) || (albumAudioId.isNotEmpty() && !ID.matches(albumAudioId))) return null
        val qualities = listOfNotNull(standard, high, lossless)
        if (qualities.isEmpty() || qualities.any { !HASH.matches(it.hash) || it.bytes <= 0 }) return null
        if (qualities.none { it.hash.equals(currentHash, true) }) return null
        return copy(currentHash = currentHash.uppercase(java.util.Locale.ROOT),
            standard = standard?.canonical(), high = high?.canonical(), lossless = lossless?.canonical())
    }

    internal fun toJson(): JSONObject = JSONObject().put("version", 1).put("platform", platform)
        .put("albumId", albumId).put("currentHash", currentHash).put("audioId", audioId)
        .put("albumAudioId", albumAudioId).apply {
            listOf("128k" to standard, "320k" to high, "flac" to lossless).forEach { (key, q) ->
                if (q != null) put(key, JSONObject().put("hash", q.hash).put("bytes", q.bytes))
            }
        }

    internal fun applyTo(info: JSONObject): JSONObject {
        val types = JSONArray()
        val index = JSONObject()
        listOf("128k" to standard, "320k" to high, "flac" to lossless).forEach { (type, q) ->
            if (q != null) {
                val size = String.format(java.util.Locale.US, "%.2fM", q.bytes / 1048576.0)
                val metadata = JSONObject().put("hash", q.hash).put("size", size)
                index.put(type, metadata)
                types.put(JSONObject(metadata.toString()).put("type", type))
            }
        }
        info.put("types", types).put("_types", index)
            .put("songmid", audioId).put("songId", audioId).put("audio_id", audioId)
        if (albumAudioId.isNotEmpty()) info.put("album_audio_id", albumAudioId)
        return info
    }

    companion object {
        internal const val MAX_JSON_BYTES = 4096
        private val ID = Regex("[1-9][0-9]{0,19}")
        private val HASH = Regex("[a-fA-F0-9]{32}")
        private val KEYS = setOf("version", "platform", "albumId", "currentHash", "audioId", "albumAudioId", "128k", "320k", "flac")
        private fun KgCatalogQuality.canonical() = copy(hash = hash.uppercase(java.util.Locale.ROOT))
        private fun positiveLong(value: Any?): Long? = when (value) {
            is Int -> value.toLong().takeIf { it > 0 }
            is Long -> value.takeIf { it > 0 }
            is String -> value.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
            else -> null
        }
        private fun quality(row: JSONObject, hash: String, size: String): KgCatalogQuality? {
            val h = row.optString(hash)
            val bytes = positiveLong(row.opt(size)) ?: return null
            return if (HASH.matches(h)) KgCatalogQuality(h, bytes) else null
        }
        internal fun fromCatalog(song: Song, row: JSONObject): KgAlbumMetadata? {
            if (row.optString("album_id") != song.albumId) return null
            return KgAlbumMetadata("kg", song.albumId, song.hash.ifBlank { song.id },
                row.optString("audio_id"), row.optString("album_audio_id").takeUnless { it == "null" }.orEmpty(),
                quality(row, "hash", "filesize"), quality(row, "320hash", "320filesize"),
                quality(row, "sqhash", "sqfilesize")).validatedFor(song)
        }
        internal fun fromJson(song: Song, value: Any?): KgAlbumMetadata? = try {
            val o = value as? JSONObject
            if (o == null || o.toString().toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES ||
                o.keys().asSequence().any { it !in KEYS } || o.opt("version") != 1) null
            else {
                fun q(key: String): KgCatalogQuality? {
                    if (!o.has(key)) return null
                    val item = o.getJSONObject(key)
                    require(item.keys().asSequence().all { it == "hash" || it == "bytes" })
                    return requireNotNull(quality(item, "hash", "bytes"))
                }
                KgAlbumMetadata(o.getString("platform"), o.getString("albumId"), o.getString("currentHash"),
                    o.getString("audioId"), o.optString("albumAudioId"), q("128k"), q("320k"), q("flac")).validatedFor(song)
            }
        } catch (_: Exception) { null }
    }
}

/** Catalog metadata, NOT playback entitlement. Unknown hashes never become multiple quality claims. */
internal class PaidAlbumMetadataCache(
    private val fetchPage: suspend (String, Int) -> String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class CatalogResult(val metadata: KgAlbumMetadata, val duration: Long)
    private data class Entry(val expires: Long, val result: CatalogResult?)
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun apply(song: Song, info: JSONObject): JSONObject {
        currentCoroutineContext().ensureActive()
        if (song.source != "kg" || !song.albumId.matches(Regex("[1-9][0-9]{0,19}"))) return info
        val hash = song.hash.ifBlank { song.id }
        if (!hash.matches(Regex("[a-fA-F0-9]{32}"))) return info
        song.kgAlbumMetadata?.validatedFor(song)?.let { return it.applyTo(info) }
        val key = "${song.albumId}:${hash.uppercase()}"
        val result = mutex.withLock {
            val cached = entries[key]?.takeIf { it.expires > now() }
            if (cached != null) cached.result else {
                val found = try {
                    withTimeoutOrNull(2_500L) {
                        val matches = ArrayList<JSONObject>()
                        for (page in 1..4) {
                            val raw = fetchPage(song.albumId, page)
                            if (raw.length > 1_048_576) break
                            val root = JSONObject(raw)
                            if (root.optInt("status") != 1) break
                            val rows = root.optJSONObject("data")?.optJSONArray("info") ?: break
                            for (i in 0 until rows.length()) {
                                val item = rows.optJSONObject(i) ?: continue
                                if (item.optString("album_id") != song.albumId) continue
                                if (listOf("hash", "320hash", "sqhash", "hash_flac").any { item.optString(it).equals(hash, true) }) matches.add(item)
                            }
                            // Require a unique identity in the matching page; do not pick arbitrary duplicates.
                            if (matches.isNotEmpty() || rows.length() < 30) break
                        }
                        matches.singleOrNull()?.let { item ->
                            KgAlbumMetadata.fromCatalog(song, item)?.let { metadata ->
                                CatalogResult(metadata, item.optLong("duration").coerceAtLeast(0))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                entries[key] = Entry(now() + if (found == null) 60_000L else 1_800_000L, found)
                while (entries.size > 128) entries.remove(entries.keys.first())
                found
            }
        } ?: return info
        result.metadata.validatedFor(song)?.applyTo(info) ?: return info
        if (result.duration > 0) info.put("interval", scriptInterval(result.duration))
        return info
    }
}

internal fun scriptInterval(seconds: Long): String = if (seconds <= 0) "" else
    String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

internal object PaidAlbumScriptMetadata {
    private val cache = PaidAlbumMetadataCache(fetchPage = { albumId, page ->
        platformHttpGet("http://mobilecdn.kugou.com/api/v3/album/song?albumid=$albumId&page=$page&pagesize=30", "https://m.kugou.com/", 1_500L)
    })
    suspend fun apply(song: Song, info: JSONObject): JSONObject = cache.apply(song, info)
}

