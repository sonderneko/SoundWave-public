package xyz.cncnlp.soundwave.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.PlayMode

/**
 * 1.0.655「默认」播放器与播放器 ⋯ 弹窗的线性图标（24×24、stroke 1.8、圆端点/圆角连接、无填充），
 * 与设计稿 v2 `<symbol id="i-*">` 同一风格。单色：Icon(tint = …) 着色。
 */
internal object DefaultPlayerGlyphs {
    private fun stroke(name: String, vararg paths: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private fun solid(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${r * 2} 0a$r $r 0 1 0 ${-r * 2} 0z"

    // ── 播放器 ──
    val CollapseDown by lazy { stroke("dp_down", "m6 9 6 6 6-6") }
    val More by lazy { solid("dp_more", circle(5f, 12f, 1.7f), circle(12f, 12f, 1.7f), circle(19f, 12f, 1.7f)) }
    val Heart by lazy { stroke("dp_heart", HEART) }
    val HeartFilled by lazy { solid("dp_heart_filled", HEART) }
    /** r657 修复：v5 原型 / 用户参考图 ◀◀ ▶▶——两个圆角实心三角（各宽约 11、重叠 1），不再是「竖线 + 三角」。 */
    val Prev by lazy { solid("dp_prev", "M22 6.6v10.8a1 1 0 0 1-1.56.83l-8.9-5.4a1 1 0 0 1 0-1.66l8.9-5.4A1 1 0 0 1 22 6.6z", "M12.4 6.6v10.8a1 1 0 0 1-1.56.83l-8.9-5.4a1 1 0 0 1 0-1.66l8.9-5.4A1 1 0 0 1 12.4 6.6z") }
    val Next by lazy { solid("dp_next", "M2 6.6v10.8a1 1 0 0 0 1.56.83l8.9-5.4a1 1 0 0 0 0-1.66l-8.9-5.4A1 1 0 0 0 2 6.6z", "M11.6 6.6v10.8a1 1 0 0 0 1.56.83l8.9-5.4a1 1 0 0 0 0-1.66l-8.9-5.4A1 1 0 0 0 11.6 6.6z") }
    val Play by lazy { solid("dp_play", "M8 5.6v12.8a1.3 1.3 0 0 0 1.98 1.1l10.1-6.4a1.3 1.3 0 0 0 0-2.2L9.98 4.5A1.3 1.3 0 0 0 8 5.6z") }
    val Pause by lazy { solid("dp_pause", "M7 4.8h2.6a1 1 0 0 1 1 1v12.4a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5.8a1 1 0 0 1 1-1z", "M14.4 4.8H17a1 1 0 0 1 1 1v12.4a1 1 0 0 1-1 1h-2.6a1 1 0 0 1-1-1V5.8a1 1 0 0 1 1-1z") }
    val Shuffle by lazy { stroke("dp_shuffle", "M3 7h3c5 0 7 10 12 10h3M18 4l3 3-3 3M3 17h3c2.2 0 3.8-1.9 5.2-4M14 8.6C15.2 7.6 16.5 7 18 7h3M18 14l3 3-3 3") }
    val Repeat by lazy { stroke("dp_repeat", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4") }
    val RepeatOne by lazy { stroke("dp_repeat_one", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4", "M11 10.2l1.4-.9v5.4") }
    val Crossfade by lazy { stroke("dp_crossfade", "M4 7h4c4 0 4 10 8 10h4", "m17 14 3 3-3 3", "M4 17h4c1.5 0 2.5-1.4 3.3-3.2M13 10c.8-1.7 1.8-3 3-3h4", "m17 4 3 3-3 3") }
    /**
     * 心动模式播放顺序图标（用户 10-18 重画）：描边心形 + 贯穿其中的心电脉冲线，一眼读作「心动」；
     * 与 Shuffle / Repeat 同 24 视口、同 1.8 描边、圆端圆角，24dp 下清晰。
     */
    val HeartLoop by lazy {
        stroke(
            "dp_heart_pulse",
            "M12 20.2c-.4 0-7.4-4.4-9-9.1C1.8 7.4 4.1 4.2 7.4 4.2c1.9 0 3.5 1 4.6 2.6 1.1-1.6 2.7-2.6 4.6-2.6 3.3 0 5.6 3.2 4.4 6.9-1.6 4.7-8.6 9.1-9 9.1z",
            "M6 12h2.9l1.5-2.7 2.3 5 1.5-2.3H18"
        )
    }
    /**
     * r657 第二轮：「关闭心动模式 / 恢复原播放顺序」——同一描边心形 + 脉冲线家族的「关闭」变体：
     * 心形与脉冲同 [HeartLoop]，加一条左上→右下斜杠（与 VolumeBarOff 等关闭态一致），不再用四芒星。
     */
    val HeartLoopOff by lazy {
        stroke(
            "dp_heart_pulse_off",
            "M12 20.2c-.4 0-7.4-4.4-9-9.1C1.8 7.4 4.1 4.2 7.4 4.2c1.9 0 3.5 1 4.6 2.6 1.1-1.6 2.7-2.6 4.6-2.6 3.3 0 5.6 3.2 4.4 6.9-1.6 4.7-8.6 9.1-9 9.1z",
            "M6 12h2.9l1.5-2.7 2.3 5 1.5-2.3H18",
            "M3.2 3.2l17.6 17.6"
        )
    }
    val Sparkle by lazy { stroke("dp_sparkle", "M12 3v3M12 18v3M3 12h3M18 12h3", "M12 7.5l1.3 3.2 3.2 1.3-3.2 1.3L12 16.5l-1.3-3.2L7.5 12l3.2-1.3z") }
    val Device by lazy { stroke("dp_device", "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", circle(12f, 14f, 3.2f), "M12 7h.01") }
    /** r657：播放设备 = AirPlay 式广播图标（两道同心弧 + 中心圆点 + 下方实心三角），按用户参考图。 */
    val AirPlay by lazy {
        ImageVector.Builder("dp_airplay", 24.dp, 24.dp, 24f, 24f).apply {
            listOf("M5.6 16.4A8.2 8.2 0 1 1 18.4 16.4", "M8.6 13.9A4.4 4.4 0 1 1 15.4 13.9").forEach { d ->
                addPath(pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            addPath(pathData = addPathNodes(circle(12f, 10.6f, 1.7f)), fill = SolidColor(Color.Black))
            addPath(pathData = addPathNodes("M11.1 14.3a1.1 1.1 0 0 1 1.8 0l3.9 5.6a.9.9 0 0 1-.74 1.4H7.94a.9.9 0 0 1-.74-1.4z"), fill = SolidColor(Color.Black))
        }.build()
    }
    val ChevronRight by lazy { stroke("dp_chev_right", "m9.5 6 6 6-6 6", width = 1.8f) }
    val Lyrics by lazy { stroke("dp_lyrics", "M4 6h11M4 10.5h16M4 15h9", "M17 15.5v4M15 17.5h4") }
    val Quote by lazy { stroke("dp_quote", "M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-9l-5 4v-4H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M8.5 9.5v2.5M12 9.5v2.5M15.5 9.5v2.5") }
    val Queue by lazy { stroke("dp_queue", "M4 6h12M4 11h12M4 16h7", "M17 13v7", "M17 20a2 2 0 1 1-2-2h2") }
    val Locate by lazy { stroke("dp_locate", circle(12f, 12f, 6.5f), circle(12f, 12f, 2f), "M12 2v3M12 19v3M2 12h3M19 12h3") }
    val Close by lazy { stroke("dp_close", "m7 7 10 10M17 7 7 17") }
    val Trash by lazy { stroke("dp_trash", "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3") }
    val DragHandle by lazy { stroke("dp_drag", "M8 9h8M8 12h8M8 15h8") } // r657 第三轮：原型 #i-drag 三横线
    val VolumeLow by lazy { stroke("dp_vol_low", "M5 9.5v5h3.5L13 18V6L8.5 9.5z", "M16 10a3 3 0 0 1 0 4") }
    val VolumeHigh by lazy { stroke("dp_vol_high", "M4 9.5v5h3.5L12 18V6L7.5 9.5z", "M15 10a3 3 0 0 1 0 4M17.6 7.4a7 7 0 0 1 0 9.2") }
    val Source by lazy { stroke("dp_source", "M4 5h7l2 2h7v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M9 13h6M12 10v6") }

    // ── 播放器 ⋯ 弹窗 ──
    val PlaybackLink by lazy { stroke("dp_playback_link", "M10 13a5 5 0 0 0 7 .2l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7-.2l-3 3a5 5 0 0 0 7 7l1.7-1.7") }
    val PlaylistAdd by lazy { stroke("dp_pl_add", "M4 6h12M4 11h12M4 16h7", "M17.5 14v6M14.5 17h6") }
    val PlayNext by lazy { stroke("dp_play_next", "M4 6h11M4 11h7M4 16h7", "M15 12.5v6.8l5-3.4z") }
    val Download by lazy { stroke("dp_download", "M12 4v11M7.5 10.5 12 15l4.5-4.5M5 19.5h14") }
    val Similar by lazy { stroke("dp_similar", circle(10.5f, 10.5f, 6.5f), "m20 20-4.6-4.6", "M7.6 10.6h1.2l1-2 1.4 4 1-2h1.2") }
    val Dislike by lazy { stroke("dp_dislike", circle(12f, 12f, 8.5f), "M6 6l12 12") }
    val Undo by lazy { stroke("dp_undo", "M9 7 4.5 11.5 9 16", "M5 11.5h9a5 5 0 0 1 0 10h-2") }
    val Timer by lazy { stroke("dp_timer", circle(12f, 13.5f, 7.5f), "M12 9.5v4l2.5 1.6M9.5 2.5h5M12 2.5V6") }
    val LyricsTune by lazy { stroke("dp_lyrics_tune", "M4 7h9M4 12h6M4 17h9", "M17 5v14M14.8 9h4.4M14.8 15h4.4") }
    val VolumeBar by lazy { stroke("dp_volume_bar", "M3.5 9.5v5H7l4.5 3.5v-12L7 9.5z", "M15 12h6", "M18 9v6") }
    val VolumeBarOff by lazy { stroke("dp_volume_bar_off", "M3.5 9.5v5H7l4.5 3.5v-12L7 9.5z", "M15.5 12h5") }
    val Style by lazy { stroke("dp_style", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 15l4.5-4.5 4 4 2.5-2.5L20 17", circle(15.5f, 8.5f, 1.5f)) }
    val Check by lazy { stroke("dp_check", "m5 12.5 4.5 4.5L19 7.5") }
    val Minus by lazy { stroke("dp_minus", "M6 12h12") }
    val Plus by lazy { stroke("dp_plus", "M12 6v12M6 12h12") }

    private const val HEART = "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21.3l7.8-7.8 1.1-1.1a5.5 5.5 0 0 0 0-7.8z"

    // ── PB-B5（第 2 波）：原 512×512 PNG 线稿 ic_player_action_* 沿原稿几何重绘的矢量版 ──
    /**
     * 沉浸歌词：对话气泡轮廓（左下尾巴）+ 两个实心引号逗点。沿原 PNG 几何：
     * 气泡体 x 1.7–22.3 / y 2.2–17、尾巴尖 (7.2,22.5)；逗点头圆心 (8.3,8.9)/(14.45,8.9) r2.55 + 下扫尾。
     */
    val ImmersiveLyrics by lazy {
        ImageVector.Builder("dp_immersive_lyrics", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M5 2.2h14a3.3 3.3 0 0 1 3.3 3.3v8.2a3.3 3.3 0 0 1-3.3 3.3H9.9L7.2 22.5a.5.5 0 0 1-.9-.4V17H5a3.3 3.3 0 0 1-3.3-3.3V5.5A3.3 3.3 0 0 1 5 2.2z"),
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
            listOf(
                "M5.75 8.9a2.55 2.55 0 1 0 5.1 0a2.55 2.55 0 1 0-5.1 0z",
                "M6.3 10.4Q6.15 12.8 7.7 13Q9.2 13.1 9.3 10.8Q8.4 11.2 7.4 10.95Q6.5 10.75 6.3 10.4Z",
                "M11.9 8.9a2.55 2.55 0 1 0 5.1 0a2.55 2.55 0 1 0-5.1 0z",
                "M12.45 10.4Q12.3 12.8 13.85 13Q15.35 13.1 15.45 10.8Q14.55 11.2 13.55 10.95Q12.65 10.75 12.45 10.4Z"
            ).forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        }.build()
    }
    /**
     * 播放队列：三条横线 + 右侧音符（实心圆环符头 + 竖茎）。沿原 PNG 几何：
     * 横线 y 4.7 / 12 / 19.3（首线至 x16.2、后两线至 x12.2）；符头圆心 (17.6,15.05) 外 r3.45 / 孔 r1.85；茎 x 19.7–22.1、顶 y3.75。
     */
    val PlayQueue by lazy {
        ImageVector.Builder("dp_play_queue", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M2.8 4.7h12.5M2.8 12h8.5M2.8 19.3h8.5"),
                fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
            addPath(pathData = addPathNodes("M19.7 16V4.7a.95.95 0 0 1 .95-.95h1.45V16z"), fill = SolidColor(Color.Black))
            addPath(
                pathData = addPathNodes("M14.15 15.05a3.45 3.45 0 1 0 6.9 0a3.45 3.45 0 1 0-6.9 0zM15.75 15.05a1.85 1.85 0 1 0 3.7 0a1.85 1.85 0 1 0-3.7 0z"),
                fill = SolidColor(Color.Black), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            )
        }.build()
    }
    /**
     * 播放设备：一对无线耳机（左右镜像的芽形头 + 茎 + 两枚传感槽）。沿原 PNG 几何：
     * 左芽 x 0.7–10.8 / y 2.9–13.5、茎 x 6.6–10.05 斜顶至 y21.2，右芽镜像；传感槽以 1.8 圆头短线描原黑槽走向。
     */
    val PlaybackDevice by lazy {
        stroke(
            "dp_playback_device",
            "M6 2.9c2.7-.2 4.7 1.9 4.8 4.5.1 3.1-2 5.8-4.7 6.1C3.4 12.9 1.2 11.3.7 9.2.2 6.9 2.9 3.2 6 2.9z",
            "M18 2.9c-2.7-.2-4.7 1.9-4.8 4.5-.1 3.1 2 5.8 4.7 6.1C20.6 12.9 22.8 11.3 23.3 9.2 23.8 6.9 21.1 3.2 18 2.9z",
            "M6.6 13.4l3.45-1.9v8.3a1.4 1.4 0 0 1-1.4 1.4H8a1.4 1.4 0 0 1-1.4-1.4z",
            "M17.4 13.4L13.95 11.5v8.3a1.4 1.4 0 0 0 1.4 1.4H16a1.4 1.4 0 0 0 1.4-1.4z",
            "M7.05 8.05l.9-1.2M2.35 11.15l.9-1.2M16.05 6.85l.9 1.2M20.75 9.95l.9 1.2"
        )
    }

    fun playMode(mode: PlayMode): ImageVector = when (mode) {
        PlayMode.SEQUENCE -> Repeat
        PlayMode.SHUFFLE -> Shuffle
        PlayMode.LOOP_ONE -> RepeatOne
        PlayMode.HEART -> HeartLoop
    }

    /** 播放器 ⋯ 弹窗：旧 WhiteGlyph 键 → 新线性图标（旧图标全部弃用）。 */
    fun forMenuKey(key: String): ImageVector = when (key) {
        "menu_playlist" -> PlaylistAdd
        "menu_next" -> PlayNext
        "menu_download" -> Download
        "menu_similar" -> Similar
        "menu_cast" -> Device
        "menu_link" -> PlaybackLink
        // r657 第二轮：心动模式入口与播放顺序同一心形脉冲图标；进行中（关闭 / 恢复原顺序）用其关闭变体。
        "sparkles_outline", "menu_heart" -> HeartLoop
        "sparkles_filled" -> HeartLoopOff
        "menu_hide" -> Dislike
        "menu_unhide" -> Undo
        "menu_timer" -> Timer
        "menu_transition", "menu_smart_transition" -> Crossfade
        "menu_lyrics" -> LyricsTune
        "volume_on" -> VolumeBar
        "volume_off" -> VolumeBarOff
        "menu_style" -> Style
        else -> More
    }
}
