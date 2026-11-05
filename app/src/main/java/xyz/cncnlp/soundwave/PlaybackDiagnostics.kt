package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放观测诊断：回答“为什么首播慢 / 换链慢 / 预取没命中”。
 *
 * 只记录时序与结果语义，不落 URL、Cookie 或凭据。最近 N 条在内存环形缓冲，
 * 并持久化到 SharedPreferences 供设置页“播放诊断”展示。
 *
 * 链路分段：
 *   点歌 t0 → resolve（取链，含脚本耗时/缓存命中）→ prepare → READY(出声)
 * 失败层级：script(脚本无链) / url(HTTP 4xx/5xx) / decoder(格式不可播) /
 *           preview(试听片段) / timeout(超时) / network(连接中断)。
 */
object PlaybackDiagnostics {
    private const val PREF = "playback_diag_v1"
    private const val KEY = "entries"
    private const val LEGACY_KEY_RECOMMEND = "recommend_state"
    private const val MAX_MEMORY = 60
    private const val MAX_PERSIST = 50

    data class Entry(
            val songKey: String,
            val songName: String,
            val singer: String = "",
            val resolveMs: Long = -1L,
            val fromUrlCache: Boolean = false,
            val scriptId: String = "",
            val platform: String = "",
            /** 歌曲原始平台（规范化后的平台码）；platform 是实际出链平台，跨源时两者不同。 */
            val originPlatform: String = "",
            val quality: String = "",
            val prepareMs: Long = -1L,
            val totalMs: Long = -1L,
            val prefetched: Boolean = false,
            val failureLayer: String = "",
            val purpose: String = "",
            val ts: Long = System.currentTimeMillis()
        )

        /** 待合并的分段（点歌起点 + resolve 结果），按 songKey 关联，READY 时合并成一条完整记录。 */
        private data class Pending(
            val startMs: Long,
            val songName: String,
            val singer: String,
            val resolveMs: Long,
            val fromUrlCache: Boolean,
            val scriptId: String,
            val platform: String,
            val originPlatform: String = "",
            val quality: String,
            val purpose: String = ""
        )

    @Volatile private var memory = ArrayDeque<Entry>()

    @Volatile private var pending = mutableMapOf<String, Pending>()

    /**
     * PB-F3：搜索/目录腿的失败分型计数（内存态，不落盘、不含查询词/URL）。
     * 键 = "scope|platform|stageLabel"，与 [Entry.failureLayer] 同属诊断口径：
     * failureLayer 回答单曲播放为什么失败，stage 计数回答某条搜索/目录腿
     * 整体在以哪种形态失败（拒绝/解析/网络/真空），「空 ≠ 失败」在此分开计账。
     */
    private val failureStageTallies = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** 记一次带阶段标签的腿级结果（只记标签与计数，行为零影响）。 */
    fun noteFailureStage(scope: String, platform: String, stage: xyz.cncnlp.soundwave.source.FailureStage) {
        val key = "$scope|${canonicalPlatform(platform).ifBlank { platform.trim().lowercase() }}|${stage.label}"
        failureStageTallies.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
    }

    /** 某 (scope, platform, stage) 的累计次数（测试与诊断汇总用）。 */
    fun failureStageCount(scope: String, platform: String, stage: xyz.cncnlp.soundwave.source.FailureStage): Int {
        val key = "$scope|${canonicalPlatform(platform).ifBlank { platform.trim().lowercase() }}|${stage.label}"
        return failureStageTallies[key]?.get() ?: 0
    }

    /** 全部阶段计数快照（键同 [noteFailureStage]）。 */
    fun failureStageTallySnapshot(): Map<String, Int> =
        failureStageTallies.mapValues { it.value.get() }

    /**
     * 平台码规范化：只有既定平台码（wy/kg/kw/tx/mg）与 local 才是可落盘、可展示的平台身份。
     * 解析链回退的 song.source 是未校验的自由字符串（可能是自定义来源键、甚至哈希），
     * 一律视为未知，禁止原样写进诊断记录冒充平台。
     */
    internal fun canonicalPlatform(raw: String): String {
        val v = raw.trim().lowercase()
        return if (v == "local" || v in Platforms.ALL) v else ""
    }

    private fun platDisplay(code: String): String = if (code == "local") "本地" else Platforms.label(code)

    /** 长十六进制摘要串（候选/作用域哈希同形）：绝不允许被当成平台名展示。 */
    private fun isDigestLike(v: String): Boolean =
        v.length >= 32 && v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * 诊断展示的来源标识（汇总行 / 报告行 / 路径弹层共用）：
     * 实际来源平台优先；跨源（实际≠原始）标注「原平台→实际平台」；实际未知回退原始平台；
     * 再回退脚本名；历史记录里已存成哈希的 platform 只在没有其它标识时兜底为「未知来源」，绝不原样打印。
     */
    fun sourceLabel(e: Entry): String {
        val actual = canonicalPlatform(e.platform)
        val origin = canonicalPlatform(e.originPlatform)
        if (actual.isNotBlank() && origin.isNotBlank() && actual != origin)
            return "${platDisplay(origin)}→${platDisplay(actual)}"
        if (actual.isNotBlank()) return platDisplay(actual)
        if (origin.isNotBlank()) return platDisplay(origin)
        val script = e.scriptId.takeIf { it.isNotBlank() && it != "local" }
        if (script != null) return script
        val raw = e.platform.trim()
        if (raw.isNotBlank() && !isDigestLike(raw)) return raw
        return "未知来源"
    }

    /** 点歌 / 重播起点：记录 t0 与歌曲身份。 */
    @Synchronized
    fun markStart(song: Song, purpose: String = "播放") {
        if (song.key().isBlank()) return
        val existing = pending[song.key()]
        pending[song.key()] = Pending(
            startMs = System.currentTimeMillis(),
            songName = song.name,
            singer = song.singer,
            resolveMs = existing?.resolveMs ?: -1L,
            fromUrlCache = existing?.fromUrlCache ?: false,
            scriptId = existing?.scriptId.orEmpty(),
            platform = existing?.platform.orEmpty(),
            originPlatform = existing?.originPlatform?.ifEmpty { canonicalPlatform(song.source) } ?: canonicalPlatform(song.source),
            quality = existing?.quality.orEmpty(),
            purpose = existing?.purpose ?: purpose
        )
    }

    /** resolve 完成：写入分段耗时与来源语义。 */
    @Synchronized
    fun markResolved(song: Song, resolveMs: Long, fromCache: Boolean, scriptId: String, platform: String, quality: String, purpose: String = "播放") {
        if (song.key().isBlank()) return
        val p = pending[song.key()]
        pending[song.key()] = Pending(
            startMs = p?.startMs ?: System.currentTimeMillis(),
            songName = song.name,
            singer = song.singer,
            resolveMs = resolveMs,
            fromUrlCache = fromCache,
            scriptId = scriptId,
            // 记录创建源头规范化：解析链给的 platform 可能是未校验的 song.source 回退值
            // （自定义来源键/哈希），只有既定平台码与 local 才准落盘；原始平台另存 originPlatform。
            platform = canonicalPlatform(platform).ifEmpty { p?.platform.orEmpty() },
            originPlatform = p?.originPlatform?.ifEmpty { canonicalPlatform(song.source) } ?: canonicalPlatform(song.source),
            quality = quality,
            purpose = p?.purpose ?: purpose
        )
    }

    /** prepare 开始时刻：由 PlayerService 记录，用于计算 prepare→READY 耗时。 */
    @Volatile private var prepareStartByKey = mutableMapOf<String, Long>()

    @Synchronized
    fun markPrepareStart(songKey: String) {
        if (songKey.isBlank()) return
        prepareStartByKey[songKey] = System.currentTimeMillis()
    }

    /** READY(出声)：合并 resolve + prepare，产出完整记录；失败时只补层级。 */
    @Synchronized
    fun markReady(song: Song, prefetched: Boolean) {
        val key = song.key()
        if (key.isBlank()) return
        val p = pending[key]
        val resolveMs = p?.resolveMs ?: -1L
        val prepareMs = prepareStartByKey[key]?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: -1L
        val totalMs = if (p != null && prepareMs >= 0) (p.startMs.let { System.currentTimeMillis() - it }).coerceAtLeast(0L) else -1L
        push(
            Entry(
                songKey = key,
                songName = song.name.ifBlank { p?.songName.orEmpty() },
                singer = song.singer.ifBlank { p?.singer.orEmpty() },
                resolveMs = resolveMs,
                fromUrlCache = p?.fromUrlCache ?: false,
                scriptId = p?.scriptId.orEmpty(),
                platform = p?.platform.orEmpty(),
                originPlatform = p?.originPlatform.orEmpty(),
                quality = p?.quality.orEmpty(),
                prepareMs = prepareMs,
                totalMs = totalMs,
                prefetched = prefetched,
                purpose = p?.purpose.orEmpty()
            )
        )
        pending.remove(key)
        prepareStartByKey.remove(key)
    }

    /** 播放最终失败：记录失败层级，保留已拿到的 resolve 信息。 */
    @Synchronized
    fun markFailure(song: Song, layer: String) {
        val key = song.key()
        if (key.isBlank()) return
        val p = pending[key]
        push(
            Entry(
                songKey = key,
                songName = song.name.ifBlank { p?.songName.orEmpty() },
                singer = song.singer.ifBlank { p?.singer.orEmpty() },
                resolveMs = p?.resolveMs ?: -1L,
                fromUrlCache = p?.fromUrlCache ?: false,
                scriptId = p?.scriptId.orEmpty(),
                platform = p?.platform.orEmpty(),
                originPlatform = p?.originPlatform.orEmpty(),
                quality = p?.quality.orEmpty(),
                failureLayer = layer,
                purpose = p?.purpose.orEmpty()
            )
        )
        pending.remove(key)
        prepareStartByKey.remove(key)
    }

    /** 把播放错误文案映射为稳定失败层级，供诊断与分组。 */
    fun failureLayerOf(message: String): String {
        val text = message.lowercase()
        return when {
            message.contains("试听") -> "preview"
            text.contains("http 401") || text.contains("http 403") || text.contains("http 404") ||
                text.contains("http 410") || text.contains("http 5") -> "url"
            text.contains("decoder") || text.contains("unsupported") || text.contains("parser") -> "decoder"
            text.contains("timeout") || text.contains("timed out") -> "timeout"
            text.contains("network") || text.contains("connect") || text.contains("socket") ||
                text.contains("resolve failed") -> "network"
            else -> "other"
        }
    }

    private fun push(e: Entry) {
        memory.addLast(e)
        while (memory.size > MAX_MEMORY) memory.removeFirst()
        persist(e)
    }

    /** 持久化最近记录到 SharedPreferences；进程重启后设置页仍可查看/复制。 */
    private fun persist(e: Entry) {
        try {
            val ctx = appContext ?: return
            val prefs = ctx.getSharedPreferences(PREF, 0)
            var arr = try { JSONArray(prefs.getString(KEY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            if (arr.length() >= MAX_PERSIST) {
                val keep = org.json.JSONArray()
                for (i in (arr.length() - (MAX_PERSIST - 1)) until arr.length()) keep.put(arr.get(i))
                arr = keep
            }
            arr.put(
                JSONObject()
                    .put("k", e.songKey)
                    .put("n", e.songName)
                    .put("a", e.singer)
                    .put("r", e.resolveMs)
                    .put("c", e.fromUrlCache)
                    .put("s", e.scriptId)
                    .put("p", e.platform)
                    .put("op", e.originPlatform)
                    .put("q", e.quality)
                    .put("pms", e.prepareMs)
                    .put("t", e.totalMs)
                    .put("pf", e.prefetched)
                    .put("fl", e.failureLayer)
                    .put("u", e.purpose)
                    .put("ts", e.ts)
            )
            prefs.edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    @Volatile private var appContext: android.content.Context? = null

    /** 由 App 启动时调用一次，供持久化使用。 */
    fun init(context: android.content.Context) {
        val app = context.applicationContext
        appContext = app
        try { app.getSharedPreferences(PREF, 0).edit().remove(LEGACY_KEY_RECOMMEND).apply() } catch (_: Exception) {}
    }

    /** 最近 N 条持久化记录（进程重启后兜底），新→旧。 */
    fun persistedRecent(limit: Int = 20): List<Entry> {
        val ctx = appContext ?: return emptyList()
        return try {
            val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val out = mutableListOf<Entry>()
            for (i in (arr.length() - 1) downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Entry(
                        songKey = o.optString("k"),
                        songName = o.optString("n"),
                        singer = o.optString("a"),
                        resolveMs = o.optLong("r", -1L),
                        fromUrlCache = o.optBoolean("c", false),
                        scriptId = o.optString("s"),
                        platform = o.optString("p"),
                        originPlatform = o.optString("op"),
                        quality = o.optString("q"),
                        prepareMs = o.optLong("pms", -1L),
                        totalMs = o.optLong("t", -1L),
                        prefetched = o.optBoolean("pf", false),
                        failureLayer = o.optString("fl"),
                        purpose = o.optString("u"),
                        ts = o.optLong("ts", 0L)
                    )
                )
                if (out.size >= limit) break
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** 单条汇总行（全局导出与单曲导出共用同一格式）。 */
    internal fun summaryLine(e: Entry): String {
        val ok = e.failureLayer.isBlank()
        val total = if (e.totalMs >= 0) "${e.totalMs}ms" else "—"
        val resolve = if (e.resolveMs >= 0) "${e.resolveMs}ms" else "—"
        val prepare = if (e.prepareMs >= 0) "${e.prepareMs}ms" else "—"
        val src = when {
            !ok -> "失败:${e.failureLayer}"
            e.prefetched -> "预取"
            e.platform == "local" -> "本地文件"
            e.fromUrlCache -> "URL缓存"
            else -> "取链"
        }
        val q = e.quality.ifBlank { "" }
        val pu = e.purpose.ifBlank { "" }
        // 来源标识走 sourceLabel：实际/原始平台与脚本名，绝不拿原始哈希顶替平台位置。
        val plat = if (e.failureLayer.isNotBlank()) "" else sourceLabel(e)
        val tag = listOf(pu, plat, src, q).filter { it.isNotBlank() }.joinToString("·")
        val who = if (e.singer.isNotBlank()) " — ${e.singer}" else ""
        return "${e.songName.ifBlank { e.songKey }}$who [$tag] 总$total 取链$resolve 准备$prepare"
    }

    /** 完整诊断文本（内存 + 持久化合并去重），供设置页“复制诊断”一键发送全部记录。 */
    fun diagnosticsText(): String {
        val mem = recent(50)
        val memKeys = mem.map { "${it.ts}_${it.songKey}" }.toSet()
        val persisted = persistedRecent(50).filter { "${it.ts}_${it.songKey}" !in memKeys }
        val all = (mem + persisted).sortedByDescending { it.ts }.take(30)
        val sb = StringBuilder()
        sb.appendLine("=== SoundWave 播放诊断（全部 · 最近 ${all.size} 条）===")
        all.forEach { e -> sb.appendLine(summaryLine(e)) }
        return sb.toString().trimEnd()
    }

    /**
     * 单曲诊断文本（播放路径弹层「复制诊断」专用）：只含这一首歌的汇总行
     * 与它自己的播放路径时间线，不混入其它歌曲的记录。
     */
    fun songDiagnosticsText(entry: Entry): String = songDiagnosticsText(entry, emptyList())

    /**
     * ACCEPT4（修7）：带真实逐次尝试的单曲诊断文本。有 attempts 留存时时间线用
     * [playbackPathStepsWithAttempts]（真实阶段/耗时/拒收原文），不再只导合成四步
     * 的"+0s 假即时失败"；无留存时回退原合成时间线。
     */
    fun songDiagnosticsText(entry: Entry, attempts: List<ResolveAttempt>, plannedStages: List<String> = emptyList()): String {
        val sb = StringBuilder()
        sb.appendLine("=== SoundWave 播放诊断 · 单曲 ===")
        sb.appendLine(summaryLine(entry))
        // ACCEPT5：与路径弹层共用同一构建器与同一时钟基（attempts 的开始偏移
        // 锚定解析起点）+ 同一完整计划口径，文本与弹层不得再各说一套。
        val steps = if (attempts.isNotEmpty()) playbackPathStepsWithAttempts(entry, attempts, plannedStages)
            else playbackPathSteps(entry)
        if (steps.isNotEmpty()) {
            sb.appendLine("播放路径：")
            steps.forEach { s ->
                val detail = if (s.detail.isNotBlank()) " — ${s.detail}" else ""
                sb.appendLine("${playbackPathTime(s.atMs)} ${s.title}$detail")
            }
        }
        return sb.toString().trimEnd()
    }

    /** 单曲暂无取链记录时的诊断文本：只写明这首歌没有记录，不借用全局导出。 */
    fun songDiagnosticsText(songName: String, singer: String): String {
        val who = if (singer.isNotBlank()) " — $singer" else ""
        return "=== SoundWave 播放诊断 · 单曲 ===\n${songName.ifBlank { "未知歌曲" }}$who\n暂无取链记录"
    }

    /** 最近 N 条（新→旧），供设置页展示。 */
    fun recent(limit: Int = 20): List<Entry> {
        val snap = memory.toList().reversed()
        return if (snap.size <= limit) snap else snap.take(limit)
    }

    fun recentSummary(limit: Int = 12): String {
        val list = recent(limit)
        if (list.isEmpty()) return "暂无播放记录，播放一首歌后这里会显示各阶段耗时。"
        return list.joinToString("\n") { e ->
            val name = e.songName.ifBlank { "未知歌曲" }
            val ok = e.failureLayer.isBlank()
            val total = if (e.totalMs >= 0) "${e.totalMs}ms" else "—"
            val resolve = if (e.resolveMs >= 0) "${e.resolveMs}ms" else "—"
            val prepare = if (e.prepareMs >= 0) "${e.prepareMs}ms" else "—"
            val src = when {
                !ok -> "失败:${e.failureLayer}"
                e.prefetched -> "预取命中"
                e.platform == "local" -> "本地文件"
                e.fromUrlCache -> "URL缓存"
                else -> "取链"
            }
            val q = e.quality.ifBlank { "" }
            // 设置页诊断列表来源标签（与导出一致，走 sourceLabel，不打印原始来源串）。
            val plat = if (e.failureLayer.isNotBlank()) "" else sourceLabel(e)
            val tag = listOf(plat, src, q).filter { it.isNotBlank() }.joinToString("·")
            "$name  [$tag] 总$total 取链$resolve 准备$prepare"
        }
    }

    /**
     * 用户可读的换源链路摘要。仅使用本地诊断中的平台、脚本和音质元数据，
     * 不暴露临时 URL、Cookie 或上游响应正文。
     */
    fun latestExplain(song: Song): String {
        val entry = recent(MAX_MEMORY).firstOrNull { it.songKey == song.key() } ?: return "暂无本次解析记录"
        if (entry.failureLayer.isNotBlank()) return "本次失败：${entry.failureLayer}"
        // 平台只认规范化后的码（实际→原始→歌曲来源），历史哈希值不得原样展示。
        val source = canonicalPlatform(entry.platform)
            .ifEmpty { canonicalPlatform(entry.originPlatform) }
            .ifEmpty { canonicalPlatform(song.source) }
        val sourceText = if (source.isEmpty()) "未知平台" else platDisplay(source)
        val script = entry.scriptId.ifBlank { "本地缓存" }
        val quality = entry.quality.ifBlank { "未知音质" }
        val route = if (entry.fromUrlCache) "命中 URL 缓存" else if (entry.prefetched) "命中预取" else "脚本取链"
        val elapsed = entry.resolveMs.takeIf { it >= 0 }?.let { " · 取链 ${it}ms" }.orEmpty()
        return "$route · $sourceText · $quality · 脚本 $script$elapsed"
    }

    /** 仅用于失败 toast/设置页，给出稳定的中文语义而非内部枚举。 */
    fun explainFailure(layer: String): String = when (layer) {
        "script" -> "脚本没有返回可播放链接"
        "url" -> "播放链接已失效或被上游拒绝"
        "decoder" -> "音频格式暂不能播放"
        "preview" -> "上游仅返回试听片段"
        "timeout" -> "取链或播放准备超时"
        "network" -> "网络连接中断"
        "" -> ""
        else -> "播放链路失败"
    }

    fun clear() {
        synchronized(this) {
            memory = ArrayDeque()
            pending = mutableMapOf()
            prepareStartByKey = mutableMapOf()
        }
        failureStageTallies.clear()
    }
}
