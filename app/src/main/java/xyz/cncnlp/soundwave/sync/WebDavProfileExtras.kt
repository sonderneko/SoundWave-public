package xyz.cncnlp.soundwave.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.BackupPreferenceProjection
import xyz.cncnlp.soundwave.ContextPreferences
import xyz.cncnlp.soundwave.DislikeStore
import xyz.cncnlp.soundwave.Script
import xyz.cncnlp.soundwave.ScriptStorage
import xyz.cncnlp.soundwave.migration.ProfileWorkIdentity
import java.security.MessageDigest

/**
 * WebDAV 账户文档的扩展载荷（顶层键 `profileExtras`，与 `user` 平级）。
 *
 * 承载 UserBundle 之外的用户数据（用户 2026-11-03 拍板纳入 WebDAV 同步范围）：
 *  - scripts：音源脚本本体 + 启用状态 + 顺序（数组顺序即优先级）；
 *  - smartSource：智能换源策略配置（SmartSourceStore 的持久键，含各平台音质 plat_play_quality）；
 *  - settings：音质（全局 play_br/down_br）与个性化样式（底栏/播放器/详情/主题/歌词），
 *    沿用手动备份的 BackupPreferenceProjection 白名单与校验，不新开一套口径；
 *  - dislikes：不喜欢/屏蔽记录（keys + identities 两集合）。
 *
 * 合并语义：分节最后写入者胜（LWW）。每节带 updatedAt 戳：capture 时对节内容做哈希，
 * 与上次同步记录的哈希一致则复用旧戳（未变不算新），变化才打新戳；merge 逐节取戳大者。
 * apply 语义：scripts/dislikes 为自包含存储，整节替换；settings/smartSource 为 prefs 型，
 * 逐键覆盖（节内未出现的键保持本机值）。旧文档无此键 → 读端忽略，天然向后兼容；
 * 旧版本 app 读新文档同样忽略未知顶层键。
 *
 * 与手动备份的边界差异：手动备份按 BackupPreferenceProjection 的安全口径只带脚本
 * 配置不带代码（备份文件可能被外传）；WebDAV 同步目标是用户自己的凭据保护服务器，
 * 且用户明确要求脚本本体进同步，故此处带代码。手动备份口径不变。
 */
class WebDavProfileExtrasSync internal constructor(
    val capture: () -> JSONObject?,
    val apply: (JSONObject) -> Unit,
    val isCurrent: () -> Boolean
)

internal object WebDavProfileExtras {
    private const val STATE_PREFS = "webdav_profile_extras_v1"
    private const val SECTION_SCRIPTS = "scripts"
    private const val SECTION_SMART_SOURCE = "smartSource"
    private const val SECTION_SETTINGS = "settings"
    private const val SECTION_DISLIKES = "dislikes"

    /** 与 SmartSourceStore 的 K_* 持久键保持一致（该 store 的全部用户配置面）。 */
    private val SMART_SOURCE_KEYS = listOf(
        "plat_order", "plat_script", "active_script", "backup_order", "stage_order",
        "fallback_128k", "remember_cross", "remembered_map", "smart_enabled", "plat_play_quality"
    )

    private fun statePrefs(context: Context) = ContextPreferences.open(
        context.applicationContext, STATE_PREFS, 0, ProfileWorkIdentity.runtime()
    )

    private fun mainPrefs(context: Context) = ContextPreferences.open(
        context.applicationContext, "soundwave", 0, ProfileWorkIdentity.runtime()
    )

    private fun scriptsPrefs(context: Context) = ContextPreferences.open(
        context.applicationContext, "soundwave_scripts", 0, ProfileWorkIdentity.runtime()
    )

    private fun smartSourcePrefs(context: Context) = ContextPreferences.open(
        context.applicationContext, "smart_source_v1", 0, ProfileWorkIdentity.runtime()
    )

    private fun contentHash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)

    /** 组装一节：内容未变复用旧戳，变化打新戳并记账。 */
    private fun stampedSection(context: Context, name: String, data: JSONObject): JSONObject {
        val prefs = statePrefs(context)
        val hash = contentHash(data.toString())
        val storedHash = prefs.getString("hash_$name", null)
        val storedStamp = prefs.getLong("stamp_$name", 0L)
        val stamp = if (storedHash == hash && storedStamp > 0L) storedStamp else System.currentTimeMillis()
        if (storedHash != hash || storedStamp != stamp) {
            prefs.edit().putString("hash_$name", hash).putLong("stamp_$name", stamp).apply()
        }
        return JSONObject().put("updatedAt", stamp).put("data", data)
    }

    private fun recordApplied(context: Context, name: String, section: JSONObject) {
        val data = section.optJSONObject("data") ?: return
        statePrefs(context).edit()
            .putString("hash_$name", contentHash(data.toString()))
            .putLong("stamp_$name", section.optLong("updatedAt", 0L))
            .apply()
    }

    fun capture(context: Context): JSONObject {
        val root = JSONObject()

        // scripts：本体 + 启用 + 顺序（Script.toJson 不含代码，单独补 code 字段）
        val scripts = ScriptStorage.loadScripts(scriptsPrefs(context))
        val scriptItems = JSONArray()
        scripts.forEach { script ->
            scriptItems.put(script.toJson().put("code", script.code))
        }
        root.put(SECTION_SCRIPTS, stampedSection(
            context, SECTION_SCRIPTS, JSONObject().put("items", scriptItems)))

        // smartSource：持久键逐项（值均为字符串）
        val smart = JSONObject()
        val smartPrefs = smartSourcePrefs(context)
        SMART_SOURCE_KEYS.forEach { key ->
            smartPrefs.getString(key, null)?.let { smart.put(key, it) }
        }
        root.put(SECTION_SMART_SOURCE, stampedSection(context, SECTION_SMART_SOURCE, smart))

        // settings：手动备份同款投影（白名单 + 值域校验）
        val all = mainPrefs(context).all
        val settings = BackupPreferenceProjection.settings(
            JSONObject().apply { all.forEach { (k, v) -> if (v != null) put(k, v) } }, strict = false)
        root.put(SECTION_SETTINGS, stampedSection(context, SECTION_SETTINGS, settings))

        // dislikes：两集合
        val dislikes = JSONObject()
            .put("keys", JSONArray(DislikeStore.keys(context).toList()))
            .put("identities", JSONArray(DislikeStore.identities(context).toList()))
        root.put(SECTION_DISLIKES, stampedSection(context, SECTION_DISLIKES, dislikes))

        return root
    }

    /** 分节 LWW 合并；任一侧缺节时取另一侧；同戳取本机（避免无谓翻转）。 */
    fun merge(local: JSONObject?, remote: JSONObject?): JSONObject? {
        if (local == null) return remote
        if (remote == null) return local
        val result = JSONObject()
        val names = linkedSetOf<String>()
        local.keys().forEach { names.add(it) }
        remote.keys().forEach { names.add(it) }
        names.forEach { name ->
            val l = local.optJSONObject(name)
            val r = remote.optJSONObject(name)
            val winner = when {
                l == null -> r
                r == null -> l
                r.optLong("updatedAt", 0L) > l.optLong("updatedAt", 0L) -> r
                else -> l
            }
            if (winner != null) result.put(name, winner)
        }
        return if (result.length() == 0) null else result
    }

    fun apply(context: Context, extras: JSONObject) {
        extras.optJSONObject(SECTION_SCRIPTS)?.let { section ->
            val items = section.optJSONObject("data")?.optJSONArray("items")
            if (items != null) {
                val scripts = ArrayList<Script>(items.length())
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val script = Script.fromJson(item).copy(code = item.optString("code"))
                    if (script.id.isNotBlank()) scripts.add(script)
                }
                // 整节替换：写入获胜节的完整脚本集（含顺序与启用状态）。
                ScriptStorage.saveScripts(scriptsPrefs(context), scripts)
            }
            recordApplied(context, SECTION_SCRIPTS, section)
        }
        extras.optJSONObject(SECTION_SMART_SOURCE)?.let { section ->
            val data = section.optJSONObject("data")
            if (data != null) {
                val editor = smartSourcePrefs(context).edit()
                SMART_SOURCE_KEYS.forEach { key ->
                    if (data.has(key)) editor.putString(key, data.optString(key))
                }
                editor.apply()
            }
            recordApplied(context, SECTION_SMART_SOURCE, section)
        }
        extras.optJSONObject(SECTION_SETTINGS)?.let { section ->
            val data = section.optJSONObject("data")
            if (data != null) {
                val clean = BackupPreferenceProjection.settings(data, strict = false)
                val editor = mainPrefs(context).edit()
                clean.keys().forEach { key ->
                    when (val v = clean.opt(key)) {
                        is Boolean -> editor.putBoolean(key, v)
                        is Int -> editor.putInt(key, v)
                        is Long -> editor.putLong(key, v)
                        is Float -> editor.putFloat(key, v)
                        is Double -> editor.putFloat(key, v.toFloat())
                        is String -> editor.putString(key, v)
                    }
                }
                editor.apply()
            }
            recordApplied(context, SECTION_SETTINGS, section)
        }
        extras.optJSONObject(SECTION_DISLIKES)?.let { section ->
            val data = section.optJSONObject("data")
            if (data != null) {
                fun strings(name: String): Set<String> {
                    val arr = data.optJSONArray(name) ?: return emptySet()
                    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
                }
                DislikeStore.replaceAll(context, strings("keys"), strings("identities"))
            }
            recordApplied(context, SECTION_DISLIKES, section)
        }
    }
}
