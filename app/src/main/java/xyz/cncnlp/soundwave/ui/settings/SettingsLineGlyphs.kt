package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * r655 设置页专用线性图标：与设计稿 `#i-*` 同一笔法（24×24、stroke 1.8、圆端点/圆角连接、无填充），
 * 但每个设置项（主页分类行与各详情页子选项）各用一枚，互不重复。单色，由 Icon(tint) 着色。
 * 唯一性由 SettingsLineGlyphsTest 校验（按路径数据去重）。
 */
object SettingsLineGlyphs {
    private fun g(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private const val CIRCLE9 = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"

    /** 名称 → 原始路径；用于唯一性测试。 */
    val pathSpecs: Map<String, List<String>> = linkedMapOf(
        // —— 播放与歌词（主页） ——
        "autoplay" to listOf("M13 3.5 5.5 13.2h5.6l-.9 7.3 8.3-9.9h-5.7z"), // r657 第三轮：原型 #i-bolt
        "quality" to listOf("M4 15v-3a8 8 0 0 1 16 0v3", "M4 14h3v6H5a1 1 0 0 1-1-1zM20 14h-3v6h2a1 1 0 0 0 1-1z"),
        "prefetch" to listOf("M5.5 6.6v10.8c0 .8.9 1.3 1.6.9l8-5.4c.6-.4.6-1.3 0-1.7l-8-5.4c-.7-.5-1.6 0-1.6.8z", "M18.3 5.5v13"), // 原型 #i-next
        "sleep" to listOf("M4.8 13a7.2 7.2 0 1 0 14.4 0a7.2 7.2 0 1 0 -14.4 0z", "M12 9.2V13l2.6 1.6M9.8 3.5h4.4"), // 原型 #i-timer
        "lyrics" to listOf("M5 5.5h14v10.2H11l-4 3.3v-3.3H5z", "M8.5 9.3h7M8.5 12.3h4.5"), // 原型 #i-lyric
        // —— 外观 ——
        "theme" to listOf(CIRCLE9, "M12 3v18", "M12 7.5h4.5M12 12h6M12 16.5h4.5"),
        "player_layout" to listOf("M6 2.5h12a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z", "M9 9.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "M8 16.5h8M10 19h4"),
        "detail_cover" to listOf("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "m3.5 17 5-5 4 4 3-3 5 5", "M15 8.5h.01"),
        "dock" to listOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 15.5h18", "M7.5 18.3h.01M12 18.3h.01M16.5 18.3h.01"),
        // —— 音频 ——
        "speed" to listOf("M3.5 16a8.5 8.5 0 1 1 17 0", "m12 16 4-5.5", "M3.5 16h2M18.5 16h2M12 7.5v1.8"),
        "crossfade" to listOf("M4 7h4c4 0 4 10 8 10h4", "m17 14 3 3-3 3", "M4 17h4c1.5 0 2.5-1.4 3.3-3.2M13 10c.8-1.7 1.8-3 3-3h4", "m17 4 3 3-3 3"),
        "equalizer" to listOf("M6 20v-6M6 10V4M12 20v-9M12 7V4M18 20v-4M18 12V4", "M4 14h4M10 7h4M16 16h4"),
        "audio_focus" to listOf("M2.5 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0z", "M10.5 12a5.5 5.5 0 1 0 11 0a5.5 5.5 0 1 0 -11 0z"),
        // —— 音源与搜索 ——
        "smart_source" to listOf("m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4"),
        "scripts" to listOf("m8 7-5 5 5 5M16 7l5 5-5 5M13.5 4.5l-3 15"),
        "cross_search" to listOf("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", "m20 20-4-4"),
        // —— 下载 ——
        "download_settings" to listOf("M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3", "M12 3v11M7.5 9.5 12 14l4.5-4.5"),
        "open_downloads" to listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M12 10.5v6M9.5 14l2.5 2.5 2.5-2.5"),
        // —— 同步与数据 ——
        "account_sync" to listOf("M17.5 19H6a4 4 0 0 1-.7-7.9A7 7 0 0 1 19 9a5 5 0 0 1-1.5 10z", "M9 13.5a3 3 0 0 1 5.4-1.2M15 14.5a3 3 0 0 1-5.4 1.2", "M14.6 10.6v1.9h-1.9M9.4 17.4v-1.9h1.9"),
        "cache" to listOf("M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z", "M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6", "M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"),
        "stats" to listOf("M4 20h16", "M7 16.5V11M12 16.5V5M17 16.5v-8"),
        "history" to listOf("M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5M12 7v5l3 2"),
        "backup" to listOf("M3.5 4h17v4h-17z", "M5 8v10.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V8", "M10 12h4"),
        "data_flow" to listOf("M12 2.8 4.5 5.8v5.7c0 4.6 3.2 8.4 7.5 9.7 4.3-1.3 7.5-5.1 7.5-9.7V5.8z", "m9 12 2 2 4-4"),
        // —— 系统与实验 ——
        "crash" to listOf("M2.5 12h4l2.5-6.5 4.5 13 2.5-6.5h5.5"),
        "about" to listOf(CIRCLE9, "M12 11v6M12 7h.01"),

        // —— 详情页子选项 ——
        "stream_mobile" to listOf("M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M10 18h4", "M9.5 11.5v2M12 9.5v4M14.5 7.5v6"),
        "prefetch_toggle" to listOf("M12 3 3 7.5l9 4.5 9-4.5z", "m3 12 9 4.5 9-4.5", "m3 16.5 9 4.5 9-4.5"),
        "prefetch_count" to listOf("M10 6h10M10 12h10M10 18h10", "M4 6h1.5v4M4 10h3", "M4 14.5c0-.8.7-1.5 1.5-1.5S7 13.7 7 14.5c0 1.2-3 2.3-3 3.5h3"),
        "request_interval" to listOf("M6 2.5h12M6 21.5h12", "M7 2.5v3.2c0 1.8 1.3 3.2 2.8 4.3L12 12l2.2-2c1.5-1.1 2.8-2.5 2.8-4.3V2.5", "M7 21.5v-3.2c0-1.8 1.3-3.2 2.8-4.3L12 12l2.2 2c1.5 1.1 2.8 2.5 2.8 4.3v3.2"),
        "sleep_timer" to listOf("M5 13a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", "M12 9.5V13l2.3 1.6", "M3.5 5.5 6.5 3M20.5 5.5 17.5 3"),
        "translation" to listOf("M3 5h8M7 3v2c0 4-2 7-4.5 8.5M5 9c1.2 2 3 3.5 5 4.5", "m12 21 4.5-10 4.5 10M13.6 17.5h5.8"),
        "romanization" to listOf("m4 19 5-14 5 14M5.8 14h6.4", "M16 13.5c0-1.4 1-2.5 2.5-2.5s2.5 1.1 2.5 2.5V19M21 15.5c-.7-.4-1.5-.5-2.5-.5-1.6 0-2.5.8-2.5 2s.9 2 2.2 2c1.3 0 2.3-.7 2.8-1.7"),
        "distance_blur" to listOf("M12 3s6.5 7 6.5 11.5a6.5 6.5 0 0 1-13 0C5.5 10 12 3 12 3z", "M9 15a3 3 0 0 0 3 3"),
        "lyric_font_size" to listOf("m3 20 5.5-15L14 20M5 15h7", "M18 14V5M15 8l3-3 3 3"),
        "lyric_offset" to listOf("M12 4.5a7.5 7.5 0 1 0 0 15 7.5 7.5 0 1 0 0-15z", "M12 8v4h3.5", "M2.5 12h1.5M20 12h1.5"),
        "lyric_line_spacing" to listOf("M11 6h10M11 12h10M11 18h10", "M5 4v16M3 6l2-2 2 2M3 18l2 2 2-2"),
        "word_highlight" to listOf("m9 11-5.5 5.5V20h3.5L12.5 14.5", "m9 11 6-6 4 4-6 6z", "M14 20h7"),
        "word_lift" to listOf("M4 20h16", "M12 16V4M7.5 8.5 12 4l4.5 4.5"),
        "floating_lyrics" to listOf("M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M12.5 12.5h6v5h-6z"),
        "float_unlock" to listOf("M7 10h10a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M8 10V7a4 4 0 0 1 7.8-1.2", "M12 14.5v2"),
        "float_lines" to listOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M7 10h10M7 14h6"),
        "float_font" to listOf("M4 5V4h16v1M12 4v16M9 20h6"),
        "float_bg_opacity" to listOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 9h6v6H3M9 3v6h6V3M15 9h6v6h-6M9 15v6h6v-6"),
        "float_text_opacity" to listOf("M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"),
        "float_width" to listOf("M3 12h18", "m6 8.5-3.5 3.5L6 15.5M18 8.5l3.5 3.5-3.5 3.5", "M12 5v2M12 17v2"),
        "float_color" to listOf("M12 3a9 9 0 0 0 0 18c1.4 0 2-.9 2-1.8 0-1.4-1.2-1.7-1.2-2.8 0-1 .8-1.4 1.9-1.4H17a4 4 0 0 0 4-4C21 6.6 17 3 12 3z", "M7.5 11h.01M10 7h.01M15 7.5h.01"),
        "lyric_font_family" to listOf("M20 4c-5 .5-9.5 3.5-12 9l-1.5 4.5L11 16c5.5-2.5 8.5-7 9-12z", "M6.5 17.5 3.5 20.5M10 11l3 3"),
        "theme_light" to listOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"),
        "theme_night" to listOf("M19 14.5A8 8 0 1 1 9.5 5a6 6 0 0 0 9.5 9.5z", "M17 3v4M15 5h4"),
        "theme_auto" to listOf("M3 18h18", "M7 18a5 5 0 0 1 10 0", "M12 6.5V9M5.6 10.6l1.6 1.6M18.4 10.6l-1.6 1.6", "M9.5 21.5h5"),
        "global_font" to listOf("m2.5 18 4.5-12 4.5 12M4.2 14h5.6", "M14.5 12.5c0-1.4 1.1-2.5 2.8-2.5s2.7 1 2.7 2.5V18M20 14.5c-.8-.4-1.6-.5-2.7-.5-1.7 0-2.8.8-2.8 2.1s1 2.1 2.4 2.1c1.4 0 2.5-.7 3.1-1.7"),
        "reduce_motion" to listOf("M3 8h10a3 3 0 1 0-3-3", "M3 12h15a3 3 0 1 1-3 3", "M3 16h6"),
        "dock_style" to listOf("M7 8.5h10a3.5 3.5 0 0 1 0 7H7a3.5 3.5 0 0 1 0-7z", "M6.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "M12.5 12h3"),
        "swipe_dock" to listOf("m7 5 5 5 5-5M7 12l5 5 5-5", "M5 21h14"),
        "auto_hide_dock" to listOf("M9.9 5.2A9.6 9.6 0 0 1 12 5c6 0 9.5 7 9.5 7a16.7 16.7 0 0 1-2.4 3.3M6.5 6.6C3.9 8.3 2.5 12 2.5 12s3.5 7 9.5 7a9.4 9.4 0 0 0 5.4-1.7", "M9.9 9.9a3 3 0 0 0 4.2 4.2", "M3 3l18 18"),
        "speed_value" to listOf("M5 13.5a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", "M12 13.5 15 10.5", "M10 2.5h4M12 2.5v4"),
        "eq_preset" to listOf("M3 6h10M17 6h4M3 12h4M11 12h10M3 18h12M19 18h2", "M15 4v4M9 10v4M17 16v4"),
        "mix_playback" to listOf("M4 4v5a7 7 0 0 0 7 7h1M20 4v5a7 7 0 0 1-7 7", "M12 16v5", "M1.8 6.2 4 4l2.2 2.2M17.8 6.2 20 4l2.2 2.2"),
        "cross_search_toggle" to listOf(CIRCLE9, "M3.5 9h17M3.5 15h17", "M12 3c2.6 2.6 3.8 5.6 3.8 9s-1.2 6.4-3.8 9c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3z"),
        "credential_guard" to listOf("M7.5 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M11 12h10M18 12v3M21 12v2"),
        "frosted_bg" to listOf("M5 4h14a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 19 20H5a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 5 4z", "M8 8h.01M12 8h.01M16 8h.01M8 12h.01M12 12h.01M16 12h.01M8 16h.01M12 16h.01M16 16h.01"),
        "frosted_tint" to listOf("M12 3.2s6 6.8 6 11a6 6 0 0 1-12 0c0-4.2 6-11 6-11z", "M12 8.5v9.7"),
        "download_quality" to listOf("M12 14a5.5 5.5 0 1 0 0-11 5.5 5.5 0 0 0 0 11z", "M8.5 12.8 7 21l5-3 5 3-1.5-8.2"),
        "multi_thread" to listOf("M6 3v12", "M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z", "M18 9a9 9 0 0 1-9 9"),
        "save_cover" to listOf("M21 12V6a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h7", "m3 16 5-5 5 5", "M18 14v7M15 18l3 3 3-3"),
        "save_lyrics" to listOf("M14 2.5H6.5a2 2 0 0 0-2 2v15a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V8z", "M14 2.5V8h5.5", "M8 13h8M8 17h5"),
        "download_filename" to listOf("M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z", "M7.5 7.5h.01"),
        "download_tasks" to listOf("M8 6h13M8 12h13M8 18h13", "m3 6 1 1 2-2M3 12l1 1 2-2M3 18l1 1 2-2")
    )

    private val cache = HashMap<String, ImageVector>()

    fun of(key: String): ImageVector = cache.getOrPut(key) {
        val spec = pathSpecs[key] ?: pathSpecs.getValue("about")
        g("sw_set_$key", *spec.toTypedArray())
    }
}
