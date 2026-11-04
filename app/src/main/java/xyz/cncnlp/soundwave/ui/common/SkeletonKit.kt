package xyz.cncnlp.soundwave.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.CoverPlaceholderBase
import xyz.cncnlp.soundwave.MotionPolicy

/**
 * 通用骨架屏体系（验收 B 波，与封面占位同一设计语言）：
 *  - 底色取设计调色板暖灰（浅色 #E9E9E3 / 夜间 #232722），在 Paper 底上清晰可见，不再用 onSurface 8% 的近隐形灰；
 *  - 封面块复用封面占位渐变底（珊瑚淡粉 → 暖灰），与真实封面占位同色温、同圆角；
 *  - 一道微光自左向右扫过（ACCEPT4 按 v8 稿 .v8sk：1500ms、cubic-bezier(.4,0,.2,1)、扫光在周期 60% 内走完，
 *    逐行相位差 70ms，见 [SkeletonRows]）；「减少动效」/ 测试环境（MotionPolicy 装饰循环冻结）时静止无扫光。
 *
 * 验收二轮 E4：块底色改调色板字面值（按背景亮度分浅/夜）——原先读主题 surfaceVariant，
 * 根主题夜间是蓝灰 #273444，与占位纠偏同口径；字面值与设计子树原取值逐值一致。
 */
private val LocalSkeletonPhase = staticCompositionLocalOf { -1f }

/** 骨架作用域：统一驱动内部所有骨架块的扫光相位（同一时刻组成 → 同相位）。 */
@Composable
fun SkeletonGroup(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val phase = if (MotionPolicy.allowDecorativeLoops(reduce)) {
        val transition = rememberInfiniteTransition(label = "skeletonShimmer")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Restart),
            label = "skeletonPhase"
        )
        animated
    } else {
        -1f
    }
    CompositionLocalProvider(LocalSkeletonPhase provides phase) {
        Box(modifier, content = content)
    }
}

/** 稿 .v8sk 扫光关键帧：位移在周期 60% 内走完，其余时间停在画外。 */
private fun skeletonSweep(phase: Float): Float = (phase / 0.6f).coerceAtMost(1f)

@Composable
private fun Modifier.skeletonSurface(shape: Shape, base: Color): Modifier {
    val phase = LocalSkeletonPhase.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlight = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.55f)
    return this.clip(shape).background(base).drawWithContent {
        drawContent()
        if (phase >= 0f) {
            val bandWidth = size.width * 0.55f
            val x = -bandWidth + (size.width + bandWidth) * skeletonSweep(phase)
            drawRect(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + bandWidth, size.height)
                )
            )
        }
    }
}

/** 通用骨架块（标题条 / 副标题条 / 卡片面）。 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SkeletonBlock(
        modifier, shape,
        if (dark) xyz.cncnlp.soundwave.SoundwaveDesignPalette.NightSurface2 else xyz.cncnlp.soundwave.SoundwaveDesignPalette.Surface2
    )
}

@Composable
internal fun SkeletonBlock(modifier: Modifier = Modifier, shape: Shape, base: Color) {
    Box(modifier.skeletonSurface(shape, base))
}

/** 封面骨架块：占位渐变底 + 黑胶母题 + 扫光，与真实封面占位同体系（ACCEPT5 收编：此前只有渐变底，真机呈淡粉空块）。 */
@Composable
fun SkeletonCoverBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val phase = LocalSkeletonPhase.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlight = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.55f)
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        CoverPlaceholderBase(Modifier.matchParentSize()) {}
        xyz.cncnlp.soundwave.VinylDiscMotif(Modifier.fillMaxSize())
        Box(
            Modifier.matchParentSize().drawWithContent {
                drawContent()
                if (phase >= 0f) {
                    val bandWidth = size.width * 0.7f
                    val x = -bandWidth + (size.width + bandWidth) * skeletonSweep(phase)
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, highlight, Color.Transparent),
                            start = Offset(x, 0f),
                            end = Offset(x + bandWidth, size.height)
                        )
                    )
                }
            }
        )
    }
}

/**
 * 列表行骨架：封面块 + 标题条 + 副标题条。
 * 默认几何与首页 Feed 骨架旧版一致（44dp r10 封面 / 150×13 标题 / 96×11 副标题）；
 * [titleFraction] > 0 时标题/副标题条改按可用宽度比例（搜索结果等宽行场景）。
 */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    coverSize: Dp = 44.dp,
    coverShape: Shape = RoundedCornerShape(10.dp),
    circleCover: Boolean = false,
    titleFraction: Float = 0f,
    subtitleFraction: Float = 0f
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SkeletonCoverBlock(Modifier.size(coverSize), if (circleCover) CircleShape else coverShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkeletonBlock(
                if (titleFraction > 0f) Modifier.fillMaxWidth(titleFraction).height(13.dp) else Modifier.width(150.dp).height(13.dp),
                RoundedCornerShape(4.dp)
            )
            Spacer(Modifier.height(8.dp))
            SkeletonBlock(
                if (subtitleFraction > 0f) Modifier.fillMaxWidth(subtitleFraction).height(11.dp) else Modifier.width(96.dp).height(11.dp),
                RoundedCornerShape(4.dp)
            )
        }
    }
}

/** 稿 .v8skin：骨架行入场（500ms 淡入 + 上移 6dp），逐行延迟 70ms；「减少动效」时直接呈现。 */
private fun Modifier.skeletonRowEnter(index: Int): Modifier = composed {
    val reduce = MotionPolicy.rememberReduceMotion()
    val p = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!reduce) p.animateTo(
            1f,
            androidx.compose.animation.core.tween(500, delayMillis = index * 70, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        )
    }
    this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 6.dp.toPx() }
}

/** 多行列表骨架（行间距与各列表的加载占位一致：行内垂直 8dp）。逐行带稿面 70ms 相位差扫光与入场。 */
@Composable
fun SkeletonRows(
    count: Int,
    modifier: Modifier = Modifier,
    coverSize: Dp = 44.dp,
    coverShape: Shape = RoundedCornerShape(10.dp),
    circleCover: Boolean = false,
    titleFraction: Float = 0f,
    subtitleFraction: Float = 0f
) {
    SkeletonGroup(modifier) {
        val basePhase = LocalSkeletonPhase.current
        Column {
            repeat(count) { i ->
                // 稿 .v8sk-r 的 --d = i×70ms：本行扫光相位相对全组滞后 i×70ms。
                val shifted = if (basePhase < 0f) -1f else (basePhase - i * 70f / 1500f).mod(1f)
                CompositionLocalProvider(LocalSkeletonPhase provides shifted) {
                    Box(Modifier.skeletonRowEnter(i)) {
                        SkeletonRow(
                            coverSize = coverSize,
                            coverShape = coverShape,
                            circleCover = circleCover,
                            titleFraction = titleFraction,
                            subtitleFraction = subtitleFraction
                        )
                    }
                }
                if (i < count - 1) Spacer(Modifier.height(16.dp))
            }
        }
    }
}
