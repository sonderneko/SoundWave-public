package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 设计稿 v2 `<symbol id="i-*">` 线性图标 1:1 移植（24×24，stroke 1.8，圆端点/圆角连接，无填充）。
 * 单色：用 Icon(tint = …) 着色。由 soundwave-design-v2.html 自动生成。
 */
object DesignLineGlyphs {
    private fun line(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    val Home: ImageVector by lazy { line("sw_i-home", "M3 10.5 12 3l9 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z", "M9 21v-7h6v7") }
    val Library: ImageVector by lazy { line("sw_i-library", "M4 4v16M9 4v16M14 5l5-1 2 15-5 1z") }
    val Search: ImageVector by lazy { line("sw_i-search", "M4.0 11.0a7.0 7.0 0 1 0 14.0 0a7.0 7.0 0 1 0 -14.0 0z", "m20 20-4-4") }
    val Disc: ImageVector by lazy { line("sw_i-disc", "M3.0 12.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0z", "M10.0 12.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", "M6.2 8.5A7 7 0 0 1 9 5.6M15.2 18.2a7 7 0 0 0 2.9-2.6") }
    val Settings: ImageVector by lazy { line("sw_i-settings", "M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0z", "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21h-4v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3.1 14H3v-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.5V3h4v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1h.1v4h-.1a1.7 1.7 0 0 0-1.5 1z") }
    val User: ImageVector by lazy { line("sw_i-user", "M8.0 8.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0z", "M4.5 21a7.5 7.5 0 0 1 15 0") }
    val Lock: ImageVector by lazy { line("sw_i-lock", "M7.0 10.0h10.0a2.0 2.0 0 0 1 2.0 2.0v7.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-7.0a2.0 2.0 0 0 1 2.0 -2.0z", "M8 10V7a4 4 0 0 1 8 0v3M12 14v3") }
    val Send: ImageVector by lazy { line("sw_i-send", "M22 2 10.8 13.2", "m22 2-7.1 20-4.1-8.8L2 9.1z") }
    val Bell: ImageVector by lazy { line("sw_i-bell", "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4") }
    val Play: ImageVector by lazy { line("sw_i-play", DesignGlyphs.PLAY_TRIANGLE_PATH) }
    val Pause: ImageVector by lazy { line("sw_i-pause", "M8 5v14M16 5v14") }
    val Prev: ImageVector by lazy { line("sw_i-prev", "M6 5v14M18 6l-9 6 9 6z") }
    val Next: ImageVector by lazy { line("sw_i-next", "M18 5v14M6 6l9 6-9 6z") }
    val Shuffle: ImageVector by lazy { line("sw_i-shuffle", "M3 7h3c5 0 7 10 12 10h3M18 4l3 3-3 3M3 17h3c2.2 0 3.8-1.9 5.3-4M18 14l3 3-3 3") }
    val Repeat: ImageVector by lazy { line("sw_i-repeat", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4") }
    val More: ImageVector by lazy { line("sw_i-more", "M4.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", "M11.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", "M18.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z", filled = true) }
    val Queue: ImageVector by lazy { line("sw_i-queue", "M4 6h11M4 12h11M4 18h7", "m17 15 4 3-4 3z") }
    val X: ImageVector by lazy { line("sw_i-x", "m6 6 12 12M18 6 6 18") }
    val Check: ImageVector by lazy { line("sw_i-check", "m5 12 4 4L19 6") }
    val Plus: ImageVector by lazy { line("sw_i-plus", "M12 5v14M5 12h14") }
    val Heart: ImageVector by lazy { line("sw_i-heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8z") }
    val Bookmark: ImageVector by lazy { line("sw_i-bookmark", "M6 4.5A1.5 1.5 0 0 1 7.5 3h9A1.5 1.5 0 0 1 18 4.5V21l-6-4-6 4z") }
    val Chevron: ImageVector by lazy { line("sw_i-chevron", "m9 18 6-6-6-6") }
    // r658b #9：播放设备「耳机/外接设备」与系统蓝牙路由用蓝牙线性图标（同 1.6 线宽）。
    val Bluetooth: ImageVector by lazy { line("sw_i-bluetooth", "M7 7l10 10-5 5V2l5 5L7 17") }
    val Headphones: ImageVector by lazy { line("sw_i-headphones", "M4 15v-3a8 8 0 0 1 16 0v3", "M4 14h3v6H5a1 1 0 0 1-1-1zM20 14h-3v6h2a1 1 0 0 0 1-1z") }
    val Cloud: ImageVector by lazy { line("sw_i-cloud", "M17.5 19H6a4 4 0 0 1-.7-7.9A7 7 0 0 1 19 9a5 5 0 0 1-1.5 10z", "M12 11v6M9.5 13.5 12 11l2.5 2.5") }
    val Moon: ImageVector by lazy { line("sw_i-moon", "M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10z") }
    val Volume: ImageVector by lazy { line("sw_i-volume", "M5 9v6h4l5 4V5L9 9zM18 9a4 4 0 0 1 0 6M20 6a8 8 0 0 1 0 12") }
    val Download: ImageVector by lazy { line("sw_i-download", "M12 3v12M7 10l5 5 5-5M4 21h16") }
    val Info: ImageVector by lazy { line("sw_i-info", "M3.0 12.0a9.0 9.0 0 1 0 18.0 0a9.0 9.0 0 1 0 -18.0 0z", "M12 11v6M12 7h.01") }
    val Phone: ImageVector by lazy { line("sw_i-phone", "M8.0 2.0h8.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-8.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0z", "M10 18h4") }
    val Down: ImageVector by lazy { line("sw_i-down", "m6 9 6 6 6-6") }
    val Lyrics: ImageVector by lazy { line("sw_i-lyrics", "M6 5h12M6 9h9M6 13h12M6 17h7") }
    val Comment: ImageVector by lazy { line("sw_i-comment", "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-8l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8.5 9.2h2.4v2.2c0 1.6-.7 2.5-2.1 3M14 9.2h2.4v2.2c0 1.6-.7 2.5-2.1 3") }
    val DeviceBuds: ImageVector by lazy { line("sw_i-device-buds", "M7.5 4.5c-2.3 0-4 1.7-4 4 0 2.1 1.4 3.6 3.3 3.9v6.1a1.5 1.5 0 0 0 3 0v-9c0-2.8-.8-5-2.3-5zM16.5 4.5c2.3 0 4 1.7 4 4 0 2.1-1.4 3.6-3.3 3.9v6.1a1.5 1.5 0 0 1-3 0v-9c0-2.8.8-5 2.3-5z", "M5.4 7.5c.8.8 1.6 1.1 2.6 1.2M18.6 7.5c-.8.8-1.6 1.1-2.6 1.2") }
    val QueueMusic: ImageVector by lazy { line("sw_i-queue-music", "M4 6h10M4 11h10M4 16h7", "M17 8v8.2a2.3 2.3 0 1 1-1.7-2.2L21 13V6z") }
    val History: ImageVector by lazy { line("sw_i-history", "M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5M12 7v5l3 2") }
    val Up: ImageVector by lazy { line("sw_i-up", "m6 11 6-6 6 6M12 5v14") }
    val Locate: ImageVector by lazy { line("sw_i-locate", "M5.0 12.0a7.0 7.0 0 1 0 14.0 0a7.0 7.0 0 1 0 -14.0 0z", "M10.0 12.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", "M12 2v3M12 19v3M2 12h3M19 12h3") }
    val Note: ImageVector by lazy { line("sw_i-note", "M9 18V5l11-2v13", "M4.0 18.0a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0z", "M15.0 16.0a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0z") }
    val Sort: ImageVector by lazy { line("sw_i-sort", "M4 6h10M4 12h7M4 18h4", "M18 5v14M15 16l3 3 3-3") }
    val Select: ImageVector by lazy { line("sw_i-select", "M7.5 3.5h9.0a4.0 4.0 0 0 1 4.0 4.0v9.0a4.0 4.0 0 0 1 -4.0 4.0h-9.0a4.0 4.0 0 0 1 -4.0 -4.0v-9.0a4.0 4.0 0 0 1 4.0 -4.0z", "m8 12 3 3 5-6") }
    val PlaylistAdd: ImageVector by lazy { line("sw_i-playlist-add", "M4 6h11M4 11h11M4 16h6", "M17 13v6M14 16l3 3 3-3") }
    val MoreBold: ImageVector by lazy { line("sw_i-more-bold", "M3.0 12.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", "M10.0 12.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", "M17.0 12.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0z", filled = true) }
}
