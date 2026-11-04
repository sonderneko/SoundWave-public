package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

/** 设计稿顶栏 .icon-btn：42dp 白色圆、1dp 线框、轻投影，21dp 单色线性图标。 */
@Composable
fun DesignCircleIconButton(icon: ImageVector, contentDescription: String?, iconSize: Dp = 21.dp, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        Modifier.size(42.dp)
            .pressScale(PressMotion.SCALE_ICON)
            .headerControlShadow(CircleShape) // r674：与头像 / 页顶圆钮同款外侧投影
            .clip(CircleShape)
            .background(frostedCardFill(MaterialTheme.colorScheme.surface)) // r657 磨砂背景下半透明
            .border(1.dp, if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(iconSize))
    }
}

/**
 * r658c U #3（用户 10-20）：详情页顶部左右圆钮（返回 / 更多 / 搜索）统一为透明磨砂圆钮，沉浸与默认模式同款，
 * 替换 r658c #5（4fa3aaa2）的不透明表面样式：
 *  - 页面上（原型 `.cbtn:not(.glass)`）：表面色 62% 半透明 + 14dp 背后模糊 + 1dp 线色描边，前景色图标；
 *  - 封面 / 图片上（[overMedia]，原型 `.cbtn.onimg`）：rgba(20,22,20,.28) + 14dp 背后模糊 + 白 22% 细描边，白色图标；
 *  - 两种都叠一层自上而下的白色微光（磨砂玻璃高光），按下缩放 .92；不画投影（半透明底下投影会透出一圈灰）。
 * [glass] 为页面局部模糊源（与按钮是兄弟节点时才可用）；为空或系统不支持时退为更不透明一点的半透明实色。
 */
@Composable
fun DesignHeaderCircleButton(
    icon: ImageVector, contentDescription: String?, iconSize: Dp = 20.dp,
    overMedia: Boolean = false,
    glass: LocalGlassState? = null,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val spec = frostedHeaderButtonSpec(overMedia, dark)
    val tint = if (overMedia) spec.tint else colors.surface.copy(alpha = spec.tint.alpha)
    val line = if (overMedia) spec.line else colors.outline.copy(alpha = colors.outline.alpha * spec.line.alpha)
    val ink = if (overMedia) Color.White else colors.onSurface
    Box(
        Modifier.size(42.dp)
            .pressScale(PressMotion.SCALE_ICON)
            // r674：页顶圆钮加外侧投影（只画在圆外，磨砂半透明内部不透灰圈）。
            .headerControlShadow(CircleShape)
            .frostedCircleBackdrop(glass, tint, spec.fallbackAlpha, CircleShape)
            .drawBehind {
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.White.copy(alpha = spec.sheen), 0.55f to Color.Transparent
                ))
            }
            .border(1.dp, line, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = ink, modifier = Modifier.size(iconSize))
    }
}

/** r658c U #3：磨砂圆钮参数（纯函数，单测覆盖）。tint/line 在页面模式下只取 alpha（颜色取主题 surface / outline）。 */
internal data class FrostedHeaderButtonSpec(val tint: Color, val fallbackAlpha: Float, val line: Color, val sheen: Float)

internal fun frostedHeaderButtonSpec(overMedia: Boolean, dark: Boolean): FrostedHeaderButtonSpec =
    if (overMedia) FrostedHeaderButtonSpec(
        tint = Color(0xFF141614).copy(alpha = 0.28f), fallbackAlpha = 0.38f,
        line = Color.White.copy(alpha = 0.22f), sheen = 0.10f
    ) else FrostedHeaderButtonSpec(
        tint = Color.White.copy(alpha = 0.62f), fallbackAlpha = 0.70f,
        line = Color.White.copy(alpha = 0.85f), sheen = if (dark) 0.05f else 0.22f
    )
