package xyz.cncnlp.soundwave

import android.content.Context
import android.os.StatFs
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import xyz.cncnlp.soundwave.cache.AtomicCacheFile
import xyz.cncnlp.soundwave.cache.Media3CacheReadLeaseRegistry
import xyz.cncnlp.soundwave.cache.clearMedia3CacheResources
import xyz.cncnlp.soundwave.cache.trimMedia3Cache
import xyz.cncnlp.soundwave.prefetch.BoundedPrefetchWorkPool
import xyz.cncnlp.soundwave.prefetch.SharedPrefetchWorkPool
import xyz.cncnlp.soundwave.diagnostics.ConcurrentWorkDiagnostics

internal const val MAX_COVER_DATA_URL_CHARS = 8 * 1024 * 1024
internal fun isCoverDataUrlSizeAllowed(length: Int): Boolean = length in 0..MAX_COVER_DATA_URL_CHARS

data class CacheClearResult(
    val skippedActiveFiles: Int,
    val skippedActiveMediaResources: Int = 0
)

internal data class MediaCacheTrimResult(
    val targetBytes: Long,
    val bytesRemaining: Long,
    val skippedActiveResources: Int,
    val failedResources: Int
) {
    val complete: Boolean get() = bytesRemaining <= targetBytes
}

/**
 * 本地缓存：封面 / 歌词 / 音频 / 临时。
 * 封面按 URL 指纹落盘；URL 不变不重复下载。
 */
object CacheManager {
    private const val PREF = "soundwave"
    private const val KEY_MAX = "cache_max_bytes"
    internal const val LYRIC_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    /** 默认 500MB */
    const val DEFAULT_MAX: Long = 500L * 1024 * 1024

    /** 剩余空间低于此值时，不再新增封面/歌词缓存，先保护正在播放与用户下载内容。 */
    private const val LOW_STORAGE_BYTES = 192L * 1024 * 1024
    private const val CRITICAL_STORAGE_BYTES = 96L * 1024 * 1024
    @Volatile private var skippedWritesLowStorage: Long = 0L
    @Volatile private var trimRuns: Long = 0L
    /** 缓存命中率索引：URL 缓存命中/未中、音频（Exo/落盘）命中/未中、淘汰原因计数。 */
    @Volatile private var urlCacheHits = 0L
    @Volatile private var urlCacheMisses = 0L
    @Volatile private var audioCacheHits = 0L
    @Volatile private var audioCacheMisses = 0L
    @Volatile private var evictLru = 0L
    @Volatile private var evictSpace = 0L
    @Volatile private var skippedActiveMediaResources = 0L
    @Volatile private var incompleteMediaCacheTrims = 0L
    private val coverWriteLocks = BlockingKeyedOperationLock<String>()
    private val coverTrimLock = Any()
    private val lyricCacheMutationLock = Any()
    @Volatile internal var coverPrefetchActionForTest: ((Context, String) -> Unit)? = null
    @Volatile internal var coverDownloadActionForTest: ((String, File, Int, Int) -> Boolean)? = null
    @Volatile internal var optionalCacheWriteAllowedForTest: Boolean? = null
    /** ACCEPT5-covergate：ensureCover 失败负缓存（短 TTL），策略与依据见 CoverGatePolicy。 */
    internal val coverFailureCache = CoverFailureCache()

    /** 音频缓存元数据索引：song.key -> (quality, source, 时间)，供"最近缓存了哪些歌"诊断。 */
    private const val KEY_AUDIO_INDEX = "audio_cache_index"
    /** J-缓存：封面磁盘 TTL（7 天），见 scheduleCoverRefresh / ensureCover 的过期语义。 */
    private const val COVER_DISK_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private val audioIndexLock = Any()

    fun recordAudioIndex(ctx: Context, song: Song) {
        if (song.key().isBlank() || song.actualQuality.isBlank()) return
        try {
            synchronized(audioIndexLock) {
                val prefs = ctx.getSharedPreferences(PREF, 0)
                val root = try { org.json.JSONObject(prefs.getString(KEY_AUDIO_INDEX, "{}") ?: "{}") } catch (_: Exception) { org.json.JSONObject() }
                root.put(
                    song.key(),
                    org.json.JSONObject()
                        .put("q", song.actualQuality)
                        .put("src", song.source.ifBlank { "wy" })
                        .put("ts", System.currentTimeMillis())
                )
                // 限 200 条，按时间淘汰最旧
                if (root.length() > 200) {
                    val entries = mutableListOf<Pair<String, Long>>()
                    root.keys().forEach { k -> entries.add(k to (root.optJSONObject(k)?.optLong("ts", 0L) ?: 0L)) }
                    entries.sortedBy { it.second }.take(entries.size - 200).forEach { root.remove(it.first) }
                }
                prefs.edit().putString(KEY_AUDIO_INDEX, root.toString()).apply()
            }
        } catch (_: Exception) {}
    }

    /** 最近缓存歌曲索引摘要：按 song.key 去重显示 音质×平台。 */
    fun audioIndexSummary(ctx: Context, limit: Int = 12): String {
        return try {
            val prefs = ctx.getSharedPreferences(PREF, 0)
            val root = org.json.JSONObject(prefs.getString(KEY_AUDIO_INDEX, "{}") ?: "{}")
            val entries = mutableListOf<Triple<String, String, Long>>()
            root.keys().forEach { k ->
                val o = root.optJSONObject(k) ?: return@forEach
                entries.add(Triple(k, "${o.optString("q")}·${o.optString("src")}", o.optLong("ts", 0L)))
            }
            entries.sortedByDescending { it.third }.take(limit).joinToString("\n") { (k, meta, ts) ->
                val src = Platforms.label(meta.substringAfter('·'))
                "${meta.substringBefore('·')} / $src"
            }.ifBlank { "暂无缓存歌曲记录" }
        } catch (_: Exception) { "暂无缓存歌曲记录" }
    }

    fun recordUrlCacheHit(identity: String? = null) {
        urlCacheHits++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CACHE, ConcurrentWorkDiagnostics.Outcome.HIT, identity = identity, cacheHit = true)
    }
    fun recordUrlCacheMiss(identity: String? = null) {
        urlCacheMisses++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CACHE, ConcurrentWorkDiagnostics.Outcome.MISS, identity = identity, cacheHit = false)
    }
    fun recordAudioCacheHit(identity: String? = null) {
        audioCacheHits++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CACHE, ConcurrentWorkDiagnostics.Outcome.HIT, identity = identity, cacheHit = true)
    }
    fun recordAudioCacheMiss(identity: String? = null) {
        audioCacheMisses++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CACHE, ConcurrentWorkDiagnostics.Outcome.MISS, identity = identity, cacheHit = false)
    }
    fun recordEvictLru() {
        evictLru++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CLEANUP, ConcurrentWorkDiagnostics.Outcome.SUCCEEDED)
    }
    fun recordEvictSpace() {
        evictSpace++
        ConcurrentWorkDiagnostics.record(ConcurrentWorkDiagnostics.WorkKind.CACHE, ConcurrentWorkDiagnostics.Phase.CLEANUP, ConcurrentWorkDiagnostics.Outcome.SUCCEEDED)
    }
    fun recordSkippedActiveMediaResources(count: Int) {
        if (count > 0) skippedActiveMediaResources += count
    }
    internal fun recordMediaCacheTrim(result: MediaCacheTrimResult) {
        recordSkippedActiveMediaResources(result.skippedActiveResources)
        if (!result.complete) incompleteMediaCacheTrims++
        val pool = SharedPrefetchWorkPool.snapshot()
        ConcurrentWorkDiagnostics.record(
            ConcurrentWorkDiagnostics.WorkKind.CACHE,
            ConcurrentWorkDiagnostics.Phase.CLEANUP,
            if (result.complete) ConcurrentWorkDiagnostics.Outcome.SUCCEEDED else ConcurrentWorkDiagnostics.Outcome.FAILED,
            failureKind = if (result.complete) null else ConcurrentWorkDiagnostics.FailureKind.STORAGE,
            queuedCount = pool.queued,
            activeCount = pool.running
        )
    }

    /** 按 song.key+音质+来源 维度聚合的命中统计；全部为进程内计数，不落 URL。 */
    fun cacheStats(ctx: Context): String {
        val urlTotal = urlCacheHits + urlCacheMisses
        val urlRate = if (urlTotal > 0) "%.0f%%".format(urlCacheHits * 100.0 / urlTotal) else "—"
        val audioTotal = audioCacheHits + audioCacheMisses
        val audioRate = if (audioTotal > 0) "%.0f%%".format(audioCacheHits * 100.0 / audioTotal) else "—"
        return "取链缓存 命中$urlCacheHits/未中$urlCacheMisses（$urlRate） · 音频缓存 命中$audioCacheHits/未中$audioCacheMisses（$audioRate） · 淘汰 LRU $evictLru 次/空间 $evictSpace 次 · 活动 Media3 资源暂缓淘汰 $skippedActiveMediaResources 项/裁剪未达预算 $incompleteMediaCacheTrims 次"
    }

    fun coversDir(ctx: Context) = File(ctx.filesDir, "covers").also { it.mkdirs() }
    fun lyricsDir(ctx: Context) = File(ctx.filesDir, "lyrics").also { it.mkdirs() }
    fun audioDir(ctx: Context) = File(ctx.filesDir, "audio_cache").also { it.mkdirs() }
    /** Media3 实际边播边缓存目录。 */
    fun exoAudioDir(ctx: Context) = File(ctx.cacheDir, "exo_media_cache")
    fun avatarDir(ctx: Context) = File(ctx.filesDir, "avatars").also { it.mkdirs() }

    fun maxBytes(ctx: Context): Long {
        // 旧版本/手改备份可能把同名键写成 String；首帧读取不得因类型不匹配直接杀死 Activity。
        val v = try {
            ctx.getSharedPreferences(PREF, 0).getLong(KEY_MAX, DEFAULT_MAX)
        } catch (_: ClassCastException) {
            try { ctx.getSharedPreferences(PREF, 0).edit().remove(KEY_MAX).apply() } catch (_: Exception) {}
            DEFAULT_MAX
        } catch (_: Exception) {
            DEFAULT_MAX
        }
        return if (v <= 0) Long.MAX_VALUE / 4 else v
    }

    fun setMaxBytes(ctx: Context, bytes: Long) {
        ctx.getSharedPreferences(PREF, 0).edit().putLong(KEY_MAX, bytes).apply()
        // Exo 的边播边缓存使用独立 SimpleCache；同步重建它才会真正采用新 LRU 上限。
        PlayerService.reconfigureCache(ctx.applicationContext, bytes)
        trimIfNeeded(ctx)
    }

    private fun dirSize(d: File): Long {
        if (!d.exists()) return 0L
        var sum = 0L
        d.walkTopDown().filter { it.isFile }.forEach { sum += it.length() }
        return sum
    }

    fun usageBytes(ctx: Context): Long {
        // 统一口径：exo_media_cache 只计入 audio 一次，不能再把整个 cacheDir 重复相加。
        return usageParts(ctx).values.sum()
    }

    fun usageParts(ctx: Context): Map<String, Long> {
        val cover = dirSize(coversDir(ctx)) + dirSize(avatarDir(ctx))
        val lyric = dirSize(lyricsDir(ctx))
        val audio = dirSize(audioDir(ctx)) + dirSize(exoAudioDir(ctx))
        // cacheDir 里剔除已单列的 Exo 音频缓存，避免“歌曲”和“其他”重复统计。
        val other = (dirSize(ctx.cacheDir) - dirSize(exoAudioDir(ctx))).coerceAtLeast(0L)
        return mapOf("cover" to cover, "lyric" to lyric, "audio" to audio, "other" to other)
    }

    fun usageBreakdown(ctx: Context): String {
        val p = usageParts(ctx)
        return "封面 ${format(p["cover"] ?: 0)} · 歌曲 ${format(p["audio"] ?: 0)} · 歌词 ${format(p["lyric"] ?: 0)} · 其他 ${format(p["other"] ?: 0)}"
    }

    fun availableBytes(ctx: Context): Long = try {
        StatFs(ctx.filesDir.absolutePath).availableBytes.coerceAtLeast(0L)
    } catch (_: Exception) { Long.MAX_VALUE }

    fun storageState(ctx: Context): String = when (availableBytes(ctx)) {
        in 0 until CRITICAL_STORAGE_BYTES -> "空间紧张"
        in CRITICAL_STORAGE_BYTES until LOW_STORAGE_BYTES -> "空间偏低"
        else -> "正常"
    }

    /** 设置页诊断用：持久分类占用、剩余空间与本进程缓存降级计数。 */
    fun diagnostics(ctx: Context): String {
        // SW-02-11：诊断读取本身也记一条有界事件（池深度 + 磁盘水位），便于复现竞争时归因。
        val pool = SharedPrefetchWorkPool.snapshot()
        ConcurrentWorkDiagnostics.record(
            ConcurrentWorkDiagnostics.WorkKind.CACHE,
            ConcurrentWorkDiagnostics.Phase.DISK,
            ConcurrentWorkDiagnostics.Outcome.SUCCEEDED,
            queuedCount = pool.queued,
            activeCount = pool.running,
            diskUsedBytes = usageParts(ctx).values.sum(),
            diskAvailableBytes = availableBytes(ctx)
        )
        return "${usageBreakdown(ctx)} · 可用 ${format(availableBytes(ctx))} · ${storageState(ctx)} · 清理 $trimRuns 次 · 降级 $skippedWritesLowStorage 次 · ${cacheStats(ctx)}"
    }

    private fun canWriteOptionalCache(ctx: Context): Boolean {
        optionalCacheWriteAllowedForTest?.let { return it }
        if (availableBytes(ctx) >= LOW_STORAGE_BYTES) return true
        skippedWritesLowStorage++
        ConcurrentWorkDiagnostics.record(
            ConcurrentWorkDiagnostics.WorkKind.CACHE,
            ConcurrentWorkDiagnostics.Phase.CACHE,
            ConcurrentWorkDiagnostics.Outcome.FAILED,
            failureKind = ConcurrentWorkDiagnostics.FailureKind.STORAGE
        )
        return false
    }

    fun format(bytes: Long): String {
        if (bytes >= 1024L * 1024 * 1024) return "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        if (bytes >= 1024L * 1024) return "%.1f MB".format(bytes / 1024.0 / 1024.0)
        if (bytes >= 1024) return "%.0f KB".format(bytes / 1024.0)
        return "$bytes B"
    }

    fun formatLimit(bytes: Long): String {
        if (bytes >= Long.MAX_VALUE / 8) return "不限"
        return format(bytes)
    }

    /** 清空缓存：临时、歌词、下载缓存、解密媒体缓存（media_decrypt 明文）及 Media3 边播缓存；封面/头像单独保留。 */
    fun clearAll(ctx: Context): CacheClearResult {
        // SimpleCache 可能正被播放器持有；通过缓存 API 清除资源，禁止直接删除其锁定目录。
        val skippedMediaResources = PlayerService.clearMediaCache(ctx.applicationContext)
        requireDirectory(ctx.cacheDir)
        val skipped = linkedSetOf<String>()
        val cacheChildren = ctx.cacheDir.listFiles()
            ?: error("Unable to list the cache directory")
        cacheChildren.filter { it.name != exoAudioDir(ctx).name }.forEach { deleteCachePath(it, skipped) }
        synchronized(lyricCacheMutationLock) {
            recreateCacheDirectory(File(ctx.filesDir, "lyrics"), skipped)
        }
        recreateCacheDirectory(File(ctx.filesDir, "audio_cache"), skipped)
        // Include active staging reservations even if their temporary files have not been created yet.
        skipped += MediaCacheLeaseManager.leasePathsUnder(ctx.cacheDir)
        skipped += MediaCacheLeaseManager.leasePathsUnder(File(ctx.filesDir, "audio_cache"))
        return CacheClearResult(skipped.size, skippedMediaResources)
    }

    private fun deleteCachePath(path: File, skipped: MutableSet<String>) {
        val result = MediaCacheLeaseManager.deleteRecursively(path)
        skipped += result.skippedLeasePaths
        check(result.failedPaths.isEmpty()) { "Unable to clear cache path: ${result.failedPaths.first().name}" }
    }

    private fun requireDirectory(directory: File) {
        if (!directory.exists()) check(directory.mkdirs() || directory.isDirectory) {
            "Unable to create cache directory: ${directory.name}"
        }
        check(directory.isDirectory) { "Cache path is not a directory: ${directory.name}" }
    }

    private fun recreateCacheDirectory(directory: File, skipped: MutableSet<String>) {
        deleteCachePath(directory, skipped)
        requireDirectory(directory)
    }

    fun saveLyric(ctx: Context, key: String, text: String) {
        if (key.isEmpty() || text.isEmpty() || !canWriteOptionalCache(ctx)) return
        // SW-02-09：歌词缓存写入走 AtomicCacheFile（同目录 staging + fsync + 原子改名），
        // 与 loadLyric/清空共用 lyricCacheMutationLock——读者永不见半截文件，
        // 清空与写入不再互相踩踏。
        synchronized(lyricCacheMutationLock) {
            val target = File(lyricsDir(ctx), "${lyricCacheFileName(key)}.lrc")
            try {
                AtomicCacheFile.replace(target) { staging ->
                    java.io.FileOutputStream(staging).use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun loadLyric(ctx: Context, key: String): String {
        if (key.isEmpty()) return ""
        return synchronized(lyricCacheMutationLock) {
            try {
                val f = File(lyricsDir(ctx), "${lyricCacheFileName(key)}.lrc")
                if (!f.isFile) return@synchronized ""
                val modified = f.lastModified()
                if (modified <= 0L || System.currentTimeMillis() - modified > LYRIC_CACHE_TTL_MS) {
                    try { f.delete() } catch (_: Exception) {}
                    return@synchronized ""
                }
                f.readText(Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun sha1(s: String): String {
        val dig = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        // r656-G：查表转十六进制；旧实现逐字节 String.format，单次约 20 个格式化对象。
        val out = CharArray(dig.size * 2)
        dig.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return String(out)
    }

    // ---- r656-G：coverModel() 内存索引 ----
    // 组合期（含列表滚动、播放高亮重组）每次调用 coverModel 原先都要 SHA-1 + stat + 读 12 字节文件头；
    // 现在：结果按 url+尺寸 记忆（LRU 512），本地封面是否存在查进程内文件名索引（后台一次性列目录建成），
    // 文件头校验移到后台且每个文件只做一次。同一会话内同一封面返回的模型稳定不变，Coil 不会因模型
    // 在 URL/File 之间跳变而重复请求、闪白。
    private val NO_COVER = Any()
    private val dataUrlMemoKeys = CoverDataUrlMemoKeys()
    // 纯 JVM 的 LRU（不用 android.util.LruCache，避免普通 JUnit 触发 CacheManager 初始化时撞上 android.jar 桩）。
    private val coverModelMemo = CoverModelMemo(512)
    private val coverIndex: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val coverValidated: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var coverIndexReady = false
    private val coverIndexStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var coversDirCache: File? = null
    /** 测试/诊断：coverModel 路径上同步读取文件头的次数。 */
    @Volatile internal var coverModelHeaderReads = 0L
        private set
    private val coverValidationExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "cover-index").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    @Volatile private var coversDirOwner: Context? = null

    /** 按 Application 实例缓存封面目录；换了 Application（如单测新进程环境）就整体重置索引与记忆。 */
    private fun coversDirFast(ctx: Context): File {
        val app = ctx.applicationContext ?: ctx
        val cached = coversDirCache
        if (cached != null && coversDirOwner === app) return cached
        synchronized(coverIndex) {
            if (coversDirOwner !== app) {
                if (coversDirOwner != null) {
                    coverModelMemo.evictAll(); coverIndex.clear(); coverValidated.clear(); coverIndexReady = false
                }
                coversDirOwner = app
            }
            return coversDir(ctx).also { coversDirCache = it }
        }
    }

    /** 进程启动时在后台列一次封面目录；建好前 coverModel 退回旧的同步检查（但同样记忆结果）。 */
    fun warmCoverIndexAsync(ctx: Context) {
        if (!coverIndexStarted.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        try {
            coverValidationExecutor.execute { buildCoverIndex(app) }
        } catch (_: Exception) { coverIndexStarted.set(false) }
    }

    internal fun buildCoverIndex(ctx: Context) {
        try {
            val names = coversDirFast(ctx).list().orEmpty().filterNot { it.endsWith(".part") }
            coverIndex.addAll(names)
            coverIndexReady = true
        } catch (_: Exception) {
            coverIndexReady = false
        }
    }

    /** 测试用：清空索引与记忆。 */
    internal fun resetCoverModelCacheForTest() {
        coverModelMemo.evictAll()
        coverIndex.clear()
        coverValidated.clear()
        coverIndexReady = false
        coverIndexStarted.set(false)
        coversDirCache = null
        coversDirOwner = null
        coverModelHeaderReads = 0L
        coverFailureCache.clear()
    }

    private fun onCoverFileInstalled(f: File, normalizedUrl: String? = null) {
        coverIndex.add(f.name)
        coverValidated.add(f.name)
        if (!normalizedUrl.isNullOrBlank()) {
            // SW-02-09：文件已落盘时，先前记住的 miss/远程 URL 必须失效并重解析为本地
            // 文件——否则 memo 仍返回远程 URL，界面继续走网络（与 J 波 SWR 不冲突：
            // 只失效与该 URL 精确相关的记忆项）。
            coverModelMemo.removeValues { it === NO_COVER || it == normalizedUrl }
        }
    }

    private fun onCoverFileRemoved(f: File) {
        coverIndex.remove(f.name)
        coverValidated.remove(f.name)
        // 让仍指向该文件的记忆项失效，下次重组退回网络 URL（只发生在被淘汰/损坏的封面上）。
        coverModelMemo.removeValues { it == f }
    }

    private fun scheduleCoverValidation(f: File) {
        if (!coverValidated.add(f.name)) return
        try {
            coverValidationExecutor.execute {
                if (!isValidCoverImageFile(f)) {
                    try { if (f.exists()) f.delete() } catch (_: Exception) {}
                    onCoverFileRemoved(f)
                }
            }
        } catch (_: Exception) { coverValidated.remove(f.name) }
    }

    // ---- J-缓存：封面磁盘过期策略（台账 WAVE-J 登记）----
    // 此前封面落盘后永不过期：同一 URL 内容变更（歌单换封面等）永远显示旧图。
    // 策略：TTL 7 天；陈旧文件先照常显示（coverModel 不阻塞），后台至多每进程一次调度
    // ensureCover 刷新；刷新失败保留陈旧文件继续显示（可用性优先于新鲜度）。
    // 容量仍走既有口径：用户缓存上限 maxBytes + trimIfNeeded（封面在淘汰序列中最后删）。
    private val coverRefreshScheduled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun coverFileStale(f: File): Boolean =
        System.currentTimeMillis() - f.lastModified() > COVER_DISK_TTL_MS

    private fun scheduleCoverRefresh(ctx: Context, normalizedUrl: String, f: File, targetSizePx: Int?) {
        if (!coverFileStale(f)) return
        if (!coverRefreshScheduled.add(f.name)) return
        val app = ctx.applicationContext
        try {
            coverValidationExecutor.execute {
                try { ensureCover(app, normalizedUrl, targetSizePx = targetSizePx) } catch (_: Exception) {}
            }
        } catch (_: Exception) { coverRefreshScheduled.remove(f.name) }
    }

    private fun coverFile(ctx: Context, url: String): File {
        // v3：平台原图 URL/歌单同对象图片字段升级后，强制避开旧缓存中的模糊响应。
        val hash = sha1("cover-cache-v3|$url")
        val ext = when {
            url.contains(".png", true) -> "png"
            url.contains(".webp", true) -> "webp"
            else -> "jpg"
        }
        return File(coversDirFast(ctx), "$hash.$ext")
    }

    private const val MAX_COVER_DOWNLOAD_BYTES = 16L * 1024 * 1024

    private fun copyCoverBytesLimited(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > MAX_COVER_DOWNLOAD_BYTES) throw java.io.IOException("Cover response exceeds size limit")
            output.write(buffer, 0, read)
        }
    }

    /** 本地已有且非空则直接返回 file://；否则返回原 URL（Coil 仍可显示，后台可 ensure） */
    fun coverModel(ctx: Context, url: String?, targetSizePx: Int? = null): Any? {
        if (url.isNullOrBlank()) return null
        // 内联图片先做长度门禁，避免对异常大的输入计算指纹或进入 Base64 解码。
        if (url.startsWith("data:", ignoreCase = true) &&
            !isCoverDataUrlSizeAllowed(url.length)
        ) return null
        // 指纹只缓存于弱键表：LRU 不保留整段内联图片，Java String.hashCode 碰撞也不会串图。
        val urlKey = if (url.startsWith("data:", ignoreCase = true)) dataUrlMemoKeys.key(url) else url
        val memoKey = if (targetSizePx == null) urlKey else "$urlKey\u0000$targetSizePx"
        coversDirFast(ctx)
        coverModelMemo.get(memoKey)?.let { return if (it === NO_COVER) null else it }
        val resolved = resolveCoverModel(ctx, url, targetSizePx)
        coverModelMemo.put(memoKey, resolved ?: NO_COVER)
        return resolved
    }

    private fun resolveCoverModel(ctx: Context, url: String, targetSizePx: Int?): Any? {
        if (url.startsWith("data:", ignoreCase = true)) {
            // data: 封面（本地导入内嵌图）首次解码落盘仍是同步的，但结果被记忆，每会话每封面只做一次。
            return try {
                val comma = url.indexOf(',')
                if (comma <= 0) return null
                val header = url.substring(0, comma)
                val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
                if (bytes.size > BoundedCoverReader.MAX_BYTES) return null
                if (bytes.size <= 64 || !isSupportedCoverImageSignature(bytes)) return null
                val ext = when {
                    header.contains("png", true) -> "png"
                    header.contains("webp", true) -> "webp"
                    else -> "jpg"
                }
                val f = File(coversDirFast(ctx), "local-${sha1(url)}.$ext")
                if (!f.exists() || f.length() != bytes.size.toLong() || !isValidCoverImageFile(f)) {
                    val temporary = File.createTempFile("inline-", ".tmp", f.parentFile)
                    try {
                        temporary.writeBytes(bytes)
                        if (!isValidCoverImageFile(temporary) || !temporary.renameTo(f)) return null
                    } finally { temporary.delete() }
                }
                f.takeIf { isValidCoverImageFile(it) }
            } catch (_: Exception) { null }
        }
        if (url.startsWith("/")) return "file://$url"
        if (url.startsWith("file:")) return url
        val normalized = normalizeCoverUrl(url, targetSizePx)
        val f = coverFile(ctx, normalized)
        if (coverIndexReady) {
            // 只查内存索引；文件头校验在后台做一次，损坏文件会被删除并让记忆失效。
            if (!coverIndex.contains(f.name)) return normalized
            scheduleCoverValidation(f)
            scheduleCoverRefresh(ctx, normalized, f, targetSizePx)
            return f
        }
        coverModelHeaderReads++
        if (isValidCoverImageFile(f)) {
            onCoverFileInstalled(f, normalized)
            scheduleCoverRefresh(ctx, normalized, f, targetSizePx)
            return f
        }
        if (f.exists()) {
            f.delete()
            onCoverFileRemoved(f)
        }
        return normalized
    }

    /**
     * 仅当本地不存在或 URL 指纹文件缺失时下载；URL 变了自然是新文件。
     * J-缓存：文件有效但已过 TTL（7 天）时尝试重新下载刷新；刷新失败/产物无效时
     * 返回陈旧文件路径继续显示，不让一次网络失败把已有封面变成占位（无文件/文件损坏时维持原语义）。
     */
    fun ensureCover(ctx: Context, url: String?, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 12000, targetSizePx: Int? = null): String {
        if (url.isNullOrBlank()) return ""
        if (url.startsWith("/") || url.startsWith("file:")) return url
        val normalized = normalizeCoverUrl(url, targetSizePx)
        if (normalized.isBlank()) return url
        val f = coverFile(ctx, normalized)
        return coverWriteLocks.withKey(f.absolutePath) {
            val valid = isValidCoverImageFile(f)
            if (valid && !coverFileStale(f)) f.absolutePath.also {
                coverFailureCache.recordSuccess(normalized)
                onCoverFileInstalled(f, normalized)
            }
            else if (!canWriteOptionalCache(ctx)) if (valid) f.absolutePath else url
            // ACCEPT5-covergate：短 TTL 内刚失败过同一 URL —— 不再打网络，直接走失败回落
            // （有陈旧文件继续显示、否则原 URL 让面上垫底），避免重组/滚动整表重试放大拥堵。
            else if (coverFailureCache.isRecentlyFailed(normalized, System.currentTimeMillis())) {
                if (valid) f.absolutePath else url
            }
            else {
                if (f.exists() && !valid) { f.delete(); onCoverFileRemoved(f) }
                var temp: File? = null
                try {
                    temp = File.createTempFile("cover-${sha1(normalized).take(12)}-", ".part", f.parentFile)
                    val fakeDownload = coverDownloadActionForTest
                    val downloaded = if (fakeDownload != null) fakeDownload(normalized, temp!!, connectTimeoutMs, readTimeoutMs)
                    else CoverNetworkClient.downloadToFile(normalized, temp!!, connectTimeoutMs, readTimeoutMs)
                    if (!downloaded) {
                        coverFailureCache.recordFailure(normalized, System.currentTimeMillis())
                        temp?.delete()
                        return@withKey if (valid) f.absolutePath else url
                    }
                    if (temp != null && isValidCoverImageFile(temp!!)) {
                        if (!temp!!.renameTo(f)) throw java.io.IOException("Unable to install verified cover cache file")
                        coverFailureCache.recordSuccess(normalized)
                        onCoverFileInstalled(f, normalized)
                        synchronized(coverTrimLock) { trimIfNeeded(ctx) }
                        f.absolutePath
                    } else {
                        coverFailureCache.recordFailure(normalized, System.currentTimeMillis())
                        temp?.delete()
                        if (valid) f.absolutePath else url
                    }
                } catch (_: Exception) {
                    coverFailureCache.recordFailure(normalized, System.currentTimeMillis())
                    temp?.delete()
                    if (valid) f.absolutePath else url
                } finally {
                    temp?.delete()
                }
            }
        }
    }

    suspend fun ensureHomeCovers(
        ctx: Context,
        hot: List<Song>, playlists: List<Playlist>, ranks: List<RankBoard>,
        favorites: List<Song>, ownedPlaylists: List<Playlist>, avatarPath: String
    ) {
        val urls = mutableListOf<String?>()
        hot.take(3).forEach { urls.add(it.cover) }
        playlists.take(2).forEach { urls.add(it.cover); it.songs.firstOrNull()?.let { song -> urls.add(song.cover) } }
        ranks.take(2).forEach { urls.add(it.cover); it.songs.firstOrNull()?.let { song -> urls.add(song.cover) } }
        favorites.take(2).forEach { urls.add(it.cover) }
        ownedPlaylists.take(1).forEach { urls.add(it.cover); it.songs.firstOrNull()?.let { song -> urls.add(song.cover) } }
        if (avatarPath.isNotEmpty()) urls.add(avatarPath)
        ensureCovers(ctx, urls.distinct().take(16))
    }

    /**
     * ACCEPT5-covergate：队列抽屉打开时的有界封面预热。选序见 [queueCoverPrewarmIndices]
     * （当前播放邻域优先 + 队列头补齐，上限 [QUEUE_COVER_PREWARM_LIMIT] 首）；按队列行
     * 同一尺寸（144px）归一，确保预热落盘的正是行图将命中的缓存文件。只在队列抽屉
     * 组合时由 DefaultPlayerQueuePage 触发；走 [ensureCovers] 的共享预取池 UI 低优先
     * 通道（播放预取保留名额、饱和即跳过），不与播放取链/解析抢资源。
     */
    suspend fun ensureQueueCovers(ctx: Context, queue: List<Song>, currentIndex: Int) {
        if (queue.isEmpty()) return
        val urls = queueCoverPrewarmIndices(queue.size, currentIndex).mapNotNull { index ->
            queue[index].cover?.takeIf { it.isNotBlank() }
        }
        if (urls.isEmpty()) return
        ensureCovers(ctx, urls, targetSizePx = QUEUE_COVER_PREWARM_TARGET_SIZE_PX)
    }

    internal suspend fun ensureCovers(ctx: Context, urls: Collection<String?>, targetSizePx: Int? = null) {
        val covers = urls.asSequence()
            .filterNotNull()
            .map { normalizeCoverUrl(it.trim(), targetSizePx) }
            .filter { it.isNotBlank() }
            .filter { it.startsWith("https://", true) || it.startsWith("http://", true) }
            .distinct()
            .take(40)
            .toList()
        if (covers.isEmpty()) return
        withTimeoutOrNull(12_000L) {
            coroutineScope {
                covers.mapNotNull { cover ->
                    // SW-02-08：可选预取统一走共享有界池（为播放预取保留名额）；
                    // 饱和时跳过，父作用域取消会撤销排队项并中断在跑的同步下载。
                    SharedPrefetchWorkPool.submit(this, BoundedPrefetchWorkPool.Priority.UI) {
                        try {
                            runInterruptible(Dispatchers.IO) {
                                val fake = coverPrefetchActionForTest
                                if (fake != null) fake(ctx, cover)
                                else ensureCover(ctx, cover, connectTimeoutMs = 3000, readTimeoutMs = 5000, targetSizePx = targetSizePx)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // 单张封面失败不连带取消同批其余封面。
                        }
                    }
                }.joinAll()
            }
        }
    }

    fun audioCacheFile(ctx: Context, key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        return File(audioDir(ctx), "$safe.cache")
    }

    /** 超限淘汰：优先删临时/歌词/音频，封面最后且尽量保留 */
    fun trimIfNeeded(ctx: Context) {
        trimRuns++
        val max = maxBytes(ctx)
        if (max >= Long.MAX_VALUE / 8) return
        val files = mutableListOf<Pair<File, Int>>()
        // 与 usageParts 同一口径：音频缓存目录单列且由 SimpleCache 自己淘汰，普通清理器不再把它重复纳入总量。
        val managedMediaCache = File(ctx.cacheDir, "exo_media_cache").canonicalPath
        val lyricDirectory = lyricsDir(ctx)
        val coverDirectory = coversDir(ctx)
        listOf(ctx.cacheDir to 0, audioDir(ctx) to 1, lyricDirectory to 2, coverDirectory to 9, avatarDir(ctx) to 8).forEach { (d, prio) ->
            if (d.exists()) d.walkTopDown().filter { it.isFile }.forEach { f ->
                val inManagedMediaCache = try { f.canonicalPath.startsWith(managedMediaCache + File.separator) } catch (_: Exception) { false }
                // SW-02-09：正在写入的 staging 文件（封面 .part / 歌词原子 staging）不进淘汰候选。
                val activeCoverStage = d == coverDirectory && f.name.startsWith("cover-") && f.name.endsWith(".part")
                val activeLyricStage = d == lyricDirectory && AtomicCacheFile.isStagingName(f)
                if (!inManagedMediaCache && !activeCoverStage && !activeLyricStage) files.add(f to prio)
            }
        }
        // 总量必须含 Exo 音频缓存；删除候选不含它，音频由 SimpleCache 的 LRU 按同一上限淘汰。
        var total = usageBytes(ctx)
        if (total <= max) return
        val target = (max * 0.85).toLong()
        files.sortWith(compareBy<Pair<File, Int>> { it.second }.thenBy { it.first.lastModified() })
        // 常规淘汰优先删临时/歌词/音频；封面最后且尽量保留
        var removedFiles = 0L
        for ((f, _) in files) {
            if (total <= target) break
            if ((f.absolutePath.contains("/covers/") || f.absolutePath.contains("/avatars/")) && total < max) break
            val len = f.length()
            if (MediaCacheLeaseManager.deleteIfUnleased(f)) {
                total -= len; removedFiles++
                if (f.parentFile?.name == "covers") onCoverFileRemoved(f)
            }
        }
        if (removedFiles > 0) CacheManager.recordEvictSpace()
        // 非 Media3 文件删完仍超限时，再由 SimpleCache 以完整资源为单位做 LRU；不直接删其目录。
        val nonMedia = (usageBytes(ctx) - dirSize(exoAudioDir(ctx))).coerceAtLeast(0L)
        val mediaBudget = (target - nonMedia).coerceAtLeast(0L)
        PlayerService.trimMediaCacheTo(mediaBudget)
    }
}

/** 线程安全的小型 LRU（访问序 LinkedHashMap）。 */
internal class CoverModelMemo(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<String, Any>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > maxEntries
    }
    @Synchronized fun get(key: String): Any? = map[key]
    @Synchronized fun put(key: String, value: Any) { map[key] = value }
    @Synchronized fun evictAll() = map.clear()
    @Synchronized fun removeValues(predicate: (Any) -> Boolean) { map.values.removeAll { predicate(it) } }
    @Synchronized fun size(): Int = map.size
}
