package xyz.cncnlp.soundwave

import java.util.UUID
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat

/**
 * 一次取链事务的身份。切歌、队列变更或预取来源变化后，旧票据必须失效，
 * WebView/网络迟到的结果不得污染当前播放器、缓存或跨源记忆。
 */
data class ResolveTicket(
    val requestId: String = UUID.randomUUID().toString(),
    val songKey: String,
    val queueRevision: Long = 0L,
    val serviceGeneration: Int = 0,
    val purpose: Purpose = Purpose.PLAY,
    val ownerContext: ProfileContext = ProfileWorkIdentity.runtime()
) {
    enum class Purpose { PLAY, PREFETCH, DOWNLOAD, PROBE }
}

enum class ResolveFailure {
    NONE,
    STALE_REQUEST,
    NO_ENABLED_SCRIPT,
    NO_PLAYABLE_URL,
    TIMEOUT,
    NETWORK_ERROR,
    POLICY_BLOCKED,
    MEDIA_UNVERIFIED,
    CANDIDATE_NOT_MATCHED
}

/**
 * 单次解析的脱敏事实链。仅保留阶段、平台、脚本标识、音质、耗时与结果，
 * 不保存临时 URL、Cookie、账号或上游响应正文。
 */
data class ResolveAttempt(
    val stage: String,
    val platform: String,
    val scriptId: String,
    val quality: String,
    val elapsedMs: Long,
    val outcome: Outcome,
    /** Stable, non-sensitive machine-readable reason; raw URL/cookie/body is never stored. */
    val reasonCode: Reason = Reason.NONE,
    val reason: String = "",
    /** H3 诊断标注：本次尝试的脚本流量实际通道（direct=校验直连 / system=实验系统栈直发 / proxy=服务端代理 / 空=未发请求）。 */
    val route: String = "",
    /**
     * ACCEPT5（时钟基统一）：本次尝试在解析运行内的开始偏移（相对 resolve 起点，
     * 毫秒）。路径弹层与文本导出据此定位步骤时刻，与逐次耗时、取链总时长同属
     * 一个时钟基；-1 = 无记录（旧数据/合成行），展示层回退累计口径。
     */
    val startedAtMs: Long = -1L
) {
    enum class Outcome { HIT, EMPTY, ERROR, SKIPPED, TIMEOUT }
    enum class Reason {
        NONE, NO_ENABLED_SCRIPT, NO_URL, SCRIPT_ERROR, STALE_REQUEST, GLOBAL_TIMEOUT, CANDIDATE_NOT_MATCHED,
        SCRIPT_DISABLED, SCRIPT_CODE_EMPTY, SCRIPT_COOLDOWN, NETWORK_ERROR, POLICY_BLOCKED, MEDIA_UNVERIFIED,
        /** H1-a：脚本自报终局错误（停服/版本），已写入持久裁决。 */
        SCRIPT_TERMINAL,
        /** H1-a：该（脚本×平台×音质）组合已被裁决，本次直接跳过、未发请求。 */
        SCRIPT_VERDICT,
        /** H2-b：单脚本软超时（阶段内预算），按瞬时失败记账，不入裁决。 */
        SOFT_TIMEOUT,
        /** ACCEPT4：脚本近期多次软超时命中短记忆，本次未执行即跳过（与持久裁决分开的短 TTL 层）。 */
        SCRIPT_TIMEOUT_MEMORY,
        /** ACCEPT5（E2）：同一脚本在本次解析内已多次无果（空链/传输失败/死链），其余档位本轮跳过（仅本轮有效）。 */
        SCRIPT_EMPTY_MEMORY,
        /** ACCEPT5（策略 conformance）：该（脚本×平台×音质）步已在本次解析的更早阶段执行过，本阶段按计划位置记跳过行，不重发请求。 */
        ALREADY_ATTEMPTED,
        /** ACCEPT5（D）：该组合近期探针实测为确定性死链（HTTP 401/403/404/410），短时记忆内跳过（30 分钟 TTL，粒度同裁决）。 */
        SCRIPT_PROBE_MEMORY,
        /** ACCEPT6（qqslow）：该脚本×平台近期多次慢速语义空返（≥3s 未交付），短时降级跳过（10 分钟滑动窗口，进程内，粒度同超时记忆）。 */
        SCRIPT_SLOW_EMPTY_MEMORY
    }
}

/**
 * Official anonymous links must be attempted before script admission on regular platform playback.
 *
 * tx (QQ) is excluded (acceptance F2/R1): its anonymous official path is structurally
 * dead — anonymous vkey requests return an empty purl platform-side, the app never
 * sends the platform cookie header the server requires (third-party platform login
 * was removed by design), and the server-side tx lookup itself takes ~46s against the
 * 12s call cap. Admitting it only ever burned 12s of the 26s playback budget before
 * the script chain could start, so tx playback now goes straight to the script
 * chain, matching the probe path which has no official stage.
 */
internal fun canAttemptAnonymousResolutionBeforeScripts(
    source: String,
    quality: String,
    originOnly: Boolean,
    forceCross: Boolean
): Boolean = !originOnly && !forceCross && source != "local" && source != "tx" &&
    !quality.contains("flac") && !quality.contains("hires") && !quality.contains("master")

/**
 * ACCEPT5（用户裁定一）：wy oiapi 段的准入——位置语义已变，谓词随之改写。
 *
 * ACCEPT4 时 oiapi 挂在脚本**之前**的官方阶段（低档在官方阶段内、高档单独
 * 放行），真机结果是每首 wy 都被 oiapi 以无音质参数的默认低质链抢先命中、
 * 还被盖章成请求档（《カタチ》MASTER「成功」实为低码率 mp3，策略顺序里
 * 只有 oiapi 一行、脚本根本没出场）。用户裁定：**脚本先于 oiapi**——oiapi
 * 降为脚本链（含跨平台候选内脚本）之后的兜底，全音质档准入（低档同样在
 * 脚本后兜底，不再于官方阶段内与 outer 并列），originOnly/forceCross 仍
 * 排除（那两种语义下原平台兜底本就不该出场；跨平台候选派发以显式
 * false/false 调用）。本谓词只管「能不能试」，「在脚本之后」由调用点
 * 位置保证。outer 的位置与准入不变（官方阶段、低档、脚本之前）。
 * oiapi 是纯本机段：/music/url 服务端兜底整条删除后（ACCEPT5），它就是
 * wy 解析链的末位兜底，链尾之后再无服务端段。
 */
internal fun canAttemptOiapiResolution(
    source: String,
    quality: String,
    originOnly: Boolean,
    forceCross: Boolean
): Boolean = !originOnly && !forceCross && source == "wy"

/**
 * ACCEPT5（用户裁定二）：跨平台换源的候选平台迭代序——**只能来自用户在
 * 设置里排定的平台顺序**（SmartSourceStore 的存储顺序），本函数是唯一的
 * 派生点：保序、去重、剔除原平台、截前 [limit] 个。任何调用点不得另写
 * 写死平台表；存储不可读时也只能回退到 SmartSourceStore 的默认序（与
 * 用户未自定义时的顺序同一份，见 defaultPlatformOrder）。
 */
/**
 * ACCEPT5（策略顺序 conformance）：阶段计划的唯一构造点（纯函数）。计划只由
 * 模式与用户设置（智能换源开关 + 存储的阶段序）决定，在解析开始时一次定死；
 * 裁决/超时/探针等一切记忆机制都无权改写它——它们只能让计划内某一步以
 * 「跳过」形态出现在原定位置（用户口径：计划是骨架，跳过只能缩短单个尝试、
 * 不许重构顺序）。默认序 [SmartSourceStore.DEFAULT_STAGES] 即用户肯定的
 * 降音质（ORIGIN 快试 → QUALITY 阶梯）→ 跨脚本 → 跨平台。
 */
internal fun resolveStagePlan(
    originOnly: Boolean,
    forceCross: Boolean,
    smartOn: Boolean,
    storedOrder: List<String>
): List<String> = when {
    originOnly -> listOf(
        SmartSourceStore.STAGE_ORIGIN, SmartSourceStore.STAGE_QUALITY, SmartSourceStore.STAGE_CROSS_SCRIPT
    )
    // 原平台已确认失效：先备用脚本、再跨平台（同平台候选身份更可靠，跨平台最后兜底）。
    forceCross -> listOf(SmartSourceStore.STAGE_CROSS_SCRIPT, SmartSourceStore.STAGE_CROSS_PLAT)
    !smartOn -> listOf(SmartSourceStore.STAGE_ORIGIN, SmartSourceStore.STAGE_QUALITY)
    else -> storedOrder
}

internal fun crossPlatTargetsInUserOrder(
    userOrder: List<String>,
    source: String,
    limit: Int = 4
): List<String> {
    val out = mutableListOf<String>()
    for (plat in userOrder) {
        if (plat.isBlank() || plat == source || plat in out) continue
        out += plat
        if (out.size >= limit) break
    }
    return out
}

/**
 * ACCEPT5（E3）：跨源身份门禁的字段级原因人话（通知摘要与播放路径共用）。
 * 输入是 SongMatcher.rejectReason 的短码或跨源腿的汇总行
 * `reject_summary:<主因>:<总数>`；未知值原样返回空串，不编造。
 */
internal fun matchRejectReasonLabel(reason: String): String {
    if (reason.startsWith("reject_summary:")) {
        val parts = reason.removePrefix("reject_summary:").split(":")
        val dominant = parts.getOrNull(0).orEmpty()
        val count = parts.getOrNull(1).orEmpty()
        val dominantLabel = matchRejectReasonLabel(dominant)
        return if (count.isNotBlank() && dominantLabel.isNotBlank()) {
            "另有 $count 个候选被拒，主因：$dominantLabel"
        } else if (count.isNotBlank()) {
            "另有 $count 个候选被拒"
        } else {
            ""
        }
    }
    return when (reason) {
        "title" -> "标题不符"
        "artist" -> "歌手不符"
        "version" -> "版本不符"
        "duration" -> "时长不符"
        "duration_missing" -> "缺时长"
        else -> ""
    }
}

/**
 * Ordered quality ladder for the script stages of one resolve.
 *
 * The user's effective maximum (per-platform override over the global value,
 * composed by the caller) always leads; lower tiers follow in strictly
 * descending order so a downgrade only ever happens after the tier above it
 * failed — never as the default
 * second shot. Tiers above the request are dropped (cascade("320k") otherwise
 * leaks a trailing "flac" upgrade past the user's cap); the low tiers
 * (≤192k) keep cascade's legacy upward escape order untouched. 128k joins
 * only when the 128k fallback is enabled or it is (at/below) the request
 * itself.
 */
internal fun scriptStageQualities(preferQuality: String, fallback128k: Boolean): List<String> {
    val pref = preferQuality.ifBlank { "320k" }
    val prefRank = xyz.cncnlp.soundwave.download.AudioQualityEvidence.qualityRank(pref)
    val cascade = Qualities.cascade(pref)
    val atOrBelow = cascade.filter {
        xyz.cncnlp.soundwave.download.AudioQualityEvidence.qualityRank(it) <= prefRank
    }
    val ladder = if (prefRank <= xyz.cncnlp.soundwave.download.AudioQualityEvidence.qualityRank("192k")) {
        cascade
    } else {
        atOrBelow
    }
    val ordered = (listOf(pref) + ladder).distinct()
    return if (fallback128k || prefRank <= xyz.cncnlp.soundwave.download.AudioQualityEvidence.qualityRank("128k")) {
        ordered
    } else {
        ordered.filter { it != "128k" && it != "128" }.ifEmpty { listOf(pref) }
    }
}

/** Missing scripts are fatal only when this request cannot use the earlier anonymous resolver path. */
internal fun shouldRejectForMissingScripts(usableScripts: Boolean, anonymousResolutionEligible: Boolean): Boolean =
    !usableScripts && !anonymousResolutionEligible

/** Collapse the resolver's redacted attempt facts into the stable user-facing failure category. */
internal fun resolveFailureFromAttempts(attempts: List<ResolveAttempt>): ResolveFailure = when {
    attempts.any { it.reasonCode == ResolveAttempt.Reason.STALE_REQUEST } -> ResolveFailure.STALE_REQUEST
    attempts.any { it.reasonCode == ResolveAttempt.Reason.POLICY_BLOCKED || it.reason.contains("policy", true) } -> ResolveFailure.POLICY_BLOCKED
    attempts.any { it.reasonCode == ResolveAttempt.Reason.NETWORK_ERROR || it.reason.contains("network", true) || it.reason.startsWith("http_") } -> ResolveFailure.NETWORK_ERROR
    attempts.any { it.outcome == ResolveAttempt.Outcome.TIMEOUT || it.reasonCode == ResolveAttempt.Reason.GLOBAL_TIMEOUT } -> ResolveFailure.TIMEOUT
    attempts.any { it.reasonCode == ResolveAttempt.Reason.CANDIDATE_NOT_MATCHED } -> ResolveFailure.CANDIDATE_NOT_MATCHED
    attempts.any { it.reasonCode == ResolveAttempt.Reason.MEDIA_UNVERIFIED } -> ResolveFailure.MEDIA_UNVERIFIED
    attempts.any { it.reasonCode == ResolveAttempt.Reason.NO_ENABLED_SCRIPT } -> ResolveFailure.NO_ENABLED_SCRIPT
    else -> ResolveFailure.NO_PLAYABLE_URL
}

/**
 * 解析边界的完整结果；禁止再以裸 Pair<String, String> 抹掉来源与失败语义。
 */
data class ResolveResult(
    val ticket: ResolveTicket,
    val url: String = "",
    val quality: String = "",
    val platform: String = "",
    val scriptId: String = "",
    val candidateKey: String = "",
    val fromCache: Boolean = false,
    val elapsedMs: Long = 0L,
    val failure: ResolveFailure = ResolveFailure.NONE,
    val attempts: List<ResolveAttempt> = emptyList()
) {
    val isSuccess: Boolean get() = url.startsWith("http")
}

/**
 * 播放服务唯一对外状态：界面、通知和 MediaSession 都由同一 Service 事实源派生。
 * transient callback 仅保留作媒体按键/错误恢复的过渡适配，界面不得再把回调当状态源。
 */
data class PlaybackSnapshot(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val generation: Int = 0,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    /** 不可变队列副本必须进入 StateFlow 值；仅 queueSize 无法感知“同长度换队列”。 */
    val queue: List<Song> = emptyList(),
    val mode: PlayMode = PlayMode.SEQUENCE,
    val hasPreparedNext: Boolean = false,
    /** Renderer-reported input format, not the user's requested resolver quality. */
    val audioFormat: PlaybackAudioFormat? = null,
    /** Actual resolver source for this media item (for example, after cross-platform fallback). */
    val sourcePlatform: String = "",
    val ownerContext: ProfileContext = ProfileWorkIdentity.runtime()
)
