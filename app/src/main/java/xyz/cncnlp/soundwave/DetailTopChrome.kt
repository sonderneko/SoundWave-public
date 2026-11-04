package xyz.cncnlp.soundwave

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.LocalGlassState
import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.scrolledHeaderGlass

/**
 * r657 第二轮：详情页顶部 = 原型 02 / 02b。
 *  - 大页头（31sp 衬线标题 + 搜索 / 更多圆钮）是列表首项，随列表滚走；
 *  - 滚动 > 6px：状态栏条磨砂（`.pg.scrolled>.sbf`）；
 *  - 英雄卡底边滚过折叠顶栏底边：折叠顶栏淡入（`.topbar.on`，260ms）——居中 18sp 粗体标题 + 右侧珊瑚播放玻璃圆钮
 *    （左侧返回按钮保持与右侧播放按钮等宽，标题居中）；
 *  - 「曲目列表 · N 首」工具条滚到折叠顶栏下沿时钉住，与顶栏共用同一块磨砂面。
 * 磨砂：「透明磨砂背景」开 → 真实模糊（paper 64% + 22dp），且不画分隔线；关 → 不透明纸色 + 底部 1dp 分隔线。
 * 钉住时列表内原工具条在绘制阶段隐藏（alpha 0），模糊里不会再透出它的残影。
 */
internal object DetailTopChromeMetrics {
    val BarHeight: Dp = 56.dp
    const val FADE_MS = 260
    /** r658c U #1/#7：英雄卡底边从「顶栏底边 + 本距离」滚到顶栏底边的过程中，折叠顶栏按滚动偏移连续淡入（不再按时间瞬切）。 */
    val HeroFadeRange: Dp = 96.dp
    /** 状态栏条磨砂按 0→本距离的滚动偏移连续淡入。 */
    val StatusFadeRange: Dp = 24.dp
}

/**
 * r658c U #1/#7：纯函数——英雄卡底边位置 → 折叠顶栏进度（0 = 不显示，1 = 完全显示）。
 * [heroBottomPx] 为 null 表示英雄卡不在可见区：[pastHero] 时为 1，否则 0。
 */
internal fun detailHeroCollapseProgress(heroBottomPx: Float?, barPx: Float, rangePx: Float, pastHero: Boolean): Float = when {
    heroBottomPx == null -> if (pastHero) 1f else 0f
    rangePx <= 0f -> if (heroBottomPx < barPx) 1f else 0f
    else -> ((barPx + rangePx - heroBottomPx) / rangePx).coerceIn(0f, 1f)
}

/** 列表滚动 → 顶部各状态（全部 derivedStateOf，只在值变化时通知读取方）。 */
internal class DetailTopChromeState(
    val scrolled: State<Boolean>,
    val topbarOn: State<Boolean>,
    val toolbarPinned: State<Boolean>,
    /** 折叠顶栏 / 沉浸页头背景的连续进度（随滚动偏移，0..1）。 */
    val heroProgress: State<Float> = androidx.compose.runtime.mutableFloatStateOf(0f),
    /** 状态栏条磨砂的连续进度（0..1）。 */
    val statusProgress: State<Float> = androidx.compose.runtime.mutableFloatStateOf(0f)
)

@Composable
internal fun rememberDetailTopChromeState(listState: LazyListState, heroKey: Any, toolbarKey: Any): DetailTopChromeState {
    val density = LocalDensity.current
    val statusPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barPx = with(density) { (statusPx + DetailTopChromeMetrics.BarHeight).toPx() }
    val heroRangePx = with(density) { DetailTopChromeMetrics.HeroFadeRange.toPx() }
    val statusRangePx = with(density) { DetailTopChromeMetrics.StatusFadeRange.toPx() }
    return remember(listState, heroKey, toolbarKey, barPx, heroRangePx, statusRangePx) {
        val scrolled = derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        val heroProgress = derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val hero = visible.firstOrNull { it.key == heroKey }
            detailHeroCollapseProgress(
                heroBottomPx = hero?.let { (it.offset + it.size).toFloat() }, barPx = barPx, rangePx = heroRangePx,
                pastHero = listState.firstVisibleItemIndex > 0 && visible.none { it.index < 2 && it.key != toolbarKey }
            )
        }
        val statusProgress = derivedStateOf {
            xyz.cncnlp.soundwave.ui.common.detailHeaderScrollProgress(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, statusRangePx)
        }
        // 可点 / 状态栏图标切换的阈值：进度过半（视觉上顶栏已基本显现）。
        val topbarOn = derivedStateOf { heroProgress.value >= 0.5f }
        val pinned = derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val bar = visible.firstOrNull { it.key == toolbarKey }
            if (bar != null) bar.offset < barPx
            else listState.firstVisibleItemIndex > 0 && visible.none { it.key == heroKey } && topbarOn.value
        }
        DetailTopChromeState(scrolled, topbarOn, pinned, heroProgress, statusProgress)
    }
}

/** 列表内原工具条：钉住时在绘制阶段隐藏（不重组），模糊面里不会出现重影。 */
internal fun Modifier.hideWhenPinned(state: DetailTopChromeState): Modifier = graphicsLayer { alpha = if (state.toolbarPinned.value) 0f else 1f }

@Composable
internal fun DetailCollapsedChrome(
    state: DetailTopChromeState,
    glass: LocalGlassState,
    title: String,
    playEnabled: Boolean,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    /**
     * r658c U #1（用户 10-20，1000073625）：沉浸模式——状态栏区域保持透明（头图延伸到状态栏下），
     * 页头背景只随英雄卡滚出按 [DetailTopChromeState.heroProgress] 连续渐显，不再一滚动就出现一块纸色实底条。
     */
    immersive: Boolean = false,
    pinnedToolbar: @Composable ColumnScope.() -> Unit
) {
    val scrolled by state.scrolled
    if (!scrolled) return
    val pinned by state.toolbarPinned
    val colors = MaterialTheme.colorScheme
    val frosted = FrostedPrefs.pageActive
    // r658c U #7（用户录屏 1000073630：回顶时紧凑页头瞬切成大页头）：顶栏不透明度与背景都按滚动偏移连续变化，
    // 列表回滚过程中与展开页头连续交接，不再按 260ms 定时淡入淡出、也不在 scrolled 翻转时整块消失。
    val barAlpha = state.heroProgress.value
    val bgProgress = if (immersive) barAlpha else maxOf(state.statusProgress.value, barAlpha)
    if (bgProgress <= 0.001f && !pinned) return
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val line = colors.outline.copy(alpha = colors.outline.alpha * 0.7f * barAlpha.coerceAtLeast(if (pinned) 1f else 0f))
    Column(
        modifier.fillMaxWidth()
            .then(
                if (frosted) Modifier.scrolledHeaderGlass(glass, colors.background, if (pinned) 1f else bgProgress)
                else Modifier.background(colors.background.copy(alpha = colors.background.alpha * (if (pinned) 1f else bgProgress)))
            )
            .then(
                if (frosted || !(barAlpha > 0f || pinned)) Modifier
                else Modifier.drawWithContent {
                    drawContent()
                    val h = 1.dp.toPx()
                    drawRect(line, Offset(0f, size.height - h), Size(size.width, h))
                }
            )
    ) {
        Spacer(Modifier.height(statusTop))
        if (barAlpha > 0.01f) {
            Row(
                Modifier.fillMaxWidth().height(DetailTopChromeMetrics.BarHeight).padding(horizontal = 14.dp).graphicsLayer { alpha = barAlpha },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // r657 第三轮（用户 2026-10-18 08:11）：折叠磨砂栏 = 左 42dp 返回 + 居中标题 + 右珊瑚播放。
                if (onBack != null) xyz.cncnlp.soundwave.ui.common.DesignHeaderCircleButton(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.Back, "返回", onClick = onBack)
                else Spacer(Modifier.size(42.dp))
                Text(
                    title, color = colors.onBackground, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { DetailTopbarPlayButton(playEnabled, onPlayAll) }
            }
        } else if (pinned) Spacer(Modifier.height(DetailTopChromeMetrics.BarHeight))
        if (pinned) pinnedToolbar()
    }
}

/** 原型 `.cbtn.glass` 珊瑚播放：42dp 玻璃圆（表面 72% + 白色描边），珊瑚实心三角；按下 .92。 */
@Composable
private fun DetailTopbarPlayButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Box(
        Modifier.size(42.dp)
            .pressScale(PressMotion.SCALE_ICON)
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = if (FrostedPrefs.pageActive) 0.62f else 0.92f))
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "播放全部"; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        Icon(DesignChromeGlyphs.PlaySolid, null, tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(18.dp))
    }
}

