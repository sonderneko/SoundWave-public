package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * r657 第二轮：数字滚动（原型 proto.js `roll()` + `.dgr .dg`）。
 * 每个数字位是一列 0–9，页面出现时从 0 滚到目标位、数值变化时从旧值滚到新值：620ms 弹簧曲线 cubic(.34,1.56,.64,1)；
 * 非数字字符（小数点、逗号）静止。列高 = 1em（行高 = 字号），按 tabular 数字排版。
 * 截图 / 单测（[MotionPolicy.decorativeLoopsFrozen]）与「简洁动效」下直接显示目标值。
 */
object NumberRollMotion {
    const val DURATION_MS = 620
    val Spring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
}

@Composable
fun RollingNumber(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    modifier: Modifier = Modifier
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val still = reduce || MotionPolicy.decorativeLoopsFrozen
    val cellDp = with(LocalDensity.current) { fontSize.toDp() }
    val style = TextStyle(
        color = color, fontSize = fontSize, lineHeight = TextUnit(fontSize.value, TextUnitType.Sp), fontFamily = fontFamily, fontWeight = fontWeight,
        letterSpacing = letterSpacing, fontFeatureSettings = "tnum",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    )
    Row(modifier, verticalAlignment = Alignment.Top) {
        text.forEachIndexed { i, ch ->
            if (ch.isDigit()) DigitColumn(i, ch - '0', style, cellDp, still)
            else Box(Modifier.height(cellDp)) { Text(ch.toString(), style = style) }
        }
    }
}

@Composable
private fun DigitColumn(position: Int, digit: Int, style: TextStyle, cell: androidx.compose.ui.unit.Dp, still: Boolean) {
    val anim = remember(position) { Animatable(if (still) digit.toFloat() else 0f) }
    LaunchedEffect(digit, still) {
        if (still) anim.snapTo(digit.toFloat())
        else anim.animateTo(digit.toFloat(), tween(NumberRollMotion.DURATION_MS, easing = NumberRollMotion.Spring))
    }
    val cellPx = with(LocalDensity.current) { cell.toPx() }
    Box(Modifier.height(cell).clipToBounds()) {
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = -anim.value * cellPx }) {
            for (d in 0..9) Box(Modifier.height(cell), contentAlignment = Alignment.Center) { Text(d.toString(), style = style) }
        }
    }
}
