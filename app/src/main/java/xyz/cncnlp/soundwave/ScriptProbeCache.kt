package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import xyz.cncnlp.soundwave.download.AudioQualityEvidence
import xyz.cncnlp.soundwave.download.AudioQualityProbe
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-script real-link probe cache.  A record contains every platform × quality
 * attempt, including explicit unsupported/failed/excluded outcomes; it never
 * treats an URL alone as a playable result.
 */
object ScriptProbeCache {
    // v4 separates quick/full reports; v3 already dropped sizes that could mistake a 206 chunk for the whole file.
    private const val PREF = "script_probe_cache_v4"
    private const val LEGACY_PREF = "script_probe_cache_v3"
    private const val K_QUICK_RECORDS = "quick_records"
    private const val K_FULL_RECORDS = "full_records"
    private const val MAX_RECORDS = 80
    private const val MASKED = "__redacted__"
    private const val INVALID_URL_HINT = "http(s)://[地址已隐藏]"
    private val EMBEDDED_HTTP_URL = Regex("(?i)https?://[^\\s<>\"']+")
    private val SENSITIVE_PATH_MARKER = Regex("(?i)(^|[._-])(token|sig|signature|signed|auth|key|secret|credential|jwt|access)([._-]|$)")
    private val MEDIA_EXTENSIONS = setOf("aac", "aiff", "ape", "flac", "m4a", "m3u8", "mp3", "mp4", "ogg", "opus", "wav", "wma")

    /** Safe diagnostic URL summary; never returns the input string on parse failure. */
    fun safeUrlSummary(raw: String): String {
        if (raw.isBlank()) return ""
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return INVALID_URL_HINT
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || host.isNullOrBlank() || host.any { it.isWhitespace() || it == '@' }) {
            return INVALID_URL_HINT
        }
        if (uri.port !in -1..65535) return INVALID_URL_HINT
        val displayHost = when {
            host.startsWith("[") && host.endsWith("]") -> host
            host.contains(':') -> "[$host]"
            else -> host
        }
        return buildString {
            append(scheme).append("://").append(displayHost)
            if (uri.port > 0) append(':').append(uri.port)
            append(safePathHint(uri.rawPath.orEmpty()))
            uri.rawQuery?.takeIf { it.isNotBlank() }?.let { append(safeQueryHint(it)) }
        }
    }

    /** Removes embedded raw URLs from report/log-bound diagnostic text. */
    internal fun safeDiagnosticText(value: String): String = EMBEDDED_HTTP_URL.replace(value) { match ->
        val text = match.value
        val punctuation = text.takeLastWhile { it in ".,;:!?)]}" }
        safeUrlSummary(text.dropLast(punctuation.length)) + punctuation
    }

    private fun safePathHint(rawPath: String): String {
        if (rawPath.isBlank() || rawPath == "/") return rawPath
        val segments = rawPath.split('/').filter { it.isNotEmpty() }.takeLast(4)
        if (segments.isEmpty()) return if (rawPath.startsWith('/')) "/" else ""
        var maskFollowingSegment = false
        val safeSegments = segments.mapIndexed { index, rawSegment ->
            val decoded = decodeUrlComponent(rawSegment)
            val extension = if (index == segments.lastIndex) mediaExtension(decoded) else null
            val sensitive = looksSensitivePathSegment(decoded)
            val output = when {
                sensitive || maskFollowingSegment || decoded.length > 64 -> extension?.let { "$MASKED.$it" } ?: MASKED
                decoded == MASKED || decoded.startsWith("$MASKED.") -> decoded
                else -> encodeUrlComponent(decoded)
            }
            maskFollowingSegment = SENSITIVE_PATH_MARKER.containsMatchIn(decoded)
            output
        }
        return (if (rawPath.startsWith('/')) "/" else "") + safeSegments.joinToString("/")
    }

    private fun safeQueryHint(rawQuery: String): String {
        val names = rawQuery.split('&').take(8).mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val decodedName = decodeUrlComponent(pair.substringBefore('='))
            val safeName = decodedName.filter { it.isLetterOrDigit() || it in "._-" }.take(40).ifBlank { "param" }
            "${encodeUrlComponent(safeName)}=$MASKED"
        }
        return if (names.isEmpty()) "" else "?" + names.joinToString("&")
    }

    private fun mediaExtension(pathSegment: String): String? =
        Regex("\\.([A-Za-z0-9]{1,8})$").find(pathSegment)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?.takeIf { it in MEDIA_EXTENSIONS }

    private fun looksSensitivePathSegment(segment: String): Boolean {
        val stem = segment.substringBeforeLast('.', segment)
        if (SENSITIVE_PATH_MARKER.containsMatchIn(stem) && stem.lowercase(Locale.ROOT) !in setOf(
                "token", "sig", "signature", "signed", "auth", "key", "secret", "credential", "jwt", "access"
            )) return true
        if (Regex("(?i)[0-9a-f]{24,}").matches(stem) || Regex("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}").matches(stem)) return true
        if (stem.length >= 48) return true
        if (stem.length >= 24 && stem.matches(Regex("[A-Za-z0-9_-]+"))) {
            val characterClasses = listOf(
                stem.any(Char::isLowerCase), stem.any(Char::isUpperCase),
                stem.any(Char::isDigit), stem.any { it == '_' || it == '-' }
            ).count { it }
            if (characterClasses >= 3) return true
        }
        return false
    }

    private fun decodeUrlComponent(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }

    private fun encodeUrlComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20").replace("%7E", "~")

    @Volatile private var prefs: SharedPreferences? = null
    private val mutableReportRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val reportRevision = mutableReportRevision.asStateFlow()
    private val reportListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == K_QUICK_RECORDS || key == K_FULL_RECORDS) {
            mutableReportRevision.value += 1L
        }
    }

    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        val current = xyz.cncnlp.soundwave.ContextPreferences.open(app, PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        migrateLegacy(xyz.cncnlp.soundwave.ContextPreferences.open(app, LEGACY_PREF, 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()), current)
        prefs = current
        current.registerOnSharedPreferenceChangeListener(reportListener)
    }

    private fun p(): SharedPreferences = prefs ?: error("ScriptProbeCache not init")

    enum class Outcome { OK, UNSUPPORTED, NO_MATCH, FAILED, EXCLUDED }

    enum class ReportType(val label: String) {
        /** 旧版定时抽检记录（r658c S 起不再生成，仅兼容读取展示）。 */
        QUICK("快速抽检"),
        FULL("完整检测")
    }

    data class Attempt(
        val platform: String,
        val quality: String,
        val outcome: Outcome,
        /** 脚本返回可验证 URL 的耗时（不是搜索耗时）。 */
        val resolveMs: Long = -1L,
        /** Range 读取首字节耗时；无法读取不计入速度成功。 */
        val firstByteMs: Long = -1L,
        val firstByteReceived: Boolean = false,
        val bytes: Long = -1L,
        val mime: String = "",
        /** 音频容器/响应、大小、真实时长均通过后才为 true。 */
        val playable: Boolean = false,
        val reason: String = "",
        val urlHint: String = "",
        /** 实际媒体响应状态；例如 HTTP 200/206/401，而不是仅记录取链是否返回 URL。 */
        val httpStatus: Int = 0,
        val sampleName: String = "",
        val sampleSinger: String = "",
        /** Fixed matrix query, separate from the actual search-result title/artist above. */
        val sampleQueryName: String = "",
        val sampleQuerySinger: String = "",
        val sampleDurationMs: Long = 0L,
        val searchMs: Long = -1L,
        val requestDescription: String = "",
        val requestMethod: String = "",
        val rangeHeader: String = "",
        val contentRange: String = "",
        val sizeSource: String = "",
        val formatHint: String = "",
        val formatEvidence: String = ""
    ) {
        val latencyMs: Long get() = when {
            firstByteMs > 0 && resolveMs > 0 -> resolveMs + firstByteMs
            resolveMs > 0 -> resolveMs
            else -> -1L
        }
        fun toJson() = JSONObject()
            .put("p", platform).put("q", quality).put("o", outcome.name)
            .put("resolve", resolveMs).put("fb", firstByteMs).put("fbReceived", firstByteReceived).put("bytes", bytes)
            .put("mime", safeDiagnosticText(mime)).put("http", httpStatus).put("playable", playable)
            .put("reason", safeDiagnosticText(reason)).put("urlHint", safeUrlSummary(urlHint))
            .put("sampleName", safeDiagnosticText(sampleName)).put("sampleSinger", safeDiagnosticText(sampleSinger)).put("duration", sampleDurationMs)
            .put("sampleQueryName", safeDiagnosticText(sampleQueryName)).put("sampleQuerySinger", safeDiagnosticText(sampleQuerySinger))
            .put("searchMs", searchMs).put("request", safeDiagnosticText(requestDescription)).put("method", safeDiagnosticText(requestMethod))
            .put("range", safeDiagnosticText(rangeHeader)).put("contentRange", safeDiagnosticText(contentRange)).put("sizeSource", safeDiagnosticText(sizeSource))
            .put("format", safeDiagnosticText(formatHint)).put("formatEvidence", safeDiagnosticText(formatEvidence))

        companion object {
            fun fromJson(o: JSONObject) = Attempt(
                platform = o.optString("p"), quality = o.optString("q"),
                outcome = runCatching { Outcome.valueOf(o.optString("o")) }.getOrDefault(Outcome.FAILED),
                resolveMs = o.optLong("resolve", o.optLong("ms", -1L)),
                firstByteMs = o.optLong("fb", -1L), firstByteReceived = o.optBoolean("fbReceived", false), bytes = o.optLong("bytes", -1L),
                mime = safeDiagnosticText(o.optString("mime")), playable = o.optBoolean("playable", false),
                reason = safeDiagnosticText(o.optString("reason")), urlHint = safeUrlSummary(o.optString("urlHint")), httpStatus = o.optInt("http", 0),
                sampleName = safeDiagnosticText(o.optString("sampleName")), sampleSinger = safeDiagnosticText(o.optString("sampleSinger")),
                sampleQueryName = safeDiagnosticText(o.optString("sampleQueryName")), sampleQuerySinger = safeDiagnosticText(o.optString("sampleQuerySinger")),
                sampleDurationMs = o.optLong("duration", 0L), searchMs = o.optLong("searchMs", -1L),
                requestDescription = safeDiagnosticText(o.optString("request")), requestMethod = safeDiagnosticText(o.optString("method")),
                rangeHeader = safeDiagnosticText(o.optString("range")), contentRange = safeDiagnosticText(o.optString("contentRange")),
                sizeSource = safeDiagnosticText(o.optString("sizeSource")), formatHint = safeDiagnosticText(o.optString("format")),
                formatEvidence = safeDiagnosticText(o.optString("formatEvidence"))
            )
        }
    }

    /** J-健康度：由最近一次现行检测报告派生的行内健康摘要（纯展示，不参与解析决策）。 */
    data class ScriptHealth(val state: State, val avgMs: Long) {
        enum class State { USABLE, STOPPED, FAILED, NONE, SKIPPED }
        val label: String get() = when (state) {
            State.USABLE -> if (avgMs > 0) "可用 · 平均 ${formatAvgMs(avgMs)}" else "可用"
            State.STOPPED -> "已停服"
            State.FAILED -> "检测未通过"
            State.NONE -> ""
            State.SKIPPED -> "已跳过"
        }
        companion object {
            // 显示侧停服判定：仅当现行报告无任何可用链接、且失败原因被终局分类器判为脚本自报
            // 终局（停服/版本类）时才标「已停服」。短语集单一事实源 = speed-h1 的
            // ScriptTerminalErrorClassifier（与 ScriptVerdictStore 的持久裁决同一套短语，
            // 收尾棒对表后本类不再自维护 STOP_PHRASES）；此处不写任何存储、不影响解析。
            internal fun formatAvgMs(ms: Long): String =
                if (ms < 1000) "${ms}ms" else "${ms / 1000}.${(ms % 1000) / 100}s"
            fun of(report: Report?): ScriptHealth {
                if (report == null) return ScriptHealth(State.NONE, -1L)
                // ACCEPT5：跳过/中断的定论记录优先于「无报告」——它有明确结论，只是没执行检测。
                if (report.skippedReason.isNotBlank()) return ScriptHealth(State.SKIPPED, -1L)
                if (report.attempts.isEmpty()) return ScriptHealth(State.NONE, -1L)
                if (report.valid.isNotEmpty()) return ScriptHealth(State.USABLE, report.avgMs)
                val stopped = report.attempts.any { a -> ScriptTerminalErrorClassifier.isTerminal(a.reason) }
                return if (stopped) ScriptHealth(State.STOPPED, -1L) else ScriptHealth(State.FAILED, -1L)
            }
        }
    }

    data class Report(
        val scriptId: String,
        val scriptName: String,
        val attempts: List<Attempt>,
        val checkedAt: Long = System.currentTimeMillis(),
        /** 绑定脚本内容，避免脚本更新后继续展示旧检测结论。 */
        val scriptHash: String = "",
        val runtimeAbi: String = RUNTIME_ABI,
        val type: ReportType = ReportType.FULL,
        val complete: Boolean = true,
        /** r658c S：本次覆盖说明（按脚本声明的平台 × 音质；未检测平台及原因）。 */
        val coverage: String = "",
        /** ACCEPT5：本轮检测未执行的原因（批量被播放抢占跳过 / 检测异常中断）。
         *  非空 = 定论记录而非检测结果：attempts 恒为空、complete 恒为 false，
         *  不参与有效性评分与自动配置，仅供状态展示与报告说明。 */
        val skippedReason: String = ""
    ) {
        val valid: List<Attempt> get() = attempts.filter {
            it.outcome == Outcome.OK && it.playable && it.firstByteMs > 0L && (it.bytes > 0L || it.reason.contains("长度未知"))
        }
        /** 最高验证音质先决定排序层；同层才比较速度。 */
        val highestVerifiedQuality: Int get() = valid.maxOfOrNull { qualityWeight(it.quality) } ?: 0
        val avgMs: Long get() = valid.map { it.latencyMs }.filter { it > 0L }.let { if (it.isEmpty()) -1L else it.average().toLong() }
        val qualityScore: Int get() = valid.sumOf { qualityWeight(it.quality) }
        val speedScore: Int get() = valid.sumOf { speedWeight(it.latencyMs) }
        val combinedScore: Int get() = highestVerifiedQuality * 1_000_000 + qualityScore * 1000 + speedScore * 10 + valid.size

        fun toJson() = JSONObject().put("id", scriptId).put("name", safeDiagnosticText(scriptName)).put("ts", checkedAt)
            .put("sha256", scriptHash).put("abi", runtimeAbi).put("type", type.name).put("complete", complete)
            .put("cov", safeDiagnosticText(coverage)).put("skip", safeDiagnosticText(skippedReason))
            .put("a", JSONArray(attempts.map { it.toJson() }))

        companion object {
            fun fromJson(o: JSONObject): Report {
                val a = o.optJSONArray("a") ?: JSONArray()
                val typeName = o.optString("type", o.optString("mode", "full")).uppercase(Locale.ROOT)
                val type = if (typeName == "QUICK") ReportType.QUICK else ReportType.FULL
                return Report(
                    scriptId = o.optString("id"),
                    scriptName = safeDiagnosticText(o.optString("name")),
                    attempts = List(a.length()) { Attempt.fromJson(a.optJSONObject(it) ?: JSONObject()) },
                    checkedAt = o.optLong("ts"),
                    scriptHash = o.optString("sha256"),
                    runtimeAbi = o.optString("abi", RUNTIME_ABI),
                    type = type,
                    complete = o.optBoolean("complete", type == ReportType.FULL),
                    coverage = safeDiagnosticText(o.optString("cov")),
                    skippedReason = safeDiagnosticText(o.optString("skip"))
                )
            }
        }
    }

    const val RUNTIME_ABI = "lx-android-1"

    fun scriptHash(code: String): String = MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    enum class SortMode(val label: String) {
        QUALITY("质量优先"), SPEED("速度优先"), BALANCED("综合")
    }

    /** A read-only plan based exclusively on verified successes in current full reports. */
    data class AutoConfigurationPlan(
        val reportCount: Int,
        val verifiedAttemptCount: Int,
        val platformOrder: List<String>,
        val platformScriptOrders: Map<String, List<String>>,
        val platformQualities: Map<String, String>,
        val globalScriptOrder: List<String>
    ) {
        val hasVerifiedSuccess: Boolean get() = verifiedAttemptCount > 0
    }

    /**
     * Creates a configuration plan without probing. Unverified scripts/platforms stay after verified
     * entries (or retain their prior platform order); no failed, unmatched, missing or explicitly
     * unsupported attempt is interpreted as a capability result.
     */
    internal fun buildAutoConfigurationPlan(
        scripts: List<Script>,
        reports: List<Report>,
        platformCodes: List<String>
    ): AutoConfigurationPlan {
        val usableScripts = scripts.filter { it.code.isNotBlank() }.distinctBy { it.id }
        val scriptById = usableScripts.associateBy { it.id }
        val currentReports = reports.asSequence()
            .filter { report -> scriptById[report.scriptId]?.let { isValidFullReport(report, it) } == true }
            .groupBy { it.scriptId }
            .mapValues { (_, candidates) -> candidates.maxBy { it.checkedAt } }
        val scriptIds = usableScripts.map { it.id }
        val successCount = currentReports.values.sumOf { it.valid.size }
        val basePlatforms = platformCodes.distinct()

        fun successesFor(platform: String): List<Pair<Report, List<Attempt>>> = currentReports.values.mapNotNull { report ->
            report.valid.filter { it.platform == platform }.takeIf { it.isNotEmpty() }?.let { report to it }
        }

        fun platformAverageLatency(platform: String): Long {
            val perScript = successesFor(platform).mapNotNull { (_, attempts) ->
                val topTier = attempts.maxOfOrNull { AudioQualityEvidence.qualityRank(it.quality) } ?: return@mapNotNull null
                attempts.asSequence()
                    .filter { AudioQualityEvidence.qualityRank(it.quality) == topTier }
                    .map { it.latencyMs }
                    .filter { it > 0L }
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.average()
            }
            return if (perScript.isEmpty()) Long.MAX_VALUE else perScript.average().toLong()
        }

        val platformIndexes = basePlatforms.withIndex().associate { it.value to it.index }
        val rankedPlatforms = basePlatforms.filter { successesFor(it).isNotEmpty() }
            .sortedWith(compareBy<String> { platformAverageLatency(it) }.thenBy { platformIndexes[it] ?: Int.MAX_VALUE })
        val configuredPlatforms = rankedPlatforms + basePlatforms.filterNot { it in rankedPlatforms }

        val platformScriptOrders = rankedPlatforms.associateWith { platform ->
            val verifiedIds = scriptIds.filter { id ->
                currentReports[id]?.valid?.any { it.platform == platform } == true
            }
            val originalIndexes = scriptIds.withIndex().associate { it.value to it.index }
            val rankedIds = verifiedIds.sortedWith(
                compareByDescending<String> { id ->
                    currentReports[id]?.let { sortValue(it, SortMode.BALANCED, platform = platform) } ?: Long.MIN_VALUE
                }.thenBy { originalIndexes[it] ?: Int.MAX_VALUE }
            )
            rankedIds + scriptIds.filterNot { it in rankedIds }
        }

        val platformQualities = rankedPlatforms.mapNotNull { platform ->
            val best = successesFor(platform).flatMap { it.second }
                .maxByOrNull { AudioQualityEvidence.qualityRank(it.quality) }
            best?.quality?.let { platform to it }
        }.toMap()

        val originalIndexes = scriptIds.withIndex().associate { it.value to it.index }
        val verifiedScriptIds = scriptIds.filter { currentReports[it]?.valid?.isNotEmpty() == true }
        val globalRankedIds = verifiedScriptIds.sortedWith(
            compareByDescending<String> { id ->
                currentReports[id]?.let { sortValue(it, SortMode.BALANCED) } ?: Long.MIN_VALUE
            }.thenBy { originalIndexes[it] ?: Int.MAX_VALUE }
        )

        return AutoConfigurationPlan(
            reportCount = currentReports.size,
            verifiedAttemptCount = successCount,
            platformOrder = configuredPlatforms,
            platformScriptOrders = platformScriptOrders,
            platformQualities = platformQualities,
            globalScriptOrder = globalRankedIds + scriptIds.filterNot { it in globalRankedIds }
        )
    }

    private fun recordsKey(type: ReportType): String =
        if (type == ReportType.QUICK) K_QUICK_RECORDS else K_FULL_RECORDS

    private fun migrateLegacy(legacy: SharedPreferences, current: SharedPreferences) {
        runCatching {
            val old = JSONObject(legacy.getString("records", "{}") ?: "{}")
            val quickRecords = JSONObject(current.getString(K_QUICK_RECORDS, "{}") ?: "{}")
            val fullRecords = JSONObject(current.getString(K_FULL_RECORDS, "{}") ?: "{}")
            var quickChanged = false
            var fullChanged = false
            val editor = current.edit()
            for (id in old.keys()) {
                val raw = old.optJSONObject(id) ?: continue
                val report = Report.fromJson(raw)
                if (report.scriptId.isBlank()) continue
                val records = if (report.type == ReportType.QUICK) quickRecords else fullRecords
                val existing = records.optJSONObject(report.scriptId)
                if (existing == null || existing.optLong("ts") <= report.checkedAt) {
                    records.put(report.scriptId, report.toJson())
                    if (report.type == ReportType.QUICK) quickChanged = true else fullChanged = true
                }
            }
            if (quickChanged) editor.putString(K_QUICK_RECORDS, quickRecords.toString())
            if (fullChanged) editor.putString(K_FULL_RECORDS, fullRecords.toString())
            editor.apply()
        }
    }

    fun save(report: Report) {
        if (report.scriptId.isBlank()) return
        try {
            val key = recordsKey(report.type)
            val root = JSONObject(p().getString(key, "{}") ?: "{}")
            root.put(report.scriptId, report.toJson())
            if (root.length() > MAX_RECORDS) {
                val old = root.keys().asSequence().map { id -> id to (root.optJSONObject(id)?.optLong("ts") ?: 0L) }
                    .sortedBy { it.second }.take(root.length() - MAX_RECORDS)
                old.forEach { root.remove(it.first) }
            }
            p().edit().putString(key, root.toString()).apply()
        } catch (_: Exception) { }
    }

    /**
     * ACCEPT5：为「本轮检测未执行」落一条定论记录（批量被播放抢占跳过、单检被抢占、
     * 或检测异常中断）。没有这条记录时行状态会回落成「尚未检测」，把跳过伪装成没检过。
     * 记录 complete=false，不进 fullReport/评分/自动配置（那些路径都要求完整报告）。
     */
    fun saveProbeSkipped(script: Script, reason: String) {
        if (script.code.isBlank()) return
        save(Report(
            scriptId = script.id,
            scriptName = script.name,
            attempts = emptyList(),
            scriptHash = scriptHash(script.code),
            type = ReportType.FULL,
            complete = false,
            coverage = "本轮检测未执行",
            skippedReason = reason
        ))
    }

    fun reportData(id: String, type: ReportType): Report? = try {
        JSONObject(p().getString(recordsKey(type), "{}") ?: "{}").optJSONObject(id)?.let(Report::fromJson)
    } catch (_: Exception) { null }

    /** 详情/状态展示的检测报告：优先完整检测记录；只有旧版抽检记录时才回退展示它。 */
    fun latestReport(id: String): Report? =
        reportData(id, ReportType.FULL) ?: reportData(id, ReportType.QUICK)

    fun fullReport(script: Script): Report? = reportData(script.id, ReportType.FULL)
        ?.takeIf { isValidFullReport(it, script) }

    fun isReportCurrent(report: Report, script: Script): Boolean =
        report.scriptId == script.id && script.code.isNotBlank() &&
            report.scriptHash == scriptHash(script.code) && report.runtimeAbi == RUNTIME_ABI

    internal fun isValidFullReport(report: Report, script: Script): Boolean =
        report.type == ReportType.FULL && report.complete && isReportCurrent(report, script)

    fun score(script: Script): Int = fullReport(script)?.combinedScore ?: -1
    fun avgMs(script: Script): Long = fullReport(script)?.avgMs ?: -1L
    fun report(id: String): String? = latestReport(id)?.let(::format)
    fun report(report: Report): String = format(report)

    fun summaryLine(script: Script): String? = latestReport(script.id)?.let { report ->
        if (!isReportCurrent(report, script)) return@let "上次检测已过期 · 脚本内容或运行环境已变化 · 请重新检测"
        if (report.skippedReason.isNotBlank()) return@let "检测已跳过 · ${report.skippedReason} · 播放不受影响，可重新检测"
        val outcomes = report.attempts.groupingBy { it.outcome }.eachCount()
        val ok = report.valid.size
        "${report.type.label} · 有效 $ok · 无可验证链接 ${outcomes[Outcome.UNSUPPORTED] ?: 0} · 样本未匹配 ${outcomes[Outcome.NO_MATCH] ?: 0} · 失败 ${outcomes[Outcome.FAILED] ?: 0} · 排除 ${outcomes[Outcome.EXCLUDED] ?: 0} · ${report.attempts.size} 项实际尝试"
    }

    private fun qualityWeight(q: String) = AudioQualityEvidence.qualityRank(q) * 6

    /**
     * 平台维度排序：只比较该平台的完整有效音频链。
     * 综合模式以 FLAC 为“足够高质量”门槛：有 FLAC/更高链时先在该组比速度，
     * 没有时才退到脚本可验证的最高质量，避免 master 仅因更高档而压过快 FLAC。
     */
    fun sortValue(script: Script, mode: SortMode, platform: String = "", quality: String = ""): Long {
        val r = fullReport(script) ?: return Long.MIN_VALUE
        return sortValue(r, mode, platform, quality)
    }

    private fun sortValue(r: Report, mode: SortMode, platform: String = "", quality: String = ""): Long {
        val valid = r.valid.filter { (platform.isBlank() || it.platform == platform) && (quality.isBlank() || it.quality == quality) }
        if (valid.isEmpty()) return Long.MIN_VALUE + 1
        val high = valid.maxOf { qualityWeight(it.quality) }
        val fastest = valid.minOfOrNull { it.latencyMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        val flacOrBetter = valid.filter { qualityWeight(it.quality) >= qualityWeight("flac") }
        val balancedPool = flacOrBetter.ifEmpty { valid }
        val balancedFastest = balancedPool.minOfOrNull { it.latencyMs.takeIf { ms -> ms > 0 } ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        return when (mode) {
            // 最高已验证音质优先，同档再比真实完整链路速度。
            SortMode.QUALITY -> high.toLong() * 1_000_000_000L + (1_000_000L - fastest.coerceAtMost(999_999L))
            // 不论音质，只比较已验证完整链最快一次；无首字节验证不参与。
            SortMode.SPEED -> if (fastest == Long.MAX_VALUE) Long.MIN_VALUE + 1 else 1_000_000_000L - fastest.coerceAtMost(999_999_999L)
            // 优先有 FLAC+ 的支持度；之后按该质量池的真实速度，不被其它平台污染。
            SortMode.BALANCED -> (if (flacOrBetter.isNotEmpty()) 2L else 1L) * 1_000_000_000L +
                (1_000_000L - balancedFastest.coerceAtMost(999_999L)) * 1_000L + high
        }
    }

    fun format(r: Report): String = buildString {
        append(safeDiagnosticText(r.scriptName.ifBlank { r.scriptId })).append("\n")
        append(if (r.type == ReportType.FULL) "检测报告" else "检测报告（旧版快速抽检记录）").append(" · ")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(r.checkedAt)))
            .append(" · ").append(r.type.label)
            .append(if (r.type == ReportType.FULL && r.complete) "（完整报告）" else "（非完整报告）")
            .append(" · ").append(r.attempts.size).append(" 项实际尝试")
            .append(" · 涉及 ").append(r.attempts.map { platformName(it.platform) }.distinct().joinToString("、").ifBlank { "无" })
            .append(" / 请求档 ").append(r.attempts.map { it.quality }.distinct().joinToString("、").ifBlank { "无" })
            .append(" · 有效 ").append(r.valid.size).append(" 条 · 最高已验证请求档权重 ").append(r.highestVerifiedQuality)
        if (r.avgMs > 0) append(" · 平均 ").append(r.avgMs).append("ms")
        append("\n请求档评分 ").append(r.qualityScore).append(" · 速度分 ").append(r.speedScore).append(" · 综合 ").append(r.combinedScore).append("\n\n")
        if (r.skippedReason.isNotBlank()) {
            append("本轮检测未执行：").append(safeDiagnosticText(r.skippedReason))
                .append("。这不是检测失败——该脚本本轮没有被实际检测，以下计数均为 0 项尝试的空结果；播放不受影响，可重新检测。\n\n")
        }
        append("实际样本×请求音质覆盖：")
        append(r.attempts.groupBy {
            Triple(it.platform, it.sampleQueryName.ifBlank { it.sampleName }, it.sampleQuerySinger.ifBlank { it.sampleSinger })
        }.entries.joinToString("；") { (sampleKey, sampleAttempts) ->
            val name = safeDiagnosticText(sampleKey.second.ifBlank { "未知样本" })
            val singer = sampleKey.third.takeIf { it.isNotBlank() }?.let { " - ${safeDiagnosticText(it)}" }.orEmpty()
            "${platformName(sampleKey.first)}/$name$singer：${sampleAttempts.map { it.quality }.distinct().joinToString("、")}"
        }.ifBlank { "无" })
        if (r.coverage.isNotBlank()) append("\n覆盖范围：").append(safeDiagnosticText(r.coverage))
        val outcomes = r.attempts.groupingBy { it.outcome }.eachCount()
        append("\n逐项结果：成功 ").append(r.valid.size).append(" · 无可验证链接 ").append(outcomes[Outcome.UNSUPPORTED] ?: 0)
            .append(" · 样本未匹配 ").append(outcomes[Outcome.NO_MATCH] ?: 0).append(" · 失败 ").append(outcomes[Outcome.FAILED] ?: 0)
            .append(" · 排除 ").append(outcomes[Outcome.EXCLUDED] ?: 0)
        if (r.type == ReportType.QUICK) {
            append("\n快速抽检只覆盖报告标出的本轮平台与档位；未出现在组合清单中的平台/音质不代表失败或不支持，而是本次未探测；失败、无可验证链接、样本未匹配与排除仅按各自尝试结果记录。\n\n")
        } else {
            append("\n完整检测逐项遍历脚本声明支持的全部平台（每平台全部固定样本）与全部音质；脚本未声明的平台不在本报告内，表示本次未探测而非失败；失败、无可验证链接、样本未匹配与排除仅按各自尝试结果记录。\n\n")
        }
        r.attempts.groupBy { it.platform }.forEach { (platform, attempts) ->
            append(platformName(platform)).append("\n")
            attempts.groupBy {
                Triple(it.sampleQueryName.ifBlank { it.sampleName }, it.sampleQuerySinger.ifBlank { it.sampleSinger }, it.sampleDurationMs)
            }.forEach { (sampleKey, sampleAttempts) ->
                append("  样本查询: ").append(safeDiagnosticText(sampleKey.first.ifBlank { "未知样本" }))
                if (sampleKey.second.isNotBlank()) append(" - ").append(safeDiagnosticText(sampleKey.second))
                val matched = sampleAttempts.firstOrNull { it.sampleName.isNotBlank() }
                if (matched != null) {
                    append(" · 匹配到: ").append(safeDiagnosticText(matched.sampleName))
                    if (matched.sampleSinger.isNotBlank()) append(" - ").append(safeDiagnosticText(matched.sampleSinger))
                    if (matched.sampleDurationMs > 0L) append(" · ").append(formatDuration(matched.sampleDurationMs))
                }
                append("\n")
                sampleAttempts.forEach { a ->
                    append("    请求档位 ").append(a.quality).append(" · ")
                    when (a.outcome) {
                        Outcome.OK -> append(if (a.bytes > 0L) "有效 · 实测总大小 ${formatBytes(a.bytes)}" else "可拉流 · 总大小未知")
                        Outcome.UNSUPPORTED -> append("未返回可验证链接 · 档位/平台可能不可用")
                        Outcome.NO_MATCH -> append("样本未匹配，无法验证")
                        Outcome.FAILED -> append("失败")
                        Outcome.EXCLUDED -> append("已排除")
                    }
                    if (a.formatHint.isNotBlank()) append(" · 疑似格式 ").append(safeDiagnosticText(a.formatHint))
                    if (a.urlHint.isNotBlank()) append(" · URL ").append(safeUrlSummary(a.urlHint))
                    if (a.httpStatus > 0) append(" · HTTP ").append(a.httpStatus)
                    if (a.searchMs > 0L) append(" · 搜索").append(a.searchMs).append("ms")
                    if (a.resolveMs > 0L) append(" · 脚本取链").append(a.resolveMs).append("ms")
                    if (a.firstByteMs > 0L) append(if (a.firstByteReceived) " · 媒体首字节" else " · 媒体响应等待").append(a.firstByteMs).append("ms")
                    if (a.sizeSource.isNotBlank()) append(" · 大小来源 ").append(safeDiagnosticText(a.sizeSource))
                    if (a.contentRange.isNotBlank()) append(" · ").append(safeDiagnosticText(a.contentRange))
                    if (a.reason.isNotBlank()) append("：").append(safeDiagnosticText(a.reason))
                    val observation = a.toQualityObservation()
                    val warning = AudioQualityEvidence.suspectedDowngrade(
                        observation,
                        sampleAttempts.filter { it !== a }.map { it.toQualityObservation() }
                    )
                    if (warning != null) append(" · 疑似降级：").append(warning)
                    append("\n")
                    if (a.requestDescription.isNotBlank()) append("      请求说明: ").append(safeDiagnosticText(a.requestDescription)).append("\n")
                    if (a.formatEvidence.isNotBlank()) append("      格式依据: ").append(safeDiagnosticText(a.formatEvidence)).append("\n")
                }
            }
        }
        append("\n请求与判定说明：先由应用按平台搜索固定样本并记录查询样本与实际匹配歌曲，再调用脚本 getMusicUrl(平台, 请求音质档位)；请求档位标注为请求值，不冒充响应实际码率。脚本内部访问的 API 由脚本自行决定，应用不假称已观测其内部请求。对返回直链实际执行媒体 GET Range 校验（失败或不接受 Range 时再退回 GET），仅读取首包；总大小只取 Content-Range 的总长度或完整 200 响应的 Content-Length。206 的分段 Content-Length、未知长度和首包字节都不会冒充整首大小。延迟拆分为搜索、脚本取链和媒体首字节。疑似降级线索只根据响应格式、不同档请求的链接/文件大小差异等观测证据标注；单纯文件偏小不单独判定。\n")
        append("探测仅作排序参考：未通过不等于禁用。播放仍使用已启用且有代码的脚本取链。")
    }.trim()

    private fun Attempt.toQualityObservation() = AudioQualityProbe(
        requestedQuality = quality,
        bytes = bytes,
        durationMs = sampleDurationMs,
        format = formatHint,
        formatEvidence = formatEvidence,
        httpStatus = httpStatus,
        requestMethod = requestMethod,
        rangeHeader = rangeHeader,
        contentRange = contentRange,
        sizeSource = sizeSource,
        resolveMs = resolveMs,
        platform = platform
    )

    private fun speedWeight(ms: Long) = when {
        ms <= 0 -> 0; ms < 800 -> 30; ms < 1500 -> 22; ms < 2500 -> 14; ms < 4000 -> 7; else -> 2
    }
    private fun formatBytes(bytes: Long) = AudioQualityEvidence.formatBytes(bytes)
    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000L).takeIf { durationMs >= 10_000L } ?: durationMs
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    private fun platformName(p: String) = platformLabel(p)
    internal fun platformLabel(p: String) = when (p) { "wy" -> "网易云"; "kw" -> "酷我"; "kg" -> "酷狗"; "tx" -> "QQ"; "mg" -> "咪咕"; else -> p }
}
