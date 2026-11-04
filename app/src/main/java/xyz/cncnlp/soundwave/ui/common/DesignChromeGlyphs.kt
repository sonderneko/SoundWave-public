package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 设计稿 v2 `<symbol id="i-*">` 图标 1:1 转写（24x24 viewBox，.icon：stroke 1.8、圆头圆角、无填充）。
 * 用于底栏 / 迷你条 / 播放器等全局 chrome；以 Icon(tint=...) 着色。
 * 实心播放三角（.mini-play / .control.play 的 fill:currentColor;stroke:0）单独提供 [PlaySolid]。
 */
object DesignChromeGlyphs {
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
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    val Home by lazy { stroke("sw_i_home", "M3 10.5 12 3l9 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z", "M9 21v-7h6v7") }
    val Library by lazy { stroke("sw_i_library", "M4 4v16M9 4v16M14 5l5-1 2 15-5 1z") }
    val Search by lazy { stroke("sw_i_search", circle(11f, 11f, 7f), "m20 20-4-4") }
    val Settings by lazy {
        stroke(
            "sw_i_settings", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21h-4v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3.1 14H3v-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.5V3h4v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1h.1v4h-.1a1.7 1.7 0 0 0-1.5 1z"
        )
    }
    /** i-play 实心：与 [DesignGlyphs.PlayTriangle] 同一圆角三角路径（三角均为圆角）。 */
    val PlaySolid by lazy {
        ImageVector.Builder("sw_i_play", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(DesignGlyphs.PLAY_TRIANGLE_PATH), fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black),
                strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }.build()
    }
    /** i-pause：两条 1.8 竖线；实心按钮里用 [PauseBold]。 */
    val Pause by lazy { stroke("sw_i_pause", "M8 5v14M16 5v14") }
    val PauseBold by lazy { stroke("sw_i_pause_bold", "M8.5 5.5v13M15.5 5.5v13", width = 3.4f) }
    val Prev by lazy { stroke("sw_i_prev", "M6 5v14M18 6l-9 6 9 6z") }
    val Next by lazy { stroke("sw_i_next", "M18 5v14M6 6l9 6-9 6z") }
    val Shuffle by lazy { stroke("sw_i_shuffle", "M3 7h3c5 0 7 10 12 10h3M18 4l3 3-3 3M3 17h3c2.2 0 3.8-1.9 5.3-4M18 14l3 3-3 3") }
    val Repeat by lazy { stroke("sw_i_repeat", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4") }
    /** 单曲循环：i-repeat + 中心“1”。 */
    val RepeatOne by lazy { stroke("sw_i_repeat_one", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4", "M11 10.2l1.4-1v5.6") }
    val Queue by lazy {
        ImageVector.Builder("sw_i_queue", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes("M4 6h11M4 12h11M4 18h7"), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(addPathNodes("m17 15 4 3-4 3z"), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    val QueueMusic by lazy { stroke("sw_i_queue_music", "M4 6h10M4 11h10M4 16h7", "M17 8v8.2a2.3 2.3 0 1 1-1.7-2.2L21 13V6z") }
    val Lyrics by lazy { stroke("sw_i_lyrics", "M6 5h12M6 9h9M6 13h12M6 17h7") }
    val Airplay by lazy { stroke("sw_i_airplay", "M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "m8 21 4-4 4 4") }
    val Headphones by lazy { stroke("sw_i_headphones", "M4 15v-3a8 8 0 0 1 16 0v3", "M4 14h3v6H5a1 1 0 0 1-1-1zM20 14h-3v6h2a1 1 0 0 0 1-1z") }
    val Phone by lazy { stroke("sw_i_phone", "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M10 18h4") }
    val Volume by lazy { stroke("sw_i_volume", "M5 9v6h4l5 4V5L9 9zM18 9a4 4 0 0 1 0 6M20 6a8 8 0 0 1 0 12") }
    val Down by lazy { stroke("sw_i_down", "m6 9 6 6 6-6") }
    val Chevron by lazy { stroke("sw_i_chevron", "m9 18 6-6-6-6") }
    val Close by lazy { stroke("sw_i_x", "m6 6 12 12M18 6 6 18") }
    val Check by lazy { stroke("sw_i_check", "m5 12 4 4L19 6") }
    val Plus by lazy { stroke("sw_i_plus", "M12 5v14M5 12h14") }
    val Download by lazy { stroke("sw_i_download", "M12 3v12M7 10l5 5 5-5M4 21h16") }
    val Heart by lazy { stroke("sw_i_heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8z") }
    val MoreBold by lazy { solid("sw_i_more_bold", circle(5f, 12f, 2f), circle(12f, 12f, 2f), circle(19f, 12f, 2f)) }
    /** r657 第三轮：原型 icons.js `back`（详情页 .hdr 返回圆钮）。 */
    val Back by lazy { stroke("sw_i_back", "M19 12H5.5M11 5.5 4.5 12l6.5 6.5") }
}
