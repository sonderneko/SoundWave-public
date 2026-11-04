package xyz.cncnlp.soundwave

import org.json.JSONObject

/**
 * 平台能力声明：客户端只描述公开数据能力，不包含第三方账号登录/同步能力。
 * 默认保守声明只保证“请求透传”存在；服务端返回 /music/capabilities 时覆盖并持久化。
 */
data class PlatformCapability(
    val source: String,
    val search: Boolean = true,
    val hot: Boolean = false,
    val rank: Boolean = false,
    val playlistSearch: Boolean = true,
    val playlistDetail: Boolean = true,
    val album: Boolean = false,
    val discover: Boolean = false,
    /** true 表示服务端明确声明过该平台数据来源已验证。 */
    val verified: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("source", source)
        put("search", search); put("hot", hot); put("rank", rank)
        put("playlistSearch", playlistSearch); put("playlistDetail", playlistDetail)
        put("album", album); put("discover", discover)
        put("verified", verified)
    }

    companion object {
        fun fromJson(o: JSONObject) = PlatformCapability(
            source = o.optString("source"),
            search = o.optBoolean("search", true),
            hot = o.optBoolean("hot", false),
            rank = o.optBoolean("rank", false),
            playlistSearch = o.optBoolean("playlistSearch", true),
            playlistDetail = o.optBoolean("playlistDetail", false),
            album = o.optBoolean("album", false),
            discover = o.optBoolean("discover", false),
            verified = o.optBoolean("verified", false)
        )
    }
}

/** 平台注册表：默认保守声明 + 服务端 /music/capabilities 覆盖。 */
object ProviderRegistry {
    const val K_CAPS = "provider_capabilities_v1"

    /** 默认声明：对未验证的公开发现能力采取保守策略。 */
    val defaults: Map<String, PlatformCapability> = run {
        val wy = PlatformCapability(
            source = "wy",
            search = true, hot = true, rank = true,
            playlistSearch = true, playlistDetail = true,
            album = true, discover = true,
            verified = true
        )
        val generic = listOf("kg", "kw", "tx", "mg").associateWith { src ->
            // 账号认证/用户歌单导入已从客户端移除；此能力表只描述公开搜索与元数据。
            PlatformCapability(source = src, search = true,
                hot = src in setOf("kg", "kw", "tx", "mg"),
                rank = src in setOf("kg", "kw", "tx", "mg"),
                playlistSearch = true, playlistDetail = src in setOf("kg", "kw", "tx", "mg"),
                album = src in setOf("tx"), discover = src == "mg",
                verified = src == "mg")
        }
        buildMap { put("wy", wy); putAll(generic) }
    }

    fun save(prefs: android.content.SharedPreferences, caps: Map<String, PlatformCapability>) {
        val root = JSONObject()
        caps.forEach { (k, v) -> root.put(k, v.toJson()) }
        prefs.edit().putString(K_CAPS, root.toString()).apply()
    }

    fun load(prefs: android.content.SharedPreferences): Map<String, PlatformCapability> {
        val raw = prefs.getString(K_CAPS, null) ?: return defaults
        return try {
            val root = JSONObject(raw)
            defaults.mapValues { (k, def) ->
                root.optJSONObject(k)?.let { PlatformCapability.fromJson(it) } ?: def
            }
        } catch (_: Exception) {
            defaults
        }
    }

    fun capability(source: String, prefs: android.content.SharedPreferences): PlatformCapability =
        load(prefs)[source] ?: PlatformCapability(source = source)
}
