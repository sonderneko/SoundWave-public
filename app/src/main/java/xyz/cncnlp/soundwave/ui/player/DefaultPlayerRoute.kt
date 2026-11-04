package xyz.cncnlp.soundwave.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import xyz.cncnlp.soundwave.immersiveCoverHeroHeightDp
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import xyz.cncnlp.soundwave.MotionPolicy
import xyz.cncnlp.soundwave.ui.common.SheetMotion
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import xyz.cncnlp.soundwave.LyricLine
import xyz.cncnlp.soundwave.PlayMode
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.queueNextUpIndex

/** 「默认」播放器各页共用的只读快照；所有可变状态与副作用仍归 FullPlayer 所有。 */
@Immutable
internal data class DefaultPlayerState(
    val song: Song,
    val queue: List<Song>,
    val queueIndex: Int,
    val playing: Boolean,
    val resolving: Boolean,
    val status: String,
    val liked: Boolean,
    val playMode: PlayMode,
    val progress: Float,
    val displayTime: Float,
    val duration: Float,
    val quality: String,
    val volume: Float,
    val showVolume: Boolean,
    val lyrics: List<LyricLine>,
    val lyricTimeMs: Long,
    val showTranslation: Boolean,
    /** r657：封面左上角来源标签（「来自 …」）；空串时不显示胶囊（第三轮用户 10-18 去掉「第 x / x 首」回退）。心动模式固定显示「基于你的喜欢智能续播」。 */
    val sourceLabel: String = "",
    /** r658c P：统一歌词偏好（与 ⋯ 歌词设置 / 长按歌词 / 设置页共用）：字号 sp、行高 dp、距离模糊 dp。 */
    val lyricFontSp: Int = 18,
    val lyricGapDp: Int = 9,
    val lyricBlurDp: Int = 2,
    val lyricWordLiftEnabled: Boolean = true,
    val lyricWordKaraoke: Boolean = true
) {
    val queueSize: Int get() = queue.size
    val currentInQueue: Boolean get() = queueIndex in queue.indices
    val trackNumberLabel: String get() = "TRACK " + (if (currentInQueue) "%02d".format(queueIndex + 1) else "01")
    val heartMode: Boolean get() = playMode == PlayMode.HEART
    val coverLabel: String get() = when {
        heartMode -> "✦ 基于你的喜欢智能续播"
        sourceLabel.isNotBlank() -> "来自 $sourceLabel"
        // r657 第三轮（用户 10-18）：r655 加入的「第 x / x 首」回退标签不是原项目功能，无真实来源时不显示任何胶囊。
        else -> ""
    }
    /** UP NEXT 右侧叠放的两张小封面：下一首与再下一首（按队列顺序）。 */
    val upNextPair: List<Song> get() {
        val first = queueNextUpIndex(playMode, queue.size, queueIndex).takeIf { it in queue.indices } ?: return emptyList()
        val second = (first + 1).takeIf { it in queue.indices && it != queueIndex }
        return listOfNotNull(queue[first], second?.let(queue::get))
    }
    val upNext: Song? get() = queueNextUpIndex(playMode, queue.size, queueIndex).takeIf { it in queue.indices }?.let(queue::get)
}

/** 「默认」播放器回调：全部转发到 FullPlayer 既有的业务入口。 */
internal class DefaultPlayerActions(
    val onCollapse: () -> Unit,
    val onMore: () -> Unit,
    val onLike: () -> Unit,
    val onPrev: () -> Unit,
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onMode: () -> Unit,
    val onQuality: () -> Unit,
    val onDevice: () -> Unit,
    val onVolume: (Float) -> Unit,
    val onSeekTap: (Float) -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onOpenLyrics: () -> Unit,
    val onCloseLyrics: () -> Unit,
    val onLyricLine: (LyricLine) -> Unit,
    val onLyricLongPress: (Int) -> Unit,
    val onOpenQueue: () -> Unit,
    val onCloseQueue: () -> Unit,
    val onQueueItem: (Song, List<Song>, Int) -> Unit,
    val onQueueMove: (Int, Int) -> Unit,
    val onQueueRemove: (Int) -> Unit,
    val onLocateCurrent: () -> Unit,
    /** r657：音量条默认隐藏；长按播放设备键或 ⋯ 菜单「音量条」切换。 */
    val onToggleVolume: () -> Unit = {},
    /** r657 队列抽屉：分段控件直接设定播放顺序；清空队列（保留当前歌曲）。 */
    val onSetMode: (PlayMode) -> Unit = {},
    val onClearQueue: () -> Unit = {},
    val onDragCancel: () -> Unit = {},
    /** E16：左滑移除的撤销恢复（按原位置插回）。 */
    val onQueueInsert: (Int, Song) -> Unit = { _, _ -> },
    /** SW-07-10（G 波）：空歌词态长按 → 打开歌词操作框（重新获取/纠错）。置于末尾，位置参数调用方不受影响。 */
    val onEmptyLyricsLongPress: () -> Unit = {}
)

/**
 * 「默认」播放器路由（r658c P）：主页与歌词页常驻组合，按过渡进度 graphicsLayer 交叉淡化；
 * 封面由顶层飞行封面在两页槽位间连续平移缩放（见 PlayerCoverSharedTransition.kt）；当前目标页置顶并接收输入。
 * 队列作为圆角上滑面板覆盖在最上层（沿用 FullPlayer 的 queueVisibilityState，使关闭动画与路由回退时序一致）。
 */
@Composable
internal fun DefaultPlayerRoute(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    state: DefaultPlayerState,
    actions: DefaultPlayerActions,
    queueListState: LazyListState,
    queueVisibilityState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    coverOnlyImmersive: Boolean = false
) {
    val reduce = MotionPolicy.rememberReduceMotion()
    val transition = remember { Animatable(if (lyricsOpen) 1f else 0f) }
    // r672：单一共享进度 + 临界阻尼弹簧（可中断、反向保留速度、末段无跳变），封面大↔小两个方向同一条曲线。
    LaunchedEffect(lyricsOpen) {
        transition.animateTo(if (lyricsOpen) 1f else 0f, playerCoverSharedSpec(reduce))
    }
    val morph = remember { PlayerCoverMorph(progressProvider = { transition.value }) }
    morph.enabled = !coverOnlyImmersive
    Box(modifier.fillMaxSize().onGloballyPositioned { c -> val o = c.positionInRoot(); if (o != morph.origin) morph.origin = o }) {
        CompositionLocalProvider(LocalPlayerCoverMorph provides morph) {
            // r672：标准沉浸封面改为路由层常驻的一张封面（两页 alpha 交叉淡化之外），不再由两页各画一张不同高度的封面。
            if (coverOnlyImmersive) StandardImmersiveCoverLayer(state, morph, lyricsOpen, Modifier.zIndex(-1f))
            DefaultPlayerMainPage(
                state, actions,
                Modifier.zIndex(if (lyricsOpen) 0f else 1f)
                    .graphicsLayer { alpha = playerMainPageAlpha(morph.progress) }
                    .then(if (lyricsOpen) Modifier else PlayerPageInputOwner),
                coverOnlyImmersive = coverOnlyImmersive
            )
            DefaultPlayerLyricsPage(
                state, actions,
                Modifier.zIndex(if (lyricsOpen) 1f else 0f)
                    .graphicsLayer { alpha = playerLyricsPageAlpha(morph.progress) }
                    .then(if (lyricsOpen) PlayerPageInputOwner else Modifier),
                following = lyricsOpen, coverOnlyImmersive = coverOnlyImmersive
            )
            if (!coverOnlyImmersive) DefaultPlayerFlyingCover(state, morph, Modifier.zIndex(2f))
        }
        if (queueOpen) {
            AnimatedVisibility(
                visibleState = queueVisibilityState,
                modifier = Modifier.zIndex(3f),
                // 底部抽屉：遮罩淡入、面板上滑由面板内 animateEnterExit 各自负责。
                enter = EnterTransition.None, exit = ExitTransition.None
            ) { DefaultPlayerQueuePage(state, actions, queueListState, enterScope = this) }
        }
    }
}

/**
 * r672（用户 10-22 真机录屏：标准 + 沉浸封面切歌词页封面跳动、状态栏区域突然变白再闪、封面底部出现一条线）：
 *  - 旧版主页与歌词页各画一张沉浸封面（高度不同 → Crop 裁切与缩放不同 → 封面跳 ~15px），两页 alpha 交叉淡化的中段两张都半透明，
 *    状态栏区域只剩浅色纸面背景 → 「突然变白再闪回」；封面底部渐变到一块不透明纸色，而下方背景是带封面色晕的半透明纸面 → 拼接硬边。
 *  - 现在参照 Apple Music 样式的沉浸封面：一张常驻封面，图像尺寸 / 位置在两页完全相同（取两页需要的较大高度，从屏幕顶端铺起），
 *    只有底部渐隐终点随共享进度在「主页封面槽位底边」与「歌词页 Hero 高度」之间连续移动；渐隐用 alpha 遮罩到全透明，
 *    直接融进下层播放器背景，没有任何拼接边。系统栏图标配色由 MainActivity 按样式固定，不随页面切换变化。
 */
@Composable
private fun StandardImmersiveCoverLayer(state: DefaultPlayerState, morph: PlayerCoverMorph, lyricsOpen: Boolean, modifier: Modifier) {
    val density = LocalDensity.current
    val statusTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val lyricsEndPx = with(density) { immersiveCoverHeroHeightDp(LocalConfiguration.current.screenWidthDp).dp.toPx() }
    // 主页槽位底边相对「屏幕顶端（= 路由顶端 − 状态栏）」的距离；未测量前不画，避免首帧用错误终点。
    val mainEndPx = (morph.immersiveMainBottom + statusTopPx).takeIf { morph.immersiveMainBottom > 0f } ?: return
    val coverScale by rememberDefaultCoverScale(state)
    val colors = defaultPlayerColors()
    val layerHeight = with(density) { maxOf(mainEndPx, lyricsEndPx).toDp() }
    Box(
        modifier.fillMaxWidth().height(layerHeight)
            .offset { IntOffset(0, -statusTopPx.roundToInt()) }
            .playerHeroFadeTo({ mainEndPx + (lyricsEndPx - mainEndPx) * morph.progress.coerceIn(0f, 1f) }, STANDARD_IMMERSIVE_FADE_FRACTION)
    ) {
        DefaultPlayerArtwork(
            state.song.cover, if (lyricsOpen) "沉浸歌词封面" else "", Modifier.fillMaxSize().graphicsLayer {
                // 主页播放 / 暂停的轻微呼吸缩放（只放大），进入歌词页时随进度归一，图像不会在终点跳变。
                val s = 1f + (1f - coverScale).coerceIn(0f, 0.2f) * 0.25f * (1f - morph.progress.coerceIn(0f, 1f))
                scaleX = s; scaleY = s
            }, 0.dp
        )
        // 状态栏下方的轻遮罩常驻（两页相同），状态栏图标对比度在切换过程中不变。
        Box(
            Modifier.fillMaxWidth().height(with(density) { (statusTopPx + 56.dp.toPx()).toDp() })
                .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.28f), Color.Transparent)))
        )
    }
}

/** 标准沉浸封面底部渐隐长度（占渐隐终点高度的比例，比 Apple 的 0.34 略长，融入更柔和）。 */
internal const val STANDARD_IMMERSIVE_FADE_FRACTION = 0.40f

/** 当前目标页根部的空指针节点：命中后阻断下层（隐藏中的）页面接收触摸，本页子项照常处理。 */
private val PlayerPageInputOwner: Modifier = Modifier.pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** 顶层飞行封面：只绘制（无指针节点，触摸穿透到下方页面的封面点按区）。 */
@Composable
private fun DefaultPlayerFlyingCover(state: DefaultPlayerState, morph: PlayerCoverMorph, modifier: Modifier) {
    if (!morph.ready) return
    val density = LocalDensity.current
    val main = morph.mainBounds
    val coverScale by rememberDefaultCoverScale(state)
    val progress = morph.progress.coerceIn(0f, 1f)
    val frame = playerCoverMorphFrame(morph.mainBounds, morph.miniBounds, progress, coverScale)
    Box(
        modifier
            .offset { IntOffset((morph.mainBounds.left - morph.origin.x).roundToInt(), (morph.mainBounds.top - morph.origin.y).roundToInt()) }
            .size(with(density) { main.width.toDp() }, with(density) { main.height.toDp() })
            .playerCoverFlight(morph) { coverScale }
    ) { DefaultPlayerCoverFace(state, defaultPlayerColors(), Modifier.fillMaxSize(),
        corner = playerCoverMorphCornerDp(progress, frame.scale, coverScale).dp,
        decorationAlpha = (1f - progress / 0.65f).coerceIn(0f, 1f)) }
}
