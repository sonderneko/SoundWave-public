package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SW-12-14 / 审计 B.6 + B.7：字号、间距、图标尺寸的 token 收敛出口（P4 起新建）。
 *
 * 使用规则：
 * - **新代码优先引用本文件**，不要再写裸 fontSize / 间距 / 图标尺寸字面量。
 * - 带「用户确认 / 照原型 / frozen」注释的**冻结数值处不迁**：那些字面量是用户已确认的外观，
 *   迁移时只许「值精确相等」替换，.5sp 半档就近归档必须逐处登记（见战役台账）。
 * - 本文件只提供数值（字号/行高、间距、尺寸），**不强制字体族与字重**：字体族沿用各调用点现状
 *   （Manrope 正文 / DmMono 眉题与数字 / Newsreader 大标题），字重按 B.7 规则在调用点保留。
 */

/**
 * 字号 9 档（B.7）。每档以 [TextStyle] 形式给出 fontSize + lineHeight 一对值：
 * 调用点可用 `style = TypeScale.Body` 整体引用，或只取 `TypeScale.Body.fontSize` 做精确等值迁移
 * （迁移时行高若原文未写则不补、已写且与本档不同则保留原文，避免改动布局）。
 */
object TypeScale {
    /** 31/38：页头大标题（设置页 31sp 衬线大标题）。 */
    val Display = TextStyle(fontSize = 31.sp, lineHeight = 38.sp)
    /** 24/30：抽屉标题、详情标题。 */
    val Title = TextStyle(fontSize = 24.sp, lineHeight = 30.sp)
    /** 18/24：模块标题。 */
    val Headline = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)
    /** 16/21：设置行标题。 */
    val BodyStrong = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    /** 15/21：列表主标题、抽屉行。 */
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    /** 13/17：副标题、说明、值。 */
    val BodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
    /** 12/16：次要说明、时间。 */
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    /** 11/14：眉题、标签。 */
    val Micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
    /** 9/10：角标专用（≤9sp 只留给角标）。 */
    val Badge = TextStyle(fontSize = 9.sp, lineHeight = 10.sp)
}

/**
 * 阴影 / 透明度 / 高程档（SW-08-01，G 波补）：收编现有共享阴影 helper 的实际取值，新代码与共享
 * helper 优先引用；一次性场景阴影（播放器光晕、舞台辉光等）不强迁，保留场景字面量。
 */
object ShadowScale {
    /** 封面阴影（homeCoverShadow）：模糊 18 / 偏移 5；不透明度浅 0.24、深 0.60。 */
    val CoverBlur: Dp = 18.dp
    val CoverOffsetY: Dp = 5.dp
    const val CoverAlphaLight = 0.24f
    const val CoverAlphaDark = 0.60f
    /** 迷你条卡阴影（scrim 16%，模糊 34 / 偏移 12）与条内封面小阴影（scrim 18%，模糊 12 / 偏移 4）。 */
    const val DockCardAlpha = 0.16f
    val DockCardBlur: Dp = 34.dp
    val DockCardOffsetY: Dp = 12.dp
    const val DockCoverAlpha = 0.18f
    val DockCoverBlur: Dp = 12.dp
    val DockCoverOffsetY: Dp = 4.dp
    /** 坞面胶囊/圆钮轻阴影（onSurface 8%，模糊 24 / 偏移 8）。 */
    const val DockPillAlpha = 0.08f
    val DockPillBlur: Dp = 24.dp
    val DockPillOffsetY: Dp = 8.dp
}

/**
 * 间距（B.6）：4 的倍数栅格 6 档 + 2 个原型例外。
 * P4 本批**只建不强迁**存量 padding/Spacer（避免布局数值争议），新代码优先引用。
 */
object SpaceScale {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    /** 原型页边距例外（14dp 已是原型确认值）。 */
    val PageGutter: Dp = 14.dp
    /** 抽屉页边距例外（B.4 Sheet Padding 左右 26dp）。 */
    val SheetGutter: Dp = 26.dp
}

/**
 * 图标尺寸档（B.7 5 档 + 仓内实际高频值）：全仓 `.size()` 统计以 20dp 最高频、18dp 次之，
 * 21dp 是设置行/抽屉行的既定标准（B.4/B.5），24dp 为工具栏档。
 * 已确认的例外（播放器 34/50 等）不在此收敛，保留原字面量。
 */
object IconSize {
    /** 12：角标内小图标。 */
    val Badge: Dp = 12.dp
    /** 16：行内说明图标。 */
    val Inline: Dp = 16.dp
    /** 18：紧凑列表图标（仓内第二高频）。 */
    val Compact: Dp = 18.dp
    /** 20：列表与抽屉标准图标（仓内最高频）。 */
    val List: Dp = 20.dp
    /** 21：设置行 / 抽屉行图标（已确认标准值）。 */
    val Settings: Dp = 21.dp
    /** 24：工具栏图标。 */
    val Toolbar: Dp = 24.dp
    /** 28：播放器次级图标。 */
    val Large: Dp = 28.dp
}

/**
 * 图标绘制规范（PB-B1，第二批后合并批第 0 波立档；验收尺由 scripts/icon-stroke-guard.sh 钉住）。
 *
 * 适用范围：24×24 口径的自绘 glyph——Kotlin ImageVector 定义与 viewport 24×24 的 vector
 * drawable。108 画布的品牌标记（启动器图标 / 启动图）是另一口径，不在本规范内。
 *
 * 规范正文（唯一基线档）：
 * - viewport 唯一 24×24（defaultWidth/Height 24.dp、viewport 24f×24f；drawable 同值）。
 * - stroke 唯一基线档 = 1.8，strokeLineCap / strokeLineJoin 一律 Round。
 * - 「更多」圆点统一 r1.9、圆心 x = 5 / 12 / 19（y = 12）。
 * - 心形标准跨度 18（路径横向跨度以 18 为准心排比对）。
 *
 * 例外档（明文立档，仅限下列定义点；新增例外须先改本节再改守卫名单）：
 * - DesignChromeGlyphs.PauseBold stroke 3.4：实心按钮变体——播放主按钮的粗暂停，
 *   以描边加粗模拟实心竖条，与同排实心三角等重。
 * - DesignChromeGlyphs.PlaySolid stroke 1.0：实心三角变体——填充三角外加 1.0 描边
 *   只为圆角连接补形，描边本身不承担线宽语义。
 *
 * 存量归一（第 2 波 PB-B3 已落地，守卫棘轮名单已清空）：DesignGlyphs.PlayTriangleSoft
 * 1.6→1.8、sw_scan / sw_pswap 1.75→1.8、DefaultPlayerGlyphs.AirPlay 1.9→1.8、
 * DefaultPlayerGlyphs.ChevronRight 2.0→1.8、ic_stat_wave 2.02 / 1.28 双宽统一 1.8；
 * DesignSheetGlyphs.Heart/HeartFilled 按原几何等比放大，路径横向跨度归本规范标准 18。
 * 例外档只保留上述 PauseBold 3.4 / PlaySolid 1.0 两项。
 */
object IconSpec {
    /** viewport 基线（dp / viewport 单位同值）。 */
    val Viewport: Dp = 24.dp
    /** stroke 唯一基线档。 */
    const val StrokeWidth: Float = 1.8f
    /** 「更多」圆点统一半径与圆心 x（y = 12）。 */
    const val MoreDotRadius: Float = 1.9f
    const val MoreDotCenterStart: Float = 5f
    const val MoreDotCenterMid: Float = 12f
    const val MoreDotCenterEnd: Float = 19f
    /** 心形标准跨度。 */
    const val HeartSpan: Float = 18f
}
