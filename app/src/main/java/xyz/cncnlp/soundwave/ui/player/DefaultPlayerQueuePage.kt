package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.PlayMode
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.common.cssShadow
import xyz.cncnlp.soundwave.ui.common.pressScale

private val DefaultQueueRowHeight = 66.dp // r657 第三轮：原型 .sheet .row 66dp

/**
 * 「默认」播放器队列（r657 按原型 11c 改为底部抽屉）：上方露出播放器 + 38% 遮罩（点按关闭），
 * 抽屉顶部小白条（下拉 >120dp 关闭）→ 珊瑚眉题「UP NEXT · N 首」+ 大标题「播放队列」+ 右侧 定位 / 清空 圆钮（r658c P 去掉「来源歌单 / 播放来源」入口）→
 * 顺序播放 / 随机 / 单曲循环 分段控件（直接设定播放顺序）→ 列表：当前曲目「律动柱 正在播放 · 歌手 · 专辑」，
 * 其余点按播放、× 移出队列、右侧把手拖动排序；不显示曲目序号（用户要求）。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun DefaultPlayerQueuePage(
    state: DefaultPlayerState, actions: DefaultPlayerActions, listState: LazyListState, modifier: Modifier = Modifier,
    enterScope: AnimatedVisibilityScope? = null
) {
    // 原型：遮罩淡入 220ms；抽屉 420ms emphasized-decelerate 上滑进场、220ms 下滑退场。
    val scrimAnim = enterScope?.run { Modifier.animateEnterExit(fadeIn(tween(220)), fadeOut(tween(200))) } ?: Modifier
    val sheetAnim = enterScope?.run {
        Modifier.animateEnterExit(slideInVertically(tween(420, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))) { it }, slideOutVertically(tween(220)) { it })
    } ?: Modifier
    val colors = defaultPlayerColors()
    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val density = LocalDensity.current
    val closePx = with(density) { 120.dp.toPx() }
    // r658c P：与公共 DesignSheet 同一行为——面板与遮罩延伸到屏幕底边（越过导航栏），列表内容再让出导航栏高度；底边无描边。
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    val statusTopPx = WindowInsets.statusBars.getTop(density)
    val navBottom = with(density) { navBottomPx.toDp() }
    var pull by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { if (state.queueIndex > 0) listState.scrollToItem(state.queueIndex) }
    // ACCEPT5-covergate：队列打开（及队列/当前曲目变化）时有界预热封面——当前播放邻域
    // 优先 + 队列头补齐、上限 24 首、144px 行图口径；走共享预取池 UI 低优先通道，
    // 只在本抽屉组合期间触发，关抽屉即随组合取消未开工的预热。
    val prewarmContext = LocalContext.current
    LaunchedEffect(state.queue, state.queueIndex) {
        CacheManager.ensureQueueCovers(prewarmContext, state.queue, state.queueIndex)
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.matchParentSize().queueSheetBleed(statusTopPx, navBottomPx).then(scrimAnim).background(Color.Black.copy(alpha = 0.38f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = actions.onCloseQueue)
                .semantics { contentDescription = "关闭播放队列" }
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.76f).queueSheetBleed(0, navBottomPx).then(sheetAnim)
                .graphicsLayer { translationY = pull }
                .cssShadow(sheetShape, Color.Black.copy(alpha = if (colors.dark) 0.5f else 0.16f), 30.dp, (-4).dp)
                .clip(sheetShape).background(colors.background)
                .border(1.dp, colors.line, QueueSheetBorderShape)
        ) {
            Column(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (pull > closePx) actions.onCloseQueue(); pull = 0f },
                        onDragCancel = { pull = 0f }
                    ) { change, dy -> change.consume(); pull = (pull + dy).coerceAtLeast(0f) }
                }.padding(horizontal = 20.dp)
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(99.dp)).background(colors.muted.copy(alpha = 0.35f)))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DefaultPlayerEyebrow("UP NEXT · ${state.queueSize} 首", colors.accentText, size = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("播放队列", color = colors.ink, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
                    }
                    DefaultPlayerCircleButton(DefaultPlayerGlyphs.Locate, "定位当前播放歌曲", colors, size = 40.dp, iconSize = 18.dp, filled = true, onClick = actions.onLocateCurrent)
                    Spacer(Modifier.width(8.dp))
                    DefaultPlayerCircleButton(DefaultPlayerGlyphs.Trash, "清空播放队列（保留当前歌曲）", colors, size = 40.dp, iconSize = 18.dp, filled = true, onClick = actions.onClearQueue)
                }
                Spacer(Modifier.height(14.dp))
                DefaultQueueOrderSegment(state.playMode, colors, actions.onSetMode)
                Spacer(Modifier.height(8.dp))
            }
            if (state.queue.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = navBottom), contentAlignment = Alignment.Center) {
                    Text("播放队列为空", color = colors.muted, fontSize = 14.sp, fontFamily = DesignFonts.Manrope)
                }
            } else {
                DefaultQueueList(state, actions, listState, colors, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), bottomInset = navBottom)
            }
        }
    }
}

/**
 * 纵向出血：按 top / bottom 像素放大测量高度，但向父级只报告原高度（向上偏移 top），
 * 让面板 / 遮罩本身铺到状态栏与导航栏下方（同 DesignSheetDialog 的全屏窗口贴底），不是额外的背景遮盖层。
 */
private fun Modifier.queueSheetBleed(topPx: Int, bottomPx: Int): Modifier = if (topPx <= 0 && bottomPx <= 0) this else layout { measurable, constraints ->
    val extra = topPx + bottomPx
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = if (constraints.hasBoundedHeight) (constraints.minHeight + extra) else constraints.minHeight,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight
        )
    )
    layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(0, -topPx) }
}

/** 底边无描边（原型 .sheet border-bottom:0）：描边轮廓向下多出 4dp，被面板 clip 截掉。 */
private val QueueSheetBorderShape = object : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density) =
        RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp).createOutline(size.copy(height = size.height + with(density) { 4.dp.toPx() }), layoutDirection, density)
}

/** 原型分段控件：顺序播放 / 随机 / 单曲循环；选中段为白色胶囊滑块（弹簧位移），心动模式时三段都不选中。 */
@Composable
private fun DefaultQueueOrderSegment(mode: PlayMode, colors: DefaultPlayerColors, onSetMode: (PlayMode) -> Unit) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val options = listOf(PlayMode.SEQUENCE to "顺序播放", PlayMode.SHUFFLE to "随机", PlayMode.LOOP_ONE to "单曲循环")
    val selected = options.indexOfFirst { it.first == mode }
    val reduceMotion = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val slide by animateFloatAsState(
        selected.coerceAtLeast(0).toFloat(),
        if (reduceMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 520f), label = "queueOrderSlide"
    )
    val track = RoundedCornerShape(99.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(44.dp).clip(track).background(colors.ink.copy(alpha = if (colors.dark) 0.10f else 0.05f)).padding(4.dp)
    ) {
        val segW = maxWidth / options.size
        if (selected >= 0) {
            Box(
                Modifier.offset(x = segW * slide).size(width = segW, height = maxHeight)
                    .cssShadow(track, Color.Black.copy(alpha = 0.10f), 8.dp, 2.dp)
                    .clip(track).background(if (colors.dark) colors.surface2 else Color.White)
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (m, label) ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(track).clickable { if (!on) { xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(pressHapticView); onSetMode(m) } }
                        .semantics { contentDescription = "播放顺序：$label" + if (on) "，已选中" else ""; role = Role.Tab },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (on) colors.ink else colors.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                        fontFamily = DesignFonts.Manrope, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun DefaultQueueList(state: DefaultPlayerState, actions: DefaultPlayerActions, listState: LazyListState, colors: DefaultPlayerColors, modifier: Modifier, bottomInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val queue = state.queue
    // 重复歌曲按出现次序区分，保证 key 唯一且排序时稳定。
    val keys = remember(queue) {
        val seen = HashMap<String, Int>()
        queue.map { s -> val k = s.key(); val n = seen.getOrDefault(k, 0); seen[k] = n + 1; "$k#$n" }
    }
    val latestKeys by rememberUpdatedState(keys)
    val latestMove by rememberUpdatedState(actions.onQueueMove)
    val latestRemove by rememberUpdatedState(actions.onQueueRemove)
    val latestInsert by rememberUpdatedState(actions.onQueueInsert)
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val reduceMotion = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    val rowPx = with(LocalDensity.current) { DefaultQueueRowHeight.toPx() }
    val scope = rememberCoroutineScope()
    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(bottom = 24.dp + bottomInset)) {
        itemsIndexed(queue, key = { i, _ -> keys.getOrElse(i) { "i$i" } }) { index, song ->
            val key = keys.getOrElse(index) { "i$index" }
            // SW-12-02（G 波）：队列拖拽排序起止触感。
            val hapticView = androidx.compose.ui.platform.LocalView.current
            val current = index == state.queueIndex
            val dragging = key == draggingKey
            // r657 修复（审计第 12 项）：移出队列先播放粒子消散，结束后按最新位置移除（期间若被拖动排序也不会删错）。
            val dissolve = xyz.cncnlp.soundwave.ui.common.rememberParticleDissolveState()
            // E16：左滑移除的跟手位移（仅左滑、回弹/触发删除都走 Animatable）。
            val swipeAnim = remember(key) { Animatable(0f) }
            val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
            val removeWithUndo: () -> Unit = {
                dissolve.dissolve {
                    val at = latestKeys.indexOf(key)
                    if (at >= 0) {
                        latestRemove(at)
                        xyz.cncnlp.soundwave.ui.notice.AppNotice.undo("已移出队列：${song.name}") { latestInsert(at, song) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().then(queueAnimateItem(reduceMotion)).height(DefaultQueueRowHeight)) {
                // E16 左滑揭示 + J 波中性化：中性浅底 + 垃圾桶字形，随滑动进度略加深；
                // 用户裁定去掉原粉色揭开背景，行本体右移露出，不溢出列表。
                if (!current && swipeAnim.value < 0f) {
                    val frac = (-swipeAnim.value / swipeThresholdPx).coerceIn(0f, 1f)
                    Box(
                        Modifier.matchParentSize().background(colors.ink.copy(alpha = 0.05f + 0.07f * frac)),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Icon(DefaultPlayerGlyphs.Trash, null, Modifier.padding(end = 20.dp).size(20.dp), tint = colors.ink.copy(alpha = 0.55f))
                    }
                }
                Row(
                    // ACCEPT5-press（稿 §3）：队列行改行档 pressScale（原裸 clickable 吃全局 Card 档 0.965）。
                    Modifier.fillMaxWidth().height(DefaultQueueRowHeight)
                        .pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_ROW)
                        .then(xyz.cncnlp.soundwave.ui.common.particleDissolveModifier(dissolve, colors.ink, colors.accent))
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            // E16：拖拽行只跟手平移，不再放大成溢出白卡（旧 scale 1.02 + surface 底已去除）。
                            translationY = if (dragging) dragOffset else 0f
                            translationX = swipeAnim.value
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                dragging -> colors.accent.copy(alpha = 0.06f)
                                swipeAnim.value != 0f -> colors.background
                                else -> Color.Transparent
                            }
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.onQueueItem(song, queue, index) }
                        // E16：长按整行拖拽排序（与智能换源/脚本页同一范式）；检测器不吞点按，点按切歌保持灵敏。
                        .pointerInput(key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggingKey = key; dragOffset = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(hapticView) },
                                onDragEnd = { draggingKey = null; dragOffset = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(hapticView) },
                                onDragCancel = { draggingKey = null; dragOffset = 0f }
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val from = latestKeys.indexOf(key)
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                // ACCEPT5-press（稿 §4）：换格发 drag 触感（此前只有拿起/放下，过程无感）。
                                if (dragOffset > rowPx / 2 && from < latestKeys.lastIndex) { latestMove(from, from + 1); dragOffset -= rowPx; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(hapticView) }
                                else if (dragOffset < -rowPx / 2 && from > 0) { latestMove(from, from - 1); dragOffset += rowPx; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(hapticView) }
                            }
                        }
                        // E16：单首移除改左滑（当前行除外，与旧 ✕ 的可见范围一致）；过阈值走既有移除链 + 可撤销提示。
                        .then(
                            if (!current) Modifier.pointerInput(key) {
                                detectHorizontalDragGestures(
                                    onDragStart = { scope.launch { swipeAnim.stop() } },
                                    onDragEnd = {
                                        if (swipeAnim.value < -swipeThresholdPx) removeWithUndo()
                                        else scope.launch { swipeAnim.animateTo(0f, tween(180)) }
                                    },
                                    onDragCancel = { scope.launch { swipeAnim.animateTo(0f, tween(180)) } }
                                ) { change, dragAmount ->
                                    change.consume()
                                    scope.launch { swipeAnim.snapTo((swipeAnim.value + dragAmount).coerceIn(-size.width * 0.5f, 0f)) }
                                }
                            } else Modifier
                        )
                        .drawBehind { if (index < queue.lastIndex && !dragging) drawLine(colors.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) } // 原型 .sheet .row+.row border-top 通栏
                        .semantics { contentDescription = "${song.name} - ${song.singer}" + if (current) "，正在播放" else "" },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // r657 第三轮：原型 .sheet .row .cv 48dp r12 + 0 8 18 rgba(19,25,21,.14)；间距 12dp。
                    DefaultPlayerArtwork(song.cover, "", Modifier.size(48.dp).shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFF131915).copy(alpha = 0.10f), spotColor = Color(0xFF131915).copy(alpha = 0.16f)), 12.dp, targetSizePx = 144)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.name, color = if (current) colors.accent else colors.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.15).sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        // E16 追加：副标题去掉「正在播放 ·」前缀，只留歌手 · 专辑；律动条移到行最右侧。
                        val sub = listOf(song.singer.ifBlank { "未知歌手" }, song.album.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
                        Text(sub, color = colors.muted, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (current) {
                        Spacer(Modifier.width(10.dp))
                        DefaultPlayerEqualizer(state.playing, colors.accent, Modifier.size(12.dp))
                        Spacer(Modifier.width(2.dp))
                    }
                }
            }
        }
    }
}

/**
 * SW-12-12（队列部分）：队列增删 / 换位动画。淡入淡出走 MotionTokens 效果弹簧（1.0 / 1600），
 * 让位走审计规格 spring(0.9, 700)；「减少动效」时返回空 Modifier（无淡入、无位移），
 * 与仓内 MotionPolicy 判定一致。稳定 key 已由 itemsIndexed 的 song.key()#出现次序提供，本修饰符不涉及 key。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.queueAnimateItem(reduceMotion: Boolean): Modifier =
    if (reduceMotion) Modifier
    else Modifier.animateItem(
        fadeInSpec = MotionTokens.Spring.EffectsSpec,
        placementSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        fadeOutSpec = MotionTokens.Spring.EffectsSpec
    )
