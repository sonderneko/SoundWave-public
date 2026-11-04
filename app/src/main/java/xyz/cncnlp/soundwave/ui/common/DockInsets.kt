package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底部 Dock（迷你条 + 底栏）的统一尺寸与内容避让。
 *
 * 用法（除全屏播放器外的所有页面）：
 *  - LazyColumn / LazyVerticalGrid：`contentPadding = PaddingValues(bottom = dockContentBottomPadding())`
 *    或末尾 `item { Spacer(Modifier.height(dockContentBottomPadding())) }`
 *  - 普通 Column(verticalScroll)：末尾 `Spacer(Modifier.height(dockContentBottomPadding()))`
 *    或 `Modifier.dockContentPadding()`
 * 数值由 AppRoot 的 Scaffold 通过 [LocalDockContentInset] 提供（随迷你条显示/隐藏、底栏样式、系统导航栏高度变化）；
 * 未提供时回退到“迷你条 + 底栏 + 系统导航栏 + 16dp”的保守值。
 */
object DockMetrics {
    /** 默认底栏（.mobile-nav[data-nav-layout=concept]）：66dp，不含系统导航栏。 */
    val BarNavHeight: Dp = 66.dp
    /** 胶囊迷你条（.bottom-player.mini-pill）：r657 修复按 v5 原型 62dp。 */
    val CapsuleMiniHeight: Dp = 62.dp
    /** 迷你条 / 胶囊底栏左右与底部统一内缩（v5 原型 14dp；用户：高度和屏幕左右边距一致）。 */
    val SideInset: Dp = 14.dp
    /** r656：胶囊底栏本体（胶囊 + 圆形搜索钮）高度与胶囊迷你条一致。 */
    val CapsuleNavBodyHeight: Dp = CapsuleMiniHeight
    /** 胶囊底栏（data-nav-layout=project）占位 = 本体 + 上下留白 16dp（r656：76dp）。 */
    val CapsuleNavHeight: Dp = CapsuleNavBodyHeight + 16.dp
    /** 默认迷你条卡片（.bottom-player）：r657 修复按 v5 原型 62dp（封面 48dp r12、卡片 r22）。 */
    val MiniHeight: Dp = 62.dp
    /** 迷你条底边到底栏顶边的间距（bottom:76 → 底栏 66 + 10；实测卡片底到分隔线 14dp）。 */
    val MiniGap: Dp = 14.dp
    /** 内容末尾额外留白（整体上移 4dp：16 → 12）。 */
    val ContentExtra: Dp = 12.dp
    /** 系统导航栏之上的 Dock 总高（默认样式，迷你条可见）。 */
    val HostHeight: Dp = BarNavHeight + MiniGap + MiniHeight
}

/** Scaffold 提供的内容底部避让（已含系统导航栏 inset 与 16dp 余量）。null = 未提供。 */
val LocalDockContentInset = compositionLocalOf<Dp?> { null }

/**
 * accept6-bottomfrost：簇背磨砂带（DockFrostBand）的高度 = 底栏足迹 + 迷你条间距带，带顶与迷你条下沿齐平。
 * 通栏：底栏本体 66 + 系统导航栏（其玻璃含 navigationBarsPadding 段）+ 间距 14；
 * 胶囊：本体 62 + 下边距（Navigation.Capsule.BottomPadding）+ 系统导航栏 + 间距 8（DockMiniGap 胶囊分支的固定值）。
 */
fun dockFrostBandHeight(capsule: Boolean, navInset: Dp): Dp =
    if (capsule) DockMetrics.CapsuleNavBodyHeight + xyz.cncnlp.soundwave.ThemeTokens.Navigation.Capsule.BottomPadding + 8.dp + navInset
    else DockMetrics.BarNavHeight + DockMetrics.MiniGap + navInset

/** 页面末尾应留出的底部空白：迷你条 + 底栏 + 系统导航栏 + 16dp。 */
@Composable
fun dockContentBottomPadding(): Dp =
    LocalDockContentInset.current
        ?: (DockMetrics.BarNavHeight + DockMetrics.MiniGap + xyz.cncnlp.soundwave.rememberMiniPlayerPresentation().height + DockMetrics.ContentExtra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

/** 便捷 PaddingValues（仅底部）。 */
@Composable
fun dockContentPaddingValues(): PaddingValues = PaddingValues(bottom = dockContentBottomPadding())

/** 便捷 Modifier：在滚动容器内部末尾留白。 */
@Composable
fun Modifier.dockContentPadding(): Modifier = this.padding(bottom = dockContentBottomPadding())

/**
 * r657 修复：迷你条与默认底栏之间的 14dp 间隙。原先是透明 Spacer，列表文字（如音乐库「收藏的歌单与专辑 · 0 ITEMS」）
 * 会从迷你条下沿和底栏顶边之间漏出来。默认（通栏）底栏时：
 *  - 磨砂关：从迷你条下半部（圆角处）起用底栏表面色渐变到不透明，间隙本身完全实色，背后内容不再透出；
 *  - 磨砂开：间隙与底栏同为毛玻璃（真模糊 / 94% 实色降级）。
 * 胶囊样式底栏本身悬浮，保持 8dp 透明间距。zIndex -1：画在迷你条下面。
 */
@Composable
fun DockMiniGap(capsule: Boolean, dark: Boolean = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f) {
    if (capsule) { androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp)); return }
    val base = xyz.cncnlp.soundwave.soundwaveDesignScheme(dark).surface.copy(alpha = 1f)
    // r658f：磨砂开时也用与磨砂关相同的软渐变（从迷你条中部透明 → 间隙底部 = 底栏玻璃同不透明度），
    // 不再画一块独立硬边玻璃，底栏视觉高度与磨砂关一致。
    // r658g：磨砂开时间隙不再画半透明色带——它是「未模糊的半透明色」，紧贴下方「模糊玻璃」会在底栏顶边形成一条接缝线。
    // 磨砂开 → 间隙完全透明（底栏高度仍不含间隙）；磨砂关 → 原软渐变。
    if (FrostedPrefs.dockFrosted) { androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(DockMetrics.MiniGap)); return }
    val fill = base
    val rise = DockMetrics.MiniHeight / 2
    androidx.compose.foundation.layout.Spacer(
        Modifier.fillMaxWidth().height(DockMetrics.MiniGap).zIndex(-1f).drawBehind {
            val up = rise.toPx()
            val total = up + size.height
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to fill.copy(alpha = 0f), (up * 0.7f / total) to fill, 1f to fill,
                    startY = -up, endY = size.height
                ),
                topLeft = androidx.compose.ui.geometry.Offset(0f, -up),
                size = androidx.compose.ui.geometry.Size(size.width, total)
            )
        }
    )
}
