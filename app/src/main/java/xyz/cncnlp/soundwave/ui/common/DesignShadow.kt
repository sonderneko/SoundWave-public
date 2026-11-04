package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import androidx.compose.ui.unit.dp

/**
 * CSS `box-shadow: x y blur spread color` 的近似实现（设计稿阴影 1:1 换算）。
 * Android setShadowLayer 的 radius 约等于 CSS blur 的一半。仅绘制阴影，不绘制填充。
 */
fun Modifier.cssShadow(
    shape: Shape,
    color: Color,
    blur: Dp,
    offsetY: Dp = 0.dp,
    offsetX: Dp = 0.dp,
    spread: Dp = 0.dp,
    /**
     * r673：true 时只在形状外侧画阴影（先 clipOut 自身轮廓）。用于半透明 / 磨砂填充的卡片：
     * 否则阴影会透过卡片底色露出来，在卡片边缘形成一圈深色「描边」。
     */
    excludeInterior: Boolean = false
): Modifier = drawBehind {
    val spreadPx = spread.toPx()
    val interior = if (excludeInterior) androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) } else null
    val outline = shape.createOutline(
        androidx.compose.ui.geometry.Size(size.width + spreadPx * 2, size.height + spreadPx * 2),
        layoutDirection, this
    )
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fp = paint.asFrameworkPaint()
        fp.isAntiAlias = true
        fp.color = color.copy(alpha = 0f).toArgb()
        fp.setShadowLayer((blur.toPx() / 2f).coerceAtLeast(0.5f), offsetX.toPx(), offsetY.toPx(), color.toArgb())
        canvas.save()
        if (interior != null) canvas.clipPath(interior, androidx.compose.ui.graphics.ClipOp.Difference)
        canvas.translate(-spreadPx, -spreadPx)
        when (outline) {
            is Outline.Rectangle -> canvas.drawRect(outline.rect, paint)
            is Outline.Rounded -> {
                val r = outline.roundRect
                canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
            }
            is Outline.Generic -> canvas.drawPath(outline.path, paint)
        }
        canvas.restore()
    }
}

/**
 * ACCEPT6 dialogfrost：对话框输入件聚焦外晕（v8 稿面：珊瑚描边 + 外圈柔光）——描边由各字段
 * 自身的聚焦色承担，本修饰只加晕：聚焦时 Accent @0.30、blur 14dp、spread 2dp 向外柔散，
 * 180ms 淡入/淡出；未聚焦且动画落定后零绘制、零修饰（返回原链）。
 * 必须排在字段的 background/border 之前（drawBehind 垫底绘制，光晕才在填充之下向外散）。
 */
@Composable
fun Modifier.sheetFocusGlow(focused: Boolean, shape: Shape): Modifier {
    val t by animateFloatAsState(if (focused) 1f else 0f, animationSpec = tween(180), label = "sheetFocusGlow")
    if (!focused && t <= 0.001f) return this
    return this.cssShadow(
        shape = shape,
        color = SoundwaveDesignPalette.Accent.copy(alpha = 0.30f * t),
        blur = 14.dp,
        spread = 2.dp,
        excludeInterior = true
    )
}
