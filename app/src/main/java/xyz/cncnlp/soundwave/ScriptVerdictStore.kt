package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * 脚本自报终局错误的分类（H 波 H1-a）：只有“脚本自己宣告已死”这一类才允许进入
 * 持久裁决；网络失败、超时、该脚本没有这首歌（空链）都是瞬时/曲目级事实，
 * 永远不得据此拉黑一个源。
 *
 * 判定只看脚本 Promise reject 时自带的消息文本（经长度截断与控制字符清理），
 * 命中版本停服/服务终止类固定短语才算终局。匹配从严：宁可漏判一个死源
 * （代价是下一首再试一次），不可误判活源（代价是整源被跳过数日）。
 */
internal object ScriptTerminalErrorClassifier {
    /**
     * 硬终局：明确宣告「源本身」死亡/版本死亡的可证伪自报（如「当前版本已停止
     * 服务，请升级到最新版本后重试」）。只有这类配 7 天长记忆（Sollin 裁决纪律：
     * verdict 只记已知终局组合）。
     */
    private val HARD_TERMINAL_PHRASES = listOf(
        "停止服务", "已停服", "停服", "停止维护", "不再维护", "停止支持", "服务已终止",
        "升级到最新版本", "请升级", "版本过低", "版本太低", "版本过旧", "不再支持",
        "discontinued", "no longer supported",
        "upgrade required", "please upgrade", "version too low"
    )

    /**
     * 软终局（ACCEPT4 分级）：字面像终局、但同样可能指「这首歌/这份资源」而非
     * 源本身的自报——「已下线」最典型（歌曲下线是曲目级事实）、deprecated /
     * outdated / end of life 也常被脚本拿来形容单个接口/单曲状态。把这类按
     * 7 天化石化，正是验收四轮快死形态的成因之一：一次曲目级自报拉黑整组合
     * 一周。故只配短 TTL（30 分钟，见 ScriptVerdictStore.SHORT_TTL_MS），
     * 过期自动恢复执行。判定顺序：先硬后软（「服务已终止」含「已终止」，
     * 必须归硬）。
     */
    private val SOFT_TERMINAL_PHRASES = listOf(
        "已终止", "已下线",
        "deprecated", "outdated", "end of life"
    )

    /** 清理脚本自报文本：去控制字符、折叠空白、截断；原始文本永不落盘。 */
    fun sanitize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val cleaned = raw.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
        return cleaned.take(160)
    }

    fun isTerminal(message: String): Boolean = isHardTerminal(message) || isSoftTerminal(message)

    /** 明确终局（源/版本死亡）：配长记忆（7 天）。 */
    fun isHardTerminal(message: String): Boolean {
        if (message.isBlank()) return false
        val lower = message.lowercase()
        return HARD_TERMINAL_PHRASES.any { phrase -> lower.contains(phrase) }
    }

    /** 含糊终局（可能只是曲目级下线）：只配短记忆（30 分钟）。硬终局命中时恒 false。 */
    fun isSoftTerminal(message: String): Boolean {
        if (message.isBlank()) return false
        if (isHardTerminal(message)) return false
        val lower = message.lowercase()
        return SOFT_TERMINAL_PHRASES.any { phrase -> lower.contains(phrase) }
    }
}

/**
 * H1-a verdict 裁决缓存（原型：Sollin 的 source×quality verdict）。
 *
 * 粒度 = 脚本 × 目标平台 × 音质 × 脚本代码哈希。脚本一旦对某组合自报终局错误，
 * 该组合在裁决期内被取链直接跳过（不发请求、同首内跨阶段不重试、跨歌持久）；
 * 代码哈希入键意味着用户更新/重新导入脚本后旧裁决自动失效，无需手动清理。
 * 瞬时失败（网络/超时/软超时/空链）绝不写入本存储，沿用既有会话冷却语义。
 * 存储带世代标记（见 CURRENT_GENERATION）：分类纪律发生追溯性变更时，旧世代
 * 存量整体失效（ACCEPT5 E1 大赦），不让旧纪律的化石裁决污染新链。
 */
object ScriptVerdictStore {
    private const val PREF = "script_verdicts_v1"
    private const val K_VERDICTS = "verdicts"
    private const val K_GEN = "gen"
    private const val K_ENTRIES = "entries"
    private const val MAX_ENTRIES = 240

    /**
     * ACCEPT5（E1）：裁决存储世代。世代 1 = H 波初版格式（顶层 `键 → 过期时间戳`
     * 裸 Long），存在两个不可逆的缺陷：① 写入时只有单档 7 天 TTL——ACCEPT4 才
     * 把「已下线/deprecated」等含糊自报分出 30 分钟软档，**分级之前的存量条目
     * 无法追溯区分硬/软**（不存类别、不存原文、不存写入时刻）；② 这批存量恰写于
     * 2026-11-03~04 全链失效窗口：脚本在坏链下的自报（聚合源上游全灭时的
     * 「已下线」类措辞）被按源级终局落裁 7 天，升级到修复包后继续跳过健康脚本
     * （真机实证：墨澜/屿溪-终章在 tx 全音质档被裁决跳过、从未执行）。
     * 故世代升至 2 时对世代 1 存量**整体大赦**：load 时 gen 不符即视为空，
     * init 时立即落盘新格式。代价可控：真停服脚本下次执行会重新自报终局、
     * 一轮内重新落裁；健康脚本立即解冻。世代 2 起每条裁决另存类别（h/s），
     * 供未来分类表再调整时按类选择性老化，不必再整表大赦。
     */
    private const val CURRENT_GENERATION = 2
    private const val CLASS_HARD = "h"
    private const val CLASS_SHORT = "s"

    /** 终局裁决保留期：停服类脚本在代码不变时不会自愈，7 天足够覆盖两次脚本更新检查周期。 */
    const val TERMINAL_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * ACCEPT4：软终局（已下线/deprecated 等含糊自报）的裁决保留期。这类自报
     * 可能只是曲目级事实，只配短记忆：30 分钟足够挡住同一会话内的无谓重试，
     * 过期自动恢复执行，不再 7 天化石化。与硬终局共用同一存储/同一粒度，
     * 仅写入时 TTL 不同——一套裁决存储、两档 TTL，不是两套记忆。
     */
    const val SHORT_TTL_MS = 30 * 60 * 1000L

    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ContextPreferences.open(
                ctx.applicationContext, PREF, 0, ProfileWorkIdentity.runtime()
            )
        }
        // E1 世代迁移在启动即落盘（不等首次读写）：旧世代存量整体失效并写入
        // 新格式空表；已是当前世代则不动。迁移后 loadStore 仍保留 gen 防御，
        // 测试/迟到写入把旧格式塞回来时同样不生效。
        val preferences = prefs ?: return
        synchronized(this) {
            if (storedGeneration(preferences) != CURRENT_GENERATION) {
                saveStore(preferences, JSONObject())
            }
        }
    }

    private fun p(): SharedPreferences? = prefs

    /** 测试缝（与 recordTerminalAt 同例）：存储实际使用的 SharedPreferences——
     * 种子写入与原始值断言必须与存储同路（ContextPreferences 按资料身份定
     * 文件名，测试自开同名文件可能落到另一个命名空间，断言会形同虚设）。 */
    internal fun sharedPreferencesForTest(): SharedPreferences? = prefs

    private fun codeHash(code: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun key(owner: ProfileContext, script: Script, source: String, quality: String): String =
        JSONArray(
            listOf(
                ProfileWorkIdentity.encodeOwner(owner) + ":" + owner.generation,
                script.id, codeHash(script.code), source, quality.lowercase()
            )
        ).toString()

    private fun storedGeneration(preferences: SharedPreferences): Int {
        val raw = preferences.getString(K_VERDICTS, null) ?: return CURRENT_GENERATION
        return try { JSONObject(raw).optInt(K_GEN, -1) } catch (_: Exception) { -1 }
    }

    /**
     * 读取当前世代的条目表（键 → {"e": 过期时刻, "c": 类别}）。
     * gen 缺失/不符（世代 1 裸 Long 格式、脏数据）一律视为空表——E1 大赦的
     * 读侧防御，与 init 的写侧迁移互为双保险。
     */
    private fun loadStore(preferences: SharedPreferences): JSONObject {
        val raw = preferences.getString(K_VERDICTS, null) ?: return JSONObject()
        val root = try { JSONObject(raw) } catch (_: Exception) { return JSONObject() }
        if (root.optInt(K_GEN, -1) != CURRENT_GENERATION) return JSONObject()
        return root.optJSONObject(K_ENTRIES) ?: JSONObject()
    }

    private fun saveStore(preferences: SharedPreferences, entries: JSONObject) {
        val root = JSONObject().put(K_GEN, CURRENT_GENERATION).put(K_ENTRIES, entries)
        preferences.edit().putString(K_VERDICTS, root.toString()).apply()
    }

    private fun expiryOf(entries: JSONObject, k: String): Long =
        entries.optJSONObject(k)?.optLong("e", 0L) ?: 0L

    /** 该（脚本 × 平台 × 音质）组合是否已被终局裁决且仍在期内。未 init 时恒 false（不拦截）。 */
    @Synchronized
    fun isVerdicted(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String
    ): Boolean {
        val preferences = p() ?: return false
        val all = loadStore(preferences)
        val k = key(owner, script, source, quality)
        val expiry = expiryOf(all, k)
        if (expiry <= 0L) return false
        if (expiry <= System.currentTimeMillis()) {
            all.remove(k)
            saveStore(preferences, all)
            return false
        }
        return true
    }

    /** 记录一条终局裁决。只接受已分类为终局的失败；调用方不得把瞬时失败写进来。 */
    @Synchronized
    fun recordTerminal(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String
    ) = recordTerminalAt(owner, script, source, quality, System.currentTimeMillis())

    /** [recordTerminal] 的可测形态：裁决过期点 = [now] + [TERMINAL_TTL_MS]。 */
    @Synchronized
    internal fun recordTerminalAt(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String,
        now: Long
    ) = recordVerdictAt(owner, script, source, quality, now, TERMINAL_TTL_MS)

    /** 记录一条软终局裁决（短 TTL）。只接受已分类为软终局的自报；瞬时失败不得写。 */
    @Synchronized
    fun recordShortVerdict(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String
    ) = recordShortAt(owner, script, source, quality, System.currentTimeMillis())

    /** [recordShortVerdict] 的可测形态：裁决过期点 = [now] + [SHORT_TTL_MS]。 */
    @Synchronized
    internal fun recordShortAt(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String,
        now: Long
    ) = recordVerdictAt(owner, script, source, quality, now, SHORT_TTL_MS)

    /** 硬/软共用写路径：同一存储、同一粒度，仅 TTL 与类别标记不同。 */
    @Synchronized
    internal fun recordVerdictAt(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String,
        now: Long,
        ttlMs: Long
    ) {
        val preferences = p() ?: return
        val all = loadStore(preferences)
        // 写入前清过期项并控量，避免无界增长。
        val pruned = JSONObject()
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val entry = all.optJSONObject(k) ?: continue
            if (entry.optLong("e", 0L) > now) pruned.put(k, entry)
        }
        if (pruned.length() >= MAX_ENTRIES && !pruned.has(key(owner, script, source, quality))) return
        pruned.put(
            key(owner, script, source, quality),
            JSONObject()
                .put("e", now + ttlMs)
                .put("c", if (ttlMs <= SHORT_TTL_MS) CLASS_SHORT else CLASS_HARD)
        )
        saveStore(preferences, pruned)
    }

    /** 某组合实际取链成功：裁决立即失效（脚本已自愈或先前误判都不得继续跳过）。 */
    @Synchronized
    fun clearVerdict(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String
    ) {
        val preferences = p() ?: return
        val all = loadStore(preferences)
        val k = key(owner, script, source, quality)
        if (all.has(k)) {
            all.remove(k)
            saveStore(preferences, all)
        }
    }
}
