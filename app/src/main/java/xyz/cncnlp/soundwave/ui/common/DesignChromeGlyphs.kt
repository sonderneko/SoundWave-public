package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 全局 chrome 的专属 glyph（24x24 viewBox，stroke 1.8、圆头圆角）。
 *
 * PB-B2（第二批后合并批第 2 波）同义去重后，本对象只保留与 [DesignLineGlyphs] 不同义的
 * 专属成员：实心播放三角 [PlaySolid]、实心按钮粗暂停 [PauseBold]、单曲循环 [RepeatOne]
 * 与详情页返回 [Back]。与 DesignLineGlyphs 同名同义的成员（Home/Library/Search/Settings/
 * Pause/Prev/Next/Shuffle/Repeat/Queue/QueueMusic/Lyrics/Headphones/Phone/Volume/Down/
 * Chevron/Check/Plus/Download/Heart/MoreBold，以及 Close≡X）已全部删除，引用点改指
 * DesignLineGlyphs；Airplay 两版（与 DefaultPlayerGlyphs.AirPlay 三版择一）随 PB-B3 删除。
 * 以 Icon(tint=...) 着色。
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

    /** i-play 实心：与原 DesignGlyphs.PlayTriangle 同一圆角三角路径（PB-B2 起以本定义为唯一存本，PlayTriangle 已删）。 */
    val PlaySolid by lazy {
        ImageVector.Builder("sw_i_play", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(DesignGlyphs.PLAY_TRIANGLE_PATH), fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black),
                strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }.build()
    }
    /** i-pause 实心按钮变体：两条 3.4 粗竖线（IconSpec 明文立档例外）；线性暂停见 DesignLineGlyphs.Pause。 */
    val PauseBold by lazy { stroke("sw_i_pause_bold", "M8.5 5.5v13M15.5 5.5v13", width = 3.4f) }
    /** 单曲循环：i-repeat + 中心“1”。 */
    val RepeatOne by lazy { stroke("sw_i_repeat_one", "m17 2 3 3-3 3M3 11V9a4 4 0 0 1 4-4h13M7 22l-3-3 3-3M21 13v2a4 4 0 0 1-4 4H4", "M11 10.2l1.4-1v5.6") }
    /** r657 第三轮：原型 icons.js `back`（详情页 .hdr 返回圆钮）。 */
    val Back by lazy { stroke("sw_i_back", "M19 12H5.5M11 5.5 4.5 12l6.5 6.5") }
}
