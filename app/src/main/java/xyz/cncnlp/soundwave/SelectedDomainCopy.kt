package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal enum class LocalCopyDomain(val source: String, val displayName: String, val description: String) {
    SETTINGS("soundwave", "播放与界面设置", "音质、歌词和播放偏好"),
    STATS("listen_stats_v1", "收听统计", "本机的时长与播放计数"),
    DISLIKES("soundwave", "不喜欢的歌曲", "只复制歌曲标记"),
    SCRIPTS("soundwave_scripts", "脚本数据", "只存入隔离副本，不会启用或执行"),
    SMART_SOURCE("smart_source_v1", "音源偏好", "音源选择顺序，不含临时链接"),
    SORT("soundwave_sort", "列表排序", "歌曲列表的排序方式"),
    SCROLL("list_scroll_v1", "列表位置", "记住的浏览位置")
}

/** Local quarantine payloads, never runtime/remote authorization. No device grants or files copied. */
internal object SelectedDomainCopy {
    private val copyLock = Any()
    private val strings = setOf("play_br", "down_br", "lyric_priority", "download_name_tpl", "lyric_highlight_color", "lyric_unhighlight_color", "search_history_v1", "lib_order_v1")
    private val ints = setOf("prefetch_interval_sec", "prefetch_count", "eq_preset", "lyric_font_sp", "lyric_gap_dp", "lyric_immersive_sp", "lyric_immersive_gap_dp", "lyric_blur_dp", "lyric_offset_ms")
    private val bools = setOf("auto_play_on_start", "prefetch_next", "download_mt", "save_ext_lyric", "save_ext_cover", "lyric_bold", "lyric_show_translation", "lyric_show_romanization", "lyric_word_karaoke", "lyric_word_lift")
    private val stats = setOf("total_sec", "today_date", "today_sec", "week_label", "week_sec", "song_map_v1", "artist_map_v1", "play_count_v1", "platform_map_v1")
    private fun dailyKey(key: String): Boolean {
        if (!Regex("day_sec_[0-9]{8}").matches(key)) return false
        return runCatching {
            java.time.LocalDate.parse(key.removePrefix("day_sec_"),
                java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        }.isSuccess
    }
    private val smart = setOf("plat_order", "plat_script", "active_script", "backup_order", "stage_order", "fallback_128k", "remember_cross", "smart_enabled", "plat_play_quality", "plat_down_quality", "stage_order_version")
    fun project(domain: LocalCopyDomain, source: Map<String, *>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        source.forEach { (key, raw) ->
            val allowed = when (domain) {
                LocalCopyDomain.SETTINGS -> key in strings || key in ints || key in bools || key == "play_speed"
                LocalCopyDomain.STATS -> key in stats || (dailyKey(key) && raw is Long && raw >= 0L)
                LocalCopyDomain.DISLIKES -> key == "dislike_keys_v1"
                LocalCopyDomain.SCRIPTS -> key == "script_meta" || key.startsWith("script_code_")
                LocalCopyDomain.SMART_SOURCE -> key in smart // expiring URLs deliberately excluded
                LocalCopyDomain.SORT -> key.startsWith("mode_") && raw is String && raw in setOf("default", "old", "name", "singer")
                LocalCopyDomain.SCROLL -> (key.startsWith("i_") || key.startsWith("o_")) && raw is Int && (!key.startsWith("i_") || raw >= 0)
            }
            if (!allowed) return@forEach
            val value = raw ?: error("Null selected value")
            require(value is String || value is Int || value is Long || value is Float || value is Boolean || value is Set<*>)
            if (value is Float) require(value.isFinite())
            if (value is String) require(value.length <= 8 * 1024 * 1024)
            if (domain == LocalCopyDomain.SETTINGS) require(when (key) {
                in strings -> value is String
                in ints -> value is Int
                in bools -> value is Boolean
                else -> value is Float
            })
            when (domain) {
                LocalCopyDomain.DISLIKES -> require(value is Set<*> && value.all { it is String })
                LocalCopyDomain.SCRIPTS -> {
                    require(value is String)
                    if (key == "script_meta") JSONArray(value) else require(key.removePrefix("script_code_").isNotBlank())
                }
                LocalCopyDomain.STATS -> require(if (dailyKey(key) || key in setOf("total_sec", "today_sec", "week_sec")) value is Long && value >= 0L else value is String)
                LocalCopyDomain.SMART_SOURCE -> require(if (key in setOf("fallback_128k", "remember_cross", "smart_enabled")) value is Boolean else if (key == "stage_order_version") value is Int else value is String)
                else -> Unit
            }
            if (value is Set<*>) { require(value.all { it is String }); out[key] = value.toSet() }
            else out[key] = value
        }
        require(out.keys.count(::dailyKey) <= 36600) { "Daily statistics exceed 100 years" }
        return out.toMap()
    }
    fun namespace(id: ProfileId, domain: LocalCopyDomain) = "soundwave_copy_${domain.name.lowercase()}_${id.value}"
    private fun encode(values: Map<String, Any>): String = JSONObject().apply {
        values.forEach { (key, value) -> put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
    }.toString()
    fun preflight(context: Context, id: ProfileId, domains: Set<LocalCopyDomain>) = synchronized(copyLock) {
        check(!context.getSharedPreferences("soundwave_copy_domain_journal", 0).contains(id.value)) { "Occupied auxiliary journal" }
        domains.forEach { domain ->
            check(context.getSharedPreferences(namespace(id, domain), 0).all.isEmpty()) { "Occupied copy namespace" }
        }
    }
    /** PREPARED records invocation ownership BEFORE writing. Receipt is known even on partial failure. */
    fun copy(context: Context, id: ProfileId, domains: Map<LocalCopyDomain, Map<String, Any>>,
             operation: String = java.util.UUID.randomUUID().toString()): Unit = synchronized(copyLock) {
        if (domains.isEmpty()) return
        preflight(context, id, domains.keys)
        val journal = context.getSharedPreferences("soundwave_copy_domain_journal", 0)
        val manifest = domains.keys.sortedBy { it.name }.joinToString(",") { it.name }
        check(journal.edit().putString(id.value, "PREPARED:$operation:$manifest").commit())
        domains.forEach { (domain, values) ->
            val p = context.getSharedPreferences(namespace(id, domain), 0)
            check(p.all.isEmpty()) { "Occupied copy namespace" }
            val raw = encode(values)
            check(p.edit().putString("payload", raw).commit() && p.getString("payload", null) == raw)
        }
        check(journal.edit().putString(id.value, "COMPLETE:$operation:$manifest").commit())
        check(journal.getString(id.value, null) == "COMPLETE:$operation:$manifest")
    }
    fun available(context: Context, id: ProfileId): Boolean = runCatching {
        val raw = context.getSharedPreferences("soundwave_copy_domain_journal", 0).getString(id.value, null)
        if (raw == null) true else {
            check(raw.startsWith("COMPLETE:"))
            val names = raw.substringAfterLast(':').split(',')
            check(names.isNotEmpty() && names.distinct().size == names.size)
            names.forEach { name ->
                val domain = LocalCopyDomain.valueOf(name)
                val p = context.getSharedPreferences(namespace(id, domain), 0)
                check(p.all.keys == setOf("payload"))
                JSONObject(p.getString("payload", null) ?: error("Missing domain payload"))
            }
            true
        }
    }.getOrDefault(false)
    fun rollback(context: Context, id: ProfileId, operation: String? = null): Boolean = synchronized(copyLock) { runCatching {
        val journal = context.getSharedPreferences("soundwave_copy_domain_journal", 0)
        val raw = journal.getString(id.value, null) ?: return true
        // Import failure cleanup may only delete a journal created by that invocation.
        if (operation != null && raw.split(':').getOrNull(1) != operation) return true
        check(raw.startsWith("PREPARED:") || raw.startsWith("COMPLETE:"))
        val names = raw.substringAfterLast(':').split(',')
        check(names.distinct().size == names.size)
        val domains = names.map { LocalCopyDomain.valueOf(it) }
        check(journal.edit().putString(id.value, "PREPARED:${raw.substringAfter(':')}").commit())
        domains.forEach { domain ->
            val p = context.getSharedPreferences(namespace(id, domain), 0)
            check(p.edit().clear().commit() && p.all.isEmpty())
        }
        journal.edit().remove(id.value).commit() && !journal.contains(id.value)
    }.getOrDefault(false) }
}
