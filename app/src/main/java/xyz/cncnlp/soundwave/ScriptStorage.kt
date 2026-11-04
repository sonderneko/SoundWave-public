package xyz.cncnlp.soundwave

import android.content.SharedPreferences
import android.content.Context
import xyz.cncnlp.soundwave.script.MAX_SCRIPT_IMPORT_BYTES
import org.json.JSONArray
import org.json.JSONObject
import xyz.cncnlp.soundwave.script.BoundedZipReader

/**
 * 脚本本地存储：不内置音源。
 * 仅支持用户本地文件 / 链接导入；列表顺序 = 调用优先级（Sollin 风格可手动排序）。
 */
object ScriptStorage {
    /** 推荐保留：星海/全豆要/西瓜/洛雪官方/ikun 及平台向 */
    private val KEEP_HINTS = listOf("星海", "全豆要", "全都要", "西瓜", "LX", "洛雪", "Huibq", "ikun", "酷狗", "qq", "咪咕", "migu")
    fun isRecommended(sc: Script): Boolean {
        val n = (sc.name + " " + sc.id).lowercase()
        return KEEP_HINTS.any { it.lowercase() in n }
    }

    private const val KEY_META = "script_meta"
    private const val KEY_CODE_PREFIX = "script_code_"

    private fun profilePrefs(context: Context, profileId: ProfileId): SharedPreferences =
        context.applicationContext.getSharedPreferences(profileId.preferencesName, Context.MODE_PRIVATE)

    fun loadScripts(prefs: SharedPreferences): List<Script> {
        val raw = prefs.getString(KEY_META, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<Script>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val code = prefs.getString(KEY_CODE_PREFIX + id, "") ?: ""
                // 历史 builtin 标记忽略，全部当作用户脚本
                list.add(Script.fromJson(o).copy(code = code, builtin = false))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveScripts(prefs: SharedPreferences, scripts: List<Script>) {
        val arr = JSONArray()
        scripts.forEach { arr.put(it.toJson().apply { put("builtin", false) }) }
        val ed = prefs.edit().putString(KEY_META, arr.toString())
        scripts.forEach { s ->
            if (s.code.isNotEmpty()) ed.putString(KEY_CODE_PREFIX + s.id, s.code)
        }
        ed.apply()
    }

    fun saveCode(prefs: SharedPreferences, id: String, code: String) {
        prefs.edit().putString(KEY_CODE_PREFIX + id, code).apply()
    }

    fun loadCode(prefs: SharedPreferences, id: String): String? =
        prefs.getString(KEY_CODE_PREFIX + id, null)

    fun deleteScript(prefs: SharedPreferences, scripts: MutableList<Script>, id: String) {
        scripts.removeAll { it.id == id }
        prefs.edit().remove(KEY_CODE_PREFIX + id).apply()
        saveScripts(prefs, scripts)
    }

    /**
     * Explicit profile-local storage for user-imported scripts. These APIs only use the named
     * profile's local preferences; they do not activate profiles or execute scripts.
     */
    fun loadScripts(context: Context, profileId: ProfileId): List<Script> =
        loadScripts(profilePrefs(context, profileId))

    fun saveScripts(context: Context, profileId: ProfileId, scripts: List<Script>) =
        saveScripts(profilePrefs(context, profileId), scripts)

    fun saveCode(context: Context, profileId: ProfileId, id: String, code: String) =
        saveCode(profilePrefs(context, profileId), id, code)

    fun loadCode(context: Context, profileId: ProfileId, id: String): String? =
        loadCode(profilePrefs(context, profileId), id)

    /** Removes only a script already listed in this profile. */
    fun deleteScript(context: Context, profileId: ProfileId, id: String): Boolean {
        val prefs = profilePrefs(context, profileId)
        val scripts = loadScripts(prefs).toMutableList()
        if (scripts.none { it.id == id }) return false
        deleteScript(prefs, scripts, id)
        return true
    }

    /** Changes enabled metadata only when this script is owned by the addressed profile. */
    fun setEnabledIfOwned(context: Context, profileId: ProfileId, id: String, enabled: Boolean): Boolean {
        val prefs = profilePrefs(context, profileId)
        val scripts = loadScripts(prefs).toMutableList()
        val index = scripts.indexOfFirst { it.id == id }
        if (index < 0) return false
        scripts[index] = scripts[index].copy(enabled = enabled)
        saveScripts(prefs, scripts)
        return true
    }

    /** Imports supplied local source into this profile's library without network access. */
    fun importScript(context: Context, profileId: ProfileId, code: String): Script {
        val prefs = profilePrefs(context, profileId)
        return importScript(prefs, loadScripts(prefs).toMutableList(), code)
    }

    /** Imports a local JS/ZIP file into this profile's library without changing legacy storage. */
    fun importFromBytes(context: Context, profileId: ProfileId, bytes: ByteArray, name: String = ""): List<Script> {
        val prefs = profilePrefs(context, profileId)
        return importFromBytes(prefs, loadScripts(prefs).toMutableList(), bytes, name)
    }

    /** 兼容旧调用：不再从云端拉内置；返回 0 */
    suspend fun loadBuiltinScripts(prefs: SharedPreferences, scripts: MutableList<Script>): Int = 0

    /** 当前运行时只执行 LX 协议。兼容常见 Unicode 转义/混淆；MusicFree/CommonJS 明确拒绝。 */
    fun compatibilityError(code: String): String? {
        if (code.isBlank() || code.length < 20) return "脚本内容为空或不完整"
        val normalized = decodeJsEscapes(code)
        val lx = Regex("\\b(?:globalThis\\.)?lx\\s*\\.").containsMatchIn(normalized) ||
            (normalized.contains("EVENT_NAMES") && (normalized.contains("musicUrl") || normalized.contains("getMusicUrl") || normalized.contains("send") || normalized.contains("request")))
        if (lx) return null
        if (normalized.contains("module.exports") || normalized.contains("getMediaSource") || normalized.contains("MusicFree", true)) {
            return "这是 MusicFree 脚本，当前仅支持洛雪 LX 脚本"
        }
        return "未识别为洛雪 LX 脚本（需 lx.on/lx.send/musicUrl）"
    }

    fun importScript(prefs: SharedPreferences, scripts: MutableList<Script>, code: String): Script {
        val script = buildImportedScript(scripts, code)
        saveCode(prefs, script.id, code)
        saveScripts(prefs, scripts)
        return script
    }

    private fun buildImportedScript(scripts: MutableList<Script>, code: String): Script {
        require(compatibilityError(code) == null) { compatibilityError(code) ?: "脚本不兼容" }
        val meta = parseMeta(decodeJsEscapes(code))
        var id = meta.name.ifEmpty { "script_${System.currentTimeMillis()}" }
        if (!id.endsWith(".js")) id = "$id.js"
        // 去重同名
        val base = id.removeSuffix(".js")
        var finalId = id
        var n = 1
        while (scripts.any { it.id == finalId }) {
            finalId = "${base}_$n.js"
            n++
        }
        val s = Script(
            id = finalId,
            name = meta.name.ifEmpty { finalId.removeSuffix(".js") },
            description = meta.description,
            version = meta.version,
            author = meta.author,
            builtin = false,
            code = code,
            enabled = true
        )
        scripts.add(s)
        return s
    }

    /** 从本地文件导入裸 JS 或 ZIP 内多个 .js；ZIP 中不兼容文件跳过，兼容文件继续导入。 */
    fun importFromBytes(prefs: SharedPreferences, scripts: MutableList<Script>, bytes: ByteArray, name: String = ""): List<Script> {
        require(bytes.size <= MAX_SCRIPT_IMPORT_BYTES) { "脚本文件超过 8MB" }
        val out = ArrayList<Script>()
        val stagedScripts = scripts.toMutableList()
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            // Fully validate the archive before parsing or committing any compatible scripts.
            BoundedZipReader.read(bytes).forEach { entry ->
                runCatching { buildImportedScript(stagedScripts, entry.bytes.toString(Charsets.UTF_8)) }
                    .onSuccess { out += it }
            }
            require(out.isNotEmpty()) { "ZIP 内没有可导入的洛雪 LX 脚本" }
        } else {
            out += buildImportedScript(stagedScripts, bytes.toString(Charsets.UTF_8))
        }
        scripts.addAll(out)
        saveScripts(prefs, scripts)
        return out
    }

    suspend fun importFromUrl(prefs: SharedPreferences, scripts: MutableList<Script>, url: String): Script? {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) throw IllegalArgumentException("脚本链接必须使用 HTTPS")
        val code = ApiClient.httpGetRaw(u)
        if (code.isBlank() || code.length < 20) throw IllegalArgumentException("链接未返回有效脚本内容")
        return importScript(prefs, scripts, code)
    }

    /**
     * P2-10：URL 脚本预取（不落盘）。返回 (代码, SHA-256, 来源域名)，
     * 由 UI 展示来源 + 摘要、用户二次确认后再调 [importScript] 真正导入。
     * 校验与 [importFromUrl] 相同（HTTPS-only + 非空 + 兼容性），失败抛 IllegalArgumentException。
     */
    data class FetchedUrlScript(val code: String, val sha256: String, val host: String)

    suspend fun fetchUrlScript(url: String): FetchedUrlScript {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) throw IllegalArgumentException("脚本链接必须使用 HTTPS")
        val code = ApiClient.httpGetRaw(u)
        if (code.isBlank() || code.length < 20) throw IllegalArgumentException("链接未返回有效脚本内容")
        val err = compatibilityError(code)
        if (err != null) throw IllegalArgumentException(err)
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
        val host = try { java.net.URI(u).host ?: "" } catch (_: Exception) { "" }
        return FetchedUrlScript(code, sha, host)
    }

    fun move(scripts: MutableList<Script>, id: String, delta: Int): List<Script> {
        val i = scripts.indexOfFirst { it.id == id }
        if (i < 0) return scripts
        val j = (i + delta).coerceIn(0, scripts.lastIndex)
        if (i == j) return scripts
        val item = scripts.removeAt(i)
        scripts.add(j, item)
        return scripts.toList()
    }

    data class ScriptMeta(val name: String, val description: String, val version: String, val author: String)

    private fun parseMeta(code: String): ScriptMeta {
        fun cap(re: String) = Regex(re).find(code)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return ScriptMeta(
            name = cap("""@name\s+(.+)"""),
            description = cap("""@description\s+(.+)"""),
            version = cap("""@version\s+(.+)"""),
            author = cap("""@author\s+(.+)""")
        )
    }

    private fun decodeJsEscapes(code: String): String = code
        .replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("\\\\x([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
}
