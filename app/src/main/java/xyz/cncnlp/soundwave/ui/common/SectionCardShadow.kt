package xyz.cncnlp.soundwave.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Image-bound Home cover shadow only. Never apply to a row, text region, or entry container. */
@Composable
internal fun Modifier.homeCoverShadow(shape: Shape): Modifier {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.25f
    // Draw outside the artwork boundary rather than relying on elevation-only RenderNode shadows.
    // This matches the existing cover shadow renderer and stays visible in both software and device rendering.
    return cssShadow(shape, Color.Black.copy(alpha = if (dark) ShadowScale.CoverAlphaDark else ShadowScale.CoverAlphaLight), ShadowScale.CoverBlur, ShadowScale.CoverOffsetY)
}

/** Whole editorial card boundary; separate from the existing image-bound shadow. */
@Composable
internal fun Modifier.editorialCardShadow(shape: Shape): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.25f
    return cssShadow(shape, Color.Black.copy(alpha = if (dark) 0.58f else 0.23f), 20.dp, 5.dp)
}

/**
 * r674（用户 10-22：所有顶部右上角按钮和头像加阴影）：页顶右上角圆钮 / 头像统一外侧投影。
 * 与 r673 搜索大卡同一做法——只画在形状外侧（excludeInterior），半透明磨砂圆钮内部不会透出灰圈；
 * 必须放在 clip 之前（drawBehind 在 clip 外层），阴影才不会被自身裁切。
 */
@Composable
internal fun Modifier.headerControlShadow(shape: Shape): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.25f
    // r675 后续（用户 10-22：再次调淡）：深/浅色 alpha 0.32/0.10 → 0.20/0.06，保持投影范围及外侧绘制不变。
    return cssShadow(shape, Color.Black.copy(alpha = if (dark) HeaderControlShadowDarkAlpha else HeaderControlShadowLightAlpha), 12.dp, 3.dp, excludeInterior = true)
}

internal const val HeaderControlShadowDarkAlpha = 0.20f
internal const val HeaderControlShadowLightAlpha = 0.06f

/** 回顶 / 定位当前歌曲的独立轻投影；不降低封面和整卡的既有阴影。放在 clip 之前。 */
@Composable
internal fun Modifier.floatingControlShadow(shape: Shape): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.25f
    // 原先复用 homeCoverShadow 的深/浅色 0.60/0.24；仅调淡，保留 18dp/5dp 投影范围。
    return cssShadow(shape, Color.Black.copy(alpha = if (dark) FloatingControlShadowDarkAlpha else FloatingControlShadowLightAlpha), 18.dp, 5.dp)
}

internal const val FloatingControlShadowDarkAlpha = 0.24f
internal const val FloatingControlShadowLightAlpha = 0.08f
