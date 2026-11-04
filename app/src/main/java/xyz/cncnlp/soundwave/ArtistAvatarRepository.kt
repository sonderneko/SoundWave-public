package xyz.cncnlp.soundwave

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

/** Artist search entities carry provider artist IDs, never song/album cover guesses.
 * Historical play records have provider + singer text, not an artist ID. Require exactly one
 * exact provider-name match; ambiguous/multi-artist/unknown identities remain a placeholder.
 */
internal object ArtistAvatarRepository {
    data class Avatar(val source: String, val artistId: String, val name: String, val url: String)
    private val mutexes = Array(16) { Mutex() }
    private fun normalized(v: String) = Normalizer.normalize(v.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun key(source: String, name: String) = "${source.lowercase(Locale.ROOT)}:${normalized(name)}"
    fun match(source: String, name: String, rows: List<Song>): Avatar? {
        if (source !in MusicSourceRegistry.platformIds || name.isBlank() || Regex("[/、;&＆]").containsMatchIn(name)) return null
        val exact = rows.filter { it.source == source && normalized(it.name) == normalized(name) && it.id.isNotBlank() }.distinctBy { it.id }
        val artist = exact.singleOrNull() ?: return null
        val uri = runCatching { java.net.URI(artist.cover) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return Avatar(source, artist.id, artist.name, artist.cover)
    }
    private fun validAvatarUrl(cover: String): Boolean {
        val uri = runCatching { java.net.URI(cover) }.getOrNull() ?: return false
        return uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    private fun freshCached(prefs: android.content.SharedPreferences, source: String, name: String, now: Long): JSONObject? {
        val cached = runCatching { JSONObject(prefs.getString(key(source, name), "").orEmpty()) }.getOrNull() ?: return null
        return if (now - cached.optLong("time") in 0 until cached.optLong("ttl")) cached else null
    }

    private fun cachedRow(source: String, cached: JSONObject) =
        Song(singer = "", id = cached.optString("id"), name = cached.optString("name"), source = source, cover = cached.optString("url"))

    private fun writeCached(prefs: android.content.SharedPreferences, source: String, name: String, result: Avatar?, now: Long) {
        val key = key(source, name)
        val value = JSONObject().put("time", now).put("ttl", if (result == null) 10 * 60_000L else 7 * 86_400_000L)
            .put("id", result?.artistId.orEmpty()).put("name", name).put("url", result?.url.orEmpty())
        val editor = prefs.edit().putString(key, value.toString())
        if (prefs.all.size >= 100) prefs.all.keys.filter { it != key }.take(prefs.all.size - 99).forEach(editor::remove)
        editor.apply()
    }

    suspend fun load(context: Context, source: String, name: String, now: Long = System.currentTimeMillis(),
        search: suspend (String, String) -> List<Song> = { src, term -> MusicSourceRegistry.platformSource(src).searchTypedResult(term, "artist").songs }
    ): Avatar? = withContext(Dispatchers.IO) { mutexes[(key(source, name).hashCode() and Int.MAX_VALUE) % mutexes.size].withLock {
        if (source !in MusicSourceRegistry.platformIds || name.isBlank()) return@withLock null
        val prefs = context.applicationContext.getSharedPreferences("artist-avatar-cache-v1", Context.MODE_PRIVATE)
        freshCached(prefs, source, name, now)?.let {
            return@withLock match(source, name, listOf(cachedRow(source, it)))
        }
        val result = try { kotlinx.coroutines.withTimeoutOrNull(5_000L) { match(source, name, search(source, name)) } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        writeCached(prefs, source, name, result, now)
        result
    } }

    /**
     * ACCEPT6-PODIUMAVATAR：领奖台专用解析口径。[match] 的「恰一精确同名」是 Round3
     * 契约（宁缺勿错），保留不动；但平台目录里同名歌手条目本就常见（如 wy「昼夜」有
     * 真身 1144 首与同名小号 4 首两条精确同名），恰一门会把真歌手永久判死。本口径
     * 同门（平台合法、非多人歌手名、id 非空、头像 http(s) 逐行先验、坏封面行先剔除
     * 再计数），消歧序：① 本地证据（用户自己曲库中该歌手歌曲的 singerId）命中行；
     * ② 恰一精确同名；③ 多条精确同名取平台相关性序第一条（平台自身排序里真身恒
     * 居前）。无精确同名 → null（占位仍是设计内回退）。
     */
    fun matchResolved(source: String, name: String, rows: List<Song>, evidenceIds: Set<String> = emptySet()): Avatar? {
        if (source !in MusicSourceRegistry.platformIds || name.isBlank() || Regex("[/、;&＆]").containsMatchIn(name)) return null
        val exact = rows.filter { it.source == source && normalized(it.name) == normalized(name) && it.id.isNotBlank() && validAvatarUrl(it.cover) }
            .distinctBy { it.id }
        if (exact.isEmpty()) return null
        val artist = exact.firstOrNull { it.id in evidenceIds } ?: exact.singleOrNull() ?: exact.first()
        return Avatar(source, artist.id, artist.name, artist.cover)
    }

    /**
     * 用户本地歌曲池里该歌手的歌手 ID 证据：（平台 → singerId 集）。统计只存名字，
     * 这是同名消歧时唯一来自用户本人数据的身份信号（各平台歌曲解析都带 singerId）。
     */
    fun evidenceIdsBySource(songs: List<Song>, name: String): Map<String, Set<String>> {
        if (name.isBlank()) return emptyMap()
        val target = normalized(name)
        val out = linkedMapOf<String, MutableSet<String>>()
        songs.forEach { song ->
            if (song.source !in MusicSourceRegistry.platformIds || song.singerId.isBlank()) return@forEach
            if (normalized(song.singer) != target) return@forEach
            out.getOrPut(song.source) { linkedSetOf() }.add(song.singerId)
        }
        return out
    }

    /**
     * ACCEPT6-PODIUMAVATAR：[load] 在主导平台未命中之后的按需跨平台层（领奖台专用，
     * 最多 3 人触发）。按调用方给的平台顺序（用户跨源顺序 [platformOrder]）逐台走
     * 「缓存 → typed 歌手搜索 → [matchResolved]」，命中即以（命中平台，name）回填同一
     * 缓存供 [load] 复用；负结果同样落负缓存（10 分钟），重复打开不重搜。跳过
     * [dominantSource]（load 已查过、其负缓存也在期内）。每台 5s 超时、IO 线程、
     * 与 load 共用条纹锁。
     */
    suspend fun loadOnDemand(context: Context, dominantSource: String, name: String,
        evidence: Map<String, Set<String>> = emptyMap(),
        platformOrder: List<String>,
        now: Long = System.currentTimeMillis(),
        search: suspend (String, String) -> List<Song> = { src, term -> MusicSourceRegistry.platformSource(src).searchTypedResult(term, "artist").songs }
    ): Avatar? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        val prefs = context.applicationContext.getSharedPreferences("artist-avatar-cache-v1", Context.MODE_PRIVATE)
        val tried = mutableSetOf<String>()
        for (platform in platformOrder) {
            val source = platform.lowercase(Locale.ROOT)
            if (source !in MusicSourceRegistry.platformIds || source == dominantSource.lowercase(Locale.ROOT) || !tried.add(source)) continue
            val hit = mutexes[(key(source, name).hashCode() and Int.MAX_VALUE) % mutexes.size].withLock {
                freshCached(prefs, source, name, now)?.let { cached ->
                    return@withLock matchResolved(source, name, listOf(cachedRow(source, cached)), evidence[source].orEmpty())
                }
                val result = try { kotlinx.coroutines.withTimeoutOrNull(5_000L) { matchResolved(source, name, search(source, name), evidence[source].orEmpty()) } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                writeCached(prefs, source, name, result, now)
                result
            }
            if (hit != null) return@withContext hit
        }
        null
    }
}
