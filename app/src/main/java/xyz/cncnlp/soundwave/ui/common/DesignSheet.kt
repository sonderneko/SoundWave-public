package xyz.cncnlp.soundwave.ui.common

import xyz.cncnlp.soundwave.ThemeTokens.Sheet as SheetTokens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.DefaultLandscapeCover
import xyz.cncnlp.soundwave.SoundwaveDanger
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.normalizeCoverUrl

/*
 * 1.0.655 统一设计抽屉（参考 1000073118「歌曲操作」）：
 *  珊瑚色 DM Mono 英文眉题 → 24sp 粗体标题 → 封面 + 标题 / 副标题条目头 → 56dp 选项行（左统一线性图标、
 *  15sp 粗体标题、右侧 12sp 灰色说明），行间 1dp 分隔线。
 * 所有分隔线 / 描边统一使用首页顶部切换按钮的线框色（[designLineColor]：日间 #D8D9D2、夜间 #323730），
 * 不再跟随旧主题 outline（偏蓝的 0x332A5CAA）。
 * 详情页、今日推荐、音乐库等的「⋯」弹窗共用；其它模块（设置 / 播放器）可直接复用。
 */

/** 首页顶部切换按钮的线框色（.icon-btn border）；所有设计弹窗分隔线、搜索框外描边、取消按钮描边共用。 */
@Composable
fun designLineColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line

/**
 * ACCEPT6：弹层状态面配色——非错误态禁止再走 colorScheme.primaryContainer：根主题未定义该
 * 角色、落 Material 默认薰衣草紫 #EADDFF + 深紫字 #21005D（用户 2026-11-04 晚真机截图点名，
 * 同验收二轮 E4 Loading 徽先例）。改设计调色板 AccentSoft（明暗随背景亮度切 NightAccentSoft），
 * 字色 Ink/NightInk——与设计主题对 primaryContainer 的映射同值，哪套主题包裹都同色。
 * 错误态保留主题错误色对（红系，非紫蓝）。
 */
@Composable
fun designStatusContainerColor(isError: Boolean): Color =
    if (isError) MaterialTheme.colorScheme.errorContainer
    else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SoundwaveDesignPalette.NightAccentSoft
    else SoundwaveDesignPalette.AccentSoft

@Composable
fun designStatusContentColor(isError: Boolean): Color =
    if (isError) MaterialTheme.colorScheme.onErrorContainer
    else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SoundwaveDesignPalette.NightInk
    else SoundwaveDesignPalette.Ink

/**
 * J 波：DesignSheetDialog 的动画关闭句柄下发给弹层内容。此前 close()（220ms 退场后才回调
 * onDismiss）只被遮罩点击/拖拽/返回三路触发，内容里的选项点击普遍直接调用 onDismiss 或由
 * 调用方在 onPick 里清状态，组合即刻移除、退场被跳过（用户点名「点选项后瞬间消失」）。
 * 内容应改用 [rememberDesignSheetDismiss] 取得此句柄：执行自身动作后调用它，退场走完才触发
 * onDismiss 清状态。不在 DesignSheetDialog 内时为 null，helper 回退调用方给的原回调。
 */
val LocalDesignSheetDismiss = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** 取得当前弹层的动画关闭句柄；不在 DesignSheetDialog 内时回退 [fallback]。 */
@Composable
fun rememberDesignSheetDismiss(fallback: () -> Unit): () -> Unit =
    LocalDesignSheetDismiss.current ?: fallback

/**
 * 运行时 Dialog 外壳：38% 黑色遮罩，点空白关闭，内容贴底。
 * r657 修复（审计第 19 项）/ r658c D：进场 420ms emphasized-decelerate（自屏幕底边 translateY(100%) 滑入停在底边，遮罩同步淡入），
 * 出场 220ms 后才真正关闭；API 31+ 背后窗口 3dp 模糊（blurBehind）。「简洁动效」时无位移、无延迟。
 */
@Composable
fun DesignSheetDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, content = content)

/** Opt-in scrolling avoids giving existing LazyColumn/weighted consumers infinite height. */
@Composable
fun DesignSheetDialog(
    onDismiss: () -> Unit,
    scrollContent: Boolean,
    dismissEnabled: Boolean = true,
    /** M 波（ISSUE-SONGMENU §4.1）：面板高度封顶（占可用视口高的比例）；null = 不封顶（其余弹层现状）。 */
    maxHeightFraction: Float? = null,
    /** M 波：内容自理内滚时，把内滚列表的 ScrollState 交回面板，使拖拽关闭的 nestedScroll 连接改绑该 state（hoist）。 */
    contentScrollState: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val enter = remember { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var closing by remember { androidx.compose.runtime.mutableStateOf(false) }
    val close: () -> Unit = {
        if (!dismissEnabled) Unit
        else if (reduce) onDismiss()
        else if (!closing) {
            closing = true
            scope.launch { enter.animateTo(0f, androidx.compose.animation.core.tween(SheetMotion.EXIT_MS, easing = androidx.compose.animation.core.FastOutLinearInEasing)); onDismiss() }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // r658f：动画被打断时也落到终点，面板不会停在半途（底部露缝）。
        if (!reduce) try { enter.animateTo(1f, androidx.compose.animation.core.tween(SheetMotion.ENTER_MS, easing = SheetMotion.Emphasized)) }
            finally { if (!closing) withContext(NonCancellable) { enter.snapTo(1f) } }
    }
    // r658h：应用内覆盖层（AppOverlayDialog）代替平台 Dialog 窗口——vivo Android 16 上 Dialog 窗口始终停在导航栏上方，
    // 抽屉下方露出一截。未安装宿主时 AppOverlayDialog 自动回退为 Dialog(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)。
    AppOverlayDialog(onDismissRequest = close) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            // r658c D：窗口铺满整屏贴底（原型 .sheet bottom:0）。Dialog 窗口默认 fitInsetsTypes=systemBars 且受导航栏限制，
            // 窗口底边停在导航栏上方，面板再 navigationBarsPadding 一次 → 整体被抬高、下方露出遮罩缝。
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w -> applyDesignSheetWindow(w) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    // ACCEPT6 dialogfrost：窗口模糊半径定版常值（与 haze 路径同值、与页面磨砂开关解耦）；
                    // 覆盖层宿主下无 Dialog 窗口，本段不生效，雾化由 SheetFrostBackdrop 承担。
                    w.attributes = w.attributes.apply { blurBehindRadius = (sheetWindowBlurDp() * view.resources.displayMetrics.density).toInt() }
                }
            }
        }
        // Insets belong to the viewport, never the text field or the scrolling panel.
        // IME consumption also prevents the panel's navigationBarsPadding from adding it twice.
        Box(Modifier.fillMaxSize()) {
        // ACCEPT6 dialogfrost：全屏雾化背板——面板之外整片背景与面板同源磨砂（稿面观感），
        // 显现进度与遮罩淡变同步；无真模糊环境下不绘制（近似路径，遮罩单独承担）。
        SheetFrostBackdrop(enter)
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = SheetMotion.SCRIM_ALPHA * enter.value)) }
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = close)
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                androidx.compose.runtime.CompositionLocalProvider(LocalDesignSheetDismiss provides close) {
                DesignSheetPanel(
                    // 原型：translateY(100%) → none（420ms emphasized），出场回到 100%；终点位移恒为 0，面板不淡入。
                    modifier = Modifier.graphicsLayer {
                        translationY = (1f - enter.value) * size.height
                    },
                    scrollContent = scrollContent,
                    // SW-12-03：把手 / 顶部区域拖拽关闭走同一条 close() 退场路径（220ms exit；减少动效时直接关闭）。
                    onDragDismissRequest = close,
                    maxHeightFraction = maxHeightFraction,
                    contentScrollState = contentScrollState,
                    content = content
                )
                }
            }
        }
        }
    }
}

/**
 * r658d：自建 Dialog 的底部抽屉（设置选项、排序等）共用 —— 窗口铺满贴底（不再在导航栏上方露缝）+ 背后模糊。
 * 在 Dialog content 内、DialogEdgeToEdge() 之后调用；面板自身负责 navigationBarsPadding。
 */
@Composable
fun DesignSheetWindowEffect() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        applyDesignSheetWindow(w)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // ACCEPT6 dialogfrost：半径定版常值（见 DesignSheetDialog 同段注）。
            w.attributes = w.attributes.apply { blurBehindRadius = (sheetWindowBlurDp() * view.resources.displayMetrics.density).toInt() }
        }
    }
}

/** 抽屉窗口：全屏宽高、贴底、不避让任何系统栏（insets 交给 Compose：内容 navigationBarsPadding、IME imePadding）。 */
internal fun applyDesignSheetWindow(w: android.view.Window) {
    w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    w.setGravity(android.view.Gravity.BOTTOM)
    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    w.setWindowAnimations(0) // 平台 Dialog 缩放/淡入动画会与面板位移叠加，入场位置跑偏
    w.attributes = w.attributes.apply {
        x = 0; y = 0; verticalMargin = 0f; horizontalMargin = 0f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) { fitInsetsTypes = 0; fitInsetsSides = 0 }
    }
}

/** 底边无描边（原型 .sheet border-bottom:0）：描边轮廓向下多出屏，被面板 clip 截掉，只剩顶部圆角与两侧。 */
private val SheetBorderShape = object : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        RoundedCornerShape(topStart = SheetTokens.Radius, topEnd = SheetTokens.Radius).createOutline(size.copy(height = size.height + with(density) { 4.dp.toPx() }), layoutDirection, density)
}

/**
 * M 波面板高度封顶的几何口径（纯函数，供单测钉死）：fraction 为 null 或父高无界时不封顶；
 * 否则封顶 = floor(父高 × fraction)，且不为负、不超过父高。
 */
internal fun designSheetCappedHeightPx(maxHeightPx: Int, fraction: Float?): Int {
    if (fraction == null || maxHeightPx == androidx.compose.ui.unit.Constraints.Infinity) return maxHeightPx
    return (maxHeightPx * fraction).toInt().coerceIn(0, maxHeightPx)
}

/** 抽屉动效规格（原型 13 / 13b）。时长 / 曲线统一走 [MotionTokens] 单一出口（SW-12-05），数值原样：进 420 / 出 220。 */
object SheetMotion {
    const val ENTER_MS = MotionTokens.Duration.SheetEnter
    const val EXIT_MS = MotionTokens.Duration.Exit
    const val SCRIM_ALPHA = SheetTokens.ScrimAlpha
    val Emphasized = MotionTokens.Easing.EmphasizedDecelerate
}

/** 抽屉面板：左右贴边、顶部 r28、1dp 线框、居中把手；可单独渲染供截图测试。 */
@Composable
fun DesignSheetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    DesignSheetPanel(modifier = modifier, scrollContent = false, content = content)

@Composable
fun DesignSheetPanel(
    modifier: Modifier = Modifier,
    scrollContent: Boolean,
    onDragDismissRequest: (() -> Unit)? = null,
    /** M 波：面板高度封顶（占父约束最大高的比例，几何口径见 [designSheetCappedHeightPx]）；null = 不封顶。 */
    maxHeightFraction: Float? = null,
    /** M 波：内容内滚列表的 ScrollState（hoist）——拖拽关闭连接改绑此 state；null 时绑面板自身 scrollState（现状）。 */
    contentScrollState: androidx.compose.foundation.ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val line = designLineColor()
    val shape = RoundedCornerShape(topStart = SheetTokens.Radius, topEnd = SheetTokens.Radius)
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val scrollState = rememberScrollState()
    // SW-12-03 把手拖拽关闭：dragOffset 只在绘制阶段（graphicsLayer）读取，不触发重组；独立渲染（截图测试等，
    // onDragDismissRequest == null）时不启用拖拽，行为与旧版一致。
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val currentDismiss by androidx.compose.runtime.rememberUpdatedState(onDragDismissRequest)
    var panelHeightPx by remember { androidx.compose.runtime.mutableStateOf(0) }
    var pastThreshold by remember { androidx.compose.runtime.mutableStateOf(false) }
    val dismissVelocityPx = with(density) { 1000.dp.toPx() }
    fun applyDrag(deltaPx: Float) {
        if (currentDismiss == null) return
        val target = (dragOffset.value + deltaPx).coerceAtLeast(0f)
        scope.launch { dragOffset.snapTo(target) }
        val crossed = panelHeightPx > 0 && target > panelHeightPx * 0.25f
        if (crossed != pastThreshold) {
            // SW-12-03 / B.3：越过关闭阈值震一次，退回阈值以内再震一次（deactivate），单手势每次 crossing 只触发一次。
            AppHaptics.thresholdCrossed(view, crossed)
        }
        pastThreshold = crossed
    }
    fun settleDrag(velocityPx: Float) {
        val dismiss = currentDismiss ?: return
        val past = panelHeightPx > 0 && dragOffset.value > panelHeightPx * 0.25f
        when {
            // 位移 > 面板高 25% 或下甩速度 > 1000dp/s → 关闭（走 Dialog 的 220ms exit；减少动效时 Dialog 直接关闭）。
            past || velocityPx > dismissVelocityPx -> dismiss()
            reduce -> scope.launch { dragOffset.snapTo(0f) } // 减少动效：无回弹，直接归位
            else -> scope.launch { dragOffset.animateTo(0f, MotionTokens.Spring.DefaultSpatialSpec) }
        }
    }
    // scrollContent=true：内容滚到顶后才拖面板（nestedScroll：向上拖先归位面板，向下拖的剩余位移交给面板）。
    // M 波：scrollContent=false + 内容自理内滚时，连接改绑内滚列表的 state（contentScrollState），
    // 「列表滚到顶后继续下拉关弹层」语义与面板自滚时完全一致；连接本体仍挂在面板 Column 上，事件由子级滚动体冒泡。
    val nestedScrollState = contentScrollState ?: scrollState
    val sheetNestedScroll = remember(nestedScrollState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (currentDismiss == null || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                if (available.y < 0f && dragOffset.value > 0f) {
                    val consume = minOf(-available.y, dragOffset.value)
                    applyDrag(-consume)
                    return androidx.compose.ui.geometry.Offset(0f, -consume)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (currentDismiss == null || source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                if (available.y > 0f && nestedScrollState.value == 0) {
                    applyDrag(available.y)
                    return available
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (currentDismiss != null && dragOffset.value > 0f) settleDrag(available.y)
                return super.onPostFling(consumed, available)
            }
        }
    }
    // M 波高度封顶：以布局约束钳制面板最大高（内容不足时仍 hug）；进出场位移与拖拽阈值都以钳制后高度为准。
    val heightCap = if (maxHeightFraction != null) Modifier.layout { measurable, constraints ->
        val capped = designSheetCappedHeightPx(constraints.maxHeight, maxHeightFraction)
        val placeable = measurable.measure(constraints.copy(minHeight = minOf(constraints.minHeight, capped), maxHeight = capped))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    } else Modifier
    // ACCEPT6 dialogfrost：面板磨砂按宿主分路（台账登记）——覆盖层内 = haze 真模糊玻璃背板
    // （Glass.kt sheetPanelGlass，常开、与页面磨砂开关解耦）；平台 Dialog 窗口内 = 半透明填充，
    // 模糊由窗口 blurBehind 承担（系统关闭跨窗模糊时回近不透明）；页内独立渲染（如
    // DetailDesignPages 的 DesignSheetSurface 调用面）= 保持既有 frostedSheetFill 语义不改。
    val inOverlay = LocalInAppOverlay.current
    val panelView = androidx.compose.ui.platform.LocalView.current
    var inDialogWindow by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.SideEffect {
        inDialogWindow = (panelView.parent as? androidx.compose.ui.window.DialogWindowProvider) != null
    }
    val (glassBlur, glassTintAlpha) = sheetGlassPlan()
    val panelBorderColor = when {
        inOverlay && glassBlur -> line.copy(alpha = SheetTokens.FrostedBorderAlpha)
        inDialogWindow && CrossWindowBlur.enabled -> line.copy(alpha = SheetTokens.FrostedBorderAlpha)
        !inOverlay && !inDialogWindow && FrostedPrefs.pageActive -> line.copy(alpha = SheetTokens.FrostedBorderAlpha)
        else -> line
    }
    Box(
        modifier.then(heightCap).fillMaxWidth()
            .onSizeChanged { panelHeightPx = it.height }
            .graphicsLayer { translationY = dragOffset.value }
    ) {
        // 面板背板层：形状 + 阴影 + 磨砂绘制 + 边框；内容列透明叠于其上。
        Box(
            Modifier.matchParentSize()
                .shadow(SheetTokens.Shadow, shape, ambientColor = Color.Black.copy(alpha = SheetTokens.ShadowAlpha), spotColor = Color.Black.copy(alpha = SheetTokens.ShadowAlpha))
                .then(
                    when {
                        inOverlay -> Modifier.sheetPanelGlass(shape, colors.surface)
                        inDialogWindow -> Modifier.background(colors.surface.copy(alpha = if (CrossWindowBlur.enabled) glassTintAlpha else GLASS_FALLBACK_ALPHA), shape)
                        else -> Modifier.background(frostedSheetFill(colors.surface), shape)
                    }
                )
                .border(SheetTokens.Border, panelBorderColor, SheetBorderShape)
        )
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .navigationBarsPadding()
                .then(if (onDragDismissRequest != null) Modifier.nestedScroll(sheetNestedScroll) else Modifier)
                .then(if (scrollContent) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(start = SheetTokens.HorizontalPadding, end = SheetTokens.HorizontalPadding, top = SheetTokens.TopPadding, bottom = SheetTokens.BottomPadding)
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(SheetTokens.HandleWidth, SheetTokens.HandleHeight).clip(RoundedCornerShape(SheetTokens.HandleRadius)).background(line))
            Spacer(Modifier.height(SheetTokens.HeaderGap))
            content()
        }
        // 拖拽热区：覆盖面板顶部（上内边距 + 把手 + 标题间隔 = 50dp），不改变任何布局数值；把手视觉尺寸仍为 46×4。
        if (onDragDismissRequest != null) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .height(SheetTokens.TopPadding + SheetTokens.HandleHeight + SheetTokens.HeaderGap)
                    .draggable(
                        state = rememberDraggableState { delta -> applyDrag(delta) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> settleDrag(velocity) }
                    )
            )
        }
    }
}

/**
 * ACCEPT6 dialogfrost：表内 pinned 顶栏带（歌曲操作 / 播放路径等带钉顶标题的表）——标题块覆盖在内滚区之上：
 * 静止时全透、与表身无缝；内容滚入带下时按滚动进度呈磨砂（与表磨砂同源同值，机制与详情页
 * scrolledHeaderGlass 同法：局部模糊源 + 进度驱动），带底缘出 ∝ 进度的发丝线；滚回顶部恢复无缝。
 * 滚动读数只在本组件内订阅 [scrollState]，不牵动表身重组。
 */
@Composable
fun DesignSheetPinnedHeader(
    scrollState: androidx.compose.foundation.ScrollState,
    glassState: LocalGlassState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val progress = sheetHeaderGlassProgress(scrollState.value, with(density) { 24.dp.toPx() })
    Box(
        modifier.fillMaxWidth()
            .sheetHeaderGlass(glassState, MaterialTheme.colorScheme.surface, progress, designLineColor())
    ) { content() }
}

/**
 * 眉题（珊瑚色等宽大字距英文）+ 大标题。
 * 自包含 Column：本组件曾把眉题/间距/标题作为三个兄弟节点发射、依赖父容器是 Column 才能上下排列；
 * 多处调用点为挂 heading() 语义把它包进 Box，兄弟节点在 Box 中叠在同一原点 → 眉题被标题压住穿过
 * （dialog-polish 波修复，用户真机截图实证）。内部收 Column 后父容器是 Box/Column/Row 均为单一子节点，
 * 调用点无需再关心布局契约。
 */
@Composable
fun DesignSheetHeader(eyebrow: String, title: String) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(
            eyebrow.uppercase(), color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = SheetTokens.EyebrowSize, lineHeight = SheetTokens.EyebrowLineHeight,
            fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.18.em, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(SheetTokens.TitleGap))
        Text(title, color = colors.onSurface, fontSize = SheetTokens.TitleSize, lineHeight = SheetTokens.TitleLineHeight, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 条目头：60dp 圆角封面 + 17sp 粗体标题 + 12sp 灰色副标题。上下距可调（默认 22/18 家族规格不变；M 波歌曲操作弹层传 16/12 收紧）。 */
@Composable
fun DesignSheetItemHeader(cover: String, title: String, subtitle: String, coverSize: Dp = 60.dp, badge: String = "", topPadding: Dp = 22.dp, bottomPadding: Dp = 18.dp, trailing: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(top = topPadding, bottom = bottomPadding), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(coverSize).clip(RoundedCornerShape(15.dp)).background(colors.surfaceVariant)) {
            val c = remember(cover) { normalizeCoverUrl(cover) }
            // ACCEPT5 收编：占位常驻垫底——加载/失败不再是 surfaceVariant 平灰。
            DefaultLandscapeCover(Modifier.fillMaxSize())
            if (c.isNotBlank()) AsyncImage(CacheManager.coverModel(context, c) ?: c, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 15.dp)) {
            Text(title, color = colors.onSurface, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(
                subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp)
            )
        }
        // r657：原型表头右侧珊瑚「无损」小标（仅在已解析到无损音质时出现）。
        if (badge.isNotBlank()) Box(
            Modifier.padding(start = 10.dp).height(18.dp).clip(RoundedCornerShape(5.dp))
                .background(xyz.cncnlp.soundwave.SoundwaveDesignPalette.Accent.copy(alpha = 0.12f)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(badge, color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        // r658c P：调用方右侧附加控件（播放器「⋯」的播放顺序圆钮）。
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

/** 选项行：顶部 1dp 分隔线；左 21dp 图标 + 15sp 粗体标题，右侧 12sp 说明。行高可调（默认 56dp 家族规格不变；M 波歌曲操作弹层传 52dp）。 */
@Composable
fun DesignSheetRow(icon: ImageVector, label: String, hint: String = "", danger: Boolean = false, enabled: Boolean = true, height: Dp = 56.dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fg = if (danger) SoundwaveDanger else colors.onSurface
    Column(Modifier.fillMaxWidth()) {
        FrostDivider(designLineColor()) // r657 第二轮：磨砂背景开时隐藏分隔线
        Row(
            Modifier.fillMaxWidth().height(height).pressScale(PressMotion.SCALE_ROW, enabled = enabled).rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.22f), enabled = enabled).clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .semantics { contentDescription = label; role = Role.Button }
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = fg.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(18.dp))
            Text(
                label, color = fg.copy(alpha = if (enabled) 1f else 0.38f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (hint.isNotBlank()) Text(
                hint, color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope,
                letterSpacing = 0.04.em, maxLines = 1, modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}
