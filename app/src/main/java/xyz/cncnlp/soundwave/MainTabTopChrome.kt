package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.LocalGlassState
import xyz.cncnlp.soundwave.ui.common.detailHeaderScrollProgress
import xyz.cncnlp.soundwave.ui.common.scrolledHeaderGlass

/**
 * accept6-topbars（用户 2026-11-04 22:40）：首页 / 音乐库顶栏补详情页同款滚动联动。
 *
 * 详情页（DetailTopChrome）的两条进度在此复用同一纯函数与同一常量：
 *  - 背景进度 bgProgress = max(状态进度, 标题进度)——状态进度走 StatusFadeRange 24dp，标题进度走 HeroFadeRange 96dp，
 *    与 DetailCollapsedChrome 的 bgProgress = max(statusProgress, barAlpha) 同合成方式；
 *  - 磨砂本体与分支与 DetailCollapsedChrome 一致：FrostedPrefs.pageActive → scrolledHeaderGlass（paper 64% + 22dp），
 *    否则 paper 色按进度淡入 + 底部 1dp 线。
 * 主页顶栏在 Column 流内（列表不从其下滚过），故联动落在顶栏本体：背板层 + 大标题交叉淡出 + 同几何居中小标题淡入。
 */
internal class MainTabTopChromeState {
    val titleProgress = mutableFloatStateOf(0f)
    val bgProgress = mutableFloatStateOf(0f)
}

/** 纯函数（单测覆盖）：列表滚动 → (背景进度, 标题进度)；背景永不落后于标题。 */
internal fun mainTabChromeProgress(firstIndex: Int, firstOffsetPx: Int, statusRangePx: Float, heroRangePx: Float): Pair<Float, Float> {
    val title = detailHeaderScrollProgress(firstIndex, firstOffsetPx, heroRangePx)
    val bg = maxOf(detailHeaderScrollProgress(firstIndex, firstOffsetPx, statusRangePx), title)
    return bg to title
}

/** 把页列表的滚动进度写入 [state]（snapshotFlow 观察，不在组合期读偏移，避免整页逐帧重组）。 */
@Composable
internal fun MainTabChromeDriver(listState: LazyListState, state: MainTabTopChromeState) {
    val density = LocalDensity.current
    val statusRangePx = with(density) { DetailTopChromeMetrics.StatusFadeRange.toPx() }
    val heroRangePx = with(density) { DetailTopChromeMetrics.HeroFadeRange.toPx() }
    LaunchedEffect(listState, statusRangePx, heroRangePx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            val (bg, title) = mainTabChromeProgress(index, offset, statusRangePx, heroRangePx)
            state.bgProgress.floatValue = bg
            state.titleProgress.floatValue = title
        }
    }
}

/**
 * 顶栏背板层：进度 0 时为现行不透明底（静止态与旧行为一致）；进度 > 0 后转磨砂带
 * （磨砂开 → scrolledHeaderGlass；关 → paper 淡入 + 底部 1dp 线，线 alpha 随标题进度）。
 */
@Composable
internal fun BoxScope.MainTabChromeBackdrop(state: MainTabTopChromeState, glass: LocalGlassState?) {
    val colors = MaterialTheme.colorScheme
    val bg = state.bgProgress.value
    val titleP = state.titleProgress.value
    val frosted = FrostedPrefs.pageActive && glass != null
    Box(
        Modifier.matchParentSize()
            .then(
                when {
                    bg <= 0.001f -> Modifier.background(colors.background)
                    frosted -> Modifier.scrolledHeaderGlass(glass!!, colors.background, bg)
                    else -> Modifier.background(colors.background.copy(alpha = colors.background.alpha * bg))
                }
            )
            .then(
                if (frosted || titleP <= 0f) Modifier
                else Modifier.drawWithContent {
                    drawContent()
                    val line = colors.outline.copy(alpha = colors.outline.alpha * 0.7f * titleP)
                    val h = 1.dp.toPx()
                    drawRect(line, Offset(0f, size.height - h), Size(size.width, h))
                }
            )
    )
}

/**
 * 滚动居中小标题：与静止态大标题行同几何（状态栏高 + [PageHeaderTopPad] + 42dp 行内居中），
 * 中心与大标题行中心重合，交叉淡化全程不跳位；规格同 DetailCollapsedChrome 标题（18sp ExtraBold Manrope）。
 */
@Composable
internal fun BoxScope.MainTabChromeCenteredTitle(state: MainTabTopChromeState, title: String) {
    val p = state.titleProgress.value
    if (p <= 0.01f) return
    val colors = MaterialTheme.colorScheme
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.matchParentSize()) {
        Column {
            Spacer(Modifier.height(statusTop))
            Spacer(Modifier.height(PageHeaderTopPad))
            Box(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 108.dp), contentAlignment = Alignment.Center) {
                Text(
                    title, color = colors.onBackground, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = DesignFonts.Manrope, letterSpacing = (-0.01).em, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = p }
                )
            }
        }
    }
}
