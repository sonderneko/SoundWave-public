package xyz.cncnlp.soundwave

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.dockGlass

/** 迷你条 / 底栏统一高度。 */
internal val DockItemH = 56.dp
internal val DockCompactMiniH = 52.dp

/** 设计稿 v2 线性图标（i-home / i-library / i-search / i-settings，stroke 1.8）。 */
@Composable
internal fun NavGlyph(
    tab: Tab,
    tint: Color,
    modifier: Modifier = Modifier.size(ThemeTokens.Navigation.Glyph),
    exposeDescription: Boolean = true
) {
    val glyph = when (tab) {
        Tab.HOME -> DesignLineGlyphs.Home
        Tab.PLAYLISTS -> DesignLineGlyphs.Library
        Tab.SEARCH -> DesignLineGlyphs.Search
        else -> DesignLineGlyphs.Settings
    }
    val desc = when (tab) {
        Tab.HOME -> "首页"
        Tab.PLAYLISTS -> "音乐库"
        Tab.SEARCH -> "搜索"
        else -> "设置"
    }
    Icon(glyph, if (exposeDescription) desc else null, tint = tint, modifier = modifier)
}

@Composable
private fun NavPressTarget(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) ThemeTokens.Navigation.Motion.PressedScale else 1f, animationSpec = tween(ThemeTokens.Navigation.Motion.PressMs), label = "navPress")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * 底栏：默认 = 设计稿 .mobile-nav[data-nav-layout=concept]（通栏白底、顶部 1dp 分隔线、四个线性图标+9sp 标签，
 * 背景延伸到系统导航栏下方直达屏幕底边）；"capsule" = data-nav-layout=project（胶囊条 + 独立圆形搜索钮，仅选中项显示标签）。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun BottomNav(
    tab: Tab,
    primary: Color = MaterialTheme.colorScheme.primary,
    dark: Boolean = true,
    themeMode: String = "dark",
    style: String = "default",
    /** r658d：通栏磨砂时把迷你条与底栏之间的间隙并入底栏同一块玻璃（原先间隙单独一块 模糊区，看起来像两层背景）。 */
    glassTopExtend: androidx.compose.ui.unit.Dp = 0.dp,
    onTab: (Tab) -> Unit
) {
    val designScheme = soundwaveDesignScheme(dark)
    // r657：底栏 / 胶囊组 / 搜索圆钮只读「底栏与迷你条磨砂」开关：关 = 实色表面，开 = 半透明磨砂（≥80% 不透明）；
    // 页面「透明磨砂背景」永远不改到这里（回归：v4 原型胶囊组完全透明）。
    val outline = designScheme.outlineVariant
    val selectedTint = designScheme.primary
    val idleTint = designScheme.onSurfaceVariant
    // r655：胶囊选中项图标在浅/深色下均为白色（取设计稿浅色表面白，避免硬编码颜色）。
    val capsuleSelectedIcon = soundwaveDesignScheme(false).surface
    fun label(item: Tab) = when (item) {
        Tab.HOME -> "首页"
        Tab.PLAYLISTS -> "音乐库"
        Tab.SEARCH -> "搜索"
        else -> "设置"
    }
    if (style == "capsule") {
        // r654：圆角胶囊按副分支（feat/neumorphic-ui-refresh）重做——无描边胶囊承载 首页·音乐库·设置（仅图标，
        // 选中项为主色实心圆底 + 白色图标），右侧独立圆形搜索钮（新拟态表面）；无边线，磨砂不透底。
        // r655：选中项图标改为白色。r656：胶囊与搜索钮高度 = 胶囊迷你条高度（DockMetrics.CapsuleNavBodyHeight）。
        val pillShape = RoundedCornerShape(DockMetrics.CapsuleNavBodyHeight / 2)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = DockMetrics.SideInset, end = DockMetrics.SideInset, bottom = ThemeTokens.Navigation.Capsule.BottomPadding)
                .height(DockMetrics.CapsuleNavBodyHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // r657 第二轮：按原型 .capbar——选中项是加宽（flex 1.25）的珊瑚渐变胶囊（图标 + 13sp 粗体标签、珊瑚投影），
            // 切换时新胶囊 scaleX .6→1.12→1 + 淡入（520ms ease-out）；选中「搜索」时右侧圆钮变珊瑚渐变并 .85→1 弹一下。
            val capPop = rememberNavSwitchPop(tab, listOf(Tab.HOME, Tab.PLAYLISTS, Tab.SETTINGS).indexOf(tab).coerceAtLeast(0))
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .cssShadow(pillShape, designScheme.onSurface.copy(alpha = xyz.cncnlp.soundwave.ui.common.ShadowScale.DockPillAlpha), xyz.cncnlp.soundwave.ui.common.ShadowScale.DockPillBlur, xyz.cncnlp.soundwave.ui.common.ShadowScale.DockPillOffsetY)
                    .dockGlass(pillShape, designScheme.surface, dark, outline)
                    .padding(ThemeTokens.Navigation.Capsule.ContentPadding),
                horizontalArrangement = Arrangement.spacedBy(ThemeTokens.Navigation.Capsule.ItemGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(Tab.HOME, Tab.PLAYLISTS, Tab.SETTINGS).forEach { item ->
                    val selected = tab == item
                    NavPressTarget(
                        onClick = { onTab(item) },
                        modifier = Modifier.weight(if (selected) ThemeTokens.Navigation.Capsule.SelectedWeight else 1f).fillMaxHeight()
                            .then(if (selected) Modifier.graphicsLayer { scaleX = capPop.scaleX(); alpha = capPop.alpha() } else Modifier)
                            .then(if (selected) Modifier.cssShadow(CircleShape, NavCoral.copy(alpha = 0.40f), 18.dp, 8.dp) else Modifier)
                            .clip(CircleShape)
                            .then(if (selected) Modifier.background(NavCoralGradient) else Modifier)
                            .semantics { contentDescription = label(item) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavGlyph(item, if (selected) capsuleSelectedIcon else idleTint, Modifier.size(ThemeTokens.Navigation.Glyph), exposeDescription = false)
                            if (selected) {
                                Spacer(Modifier.width(ThemeTokens.Navigation.Capsule.LabelGap))
                                Text(label(item), color = capsuleSelectedIcon, fontSize = ThemeTokens.Navigation.Capsule.LabelSize, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(ThemeTokens.Navigation.Capsule.SearchGap))
            val searchOn = tab == Tab.SEARCH
            NavPressTarget(
                onClick = { onTab(Tab.SEARCH) },
                modifier = Modifier.size(DockMetrics.CapsuleNavBodyHeight)
                    .then(if (searchOn) Modifier.graphicsLayer { val k = capPop.searchScale(); scaleX = k; scaleY = k } else Modifier)
                    .cssShadow(CircleShape, if (searchOn) NavCoral.copy(alpha = 0.40f) else designScheme.onSurface.copy(alpha = 0.08f), if (searchOn) 18.dp else 24.dp, 8.dp)
                    .then(if (searchOn) Modifier.clip(CircleShape).background(NavCoralGradient) else Modifier.dockGlass(CircleShape, designScheme.surface, dark, outline))
                    .semantics { contentDescription = "搜索" }
            ) {
                NavGlyph(Tab.SEARCH, if (searchOn) capsuleSelectedIcon else selectedTint, Modifier.size(ThemeTokens.Navigation.Glyph), exposeDescription = false)
            }
        }
        return
    }
    // concept：通栏。背景（94% 表面色）+ 顶部分隔线一直铺到系统导航栏下方，无底部外距。
    Column(
        Modifier.fillMaxWidth()
            // r654：去掉顶部分隔线；磨砂不透底（不透明表面色，背后内容不再透出）。
            .dockGlass(androidx.compose.ui.graphics.RectangleShape, designScheme.surface, dark, Color.Transparent)
            .navigationBarsPadding()
    ) {
        if (glassTopExtend > 0.dp) androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(glassTopExtend))
        // r657 第二轮：按原型 renderDock——选中项（珊瑚图标 + 粗体标签，无底色 #17）从上一个选中位置滑到新位置
        // （translateX 520ms 弹簧曲线 cubic(.34,1.56,.64,1)），图标同时 .82→1（420ms）；首帧与简洁动效下不动。
        val classicTabs = listOf(Tab.HOME, Tab.PLAYLISTS, Tab.SEARCH, Tab.SETTINGS)
        val slide = rememberNavSwitchPop(tab, classicTabs.indexOf(tab))
        Row(
            Modifier.fillMaxWidth().height(DockMetrics.BarNavHeight).padding(horizontal = ThemeTokens.Navigation.Classic.HorizontalPadding),
            verticalAlignment = Alignment.Top
        ) {
            classicTabs.forEach { item ->
                val selected = tab == item
                val tint = if (selected) selectedTint else idleTint
                NavPressTarget(
                    onClick = { onTab(item) },
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label(item) }
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(top = ThemeTokens.Navigation.Classic.TopPadding)
                            .then(if (selected) Modifier.graphicsLayer { translationX = slide.slideCells() * size.width } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NavGlyph(item, tint, Modifier.size(width = ThemeTokens.Navigation.Classic.GlyphWidth, height = ThemeTokens.Navigation.Classic.GlyphHeight)
                            .then(if (selected) Modifier.graphicsLayer { val k = slide.iconScale(); scaleX = k; scaleY = k } else Modifier), exposeDescription = false)
                        Spacer(Modifier.height(ThemeTokens.Navigation.Classic.LabelGap))
                        Text(
                            label(item), color = tint, fontSize = ThemeTokens.Navigation.Classic.LabelSize, lineHeight = ThemeTokens.Navigation.Classic.LabelLineHeight, fontFamily = DesignFonts.Manrope,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1,
                            style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
        }
    }
}

/** 原型 SPRING = cubic-bezier(.34,1.56,.64,1)、EOUT = cubic-bezier(.2,.8,.2,1)。 */
internal val NavSpringEasing = ThemeTokens.Navigation.Motion.Spring
internal val NavOutEasing = ThemeTokens.Navigation.Motion.Out
internal val NavCoral = SoundwaveDesignPalette.Accent
/** 原型 radial-gradient(120% 160% at 30% 0%, #FF8A73, #FF5C45 55%, #E8452F)。 */
internal val NavCoralGradient: Brush = object : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): androidx.compose.ui.graphics.Shader =
        androidx.compose.ui.graphics.RadialGradientShader(
            center = Offset(size.width * 0.3f, 0f), radius = kotlin.math.max(size.width * 1.2f, size.height * 1.6f).coerceAtLeast(1f),
            colors = listOf(SoundwaveDesignPalette.AccentLight, SoundwaveDesignPalette.Accent, SoundwaveDesignPalette.AccentDeep), colorStops = listOf(0f, 0.55f, 1f)
        )
}

/**
 * 底栏切换动效（只在绘制阶段读取）。SW-12-07：指示位置由 [position] Animatable 以 Tab 序号为单位
 * 持有当前视觉位置，切 Tab 时从当前值 spring 到目标（可打断、无 snapTo 闪回）；[icon] 仍驱动图标 /
 * 搜索圆钮的瞬时回弹。首次组合静止于目标位置；「简洁动效」下 position 走 NAV_REDUCED_MS tween。
 */
internal class NavSwitchPop(
    private val position: Animatable<Float, *>,
    private val icon: Animatable<Float, *>,
    private val reduced: () -> Boolean = { false },
    private val targetIndex: () -> Float
) {
    private fun spring(p: Float): Float = if (reduced()) NavOutEasing.transform(p) else NavSpringEasing.transform(p)
    /** 经典：选中项相对其格位的横移（单位 = 一个 Tab 宽度）= 视觉位置 − 目标序号，过冲时自然越过格位。 */
    fun slideCells(): Float = position.value - targetIndex()
    /** 「简洁动效」：图标只做 0.94 → 1 的轻微回弹（无过冲）。 */
    fun iconScale(): Float = if (reduced()) 0.94f + 0.06f * spring(icon.value.coerceIn(0f, 1f)) else 0.82f + 0.18f * spring(icon.value.coerceIn(0f, 1f))
    /** 胶囊接近进度：距目标最后一格内 0→1（ease-out），远距离保持 0；与旧版 0→1 进度观感衔接。 */
    private fun capsuleProgress(): Float = NavOutEasing.transform((1f - kotlin.math.abs(position.value - targetIndex()).coerceIn(0f, 1f)))
    /** 胶囊：scaleX .6 → 1.12（60%）→ 1（关键帧由接近进度驱动）；简洁动效下 .9 → 1、无过冲。 */
    fun scaleX(): Float = capsuleProgress().let { p -> if (reduced()) 0.9f + 0.1f * p else navCapsuleScaleX(p) }
    fun alpha(): Float = 0.4f + 0.6f * (capsuleProgress() / 0.6f).coerceAtMost(1f)
    fun searchScale(): Float = if (reduced()) 0.94f + 0.06f * spring(icon.value.coerceIn(0f, 1f)) else 0.85f + 0.15f * spring(icon.value.coerceIn(0f, 1f))
}

/** 「简洁动效」下底栏切换时长（只减弱不取消）：横移 / 胶囊 200ms，图标 160ms。 */
internal const val NAV_REDUCED_MS = ThemeTokens.Navigation.Motion.ReducedMs
internal const val NAV_REDUCED_ICON_MS = ThemeTokens.Navigation.Motion.ReducedIconMs

/** 纯函数（单测覆盖）：胶囊选中底 scaleX 关键帧。 */
internal fun navCapsuleScaleX(p: Float): Float = if (p < 0.6f) 0.6f + (1.12f - 0.6f) * (p / 0.6f) else 1.12f + (1f - 1.12f) * ((p - 0.6f) / 0.4f)

@Composable
internal fun rememberNavSwitchPop(tab: Tab, index: Int = 0): NavSwitchPop {
    val reduce = MotionPolicy.rememberReduceMotion()
    // SW-12-07：position 以 Tab 序号为单位持有当前视觉位置；切 Tab 时 LaunchedEffect 被新一轮取代，
    // 正在进行的 animateTo 随之取消，新动画从 Animatable 的当前值起步——连点不闪回、天然可打断可反向。
    val position = androidx.compose.runtime.remember { Animatable(index.toFloat()) }
    val icon = androidx.compose.runtime.remember { Animatable(1f) }
    val first = androidx.compose.runtime.remember { booleanArrayOf(true) }
    val targetState = androidx.compose.runtime.rememberUpdatedState(index)
    androidx.compose.runtime.LaunchedEffect(tab) {
        if (first[0]) { first[0] = false; position.snapTo(index.toFloat()); return@LaunchedEffect }
        kotlinx.coroutines.coroutineScope {
            launch { icon.snapTo(0f); icon.animateTo(1f, tween(if (reduce) NAV_REDUCED_ICON_MS else ThemeTokens.Navigation.Motion.IconMs, easing = androidx.compose.animation.core.LinearEasing)) }
            launch {
                if (reduce) position.animateTo(index.toFloat(), tween(NAV_REDUCED_MS, easing = androidx.compose.animation.core.LinearEasing))
                else position.animateTo(index.toFloat(), MotionTokens.Spring.ExpressiveSpec)
            }
        }
    }
    val reduceState = androidx.compose.runtime.rememberUpdatedState(reduce)
    return androidx.compose.runtime.remember(position, icon) { NavSwitchPop(position, icon, { reduceState.value }) { targetState.value.toFloat() } }
}
