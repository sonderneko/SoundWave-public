package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 1.0.655：本地歌单扫描结果的本机持久化。
 * 本地歌单曲目指向设备文件，不进入账号快照 / 云同步；以前重启后内存为空，音乐库又提示「扫描设备中的音乐」。
 * 扫描完成即写入本机文件，音乐库与本地歌单详情在内存为空时回落读取，已扫描过就不再要求重新扫描。
 */
internal object LocalLibraryStore {
    private const val FILE = "local_library_songs_v1.json"
    private const val EXCLUDED_FILE = "local_library_excluded_v1.json"
    @Volatile private var cache: List<Song>? = null
    @Volatile private var excludedCache: Set<String>? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)
    private fun excludedFile(context: Context) = File(context.applicationContext.filesDir, EXCLUDED_FILE)

    fun songs(context: Context): List<Song> {
        cache?.let { return it }
        val loaded = runCatching {
            val f = file(context)
            if (!f.exists()) emptyList() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { Song.fromJson(it, "local") }.getOrNull() } }
            }
        }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    fun hasScanned(context: Context): Boolean = file(context).exists()

    fun save(context: Context, songs: List<Song>) {
        cache = songs.toList()
        runCatching {
            val arr = JSONArray()
            songs.forEach { arr.put(it.toJson(includeLocalUri = true)) }
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        }
    }

    /**
     * ACCEPT4（审计 §11）：从本地歌单移除过的曲目键（持久排除记录）。
     * 本地歌单的真源是本 Store + 扫描——以前删除只写 bundle 镜像，详情回落读 Store / 重扫都会让曲目复活。
     * 移除 = 写真源 + 落排除记录；扫描落盘前按排除记录过滤（重扫不复活，文件本体不动）。
     */
    fun excludedKeys(context: Context): Set<String> {
        excludedCache?.let { return it }
        val loaded = runCatching {
            val f = excludedFile(context)
            if (!f.exists()) emptySet() else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
            }
        }.getOrDefault(emptySet())
        excludedCache = loaded
        return loaded
    }

    private fun saveExcluded(context: Context, keys: Set<String>) {
        excludedCache = keys
        runCatching {
            val arr = JSONArray()
            keys.forEach { arr.put(it) }
            val f = excludedFile(context)
            val tmp = File(f.parentFile, "$EXCLUDED_FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        }
    }

    /** 从本地库移除曲目（不删文件）：写回真源并落排除记录；返回移除后的曲目表。 */
    fun removeSongs(context: Context, keys: Set<String>): List<Song> {
        if (keys.isEmpty()) return songs(context)
        val next = songs(context).filterNot { it.key() in keys }
        save(context, next)
        saveExcluded(context, excludedKeys(context) + keys)
        return next
    }

    /** 撤销移除：解除排除并把曲目并回真源（已存在的跳过，缺失项按快照顺序追加）；返回并回后的曲目表。 */
    fun restoreSongs(context: Context, restored: List<Song>): List<Song> {
        if (restored.isEmpty()) return songs(context)
        saveExcluded(context, excludedKeys(context) - restored.map { it.key() }.toSet())
        val current = songs(context)
        val have = current.mapTo(HashSet()) { it.key() }
        val next = current + restored.filterNot { it.key() in have }
        save(context, next)
        return next
    }

    /** 扫描结果落盘前过滤已排除项（重扫不复活口径）。 */
    fun filterExcluded(context: Context, scanned: List<Song>): List<Song> {
        val excluded = excludedKeys(context)
        if (excluded.isEmpty()) return scanned
        return scanned.filterNot { it.key() in excluded }
    }
}
