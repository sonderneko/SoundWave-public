package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 音源平台单色线性标记（24x24 viewBox，stroke 1.8、圆头圆角，与设计稿 `.icon` 线性风格一致）。
 * 统一外形：圆角方框内放平台识别符号，单色 Icon(tint=...) 着色，浅/深色主题均可读。
 */
object PlatformLineGlyphs {
    private const val FRAME = "M7 3.5h10A3.5 3.5 0 0 1 20.5 7v10a3.5 3.5 0 0 1-3.5 3.5H7A3.5 3.5 0 0 1 3.5 17V7A3.5 3.5 0 0 1 7 3.5z"

    private fun glyph(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            (listOf(FRAME) + paths).forEach { d ->
                addPath(
                    pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    /** 网易云音乐：旋涡音符（开口圆弧 + 中心音杆）。 */
    val NetEase by lazy { glyph("sw_plat_wy", "M15.4 8.6a4.6 4.6 0 1 0 1.1 4.1", "M12.4 7.6v5.2a1.4 1.4 0 1 1-1.4-1.4") }
    /** QQ 音乐：八分音符。 */
    val QQ by lazy { glyph("sw_plat_tx", "M10.6 15.4V8.2l4.6-1.1v6.5", "M10.6 15.4a1.6 1.6 0 1 1-1.6-1.6h1.6M15.2 13.6a1.6 1.6 0 1 1-1.6-1.6h1.6") }
    /** 酷狗音乐：K。 */
    val Kugou by lazy { glyph("sw_plat_kg", "M9.4 7.6v8.8", "M15 7.6l-5.6 4.4 5.8 4.4") }
    /** 酷我音乐：声波 W。 */
    val Kuwo by lazy { glyph("sw_plat_kw", "M7.4 9l1.9 6 2.7-4.6 2.7 4.6 1.9-6") }
    /** 咪咕音乐：M。 */
    val Migu by lazy { glyph("sw_plat_mg", "M8 16V8.4l4 4.4 4-4.4V16") }
    /** 其它/聚合音源：音符圆。 */
    val Generic by lazy { glyph("sw_plat_any", "M12 8v6.2a1.8 1.8 0 1 1-1.8-1.8H12", "M12 8l3 1") }

    fun of(code: String): ImageVector = when (code.lowercase()) {
        "wy", "netease" -> NetEase
        "tx", "qq" -> QQ
        "kg", "kugou" -> Kugou
        "kw", "kuwo" -> Kuwo
        "mg", "migu" -> Migu
        else -> Generic
    }
}
