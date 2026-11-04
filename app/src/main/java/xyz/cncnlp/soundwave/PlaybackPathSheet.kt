package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetPinnedHeader
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.common.localGlassSource
import xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState

/**
 * r657 第三轮第 19 项（用户 10-18，参考 1000073332_fixed）：歌曲「⋯」→「播放取链链路」打开的「播放路径」抽屉。
 * 复用缓存管理「播放取链报告」的同一份记录（[playbackLinkReportEntries]，内存 + 持久化，最近 30 条），
 * 取该歌最近一次记录展开成竖线时间轴；没有记录时显示空态。不臆造未记录的阶段：只有真实存在的耗时才显示。
 */
internal enum class PathStepKind { Info, Attempt, Success, Failure }

internal data class PathStep(val title: String, val detail: String, val atMs: Long, val kind: PathStepKind, val tags: List<String>)

internal enum class PathStatus(val label: String) { Resolving("解析中"), Success("成功"), Failed("失败") }

internal fun playbackPathStatus(e: PlaybackDiagnostics.Entry): PathStatus = when {
    e.failureLayer.isNotBlank() -> PathStatus.Failed
    e.totalMs >= 0 -> PathStatus.Success
    else -> PathStatus.Resolving
}

private fun pathSeconds(ms: Long): String = if (ms <= 0L) "+0s" else "+" + String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.') + "s"

internal fun playbackPathTime(ms: Long): String = pathSeconds(ms)

/** 由一条取链记录生成时间轴（纯函数，单测覆盖）。 */
internal fun playbackPathSteps(e: PlaybackDiagnostics.Entry): List<PathStep> {
    // 「从原平台 X」用原始平台；旧记录没有 originPlatform 时回退实际平台（与旧行为一致）。
    // 平台值一律先经 canonicalPlatform 规范化，历史记录里的哈希类 platform 不得进文案。
    val originCode = PlaybackDiagnostics.canonicalPlatform(e.originPlatform)
        .ifEmpty { PlaybackDiagnostics.canonicalPlatform(e.platform) }
    val plat = when (originCode) {
        "local" -> "本地"
        "" -> ""
        else -> Platforms.label(originCode)
    }
    val source = e.scriptId.takeIf { it.isNotBlank() && it != "local" }.orEmpty()
    val q = e.quality.takeIf { it.isNotBlank() && it != "本地" }?.uppercase().orEmpty()
    // 标签用统一来源标识：跨源显示「原→实际」，平台未知时回退脚本名，绝不打印哈希。
    val platTag = listOf(PlaybackDiagnostics.sourceLabel(e)).filter { it.isNotBlank() && it != "未知来源" }
    val steps = mutableListOf<PathStep>()
    steps += PathStep("开始${e.purpose.ifBlank { "播放" }}", if (plat.isNotBlank()) "从原平台 $plat 开始获取播放链接" else "开始获取播放链接", 0L, PathStepKind.Info, platTag)
    when {
        // ACCEPT5（本地直读）：原平台是真实平台 = 已下载歌曲直读，文案明说「已下载」
        // 并带真实档位；原平台即 local = 本机扫描文件，维持原文案。
        e.platform == "local" -> steps += PathStep(
            "本地文件",
            if (originCode.isNotBlank() && originCode != "local") "已下载的本地文件，直接读取，无需取链" else "直接读取本机音频文件，无需取链",
            0L, PathStepKind.Success,
            listOf("本地", q).filter { it.isNotBlank() }
        )
        e.prefetched -> steps += PathStep("预取命中", "使用上一首播放时提前解析好的链接", 0L, PathStepKind.Success, platTag)
        e.fromUrlCache -> steps += PathStep("URL 缓存命中", "使用缓存中仍有效的播放链接", 0L, PathStepKind.Success, platTag)
        else -> {
            steps += PathStep("开始解析播放链接", "歌曲链接获取中…", 0L, PathStepKind.Info, platTag)
            steps += PathStep(
                "尝试音源解析", if (source.isNotBlank()) "正在使用 $source 解析" else "正在调用音源脚本解析", 0L, PathStepKind.Attempt,
                platTag + listOf(source, q).filter { it.isNotBlank() }
            )
        }
    }
    if (e.failureLayer.isNotBlank()) {
        steps += PathStep("取链失败", PlaybackDiagnostics.explainFailure(e.failureLayer), maxOf(e.resolveMs, 0L), PathStepKind.Failure, platTag + listOf(source).filter { it.isNotBlank() })
        return steps
    }
    if (e.resolveMs >= 0 && e.platform != "local") {
        steps += PathStep("已获取真实播放链接", listOf(source, e.quality).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "链接可用" }, e.resolveMs, PathStepKind.Success,
            platTag + listOf(source, q).filter { it.isNotBlank() })
        steps += PathStep("正在加载音频", "播放器正在连接真实音频地址", e.resolveMs, PathStepKind.Attempt, platTag)
    }
    if (e.totalMs >= 0) steps += PathStep("音频数据已就绪", "已收到可播放的音频数据" + if (e.prepareMs >= 0) "（准备 ${e.prepareMs}ms）" else "", e.totalMs, PathStepKind.Success, platTag)
    return steps
}

/** 该歌最近一次取链记录（按 songKey）；没有则 null。 */
internal fun latestPlaybackPathEntry(song: Song): PlaybackDiagnostics.Entry? =
    runCatching { playbackLinkReportEntries().firstOrNull { it.songKey == song.key() } }.getOrNull()

// ===== J 波：播放路径接入真实逐次尝试（ResolveAttempt）=====
// 此前时间线由 Entry 汇总"合成"固定步骤，逐次尝试从未接进来。现改为：UrlResolver.latestAttempts
// 有留存时，用真实 attempts 渲染「换源策略顺序 + 逐次尝试」；无留存（重启/被挤出）回退原合成步骤。
// 只用已有事实：stage/platform/scriptId/quality/elapsedMs/outcome/reasonCode；脚本自报原文未留存，不编造。

/** attempt.stage 的展示标签（取值集合与 UrlResolver.trace 的实际调用点逐一对应，未知值原样显示）。 */
internal fun playbackAttemptStageLabel(stage: String): String = when (stage) {
    "cache" -> "URL 缓存"
    "remembered" -> "记忆直链"
    "official-direct" -> "平台官方直连"
    "official" -> "官方链路"
    STAGE_OFFICIAL_OIAPI -> "oiapi 取链"
    "admission" -> "脚本准入检查"
    "origin" -> "原平台解析"
    "quality" -> "按音质降级"
    "cross" -> "跨源解析"
    SmartSourceStore.STAGE_CROSS_SCRIPT -> "跨脚本换源"
    SmartSourceStore.STAGE_CROSS_PLAT -> "跨平台换源"
    "budget" -> "预算截止"
    "local" -> "本地文件"
    else -> stage
}

/** attempt.reasonCode 的人话（机器码不直接展示给用户）。 */
internal fun playbackAttemptReasonLabel(reason: ResolveAttempt.Reason): String = when (reason) {
    ResolveAttempt.Reason.NONE -> ""
    ResolveAttempt.Reason.NO_ENABLED_SCRIPT -> "无可用脚本"
    ResolveAttempt.Reason.NO_URL -> "未返回链接"
    ResolveAttempt.Reason.SCRIPT_ERROR -> "脚本执行失败"
    ResolveAttempt.Reason.STALE_REQUEST -> "请求已过期"
    ResolveAttempt.Reason.GLOBAL_TIMEOUT -> "全局超时"
    // 合并 fix-forward（MERGE7）：speed-h1 给 Reason 枚举新增三值，J9 的 when 须穷举；标签按 reasonCode 事实（soft_timeout/script_terminal/verdict_cached）人话化，不编造脚本原文。
    ResolveAttempt.Reason.SOFT_TIMEOUT -> "单脚本超时"
    ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED -> "跨源未匹配到候选"
    ResolveAttempt.Reason.SCRIPT_DISABLED -> "脚本已停用"
    ResolveAttempt.Reason.SCRIPT_CODE_EMPTY -> "脚本内容为空"
    ResolveAttempt.Reason.SCRIPT_COOLDOWN -> "脚本冷却中"
    ResolveAttempt.Reason.SCRIPT_TERMINAL -> "脚本已停服"
    ResolveAttempt.Reason.SCRIPT_VERDICT -> "停服裁决命中"
    ResolveAttempt.Reason.NETWORK_ERROR -> "网络失败"
    ResolveAttempt.Reason.POLICY_BLOCKED -> "被网络策略拦截"
    ResolveAttempt.Reason.MEDIA_UNVERIFIED -> "链接校验未通过"
    ResolveAttempt.Reason.SCRIPT_TIMEOUT_MEMORY -> "近期多次超时，暂跳过"
    // ACCEPT5（E2）：本轮内同脚本多次无果熔断（非裁决、非冷却，跨轮不保留）。
    ResolveAttempt.Reason.SCRIPT_EMPTY_MEMORY -> "本轮多次无结果，暂跳过"
    // ACCEPT5（策略 conformance）：计划内重复步在本阶段位置的跳过行。
    ResolveAttempt.Reason.ALREADY_ATTEMPTED -> "本轮已尝试，跳过"
    // ACCEPT5（D）：探针死链短记忆（30 分钟）内的跳过行。
    ResolveAttempt.Reason.SCRIPT_PROBE_MEMORY -> "近期探针死链，暂跳过"
    // ACCEPT6（qqslow）：慢速语义空返短记忆（10 分钟）内的跳过行。
    ResolveAttempt.Reason.SCRIPT_SLOW_EMPTY_MEMORY -> "近期多次慢速空返，暂跳过"
}

/**
 * ACCEPT5（A/B/E3）：直连/oiapi 空结果的 detail 短码 → 人话。attempts.reason 里
 * 以 `direct_empty:<detail>` / `oiapi_empty:<detail>` 保存；原始短码（含 HTTP
 * 状态与异常类名）在复制诊断里保留，这里只决定弹层怎么说。
 */
internal fun directEmptyDetailLabel(reason: String): String = when {
    reason.startsWith("direct_empty:") -> "直连未出链（${resolveTransportDetailLabel(reason.removePrefix("direct_empty:"))}）"
    reason.startsWith("oiapi_empty:") -> "oiapi 未出链（${resolveTransportDetailLabel(reason.removePrefix("oiapi_empty:"))}）"
    else -> ""
}

private fun resolveTransportDetailLabel(detail: String): String = when {
    detail == "redirect_404" -> "歌曲受限，官方直链 404"
    detail == "redirect_no_location" -> "跳转应答无地址"
    detail == "redirect_invalid" -> "跳转地址无效"
    detail == "transport_timeout" -> "请求超时"
    detail == "transport_policy" -> "被策略拦截"
    detail == "transport_cooldown" -> "通道冷却中"
    detail == "transport_unknown" -> "传输异常"
    detail == "oiapi_bad_json" -> "接口应答异常"
    detail == "oiapi_no_data" -> "接口无数据"
    detail == "oiapi_no_url" -> "接口无链接"
    detail == "blank_id" -> "歌曲 ID 缺失"
    detail.startsWith("no_redirect_") -> "无跳转应答（HTTP ${detail.removePrefix("no_redirect_")}）"
    detail.startsWith("transport_http_") -> "HTTP ${detail.removePrefix("transport_http_")}"
    detail.startsWith("transport_network_") -> "网络失败"
    else -> detail
}

/**
 * ACCEPT4（修7）：副标题与状态徽标同源。事实行（音质/总耗时）优先；无事实时按
 * [playbackPathStatus] 给结论，杜绝"徽标=失败、副标题却写仍在解析"的自相矛盾。
 */
internal fun playbackPathSubtitle(e: PlaybackDiagnostics.Entry): String {
    val facts = listOfNotNull(
        e.quality.takeIf { it.isNotBlank() }?.let { "音质 ${it.uppercase()}" },
        e.totalMs.takeIf { it >= 0 }?.let { "总耗时 ${String.format(java.util.Locale.ROOT, "%.3f", it / 1000.0)}s" }
    ).joinToString(" · ")
    if (facts.isNotBlank()) return facts
    return when (playbackPathStatus(e)) {
        PathStatus.Failed -> "解析失败"
        PathStatus.Success -> "解析完成"
        PathStatus.Resolving -> "仍在解析"
    }
}

private val PLAYBACK_QUALITY_RANK = listOf("master", "hires", "flac24bit", "flac", "320k", "192k", "128k")

private fun playbackQualityRank(quality: String): Int =
    PLAYBACK_QUALITY_RANK.indexOf(quality.trim().lowercase())

/** attempt 里的伪脚本标识（缓存/官方/本地等通道名，不是真实脚本名，展示时不当脚本名打印）。 */
private fun ResolveAttempt.displayScript(): String =
    scriptId.takeIf { it.isNotBlank() && !it.endsWith("-direct") && it !in setOf("cache", "official", "remembered", "match", "local") }.orEmpty()

/**
 * 由真实逐次尝试构建时间线：开始 → 换源策略顺序（本次实际执行顺序）→ 逐次尝试
 * （累计时刻、结果、失败原因、降级/跨平台/切换音源标注）→ 与 [playbackPathSteps] 相同的收尾。
 * 纯函数，单测覆盖。
 */
/**
 * ACCEPT6（qqslow）：路径单展示去重（仅展示层，留存 attempts 不变）——
 * 同一腿（平台×脚本×音质）在计划内多个阶段位置各有一行时，实质行之后
 * 的「本轮已尝试」行不再重复展示（真机实证：停服失败行 + 跳过行成对出现）；
 * 跨源两轮搜索（直连轮 + 服务端回落轮）对同一候选产出的完全相同拒绝行
 * 只留第一组。实质行永不被去重吞掉：不同歌曲的跨源候选共用脚本时，
 * 两条实质行 key 相同也都保留（只吞 AA 行与逐字重复行）。
 */
internal fun dedupeAttemptsForDisplay(attempts: List<ResolveAttempt>): List<ResolveAttempt> {
    val seenSubstantive = HashSet<String>()
    val seenExact = HashSet<String>()
    return attempts.filter { a ->
        val legKey = "${a.platform}|${a.scriptId}|${a.quality}"
        val exactKey = "$legKey|${a.stage}|${a.outcome}|${a.reasonCode}|${a.reason}"
        if (!seenExact.add(exactKey)) return@filter false
        if (a.reasonCode == ResolveAttempt.Reason.ALREADY_ATTEMPTED && legKey in seenSubstantive) {
            return@filter false
        }
        if (a.reasonCode != ResolveAttempt.Reason.ALREADY_ATTEMPTED) seenSubstantive += legKey
        true
    }
}

internal fun playbackPathStepsWithAttempts(
    e: PlaybackDiagnostics.Entry,
    attempts: List<ResolveAttempt>,
    plannedStages: List<String> = emptyList()
): List<PathStep> {
    val base = playbackPathSteps(e)
    // 预取/缓存命中的短路情形没有多阶段尝试可展示，保持原合成时间线。
    if (e.prefetched || e.fromUrlCache || attempts.isEmpty()) return base
    // ACCEPT5（本地直读）：本地条目只有在留存里确有「本地文件」尝试行时才走
    // 真实尝试构建（策略行宣布本地文件 + 成功行带实测音质）；留存仍是旧的
    // 联网尝试时回退合成时间线，不许把旧取链链冒充成本次本地播放。
    if (e.platform == "local" && attempts.none { it.stage == STAGE_LOCAL_FILE }) return base
    val originCode = PlaybackDiagnostics.canonicalPlatform(e.originPlatform)
        .ifEmpty { PlaybackDiagnostics.canonicalPlatform(e.platform) }
    val platTag = listOf(PlaybackDiagnostics.sourceLabel(e)).filter { it.isNotBlank() && it != "未知来源" }
    val steps = mutableListOf<PathStep>()
    steps += base.first()
    // 策略顺序行（ACCEPT5 conformance）：有计划留痕时宣布**完整计划**（含未
    // 执行到的跨平台/兜底段）——旧形态只按 attempts 首次出现序归纳，中途成功
    // 的解析看起来像「计划里根本没有跨平台腿」。无留痕（旧数据）回退旧口径。
    val stageOrderLabels = if (plannedStages.isNotEmpty()) {
        plannedStages.map { playbackAttemptStageLabel(it) }.distinct()
    } else {
        attempts.map { it.stage }.distinct().map { playbackAttemptStageLabel(it) }
    }
    if (stageOrderLabels.isNotEmpty()) {
        steps += PathStep("换源策略顺序", stageOrderLabels.joinToString(" → "), 0L, PathStepKind.Info, platTag)
    }
    var cumulative = 0L
    val displayAttempts = dedupeAttemptsForDisplay(attempts)
    displayAttempts.forEachIndexed { i, a ->
        // ACCEPT5（时钟基统一）：步骤时刻 = 尝试在本轮解析内的开始偏移 + 其
        // 耗时（与取链总时长同一时钟基、可互相推导），不再累计求和——旧口径
        // 把软超时整窗、budget 尾注整轮耗时等行重复计入，偏移与总时长必然
        // 漂移（实证 +13.845s vs 取链 10509ms）。无偏移记录的行回退累计口径；
        // max 保证单调（跨运行合并时不倒退）。
        cumulative = if (a.startedAtMs >= 0L) {
            maxOf(cumulative, a.startedAtMs + a.elapsedMs.coerceAtLeast(0L))
        } else {
            cumulative + a.elapsedMs.coerceAtLeast(0L)
        }
        val platLabel = PlaybackDiagnostics.canonicalPlatform(a.platform).let { code ->
            when (code) { "" -> ""; "local" -> "本地"; else -> Platforms.label(code) }
        }
        val script = a.displayScript()
        val outcomeText = when (a.outcome) {
            ResolveAttempt.Outcome.HIT -> "成功"
            ResolveAttempt.Outcome.EMPTY -> "无结果"
            ResolveAttempt.Outcome.ERROR -> "失败"
            ResolveAttempt.Outcome.TIMEOUT -> "超时"
            ResolveAttempt.Outcome.SKIPPED -> "已跳过"
        }
        val reasonLabel = playbackAttemptReasonLabel(a.reasonCode)
        // ACCEPT4（修3）：探针拒收原文（分类文本，如"权限拒绝 HTTP 403"/
        // "HTML/JSON 响应（非音频）")直接进时间线——机器码形态（snake_case）
        // 不展示，原文缺失时只保留上面的分类标签。
        val probeReason = if (a.reasonCode == ResolveAttempt.Reason.MEDIA_UNVERIFIED &&
            a.reason.isNotBlank() && !a.reason.matches(Regex("^[a-z0-9_]+$"))) a.reason else ""
        // ACCEPT5（E3/A）：跨源未匹配带字段级原因（缺时长/版本不符/汇总主因）；
        // 直连/oiapi 空结果带传输定性（跳转 404/超时/无跳转应答）。
        val matchReason = if (a.reasonCode == ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED)
            matchRejectReasonLabel(a.reason) else ""
        val transportDetail = if (a.outcome == ResolveAttempt.Outcome.EMPTY) directEmptyDetailLabel(a.reason) else ""
        val detail = listOf(
            platLabel,
            script,
            a.quality.takeIf { it.isNotBlank() }?.uppercase(),
            outcomeText + if (reasonLabel.isNotBlank() && a.outcome != ResolveAttempt.Outcome.HIT) "：$reasonLabel" else "",
            probeReason,
            matchReason,
            transportDetail
        ).filter { !it.isNullOrBlank() }.joinToString(" · ")
        val tags = mutableListOf<String>()
        tags += platTag
        if (a.quality.isNotBlank()) tags += a.quality.uppercase()
        val prev = displayAttempts.getOrNull(i - 1)
        if (prev != null) {
            val prevRank = playbackQualityRank(prev.quality)
            val curRank = playbackQualityRank(a.quality)
            if (prevRank >= 0 && curRank > prevRank) tags += "降级 ${prev.quality.uppercase()}→${a.quality.uppercase()}"
            val prevScript = prev.displayScript()
            if (prevScript.isNotBlank() && script.isNotBlank() && prevScript != script) tags += "切换音源"
        }
        val attemptPlat = PlaybackDiagnostics.canonicalPlatform(a.platform)
        if (attemptPlat.isNotBlank() && originCode.isNotBlank() && attemptPlat != originCode) {
            tags += "跨平台 ${Platforms.label(originCode)}→${Platforms.label(attemptPlat)}"
        }
        steps += PathStep(
            playbackAttemptStageLabel(a.stage), detail, cumulative,
            when (a.outcome) {
                ResolveAttempt.Outcome.HIT -> PathStepKind.Success
                ResolveAttempt.Outcome.SKIPPED -> PathStepKind.Info
                else -> PathStepKind.Attempt
            },
            tags
        )
    }
    if (e.failureLayer.isNotBlank()) {
        steps += PathStep("取链失败", PlaybackDiagnostics.explainFailure(e.failureLayer), maxOf(e.resolveMs, 0L), PathStepKind.Failure, platTag)
        return steps
    }
    // ACCEPT5（本地直读）：本地文件没有「真实播放链接」可获取，收尾两步只对联网解析成立（与合成构建器同口径）。
    if (e.resolveMs >= 0 && e.platform != "local") {
        val source = e.scriptId.takeIf { it.isNotBlank() && it != "local" }.orEmpty()
        steps += PathStep("已获取真实播放链接", listOf(source, e.quality).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "链接可用" }, e.resolveMs, PathStepKind.Success, platTag)
        steps += PathStep("正在加载音频", "播放器正在连接真实音频地址", e.resolveMs, PathStepKind.Attempt, platTag)
    }
    if (e.totalMs >= 0) steps += PathStep("音频数据已就绪", "已收到可播放的音频数据" + if (e.prepareMs >= 0) "（准备 ${e.prepareMs}ms）" else "", e.totalMs, PathStepKind.Success, platTag)
    return steps
}

private val PathOk = Color(0xFF2F9E62)
private val PathTry = Color(0xFF8E3B42)
private val PathFail = Color(0xFFD23C2E)

/** 节点视觉角色（纯函数，单测）：解析中时最后一个「尝试」节点为当前节点。 */
internal enum class PathNodeRole(val label: String) { Info("信息"), Done("已完成"), Current("进行中"), Success("成功"), Failure("失败") }

internal fun playbackPathNodeRoles(steps: List<PathStep>, status: PathStatus): List<PathNodeRole> {
    val currentIndex = if (status == PathStatus.Resolving) steps.indexOfLast { it.kind == PathStepKind.Attempt } else -1
    return steps.mapIndexed { i, step ->
        when {
            i == currentIndex -> PathNodeRole.Current
            step.kind == PathStepKind.Success -> PathNodeRole.Success
            step.kind == PathStepKind.Failure -> PathNodeRole.Failure
            step.kind == PathStepKind.Attempt -> PathNodeRole.Done
            else -> PathNodeRole.Info
        }
    }
}

/** 每个节点自身耗时 = 与上一节点的时间差（毫秒，<0 视为 0）。 */
internal fun playbackPathStepDurations(steps: List<PathStep>): List<Long> =
    steps.mapIndexed { i, step -> if (i == 0) 0L else (step.atMs - steps[i - 1].atMs).coerceAtLeast(0L) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackPathSheet(song: Song, onDismiss: () -> Unit) {
    // r657 第三轮 b（用户：播放链路对话框与整体风格不符）：统一为全局磨砂抽屉（DesignSheetDialog：夜间关磨砂完全不透明、浅色白底），
    // 时间轴每个节点 = 步骤 / 来源标签 / 状态 / 累计时刻 + 本步耗时 / 结果；当前（珊瑚）、成功（绿）、失败（红）、已完成尝试（暗红）、信息（灰）分色，竖线连接。
    // 数据只复用取链报告记录（内存 + 持久化），不为展示额外取链；无记录显示空态。可刷新、可复制诊断。
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val entry = remember(song.key(), refresh) { latestPlaybackPathEntry(song) }
    // J 波：同一首歌的真实逐次尝试（内存留存，≤40 首；无留存时时间线回退 Entry 合成步骤）。
    val attempts = remember(song.key(), refresh) { runCatching { UrlResolver.latestAttempts(song.key()) }.getOrDefault(emptyList()) }
    val plannedStages = remember(song.key(), refresh) { runCatching { UrlResolver.latestPlan(song.key()) }.getOrDefault(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant
    val accent = SoundwaveDesignPalette.Accent
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false) {
        // ACCEPT4（审计 §9 B 类）：关闭钮改走退场句柄（原直调 onDismiss 同步卸载）。
        val dismissPath = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        // ACCEPT6 dialogfrost：钉顶带（标题行 + 来源副题 + 分隔线）——滚动态磨砂的状态三件套；
        // 标题行抽为局部组件，空态直用、有记录时进钉顶带（DesignSheetPinnedHeader）。
        val pathScrollState = rememberScrollState()
        val pathGlass = rememberLocalGlassState()
        var headerBandPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val headerBand: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("PLAYBACK PATH", color = accent, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = androidx.compose.ui.unit.TextUnit(0.16f, androidx.compose.ui.unit.TextUnitType.Em))
                Text("播放路径", color = colors.onSurface, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 4.dp))
                Text(song.name, color = colors.onSurface, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = dismissPath).semantics { contentDescription = "关闭"; role = Role.Button },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, null, tint = colors.onSurface, modifier = Modifier.size(22.dp)) }
                if (entry != null) {
                    val st = playbackPathStatus(entry)
                    val c = when (st) { PathStatus.Success -> PathOk; PathStatus.Failed -> PathFail; PathStatus.Resolving -> accent }
                    Text(
                        st.label, color = c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(999.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        }
        if (entry == null) {
            headerBand()
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                }
                Text("暂无取链记录", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 10.dp))
                Text(
                    "播放这首歌后，这里会按时间线列出取链的每一步、状态与耗时（与 设置 › 缓存管理 › 播放取链报告 同一份记录）。此入口不会额外请求取链。",
                    color = muted, fontSize = 12.5.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
                )
            }
            PathActions(onRefresh = { refresh++ }, onCopy = { copyDiagnostics(ctx, entry, song) })
            return@DesignSheetDialog
        }
        val plat = PlaybackDiagnostics.sourceLabel(entry)
        val src = entry.scriptId.takeIf { it.isNotBlank() && it != "local" }
        // 副标题只展示来源标签（平台/跨源→/脚本），不再直拼原始 song.id（平台原始 ID/哈希不是可读来源标识，与诊断来源口径一致）。
        // ACCEPT6 dialogfrost：两行副题 + 分隔线已随标题行移入下方钉顶带（见 DesignSheetPinnedHeader 调用处）。
        val steps = remember(entry, attempts, plannedStages) { if (attempts.isNotEmpty()) playbackPathStepsWithAttempts(entry, attempts, plannedStages) else playbackPathSteps(entry) }
        val roles = remember(entry, attempts) { playbackPathNodeRoles(steps, playbackPathStatus(entry)) }
        val durations = remember(entry, attempts) { playbackPathStepDurations(steps) }
        // ACCEPT6 dialogfrost：时间线整滚（上限 480dp 不变），顶部以「钉顶带实测高 + 原 10dp 间隔」
        // 占位——静止布局与改前一致；滚入后钉顶带磨砂（局部源 = 本时间线）。
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(pathScrollState).localGlassSource(pathGlass)) {
            Spacer(Modifier.height(with(density) { headerBandPx.toDp() } + 10.dp))
            steps.forEachIndexed { i, step ->
                val role = roles[i]
                val dot = when (role) {
                    PathNodeRole.Info -> muted.copy(alpha = 0.55f)
                    PathNodeRole.Done -> PathTry
                    PathNodeRole.Current -> accent
                    PathNodeRole.Success -> PathOk
                    PathNodeRole.Failure -> PathFail
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(26.dp).fillMaxHeight()) {
                        if (i < steps.lastIndex) Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).width(2.dp).fillMaxHeight().background(designLineColor()))
                        if (role == PathNodeRole.Current) Box(Modifier.align(Alignment.TopCenter).padding(top = 1.dp).size(20.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)))
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(10.dp).clip(CircleShape).background(dot))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).padding(bottom = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(step.title, color = when (role) { PathNodeRole.Failure -> PathFail; PathNodeRole.Current -> accent; else -> colors.onSurface },
                                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(playbackPathTime(step.atMs), color = muted, fontSize = 12.sp, fontFamily = DesignFonts.DmMono)
                        }
                        Text(step.detail, color = muted, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                role.label, color = dot, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(dot.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            if (durations[i] > 0) Text(
                                "耗时 ${durations[i]}ms", color = muted, fontSize = 11.sp, fontFamily = DesignFonts.DmMono,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.onSurface.copy(alpha = 0.06f)).padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            step.tags.distinct().forEach { t ->
                                Text(t, color = muted, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.onSurface.copy(alpha = 0.06f)).padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        DesignSheetPinnedHeader(pathScrollState, pathGlass, Modifier.onSizeChanged { headerBandPx = it.height }) {
            Column {
                headerBand()
                Text(
                    plat + (src?.takeIf { it != plat }?.let { " → $it" } ?: ""),
                    color = muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    playbackPathSubtitle(entry),
                    color = muted, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 4.dp)
                )
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(designLineColor()))
            }
        }
        }
        PathActions(onRefresh = { refresh++ }, onCopy = { copyDiagnostics(ctx, entry, song) })
    }
}

/** 单曲弹层只复制当前这首歌的诊断（汇总行 + 它自己的路径时间线），不再误用全局导出。 */
private fun copyDiagnostics(ctx: android.content.Context, entry: PlaybackDiagnostics.Entry?, song: Song) {
    // ACCEPT4（修7）：复制诊断带上该歌留存的真实逐次尝试，导出文本与弹层时间线同源。
    val text = if (entry != null) PlaybackDiagnostics.songDiagnosticsText(entry, UrlResolver.latestAttempts(song.key()), UrlResolver.latestPlan(song.key()))
        else PlaybackDiagnostics.songDiagnosticsText(song.name, song.singer)
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("soundwave-diag", text))
    xyz.cncnlp.soundwave.ui.notice.AppNotice.attach(ctx.applicationContext)
    xyz.cncnlp.soundwave.ui.notice.AppNotice.toast("已复制本首诊断", xyz.cncnlp.soundwave.ui.notice.AppNotice.Kind.SUCCESS)
}

@Composable
private fun PathActions(onRefresh: () -> Unit, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("刷新" to onRefresh, "复制诊断" to onCopy).forEachIndexed { i, (label, action) ->
            val primary = i == 1
            Box(
                Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (primary) SoundwaveDesignPalette.Accent else colors.onSurface.copy(alpha = 0.07f))
                    .clickable(onClick = action).semantics { role = Role.Button },
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (primary) Color.White else colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope) }
        }
    }
}
