package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import xyz.cncnlp.soundwave.ui.common.SheetMotion

/*
 * r658c P（用户 10-20 录屏 1000073622 / 1000073626：默认样式点封面进 / 出歌词页瞬切、卡一下）：
 * 旧实现 = AnimatedContent + SharedTransitionLayout。每次切换都要「新建」目标页（歌词 LazyColumn、逐行 blur、Offscreen 渐隐蒙版
 * 全部首次组合 + 测量），旧页在转场结束后被销毁，下次再切又全量重建；首帧组合在主线程上超过一个转场时长，
 * 交叉淡入与共享元素动画的中间帧全部被吞掉，看起来就是「瞬切 / 卡一下」，「暂无歌词」页同样重建整页所以也卡。
 *
 * 新实现（Apple Music 式连续过渡，零重建）：
 *  - 主页与歌词页「同时常驻组合」，只用 graphicsLayer alpha 交叉淡化（alpha=0 的 RenderNode 不绘制，逐行 blur / 蒙版不参与合成）；
 *    输入交给当前目标页（zIndex 置顶 + 页根阻断下层），不再有任何页面在切换时创建 / 销毁。
 *  - 封面由路由顶层一个「飞行封面」绘制：从主页大封面槽位按 graphicsLayer 平移 + 缩放到歌词页顶部 52dp 迷你封面槽位，返回反向；
 *    两端槽位用 onGloballyPositioned 记录根坐标，进度只在绘制阶段读取（不触发重组、不重新测量）。到达终点前 10% 与真实迷你封面交叉淡化。
 *  - 时长 / 曲线与抽屉、详情转场一致（420ms emphasized-decelerate）；简洁动效 200ms 线性（不取消）。
 *
 * r672（用户 10-22：默认样式封面大↔小末段不顺，尤其歌词页返回封面页）：
 *  - 旧版到达终点前 10% 飞行封面与真实迷你封面「交叉淡化」：两张半透明的同一封面叠加时合成不透明度 < 1，末段透出背景并闪一下；
 *    emphasized-decelerate 曲线最后 40% 时间只走几个像素，看起来像「停住 → 再跳到位」。
 *  - 现在全程只有一个共享元素：路由就绪后飞行封面始终不透明（含两端静止态），歌词页迷你槽位只负责测量与点按（不绘制），
 *    终点不再有任何替换帧；进度改为临界阻尼弹簧（可中断、反向时保留速度、无回弹、末段位移阈值 0.001 不留跳变）。
 */

internal const val PLAYER_COVER_SHARED_MS = SheetMotion.ENTER_MS
internal const val PLAYER_COVER_SHARED_REDUCED_MS = 200

internal fun playerCoverSharedDuration(reduce: Boolean): Int = if (reduce) PLAYER_COVER_SHARED_REDUCED_MS else PLAYER_COVER_SHARED_MS

/** r672：主页 ↔ 歌词页共享进度的弹簧（临界阻尼、无回弹；约 0.35s 走完 99%）。简洁动效仍用 200ms 线性 tween。 */
internal const val PLAYER_COVER_SHARED_SPRING_STIFFNESS = 380f
internal const val PLAYER_COVER_SHARED_VISIBILITY_THRESHOLD = 0.001f

internal fun playerCoverSharedSpec(reduce: Boolean): AnimationSpec<Float> =
    if (reduce) tween(PLAYER_COVER_SHARED_REDUCED_MS, easing = LinearEasing)
    else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PLAYER_COVER_SHARED_SPRING_STIFFNESS,
        visibilityThreshold = PLAYER_COVER_SHARED_VISIBILITY_THRESHOLD)

/** 主页 → 歌词页过渡状态；所有字段只在 layout / draw 阶段读写。 */
@Stable
internal class PlayerCoverMorph(initialProgress: Float = 0f, private val progressProvider: (() -> Float)? = null) {
    /** 0 = 主页，1 = 歌词页（已带缓动）。 */
    private var storedProgress by mutableStateOf(initialProgress)
    val progress: Float get() = progressProvider?.invoke() ?: storedProgress
    fun setProgress(value: Float) { storedProgress = value }
    /** false = 沉浸封面样式（主页无方形封面槽位），歌词页迷你封面随页面淡入，不画飞行封面。路由每次组合时同步（非快照状态，避免组合期回写）。 */
    var enabled: Boolean = true
    var origin by mutableStateOf(Offset.Zero)
    var mainBounds by mutableStateOf(Rect.Zero)
    var miniBounds by mutableStateOf(Rect.Zero)
    /** r672：标准沉浸封面——主页封面槽位底边（相对路由顶端 px）；路由据此让常驻沉浸封面层的渐隐终点在两页之间连续移动。 */
    var immersiveMainBottom by mutableStateOf(0f)
    val ready: Boolean get() = enabled && mainBounds.width > 0f && miniBounds.width > 0f
}

internal val LocalPlayerCoverMorph = staticCompositionLocalOf<PlayerCoverMorph?> { null }

/** 主页透明度：前 60% 进度内淡出。 */
internal fun playerMainPageAlpha(progress: Float): Float = (1f - progress / 0.6f).coerceIn(0f, 1f)

/** 歌词页透明度：35% 进度后淡入。 */
internal fun playerLyricsPageAlpha(progress: Float): Float = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)

/** 歌词页真实迷你封面透明度：r672 起路由就绪时不绘制（只测量 + 点按），封面全程由同一个飞行封面绘制；独立渲染（无路由）时自绘。 */
internal fun playerMiniCoverAlpha(progress: Float, morphReady: Boolean): Float = if (morphReady) 0f else 1f

/** 飞行封面透明度（与 [playerMiniCoverAlpha] 互补）：r672 起全程不透明，终点不再与迷你封面交叉淡化。 */
@Suppress("UNUSED_PARAMETER")
internal fun playerFlyingCoverAlpha(progress: Float): Float = 1f

/**
 * 飞行封面在 progress 处相对主页槽位左上角的平移与缩放（transformOrigin = 左上角）。
 * baseScale = 主页封面播放 / 暂停呼吸缩放（以槽位中心为原点），起点据此换算到左上角原点。
 */
internal data class PlayerCoverMorphFrame(val translationX: Float, val translationY: Float, val scale: Float)

internal fun playerCoverMorphFrame(main: Rect, mini: Rect, progress: Float, baseScale: Float): PlayerCoverMorphFrame {
    val p = progress.coerceIn(0f, 1f)
    val startLeft = main.left + main.width * (1f - baseScale) / 2f
    val startTop = main.top + main.height * (1f - baseScale) / 2f
    val endScale = if (main.width > 0f) mini.width / main.width else baseScale
    val scale = baseScale + (endScale - baseScale) * p
    val left = startLeft + (mini.left - startLeft) * p
    val top = startTop + (mini.top - startTop) * p
    return PlayerCoverMorphFrame(left - main.left, top - main.top, scale)
}

/** 记录主页大封面槽位（根坐标，不受后续 graphicsLayer 影响）。 */
internal fun Modifier.playerCoverMainSlot(morph: PlayerCoverMorph?): Modifier = if (morph == null) this else onGloballyPositioned { c ->
    val r = Rect(c.positionInRoot(), c.size.toSize())
    if (r != morph.mainBounds) morph.mainBounds = r
}

/** 记录歌词页迷你封面槽位，并在飞行封面到位时交叉淡入。 */
internal fun Modifier.playerCoverMiniSlot(morph: PlayerCoverMorph?): Modifier = if (morph == null) this else onGloballyPositioned { c ->
    val r = Rect(c.positionInRoot(), c.size.toSize())
    if (r != morph.miniBounds) morph.miniBounds = r
}.graphicsLayer { alpha = playerMiniCoverAlpha(morph.progress, morph.ready) }

/** 飞行封面变换：只在绘制阶段读取进度与槽位。 */
internal fun Modifier.playerCoverFlight(morph: PlayerCoverMorph, baseScale: () -> Float): Modifier = graphicsLayer {
    val p = morph.progress
    val f = playerCoverMorphFrame(morph.mainBounds, morph.miniBounds, p, baseScale())
    transformOrigin = TransformOrigin(0f, 0f)
    translationX = f.translationX
    translationY = f.translationY
    scaleX = f.scale
    scaleY = f.scale
    alpha = playerFlyingCoverAlpha(p)
}

/** Radius in the unscaled flying node; the visible radius lands on the 12dp mini artwork.
 * Chrome is faded separately, so the final handoff changes neither artwork nor corner geometry. */
internal fun playerCoverMorphCornerDp(progress: Float, scale: Float, baseScale: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return (28f * baseScale + (12f - 28f * baseScale) * p) / scale.coerceAtLeast(0.001f)
}
