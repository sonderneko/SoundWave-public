package xyz.cncnlp.soundwave

import org.json.JSONArray
import org.json.JSONObject

/** Generic backup is a data projection, not a preference dump or a script source archive. */
internal object BackupPreferenceProjection {
    private val booleans = setOf(
        "auto_hide_dock", "swipe_hide_dock", "menu_icon_white", "lyric_bold",
        "lyric_show_translation", "lyric_show_romanization", "lyric_word_karaoke",
        "auto_play_on_start", "download_mt", "save_ext_lyric", "save_ext_cover",
        "allow_mix", "cross_search", "prefetch_next", "lyric_float_locked", "lyric_word_lift"
    )
    private val integers = mapOf(
        "dock_hide_seconds" to 1..30, "lyric_gap_dp" to 0..64,
        "lyric_font_sp" to 10..56, "lyric_immersive_sp" to 14..56,
        "lyric_immersive_gap_dp" to 6..64, "lyric_blur_dp" to 0..8,
        "lyric_offset_ms" to -30_000..30_000, "eq_preset" to 0..5,
        "prefetch_interval_sec" to 1..30, "prefetch_count" to 1..5,
        "lyric_float_width_dp" to 0..420, "lyric_float_lines" to 1..2,
        "lyric_float_color" to Int.MIN_VALUE..Int.MAX_VALUE,
        "lyric_float_dim" to Int.MIN_VALUE..Int.MAX_VALUE
    )
    private val floats = mapOf(
        "play_speed" to .5f..2f, "menu_opacity" to .2f..1f,
        "lyric_float_sp" to 12f..32f, "lyric_float_alpha" to 0f..1f,
        "lyric_float_text_alpha" to .35f..1f, "lyric_float_bg_alpha" to 0f..1f
    )
    private val strings = mapOf(
        "theme_mode" to setOf("light", "dark", "system"),
        "play_br" to setOf("128k", "192k", "320k", "flac", "flac24bit", "hires", "atmos", "master"),
        "down_br" to setOf("128k", "192k", "320k", "flac", "flac24bit", "hires", "atmos", "master"),
        "lyric_priority" to setOf("external", "embedded"),
        "global_font_family" to setOf("system", "serif", "sans", "mono"),
        "lyric_font_family" to setOf("system", "serif", "sans", "mono"),
        "bottom_nav_style" to setOf("default", "capsule"),
        "mini_player_style" to setOf("default", "capsule"),
        "download_name_tpl" to setOf("title_singer", "title_only", "album_title"),
        "player_layout" to setOf("standard", "default", "default_immersive_cover", "apple", "lyrics", "immersive_cover", "photo_lyric"),
        "detail_cover_style" to setOf("default", "standard", "immersive", "default2", "immersive_hero")
    )
    private val colors = setOf("lyric_highlight_color", "lyric_unhighlight_color")

    fun settings(input: JSONObject?, strict: Boolean): JSONObject = JSONObject().apply {
        input ?: return@apply
        input.keys().forEach { key ->
            val v = input.opt(key)
            val projected: Any? = when {
                key in booleans -> v.takeIf { it is Boolean }
                key == xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore.KEY_DURATION -> (v as? Number)?.let {
                    val d = it.toDouble()
                    it.toInt().takeIf { n -> d.isFinite() && d == n.toDouble() && n in xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore.OPTIONS_SECONDS }
                }
                key in integers -> (v as? Number)?.let {
                    val d = it.toDouble()
                    it.toInt().takeIf { n -> d.isFinite() && d == n.toDouble() && n in integers.getValue(key) }
                }
                key in floats -> (v as? Number)?.toFloat()?.takeIf { it.isFinite() && it in floats.getValue(key) }
                key in strings -> (v as? String)?.takeIf { it in strings.getValue(key) }
                key in colors -> (v as? String)?.takeIf { Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(it) }
                key == "cache_max_bytes" -> (v as? Number)?.let { n ->
                    n.toLong().takeIf { n.toDouble() == it.toDouble() && it in 0L..(64L * 1024 * 1024 * 1024) }
                }
                else -> return@forEach // Unknown fields, credentials, consent, URIs and service addresses stay local.
            }
            require(projected != null || !strict) { "备份设置格式无效: $key" }
            if (projected != null) put(key, projected)
        }
    }

    /** Only order/enabled configuration is portable. Code and free-form metadata may contain secrets. */
    fun scriptConfiguration(input: JSONObject?, strict: Boolean): JSONObject {
        if (input == null || !input.has("script_meta")) return JSONObject()
        val items = try { JSONArray(input.getString("script_meta")) } catch (e: Exception) {
            require(!strict) { "备份脚本配置格式无效" }; return JSONObject()
        }
        val out = JSONArray()
        require(items.length() <= 1000) { "备份脚本配置过多" }
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)
            val id = item?.opt("id") as? String
            val enabled = item?.opt("enabled") ?: true
            val valid = !id.isNullOrBlank() && id.length <= 256 && id.none { it.isISOControl() } && enabled is Boolean
            require(valid || !strict) { "备份脚本配置格式无效" }
            if (valid) out.put(JSONObject().put("id", id).put("enabled", enabled))
        }
        return JSONObject().put("script_meta", out.toString())
    }

    /** Merge configuration only into scripts already on this device; never delete code or invent empty scripts. */
    fun mergeScriptConfiguration(local: String, projected: JSONObject): String? {
        if (!projected.has("script_meta")) return null
        val existing = try { JSONArray(local) } catch (_: Exception) { return null }
        val byId = (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }.associateBy { it.optString("id") }
        val ordered = linkedMapOf<String, JSONObject>()
        val config = JSONArray(projected.getString("script_meta"))
        for (i in 0 until config.length()) {
            val item = config.getJSONObject(i)
            val id = item.getString("id")
            byId[id]?.let { ordered[id] = JSONObject(it.toString()).put("enabled", item.getBoolean("enabled")) }
        }
        byId.forEach { (id, value) -> if (id !in ordered) ordered[id] = value }
        return JSONArray(ordered.values.toList()).toString()
    }
}
