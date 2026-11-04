package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import xyz.cncnlp.soundwave.R

/**
 * 设计稿 v2 字体（Google Fonts，OFL）：Manrope 正文、Newsreader 编辑风标题、DM Mono 等宽标签。
 * 仅含拉丁子集；中文自动回落系统 CJK 字体。只用于按设计稿重做的首页/今日推荐等展示层。
 */
object DesignFonts {
    val Manrope: FontFamily = FontFamily(
        Font(R.font.sw_manrope_regular, FontWeight.Normal),
        Font(R.font.sw_manrope_medium, FontWeight.Medium),
        Font(R.font.sw_manrope_semibold, FontWeight.SemiBold),
        Font(R.font.sw_manrope_bold, FontWeight.Bold),
        Font(R.font.sw_manrope_extrabold, FontWeight.ExtraBold)
    )
    val Newsreader: FontFamily = FontFamily(
        Font(R.font.sw_newsreader_medium, FontWeight.Medium),
        Font(R.font.sw_newsreader_semibold, FontWeight.SemiBold),
        Font(R.font.sw_newsreader_medium_italic, FontWeight.Medium, FontStyle.Italic)
    )
    /**
     * r657 修复：中文衬线（Noto Serif SC Bold 子集，SIL OFL 1.1，© Adobe；GB2312 一级字 + 常用符号）。
     * 原型 --serif = Noto Serif CJK 700–900；大标题 / 页头 / 详情 hero / 我的喜欢一律用它。只打包 Bold 一个字重控制 APK 体积，
     * 其他字重请求就近落到 Bold；子集外的生僻字逐字回落系统字体。
     */
    val SerifCJK: FontFamily = FontFamily(
        Font(R.font.sw_notoserifsc_bold, FontWeight.Bold)
    )
    val DmMono: FontFamily = FontFamily(
        Font(R.font.sw_dmmono_regular, FontWeight.Normal),
        Font(R.font.sw_dmmono_medium, FontWeight.Medium)
    )
}
