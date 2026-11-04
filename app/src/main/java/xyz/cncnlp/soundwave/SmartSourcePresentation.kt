package xyz.cncnlp.soundwave

import java.util.Locale

internal val USER_AUDIO_QUALITY_OPTIONS = listOf(
    "master" to "母带 Master",
    "hires" to "Hi-Res",
    "flac24bit" to "24Bit",
    "flac" to "无损 FLAC",
    "320k" to "较高 320K",
    "128k" to "标准 128K"
)

/** Atmos is no longer user-selectable; migrate previously saved choices to the nearest high tier. */
internal fun normalizeUserSelectableQuality(value: String?, fallback: String = "320k"): String {
    val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        normalized.isBlank() -> fallback
        normalized == "atmos" -> "hires"
        else -> normalized
    }
}

/** 音质弹窗只负责选择；持久化时把用户点选的原始档位原样交给既有设置写入函数。 */
internal data class SmartSourceQualitySelection(
    val playbackQuality: String,
    val downloadQuality: String
) {
    fun selectPlayback(quality: String): SmartSourceQualitySelection = copy(playbackQuality = normalizeUserSelectableQuality(quality))
    fun selectDownload(quality: String): SmartSourceQualitySelection = copy(downloadQuality = normalizeUserSelectableQuality(quality))

    fun persist(
        platform: String,
        writePlayback: (String, String) -> Unit,
        writeDownload: (String, String) -> Unit
    ) {
        writePlayback(platform, playbackQuality)
        writeDownload(platform, downloadQuality)
    }
}

internal val SMART_SOURCE_QUALITY_OPTIONS = USER_AUDIO_QUALITY_OPTIONS.map { it.first }

internal fun platformScriptBindingSummary(ids: List<String>, scripts: List<Script>): String {
    val eligible = SmartSourceStore.platformEligibleScripts(scripts).associateBy { it.id }
    return SmartSourceStore.filterPlatformScriptOrder(ids, scripts)
        .mapIndexed { index, id -> "${index + 1}. ${eligible[id]?.name?.ifEmpty { id } ?: id}" }
        .joinToString(" → ")
        .ifEmpty { "未指定（使用默认）" }
}

/** 展示模型仅投影最近报告中的实际尝试与缓存已判定的有效链路，不推断未测试能力。 */
internal data class SmartSourceScriptProbeSnapshot(
    val scriptId: String,
    val scriptName: String,
    val report: ScriptProbeCache.Report?,
    val current: Boolean,
    val attemptCount: Int,
    val validAttemptCount: Int,
    val platforms: List<PlatformProbeSnapshot>
) {
    internal data class PlatformProbeSnapshot(
        val code: String,
        val attemptCount: Int,
        val outcomeCounts: Map<ScriptProbeCache.Outcome, Int>,
        val verifiedQualities: List<String>,
        val averageLatencyMs: Long?
    )

    companion object {
        fun load(script: Script): SmartSourceScriptProbeSnapshot =
            from(script, ScriptProbeCache.latestReport(script.id))

        fun from(script: Script, report: ScriptProbeCache.Report?): SmartSourceScriptProbeSnapshot {
            if (report == null) {
                return SmartSourceScriptProbeSnapshot(
                    scriptId = script.id,
                    scriptName = script.name,
                    report = null,
                    current = false,
                    attemptCount = 0,
                    validAttemptCount = 0,
                    platforms = emptyList()
                )
            }
            val platformCodes = report.attempts.map { it.platform }.distinct()
            val platforms = platformCodes.map { code ->
                val attempts = report.attempts.filter { it.platform == code }
                // Delegate capability eligibility to the cache's existing validation rule.
                val valid = report.valid.filter { it.platform == code }
                val measuredLatencies = valid.map { it.latencyMs }.filter { it > 0L }
                PlatformProbeSnapshot(
                    code = code,
                    attemptCount = attempts.size,
                    outcomeCounts = attempts.groupingBy { it.outcome }.eachCount(),
                    verifiedQualities = valid.map { it.quality }.distinct(),
                    averageLatencyMs = measuredLatencies.takeIf { it.isNotEmpty() }
                        ?.average()?.toLong()
                )
            }
            return SmartSourceScriptProbeSnapshot(
                scriptId = script.id,
                scriptName = script.name,
                report = report,
                current = ScriptProbeCache.isReportCurrent(report, script),
                attemptCount = report.attempts.size,
                validAttemptCount = report.valid.size,
                platforms = platforms
            )
        }
    }
}
