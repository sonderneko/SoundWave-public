package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Presentation-only glyphs taken 1:1 from the SoundWave v2 design draft (24x24 viewBox).
 * Tint them with Icon(tint = ...); fills are drawn in black and replaced by the tint.
 */
object DesignGlyphs {
    /** 全局统一的圆角播放三角（三个角均为圆角，非尖角）。 */
    const val PLAY_TRIANGLE_PATH = "M9.81 5.96L17.39 10.64Q19.6 12 17.39 13.36L9.81 18.04Q7.6 19.4 7.6 16.8L7.6 7.2Q7.6 4.6 9.81 5.96Z"

    // PB-B2：第 2 波同义去重——原 PlayTriangle（本路径的纯填充版）已删，全仓唯一存本为
    // DesignChromeGlyphs.PlaySolid（同路径 + 1.0 描边补角，IconSpec 立档例外），引用点已改指。

    /** 无底色的柔和圆角三角（三个角均带较大弧度，用于封面右下角悬浮播放）。 */
    const val PLAY_TRIANGLE_SOFT_PATH = "M9.4 5.3Q7.2 4 7.2 6.6L7.2 17.4Q7.2 20 9.4 18.7L18.1 13.5Q20.4 12 18.1 10.5Z"

    val PlayTriangleSoft: ImageVector by lazy {
        ImageVector.Builder("sw_play_triangle_soft", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(PLAY_TRIANGLE_SOFT_PATH),
                fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round
            )
        }.build()
    }

    private const val HEART_PATH =
        "M12 20.2c-.3 0-.6-.1-.8-.3C8.6 17.8 3 13.6 3 9.1 3 6.3 5.1 4.2 7.7 4.2c1.8 0 3.3.9 4.3 2.4 1-1.5 2.5-2.4 4.3-2.4C18.9 4.2 21 6.3 21 9.1c0 4.5-5.6 8.7-8.2 10.8-.2.2-.5.3-.8.3z"

    /** Redrawn rounded heart outline (stroke 1.8). */
    val HeartOutline: ImageVector by lazy {
        ImageVector.Builder("sw_heart_outline", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(HEART_PATH),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round
            )
        }.build()
    }

    /** Same heart, filled + stroked (favorited state). */
    val HeartFilled: ImageVector by lazy {
        ImageVector.Builder("sw_heart_filled", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(HEART_PATH),
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round
            )
        }.build()
    }

    /** Three round dots (more). */
    val MoreDots: ImageVector by lazy {
        ImageVector.Builder("sw_more_dots", 24.dp, 24.dp, 24f, 24f).apply {
            listOf(5.5f, 12f, 18.5f).forEach { cx ->
                addPath(
                    pathData = addPathNodes("M${cx - 1.9f} 12a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0z"),
                    fill = SolidColor(Color.Black)
                )
            }
        }.build()
    }

    /** Favorite red used by the design (#ff5a5f). */
    val FavoriteRed = Color(0xFFFF5A5F)
}
