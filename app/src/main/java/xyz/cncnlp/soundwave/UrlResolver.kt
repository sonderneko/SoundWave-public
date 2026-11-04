package xyz.cncnlp.soundwave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import okhttp3.ResponseBody.Companion.toResponseBody
import xyz.cncnlp.soundwave.source.direct.kugouDirectResolveUrl
import xyz.cncnlp.soundwave.source.direct.kuwoDirectResolveUrl
import xyz.cncnlp.soundwave.source.direct.normalizeKgMediaUrl
import xyz.cncnlp.soundwave.source.direct.miguDirectResolveUrl
import xyz.cncnlp.soundwave.source.direct.WyResolveOutcome
import xyz.cncnlp.soundwave.source.direct.wangyiOiapiResolveOutcome
import xyz.cncnlp.soundwave.source.direct.wangyiOuterResolveOutcome
import xyz.cncnlp.soundwave.source.direct.wangyiOuterResolveUrl
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.TrustedMediaCacheIdentity
import xyz.cncnlp.soundwave.resolve.UrlCacheExpiryPolicy

/** wy 本机 oiapi 段的诊断阶段名（脚本链之后出场；/music/url 服务端兜底整条删除后，它是 wy 解析链的末位兜底）。 */
internal const val STAGE_OFFICIAL_OIAPI = "official-oiapi"

/** ACCEPT5（本地直读）：已下载/本地文件命中的计划与尝试阶段名（播放路径标签「本地文件」）。 */
internal const val STAGE_LOCAL_FILE = "local"

/**
 * 探测让行门：把「播放优先」实现成暂停-恢复，而不是整单处决。
 *
 * 探测在每个检查点调用 [checkpoint]：播放纪元未变且播放解析通道空闲时立即
 * 放行；否则原地轮询等待，直到纪元连续稳定 [SETTLE_MS] 且通道空闲，才领用
 * 新纪元继续。这样批量检测在用户连续听歌时只是变慢，不会再被逐个掐死。
 * 等待累计超过 [MAX_TOTAL_WAIT_MS] 仍等不到稳定窗口，才抛
 * CancellationException，落回调用方既有的 SKIPPED 记账；任务被真正取消时
 * 经 [sleeper] 立即上抛，不额外延迟收尾。
 *
 * epoch / 忙碌判定 / 时钟 / 睡眠全部注入，生产环境由 [UrlResolver] 绑定真实
 * 播放纪元与播放通道，单测以虚拟时间驱动。
 */
class ProbeYieldGate(
    private val epoch: () -> Long,
    private val playbackBusy: () -> Boolean,
    private val nowMs: () -> Long,
    private val sleeper: suspend (Long) -> Unit
) {
    private var admittedEpoch: Long = epoch()
    private var waitedMs: Long = 0L

    suspend fun checkpoint() {
        if (epoch() == admittedEpoch && !playbackBusy()) return
        var stableSinceMs = -1L
        while (true) {
            sleeper(POLL_MS)
            waitedMs += POLL_MS
            if (waitedMs > MAX_TOTAL_WAIT_MS) {
                throw kotlinx.coroutines.CancellationException("播放请求持续抢占，脚本探测等待超限")
            }
            val current = epoch()
            if (current != admittedEpoch) {
                admittedEpoch = current
                stableSinceMs = -1L
            }
            if (!playbackBusy() && current == admittedEpoch) {
                if (stableSinceMs < 0L) stableSinceMs = nowMs()
                if (nowMs() - stableSinceMs >= SETTLE_MS) return
            } else {
                stableSinceMs = -1L
            }
        }
    }

    companion object {
        const val SETTLE_MS = 1_500L
        const val POLL_MS = 200L
        const val MAX_TOTAL_WAIT_MS = 5 * 60_000L
    }
}

/**
 * 取链策略（洛雪/Sollin）：
 * 阶段可配：原平台重试 → 音质降级(含128k兜底) → 跨脚本 → 跨平台
 * 全局硬超时，成功缓存；可选记住跨平台结果
 */
object UrlResolver {
    private val SRC_PRIORITY = mapOf(
        "kg" to listOf("全豆要", "全都要", "星海", "ikun", "西瓜", "玉宁熙", "LX", "洛雪"),
        "kw" to listOf("星海", "全豆要", "全都要", "溯音", "玉宁熙", "西瓜", "LX", "ikun"),
        "tx" to listOf("全豆要", "全都要", "星海", "溯音", "玉宁熙", "LX", "洛雪", "西瓜", "ikun"),
        "wy" to listOf("星海", "溯音", "全豆要", "全都要", "玉宁熙", "西瓜", "LX", "ikun"),
        "mg" to listOf("全豆要", "全都要", "星海", "玉宁熙", "西瓜", "LX", "ikun")
    )

    /**
     * URL cache is keyed by original song + requested quality.  A cross-source
     * result also carries its candidate identity so a cached 128k/other-song URL
     * cannot silently become the preferred 320k source for a later request.
     */
    private data class CachedUrl(
        val url: String,
        val actualQuality: String,
        val scriptId: String,
        val candidateKey: String,
        val platform: String,
        val contentEvidence: String,
        val createdAt: Long = System.currentTimeMillis(),
        // SW-02-13：签名链自带过期提示时以其为准（不超过 30 分钟上限），不再一律按 createdAt+TTL。
        val expiresAtEpochMs: Long = UrlCacheExpiryPolicy.expiresAtEpochMillis(url, createdAt)
    )
    private const val CACHE_POLICY_VERSION = "resolve-anonymous-v4"
    private const val URL_CACHE_TTL_MS = UrlCacheExpiryPolicy.MAX_SUCCESS_TTL_MS
    private val urlCache = LinkedHashMap<String, CachedUrl>(64, 0.75f, true)
    private val cacheLock = Any()

    // ---- J-缓存：取链结果缓存落盘（台账 WAVE-J 登记）----
    // 此前 urlCache 纯内存，app 被杀即全失、重启后首播必全量重解析。
    // 落盘口径与内存完全一致：同一缓存键（策略版本 | owner | 脚本配置 SHA-256 | 请求域 | 请求音质）、
    // 同一 TTL（30 分钟，按 createdAt 判定，落盘不续期）、同一容量（80 条 LRU）、
    // qualitySatisfies 门控仍在各读取点执行，本层只做序列化/恢复，不改解析编排。
    private const val URL_CACHE_PREF = "soundwave_url_cache_v1"
    private const val URL_CACHE_KEY = "entries"
    @Volatile private var urlCachePrefs: android.content.SharedPreferences? = null
    @Volatile private var urlCacheLoaded = false

    /** 由 SoundWaveApplication / MainActivity 在既有 store init 旁调用；重复调用无副作用。 */
    @Synchronized
    fun init(ctx: android.content.Context) {
        if (urlCachePrefs != null) return
        urlCachePrefs = ctx.applicationContext.getSharedPreferences(URL_CACHE_PREF, android.content.Context.MODE_PRIVATE)
        loadPersistedUrlCache()
    }

    private fun loadPersistedUrlCache() {
        val prefs = urlCachePrefs ?: return
        val raw = try { prefs.getString(URL_CACHE_KEY, null) } catch (_: Exception) { null } ?: return
        synchronized(cacheLock) {
            if (urlCacheLoaded) return
            urlCacheLoaded = true
            val now = System.currentTimeMillis()
            try {
                val root = org.json.JSONObject(raw)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = root.optJSONObject(k) ?: continue
                    val createdAt = o.optLong("t", 0L)
                    val u = o.optString("u")
                    if (u.isBlank()) continue
                    if (UrlCacheExpiryPolicy.expiresAtEpochMillis(u, createdAt) <= now) continue
                    urlCache[k] = CachedUrl(u, o.optString("q"), o.optString("s"), o.optString("c"), o.optString("p"), o.optString("e"), createdAt)
                }
                trimCacheLocked()
            } catch (_: Exception) { /* 脏数据整表丢弃，内存缓存照常工作 */ }
        }
    }

    /** 必须在 cacheLock 内调用：把内存缓存快照写盘（apply 异步落盘，写失败不影响解析）。 */
    private fun persistUrlCacheLocked() {
        val prefs = urlCachePrefs ?: return
        try {
            val root = org.json.JSONObject()
            val now = System.currentTimeMillis()
            for ((k, e) in urlCache) {
                if (e.expiresAtEpochMs <= now || e.url.isBlank()) continue
                root.put(k, org.json.JSONObject()
                    .put("u", e.url).put("q", e.actualQuality).put("s", e.scriptId)
                    .put("c", e.candidateKey).put("p", e.platform).put("e", e.contentEvidence)
                    .put("t", e.createdAt))
            }
            prefs.edit().putString(URL_CACHE_KEY, root.toString()).apply()
        } catch (_: Exception) {}
    }
    /**
     * 短熔断按“歌曲 + 脚本”隔离：某脚本对 A 失败，不得错误阻塞它解析 B。
     * 全局脚本故障仍可通过 songKey="*" 显式记录。
     */
    private val failedScriptsUntil = mutableMapOf<String, Long>()
    private const val SCRIPT_COOLDOWN_MS = 90_000L
    /**
     * 阶段链预算预留——链切片总预算 = 26s 全局预算先扣收尾预留，保证链尾
     * 收尾（oiapi 本机兜底 + finish）不被外层硬超时腰斩。ACCEPT5 删除兜底棒：
     * 原为服务端末位兜底预留的 5s 随 /music/url 腿整条
     * 删除而消灭，恒只留收尾预留（即原「兜底未准入」分支的既有形态）。
     */
    private const val TAIL_RESERVE_MS = 1_000L
    private const val MIN_CHAIN_BUDGET_MS = 8_000L
    /** 阶段链内层看门狗在链预算之外的宽限（容纳最后一枪的探针/收尾开销）。 */
    private const val STAGE_CHAIN_SLACK_MS = 2_000L
    /**
     * H2-b 阶段内单脚本软超时（介于“成功尝试实测分布”与 16s 硬看门狗之间）：
     * 外部脚本实测一次成功调用约 3.7s（见 resolve 内注释），TUN 代理单跳实测
     * 2.8–5.1s——6s 覆盖“一次成功调用 + 一跳代理”的正常长尾，又能把无响应的
     * 长尾尝试从 16s 砍到 6s。软超时按瞬时失败记账，不入 H1-a 裁决；
     * 同一 tryScript 内某档触发软超时即结束该脚本本轮（不再拿下一档继续烧）。
     */
    private const val SCRIPT_SOFT_TIMEOUT_MS = 6_000L

    private fun ownerKey(owner: xyz.cncnlp.soundwave.migration.ProfileContext) =
        "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner)}:${owner.generation}"
    private fun cacheKey(song: Song, requestedQuality: String,
                         owner: xyz.cncnlp.soundwave.migration.ProfileContext,
                         scripts: List<Script>): String {
        val configuration = org.json.JSONArray(scripts.map { sc ->
            org.json.JSONArray(listOf(sc.id, sc.enabled, sc.code))
        }).toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(configuration.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val requestScope = TrustedMediaCacheIdentity.requestScope(listOf(
            song.source, song.id, song.name, song.singer, song.albumId,
            song.copyrightId, song.mediaMid, song.hash, song.duration.toString()
        ))
        return "$CACHE_POLICY_VERSION|${ownerKey(owner)}|$hash|$requestScope|${requestedQuality.lowercase()}"
    }
    private fun candidateIdentity(song: Song): String = TrustedMediaCacheIdentity.requestScope(listOf(
        song.source, song.id, song.name, song.singer, song.albumId,
        song.copyrightId, song.mediaMid, song.hash, song.duration.toString()
    ))
    private fun failedScriptKey(songKey: String, scriptId: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext): String = "${ownerKey(owner)}|$songKey|$scriptId"

    fun peekCachedUrl(song: Song, requestedQuality: String,
                      scripts: List<Script> = emptyList(),
                      owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): String? = synchronized(cacheLock) {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) return null
        val entry = urlCache[cacheKey(song, requestedQuality, owner, scripts)] ?: return null
        if (entry.url.isBlank() || entry.expiresAtEpochMs <= System.currentTimeMillis()) {
            urlCache.remove(cacheKey(song, requestedQuality, owner, scripts))
            return null
        }
        return entry.url
    }

    fun clearSongCache(song: Song, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) = synchronized(cacheLock) {
        // 同时清除旧版本键，升级后不会留下不可见的陈旧条目。
        val scope = TrustedMediaCacheIdentity.requestScope(listOf(
            song.source, song.id, song.name, song.singer, song.albumId,
            song.copyrightId, song.mediaMid, song.hash, song.duration.toString()
        ))
        val doomed = urlCache.keys.filter { it.startsWith("$CACHE_POLICY_VERSION|${ownerKey(owner)}|") && it.contains("|$scope|") }
        doomed.forEach { urlCache.remove(it) }
        if (doomed.isNotEmpty()) persistUrlCacheLocked()
    }

    @Synchronized
    fun markScriptBad(songKey: String, id: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) {
        if (songKey.isNotEmpty() && id.isNotEmpty()) {
            failedScriptsUntil[failedScriptKey(songKey, id, owner)] = System.currentTimeMillis() + SCRIPT_COOLDOWN_MS
        }
    }

    /** 当前首歌最终出链的脚本，按歌曲 key 绑定；预取/并发解析不会误熔断另一首歌的脚本。 */
    private val winningScriptBySong = mutableMapOf<String, String>()
    /** 最近一次解析的脱敏事实链；按歌曲保留，供设置页/失败提示解释，不持久化临时 URL。 */
    private val lastAttemptsBySong = LinkedHashMap<String, List<ResolveAttempt>>(32, 0.75f, true)

    @Synchronized
    fun latestAttempts(songKey: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): List<ResolveAttempt> = lastAttemptsBySong["${ownerKey(owner)}|$songKey"].orEmpty()

    @Synchronized
    private fun rememberAttempts(songKey: String, attempts: List<ResolveAttempt>, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner) || songKey.isBlank()) return
        // ACCEPT5：上限 24 → 40——计划内跳过行（去重/超时/裁决/探针记忆）按
        // 策略 conformance 要求逐位置留痕后，完整链的行数上限随之提高，
        // 避免尾段（跨平台腿）再被截断出窗口（E3 的旧病根之一）。
        lastAttemptsBySong["${ownerKey(owner)}|$songKey"] = attempts.takeLast(40)
        while (lastAttemptsBySong.size > 40) lastAttemptsBySong.entries.iterator().apply { if (hasNext()) { next(); remove() } }
    }

    /**
     * ACCEPT5（策略 conformance）：最近一次解析的**完整阶段计划**（阶段码序列，
     * 含官方直连/oiapi 本机兜底段）。策略行据此宣布真实全计划，而不是只报
     * 实际执行到的第一段——计划在解析开始时由 resolveStagePlan 定死，执行
     * 中途成功也不会让未执行的跨平台腿从计划里消失。
     */
    private val lastPlanBySong = LinkedHashMap<String, List<String>>(32, 0.75f, true)

    @Synchronized
    fun latestPlan(songKey: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): List<String> = lastPlanBySong["${ownerKey(owner)}|$songKey"].orEmpty()

    @Synchronized
    private fun rememberPlan(songKey: String, plan: List<String>, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner) || songKey.isBlank()) return
        lastPlanBySong["${ownerKey(owner)}|$songKey"] = plan
        while (lastPlanBySong.size > 40) lastPlanBySong.entries.iterator().apply { if (hasNext()) { next(); remove() } }
    }

    /**
     * ACCEPT5（本地直读）：本地文件命中不进解析链，但计划宣布与尝试留存必须
     * 与解析同一套纪律——否则该歌在 Resolver 侧的策略行永远停留在上一次
     * 联网解析（含下载时那次），路径弹层/文本导出会把旧取链链当成本次事实。
     * 写入即覆盖：计划只有一段「本地文件」，尝试只有一行 HIT（startedAtMs=0
     * 与条目墙钟同一时钟基），quality 为实测/存储的真实档位。
     */
    fun noteLocalPlayback(
        songKey: String,
        platform: String,
        quality: String,
        downloaded: Boolean,
        owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
    ) {
        if (songKey.isBlank()) return
        rememberPlan(songKey, listOf(STAGE_LOCAL_FILE), owner)
        rememberAttempts(
            songKey,
            listOf(
                ResolveAttempt(
                    stage = STAGE_LOCAL_FILE,
                    platform = platform,
                    scriptId = "local",
                    quality = quality,
                    elapsedMs = 0L,
                    outcome = ResolveAttempt.Outcome.HIT,
                    reason = if (downloaded) "downloaded_local" else "local_file",
                    startedAtMs = 0L
                )
            ),
            owner
        )
    }

    @Synchronized
    fun markWinningScript(songKey: String, id: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) {
        if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner) && songKey.isNotBlank() && id.isNotBlank()) winningScriptBySong["${ownerKey(owner)}|$songKey"] = id
    }

    @Synchronized
    fun markWinningScriptBad(songKey: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): String {
        val id = winningScriptBySong.remove("${ownerKey(owner)}|$songKey").orEmpty()
        if (id.isNotEmpty()) markScriptBad(songKey, id, owner)
        return id
    }

    @Synchronized
    private fun isScriptBad(songKey: String, id: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): Boolean {
        if (songKey.isEmpty() || id.isEmpty()) return false
        val key = failedScriptKey(songKey, id, owner)
        val until = failedScriptsUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedScriptsUntil.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun clearFailedScripts() {
        failedScriptsUntil.clear()
    }

    suspend fun resolve(
        song: Song,
        scripts: List<Script>,
        runtime: ScriptRuntime,
        preferQuality: String = "320k",
        skipScripts: Set<String> = emptySet(),
        forceCross: Boolean = false,
        /** 导入扫描专用：只验证原平台，不允许智能级联把跨源成功误记为原平台可播。 */
        originOnly: Boolean = false,
        /** 本次解析用途，传给共享 ScriptRuntime，避免播放抢占下载。 */
        purpose: ResolveTicket.Purpose = ResolveTicket.Purpose.PLAY,
        /** 本次请求身份；同曲重试/切歌后旧请求不得回写缓存、Song 或跨源记忆。 */
        requestStillCurrent: () -> Boolean = { true },
        /** 取链进度回调（后台线程）：每次实质尝试时上报简短文案，供播放器展示「正在换源」。 */
        onProgress: ((String) -> Unit)? = null,
        /** Request-local provenance; never reconstruct a winner from the shared cache afterward. */
        onResolvedMedia: ((TrustedMediaCacheIdentity) -> Unit)? = null
    ): Pair<String, String> {
        // ACCEPT4（修1）：attempts 收集器提升到 26s 硬超时作用域之外。旧形态下
        // attempts 是 withContext 块内局部量，外层超时一触发就随作用域被弃，
        // 诊断只剩一条合成 budget 行——本轮到底试过什么、死在哪一步全不可见
        // （审计 §10 结论一，证据销毁点）。现收集器在作用域外创建、超时尾部
        // 读快照并尾加 budget 行，真实 attempts 全量保留。
        val collectedAttempts = java.util.Collections.synchronizedList(mutableListOf<ResolveAttempt>())
        val resolveStartedAt = System.currentTimeMillis()
        return withTimeoutOrNull(26_000L) { withContext(Dispatchers.IO + ResolveBlockingStage.context(purpose)) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(runtime.ownerContext) || !requestStillCurrent()) return@withContext "" to ""
        val cacheKey = cacheKey(song, preferQuality, runtime.ownerContext, scripts)
        val attempts: MutableList<ResolveAttempt> = collectedAttempts
        fun attemptsSnapshot(): List<ResolveAttempt> = synchronized(attempts) { attempts.toList() }
        fun trace(
            stage: String,
            platform: String,
            script: String,
            quality: String,
            elapsed: Long,
            outcome: ResolveAttempt.Outcome,
            reasonCode: ResolveAttempt.Reason = ResolveAttempt.Reason.NONE,
            reason: String = "",
            route: String = "",
            claimedQuality: String = ""
        ) {
            // ACCEPT5（时钟基统一）：每行尝试落其在本轮解析内的开始偏移
            // （now − elapsed − 起点）。旧形态只有逐次耗时，展示层靠「累计求和」
            // 反推时刻——软超时整窗、budget 尾注整轮耗时等行一入列，求和即与
            // 条目的墙钟 resolveMs/totalMs 漂移（真机实证 +13.845s vs 10509ms）。
            // 偏移与总时长自此同出一钟（resolveStartedAt），可互相推导。
            val startedOffset = (System.currentTimeMillis() - elapsed.coerceAtLeast(0L) - resolveStartedAt)
                .coerceAtLeast(0L)
            attempts += ResolveAttempt(
                stage, platform, script, quality, elapsed.coerceAtLeast(0L), outcome,
                reasonCode, reason, route, startedAtMs = startedOffset,
                claimedQuality = claimedQuality
            )
            if (onProgress != null) {
                val label = when {
                    stage == "cache" -> "命中缓存 · ${quality.ifBlank { preferQuality }}"
                    stage == STAGE_OFFICIAL_OIAPI -> "oiapi 取链 · ${quality.ifBlank { preferQuality }}"
                    stage.startsWith("official") -> "官方直链 · ${quality.ifBlank { preferQuality }}"
                    outcome == ResolveAttempt.Outcome.SKIPPED -> null // admission/cooldown 等跳过不刷屏
                    else -> "尝试 ${(script.ifBlank { platform }).take(12)} · ${quality.ifBlank { preferQuality }}"
                }
                if (label != null) onProgress(label)
            }
        }
        fun finish(pair: Pair<String, String>): Pair<String, String> {
            if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(runtime.ownerContext) || !requestStillCurrent()) return "" to ""
            rememberAttempts(song.key(), attemptsSnapshot(), runtime.ownerContext)
            return pair
        }
        fun cached(key: String): CachedUrl? {
            val entry = urlCache[key] ?: return null
            if (entry.url.isBlank() || entry.expiresAtEpochMs <= System.currentTimeMillis()) {
                urlCache.remove(key)
                return null
            }
            // Never fulfill a higher-quality request from an unrelated cached lower quality.
            if (key == cacheKey && !qualitySatisfies(entry.actualQuality, preferQuality)) return null
            return entry
        }
        // forceCross 是原曲已经确认不可播/试听/断流后的恢复路径；不得复用原平台旧缓存。
        if (!originOnly && !forceCross) {
            val hit = synchronized(cacheLock) { cached(cacheKey) }
            if (hit != null) {
                // ACCEPT4（修5）：缓存读出同样过出链归一——J 波落盘条目可能含归一
                // 之前的 http 链，放行前先提升 https，探针与身份都以归一链为准。
                val hitUrl = normalizeKgMediaUrl(hit.url)
                // 缓存只保存短期直链；播放复用前仍需确认 CDN 没过期且响应确实是媒体。
                val media = if (purpose == ResolveTicket.Purpose.PLAY || purpose == ResolveTicket.Purpose.PROBE) {
                    validateProbeMedia(hit.platform, hit.actualQuality, hitUrl, 0L, purpose, song.duration)
                } else null
                val currentEvidence = media?.let {
                    TrustedMediaCacheIdentity.contentEvidence(it.httpStatus, it.mime, it.bytes, it.formatHint, "")
                }.orEmpty()
                val identity = TrustedMediaCacheIdentity(
                    owner = ownerKey(runtime.ownerContext), candidate = hit.candidateKey,
                    platform = hit.platform, actualQuality = hit.actualQuality,
                    script = hit.scriptId, url = hitUrl, contentEvidence = hit.contentEvidence
                )
                val currentIdentity = identity.copy(
                    url = hitUrl,
                    contentEvidence = currentEvidence
                )
                if (purpose != ResolveTicket.Purpose.PLAY && purpose != ResolveTicket.Purpose.PROBE || media?.playable == true) {
                    if (TrustedMediaCacheIdentity.canReuse(identity, currentIdentity)) {
                        CacheManager.recordUrlCacheHit(song.key())
                        trace("cache", hit.platform, "cache", hit.actualQuality, 0L, ResolveAttempt.Outcome.HIT)
                        onResolvedMedia?.invoke(currentIdentity)
                        return@withContext finish(hitUrl to hit.actualQuality)
                    }
                }
                synchronized(cacheLock) { urlCache.remove(cacheKey); persistUrlCacheLocked() }
                trace("cache", hit.platform, "cache", hit.actualQuality, 0L, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED, media?.reason.orEmpty().ifBlank { "content_changed" })
            }
            CacheManager.recordUrlCacheMiss(song.key())
        }

        if (!originOnly && !forceCross) try {
            if (SmartSourceStore.rememberCrossPlat()) {
                SmartSourceStore.getRemembered(song.key())?.let { rem ->
                    if (rem.url.startsWith("http")) {
                        // A remembered cross-platform URL is only a hint. It is a short-lived
                        // CDN credential and must not bypass the script resolver indefinitely.
                        // Re-resolve through the current script chain instead of treating it as a hit.
                        trace("remembered", rem.source, "remembered", rem.quality, 0L, ResolveAttempt.Outcome.SKIPPED,
                            ResolveAttempt.Reason.NO_URL, "hint_ignored")
                        SmartSourceStore.forget(song.key())
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        val src = song.source.ifEmpty { "wy" }
        // ACCEPT5（裁定一 + B）：oiapi 后置兜底。位置：脚本链（含跨平台候选内
        // 脚本）之后的全链末位——/music/url 服务端兜底已整条删除（ACCEPT5
        // 删除兜底棒），其后无任何段；准入由调用点以 canAttemptOiapiResolution
        // 判定后经 admitted 传入（跨平台候选以显式 false/false 口径调用该谓词）。
        // 纯本机段。命中先测链：不可播不采（旧形态完全不验，
        // 死链会直接进播放器再失败一轮）；可播则以实测证据定结果档——oiapi 响应
        // 不带任何音质字段，实际档位 = 格式/码率估算（AudioQualityEvidence.
        // estimateDeliveredQuality），无证据时按匿名 mp3 链的保守地板 128k
        // 标注，禁止再拿请求档盖章（真机实证：默认低质链被标「MASTER 成功」）。
        suspend fun tryOiapiFallback(
            target: Song,
            targetSrc: String,
            requestedQuality: String,
            admitted: Boolean
        ): Pair<String, String>? {
            if (!admitted || targetSrc != "wy") return null
            if (!requestStillCurrent()) return null
            val oiapiStarted = System.currentTimeMillis()
            val outcome = try {
                ResolveBlockingStage.run { wangyiOiapiResolveOutcome(target.id) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            val oiapiElapsed = System.currentTimeMillis() - oiapiStarted
            return when (outcome) {
                is WyResolveOutcome.Found -> {
                    val media = validateProbeMedia(targetSrc, requestedQuality, outcome.url, oiapiElapsed, purpose, target.duration)
                    if (!media.playable) {
                        trace(
                            STAGE_OFFICIAL_OIAPI, targetSrc, "oiapi-direct", requestedQuality,
                            System.currentTimeMillis() - oiapiStarted,
                            ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED,
                            media.reason.ifBlank { "media_unverified" }
                        )
                        null
                    } else {
                        val actual = AudioQualityEvidence.estimateDeliveredQuality(
                            media.formatHint, media.bytes, target.duration
                        ) ?: "128k"
                        trace(
                            STAGE_OFFICIAL_OIAPI, targetSrc, "oiapi-direct", actual,
                            System.currentTimeMillis() - oiapiStarted,
                            ResolveAttempt.Outcome.HIT, reason = "oiapi_measured"
                        )
                        outcome.url to actual
                    }
                }
                is WyResolveOutcome.Empty -> {
                    trace(
                        STAGE_OFFICIAL_OIAPI, targetSrc, "oiapi-direct", requestedQuality, oiapiElapsed,
                        ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL,
                        "oiapi_empty:${outcome.detail}"
                    )
                    null
                }
                null -> {
                    trace(
                        STAGE_OFFICIAL_OIAPI, targetSrc, "oiapi-direct", requestedQuality, oiapiElapsed,
                        ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL,
                        "oiapi_empty:transport_unknown"
                    )
                    null
                }
            }
        }
        // 普通官方匿名直连优先；第三方账号登录增强已移除，直连失败继续走脚本链。
        // 仅对 320k 及以下生效；flac/hires/master 仍走脚本音质。
        // tx 例外：匿名官方链结构性失效（F2/R1），不进官方阶段，脚本链独享 26 秒总预算。
        if (canAttemptAnonymousResolutionBeforeScripts(src, preferQuality, originOnly, forceCross)) {
            // ACCEPT4：官方阶段自此失败也打点（此前只记 HIT）——直连在真机网络层
            // 失败时完全不留痕，落入脚本链后与脚本失败无从区分，全域失效时无法
            // 定位断点在直连还是脚本。EMPTY 行只记事实（平台/通道/耗时），不改回落语义。
            // 批8b kg 直连试点：客户端直打酷狗官方接口；失败/空原样回落脚本链（/music/url 已降为全链末位兜底）。
            if (src == "kg") {
                val directStarted = System.currentTimeMillis()
                val directUrl = try {
                    ResolveBlockingStage.run { kugouDirectResolveUrl(song.id) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                if (!directUrl.isNullOrBlank()) {
                    // ACCEPT6（qqslow）：直连命中不再零校验交付——过媒体探针+时长
                    // 完整性（与 oiapi「命中先测链」同口径）；不过则落脚本链。
                    val media = validateProbeMedia(src, preferQuality, directUrl, System.currentTimeMillis() - directStarted, purpose, song.duration)
                    if (media.playable) {
                        // ACCEPT6（mgquality）：直连命中同按实测盖章（kg 出链由 FileHash
                        // 定文件，偏好档只是声称；探针证据冲突时以实测为准，口径同脚本段）。
                        val stampedQ = AudioQualityEvidence.stampedPlaybackQuality(
                            preferQuality, media.magicFormat, media.formatHint, media.formatEvidence,
                            media.bytes, song.duration
                        )
                        trace("official-direct", src, "kg-direct", stampedQ, 0L, ResolveAttempt.Outcome.HIT, claimedQuality = if (stampedQ != preferQuality) preferQuality else "")
                        return@withContext finish(directUrl to stampedQ)
                    }
                    trace("official-direct", src, "kg-direct", preferQuality, System.currentTimeMillis() - directStarted, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED, media.reason.ifBlank { "media_unverified" })
                } else {
                    trace("official-direct", src, "kg-direct", preferQuality, System.currentTimeMillis() - directStarted, ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL, "direct_empty")
                }
            }
            // 批8c mg 直连：客户端直打咪咕官方接口（服务端 /music/url 无 mg 一等分支，直连升一等源）；
            // 失败/空原样回落脚本链（/music/url 已降为全链末位兜底）。
            if (src == "mg") {
                val directStarted = System.currentTimeMillis()
                val directUrl = try {
                    ResolveBlockingStage.run { miguDirectResolveUrl(song.id) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                if (!directUrl.isNullOrBlank()) {
                    // ACCEPT6（qqslow）：同 kg 口径——直连命中先测链再交付。
                    val media = validateProbeMedia(src, preferQuality, directUrl, System.currentTimeMillis() - directStarted, purpose, song.duration)
                    if (media.playable) {
                        // ACCEPT6（mgquality）：同 kg 口径——直连命中按实测盖章。
                        val stampedQ = AudioQualityEvidence.stampedPlaybackQuality(
                            preferQuality, media.magicFormat, media.formatHint, media.formatEvidence,
                            media.bytes, song.duration
                        )
                        trace("official-direct", src, "mg-direct", stampedQ, 0L, ResolveAttempt.Outcome.HIT, claimedQuality = if (stampedQ != preferQuality) preferQuality else "")
                        return@withContext finish(directUrl to stampedQ)
                    }
                    trace("official-direct", src, "mg-direct", preferQuality, System.currentTimeMillis() - directStarted, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED, media.reason.ifBlank { "media_unverified" })
                } else {
                    trace("official-direct", src, "mg-direct", preferQuality, System.currentTimeMillis() - directStarted, ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL, "direct_empty")
                }
            }
            // 批8d kw 直连：客户端直打酷我官方 anti.s（br 级联 + 500KB probe 闸门，等价服务端
            // kuwo_url_by_rid 官方段，music_server.py:1793-1847）；失败/空原样回落脚本链（/music/url 已降为全链末位兜底）。
            if (src == "kw") {
                val directStarted = System.currentTimeMillis()
                val directUrl = try {
                    // preferQuality（320k/128k…）→ br 数值（320/128…），等价服务端 normalize_br 可达域（≤320k 直连闸门内）。
                    ResolveBlockingStage.run { kuwoDirectResolveUrl(song.id, preferQuality.removeSuffix("k").ifBlank { "320" }, song.duration) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                if (!directUrl.isNullOrBlank()) {
                    trace("official-direct", src, "kw-direct", preferQuality, 0L, ResolveAttempt.Outcome.HIT)
                    return@withContext finish(directUrl to preferQuality)
                }
                trace("official-direct", src, "kw-direct", preferQuality, System.currentTimeMillis() - directStarted, ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL, "direct_empty")
            }
            // 批8e wy 直连：客户端匿名直打网易云 outer/url（不跟随重定向，自读 Location）。
            // ACCEPT5（A）：改走结构化结果——失败 detail（跳转 404/无跳转应答/传输
            // 超时/网络失败等）进 attempts，不再与其它失败压成同一个 direct_empty。
            // ACCEPT5（裁定一）：oiapi 不再于官方阶段出场——它已降为脚本链之后
            // 的本机兜底（tryOiapiFallback）；本阶段 wy 只剩 outer 一段。
            // 平台特化，勿改：wy 直连取链专属实现（mg/kw/kg/tx 各有独立直连分支），非 UI 能力判断。
            if (src == "wy") {
                val outerStarted = System.currentTimeMillis()
                val outerOutcome = try {
                    ResolveBlockingStage.run { wangyiOuterResolveOutcome(song.id) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                when (outerOutcome) {
                    is WyResolveOutcome.Found -> {
                        // ACCEPT6（qqslow）：outer 命中同过探针+时长完整性（同 kg/mg 口径）。
                        val media = validateProbeMedia(src, preferQuality, outerOutcome.url, System.currentTimeMillis() - outerStarted, purpose, song.duration)
                        if (media.playable) {
                            // ACCEPT6（mgquality）：同 kg 口径——outer 命中按实测盖章
                            // （outer 实际只发标准档，偏好 320k 时旧盖章即虚标）。
                            val stampedQ = AudioQualityEvidence.stampedPlaybackQuality(
                                preferQuality, media.magicFormat, media.formatHint, media.formatEvidence,
                                media.bytes, song.duration
                            )
                            trace("official-direct", src, "wy-direct", stampedQ, 0L, ResolveAttempt.Outcome.HIT, claimedQuality = if (stampedQ != preferQuality) preferQuality else "")
                            return@withContext finish(outerOutcome.url to stampedQ)
                        }
                        trace("official-direct", src, "wy-direct", preferQuality, System.currentTimeMillis() - outerStarted, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED, media.reason.ifBlank { "media_unverified" })
                    }
                    is WyResolveOutcome.Empty -> trace(
                        "official-direct", src, "wy-direct", preferQuality,
                        System.currentTimeMillis() - outerStarted,
                        ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL,
                        "direct_empty:${outerOutcome.detail}"
                    )
                    null -> trace(
                        "official-direct", src, "wy-direct", preferQuality,
                        System.currentTimeMillis() - outerStarted,
                        ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL,
                        "direct_empty:transport_unknown"
                    )
                }
            }
            // /music/url 服务端取链曾在此处（官方阶段内）出场，I 波先降为全链
            // 末位兜底，ACCEPT5 已整条删除——直连失败直接走脚本链。
        }
        var pool = try {
            SmartSourceStore.orderedScripts(src, scripts)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scripts.filter { it.enabled && it.code.isNotEmpty() }
        }
        pool = pool.filter {
            it.code.isNotEmpty() && it.id !in skipScripts && !isScriptBad(song.key(), it.id, runtime.ownerContext)
        }
        if (pool.isEmpty()) {
            pool = scripts.filter { it.enabled && it.code.isNotEmpty() && it.id !in skipScripts && !isScriptBad(song.key(), it.id, runtime.ownerContext) }
        }
        if (pool.isEmpty()) {
            pool = scripts.filter { it.code.isNotEmpty() && it.id !in skipScripts && !isScriptBad(song.key(), it.id, runtime.ownerContext) }
        }
        if (pool.isEmpty()) {
            // 细分 admission 失败根因：区分“没有启用脚本 / 启用脚本代码全空 / 其余不可用”。
            val enabledCount = scripts.count { it.enabled }
            val enabledCodeEmpty = scripts.count { it.enabled && it.code.isEmpty() }
            val allCodeEmpty = scripts.isNotEmpty() && scripts.all { it.code.isEmpty() }
            val (rc, rs) = when {
                enabledCount == 0 -> ResolveAttempt.Reason.NO_ENABLED_SCRIPT to "no_enabled_script"
                enabledCodeEmpty > 0 && enabledCodeEmpty == enabledCount -> ResolveAttempt.Reason.SCRIPT_CODE_EMPTY to "script_code_empty"
                allCodeEmpty -> ResolveAttempt.Reason.SCRIPT_CODE_EMPTY to "script_code_empty"
                else -> ResolveAttempt.Reason.SCRIPT_DISABLED to "script_disabled"
            }
            trace("admission", src, "", preferQuality, 0L, ResolveAttempt.Outcome.SKIPPED, rc, rs)
            // 无可用脚本时本机链只剩官方直连（已试）与 oiapi 本机兜底（ACCEPT5：
            // 脚本为空时它就是 wy 的下一顺位）——给出场机会，否则无脚本用户会
            // 从「可走本机直连/oiapi」回退成「完全不可播」。/music/url 服务端
            // 兜底已整条删除（ACCEPT5 删除兜底棒），oiapi 之后即失败收尾。
            val admissionOiapi = tryOiapiFallback(
                song, src, preferQuality,
                canAttemptOiapiResolution(src, preferQuality, originOnly, forceCross)
            )
            if (admissionOiapi != null) return@withContext finish(admissionOiapi)
            rememberAttempts(song.key(), attemptsSnapshot(), runtime.ownerContext)
            return@withContext "" to ""
        }

        // 关闭智能换源：只试平台绑定/激活脚本，不做级联
        val smartOn = try { SmartSourceStore.smartEnabled() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; true }
        if (!smartOn && !forceCross) {
            pool = pool.take(1)
        }

        val prio = SRC_PRIORITY[src] ?: emptyList()
        if (prio.isNotEmpty() && pool.size > 1) {
            pool = pool.mapIndexed { idx, sc ->
                val hit = prio.indexOfFirst { p -> sc.id.contains(p) || sc.name.contains(p) }
                val score = idx * 1000 + (if (hit < 0) 500 else hit)
                score to sc
            }.sortedBy { it.first }.map { it.second }
        }
        pool = pool.filterNot {
            it.name.contains("LX官方") || it.id.contains("LX官方") || it.name.contains("独家音源")
        } + pool.filter {
            it.name.contains("LX官方") || it.id.contains("LX官方") || it.name.contains("独家音源")
        }

        // 已确认原平台失效时，不能在跨平台失败后再退回原平台坏链。
        // ACCEPT5（策略 conformance）：计划构造收敛到纯函数 resolveStagePlan——
        // 只由模式与用户设置决定，一次定死；任何记忆机制（裁决/超时/探针）
        // 都不参与构造，只能在执行时把计划内某一步缩成跳过行。
        val stages = try {
            resolveStagePlan(originOnly, forceCross, smartOn, SmartSourceStore.stageOrder())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (forceCross) listOf(SmartSourceStore.STAGE_CROSS_PLAT, SmartSourceStore.STAGE_CROSS_SCRIPT)
            else SmartSourceStore.DEFAULT_STAGES.toList()
        }
        // ACCEPT5（策略 conformance）：完整计划留痕供策略行宣布——官方直连
        // （准入时）→ 阶段计划 → oiapi 本机兜底（准入时，wy 的计划终点）。
        // /music/url 服务端兜底段已随该腿整条删除（ACCEPT5 删除兜底棒），
        // 计划里不再有服务端段。执行在中途成功不改计划内容。
        rememberPlan(
            song.key(),
            buildList {
                if (canAttemptAnonymousResolutionBeforeScripts(src, preferQuality, originOnly, forceCross)) {
                    add("official-direct")
                }
                addAll(stages)
                if (canAttemptOiapiResolution(src, preferQuality, originOnly, forceCross)) {
                    add(STAGE_OFFICIAL_OIAPI)
                }
            },
            runtime.ownerContext
        )
        val use128 = try {
            SmartSourceStore.fallback128k()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            true
        }

        val platformPreferQuality = try {
            SmartSourceStore.platformPlayQuality(src, preferQuality)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            preferQuality
        }
        val preferCascade = Qualities.cascade(platformPreferQuality).ifEmpty { listOf("320k", "flac") }
        val highQs = preferCascade.filter { it != "128k" && it != "128" }.ifEmpty {
            listOf(preferQuality.ifEmpty { "320k" })
        }
        val lowQs = if (use128) listOf("128k") else emptyList()

        // 播放链路：优先首脚本+首选音质快速出链（对齐 Sollin 体感），失败再降质/换脚本。
        // 跨平台阶段还包含搜索与候选匹配，12 秒会把正常脚本提前截断成“取链超时”。
        // 单 WebView 取链必须有硬返回上限：旧版 20~36 秒会把正常失败表现成
        // “一直取链中”，并且还会让后续脚本排队。跨平台恢复也只能在同一轮预算内完成。
        // 单脚本真实多后端回退经常超过 2.8 秒；现有外部脚本实测 QQ/网易需约 3.7 秒。
        // 15 秒会在首脚本的两档尝试中耗尽，后续已可用脚本永远没有入场机会。
        // 全局仍受硬上限、每脚本独立 watchdog 与最多四个脚本约束。
        val globalMs = 26_000L
        val started = System.currentTimeMillis()
        val info = ResolveBlockingStage.run { buildMusicInfo(song) }
        // cascade 上限统一为 4；跨平台分支也必须共享这个上限，不能因智能换源再扩成 5。
        val topScripts = pool.take(4)
        val primary = topScripts.firstOrNull()
        val restScripts = if (topScripts.size > 1) topScripts.drop(1) else emptyList()
        // 音质阶梯：用户有效最高档居首，之后严格逐档下降（master→flac→320k→128k）；
        // 降级只能是上一档失败后的下一步，绝不把 320k 当默认第二枪（旧 fastQs 曾让
        // ORIGIN 阶段 [pref, 320k] 两枪定音，flac 等中间档永远轮不到）。
        val stageQs = scriptStageQualities(platformPreferQuality, use128)

        // ACCEPT4（修2）：本轮内已软超时的脚本集合——同轮跨阶段走到时直接跳过，
        // 不再陪跑；与 ScriptTimeoutMemory 的跨轮短记忆是同一纪律的两层。
        val resolveTimedOutScripts = mutableSetOf<String>()
        // ACCEPT6（qqslow）：本轮已自报停服的（脚本×平台）——同轮后续阶段对
        // 该脚本同平台剩余档位直接记裁决跳过，不再逐档付费（真机实证：首遇
        // 停服时 FLAC 仍白付约 1.3s）。跨歌曲粒度不动（仍脚本×平台×音质）。
        val resolveTerminalScripts = mutableSetOf<String>()

        // ACCEPT5（E2）：本轮执行账本——慢死的第二形态是「同脚本同档被多个阶段
        // 重复执行 + 对一首歌无资源的脚本逐档串行空转」（真机实证：单脚本在一首
        // 歌里执行 8 次全空、烧掉约 13s，其中整段阶梯被两个阶段各走一遍）。
        // ① executedScriptAttempts：(脚本×平台×音质) 本轮只实际执行一次，后续
        // 阶段再遇到直接静默跳过（裁决跳过同理，只记第一行）；
        // ② emptyScriptStrikes：同脚本同平台在本轮内无果执行（语义空/传输失败/
        // 执行异常/探针死链拒收）累计 2 次后，其余档位本轮跳过——聚合脚本对一
        // 首歌有无资源与档位弱相关，逐档空转没有信息增益。仅本轮有效，不落盘、
        // 不入裁决（跨轮记忆仍由裁决存储与超时短记忆按各自纪律负责）。
        val executedScriptAttempts = mutableSetOf<String>()
        val emptyScriptStrikes = mutableMapOf<String, Int>()
        fun noteEmptyStrike(sc: Script, source: String) {
            val k = "${sc.id}|$source"
            emptyScriptStrikes[k] = (emptyScriptStrikes[k] ?: 0) + 1
        }

        suspend fun tryScript(
            sc: Script, source: String, music: Song, qs: List<String>, cross: Boolean,
            deadlineMs: Long
        ): Pair<String, String>? {
            if (!requestStillCurrent()) return null
            if (System.currentTimeMillis() - started > globalMs - 300) return null
            // ACCEPT4（修1/修2）：阶段截止已过或该脚本正处超时短记忆期——不发请求，
            // 把剩余预算留给后续阶段（降音质/跨脚本/跨平台转移腿）。
            if (System.currentTimeMillis() > deadlineMs) return null
            if (sc.id in resolveTimedOutScripts) {
                trace(
                    if (cross) "cross" else "origin", source, sc.id, qs.firstOrNull().orEmpty(), 0L,
                    ResolveAttempt.Outcome.SKIPPED,
                    ResolveAttempt.Reason.SCRIPT_TIMEOUT_MEMORY, "timeout_memory_resolve"
                )
                return null
            }
            if (ScriptTimeoutMemory.isDegraded(runtime.ownerContext, sc, source)) {
                trace(
                    if (cross) "cross" else "origin", source, sc.id, qs.firstOrNull().orEmpty(), 0L,
                    ResolveAttempt.Outcome.SKIPPED,
                    ResolveAttempt.Reason.SCRIPT_TIMEOUT_MEMORY, "timeout_memory"
                )
                return null
            }
            val mi = if (music.key() == song.key()) info else ResolveBlockingStage.run { buildMusicInfo(music) }
            for (q in qs) {
                if (!requestStillCurrent()) return null
                if (System.currentTimeMillis() - started > globalMs - 250) break
                if (System.currentTimeMillis() > deadlineMs) break
                val execKey = "${sc.id}|$source|${q.lowercase()}"
                // ACCEPT5（策略 conformance）：本轮内已软超时的脚本，其余档位在
                // **原定位置**逐档记跳过行——计划是骨架：超时只能把这一步缩成
                // 跳过，不许让阶梯后段凭空消失（旧形态 return null 直接截断，
                // 表现为「某脚本只降到 FLAC 就断、另一脚本却走满四档」的不一致）。
                if (sc.id in resolveTimedOutScripts) {
                    if (execKey !in executedScriptAttempts) {
                        executedScriptAttempts += execKey
                        trace(
                            if (cross) "cross" else "origin", source, sc.id, q, 0L,
                            ResolveAttempt.Outcome.SKIPPED,
                            ResolveAttempt.Reason.SCRIPT_TIMEOUT_MEMORY, "timeout_memory_resolve"
                        )
                    }
                    continue
                }
                // ACCEPT5（策略 conformance）：本轮同组合已在更早阶段执行过——
                // 在本阶段的原定位置记一行跳过（不重发请求），使重复计划步在
                // 诊断里可解释，而不是出现两次执行或凭空消失。
                if (execKey in executedScriptAttempts) {
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.ALREADY_ATTEMPTED, "already_attempted_resolve"
                    )
                    continue
                }
                if ((emptyScriptStrikes["${sc.id}|$source"] ?: 0) >= 2) {
                    executedScriptAttempts += execKey
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.SCRIPT_EMPTY_MEMORY, "empty_memory_resolve"
                    )
                    continue
                }
                // H1-a：已被终局裁决的（脚本×平台×音质）组合直接跳过——不发请求，
                // 同一首歌内跨阶段走到这里同样跳过；裁决只来自脚本自报终局错误。
                if (ScriptVerdictStore.isVerdicted(runtime.ownerContext, sc, source, q)) {
                    executedScriptAttempts += execKey
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.SCRIPT_VERDICT, "verdict_cached"
                    )
                    continue
                }
                // ACCEPT5（D）：探针死链短记忆——该组合近期被探针实测为确定性
                // 死链（HTTP 401/403/404/410），30 分钟内在原定位置跳过，不再
                // 为同一条死链重复付解析+探针的钱（跨歌生效，与裁决同粒度）。
                if (ScriptProbeMemoryStore.isMemorized(runtime.ownerContext, sc, source, q)) {
                    executedScriptAttempts += execKey
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.SCRIPT_PROBE_MEMORY, "probe_memory"
                    )
                    continue
                }
                // ACCEPT6（qqslow）：本轮级联——同脚本同平台已自报停服，剩余
                // 档位在原定位置记裁决跳过（跨歌曲粒度不变，理由见声明处）。
                if ("${sc.id}|$source" in resolveTerminalScripts) {
                    executedScriptAttempts += execKey
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.SCRIPT_VERDICT, "verdict_in_round"
                    )
                    continue
                }
                // ACCEPT6（qqslow）：慢速语义空返短记忆——该脚本×平台近期多次
                // ≥3s 语义空返，本次在原定位置跳过（10 分钟滑动窗口，进程内）。
                if (ScriptSlowEmptyMemory.shouldSkip(runtime.ownerContext, sc, source)) {
                    executedScriptAttempts += execKey
                    trace(
                        if (cross) "cross" else "origin", source, sc.id, q, 0L,
                        ResolveAttempt.Outcome.SKIPPED,
                        ResolveAttempt.Reason.SCRIPT_SLOW_EMPTY_MEMORY, "slow_empty_memory"
                    )
                    continue
                }
                executedScriptAttempts += execKey
                val attemptStarted = System.currentTimeMillis()
                try {
                    // ACCEPT4（修1）：本次尝试窗口 = min(软超时, 距阶段截止的剩余预算)；
                    // 剩余不足最小切片直接收工，不再出现"4 次 6s 尝试吃满全局预算"。
                    val windowMs = ResolveStageBudget.attemptWindowMs(
                        deadlineMs - System.currentTimeMillis(), SCRIPT_SOFT_TIMEOUT_MS
                    )
                    if (windowMs <= 0L) break
                    val fullWindow = windowMs >= SCRIPT_SOFT_TIMEOUT_MS
                    val u = runtime.getMusicUrl(sc.id, sc.code, mi, source, q, purpose = purpose, scriptMeta = scriptMetaJson(sc), attemptTimeoutMs = windowMs)
                    val attemptElapsed = System.currentTimeMillis() - attemptStarted
                    // ACCEPT4（修5）：脚本出链与直连/kg/kw 同口径归一——http 且宿主
                    // 支持 https 时升级，堵 SW-03 播放器汇点明文门对脚本链的二次杀伤；
                    // 汇点门本身不动（SW-03 守卫面不扩大）。
                    val mediaUrl = normalizeKgMediaUrl(u)
                    if (mediaUrl.startsWith("http")) {
                        // LX 协议只保证脚本返回 URL；播放提交前必须再过一次真实媒体响应门禁，
                        // 否则 HTML/JSON/403/试听短片会被误认为“取链成功”，直到 ExoPlayer 才报错。
                        val media = validateProbeMedia(source, q, mediaUrl, attemptElapsed, purpose, music.duration)
                        if (!media.playable) {
                            // ACCEPT5（C/E2）：死链/不可播计入本轮无果——同脚本连吃
                            // 两次后其余档位不再逐档陪探（逐档是不同 URL，首两次仍
                            // 逐个验，给「只有高档死链」的脚本留出低档机会）。
                            noteEmptyStrike(sc, source)
                            // ACCEPT5（D）：确定性 HTTP 死链另入持久短记忆（30
                            // 分钟、同粒度）——下一首同平台歌曲不再重付这条死链
                            // 的解析+探针成本；不确定的拒收（HTML/试听/无状态）
                            // 不入记忆，各归其层。
                            if (media.httpStatus in ScriptProbeMemoryStore.DEFINITIVE_HTTP_STATUSES) {
                                ScriptProbeMemoryStore.recordProbeFailure(
                                    runtime.ownerContext, sc, source, q, media.httpStatus
                                )
                            }
                            trace(
                                if (cross) "cross" else "origin", source, sc.id, q,
                                System.currentTimeMillis() - attemptStarted,
                                ResolveAttempt.Outcome.SKIPPED,
                                ResolveAttempt.Reason.MEDIA_UNVERIFIED,
                                media.reason.ifBlank { "media_unverified" },
                                runtime.lastRoute(sc.id)
                            )
                            continue
                        }
                        val contentEvidence = TrustedMediaCacheIdentity.contentEvidence(
                            media.httpStatus, media.mime, media.bytes, media.formatHint, ""
                        )
                        // ACCEPT6（mgquality）：脚本命中自此按实测盖章（与 oiapi 段同
                        // 纪律）——脚本是黑盒，阶梯档 q 只是声称；探针已握有魔数/格式
                        // 线索/总字节，证据与声称冲突时以实测档为结果档，声称档留存
                        // claimedQuality 供路径弹层标注「声称 X · 实测 Y」。裁决/超时/
                        // 探针记忆等一切状态机仍按 q 记账（那是请求侧事实），不因盖章
                        // 改变——本改动只动交付档位的认定与展示。
                        val stampedQ = AudioQualityEvidence.stampedPlaybackQuality(
                            q, media.magicFormat, media.formatHint, media.formatEvidence,
                            media.bytes, music.duration
                        )
                        trace(if (cross) "cross" else "origin", source, sc.id, stampedQ, attemptElapsed, ResolveAttempt.Outcome.HIT, route = runtime.lastRoute(sc.id), claimedQuality = if (stampedQ != q) q else "")
                        // H1-a：该组合真实出链成功，任何既有裁决立即失效。
                        ScriptVerdictStore.clearVerdict(runtime.ownerContext, sc, source, q)
                        // ACCEPT4（修2）：真实出链成功同样清除该脚本的超时短记忆。
                        ScriptTimeoutMemory.clearOnSuccess(runtime.ownerContext, sc, source)
                        // ACCEPT5（D）：成功同样清除该组合的探针死链记忆。
                        ScriptProbeMemoryStore.clearOnSuccess(runtime.ownerContext, sc, source, q)
                        // ACCEPT6（qqslow）：真实出链成功同样清除该脚本的慢空返短记忆。
                        ScriptSlowEmptyMemory.clearOnSuccess(runtime.ownerContext, sc, source)
                        // 原生网络已按请求取消；迟到 JS 仍需身份校验，禁止回写状态。
                        if (!requestStillCurrent()) return null
                        val pair = mediaUrl to stampedQ
                        val verifiedIdentity = TrustedMediaCacheIdentity(
                            ownerKey(runtime.ownerContext), candidateIdentity(music), source,
                            stampedQ, sc.id, mediaUrl, contentEvidence
                        )
                        onResolvedMedia?.invoke(verifiedIdentity)
                        synchronized(cacheLock) {
                            val cached = CachedUrl(
                                url = mediaUrl,
                                actualQuality = stampedQ,
                                scriptId = sc.id,
                                candidateKey = candidateIdentity(music),
                                platform = source,
                                contentEvidence = contentEvidence
                            )
                            // SW-02-13：签名链已过期则不入缓存，避免刚写入即被读路径当作有效条目。
                            if (cached.expiresAtEpochMs > System.currentTimeMillis()) urlCache[cacheKey] = cached
                            trimCacheLocked()
                            persistUrlCacheLocked()
                        }
                        // Resolver 只产出身份化结果与缓存；队列 Song 由当前播放事务在
                        // commit 点写入。否则预取/迟到 WebView 会把裸直链和旧音质污染 UI。
                        if (cross) {
                            try {
                                if (SmartSourceStore.rememberCrossPlat()) {
                                    SmartSourceStore.remember(
                                        song.key(),
                                        SmartSourceStore.Remembered(
                                            id = music.id,
                                            source = music.source,
                                            url = mediaUrl,
                                            quality = stampedQ,
                                            name = music.name,
                                            singer = music.singer
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                            }
                        }
                        markWinningScript(song.key(), sc.id, runtime.ownerContext)
                        return pair
                    } else {
                        // 脚本返回空：区分网络层失败（timeout/policy/too_large/network）与脚本真空链。
                        val fr = runtime.lastFailureReason(sc.id)
                        val route = runtime.lastRoute(sc.id)
                        // H1-a 分类通道：脚本自报错误优先于一切推断——先消费并分类。
                        val explicitError = runtime.consumeLastExplicitError(sc.id)
                        when {
                            // H2-b 软超时：瞬时失败记账，结束该脚本本轮（下一档不再陪跑）。
                            // ACCEPT4（修2）：软超时自此有短记忆——本轮集合立即生效，跨轮由
                            // ScriptTimeoutMemory 承接；仅当本次拿到完整软超时窗口才记跨轮账，
                            // 被阶段截止压缩出的"伪超时"不污染记忆（那是预算形态，非脚本之罪）。
                            fr == "soft_timeout" -> {
                                resolveTimedOutScripts += sc.id
                                if (fullWindow) {
                                    ScriptTimeoutMemory.recordTimeout(runtime.ownerContext, sc, source)
                                }
                                trace(
                                    if (cross) "cross" else "origin", source, sc.id, q, attemptElapsed,
                                    ResolveAttempt.Outcome.TIMEOUT,
                                    ResolveAttempt.Reason.SOFT_TIMEOUT, "soft_timeout", route
                                )
                                // ACCEPT5（策略 conformance）：不再 return 截断
                                // 本次调用的阶梯——回到循环顶部后，该脚本其余
                                // 档位会被超时门逐档记成计划内跳过行，阶梯形态
                                // 对每个脚本一致（快的走满、慢的以跳过行走满）。
                                continue
                            }
                            // 脚本自报终局错误：写持久裁决，本轮不再试该脚本其余音质
                            // （其余组合会在后续阶段/歌曲被尝试时各自落裁，粒度不放大）。
                            // ACCEPT4（修3）：裁决 TTL 分级——硬终局（停服/版本不支持）7 天；
                            // 软终局（下线/deprecated 等措辞含糊类）只记 30 分钟短裁决，
                            // 防止可能瞬时/误报的失败被 7 天化石化（Sollin 纪律：只有
                            // 可证伪的终局组合才配长记忆）。
                            explicitError.isNotEmpty() && ScriptTerminalErrorClassifier.isTerminal(explicitError) -> {
                                if (ScriptTerminalErrorClassifier.isHardTerminal(explicitError)) {
                                    ScriptVerdictStore.recordTerminal(runtime.ownerContext, sc, source, q)
                                } else {
                                    ScriptVerdictStore.recordShortVerdict(runtime.ownerContext, sc, source, q)
                                }
                                trace(
                                    if (cross) "cross" else "origin", source, sc.id, q, attemptElapsed,
                                    ResolveAttempt.Outcome.ERROR,
                                    ResolveAttempt.Reason.SCRIPT_TERMINAL, "script_terminal", route
                                )
                                resolveTerminalScripts += "${sc.id}|$source"
                                return null
                            }
                            else -> {
                                // ACCEPT5（E2）：语义空/传输失败计入本轮无果熔断。
                                noteEmptyStrike(sc, source)
                                // ACCEPT6（qqslow）：慢速语义空入短记忆——仅 handler
                                // 已解析的空（fr 为空 = 无传输失败分类）才记账；网络/
                                // 策略类空不入（ACCEPT4 负缓存纪律）。耗时下限在记忆层。
                                if (fr.isEmpty()) {
                                    ScriptSlowEmptyMemory.recordSlowEmpty(runtime.ownerContext, sc, source, attemptElapsed)
                                }
                                trace(if (cross) "cross" else "origin", source, sc.id, q, attemptElapsed, ResolveAttempt.Outcome.EMPTY, ResolveAttempt.Reason.NO_URL, if (fr.isEmpty()) "no_url" else fr, route)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val attemptElapsed = System.currentTimeMillis() - attemptStarted
                    // ACCEPT5（E2）：执行异常同样计入本轮无果熔断。
                    noteEmptyStrike(sc, source)
                    trace(if (cross) "cross" else "origin", source, sc.id, q, attemptElapsed, ResolveAttempt.Outcome.ERROR, ResolveAttempt.Reason.SCRIPT_ERROR, "script_error", runtime.lastRoute(sc.id))
                }
            }
            return null
        }

        // ACCEPT4（修1）：阶段链预算——全局 26s 先扣收尾预留 TAIL_RESERVE_MS，
        // 其余按 ResolveStageBudget 加权切成累计截止，保证每个阶段（含跨平台
        // 转移腿）都有自己的时间片，前段串行尝试吃不掉后段。ACCEPT5 删除兜底
        // 棒：原为服务端末位兜底预留的 5s 随该腿删除而消灭（恒为原未准入形态）。
        val chainBudgetMs = (globalMs - TAIL_RESERVE_MS).coerceAtLeast(MIN_CHAIN_BUDGET_MS)
        val stageDeadlineAt = ResolveStageBudget.stageDeadlineOffsets(stages, chainBudgetMs)
            .map { started + it }

        val result = withTimeoutOrNull(chainBudgetMs + STAGE_CHAIN_SLACK_MS) {
            for ((stageIndex, stage) in stages.withIndex()) {
                if (System.currentTimeMillis() - started > globalMs - 400) break
                val stageDeadline = stageDeadlineAt.getOrElse(stageIndex) { started + chainBudgetMs }
                when (stage) {
                    SmartSourceStore.STAGE_ORIGIN -> {
                        val sc = primary ?: continue
                        // 首选音质先行两枪：首选档 + 紧邻的下一档（阶梯顺序，见 stageQs）；
                        // 第二枪不再直跳 320k——那会让高档用户的 flac 尝试永远轮不到。
                        val hit = tryScript(sc, src, song, stageQs.take(2), false, stageDeadline)
                        if (hit != null) return@withTimeoutOrNull hit
                    }
                    SmartSourceStore.STAGE_QUALITY -> {
                        val qs = stageQs
                        val scs = listOfNotNull(primary) + restScripts.take(1)
                        for (sc in scs) {
                            val hit = tryScript(sc, src, song, qs, false, stageDeadline)
                            if (hit != null) return@withTimeoutOrNull hit
                        }
                    }
                    SmartSourceStore.STAGE_CROSS_SCRIPT -> {
                        val qs = stageQs.ifEmpty { listOf("320k", "128k") }
                        for (sc in restScripts) {
                            if (isScriptBad(song.key(), sc.id, runtime.ownerContext)) {
                                trace(SmartSourceStore.STAGE_CROSS_SCRIPT, src, sc.id, stageQs.firstOrNull().orEmpty(), 0L, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.SCRIPT_COOLDOWN, "script_cooldown")
                                continue
                            }
                            val hit = tryScript(sc, src, song, qs, false, stageDeadline)
                            if (hit != null) return@withTimeoutOrNull hit
                            // 单次失败不永久熔断，留给最终兜底再试
                        }
                    }
                    SmartSourceStore.STAGE_CROSS_PLAT -> {
                        // 导入检测的“原平台可播”只允许原平台脚本/音质回退，绝不把跨平台候选算进去。
                        if (originOnly) continue
                        // ACCEPT5（裁定二）：候选平台迭代序只来自用户排定的平台
                        // 顺序（SmartSourceStore 存储序）；存储不可读时回退同一份
                        // 默认序（defaultPlatformOrder），不在此另写写死平台表。
                        val plats = try {
                            crossPlatTargetsInUserOrder(SmartSourceStore.enabledPlatformsInOrder(), src)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            crossPlatTargetsInUserOrder(SmartSourceStore.defaultPlatformOrder(), src)
                        }
                        val kw = listOf(song.name, song.singer).filter { it.isNotBlank() }.joinToString(" ")
                            .ifBlank { song.name }
                        if (kw.isBlank()) continue
                        // ACCEPT5（C）：源曲缺时长时，从源平台自身直连搜索按同 id
                        // 补齐后再匹配——同平台同 id 即同一条目，其时长是权威事实
                        // 而非猜测；补不到保持 0，匹配器照旧按 duration_missing
                        // 拒绝（LX 严配纪律不放宽）。只服务本阶段匹配，不回写歌曲。
                        val matchSource = if (song.duration > 0L) song else try {
                            val ownResults = ResolveBlockingStage.run { crossSourceSearchSongs(src, kw) }
                            ownResults.firstOrNull { it.id == song.id && it.duration > 0L }
                                ?.let { song.copy(duration = it.duration) } ?: song
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            song
                        }
                        // ACCEPT5（E3）：拒绝行治理——逐条最多记 3 条，其余汇总为
                        // 一行（主因 + 总数）。旧形态逐条全记（单平台可达 16 行），
                        // attempts 24 行窗口被单一平台刷满，先试的平台痕迹被挤掉、
                        // 看起来像「只跳了最后一个平台」。
                        fun traceMatchRejects(plat: String, results: List<Song>) {
                            val rejects = results.mapNotNull { cand -> SongMatcher.rejectReason(matchSource, cand) }
                            rejects.take(3).forEach { r ->
                                trace(
                                    "cross", plat, "match", "", 0L,
                                    ResolveAttempt.Outcome.SKIPPED,
                                    ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED,
                                    r
                                )
                            }
                            if (rejects.size > 3) {
                                val dominant = rejects.groupingBy { it }.eachCount()
                                    .maxByOrNull { it.value }?.key.orEmpty()
                                trace(
                                    "cross", plat, "match", "", 0L,
                                    ResolveAttempt.Outcome.SKIPPED,
                                    ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED,
                                    "reject_summary:$dominant:${rejects.size}"
                                )
                            }
                        }
                        for (plat in plats) {
                            val platPreferQ = try { SmartSourceStore.platformPlayQuality(plat, preferQuality) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; preferQuality }
                            val platCascade = Qualities.cascade(platPreferQ).ifEmpty { listOf("320k", "128k") }
                            val qs = (platCascade.filter { it != "128k" && it != "128" }.take(2) + lowQs).distinct().ifEmpty { listOf("320k", "128k") }
                            if (System.currentTimeMillis() - started > globalMs - 600) break
                            val searchResults = try {
                                if (plat in MusicSourceRegistry.platformIds) {
                                    ResolveBlockingStage.run { crossSourceSearchSongs(plat, kw) }
                                } else {
                                    ApiClient.searchCancellable(kw, plat)
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                emptyList<Song>()
                            }
                            // 可解释淘汰：被身份门禁拒绝的候选写入诊断（字段级原因
                            // + 汇总行），用户能看到“为什么没换到这个平台”。
                            traceMatchRejects(plat, searchResults)
                            val cands = SongMatcher.rank(matchSource, searchResults, limit = 2)
                            // ACCEPT4（修4）曾在此补「本地全不匹配 → 服务端搜索再
                            // 匹配一次」的跨源逃逸腿；ACCEPT6 跨源腿退役棒已整条
                            // 删除（覆盖验证见 WAVE-ACCEPT6-CROSSLEGS §1：服务端
                            // 集合与直连同上游同解析，字段口径直连 ≥ 服务端，
                            // 能过严配的候选必已在本地集合中被评估）。现收尾
                            // 形态：以本机结果为准，全不匹配即换下一平台。
                            if (cands.isEmpty()) continue
                            val platPool = try {
                                SmartSourceStore.orderedScripts(plat, scripts)
                                    .filter { it.code.isNotEmpty() && !isScriptBad(song.key(), it.id, runtime.ownerContext) }
                                    // 跨平台候选必须按目标平台绑定链路取脚本；只有目标平台未配置时
                                    // 才回退到全局启用脚本，不能复用原平台的 topScripts。
                                    .ifEmpty { scripts.filter { it.enabled && it.code.isNotEmpty() && it.id !in skipScripts } }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                scripts.filter { it.enabled && it.code.isNotEmpty() && it.id !in skipScripts }
                            }.take(4)
                            for (cand in cands) {
                                // I 波阶段 1（2d）：候选先试目标平台直连解析（与官方阶段同一批
                                // 直连实现、同一匿名准入谓词），直连无果才进该平台脚本池。
                                if (canAttemptAnonymousResolutionBeforeScripts(plat, platPreferQ, false, false)) {
                                    val directUrl = try {
                                        ResolveBlockingStage.run { directResolveUrlOrNull(plat, cand.id, platPreferQ) }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        null
                                    }
                                    if (!directUrl.isNullOrBlank()) {
                                        // ACCEPT6（qqslow）：跨源直连命中同样过探针+时长
                                        // 完整性——此前直接 return 交付，候选平台的试听/
                                        // 截断文件在此无门可拦（与官方阶段同口径）；不过
                                        // 则继续该候选的脚本池，不算命中。
                                        val media = validateProbeMedia(plat, platPreferQ, directUrl, 0L, purpose, cand.duration)
                                        if (media.playable) {
                                            // ACCEPT6（mgquality）：跨源直连同按实测盖章（口径同官方阶段）。
                                            val stampedQ = AudioQualityEvidence.stampedPlaybackQuality(
                                                platPreferQ, media.magicFormat, media.formatHint, media.formatEvidence,
                                                media.bytes, cand.duration
                                            )
                                            trace("cross", plat, "$plat-direct", stampedQ, 0L, ResolveAttempt.Outcome.HIT, claimedQuality = if (stampedQ != platPreferQ) platPreferQ else "")
                                            return@withTimeoutOrNull directUrl to stampedQ
                                        }
                                        trace("cross", plat, "$plat-direct", platPreferQ, 0L, ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.MEDIA_UNVERIFIED, media.reason.ifBlank { "media_unverified" })
                                    }
                                }
                                for (sc in platPool) {
                                    val hit = tryScript(sc, plat, cand, qs, true, stageDeadline)
                                    if (hit != null) return@withTimeoutOrNull hit
                                }
                                // ACCEPT5（裁定一）：跨源候选为 wy 时，oiapi 在该候选的
                                // 脚本池之后才出场（与原平台同序：直连 → 脚本 → oiapi
                                // 兜底），测链定档同 tryOiapiFallback 口径。
                                val candOiapi = tryOiapiFallback(
                                    cand, plat, platPreferQ,
                                    canAttemptOiapiResolution(plat, platPreferQ, false, false)
                                )
                                if (candOiapi != null) return@withTimeoutOrNull candOiapi
                            }
                        }
                    }
                }
            }
            val qs = (highQs + lowQs + listOf("320k", "128k")).distinct()
            // originOnly/forceCross 都已在阶段内完成各自语义：前者禁跨平台，后者禁退回原平台。
            // 最终扫尾与阶段链共享同一链预算截止（started + chainBudgetMs），不得越过
            // 收尾预留（链尾 oiapi + finish 的时间）。
            if (!originOnly && !forceCross) for (sc in topScripts) {
                val hit = tryScript(sc, src, song, qs, false, started + chainBudgetMs)
                if (hit != null) return@withTimeoutOrNull hit
            }
            "" to ""
        }
        var finished = result ?: ("" to "")
        if (finished.first.isBlank()) {
            // ACCEPT5（裁定一）：脚本链全灭后试本机 oiapi 兜底（wy）——它就是
            // 全链末位：/music/url 服务端兜底已整条删除（ACCEPT5 删除兜底棒），
            // oiapi 再无果即本轮失败收尾，attempts 自然终止于最后一次真实尝试。
            tryOiapiFallback(
                song, src, preferQuality,
                canAttemptOiapiResolution(src, preferQuality, originOnly, forceCross)
            )?.let { finished = it }
        }
        rememberAttempts(song.key(), attemptsSnapshot(), runtime.ownerContext)
        finished
        } } ?: run {
            // ACCEPT4（修1）：超时收尾——真实 attempts 快照全量保留，budget 行只作
            // 尾注（耗时为实测而非硬编码 26_000）；确无任何尝试留痕时才退回纯合成行。
            if (requestStillCurrent()) {
                val snapshot = synchronized(collectedAttempts) { collectedAttempts.toList() }
                val budgetRow = ResolveAttempt(
                    "budget", song.source, "", preferQuality,
                    (System.currentTimeMillis() - resolveStartedAt).coerceAtLeast(0L),
                    ResolveAttempt.Outcome.TIMEOUT, ResolveAttempt.Reason.GLOBAL_TIMEOUT, "global_timeout",
                    startedAtMs = 0L
                )
                rememberAttempts(song.key(), if (snapshot.isEmpty()) listOf(budgetRow) else snapshot + budgetRow, runtime.ownerContext)
            }
            "" to ""
        }
    }

    /** 用于取链失败界面的稳定中文语义，避免把所有失败压成同一句话。 */
    fun failureMessage(failure: ResolveFailure): String = when (failure) {
        ResolveFailure.STALE_REQUEST -> "" // 旧请求被新播放意图取代，属于正常让位，不应成为用户错误提示
        ResolveFailure.NO_ENABLED_SCRIPT -> "没有可用的洛雪脚本，请到设置→脚本管理导入或启用脚本"
        ResolveFailure.NO_PLAYABLE_URL -> "脚本已执行，但没有返回可播放链接"
        ResolveFailure.TIMEOUT -> "脚本取链超时，请稍后重试或检查脚本"
        ResolveFailure.NETWORK_ERROR -> "脚本请求网络失败，请检查网络或服务代理"
        ResolveFailure.POLICY_BLOCKED -> "脚本请求被网络安全策略拦截"
        ResolveFailure.MEDIA_UNVERIFIED -> "已取得链接，但媒体响应未通过验证"
        ResolveFailure.CANDIDATE_NOT_MATCHED -> "搜索有结果，但没有匹配的歌曲候选"
        ResolveFailure.NONE -> ""
    }

    /** 供设置页/播放失败提示展示的脱敏诊断，拒绝临时链接、Cookie、账号及上游正文。 */
    fun attemptsSummary(attempts: List<ResolveAttempt>): String {
        if (attempts.isEmpty()) return ""
        val last = attempts.takeLast(5)
        return last.joinToString(" · ") { a ->
            val source = a.platform.ifBlank { "当前平台" }
            val script = a.scriptId.take(12).ifBlank { "无脚本" }
            val reason = when (a.outcome) {
                ResolveAttempt.Outcome.HIT -> "命中 ${a.quality.ifBlank { "自动" }}"
                ResolveAttempt.Outcome.EMPTY -> when (a.reason) {
                    "timeout" -> "脚本请求超时"
                    "policy" -> "脚本请求被安全策略拒绝"
                    "too_large" -> "脚本响应超限"
                    "network" -> "脚本请求网络失败"
                    "http_403" -> "脚本请求被平台拒绝"
                    "http_404" -> "脚本请求地址失效"
                    "http_429" -> "脚本请求被限流"
                    "http_5xx" -> "脚本请求服务异常"
                    "http_4xx" -> "脚本请求客户端错误"
                    else -> "未返回链接"
                }
                ResolveAttempt.Outcome.ERROR -> when (a.reason) {
                    "script_terminal" -> "脚本已停止服务（终局错误）"
                    else -> "脚本执行异常"
                }
                ResolveAttempt.Outcome.SKIPPED -> when {
                    // ACCEPT4（修3）：探针拒收必须带原文分类（HTTP/MIME/HTML-JSON/
                    // 试听片段等），否则用户只看到"已跳过"，无从判断是链接问题还是脚本问题。
                    a.reasonCode == ResolveAttempt.Reason.MEDIA_UNVERIFIED &&
                        a.reason.isNotBlank() && !a.reason.matches(Regex("^[a-z0-9_]+$")) -> a.reason
                    // ACCEPT5（E3）：跨源未匹配必须带字段级原因（缺时长/版本不符等），
                    // 否则用户只看到"未匹配到候选"，无从判断是数据缺失还是真没这首歌。
                    a.reasonCode == ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED ->
                        matchRejectReasonLabel(a.reason).let {
                            if (it.isNotBlank()) "未匹配到候选（$it）" else "未匹配到候选"
                        }
                    a.reasonCode == ResolveAttempt.Reason.SCRIPT_EMPTY_MEMORY -> "本轮多次无结果，短时跳过"
                    a.reasonCode == ResolveAttempt.Reason.ALREADY_ATTEMPTED -> "本轮已尝试，跳过"
                    a.reasonCode == ResolveAttempt.Reason.SCRIPT_PROBE_MEMORY -> "链接已确认失效，短时跳过"
                    else -> when (a.reason) {
                        "no_enabled_script" -> "没有已启用脚本"
                        "script_code_empty" -> "脚本代码为空"
                        "script_cooldown" -> "脚本冷却中"
                        "script_disabled" -> "脚本不可用"
                        "verdict_cached" -> "已裁决跳过"
                        "timeout_memory", "timeout_memory_resolve" -> "近期多次超时，短时跳过"
                        else -> "已跳过"
                    }
                }
                ResolveAttempt.Outcome.TIMEOUT -> "超时"
            }
            // H3 诊断：标注本次尝试的脚本流量通道（direct/system）。
            val routeLabel = when (a.route) {
                "direct" -> "直连"
                "system" -> "系统直连"
                else -> ""
            }
            // I 波 2b 诊断：本机 oiapi 段命中与官方 outer 命中必须可辨。
            // （/music/url 服务端兜底段的「服务端兜底」后缀随该腿删除一并摘除。）
            val oiapiLabel = if (a.stage == STAGE_OFFICIAL_OIAPI) "oiapi" else ""
            val suffix = listOf(routeLabel, oiapiLabel).filter { it.isNotEmpty() }.joinToString("") { "·$it" }
            "$source/$script：$reason" + suffix
        }
    }

    /** 播放器已获得 URL 后的错误也必须保留根因，不能再次压成“取链失败”。 */
    fun playbackFailureMessage(raw: String): String {
        val text = raw.lowercase()
        return when {
            raw.contains("试听") -> "检测到试听片段，正在换合法备用链路…"
            text.contains("http 401") || text.contains("http 403") -> "音频地址被平台拒绝，正在换脚本与平台…"
            text.contains("http 404") || text.contains("http 410") -> "音频地址已失效，正在换脚本与平台…"
            text.contains("http 5") -> "音频服务暂时异常，正在换脚本与平台…"
            text.contains("timeout") || text.contains("timed out") -> "音频连接超时，正在换脚本与平台…"
            text.contains("decoder") || text.contains("unsupported") -> "音频格式不可播放，正在换脚本与平台…"
            text.contains("network") || text.contains("connect") || text.contains("socket") -> "网络连接中断，正在换脚本与平台…"
            else -> "当前链接播放失败，正在换脚本与平台…"
        }
    }

    /**
     * I 波阶段 1（2d）：跨源候选与脚本探测样本共用的搜索腿——纯本机直连。
     *
     * ACCEPT6 跨源腿退役棒（覆盖验证见 WAVE-ACCEPT6-CROSSLEGS §1）：原「直连
     * 无果回落服务端搜索」的 /music/search 服务端腿已整条删除。五平台直连
     * 搜索均为服务端搜索函数的逐行移植（同上游同解析），且在匹配关键的时长
     * 字段上直连 ≥ 服务端（kg/wy 经 ACCEPT5(C) 补读后严格更优，mg 两边同缺
     * 时长、同被严配拒绝），删后候选覆盖不劣于删前；跨平台链对某平台无果的
     * 响应仍是按用户序换下一平台。直连异常按无结果收敛（与各调用点既有
     * catch 口径一致），取消恒重抛。
     */
    private suspend fun crossSourceSearchSongs(plat: String, kw: String): List<Song> {
        return try {
            MusicSourceRegistry.directSearchSongs(plat, kw)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    /**
     * I 波阶段 1（2d）：跨源候选的目标平台直连解析派发——与官方阶段同一批直连
     * 实现（kg/mg/kw/wy）；tx 无直连解析器（匿名 vkey 已枯竭，F2/R1），恒 null 走脚本。
     * ACCEPT5（裁定一）：wy 只派发 outer 一段——oiapi 不再混在直连组合里抢在
     * 脚本之前，它由跨源腿在候选脚本池之后单独兜底（tryOiapiFallback）。
     */
    private suspend fun directResolveUrlOrNull(platform: String, songId: String, quality: String): String? =
        when (platform) {
            "kg" -> kugouDirectResolveUrl(songId)
            "mg" -> miguDirectResolveUrl(songId)
            "kw" -> kuwoDirectResolveUrl(songId, quality.removeSuffix("k").ifBlank { "320" })
            "wy" -> wangyiOuterResolveUrl(songId)
            else -> null
        }

    /** Purpose lanes keep foreground playback independent from any background probe/prefetch wait. */
    private val playResolveLane = Mutex()
    private val prefetchResolveLane = Mutex()
    private val probeResolveLane = Mutex()
    private val downloadResolveLane = Mutex()
    private val playPriorityEpochs = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private fun priorityEpoch(owner: xyz.cncnlp.soundwave.migration.ProfileContext): AtomicLong =
        playPriorityEpochs.getOrPut(ownerKey(owner)) { AtomicLong(0L) }
    private val playPriorityEpoch: AtomicLong
        get() = priorityEpoch(xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
    /** 显式优先级：播放 0ms 立即入场；下载 90ms 仅轻微让位（用户明确任务，不应被预取压后）；预取/探测 180ms 充分让位。 */
    private const val BACKGROUND_ADMISSION_DELAY_MS = 180L
    private const val DOWNLOAD_ADMISSION_DELAY_MS = 90L

    /**
     * 只有预取/探测可因新播放意图被丢弃。下载是用户明确任务，
     * 有独立 runtime/lane，只受自身 owner 与取消状态约束，不能被 PLAY epoch 误杀。
     */
    private fun isDiscardableBackground(ticket: ResolveTicket): Boolean =
        ticket.purpose == ResolveTicket.Purpose.PREFETCH || ticket.purpose == ResolveTicket.Purpose.PROBE

    suspend fun resolveResult(
        ticket: ResolveTicket,
        song: Song,
        scripts: List<Script>,
        runtime: ScriptRuntime,
        preferQuality: String = "320k",
        skipScripts: Set<String> = emptySet(),
        forceCross: Boolean = false,
        originOnly: Boolean = false,
        requestStillCurrent: () -> Boolean = { true },
        onProgress: ((String) -> Unit)? = null
    ): ResolveResult {
        val started = System.nanoTime()
        // r658c D#1：本地歌曲绝不取链（含预取、失败重试、跨平台兜底）；调用方应先走 LocalFirst。
        if (LocalPlayback.isLocalSong(song)) {
            return ResolveResult(ticket = ticket, failure = ResolveFailure.NO_PLAYABLE_URL,
                attempts = listOf(ResolveAttempt("local", "local", "", preferQuality, 0L,
                    ResolveAttempt.Outcome.SKIPPED, ResolveAttempt.Reason.NONE, "local_media_no_resolve")))
        }
        return withTimeoutOrNull(26_000L) {
            resolveResultWithinBudget(ticket, song, scripts, runtime, preferQuality, skipScripts,
                forceCross, originOnly, requestStillCurrent, onProgress)
        } ?: run {
            // ACCEPT4（修1）：外层预算耗尽同样不得销毁证据——内层 resolve() 已把真实
            // attempts 快照留痕（lastAttemptsBySong），这里优先取回并仅追加 budget 尾注；
            // 确无留痕时才退回纯合成行。
            val kept = latestAttempts(song.key(), ticket.ownerContext)
            val budgetRow = ResolveAttempt("budget", song.source, "", preferQuality,
                (System.nanoTime() - started) / 1_000_000L,
                ResolveAttempt.Outcome.TIMEOUT, ResolveAttempt.Reason.GLOBAL_TIMEOUT, "global_timeout",
                startedAtMs = 0L)
            ResolveResult(ticket = ticket,
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                failure = ResolveFailure.TIMEOUT,
                attempts = if (kept.isEmpty()) listOf(budgetRow) else kept + budgetRow)
        }
    }

    private suspend fun resolveResultWithinBudget(
        ticket: ResolveTicket,
        song: Song,
        scripts: List<Script>,
        runtime: ScriptRuntime,
        preferQuality: String = "320k",
        skipScripts: Set<String> = emptySet(),
        forceCross: Boolean = false,
        originOnly: Boolean = false,
        requestStillCurrent: () -> Boolean = { true },
        onProgress: ((String) -> Unit)? = null
    ): ResolveResult {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(ticket.ownerContext) ||
            !runtime.ownerContext.matches(ticket.ownerContext) || !requestStillCurrent()) {
            return ResolveResult(ticket = ticket, failure = ResolveFailure.STALE_REQUEST)
        }
        val ownerEpoch = priorityEpoch(ticket.ownerContext)
        val admittedEpoch = when (ticket.purpose) {
            ResolveTicket.Purpose.PLAY -> {
                ownerEpoch.incrementAndGet()
                // A foreground play request preempts only speculative work. Download is explicit
                // and has its own task lifecycle even though the WebView is shared.
                runtime.cancelSpeculativeUrlRequests()
                ownerEpoch.get()
            }
            else -> ownerEpoch.get()
        }
        // 合并短暂后台请求，让紧随其后的用户点歌先入队；不延迟前台播放。
        if (ticket.purpose != ResolveTicket.Purpose.PLAY) {
            val wait = if (ticket.purpose == ResolveTicket.Purpose.DOWNLOAD) DOWNLOAD_ADMISSION_DELAY_MS else BACKGROUND_ADMISSION_DELAY_MS
            delay(wait)
        }
        val stillAdmitted: () -> Boolean = {
            xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(ticket.ownerContext) &&
                runtime.ownerContext.matches(ticket.ownerContext) &&
                requestStillCurrent() && (!isDiscardableBackground(ticket) || admittedEpoch == ownerEpoch.get())
        }
        if (!stillAdmitted()) {
            return ResolveResult(ticket = ticket, failure = ResolveFailure.STALE_REQUEST)
        }
        val lane = when (ticket.purpose) {
            ResolveTicket.Purpose.PLAY -> playResolveLane
            ResolveTicket.Purpose.PREFETCH -> prefetchResolveLane
            ResolveTicket.Purpose.PROBE -> probeResolveLane
            ResolveTicket.Purpose.DOWNLOAD -> downloadResolveLane
        }
        return lane.withLock {
            if (!stillAdmitted()) {
                ResolveResult(ticket = ticket, failure = ResolveFailure.STALE_REQUEST)
            } else {
                resolveResultInternal(ticket, song, scripts, runtime, preferQuality, skipScripts, forceCross, originOnly, stillAdmitted, onProgress)
            }
        }
    }

    private suspend fun resolveResultInternal(
        ticket: ResolveTicket,
        song: Song,
        scripts: List<Script>,
        runtime: ScriptRuntime,
        preferQuality: String,
        skipScripts: Set<String>,
        forceCross: Boolean,
        originOnly: Boolean,
        requestStillCurrent: () -> Boolean,
        onProgress: ((String) -> Unit)? = null
    ): ResolveResult {
        val started = System.currentTimeMillis()
        if (ticket.songKey != song.key() || !requestStillCurrent()) {
            return ResolveResult(ticket = ticket, failure = ResolveFailure.STALE_REQUEST)
        }
        val usableScripts = scripts.any { it.enabled && it.code.isNotEmpty() && it.id !in skipScripts }
        // ACCEPT4（修6）：无脚本时是否直接判死，要把 oiapi 高档可达性算进来——
        // wy 高档歌即使没有可用脚本，官方阶段第二段（oiapi）仍可能出链；
        // 匿名官方谓词本身不放宽（它关着的是必败的外层直连尝试）。
        val anonymousEligible = canAttemptAnonymousResolutionBeforeScripts(
            song.source.ifEmpty { "wy" }, preferQuality, originOnly, forceCross
        ) || canAttemptOiapiResolution(song.source.ifEmpty { "wy" }, preferQuality, originOnly, forceCross)
        if (shouldRejectForMissingScripts(usableScripts, anonymousEligible)) {
            return ResolveResult(ticket = ticket, failure = ResolveFailure.NO_ENABLED_SCRIPT)
        }
        var resolvedMedia: TrustedMediaCacheIdentity? = null
        val pair = resolve(
            song = song,
            scripts = scripts,
            runtime = runtime,
            preferQuality = preferQuality,
            skipScripts = skipScripts,
            forceCross = forceCross,
            originOnly = originOnly,
            purpose = ticket.purpose,
            requestStillCurrent = { ticket.songKey == song.key() && requestStillCurrent() },
            onProgress = onProgress,
            onResolvedMedia = { resolvedMedia = it }
        )
        val elapsed = System.currentTimeMillis() - started
        if (!requestStillCurrent()) {
            return ResolveResult(ticket = ticket, elapsedMs = elapsed, failure = ResolveFailure.STALE_REQUEST)
        }
        if (!pair.first.startsWith("http")) {
            val attempts = latestAttempts(song.key(), runtime.ownerContext)
            val failure = resolveFailureFromAttempts(attempts)
            return ResolveResult(
                ticket = ticket,
                elapsedMs = elapsed,
                failure = failure,
                attempts = attempts
            )
        }
        val meta = resolvedMedia?.takeIf {
            it.url == pair.first && it.actualQuality == pair.second && it.owner == ownerKey(ticket.ownerContext)
        }
        return ResolveResult(
            ticket = ticket,
            url = pair.first,
            quality = pair.second,
            platform = meta?.platform.orEmpty().ifEmpty { song.source },
            scriptId = meta?.script.orEmpty(),
            candidateKey = meta?.candidate.orEmpty(),
            fromCache = latestAttempts(song.key(), runtime.ownerContext).any { it.stage == "cache" && it.outcome == ResolveAttempt.Outcome.HIT },
            elapsedMs = elapsed,
            attempts = latestAttempts(song.key(), runtime.ownerContext)
        )
    }

    suspend fun warmup(scripts: List<Script>, runtime: ScriptRuntime) {
        // H6-a：预热集与用户实际链路对齐——按已开启平台顺序取各平台链路前 2 个脚本
        // 去重（总量上限 6），不再写死网易云前 3：主听 tx 的用户冷启首播同样命中预热。
        val pool = try {
            val seen = mutableSetOf<String>()
            SmartSourceStore.enabledPlatformsInOrder()
                .flatMap { plat -> SmartSourceStore.orderedScripts(plat, scripts).take(2) }
                .filter { seen.add(it.id) }
                .take(6)
                .ifEmpty { scripts.filter { it.enabled && it.code.isNotEmpty() }.take(3) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            scripts.filter { it.enabled && it.code.isNotEmpty() }.take(3)
        }
        for (sc in pool) {
            try {
                runtime.warmup(sc.id, sc.code, scriptMetaJson(sc))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    data class ProbeScore(
        val report: String,
        val score: Int,
        val avgMs: Long,
        /** platform -> highest actually validated quality */
        val platQuality: Map<String, String> = emptyMap(),
        val platMs: Map<String, Long> = emptyMap()
    )

    /**
     * Runs on the single ScriptRuntime/WebView lane.  A URL is deliberately not
     * counted as a success until a ranged media response proves a positive size
     * and an audio MIME/extension; HTML, zero/unknown length, tiny preview
     * assets and a search result that does not match the probe song are rejected.
     */
    /**
     * 探测按单个脚本/单个候选入场，不得把完整五平台×音质循环包在 Runtime 锁里。
     * 用户点歌后，探测在边界检查点让行等待（ProbeYieldGate）：等播放解析通道
     * 空闲且播放纪元停止变化后从原地继续，不再整单中止；只有等待累计超限或
     * 任务被真正取消时才按既有 SKIPPED 语义收场。已完成采样结果不受影响。
     */
    private val probeLane = Mutex()

    suspend fun <T> withProbeLane(block: suspend (gate: ProbeYieldGate) -> T): T {
        val gate = newProbeYieldGate()
        delay(BACKGROUND_ADMISSION_DELAY_MS)
        return probeLane.withLock {
            gate.checkpoint()
            block(gate)
        }
    }

    private fun newProbeYieldGate(): ProbeYieldGate = ProbeYieldGate(
        epoch = { playPriorityEpoch.get() },
        playbackBusy = { playResolveLane.isLocked },
        nowMs = { System.currentTimeMillis() },
        sleeper = { delay(it) }
    )

    private val probeRuntimeLane = Mutex()

    private suspend fun <T> withProbeRuntime(gate: ProbeYieldGate?, block: suspend () -> T): T {
        gate?.checkpoint()
        return probeRuntimeLane.withLock {
            gate?.checkpoint()
            block()
        }
    }

    suspend fun probeScriptScored(
        sc: Script,
        runtime: ScriptRuntime,
        maxPlats: Int = 5,
        preferQ: String = "320k",
        yieldGate: ProbeYieldGate? = null
    ): ProbeScore = withContext(Dispatchers.IO + ResolveBlockingStage.context(ResolveTicket.Purpose.PROBE)) {
        if (sc.code.isBlank()) return@withContext ProbeScore("无代码 · 不可用", -1, -1L)
        ScriptStorage.compatibilityError(sc.code)?.let { reason ->
            return@withContext ProbeScore("脚本不兼容 · $reason", -1, -1L)
        }
        // r658c S：完整检测（无抽检）：脚本声明的全部平台 × 每平台全部固定样本 × 该平台声明的全部音质；
        // 未取到声明时按引擎五平台 × 七档兼容遍历。不插入用户播放档，也不因首档成功而提前停止。
        val declared = try {
            withProbeRuntime(yieldGate) { runtime.declaredSources(sc.id, sc.code, scriptMetaJson(sc)) }
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw kotlinx.coroutines.CancellationException("播放请求已抢占脚本探测")
        } catch (_: Exception) {
            ""
        }
        val plan = ScriptProbeMatrix.plan(ScriptProbeMatrix.parseDeclaredSources(declared), maxPlats)
        val samples = plan.samples
        val attempts = mutableListOf<ScriptProbeCache.Attempt>()
        val platQ = linkedMapOf<String, String>()
        val platMs = linkedMapOf<String, Long>()
        // 超时控制：单脚本总时限 + 同平台连续取链超时熔断；未执行项仍逐项记录原因，不静默丢弃。
        val budgetDeadline = System.currentTimeMillis() + ScriptProbeMatrix.FULL_PROBE_BUDGET_MS
        var budgetExceeded = false
        var matrixIncomplete = false
        val timeoutStreak = mutableMapOf<String, Int>()
        for (sample in samples) {
            yieldGate?.checkpoint()
            val (plat, name, singer) = sample
            val qOrder = plan.qualitiesFor(plat)
            val skipReason = when {
                budgetExceeded || System.currentTimeMillis() > budgetDeadline -> {
                    budgetExceeded = true
                    "超过单脚本检测总时限 ${ScriptProbeMatrix.FULL_PROBE_BUDGET_MS / 60_000L} 分钟，本项未执行"
                }
                (timeoutStreak[plat] ?: 0) >= ScriptProbeMatrix.PLATFORM_TIMEOUT_STREAK_LIMIT ->
                    "同平台已连续 ${ScriptProbeMatrix.PLATFORM_TIMEOUT_STREAK_LIMIT} 次取链超时，本项未再请求"
                else -> ""
            }
            if (skipReason.isNotEmpty()) {
                matrixIncomplete = true
                qOrder.forEach { q ->
                    attempts += ScriptProbeCache.Attempt(
                        platform = plat, quality = q, outcome = ScriptProbeCache.Outcome.FAILED, reason = skipReason,
                        sampleQueryName = name, sampleQuerySinger = singer,
                        requestDescription = "未进入平台搜索/脚本取链：$skipReason"
                    )
                }
                continue
            }
            val kw = listOf(name, singer).filter { it.isNotBlank() }.joinToString(" ")
            var searchFailureReason = ""
            var searchMs = -1L
            var searchStarted = 0L
            val song = try {
                // 搜索结果的来源服务可能返回繁体/括号/版本后缀；探测样本只需要
                // 找到同名同歌手候选，不应把严格的跨平台版本门禁误用到基础探测。
                // 先按精确规范化标题，再按歌手匹配，最后允许同名首项作为兜底。
                searchStarted = System.currentTimeMillis()
                val results = ResolveBlockingStage.run { crossSourceSearchSongs(plat, kw) }
                searchMs = (System.currentTimeMillis() - searchStarted).coerceAtLeast(1L)
                results.firstOrNull { probeSongMatches(it, name, singer) }
                    ?: results.firstOrNull { SongMatcher.normalize(it.name) == SongMatcher.normalize(name) }
                    ?: results.firstOrNull { SongMatcher.normalize(it.name).contains(SongMatcher.normalize(name)) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (searchStarted > 0L) searchMs = (System.currentTimeMillis() - searchStarted).coerceAtLeast(1L)
                searchFailureReason = "搜索失败：${e.javaClass.simpleName}"
                null
            }
            if (song == null) {
                qOrder.forEach { q ->
                    attempts += ScriptProbeCache.Attempt(
                        platform = plat,
                        quality = q,
                        outcome = if (searchFailureReason.isBlank()) ScriptProbeCache.Outcome.NO_MATCH else ScriptProbeCache.Outcome.FAILED,
                        reason = searchFailureReason.ifBlank { "未找到匹配歌曲，无法验证此平台/档位" },
                        sampleQueryName = name,
                        sampleQuerySinger = singer,
                        searchMs = searchMs,
                        requestDescription = "平台搜索样本「$name${singer.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()}」；${if (searchFailureReason.isBlank()) "无匹配歌曲" else searchFailureReason}；未进入脚本取链/媒体请求"
                    )
                }
                continue
            }
            val urlByQuality = linkedMapOf<String, String>()
            for (q in qOrder) {
                yieldGate?.checkpoint()
                val cutReason = when {
                    System.currentTimeMillis() > budgetDeadline -> {
                        budgetExceeded = true
                        "超过单脚本检测总时限 ${ScriptProbeMatrix.FULL_PROBE_BUDGET_MS / 60_000L} 分钟，本项未执行"
                    }
                    (timeoutStreak[plat] ?: 0) >= ScriptProbeMatrix.PLATFORM_TIMEOUT_STREAK_LIMIT ->
                        "同平台已连续 ${ScriptProbeMatrix.PLATFORM_TIMEOUT_STREAK_LIMIT} 次取链超时，本项未再请求"
                    else -> ""
                }
                if (cutReason.isNotEmpty()) {
                    matrixIncomplete = true
                    attempts += ScriptProbeCache.Attempt(
                        platform = plat, quality = q, outcome = ScriptProbeCache.Outcome.FAILED, reason = cutReason,
                        sampleName = song.name, sampleSinger = song.singer, sampleQueryName = name, sampleQuerySinger = singer,
                        sampleDurationMs = song.duration, searchMs = searchMs,
                        requestDescription = "未调用 getMusicUrl($plat, $q)：$cutReason"
                    )
                    continue
                }
                val t1 = System.currentTimeMillis()
                var runtimeFailure = ""
                val u = try {
                    withProbeRuntime(yieldGate) {
                        runtime.consumeLastFailureReason(sc.id)
                        val resolved = runtime.getMusicUrl(sc.id, sc.code, buildMusicInfo(song), plat, q, ResolveTicket.Purpose.PROBE, scriptMeta = scriptMetaJson(sc))
                        runtimeFailure = ScriptProbeCache.safeDiagnosticText(runtime.consumeLastFailureReason(sc.id))
                        resolved
                    }
                } catch (_: kotlinx.coroutines.CancellationException) {
                    throw kotlinx.coroutines.CancellationException("播放请求已抢占脚本探测")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    runtimeFailure = e.javaClass.simpleName
                    ""
                }
                val dt = (System.currentTimeMillis() - t1).coerceAtLeast(1L)
                timeoutStreak[plat] = if (u.isBlank() && runtimeFailure == "timeout") (timeoutStreak[plat] ?: 0) + 1 else 0
                var attempt = if (u.isBlank()) {
                    ScriptProbeCache.Attempt(
                        platform = plat,
                        quality = q,
                        outcome = if (runtimeFailure.isBlank()) ScriptProbeCache.Outcome.UNSUPPORTED else ScriptProbeCache.Outcome.FAILED,
                        resolveMs = dt,
                        reason = runtimeFailure.ifBlank { "脚本未返回链接；该平台/档位可能不可用" }
                    )
                } else if (!u.startsWith("http://") && !u.startsWith("https://")) {
                    ScriptProbeCache.Attempt(plat, q, ScriptProbeCache.Outcome.FAILED, resolveMs = dt, reason = "脚本返回非 HTTP(S) 链接")
                } else {
                    // ACCEPT5 检测口径对齐：与播放链 resolveViaScript 同一处理——脚本出链先经
                    // normalizeKgMediaUrl 规整（http→https）再校验，校验对象必须是播放实际会
                    // 请求的那条 URL。原始 http 直链会被 App 明文策略在连接层拦截
                    // （UnknownServiceException），曾把播放可用的好链整批误判为「媒体链接网络失败」。
                    val mediaUrl = normalizeKgMediaUrl(u)
                    validateProbeMedia(plat, q, mediaUrl, dt, ResolveTicket.Purpose.PROBE, song.duration)
                        .copy(urlHint = ScriptProbeCache.safeUrlSummary(mediaUrl))
                }
                attempt = attempt.copy(
                    sampleName = song.name,
                    sampleSinger = song.singer,
                    sampleQueryName = name,
                    sampleQuerySinger = singer,
                    sampleDurationMs = song.duration,
                    searchMs = searchMs,
                    requestDescription = buildString {
                        append("脚本调用 getMusicUrl($plat, $q)")
                        if (attempt.requestDescription.isNotBlank()) {
                            append("；").append(attempt.requestDescription)
                        } else if (attempt.requestMethod.isNotBlank()) {
                            append("；媒体校验 ").append(attempt.requestMethod)
                            if (attempt.rangeHeader.isNotBlank()) append(" Range: ").append(attempt.rangeHeader)
                        } else {
                            append("；未发起媒体校验请求（脚本未返回有效 HTTP 直链）")
                        }
                    }
                )
                val sameAs = urlByQuality.entries.firstOrNull { it.value == u }?.key
                if (sameAs != null && attempt.outcome == ScriptProbeCache.Outcome.OK) {
                    attempt = attempt.copy(outcome = ScriptProbeCache.Outcome.EXCLUDED, playable = false,
                        reason = "与 $sameAs 返回同一媒体链接，疑似未切换音质")
                }
                if (u.startsWith("http")) urlByQuality[q] = u
                attempts += attempt
                if (attempt.outcome == ScriptProbeCache.Outcome.OK) {
                    val previousQuality = platQ[plat]
                    val previousMs = platMs[plat] ?: Long.MAX_VALUE
                    if (previousQuality == null || AudioQualityEvidence.qualityRank(q) > AudioQualityEvidence.qualityRank(previousQuality) ||
                        (AudioQualityEvidence.qualityRank(q) == AudioQualityEvidence.qualityRank(previousQuality) && dt < previousMs)) {
                        platQ[plat] = q
                        platMs[plat] = dt
                    }
                }
            }
        }
        val data = ScriptProbeCache.Report(
            scriptId = sc.id,
            scriptName = sc.name.ifEmpty { sc.id },
            attempts = attempts,
            scriptHash = ScriptProbeCache.scriptHash(sc.code),
            runtimeAbi = ScriptProbeCache.RUNTIME_ABI,
            type = ScriptProbeCache.ReportType.FULL,
            // 完整 = 计划内每一项（声明平台 × 全部样本 × 声明音质）都实际执行过；时限截断则标为非完整、不参与排序。
            complete = !budgetExceeded && maxPlats >= ScriptProbeMatrix.platformOrder.size && !matrixIncomplete &&
                attempts.size == plan.totalItems,
            coverage = ScriptProbeMatrix.coverageText(plan, ScriptProbeCache::platformLabel)
        )
        ScriptProbeCache.save(data)
        ProbeScore(ScriptProbeCache.format(data), data.combinedScore, data.avgMs, platQ, platMs)
    }

    /** Compatibility entry point; always uses the validated structured probe. */
    suspend fun probeScript(
        sc: Script,
        runtime: ScriptRuntime,
        maxPlats: Int = 5
    ): String = probeScriptScored(sc, runtime, maxPlats).report

    /** 探测只做样本可用性参考，不要求跨源级严格身份；标题规范化相等或包含即可。 */
    private fun probeSongMatches(song: Song, expectedName: String, expectedSinger: String): Boolean {
        val title = SongMatcher.normalize(song.name)
        val target = SongMatcher.normalize(expectedName)
        if (title.isBlank() || target.isBlank()) return false
        val titleOk = title == target || title.contains(target) || target.contains(title)
        if (!titleOk) return false
        val artists = song.singer.split(Regex("[、,，/&;；|＋+]"))
            .map { SongMatcher.normalize(it) }.filter { it.isNotBlank() }.toSet()
        val expectedArtists = expectedSinger.split(Regex("[、,，/&;；|＋+]"))
            .map { SongMatcher.normalize(it) }.filter { it.isNotBlank() }.toSet()
        // 样本未给歌手时只比歌名；给了则任一交集即可，避免「周杰伦」vs「周杰伦/费玉清」误杀。
        if (expectedArtists.isEmpty()) return true
        if (artists.isEmpty()) return true
        return artists.any { a -> expectedArtists.any { e -> a == e || a.contains(e) || e.contains(a) } }
    }

    /**
     * 探测媒体校验：宁可漏报“未验证”，不可把可播链误杀成“不可用”。
     * 常见 CDN 对 Range:0-0 不回 Content-Length / 只回 octet-stream，旧逻辑会整脚本标红。
     */
    private val probeMediaClient by lazy {
        AppHttp.shared.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
            .connectionPool(okhttp3.ConnectionPool(1, 30, java.util.concurrent.TimeUnit.SECONDS))
            .build()
    }

    /** The single-slot probe dispatcher must never queue foreground playback or explicit downloads. */
    private fun mediaValidationClient(purpose: ResolveTicket.Purpose): okhttp3.OkHttpClient =
        if (purpose == ResolveTicket.Purpose.PROBE) probeMediaClient else AppHttp.shared

    private suspend fun validateProbeMedia(
        platform: String,
        quality: String,
        url: String,
        resolveMs: Long,
        purpose: ResolveTicket.Purpose,
        expectedDurationMs: Long = 0L
    ): ScriptProbeCache.Attempt {
        suspend fun attemptOnce(range: String?): Pair<okhttp3.Response?, String> {
            return try {
                val client = mediaValidationClient(purpose).newBuilder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val b = MediaRequestPolicy.apply(okhttp3.Request.Builder().url(url))
                    .header("Accept", "*/*")
                if (range != null) b.header("Range", range)
                resolveCall(client.newCall(b.build())) { response ->
                    val bytes = response.body?.byteStream()?.let { input ->
                        val prefix = ByteArray(64)
                        val count = input.read(prefix)
                        if (count > 0) prefix.copyOf(count) else ByteArray(0)
                    } ?: ByteArray(0)
                    response.newBuilder().body(bytes.toResponseBody(response.body?.contentType())).build()
                } to ""
            } catch (e: java.net.SocketTimeoutException) { null to "媒体链接探测超时" }
              catch (e: java.io.IOException) { null to "媒体链接网络失败: ${e.javaClass.simpleName}" }
              catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null to "媒体链接无效: ${e.javaClass.simpleName}" }
        }
        var rangeHeader = "bytes=0-2047"
        return try {
            val start = System.currentTimeMillis()
            // 先 Range 小段；失败再普通 GET 首包，避免 chunked/无长度 CDN 被误杀。
            var pair = attemptOnce("bytes=0-2047")
            var response = pair.first
            var networkReason = pair.second
            // ACCEPT5（C）：首探已拿到确定性 HTTP 终局状态（401/403/404/410）时
            // 不再回退普通 GET——状态码本身即结论，第二趟往返对死链纯属多烧一程
            // （真机实证：tx 脚本 MASTER 死链 404 双探串行吃掉约 3.5s 才降级）。
            // 回退只保留给无响应/网络失败/状态不确定的情形，分类口径不变。
            val definitiveHttpStatus = response?.code in setOf(401, 403, 404, 410)
            if (!definitiveHttpStatus && (response == null || (!response.isSuccessful && response.code != 206 && response.code != 200))) {
                response?.close()
                rangeHeader = ""
                pair = attemptOnce(null)
                response = pair.first
                if (pair.second.isNotBlank()) networkReason = pair.second
            }
            if (response == null) {
                val waitMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                return ScriptProbeCache.Attempt(
                    platform, quality, ScriptProbeCache.Outcome.FAILED,
                    resolveMs = resolveMs,
                    firstByteMs = waitMs,
                    reason = networkReason.ifBlank { "媒体链接无效或不可用" },
                    requestMethod = "GET",
                    rangeHeader = rangeHeader,
                    requestDescription = if (rangeHeader.isBlank()) {
                        "先发送 GET Range: bytes=0-2047 未取得响应，再回退普通 GET 仍未取得响应"
                    } else {
                        "GET Range: $rangeHeader 未取得响应"
                    }
                )
            }
            response.use { resp ->
                val buf = ByteArray(64)
                val n = try { resp.body?.byteStream()?.read(buf) ?: -1 } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; -1 }
                val firstByteMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                val type = resp.header("Content-Type").orEmpty().lowercase().substringBefore(';').trim()
                val contentRange = resp.header("Content-Range").orEmpty()
                val measured = AudioQualityEvidence.measuredSize(resp.code, contentRange, resp.header("Content-Length"))
                val total = measured.first
                val formatHint = AudioQualityEvidence.formatHint(url, type)
                fun observed(
                    outcome: ScriptProbeCache.Outcome,
                    reason: String = "",
                    playable: Boolean = false
                ) = ScriptProbeCache.Attempt(
                    platform = platform,
                    quality = quality,
                    outcome = outcome,
                    resolveMs = resolveMs,
                    firstByteMs = firstByteMs,
                    bytes = total,
                    mime = type,
                    playable = playable,
                    reason = reason,
                    firstByteReceived = n > 0,
                    httpStatus = resp.code,
                    requestMethod = "GET",
                    rangeHeader = rangeHeader,
                    contentRange = contentRange,
                    sizeSource = measured.second,
                    formatHint = formatHint?.label.orEmpty(),
                    formatEvidence = formatHint?.evidence.orEmpty(),
                    magicFormat = magicAudioFormat(buf, n)
                )
                if (n <= 0 && !resp.isSuccessful && resp.code != 206) {
                    return observed(ScriptProbeCache.Outcome.FAILED, "HTTP ${resp.code}")
                }
                val urlAudio = Regex("\\.(mp3|m4a|aac|flac|ogg|opus|wav|ape)(?:[?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
                val mimeAudio = type.startsWith("audio/") ||
                    type == "application/octet-stream" ||
                    type == "binary/octet-stream" ||
                    type == "application/ogg" ||
                    type.isBlank() // 部分源不回 Content-Type
                val audio = urlAudio || mimeAudio
                // 试听门限放宽：很多 128k 短链/加密链长度元数据不准，有首字节即可先算候选。
                val previewFloor = when (quality.lowercase()) {
                    "128k", "128" -> 80_000L
                    "192k", "192" -> 120_000L
                    else -> 160_000L
                }
                when {
                    resp.code == 401 || resp.code == 403 ->
                        observed(ScriptProbeCache.Outcome.FAILED, "权限拒绝 HTTP ${resp.code}")
                    !resp.isSuccessful && resp.code != 206 ->
                        observed(ScriptProbeCache.Outcome.FAILED, "HTTP ${resp.code}")
                    n <= 0 ->
                        observed(ScriptProbeCache.Outcome.FAILED, "无首字节")
                    // ACCEPT4（修3，探针分类口径）：响应体首字节呈 HTML/JSON 形态时，
                    // 即使 URL 带音频后缀或类型为 octet-stream，也按 HTML/JSON 错误页
                    // 归类拒收——分类名进入诊断，失败不再是无声的"未通过验证"。
                    isHtmlOrJsonBody(buf, n) ->
                        observed(ScriptProbeCache.Outcome.EXCLUDED, "HTML/JSON 响应（非音频）")
                    !audio ->
                        observed(ScriptProbeCache.Outcome.EXCLUDED, "MIME 非音频($type)")
                    // HLS/DASH 流式清单：不是单文件，解密不适用，保持排除。
                    isStreamManifest(url, type, buf, n) ->
                        observed(ScriptProbeCache.Outcome.EXCLUDED, "疑似流媒体清单，无法按单文件校验")
                    // ACCEPT6（qqslow）：时长完整性——总长已知且按保守比特率下限
                    // 折算的上限时长仍明显短于元数据时长 ⇒ 物理上装不下完整歌曲，
                    // 判试听片段（旧口径只有 80~160KB 绝对下限：30~60s 的 128k
                    // 试听有 480~960KB，整整漏过一个数量级）。加密容器大小与音频
                    // 同量级、折算口径不变，故本检置于加密放行之前。
                    AudioQualityEvidence.isPreviewFragment(total, expectedDurationMs, (formatHint?.label ?: "") + " " + url) ->
                        observed(ScriptProbeCache.Outcome.EXCLUDED, "试听片段：文件大小与歌曲时长不符")
                    // 文件型加密（.mflac/.mgg/.ncm/.qmc 等）：本地可解密，放行由播放/下载链路负责解密。
                    isEncryptedMedia(url) ->
                        observed(ScriptProbeCache.Outcome.OK, "加密媒体，播放前解密", playable = true)
                    // 大小未知但已读到字节：记为有效（CDN chunked 常见），不再“已排除”。
                    total <= 0L ->
                        observed(ScriptProbeCache.Outcome.OK, "长度未知但可拉流", playable = true)
                    total < previewFloor ->
                        observed(ScriptProbeCache.Outcome.EXCLUDED, "疑似试听/过短片段")
                    else ->
                        observed(ScriptProbeCache.Outcome.OK, playable = true)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ScriptProbeCache.Attempt(
                platform, quality, ScriptProbeCache.Outcome.FAILED,
                resolveMs = resolveMs,
                reason = e.javaClass.simpleName,
                requestMethod = "GET",
                rangeHeader = rangeHeader,
                requestDescription = if (rangeHeader.isBlank()) {
                    "媒体 Range GET 失败后回退普通 GET 时发生异常"
                } else {
                    "媒体 GET Range: $rangeHeader 校验时发生异常"
                }
            )
        }
    }

    /**
     * 文件型加密媒体：本地具备解密能力（见 decrypt/QmcDecrypt、decrypt/NcmDecrypt），
     * probe 应判为可播，由播放/下载链路下载后再解密，不能因“无法直接播放”而排除。
     */
    private fun isEncryptedMedia(url: String): Boolean {
        return MediaDecryptCache.encryptedExtOf(url) != null
    }

    /**
     * HLS/DASH 流式清单：是播放列表而非单文件，解密不适用，仍按“无法验证可播放性”排除。
     */
    private fun isStreamManifest(url: String, type: String, bytes: ByteArray, n: Int): Boolean {
        if (type.contains("application/vnd.apple.mpegurl") || type.contains("application/x-mpegurl") || type.contains("application/dash+xml")) return true
        val head = bytes.copyOf(n.coerceIn(0, bytes.size)).toString(Charsets.ISO_8859_1)
        return head.startsWith("#EXTM3U") || head.contains("#EXT-X-KEY")
    }

    /**
     * ACCEPT4（修3）：响应体形态分类——首个非空白字节为 '<'（HTML/XML）或 '{'
     * （JSON）即非音频正文。任何真实音频容器（ID3/帧同步/fLaC/OggS/RIFF/ftyp）
     * 都不会以这两个字节开头，误杀面为零。
     */
    /**
     * ACCEPT6（mgquality）：响应首字节魔数判格式族——只认字节自证的三种：
     * fLaC → FLAC；ID3 头或 MPEG 音频帧同步（层位非 0）→ MP3；RIFF/WAVE → WAV。
     * Ogg/MP4 容器内编码不可由魔数细分、加密封装不可见内层，一律返回空（不猜）。
     */
    private fun magicAudioFormat(bytes: ByteArray, n: Int): String {
        val limit = n.coerceIn(0, bytes.size)
        fun at(i: Int, s: String): Boolean {
            if (limit < i + s.length) return false
            for (j in s.indices) if (bytes[i + j] != s[j].code.toByte()) return false
            return true
        }
        return when {
            at(0, "fLaC") -> "FLAC"
            at(0, "ID3") -> "MP3"
            limit >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 &&
                (bytes[1].toInt() and 0x06) != 0 -> "MP3"
            at(0, "RIFF") && at(8, "WAVE") -> "WAV"
            else -> ""
        }
    }

    private fun isHtmlOrJsonBody(bytes: ByteArray, n: Int): Boolean {
        val limit = n.coerceIn(0, bytes.size)
        var i = 0
        while (i < limit) {
            val c = bytes[i].toInt().toChar()
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue }
            return c == '<' || c == '{'
        }
        return false
    }

    private suspend fun buildMusicInfo(input: Song): JSONObject {
        val song = MiguScriptMetadata.prepare(input)
        val sid = song.id
        val scriptSongId = if (song.source == "mg") song.miguSongId else sid
        val src = song.source.ifEmpty { "wy" }
        val kgHash = if (src == "kg") song.hash.ifEmpty { sid } else ""
        val hash = if (src == "kg") kgHash else song.hash.ifEmpty { sid }
        return PaidAlbumScriptMetadata.apply(song, JSONObject().apply {
            put("name", song.name)
            put("singer", song.singer)
            put("songmid", if (src == "kg") hash else scriptSongId)
            put("hash", hash)
            put("songId", scriptSongId)
            put("id", sid)
            put("mid", sid)
            put("strMediaMid", sid)
            put("albumId", song.albumId)
            put("albumName", song.album)
            put("source", src)
            put("img", song.cover)
            // Song durations are milliseconds; script interval is a minute:second string.
            put("interval", scriptInterval(song.duration / 1000L))
            put("types", org.json.JSONArray())
            val types = JSONObject()
            put("_types", types)
            put("typeUrl", JSONObject())
            if (src == "mg") {
                put("copyrightId", song.copyrightId)
                put("contentId", sid)
            }
            if (src == "tx" && song.mediaMid.isNotEmpty()) {
                put("strMediaMid", song.mediaMid)
                put("media_mid", song.mediaMid)
            }
        })
    }

    private fun qualitySatisfies(actual: String, requested: String): Boolean {
        fun rank(q: String): Int = when (q.lowercase()) {
            "master", "dolby" -> 6
            "hires" -> 5
            "flac" -> 4
            "320k", "320" -> 3
            "192k", "192" -> 2
            "128k", "128" -> 1
            else -> 0
        }
        // Unknown requested quality must not make an otherwise valid exact-key cache unusable.
        val want = rank(requested)
        return want == 0 || rank(actual) >= want
    }

    private fun trimCacheLocked() {
        while (urlCache.size > 80) {
            val it = urlCache.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else break
        }
    }
}
