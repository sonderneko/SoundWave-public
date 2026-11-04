package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * 歌单导入检测：导入时后台扫描原平台能否取链；
 * 失败标记 cross，播放/预解析时 forceCross。
 *
 * ACCEPT5（死链标记生命周期）：标记带时间戳与 TTL——cross 7 天、ok 30 天；
 * 旧版裸值 "ok"/"cross" 按时间戳 0 读（已过期），自动进入复检/回写纠正通道。
 * 到期 cross 不再强制跨源，原平台在下次实播获得一次机会；实播跨平台救活
 * 回写 cross、原平台成功纠正 ok（见 notePlayOutcome）。复检只走懒触发：
 * 导入、打开导入歌单详情（二者共用本扫描，新鲜标记跳过）、实播回写。
 */
object PlaylistImportChecker {
    private const val PREF = "pl_import_check_v1"
    private const val K_MAP = "check_map"

    internal const val CROSS_TTL_MS: Long = 7L * 24 * 3600 * 1000
    internal const val OK_TTL_MS: Long = 30L * 24 * 3600 * 1000

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var activeScan: Job? = null
    private val scanLock = Any()

    fun init(ctx: Context) {
        if (prefs == null) prefs = xyz.cncnlp.soundwave.ContextPreferences.open(ctx.applicationContext, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
    }

    private fun p(): SharedPreferences = prefs ?: throw IllegalStateException("PlaylistImportChecker not init")

    // ---- 状态值编解码与 TTL 判定（纯逻辑，JVM 可测） ----

    internal fun encodeStatusValue(status: String, atMs: Long): String = "$status@$atMs"

    /** 返回 (状态, 时间戳)；旧版裸值时间戳记 0（视为已过期）；空/缺失返回 null。 */
    internal fun parseStatusValue(raw: String?): Pair<String, Long>? {
        if (raw.isNullOrEmpty()) return null
        val at = raw.indexOf('@')
        if (at < 0) return raw to 0L
        val status = raw.substring(0, at)
        if (status.isEmpty()) return null
        return status to (raw.substring(at + 1).toLongOrNull() ?: 0L)
    }

    internal fun statusIsFresh(status: String, atMs: Long, nowMs: Long): Boolean {
        val age = nowMs - atMs
        if (age < 0L) return true // 时钟回拨：保守视为新鲜
        return when (status) {
            "cross" -> age < CROSS_TTL_MS
            "ok" -> age < OK_TTL_MS
            else -> false
        }
    }

    internal fun forceCrossActive(status: String, atMs: Long, nowMs: Long): Boolean =
        status == "cross" && statusIsFresh(status, atMs, nowMs)

    // ---- 存储读写 ----

    private fun loadMap(): JSONObject =
        try { JSONObject(p().getString(K_MAP, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun entryFrom(root: JSONObject, songKey: String): Pair<String, Long>? =
        parseStatusValue(try { root.optString(songKey, "") } catch (_: Exception) { "" })

    private fun readEntry(songKey: String): Pair<String, Long>? {
        if (prefs == null) return null
        return entryFrom(loadMap(), songKey)
    }

    /**
     * 后台异步：严格验证原平台能否出链；失败立即标 cross，供预取和实播优先跨平台。
     * 不能只试第一份脚本或只看 URL 文本，否则导入失效曲会被错误标成 ok。
     *
     * ACCEPT5：跳过规则改为「状态新鲜才跳」——同一扫描同时服务导入与懒复检；
     * [probeCap] 非空时按 缺失 > 过期 cross > 过期 ok 优先截顶（详情打开复检用）。
     * 已有扫描在飞时直接返回在飞 Job，不叠加第二轮。
     */
    fun checkPlaylistAsync(
        ctx: Context,
        songs: List<Song>,
        scripts: List<Script>,
        runtime: ScriptRuntime,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        probeCap: Int? = null
    ): Job {
        init(ctx)
        synchronized(scanLock) {
            activeScan?.let { if (it.isActive) return it }
            val owner = runtime.ownerContext
            val job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val probeRuntime = runtime.forkForProbe()
                try {
                    // 待检集在 IO 线程内计算：缺失/过期才探测，新鲜标记零成本跳过。
                    val now = System.currentTimeMillis()
                    val root = loadMap()
                    val stale = songs.filter { song ->
                        val e = entryFrom(root, song.key())
                        e == null || !statusIsFresh(e.first, e.second, now)
                    }
                    val work = if (probeCap != null && stale.size > probeCap) {
                        stale.sortedBy { song ->
                            val e = entryFrom(root, song.key())
                            when {
                                e == null -> 0
                                e.first == "cross" -> 1
                                else -> 2
                            }
                        }.take(probeCap)
                    } else stale
                    val total = work.size
                    var done = 0
                    // 限并发 3，避免打爆 WebView
                    val sem = kotlinx.coroutines.sync.Semaphore(3)
                    work.map { song ->
                        async {
                            sem.withPermit {
                                val status = try {
                                    val q = try {
                                        SmartSourceStore.platformPlayQuality(song.source.ifEmpty { "wy" }, "320k")
                                    } catch (_: Exception) { "320k" }
                                    // 与正常播放同一 Resolver：专用 PROBE Runtime 中依次试原平台脚本/音质，
                                    // originOnly 禁用缓存、记忆跨源、跨平台候选，结果才可作为导入可播性依据。
                                    val ticket = ResolveTicket(
                                        songKey = song.key(),
                                        purpose = ResolveTicket.Purpose.PROBE,
                                        ownerContext = owner
                                    )
                                    val result = UrlResolver.resolveResult(
                                        ticket = ticket,
                                        song = song,
                                        scripts = scripts,
                                        runtime = probeRuntime,
                                        preferQuality = q,
                                        originOnly = true,
                                        requestStillCurrent = { isActive && xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner) }
                                    )
                                    if (result.isSuccess) "ok" else "cross"
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    "cross"
                                }
                                if (!isActive || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) return@withPermit
                                setStatus(song.key(), status)
                                done++
                                withContext(Dispatchers.Main) { onProgress?.invoke(done, total) }
                            }
                        }
                    }.awaitAll()
                    withContext(Dispatchers.Main) { onDone?.invoke() }
                } finally {
                    probeRuntime.cancelSpeculativeUrlRequests()
                    withContext(NonCancellable) { probeRuntime.closeAndWait() }
                }
            }
            activeScan = job
            return job
        }
    }

    fun shouldForceCross(songKey: String): Boolean {
        val (status, atMs) = readEntry(songKey) ?: return false
        return forceCrossActive(status, atMs, System.currentTimeMillis())
    }

    fun getStatus(songKey: String): String? = readEntry(songKey)?.first

    fun setStatus(songKey: String, status: String) {
        try {
            val root = loadMap()
            root.put(songKey, encodeStatusValue(status, System.currentTimeMillis()))
            if (root.length() > 2000) {
                val keys = root.keys().asSequence().toList()
                keys.take(keys.size - 2000).forEach { root.remove(it) }
            }
            p().edit().putString(K_MAP, root.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * ACCEPT5（实播回写）：一次未强制跨源的实播解析给出的原平台生死证据。
     * - 跨平台救活（served≠origin 且非 local）：原平台已死 + 跨源存活的同轮实证 → 标 cross；
     *   全链全灭（疑似断网）走不到这里（无 served 成功），不会误伤。
     * - 原平台成功：仅纠正/刷新已有标记（过期 cross 恢复在此改判 ok），
     *   不给无标记歌曲新建 ok，避免普通播放污染标记图。
     * - wasForceCross：原平台根本没被尝试，无新证据，不写。预取不调用本函数（纪律不变）。
     */
    fun notePlayOutcome(songKey: String, originSource: String, servedPlatform: String, wasForceCross: Boolean) {
        if (prefs == null || wasForceCross) return
        val origin = originSource.ifBlank { "wy" }.lowercase()
        val served = servedPlatform.trim().lowercase()
        if (served.isEmpty()) return
        when {
            served != "local" && served != origin -> setStatus(songKey, "cross")
            served == origin -> if (readEntry(songKey) != null) setStatus(songKey, "ok")
        }
    }

    fun clearAll(ctx: Context) {
        init(ctx)
        p().edit().remove(K_MAP).apply()
    }
}
