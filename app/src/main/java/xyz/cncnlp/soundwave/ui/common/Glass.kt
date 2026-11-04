package xyz.cncnlp.soundwave.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import xyz.cncnlp.soundwave.ThemeTokens.SheetFrost as SheetFrostTokens

/**
 * r657 修复：真实背景模糊（毛玻璃）。
 *
 * - 内容根（AppRoot Scaffold 的内容 Box）挂 [glassSource]，把页面画进一张 GraphicsLayer；
 * - 底栏 / 胶囊组 / 搜索圆钮 / 迷你条 / 详情页迷你条挂 [dockGlass]：
 *   - 「底栏与迷你条磨砂」关闭（默认）→ 不透明实色表面（与 1.0.656 一致）；
 *   - 打开且 API ≥ 32（haze 默认开启真模糊的版本）→ 背后内容 24dp 模糊 + 表面色 82% 罩（可混入封面取色）；
 *   - 打开但 API ≤ 31、软件画布或尚无模糊源 → 表面色 [GLASS_FALLBACK_ALPHA] 实色降级，背后文字永远不会清晰透出。
 * - 详情顶栏滚动后用 [scrolledHeaderGlass]（paper 64% + 22dp 模糊，页面内局部模糊源）。
 *
 * 模糊源与效果节点不能互为后代：底栏 / 迷你条位于 Scaffold bottomBar，与内容根是兄弟节点。
 */
object GlassHost {
    /** 全局唯一的模糊源（AppRoot 内容根）。单 Activity 应用，跨重建复用不会泄漏（只持有 GraphicsLayer 引用，由节点释放）。 */
    val state: HazeState = HazeState()
    /** 内容根的模糊源 Modifier（非扩展形式，便于 MainActivity 只改一行、不加 import）。 */
    val source: Modifier get() = Modifier.haze(state)
    /**
     * ACCEPT6 dialogfrost：顶层模糊源——包裹「全屏播放器层 + 压播放器详情层」。
     * 表开在播放器之上时，内容根源记录的是被播放器盖住的主标签页，采样它会雾错对象；
     * 此时改采顶层源（见 [topCovering]）。
     */
    val topState: HazeState = HazeState()
    /** 顶层源 Modifier（非扩展形式，同 [source] 口径）。 */
    val topSource: Modifier get() = Modifier.haze(topState)
    /** 顶层是否正覆盖内容根（由 AppRoot 按播放器可见态写入）；表背板/面板的采样源据此二选一。 */
    var topCovering by mutableStateOf(false)
}

/** 表磨砂的当前采样源：顶层覆盖时采顶层源，否则采内容根源。 */
@Composable
private fun sheetBackdropSource(): HazeState = if (GlassHost.topCovering) GlassHost.topState else GlassHost.state

/** API ≤ 31 / 无模糊源时磨砂表面的不透明度（审计第 1 项：做不到真模糊时 ≥ 94%）。 */
const val GLASS_FALLBACK_ALPHA = 0.94f
/** 真模糊时表面罩的不透明度（原型 surface 82%）。 */
const val GLASS_TINT_ALPHA = 0.82f
val GlassDockBlur: Dp = 24.dp

/** 真模糊只在 haze 默认开启的版本（API 32+）上使用；更低版本走实色降级。 */
fun glassBlurSupported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 32

/**
 * 纯函数：磨砂开关 / 系统版本 → 表面实际绘制方式（单测覆盖）。
 * 返回 (blur, alpha)：blur = 是否走真模糊；alpha = 表面色罩不透明度。
 */
fun dockGlassPlan(dockFrosted: Boolean, sdk: Int = Build.VERSION.SDK_INT): Pair<Boolean, Float> = when {
    !dockFrosted -> false to 1f
    glassBlurSupported(sdk) -> true to GLASS_TINT_ALPHA
    else -> false to GLASS_FALLBACK_ALPHA
}

/* ───────── ACCEPT6 dialogfrost：对话框/表磨砂（常开，与 FrostedPrefs 页面磨砂解耦） ───────── */

/**
 * 纯函数：表磨砂绘制计划（单测覆盖）。返回 (blur, tintAlpha)：
 * API ≥ 32 真模糊 + 罩 [SheetFrostTokens.GlassTintAlpha]；更低版本无真模糊 → 罩 [GLASS_FALLBACK_ALPHA] 近不透明（近似路径，台账登记）。
 */
fun sheetGlassPlan(sdk: Int = Build.VERSION.SDK_INT): Pair<Boolean, Float> =
    if (glassBlurSupported(sdk)) true to SheetFrostTokens.GlassTintAlpha else false to GLASS_FALLBACK_ALPHA

/** 纯函数：pinned 顶栏带磨砂进度——前 [rampPx] 滚动内 0→1 线性（单测覆盖）。 */
fun sheetHeaderGlassProgress(scrollPx: Int, rampPx: Float): Float =
    if (rampPx <= 0f) (if (scrollPx > 0) 1f else 0f) else (scrollPx / rampPx).coerceIn(0f, 1f)

/**
 * 对话框/表背后的全屏雾化层（稿面：面板之外整片背景同样被雾化）：采样当前表源，
 * 只画模糊不罩色（压暗归调用方的家族遮罩）；显现进度读自 [enter]（绘制期读取，与遮罩淡变同步）。
 * 无真模糊时不绘制——遮罩单独承担（近似路径）。
 */
@Composable
fun SheetFrostBackdrop(enter: androidx.compose.animation.core.Animatable<Float, *>, modifier: Modifier = Modifier) {
    if (!glassBlurSupported()) return
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier.fillMaxSize().hazeChild(
            sheetBackdropSource(),
            HazeStyle(
                backgroundColor = background,
                tint = HazeTint(Color.Transparent),
                blurRadius = SheetFrostTokens.GlassBlurDp.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(Color.Transparent)
            )
        ) { alpha = enter.value }
    )
}

/**
 * 表/对话框面板玻璃：真模糊时采样当前表源（罩 = 主题 surface @ GlassTintAlpha），
 * 否则 surface @ GLASS_FALLBACK_ALPHA 实色（近似路径）。已含 clip(shape)；
 * Dialog 窗口回退宿主（背后是窗口级 blurBehind）由 DesignSheetPanel 另行分支，不走这里。
 */
@Composable
fun Modifier.sheetPanelGlass(shape: Shape, surface: Color): Modifier {
    val (blur, alpha) = sheetGlassPlan()
    val clipped = this.clip(shape)
    if (!blur) return clipped.background(surface.copy(alpha = alpha), shape)
    return clipped.hazeChild(
        sheetBackdropSource(),
        HazeStyle(
            backgroundColor = surface.copy(alpha = 1f),
            tint = HazeTint(surface.copy(alpha = alpha)),
            blurRadius = SheetFrostTokens.GlassBlurDp.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(surface.copy(alpha = GLASS_FALLBACK_ALPHA))
        )
    )
}

/**
 * pinned 顶栏带磨砂（歌曲操作/播放路径等带钉顶标题的表）：[state] 为表内滚动内容的局部模糊源，
 * 参数与表磨砂同源同值（罩 GlassTintAlpha / 模糊 GlassBlurDp）；[progress] 0 = 全透（与表身无缝）、
 * 1 = 完整磨砂 + 底缘发丝线。无真模糊时以近不透明实色按进度淡入（近似路径）。与详情页
 * [scrolledHeaderGlass] 同法同源（局部源 + 进度驱动），数值按表令牌，不另起一套。
 */
@Composable
fun Modifier.sheetHeaderGlass(state: HazeState, surface: Color, progress: Float, line: Color): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return this
    val glassed = if (glassBlurSupported()) {
        this.hazeChild(
            state,
            HazeStyle(
                backgroundColor = surface.copy(alpha = 1f),
                tint = HazeTint(surface.copy(alpha = SheetFrostTokens.GlassTintAlpha)),
                blurRadius = SheetFrostTokens.GlassBlurDp.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(surface.copy(alpha = GLASS_FALLBACK_ALPHA))
            )
        ) { alpha = p }
    } else this.background(surface.copy(alpha = GLASS_FALLBACK_ALPHA * p), RectangleShape)
    return glassed.drawWithContent {
        drawContent()
        val h = 1.dp.toPx()
        drawRect(line.copy(alpha = line.alpha * p), androidx.compose.ui.geometry.Offset(0f, size.height - h), androidx.compose.ui.geometry.Size(size.width, h))
    }
}

/** 非扩展形式（调用方无需新增 import）。 */
@Composable
fun sheetHeaderGlassModifier(state: HazeState, surface: Color, progress: Float, line: Color): Modifier =
    Modifier.sheetHeaderGlass(state, surface, progress, line)

/** 页面内局部模糊源（详情页列表 → 详情顶栏）；不能用全局源，因为详情页本身就在全局源内部。 */
/** 页面局部玻璃源状态（haze 只允许在本文件出现，其它文件用这个别名）。 */
typealias LocalGlassState = HazeState

@Composable
fun rememberLocalGlassState(): HazeState = androidx.compose.runtime.remember { HazeState() }

fun Modifier.localGlassSource(state: HazeState): Modifier = this.haze(state)

/** 内容根：成为毛玻璃的模糊源。 */
fun Modifier.glassSource(): Modifier = this.haze(GlassHost.state)

/**
 * 底栏 / 迷你条表面。[base] 为不透明表面色；[tint] 为可选封面取色。
 * 已包含 clip(shape)；alpha < 1 时加 1dp 内描边（line 80%，审计第 11 项）。
 */
@Composable
fun Modifier.dockGlass(shape: Shape, base: Color, dark: Boolean, line: Color? = null): Modifier {
    val (blur, alpha) = dockGlassPlan(FrostedPrefs.dockFrosted)
    val tone = dockCoverTint(FrostedPrefs.pageActive, FrostedPrefs.coverTint, FrostedPrefs.tone)
    val opaque = base.copy(alpha = 1f)
    val tinted = if (tone != null && FrostedPrefs.dockFrosted) lerp(opaque, tone.copy(alpha = 1f), if (dark) 0.14f else 0.08f) else opaque
    val clipped = this.clip(shape)
    if (alpha >= 1f) return clipped.background(opaque, shape)
    // r658g（用户 10-20：默认 / 胶囊底栏与迷你条开磨砂后有一圈边缘线）：磨砂态不再叠 1dp 描边（原 outlineVariant / 白 55%），
    // 玻璃靠模糊 + 投影自然分层，与磨砂关时的无边线外观一致。[line] 参数保留以兼容调用点。
    @Suppress("UNUSED_VARIABLE") val unusedLine = line
    return if (!blur) {
        clipped.background(tinted.copy(alpha = GLASS_FALLBACK_ALPHA), shape)
    } else {
        clipped.hazeChild(
            GlassHost.state,
            HazeStyle(
                backgroundColor = opaque,
                tint = HazeTint(tinted.copy(alpha = alpha)),
                blurRadius = GlassDockBlur,
                noiseFactor = 0f,
                fallbackTint = HazeTint(tinted.copy(alpha = GLASS_FALLBACK_ALPHA))
            )
        )
    }
}

/**
 * accept6-bottomfrost（用户 2026-11-04 23:44）：底部悬浮簇背后的统一磨砂带。
 *
 * 迷你条 / 底栏只磨砂各自形状（[dockGlass]），二者之间的间距带、胶囊底栏的左右内缩与下边距带此前无承载面，
 * 滚动内容从这些带里生硬露出。本带由宿主定位在其背后：带顶与迷你条下沿齐平（高度 = 底栏足迹 + 间距带，
 * 见 DockInsets.dockFrostBandHeight），向下直达屏幕底边。与 [dockGlass] 同参同源：
 * 开关 = [FrostedPrefs.dockFrosted]（关 → 不绘制，宿主回退现行呈现）、罩 = [GLASS_TINT_ALPHA]、模糊 = [GlassDockBlur]、
 * 封面混色口径同 dockGlass；API ≤ 31 / 无真模糊时以 [GLASS_FALLBACK_ALPHA] 实色降级承接（近似，非真模糊）。
 * 采样源为内容根 [GlassHost.state]，采样范围限于本带 bounds（非整屏采样）。
 */
@Composable
fun DockFrostBand(modifier: Modifier = Modifier, base: Color, dark: Boolean) {
    Box(if (FrostedPrefs.dockFrosted) modifier.dockFrostBandSurface(base, dark) else modifier)
}

@Composable
private fun Modifier.dockFrostBandSurface(base: Color, dark: Boolean): Modifier {
    val (blur, _) = dockGlassPlan(dockFrosted = true)
    val tone = dockCoverTint(FrostedPrefs.pageActive, FrostedPrefs.coverTint, FrostedPrefs.tone)
    val opaque = base.copy(alpha = 1f)
    val tinted = if (tone != null) lerp(opaque, tone.copy(alpha = 1f), if (dark) 0.14f else 0.08f) else opaque
    return if (!blur) {
        background(tinted.copy(alpha = GLASS_FALLBACK_ALPHA))
    } else {
        hazeChild(
            GlassHost.state,
            HazeStyle(
                backgroundColor = opaque,
                tint = HazeTint(tinted.copy(alpha = GLASS_TINT_ALPHA)),
                blurRadius = GlassDockBlur,
                noiseFactor = 0f,
                fallbackTint = HazeTint(tinted.copy(alpha = GLASS_FALLBACK_ALPHA))
            )
        )
    }
}

/**
 * 详情顶栏滚动磨砂（原型 02b `.pg.scrolled>.sbf`）：[progress] 0 = 完全透明，1 = paper 64% + 22dp 模糊。
 * 低版本：paper 0→94% 实色淡入。
 */
@Composable
fun Modifier.scrolledHeaderGlass(state: HazeState, paper: Color, progress: Float): Modifier {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return this
    return if (glassBlurSupported()) {
        this.hazeChild(
            state,
            HazeStyle(
                backgroundColor = paper.copy(alpha = 1f),
                tint = HazeTint(paper.copy(alpha = 0.64f)),
                blurRadius = 22.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(paper.copy(alpha = GLASS_FALLBACK_ALPHA))
            )
        ) { alpha = p }
    } else this.background(paper.copy(alpha = GLASS_FALLBACK_ALPHA * p), RectangleShape)
}

/** 纯函数：详情列表滚动 → 顶栏磨砂进度（0→[rangePx] 线性，滚过首项即 1）。 */
fun detailHeaderScrollProgress(firstIndex: Int, firstOffsetPx: Int, rangePx: Float): Float =
    if (firstIndex > 0) 1f else if (rangePx <= 0f) 1f else (firstOffsetPx / rangePx).coerceIn(0f, 1f)

/**
 * r658c U #3：透明磨砂圆钮底（原型 `.cbtn` / `.cbtn.onimg`：半透明底 + backdrop-filter blur(14px)）。
 * [state] 非空且 API ≥ 32 → 背后内容 14dp 真模糊 + [tint]；否则 [tint] 加深到 [fallbackAlpha] 的半透明实色（不会完全不透明）。
 * 已包含 clip(shape)。haze 只允许出现在本文件，其它文件通过本函数使用。
 */
fun Modifier.frostedCircleBackdrop(state: HazeState?, tint: Color, fallbackAlpha: Float, shape: Shape): Modifier {
    val clipped = this.clip(shape)
    return if (state != null && glassBlurSupported()) {
        clipped.hazeChild(
            state,
            HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(tint),
                blurRadius = 14.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(tint.copy(alpha = fallbackAlpha))
            )
        )
    } else clipped.background(tint.copy(alpha = maxOf(tint.alpha, fallbackAlpha)), shape)
}

/** 非扩展形式的局部模糊源（避免调用方新增 import）。 */
fun localGlassSourceModifier(state: HazeState): Modifier = Modifier.haze(state)

/** 详情顶栏：静止透明，滚动后磨砂 + 底部 1dp 线（line 30%）。 */
@Composable
fun Modifier.detailHeaderGlass(state: HazeState, paper: Color, line: Color, progress: () -> Float): Modifier {
    val p = progress()
    return this
        .scrolledHeaderGlass(state, paper, p)
        .then(
            if (p <= 0.001f) Modifier else Modifier.drawBehindLine(line.copy(alpha = line.alpha * 0.3f * p))
        )
}

private fun Modifier.drawBehindLine(color: Color): Modifier = this.drawWithContent {
    drawContent()
    val h = 1.dp.toPx()
    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
}

/** 非扩展形式（调用方无需新增 import）。 */
@Composable
fun detailHeaderGlassModifier(state: HazeState, paper: Color, line: Color, progress: () -> Float): Modifier =
    Modifier.detailHeaderGlass(state, paper, line, progress)

/**
 * 审计第 22 项：「透明磨砂背景」打开时卡片 / 圆钮改为半透明表面（原型 surface 58%；
 * 卡片背后只有已模糊的磨砂底层，不会透出文字）。API ≤ 31 用 86%。关闭时原样返回。
 */
@Composable
fun frostedCardFill(base: Color): Color = frostedCardFill(base, FrostedPrefs.pageActive)

// r658d（用户 10-20：磨砂只有透明度、太透）：卡片 / 胶囊不做逐个实时模糊（列表里太贵），改为 74% 表面色压在已模糊的页面背景上，接近迷你条质感。
fun frostedCardFill(base: Color, pageFrosted: Boolean, sdk: Int = Build.VERSION.SDK_INT): Color =
    if (!pageFrosted) base else base.copy(alpha = if (glassBlurSupported(sdk)) 0.74f else 0.86f)

/**
 * r657 第二轮：「透明磨砂背景」打开时胶囊 / 卡片按钮（Chip、Pill、取值框）改为半透明表面（与圆钮同为 surface 58%，
 * API ≤ 31 为 86%）；关闭时返回 [base]（可为透明，保持原样）。主色 / 选中态不要走这里。
 */
@Composable
fun frostedChipFill(base: Color): Color =
    if (!FrostedPrefs.pageActive) base
    else androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = if (glassBlurSupported(Build.VERSION.SDK_INT)) 0.74f else 0.86f)
