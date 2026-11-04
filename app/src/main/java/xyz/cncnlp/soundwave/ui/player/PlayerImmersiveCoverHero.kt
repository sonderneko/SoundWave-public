@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.IMMERSIVE_HERO_FADE_FRACTION
import xyz.cncnlp.soundwave.immersivePlayerHeroArtworkAlpha

/** Bounded immersive-cover hero; playback mode and navigation remain owned by FullPlayer. */
@Composable
internal fun PlayerImmersiveCoverHero(
    modifier: Modifier,
    coverPainter: Painter,
    coverDescription: String,
    /** r674：状态栏压暗渐变的透明度（Apple 随页面过渡渐变，避免切换时顶部突变）；绘制阶段读取。 */
    statusScrimAlpha: () -> Float = { 1f },
    /** r674：渐隐中段衔接色（下方背景近似色），Color.Unspecified 时不染色。 */
    fadeBridgeColor: () -> Color = { Color.Unspecified },
    onClick: () -> Unit
) {
    Box(
        modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        )
    ) {
        // The full-screen layer already supplies this song's blurred art; do not blur a
        // second hero-bounded crop, whose blur escapes the fade mask and is clipped flat.
        Image(
            painter = coverPainter,
            contentDescription = coverDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().playerHeroFade(bridge = fadeBridgeColor)
        )
        Box(
            Modifier.fillMaxWidth()
                .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
                .align(Alignment.TopCenter)
                .graphicsLayer { alpha = statusScrimAlpha().coerceIn(0f, 1f) }
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.34f), Color.Transparent)))
        )
    }
}

/**
 * r674（用户 10-22：Apple Music 沉浸封面底部有条白线；所有沉浸封面交界要最佳效果）——播放器专用渐隐曲线。
 * 参考 Apple Music iOS / Codelaby 复刻（smoothLinearGradient 作遮罩）与 AMLL（封面只做 alpha 遮罩，背景是同一张图的模糊取色层）：
 *  - 只对封面做 alpha 遮罩（DST_IN），不叠任何白色 / 背景色渐变；
 *  - 曲线改为 smootherstep（6t⁵−15t⁴+10t³，起止处一阶、二阶导数都为 0），消除旧 smoothstep 末端二阶导突变造成的马赫带（肉眼的「白线」）；
 *  - 渐隐在图层底边之前 [PLAYER_COVER_FADE_TAIL] 处就到 0，最后一段完全透明：图层边界上不可能留下任何残余像素形成硬边；
 *  - 渐变着色器开启 dither，8bit alpha 不出色带。
 * [position] 0..1 为图层内纵向位置，[fadeFraction] 为渐隐段（含透明尾段）占图层高度比例。
 */
internal const val PLAYER_COVER_FADE_TAIL = 0.05f
internal fun playerCoverFadeAlpha(position: Float, fadeFraction: Float): Float {
    val f = fadeFraction.coerceIn(0.08f, 0.95f)
    val start = 1f - f
    val end = 1f - PLAYER_COVER_FADE_TAIL.coerceAtMost(f * 0.5f)
    val u = ((position.coerceIn(0f, 1f) - start) / (end - start)).coerceIn(0f, 1f)
    val eased = u * u * u * (u * (u * 6f - 15f) + 10f)
    return 1f - eased
}

/** 中段衔接色权重：渐隐段中部最强（钟形 sin），起止为 0，不改变封面主体，也不在末端留下色块。 */
internal const val PLAYER_COVER_BRIDGE_MAX = 0.20f
internal fun playerCoverBridgeAlpha(position: Float, fadeFraction: Float): Float {
    val f = fadeFraction.coerceIn(0.08f, 0.95f)
    val start = 1f - f
    val end = 1f - PLAYER_COVER_FADE_TAIL.coerceAtMost(f * 0.5f)
    val u = ((position.coerceIn(0f, 1f) - start) / (end - start)).coerceIn(0f, 1f)
    return PLAYER_COVER_BRIDGE_MAX * kotlin.math.sin(u * Math.PI.toFloat())
}

private const val FADE_STEPS = 256

/** Draw and mask in the same canvas layer, including software/native screenshot renderers.
 * The mask reaches transparent before the crop edge; no background-colored overlay is used.
 * [bridge]：可选的中段衔接色（下方背景的近似色）；只以 SRC_ATOP 轻微染在封面像素上（钟形权重），再整体 alpha 遮罩。
 */
internal fun Modifier.playerHeroFade(
    strength: Float = 1f,
    fadeFraction: Float = IMMERSIVE_HERO_FADE_FRACTION_PLAYER,
    bridge: () -> Color = { Color.Unspecified }
): Modifier = drawWithCache {
    val k = strength.coerceIn(0f, 1f)
    val colors = IntArray(FADE_STEPS + 1) { step ->
        val mask = playerCoverFadeAlpha(step / FADE_STEPS.toFloat(), fadeFraction)
        val a = ((1f - k * (1f - mask)) * 255f).toInt().coerceIn(0, 255)
        android.graphics.Color.argb(a, 255, 255, 255)
    }
    val paint = android.graphics.Paint().apply {
        isDither = true
        shader = android.graphics.LinearGradient(0f, 0f, 0f, size.height.coerceAtLeast(1f),
            colors, null, android.graphics.Shader.TileMode.CLAMP)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    val bridgeWeights = FloatArray(FADE_STEPS + 1) { step -> playerCoverBridgeAlpha(step / FADE_STEPS.toFloat(), fadeFraction) }
    val bridgePaint = android.graphics.Paint().apply {
        isDither = true
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
    }
    var bridgeArgb = 0
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.saveLayer(0f, 0f, size.width, size.height, null)
            drawContent()
            val c = bridge()
            if (c != Color.Unspecified && k > 0f) {
                val argb = c.toArgb()
                if (argb != bridgeArgb || bridgePaint.shader == null) {
                    bridgeArgb = argb
                    val cols = IntArray(FADE_STEPS + 1) { i ->
                        android.graphics.Color.argb((bridgeWeights[i] * k * 255f).toInt().coerceIn(0, 255),
                            android.graphics.Color.red(argb), android.graphics.Color.green(argb), android.graphics.Color.blue(argb))
                    }
                    bridgePaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, size.height.coerceAtLeast(1f),
                        cols, null, android.graphics.Shader.TileMode.CLAMP)
                }
                native.drawRect(0f, 0f, size.width, size.height, bridgePaint)
            }
            native.drawRect(0f, 0f, size.width, size.height, paint)
            native.restoreToCount(checkpoint)
        }
    }
}

/** 播放器沉浸封面（Apple Hero / 连续封面）渐隐段比例：r674 由 0.34 延长到 0.42，过渡更缓。详情页仍用 [IMMERSIVE_HERO_FADE_FRACTION]。 */
internal const val IMMERSIVE_HERO_FADE_FRACTION_PLAYER = 0.42f

/**
 * r672：与 [playerHeroFade] 同一绘制方式（同一画布 native saveLayer + DST_IN 渐变，Apple 沉浸封面已在用；
 * 不使用 CompositingStrategy.Offscreen + BlendMode.DstIn——r658f 回退过的那种写法），
 * 区别是渐隐终点 [fadeEndPx] 在绘制阶段读取：终点以下完全透明，图像本身的尺寸 / 裁切不变。
 * 标准沉浸封面用它在主页与歌词页之间连续移动渐隐终点，封面像素不位移、不缩放，底部也不会出现与背景拼接的硬边。
 * r674：曲线同 [playerCoverFadeAlpha]（smootherstep + 透明尾段 + dither）。
 */
internal fun Modifier.playerHeroFadeTo(
    fadeEndPx: () -> Float,
    fadeFraction: Float = IMMERSIVE_HERO_FADE_FRACTION
): Modifier = drawWithCache {
    val colors = IntArray(FADE_STEPS + 1) { step ->
        val mask = playerCoverFadeAlpha(step / FADE_STEPS.toFloat(), fadeFraction)
        android.graphics.Color.argb((mask * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
    }
    // 归一化 0..1 的渐变，按每帧终点用 local matrix 纵向缩放；CLAMP 让终点以下保持全透明。
    val shader = android.graphics.LinearGradient(0f, 0f, 0f, 1f, colors, null, android.graphics.Shader.TileMode.CLAMP)
    val matrix = android.graphics.Matrix()
    val paint = android.graphics.Paint().apply {
        isDither = true
        this.shader = shader
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    onDrawWithContent {
        val end = fadeEndPx().coerceIn(1f, size.height.coerceAtLeast(1f))
        matrix.setScale(1f, end)
        shader.setLocalMatrix(matrix)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val checkpoint = native.saveLayer(0f, 0f, size.width, size.height, null)
            drawContent()
            native.drawRect(0f, 0f, size.width, size.height, paint)
            native.restoreToCount(checkpoint)
        }
    }
}
