package xyz.cncnlp.soundwave

import java.util.Locale

internal data class ScriptProbeSample(
    val platform: String,
    val name: String,
    val singer: String
)

/** One platform the script declared (from `send(inited, {sources})`), normalized to app platform codes. */
internal data class ScriptDeclaredSource(
    val rawKey: String,
    val platform: String,
    /** false when the script declared `actions` without `musicUrl`. */
    val musicUrl: Boolean,
    /** null = the script declared the platform but no quality list → every engine tier is requested. */
    val qualities: List<String>?
)

/**
 * r658c S：完整检测计划。覆盖脚本声明支持的全部平台（每平台全部固定样本）× 该平台声明的全部音质；
 * 脚本未声明 sources 时按引擎全部五平台 × 七档兼容遍历。
 */
internal data class ScriptProbePlan(
    val samples: List<ScriptProbeSample>,
    val qualitiesByPlatform: Map<String, List<String>>,
    /** true = 计划来自脚本声明；false = 未取到声明，按全矩阵兼容遍历。 */
    val fromDeclaration: Boolean,
    /** 脚本声明了但本次无法检测的平台 → 原因（逐项写入报告覆盖说明）。 */
    val skipped: Map<String, String>,
    /** 引擎支持但脚本未声明的平台（不检测、不算失败）。 */
    val notDeclared: List<String>
) {
    fun qualitiesFor(platform: String): List<String> = qualitiesByPlatform[platform].orEmpty()
    val totalItems: Int get() = samples.sumOf { qualitiesFor(it.platform).size }
}

/** Stable full-diagnostic matrix. Full reports must cover every declared platform × sample × tier. */
internal object ScriptProbeMatrix {
    val platformOrder = listOf("wy", "kw", "tx", "kg", "mg")

    val qualityTiers = listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")

    val fixedSamples = listOf(
        ScriptProbeSample("wy", "鲜花", ""),
        ScriptProbeSample("wy", "夜曲", "周杰伦"),
        ScriptProbeSample("kw", "晴天", "周杰伦"),
        ScriptProbeSample("kw", "演员", "薛之谦"),
        ScriptProbeSample("tx", "晴天", "周杰伦"),
        ScriptProbeSample("tx", "稻香", "周杰伦"),
        ScriptProbeSample("kg", "演员", "薛之谦"),
        ScriptProbeSample("kg", "丑八怪", "薛之谦"),
        ScriptProbeSample("mg", "最佳损友", "陈奕迅"),
        ScriptProbeSample("mg", "K歌之王", "陈奕迅")
    )

    /** 单脚本完整检测总时限：超出后剩余项逐项记为未执行，报告标为非完整。 */
    const val FULL_PROBE_BUDGET_MS = 10 * 60_000L

    /** 同一平台连续取链超时达到该次数后，该平台剩余项直接记失败，不再逐项等满 16s 超时。 */
    const val PLATFORM_TIMEOUT_STREAK_LIMIT = 3

    /** 运行时返回的“已初始化但未声明 sources”标记。 */
    const val NO_DECLARATION = "__none__"

    private val platformAliases = mapOf(
        "netease" to "wy", "kugou" to "kg", "kuwo" to "kw", "qq" to "tx", "tencent" to "tx", "migu" to "mg",
        "网易" to "wy", "网易云" to "wy", "酷狗" to "kg", "酷我" to "kw", "腾讯" to "tx", "咪咕" to "mg"
    )

    /** Same alias rule as the runtime's source lookup (`norm` in ScriptRuntime.buildJs). */
    fun normalizePlatform(raw: String): String {
        val key = raw.trim().lowercase(Locale.ROOT).removeSuffix("music")
        return platformAliases[key] ?: key
    }

    /** Every fixed sample of the first [maxPlats] canonical platforms (complete traversal, no sampling). */
    fun samples(maxPlats: Int): List<ScriptProbeSample> {
        val platforms = platformOrder.take(maxPlats.coerceIn(1, platformOrder.size))
        return fixedSamples.filter { it.platform in platforms }
    }

    /** Declared quality list ordered by engine tier (unknown declared tiers kept after, in declared order). */
    fun normalizeQualities(declared: List<String>): List<String> {
        val cleaned = declared.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        val known = qualityTiers.filter { it in cleaned }
        return known + cleaned.filterNot { it in qualityTiers }
    }

    /**
     * Parses the runtime's compact declaration: `key=q1,q2;key2=*;key3=!` where `*` = no quality list and
     * `!` = declared without musicUrl. Returns null when the script declared nothing (or init failed).
     */
    fun parseDeclaredSources(raw: String?): List<ScriptDeclaredSource>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == NO_DECLARATION || text.length > 8192) return null
        val entries = text.split(';').mapNotNull { part ->
            val key = part.substringBefore('=').trim()
            if (key.isEmpty() || key.length > 32 || !part.contains('=')) return@mapNotNull null
            val value = part.substringAfter('=').trim()
            when (value) {
                "!" -> ScriptDeclaredSource(key, normalizePlatform(key), musicUrl = false, qualities = null)
                "*" -> ScriptDeclaredSource(key, normalizePlatform(key), musicUrl = true, qualities = null)
                else -> ScriptDeclaredSource(
                    key, normalizePlatform(key), musicUrl = true,
                    qualities = normalizeQualities(value.split(',').filter { it.isNotBlank() && it.length <= 24 })
                )
            }
        }
        return entries.takeIf { it.isNotEmpty() }
    }

    fun plan(declared: List<ScriptDeclaredSource>?, maxPlats: Int = platformOrder.size): ScriptProbePlan {
        val enginePlatforms = platformOrder.take(maxPlats.coerceIn(1, platformOrder.size))
        if (declared == null) {
            return ScriptProbePlan(
                samples = samples(maxPlats),
                qualitiesByPlatform = enginePlatforms.associateWith { qualityTiers },
                fromDeclaration = false,
                skipped = emptyMap(),
                notDeclared = emptyList()
            )
        }
        val byPlatform = declared.groupBy { it.platform }
        val skipped = linkedMapOf<String, String>()
        val qualities = linkedMapOf<String, List<String>>()
        enginePlatforms.forEach { platform ->
            val entries = byPlatform[platform] ?: return@forEach
            val usable = entries.filter { it.musicUrl }
            if (usable.isEmpty()) {
                skipped[platform] = "脚本声明该平台不提供 musicUrl 取链"
                return@forEach
            }
            val tiers = if (usable.any { it.qualities == null }) qualityTiers
            else normalizeQualities(usable.flatMap { it.qualities.orEmpty() })
            if (tiers.isEmpty()) skipped[platform] = "脚本声明该平台但音质列表为空" else qualities[platform] = tiers
        }
        declared.filter { it.platform !in platformOrder && it.platform != "local" }.forEach {
            skipped.putIfAbsent(it.rawKey, "应用没有该平台的样本搜索，无法检测")
        }
        return ScriptProbePlan(
            samples = fixedSamples.filter { it.platform in qualities.keys },
            qualitiesByPlatform = qualities,
            fromDeclaration = true,
            skipped = skipped,
            notDeclared = enginePlatforms.filter { it !in byPlatform.keys }
        )
    }

    /** One-line coverage statement persisted with the report. */
    fun coverageText(plan: ScriptProbePlan, platformName: (String) -> String): String = buildString {
        append(if (plan.fromDeclaration) "按脚本声明完整覆盖：" else "脚本未声明支持平台/音质，按引擎全部平台 × 全部音质遍历：")
        append(plan.qualitiesByPlatform.entries.joinToString("；") { (p, q) ->
            "${platformName(p)} ${plan.samples.count { it.platform == p }} 首 × ${q.joinToString("/")}"
        }.ifBlank { "无可检测平台" })
        append("，共 ").append(plan.totalItems).append(" 项")
        if (plan.skipped.isNotEmpty()) {
            append("。未检测：").append(plan.skipped.entries.joinToString("；") { (p, why) -> "${platformName(p)}（$why）" })
        }
        if (plan.notDeclared.isNotEmpty()) {
            append("。脚本未声明：").append(plan.notDeclared.joinToString("、") { platformName(it) })
        }
    }
}
