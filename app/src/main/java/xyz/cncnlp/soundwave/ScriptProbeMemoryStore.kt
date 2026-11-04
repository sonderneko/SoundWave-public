package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity

/**
 * ACCEPT5（D）：探针死链短记忆——脚本排序「按成败历史学习」在现有架构里能落
 * 地的一半。背景：脚本成败信号原本只有三处去向——脚本自报终局进持久裁决
 * （ScriptVerdictStore）、软超时进进程内短记忆（ScriptTimeoutMemory）、其余
 * （含探针实测的确定性死链 HTTP 401/403/404/410）只活在单次解析的 attempts
 * 里，跨歌全部遗忘。真机实证（咪咕《我好想你》）：墨澜在 mg 的 MASTER 链
 * 探针 404（约 3s）这一硬事实，下一首 mg 歌仍会原样再付一遍。
 *
 * 本存储只收「探针实测的确定性 HTTP 终局状态」：该（脚本×平台×音质×代码
 * 哈希）组合返回过的 URL 被服务端以 401/403/404/410 明确拒绝——这是可证伪
 * 的曲目无关事实（同一组合短期内不会自愈），配 30 分钟短记忆（与裁决软档
 * 同档：足够挡住一个会话内的重复付费，过期自动恢复，不化石化）。粒度严格
 * 到音质：MASTER 死链不连坐 FLAC（真机另有实证：同脚本 MASTER 404 后
 * FLAC 成功）。网络失败/超时/HTML 应答等不确定信号绝不写入（各有其记忆层）。
 * 命中成功（HIT）即清除该组合条目。本存储不改写用户的脚本链顺序——学习只
 * 以「计划内跳过」的形态生效，顺序骨架仍是用户的设置（裁定二同理）。
 */
object ScriptProbeMemoryStore {
    private const val PREF = "script_probe_memory_v1"
    private const val K_ROOT = "probe_memory"
    private const val K_GEN = "gen"
    private const val K_ENTRIES = "entries"
    private const val CURRENT_GENERATION = 1
    private const val MAX_ENTRIES = 240

    /** 死链短记忆保留期：与裁决软档同档（30 分钟）。 */
    const val PROBE_MEMORY_TTL_MS = 30 * 60 * 1000L

    /** 只有这些状态码算「确定性死链」：服务端明确拒绝该 URL 本身。 */
    val DEFINITIVE_HTTP_STATUSES = setOf(401, 403, 404, 410)

    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ContextPreferences.open(
                ctx.applicationContext, PREF, 0, ProfileWorkIdentity.runtime()
            )
        }
    }

    private fun p(): SharedPreferences? = prefs

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

    private fun loadStore(preferences: SharedPreferences): JSONObject {
        val raw = preferences.getString(K_ROOT, null) ?: return JSONObject()
        val root = try { JSONObject(raw) } catch (_: Exception) { return JSONObject() }
        if (root.optInt(K_GEN, -1) != CURRENT_GENERATION) return JSONObject()
        return root.optJSONObject(K_ENTRIES) ?: JSONObject()
    }

    private fun saveStore(preferences: SharedPreferences, entries: JSONObject) {
        val root = JSONObject().put(K_GEN, CURRENT_GENERATION).put(K_ENTRIES, entries)
        preferences.edit().putString(K_ROOT, root.toString()).apply()
    }

    /** 该组合是否在死链短记忆期内。未 init 时恒 false（不拦截）。 */
    @Synchronized
    fun isMemorized(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String
    ): Boolean {
        val preferences = p() ?: return false
        val all = loadStore(preferences)
        val k = key(owner, script, source, quality)
        val expiry = all.optLong(k, 0L)
        if (expiry <= 0L) return false
        if (expiry <= System.currentTimeMillis()) {
            all.remove(k)
            saveStore(preferences, all)
            return false
        }
        return true
    }

    /** 记录一次确定性死链探针结果。非确定性状态由调用方过滤，本函数再核一道。 */
    @Synchronized
    fun recordProbeFailure(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String,
        httpStatus: Int
    ) = recordProbeFailureAt(owner, script, source, quality, httpStatus, System.currentTimeMillis())

    /** [recordProbeFailure] 的可测形态：过期点 = [now] + [PROBE_MEMORY_TTL_MS]。 */
    @Synchronized
    internal fun recordProbeFailureAt(
        owner: ProfileContext,
        script: Script,
        source: String,
        quality: String,
        httpStatus: Int,
        now: Long
    ) {
        if (httpStatus !in DEFINITIVE_HTTP_STATUSES) return
        val preferences = p() ?: return
        val all = loadStore(preferences)
        // 先剪过期，再控总量（与裁决仓同一纪律：重建新表，不在迭代中改原表）。
        val pruned = JSONObject()
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val expiry = all.optLong(k, 0L)
            if (expiry > now) pruned.put(k, expiry)
        }
        if (pruned.length() >= MAX_ENTRIES && !pruned.has(key(owner, script, source, quality))) return
        pruned.put(key(owner, script, source, quality), now + PROBE_MEMORY_TTL_MS)
        saveStore(preferences, pruned)
    }

    /** 该组合命中成功后清除其死链记忆（成功是最强的反证，不等 TTL）。 */
    @Synchronized
    fun clearOnSuccess(
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
