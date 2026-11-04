@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package xyz.cncnlp.soundwave

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned

import xyz.cncnlp.soundwave.ui.player.DefaultPlayerActions
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerBackdrop
import xyz.cncnlp.soundwave.ui.player.PlayerPlaybackLinkSheet
import xyz.cncnlp.soundwave.ui.player.SharedPlayerLyricFlow
import xyz.cncnlp.soundwave.ui.player.defaultPlayerColors
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.ui.common.IsolatedSection
import xyz.cncnlp.soundwave.ui.common.IsolatedBoxSection
import xyz.cncnlp.soundwave.ui.common.IsolatedColumnSection
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerRoute
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerQueuePage
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import xyz.cncnlp.soundwave.ui.player.DefaultPlayerState
import xyz.cncnlp.soundwave.ui.player.PlayerSoundRingsDecor
import xyz.cncnlp.soundwave.ui.player.playerOutputRouteMonitoringEnabled
import xyz.cncnlp.soundwave.ui.player.PlayerSystemOutputController
import xyz.cncnlp.soundwave.ui.player.PlayerSystemVolumeController
import xyz.cncnlp.soundwave.ui.player.DlnaControllerGateway
import xyz.cncnlp.soundwave.ui.player.PlayerDlnaSessionCoordinator
import xyz.cncnlp.soundwave.ui.player.CoverBackgroundTones
import xyz.cncnlp.soundwave.ui.player.sampleCoverBackground
import xyz.cncnlp.soundwave.ui.player.FullPlayerBackdropLayers
import xyz.cncnlp.soundwave.ui.player.FullPlayerFooterRenderer
import xyz.cncnlp.soundwave.ui.player.PlayerBottomControlChromeRenderer as PlayerBottomControlChromeView
import xyz.cncnlp.soundwave.ui.player.PlayerLyricsStylePreview
import xyz.cncnlp.soundwave.ui.player.PlayerTransitionBackdrop
import xyz.cncnlp.soundwave.ui.player.PlayerImmersiveCoverHero
import xyz.cncnlp.soundwave.ui.player.PlayerImmersiveCoverLyricsHeader
import xyz.cncnlp.soundwave.ui.player.PlayerLyricsPageHeader
import xyz.cncnlp.soundwave.ui.player.PlayerCoverArtworkCard
import xyz.cncnlp.soundwave.ui.player.PlayerLyricsRoute
import xyz.cncnlp.soundwave.ui.player.PlayerLyricRow
import xyz.cncnlp.soundwave.ui.player.PlayerLyricActionsSheet
import xyz.cncnlp.soundwave.ui.player.toggleFloatingLyric
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.player.PlayerLyricCorrectionDialog
import xyz.cncnlp.soundwave.ui.player.PlayerLyricBrowseState
import xyz.cncnlp.soundwave.ui.player.playerLyricSettleBrowsing
import xyz.cncnlp.soundwave.ui.player.playerLyricBeginBrowsing
import xyz.cncnlp.soundwave.ui.player.playerLyricCloseSelection
import xyz.cncnlp.soundwave.ui.player.playerLyricFocusedIndex
import xyz.cncnlp.soundwave.ui.player.playerLyricOpenSelection
import xyz.cncnlp.soundwave.ui.player.playerLyricPlaybackIndexChanged
import xyz.cncnlp.soundwave.ui.player.playerLyricResumePlaybackFollow
import xyz.cncnlp.soundwave.ui.player.playerLyricExitLyricsPage
import xyz.cncnlp.soundwave.ui.player.playerLyricSelectIndex
import xyz.cncnlp.soundwave.ui.player.PlayerDeviceSheet
import xyz.cncnlp.soundwave.ui.player.playerLyricSeekProgress
import xyz.cncnlp.soundwave.ui.player.FullPlayerPlaybackCoordinator
import xyz.cncnlp.soundwave.playback.PlaybackAudioFormat
import xyz.cncnlp.soundwave.playback.playbackAudioFormatLabel
import xyz.cncnlp.soundwave.ui.player.PlayerDismissDecision
import xyz.cncnlp.soundwave.ui.player.playerDismissDecision
import xyz.cncnlp.soundwave.ui.player.rememberPlayerDismissGestureModifier
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch


internal fun queueNextUpIndex(mode: PlayMode, queueSize: Int, currentIndex: Int): Int {
    if (queueSize <= 0) return -1
    val current = currentIndex.takeIf { it in 0 until queueSize } ?: return 0
    return when (mode) {
        PlayMode.LOOP_ONE -> current
        PlayMode.SEQUENCE -> (current + 1) % queueSize
        // 随机/心动会在播放时动态挑选候选；队列标签锚定列表中首个非当前候选，而不伪称确定顺序。
        PlayMode.SHUFFLE, PlayMode.HEART -> (0 until queueSize).firstOrNull { it != current } ?: current
    }
}

// ===== 全屏播放器（自 MainActivity 迁入） =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FullPlayer(
    song: Song?,
    /** AppRoot 的 Compose 状态快照；不能在队列面板内直接读取 Service 的非状态值。 */
    queueSnapshot: List<Song> = emptyList(),
    queueCurrentIndexState: androidx.compose.runtime.State<Int>,
    playing: Boolean,
    progressState: androidx.compose.runtime.State<Float>,
    currentTimeState: androidx.compose.runtime.State<Float>,
    duration: Float,
    resolvingState: androidx.compose.runtime.State<Boolean>,
    statusState: androidx.compose.runtime.State<String>, // ACCEPT6-FREEZE：State 入参延迟读取（同 progressState 纪律），宿主层与主体不再订阅解析状态
    lyrics: List<LyricLine>,
    lyricIdx: Int,
    showLyrics: Boolean,
    liked: Boolean,
    playMode: PlayMode,
    sleepRemain: Long,
    playbackAudioFormat: PlaybackAudioFormat? = null,
    lyricGapDp: Int = 9,
    lyricFontSp: Int = 18,
    lyricBold: Boolean = true,
    lyricBlurDp: Int = 0,
    lyricShowTranslation: Boolean = false,
    lyricShowRomanization: Boolean = false,
    lyricHighlightHex: String = "#FFFFFF",
    lyricUnhighlightHex: String = "#A8A8B3",
    lyricWordKaraoke: Boolean = true,
    onLyricWordKaraoke: (Boolean) -> Unit = {},
    onLyricShowTranslation: (Boolean) -> Unit = {},
    onLyricShowRomanization: (Boolean) -> Unit = {},
    onLyricHighlight: (String) -> Unit = {},
    onLyricUnhighlight: (String) -> Unit = {},
    lyricOffsetMs: Int = 0,
    onLyricOffset: (Int) -> Unit = {},
    playerLayout: String = "standard",
    dismissOffsetHolder: PlayerDismissOffsetHolder,
    onDismissGestureStatusBarExposure: (Boolean) -> Unit = {},
    onPlayerLayout: (String) -> Unit = {},
    onLyricGap: (Int) -> Unit = {},
    onLyricFont: (Int) -> Unit = {},
    onLyricBlur: (Int) -> Unit = {},
    /** SW-07-10（G 波）：空歌词操作框「重新获取」→ 宿主对当前歌曲强制重取歌词。 */
    onRefetchLyrics: () -> Unit = {},
    onBack: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onLike: () -> Unit,
    onMode: () -> Unit,
    onQuality: () -> Unit = {},
    onSleep: (Int) -> Unit,
    onDownload: () -> Unit,
    onQueueItem: (Song, List<Song>, Int) -> Unit = { _, _, _ -> },
    onQueueMove: (Int, Int) -> Unit = { _, _ -> },
    onQueueRemove: (Int) -> Unit = {},
    onQueueInsert: (Int, Song) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onAddNext: (Song) -> Unit = {},
    onSimilar: (Song) -> Unit = {},
    onHeartMode: (Song) -> Unit = {},
    onLyricCorrectionRefresh: (Song) -> Unit = {}
) {
    if (song == null) {
        Box(Modifier.fillMaxSize().background(ThemeTokens.PlayerBackground), contentAlignment = Alignment.Center) {
            Text("选择歌曲开始播放", color = Color.White.copy(alpha = 0.7f))
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
                Text("返回", color = Color.White)
            }
        }
        return
    }
    // BATCH2 反馈 F4：progressState / currentTimeState 不在主体顶层读。播放 tick 每 400ms
    // 推进这两个 State，顶层读曾使整个 FullPlayer 主体（封面色板/手势/菜单/转场状态）每 tick
    // 失效重组。高频读下沉至两个真实边界内：PlayerBottomControlChrome 函数体、以及
    // IsolatedSection 的 content lambda（其读取在 IsolatedSection 作用域内执行）。
    // 守卫：scripts/playback-tick-scope-guard.sh 钉住本纪律。
    val queueCurrentIndex by queueCurrentIndexState
    val appleWhiteFrost = normalizePlayerLayout(playerLayout) == "apple" && xyz.cncnlp.soundwave.ui.common.pageFrostAllowed(xyz.cncnlp.soundwave.ui.common.FrostedPrefs.themeMode)
    val playerPrimary = if (appleWhiteFrost) SoundwaveDesignPalette.Ink else Color.White
    val playerSecondary = playerPrimary.copy(alpha = 0.72f)
    // 播放器封面采用固定背板遮罩与模糊效果，独立于全局主题设置。
    val playerMask = 0.30f
    val white = playerPrimary
    val soft = playerSecondary
    val dim = white.copy(alpha = 0.45f)
    var coverPalette by remember(song.key(), song.cover) {
        mutableStateOf(xyz.cncnlp.soundwave.ui.player.CoverToneCache.cached(song.cover) ?: CoverBackgroundTones(Color(0xFF17202E.toInt()), Color(0xFF10151D.toInt())))
    }
    val coverArtworkLuminance = coverPalette.artworkLuminance
    val coverLower by animateColorAsState(coverPalette.lower, animationSpec = tween(650), label = "coverPaletteLower")
    var showMenu by remember { mutableStateOf(false) }
    var showTransitionSettings by remember { mutableStateOf(false) }
    var showPlaybackLink by remember { mutableStateOf(false) }
    var showSleepBar by remember { mutableStateOf(false) }
    var menuLevel by remember { mutableIntStateOf(0) }
    // 音量条默认隐藏；仍可在播放器菜单「音量条」中打开。
    val presentationPrefs = LocalStore.prefs(LocalContext.current)
    val presentation = remember(presentationPrefs) { PlayerPresentationPreferences(presentationPrefs) }
    val presentationMode = normalizePlayerLayout(playerLayout)
    var showVolumeInMenu by remember(presentationPrefs, presentationMode) { mutableStateOf(presentation.volumeVisible(presentationMode)) }
    val coverImmersiveState = remember(presentationPrefs, presentationMode) { mutableStateOf(presentation.immersiveCover(presentationMode)) }
    var coverImmersive by coverImmersiveState
    var volumePressed by remember { mutableStateOf(false) }
    var lyricScale by remember { mutableFloatStateOf(1f) }
    var lyricBrowseState by remember(song.key()) { mutableStateOf(PlayerLyricBrowseState()) }
    LaunchedEffect(song.key(), lyricIdx) {
        lyricBrowseState = playerLyricPlaybackIndexChanged(lyricBrowseState, lyricIdx)
    }
    var lyricOffset by remember(lyricOffsetMs) { mutableIntStateOf(lyricOffsetMs) }
    // 沉浸歌词与默认歌词页共用字号/行高换算，统一字号偏好在两种模式下保持相同视觉大小。
    val lyricImmersiveSp = PlayerLyricsSettingsModel.immersiveFontFor(lyricFontSp)
    val lyricImmersiveGapDp = PlayerLyricsSettingsModel.immersiveLineHeightFor(lyricGapDp)
    var dragging by remember { mutableStateOf(false) }; var dragRatio by remember { mutableFloatStateOf(0f) }
    val seekHapticView = androidx.compose.ui.platform.LocalView.current // 进度拖动跨档触感取景（件 2），两处宿主接线共用。
    var coverPressed by remember { mutableStateOf(false) }
    val chromeVisibleState = remember { mutableStateOf(true) }
    var chromeVisible by chromeVisibleState
    var suppressSeekChromeReveal by remember { mutableStateOf(false) }
    val immersiveModeState = remember { mutableStateOf(false) }
    var immersiveMode by immersiveModeState
    val interactionTickState = remember { mutableIntStateOf(0) }
    var interactionTick by interactionTickState
    var playerContentHeightPx by remember { mutableIntStateOf(0) }
    var playerSurfaceHeightPx by remember { mutableIntStateOf(0) }
    var playerBottomChromeHeightPx by remember { mutableIntStateOf(0) }
    // 队列是播放器内独立全屏子路由；共用背景，但不复用播放/音量控制。
    // 空串=歌词/封面原态，queue=队列页；只切视觉路由，不改播放队列本身。
    val midPanelState = remember { mutableStateOf("") }
    var midPanel by midPanelState
    val queueListState = rememberLazyListState()
    val queueVisibilityState = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    val queueReturnImmersiveState = remember { mutableStateOf(false) }
    val queueReturnShowLyricsState = remember { mutableStateOf(false) }
    val queueReturnForcedDismissedState = remember { mutableStateOf(false) }
    val layout = presentation.effectiveLayout(presentationMode, coverImmersive)
    val layoutSpec = playerLayoutSpec(layout)
    val layoutForcesLyrics = layoutSpec.showLyrics
    val immersiveLayout = layoutSpec.visualMode == PlayerVisualMode.IMMERSIVE
    val immersiveCoverLayout = layoutSpec.visualMode == PlayerVisualMode.IMMERSIVE_COVER
    val lyricsStyleLayout = layoutSpec.visualMode == PlayerVisualMode.LYRICS_STYLE
    val defaultLayout = layoutSpec.visualMode == PlayerVisualMode.DEFAULT // 1.0.655 全新「默认」样式（ui/player/DefaultPlayer*）
    val fullBleedArtwork = layoutSpec.artworkPresentation == PlayerArtworkPresentation.FULL_BLEED_BACKGROUND
    val mainCoverFraction = layoutSpec.coverFraction
    val showPlayerVolume = showVolumeInMenu && !lyricsStyleLayout
    // 音量隐藏时把标题、进度和主传输控件移入 30dp 音量槽；底部动作行仍保留原位置。
    val hiddenVolumeShift = if (showPlayerVolume) 0.dp else 30.dp
    val immersiveCoverHeroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.19f).coerceIn(150.dp, 220.dp)
    // Cover and ImmersiveLyrics keep the same measured Hero bounds; only its foreground image fades into the fixed root backdrop.
    val immersiveCoverArtworkHeight = immersiveCoverHeroHeightDp(LocalConfiguration.current.screenWidthDp).dp
    // 强制歌词布局仍可在播放器内暂时返回封面；不改写布局偏好或播放状态。
    val forcedLyricsDismissedState = remember(layout) { mutableStateOf(false) }
    var forcedLyricsDismissed by forcedLyricsDismissedState
    // Apple has only the explicit immersive lyrics route; the default lyric page stays intact.
    val lyricLayout = if (presentationMode == "apple") immersiveMode else immersiveMode || ((showLyrics || layoutForcesLyrics) && !forcedLyricsDismissed)
    LaunchedEffect(lyricLayout) {
        if (!lyricLayout) lyricBrowseState = playerLyricExitLyricsPage(lyricBrowseState)
    }
    // r672（用户 10-22：Apple 样式歌词页返回封面页整屏闪一下）：封面页 / 歌词页是同一列里的两个分支，切换当帧整屏内容硬切。
    // 现在先淡出当前页（130ms）→ 再换分支 → 淡入新页（220ms），常驻的模糊背板 / 沉浸封面不参与，所以不再整屏闪；快速反向可中断。
    val appleMode = presentationMode == "apple"
    // r674（用户 10-22：标准 / Apple Music 点击进入歌词页加动画，退出也要）：标准样式同样走这套页面过渡（之前标准是硬切）。
    // 过渡 = 旧页 淡出 + 沿离开方向上移 14dp（140ms，FastOutLinearIn）→ 换分支 → 新页 淡入 + 从 14dp 处归位（260ms，LinearOutSlowIn / 减速）。
    // 进入歌词：封面页向上离开、歌词页自下而上浮入；退出相反。快速反向点按会中断当前动画并从当前 alpha 继续，不叠页、不跳位。
    // SW-08-03：系统「移除动画」/ 减少动画时直接切换，不做位移与淡入淡出。
    val pageMotion = !defaultLayout
    val reducePageMotion = MotionPolicy.rememberReduceMotion()
    var appleShownLyrics by remember { mutableStateOf(lyricLayout) }
    val applePageAlpha = remember { Animatable(1f) }
    var pageMotionDir by remember { mutableFloatStateOf(1f) }
    var pageIncoming by remember { mutableStateOf(true) }
    LaunchedEffect(lyricLayout, pageMotion) {
        if (!pageMotion || reducePageMotion) { appleShownLyrics = lyricLayout; pageIncoming = true; applePageAlpha.snapTo(1f); return@LaunchedEffect }
        if (appleShownLyrics != lyricLayout) {
            pageMotionDir = if (lyricLayout) 1f else -1f
            pageIncoming = false
            applePageAlpha.animateTo(0f, tween(PLAYER_PAGE_EXIT_MS, easing = FastOutLinearInEasing))
            appleShownLyrics = lyricLayout
            pageIncoming = true
        }
        applePageAlpha.animateTo(1f, tween(PLAYER_PAGE_ENTER_MS, easing = LinearOutSlowInEasing))
    }
    // r674：沉浸封面渐隐中段衔接色 ≈ 封面下方背景色（Apple 白磨砂 = 封面底部取色 + 78% 白；非磨砂 = 底部取色压暗），只在 Apple 用。
    val appleFadeBridge = if (!appleMode) Color.Unspecified
        else if (appleWhiteFrost) androidx.compose.ui.graphics.lerp(coverLower, Color.White, 0.78f)
        else androidx.compose.ui.graphics.lerp(coverLower, Color.Black, playerMask.coerceIn(0f, 1f))
    val pageShiftPx = with(LocalDensity.current) { PLAYER_PAGE_SHIFT_DP.dp.toPx() }
    // 非默认样式（标准 / Apple）实际显示的页面；队列改为覆盖层后不再替换下面的页面。
    val pageLyrics = if (pageMotion) appleShownLyrics else lyricLayout
    // r672：队列在所有样式都是覆盖在当前页之上的共享底部抽屉（DefaultPlayerQueuePage），不再是替换页面的子路由。
    val immersiveCoverLyricsPage = (appleMode && pageLyrics) || playerUsesDedicatedImmersiveCoverLyricsViewport(layout, pageLyrics, false)
    val brightImmersiveCoverLyricsHeader = playerUsesBrightImmersiveCoverLyricsHeader(
        layout, pageLyrics, false, coverArtworkLuminance
    )
    // The ambient art spans every immersive-cover route; the bounded hero remains on cover and immersive-lyrics pages, not queue.
    val fullBleedCoverPage = playerLayoutUsesFullBleedCoverPage(layout, pageLyrics, false)
    val immersiveCoverBackdropArtwork = playerImmersiveBackdropArtwork(layout, song.cover)
    // r672（用户 10-22）：Apple 样式封面非沉浸时，歌词页顶部也显示同一张沉浸封面（之前该区域空白）。
    val immersiveCoverHeroLayer = playerShowsImmersiveCoverHero(layout, queuePage = false) || (appleMode && pageLyrics)
    val chromeAutoHide = immersiveLayout || immersiveMode
    LaunchedEffect(chromeAutoHide, playing, interactionTick) {
        if (!chromeAutoHide) {
            chromeVisible = true
            return@LaunchedEffect
        }
        if (!playing) {
            if (playerChromeShouldRevealOnPause(suppressSeekChromeReveal)) chromeVisible = true
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(2000)
        chromeVisible = false
    }
    LaunchedEffect(suppressSeekChromeReveal) {
        if (suppressSeekChromeReveal) {
            kotlinx.coroutines.delay(750)
            suppressSeekChromeReveal = false
        }
    }
    fun seekLyricLine(targetProgress: Float) {
        if (immersiveCoverLyricsPage && chromeAutoHide && !chromeVisible) suppressSeekChromeReveal = true
        onSeek(targetProgress.coerceIn(0f, 1f))
    }
    val reduceMotion = MotionPolicy.rememberReduceMotion()
    var playerSurfaceTopPx by remember { mutableFloatStateOf(0f) }
    val appleCoverProgress by animateFloatAsState(
        targetValue = if (coverImmersive) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 160 else 420, easing = FastOutSlowInEasing),
        label = "appleCoverMorph"
    )
    var coverShrunk by remember { mutableStateOf(false) }
    val coverScale by animateFloatAsState(
        targetValue = when {
            lyricLayout -> 0.78f
            coverShrunk -> 0.86f
            else -> 1f
        },
        animationSpec = if (reduceMotion) androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.LinearEasing) else androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
        label = "coverScale"
    )
    val lyricWordLiftEnabled by rememberLyricWordLiftEnabled()
    val ctx = LocalContext.current
    val menuOwner = remember(song.key()) { PlayerService.snapshot().ownerContext }
    val transitionPrefs = remember(ctx, menuOwner) { ContextPreferences.open(ctx, "soundwave", 0, menuOwner) }
    val transitionSeconds by rememberPlaybackTransitionSeconds(transitionPrefs)
    val playerScope = rememberCoroutineScope()
    val lyricCorrection = remember(song.key()) { xyz.cncnlp.soundwave.ui.player.FullPlayerLyricCorrectionState() }
    LaunchedEffect(ctx, song.key()) {
        lyricCorrection.loadInitial(ctx, song, menuOwner)
    }
    val dlnaCoordinator = remember(playerScope) {
        PlayerDlnaSessionCoordinator(scope = playerScope, gateway = DlnaControllerGateway)
    }
    val dlnaSnapshot by dlnaCoordinator.snapshot.collectAsState()
    val coverDecodeSizePx = immersiveCoverArtworkRequestSizePx(
        with(LocalDensity.current) { immersiveCoverArtworkHeight.roundToPx() }
    )
    val immersiveCoverImageRequest = remember(ctx, song.cover, immersiveCoverLayout || presentationMode == "apple", coverDecodeSizePx) {
        if ((immersiveCoverLayout || presentationMode == "apple") && song.cover.isNotBlank()) {
            coil.request.ImageRequest.Builder(ctx)
                .data(CacheManager.coverModel(ctx, song.cover) ?: song.cover)
                .size(coverDecodeSizePx, coverDecodeSizePx)
                .crossfade(false)
                .build()
        } else null
    }
    val immersiveCoverPainter = rememberAsyncImagePainter(
        model = immersiveCoverImageRequest
    )
    LaunchedEffect(immersiveCoverPainter.state) {
        val success = immersiveCoverPainter.state as? coil.compose.AsyncImagePainter.State.Success
            ?: return@LaunchedEffect
        sampleCoverBackground(success.result.drawable)?.let { coverPalette = it }
    }
    LaunchedEffect(ctx, song.cover) { xyz.cncnlp.soundwave.ui.player.CoverToneCache.tonesFor(ctx, song.cover)?.let { coverPalette = it } } // r656-G：后台取色（硬件位图安全）
    var showDeviceSheet by remember { mutableStateOf(false) }
    // SW-07-10（G 波）：空歌词长按打开的操作框（与行选框是两个实例，selectedLine=null）。
    var showEmptyLyricActions by remember { mutableStateOf(false) }
    val systemOutputController = remember(ctx) { PlayerSystemOutputController(ctx) }
    var outputSnapshot by remember(systemOutputController) {
        mutableStateOf(systemOutputController.emptySnapshot())
    }
    DisposableEffect(systemOutputController, showDeviceSheet) {
        if (playerOutputRouteMonitoringEnabled(showDeviceSheet)) {
            systemOutputController.start { snapshot ->
                outputSnapshot = snapshot
            }
            onDispose { systemOutputController.stop() }
        } else {
            onDispose { systemOutputController.stop() }
        }
    }
    val systemVolumeController = remember(ctx) { PlayerSystemVolumeController.from(ctx) }
    var volumeLevel by remember(systemVolumeController) {
        mutableFloatStateOf(systemVolumeController.initialVolumeLevel())
    }
    fun setSystemVolume(value: Float) {
        volumeLevel = systemVolumeController.setVolume(value)
    }
    fun toast(m: String) = try { AppNotice.toast(m, AppNotice.Kind.NEUTRAL) } catch (_: Exception) {}
    fun beginLyricBrowsing() {
        lyricBrowseState = playerLyricBeginBrowsing(
            lyricBrowseState,
            playerLyricFocusedIndex(lyricBrowseState, lyricIdx)
        )
    }
    fun selectLyric(index: Int) {
        lyricBrowseState = playerLyricSelectIndex(lyricBrowseState, index)
    }
    fun settleLyricBrowsing(index: Int) {
        lyricBrowseState = playerLyricSettleBrowsing(lyricBrowseState, index)
    }
    fun openLyricSelection(index: Int) {
        lyricBrowseState = playerLyricOpenSelection(lyricBrowseState, index)
    }
    fun playLyricLine(line: LyricLine) {
        val target = playerLyricSeekProgress(line.timeMs, duration, lyricOffset.toLong()) ?: return
        lyricBrowseState = playerLyricResumePlaybackFollow(lyricBrowseState)
        seekLyricLine(target)
        toast("已跳到该句")
    }
    fun scanDlna() = dlnaCoordinator.scan()
    fun castDlna(device: DlnaDevice) {
        dlnaCoordinator.cast(
            device = device,
            mediaUrl = song.resolvedUrl,
            title = song.name,
            onConnected = { toast("已连接 $it") },
            onFailure = { toast("DLNA 连接失败：$it") }
        )
    }
    /** 远端控制：暂停/继续/跳转/停止。断开时清理会话标记。 */
    fun dlnaPause() = dlnaCoordinator.pause { toast("DLNA 已断开") }
    fun dlnaResume() = dlnaCoordinator.resume { toast("DLNA 已断开") }
    fun dlnaSeek(ms: Long) = dlnaCoordinator.seek(ms) { toast("DLNA 已断开") }
    fun dlnaStop() = dlnaCoordinator.stop { toast("DLNA 停止失败") }
    LaunchedEffect(showDeviceSheet) {
        if (showDeviceSheet) scanDlna()
    }
    val playbackCoordinator = FullPlayerPlaybackCoordinator(
        scope = playerScope,
        midPanelState = midPanelState,
        immersiveModeState = immersiveModeState,
        forcedLyricsDismissedState = forcedLyricsDismissedState,
        chromeVisibleState = chromeVisibleState,
        interactionTickState = interactionTickState,
        queueVisibilityState = queueVisibilityState,
        queueReturnImmersiveState = queueReturnImmersiveState,
        queueReturnShowLyricsState = queueReturnShowLyricsState,
        queueReturnForcedDismissedState = queueReturnForcedDismissedState,
        layout = layout,
        layoutForcesLyrics = layoutForcesLyrics,
        lyricsStyleLayout = lyricsStyleLayout,
        immersiveCoverLayout = immersiveCoverLayout,
        lyricLayout = lyricLayout,
        showLyrics = showLyrics,
        playing = playing,
        onToggleLyrics = onToggleLyrics,
        keepSubmodeUnderQueue = presentationMode == "apple",
        coverImmersiveState = coverImmersiveState,
        appleCoverMode = presentationMode == "apple",
        onSetImmersiveCover = { presentation.setImmersiveCover(presentationMode, it) }
    )
    // Keep the existing composition call sites stable; the route decisions live in the coordinator.
    fun returnToPreviousPlayerMode(): Boolean = playbackCoordinator.returnToPreviousPlayerMode()
    fun openLyricsImmersive() = playbackCoordinator.openLyricsImmersive()
    fun toggleCoverLyricsMode() = playbackCoordinator.toggleCoverLyricsMode()
    fun toggleVolumeVisibility() {
        showVolumeInMenu = !showVolumeInMenu
        presentation.setVolumeVisible(presentationMode, showVolumeInMenu)
    }
    fun openQueueInLyrics() = playbackCoordinator.openQueueInLyrics()
    @Composable
    fun PlayerBottomControlChrome(modifier: Modifier = Modifier, applyOffset: Boolean = true) {
        // FullPlayer keeps all mutable state and action ownership; this adapter only supplies the current view model and callbacks.
        // BATCH2 反馈 F4：高频进度在本函数作用域内读（原主体顶层读已删，见主体顶部注）。
        val progress by progressState
        val currentTime by currentTimeState
        val displayTime = if (dragging) dragRatio * duration else currentTime
        PlayerBottomControlChromeView(
            modifier = modifier,
            verticalOffset = if (applyOffset) (-14).dp else 0.dp,
            hiddenVolumeShift = hiddenVolumeShift,
            progress = progress,
            dragRatio = dragRatio,
            displayTime = displayTime,
            duration = duration,
            playbackAudioFormat = playbackAudioFormat,
            dragging = dragging,
            onDragStart = { dragging = true; dragRatio = progress },
            onDrag = { val s = xyz.cncnlp.soundwave.ui.player.seekStepSnapRatio(it, duration); if (xyz.cncnlp.soundwave.ui.player.seekStepIndex(s, duration) != xyz.cncnlp.soundwave.ui.player.seekStepIndex(dragRatio, duration)) xyz.cncnlp.soundwave.ui.common.AppHaptics.segmentTick(seekHapticView); dragRatio = s },
            onDragEnd = { dragging = false; onSeek(dragRatio) },
            onDragCancel = { dragging = false },
            onSeekTap = onSeek,
            onQuality = onQuality,
            playing = playing,
            playMode = playMode,
            volume = volumeLevel,
            onVolume = { setSystemVolume(it) },
            showVolume = showPlayerVolume,
            volumePressed = volumePressed,
            onVolumePressed = { volumePressed = it },
            onPrev = onPrev,
            onToggle = onToggle,
            onNext = onNext,
            onLyrics = { openLyricsImmersive() },
            onDevice = { showDeviceSheet = true },
            // 内嵌队列只切换播放器中段；不再走父层空回调或第二个面板。
            onQueue = { if (midPanel == "queue") returnToPreviousPlayerMode() else openQueueInLyrics() },
            modeAsBottomAction = lyricsStyleLayout,
            onMode = onMode,
            immersiveSelected = immersiveMode,
            deviceOn = showDeviceSheet,
            queueOn = midPanel == "queue",
            showDeviceAndQueueActions = playerShowsDeviceAndQueueActions(layout, pageLyrics, false),
            foreground = playerPrimary,
            lightControls = !appleWhiteFrost
        )
    }
    BackHandler(enabled = midPanel == "queue" || immersiveMode || lyricLayout) {
        returnToPreviousPlayerMode()
    }
    BackHandler(enabled = lyricBrowseState.isSelecting) {
        lyricBrowseState = playerLyricCloseSelection(lyricBrowseState)
    }
    val density = LocalDensity.current
    val dismissView = androidx.compose.ui.platform.LocalView.current
    val statusBarRevealThresholdPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    fun updateDismissStatusBarExposure(offsetPx: Float) {
        dismissOffsetHolder.updateStatusBarExposure(offsetPx, statusBarRevealThresholdPx, onDismissGestureStatusBarExposure)
    }
    val playerContentHeightDp = with(density) { playerContentHeightPx.toDp().value }
    val playerBottomChromeHeightDp = with(density) { playerBottomChromeHeightPx.toDp().value }
    val immersiveLyricsEntrySpacer = if (immersiveCoverLyricsPage) {
        immersiveCoverLyricsHeaderSpacerDp(
            heroHeightDp = immersiveCoverArtworkHeight.value,
            statusBarInsetDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
        )
    } else {
        immersiveLyricsHeaderSpacerDp(
            heroHeightDp = immersiveCoverArtworkHeight.value,
            statusBarInsetDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value,
            contentHeightDp = playerContentHeightDp,
            controlsHeightDp = playerBottomChromeHeightDp
        )
    }.dp
    val configuredScreenHpx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val screenHpx = playerSurfaceHeightPx.takeIf { it > 0 }?.toFloat() ?: configuredScreenHpx
    val dismissOffsetState = dismissOffsetHolder.state
    var dismissAnimationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val dismissVelocityTracker = remember { xyz.cncnlp.soundwave.ui.player.PlayerDismissVelocityTracker() }
    val dismissTouchSlopPx = with(density) { 8.dp.toPx() }
    val dismissThresholdPx = maxOf(with(density) { 112.dp.toPx() }, screenHpx * 0.18f)
    // 入场只由 MainActivity 的单一父层转场驱动，避免内部/外部双重平移。
    fun animateDismissTo(target: Float) {
        dismissAnimationJob?.cancel()
        val start = dismissOffsetState.floatValue
        dismissAnimationJob = playerScope.launch {
            val animation = Animatable(start)
            animation.animateTo(target, animationSpec = spring(dampingRatio = 0.78f, stiffness = 480f)) {
                dismissOffsetHolder.update(value)
                updateDismissStatusBarExposure(value)
            }
            if (target == 0f) {
                dismissOffsetHolder.update(0f)
                updateDismissStatusBarExposure(0f)
            }
        }
    }
    fun resetDismiss() = animateDismissTo(0f)
    fun onDismissDrag(dragAmount: Float) {
        if (dragAmount == 0f) return
        if (dragAmount > 0f && midPanel == "queue") return
        dismissAnimationJob?.cancel()
        dismissAnimationJob = null
        val nextOffset = (dismissOffsetState.floatValue + dragAmount).coerceIn(0f, screenHpx * 1.05f)
        dismissOffsetHolder.update(nextOffset); dismissVelocityTracker.onDrag(nextOffset, dismissThresholdPx, dismissView)
        updateDismissStatusBarExposure(nextOffset)
    }
    fun onDismissEnd() {
        when (playerDismissDecision(
            offsetPx = dismissOffsetState.floatValue,
            thresholdPx = dismissThresholdPx,
            immersiveMode = immersiveMode,
            lyricPage = lyricLayout, velocityDownPxPerSec = dismissVelocityTracker.velocityDownPxPerSec(), velocityThresholdPxPerSec = with(density) { xyz.cncnlp.soundwave.ui.player.PLAYER_DISMISS_VELOCITY_DP_PER_SEC.dp.toPx() }
        )) {
            PlayerDismissDecision.RETURN_TO_PREVIOUS_MODE -> {
                // 歌词页是播放器内子模式；下滑只回到封面，不关闭整个播放器。
                animateDismissTo(0f)
                returnToPreviousPlayerMode()
            }
            // The host continues the same drag offset through its exit slide; never run a second sheet animation.
            PlayerDismissDecision.CLOSE_PLAYER -> {
                updateDismissStatusBarExposure(0f)
                onBack()
            }
            PlayerDismissDecision.RESET_OFFSET -> resetDismiss()
        }
    }
    val dismissGestureModifier = rememberPlayerDismissGestureModifier(
        songKey = song.key(),
        screenHeightPx = screenHpx,
        touchSlopPx = dismissTouchSlopPx,
        dismissOffsetState = dismissOffsetState,
        midPanel = midPanel,
        queueListState = queueListState,
        onDismissDrag = ::onDismissDrag,
        onDismissEnd = ::onDismissEnd,
        onResetDismiss = ::resetDismiss,
        onReturnToPreviousMode = ::returnToPreviousPlayerMode
    )
    // r673：渲染树（含 DefaultPlayerState / DefaultPlayerActions 的构造与所有回调闭包）整体放进独立 composable lambda，
    // FullPlayer 主体只保留状态与副作用，避免再次合成超大 dex 方法（见 ui/common/IsolatedSection.kt）。
    IsolatedSection {
    // BATCH2 反馈 F4：高频进度只在本 lambda 内读（原主体顶层读已删，见主体顶部注）——
    // 读取在 IsolatedSection 的作用域内执行，FullPlayer 主体不再被播放 tick 失效。
    val progress by progressState
    val currentTime by currentTimeState
    val displayTime = if (dragging) dragRatio * duration else currentTime
    // 「默认 / 标准」路由与所有样式共用的队列抽屉（r672：Apple 也用这一个）共用同一份只读快照与回调。
    val defaultPlayerState = DefaultPlayerState(song, queueSnapshot, queueCurrentIndex, playing, resolvingState.value, statusState.value, liked, playMode,
                    if (dragging) dragRatio else progress, displayTime, duration, playbackAudioFormatLabel(playbackAudioFormat).ifBlank { song.actualQuality }, volumeLevel, showVolumeInMenu,
                    lyrics, (displayTime * 1000f).toLong() + lyricOffset, lyricShowTranslation, lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricBlurDp = lyricBlurDp, lyricWordLiftEnabled = lyricWordLiftEnabled, lyricWordKaraoke = lyricWordKaraoke)
    val defaultPlayerActions = DefaultPlayerActions(onCollapse = onBack, onMore = { menuLevel = 0; showMenu = true }, onLike = onLike, onPrev = onPrev, onToggle = onToggle, onNext = onNext,
        onMode = onMode, onQuality = onQuality, onDevice = { showDeviceSheet = true }, onVolume = { setSystemVolume(it) }, onSeekTap = onSeek,
        onDragStart = { dragging = true; dragRatio = progress }, onDrag = { val s = xyz.cncnlp.soundwave.ui.player.seekStepSnapRatio(it, duration); if (xyz.cncnlp.soundwave.ui.player.seekStepIndex(s, duration) != xyz.cncnlp.soundwave.ui.player.seekStepIndex(dragRatio, duration)) xyz.cncnlp.soundwave.ui.common.AppHaptics.segmentTick(seekHapticView); dragRatio = s }, onDragEnd = { dragging = false; onSeek(dragRatio) }, onDragCancel = { dragging = false },
        onOpenLyrics = { toggleCoverLyricsMode() }, onCloseLyrics = { returnToPreviousPlayerMode() }, onLyricLine = { playLyricLine(it) },
        onLyricLongPress = { openLyricSelection(it) }, onOpenQueue = { openQueueInLyrics() }, onCloseQueue = { returnToPreviousPlayerMode() },
        onQueueItem = { selectedSong, items, index -> onQueueItem(selectedSong, items, index); returnToPreviousPlayerMode() },
        onQueueMove = onQueueMove, onQueueRemove = onQueueRemove, onQueueInsert = onQueueInsert,
        onLocateCurrent = { playerScope.launch { queueListState.animateScrollToItem(queueCurrentIndex.coerceAtLeast(0)) } },
        onToggleVolume = { toggleVolumeVisibility() }, onSetMode = { PlayerService.setMode(it) }, onClearQueue = onClearQueue,
        onEmptyLyricsLongPress = { showEmptyLyricActions = true })
    // Clip blur/scale overflow at the moving surface edge so it cannot tint the exposed source page.
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { playerSurfaceHeightPx = it.height }
            .onGloballyPositioned { playerSurfaceTopPx = it.positionInRoot().y }
            .graphicsLayer {
                clip = true
                translationY = playerDismissSurfaceOffsetPx(dismissOffsetState.floatValue, size.height)
            }
    ) {
        if (playerTransitionBackdropMovesWithSheet(layout)) {
            PlayerTransitionBackdrop(
                coverPainter = immersiveCoverPainter,
                coverPalette = coverPalette,
                coverLower = coverLower,
                modifier = Modifier.fillMaxSize()
            )
        }
    Box(
        Modifier
            .fillMaxSize()
            .then(dismissGestureModifier)
            .then(if (immersiveCoverBackdropArtwork == null) Modifier.background(ThemeTokens.PlayerBackground) else Modifier)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                if (lyricLayout && midPanel != "queue") returnToPreviousPlayerMode()
            }
    ) {
        // r673：背景层（模糊封面 / 遮罩 / 沉浸 Hero / 声波装饰）拆成独立 composable lambda。
        IsolatedBoxSection {
        // The outer player root owns the moving immersive backdrop; this inner sheet draws foreground content.
        if (defaultLayout) DefaultPlayerBackdrop(song.cover, Modifier.matchParentSize(), playing = playing) else if (song.cover.isNotEmpty()) {
            if (!immersiveCoverLayout || presentationMode == "apple") {
                AsyncImage(
                    model = CacheManager.coverModel(ctx, song.cover) ?: song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { clip = false }
                        .blur(if (fullBleedArtwork) (if (presentationMode != "apple") 24.dp else if (appleWhiteFrost) 50.dp else 42.dp) else if (appleWhiteFrost) 50.dp else 42.dp),
                    onSuccess = { result -> sampleCoverBackground(result.result.drawable)?.let { coverPalette = it } }
                )
            }
            if (fullBleedArtwork && presentationMode != "apple" && !immersiveCoverLayout) {
                AsyncImage(
                    model = CacheManager.coverModel(ctx, song.cover) ?: song.cover,
                    contentDescription = "${song.name} 封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { clip = false; alpha = 0.84f }
                        .then(
                            if (fullBleedCoverPage) Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { toggleCoverLyricsMode() } else Modifier
                        )
                )
            }
        }
        FullPlayerBackdropLayers(
            coverLayers = !defaultLayout && song.cover.isNotEmpty(),
            emptyCoverLayers = !defaultLayout && song.cover.isEmpty(),
            immersiveCoverLayout = immersiveCoverLayout,
            presentationMode = presentationMode,
            appleWhiteFrost = appleWhiteFrost,
            fullBleedArtwork = fullBleedArtwork,
            playerMask = playerMask,
            coverTop = coverPalette.top,
            coverLower = coverLower
        )
        if (immersiveCoverHeroLayer && song.cover.isNotEmpty()) {
            PlayerImmersiveCoverHero(
                // Apple 沉浸封面：Hero 常驻封面页与歌词页（封面页上它与 ApplePlayerCoverMorph 终态几何完全相同、垫在下面），
                // 页面淡出淡入时封面不消失；封面形态动画进行中隐藏，避免与移动中的封面叠影。
                // Apple 非沉浸：Hero 只属于歌词页，随歌词页一起淡入淡出。
                modifier = Modifier.fillMaxWidth().height(immersiveCoverArtworkHeight).align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = when {
                            !appleMode -> 1f
                            !coverImmersive -> applePageAlpha.value
                            pageLyrics -> 1f
                            else -> if (appleCoverProgress >= 0.999f) 1f else 0f
                        }
                    },
                coverPainter = immersiveCoverPainter,
                coverDescription = "${song.name} 封面",
                // r674（Apple 进出歌词页顶部闪一下的根因）：Hero 自带状态栏压暗渐变（黑 34%→透明），Apple 沉浸封面时
                // Hero 只在歌词页出现且 alpha 直接 = 1，换分支那一帧状态栏区域整块变暗 / 变亮（状态栏图标观感随之突变）。
                // 现在 Apple 下这条压暗随页面过渡 alpha 渐入渐出；其它样式保持常驻。
                fadeBridgeColor = { appleFadeBridge },
                statusScrimAlpha = { if (appleMode) (if (pageLyrics) applePageAlpha.value else 0f) else 1f },
                onClick = { toggleCoverLyricsMode() }
            )
        }

        // 播放页只增加装饰图案：低对比声波圆环位于背景层，不占布局、不接收触摸（见 PlayerSoundRingsDecor）。
        if (!defaultLayout && presentationMode != "apple") PlayerSoundRingsDecor(Modifier.fillMaxSize())
        } // IsolatedBoxSection: backdrop

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .onSizeChanged { playerContentHeightPx = it.height }
                .then(if (pageMotion) Modifier.graphicsLayer {
                    val a = applePageAlpha.value
                    alpha = a
                    // Apple 沉浸封面：封面页的封面与常驻 Hero 同几何叠放，整列位移会让两者错开出现重影，故只淡入淡出不位移。
                    translationY = if (appleMode && coverImmersive) 0f else playerPageShiftPx(a, pageIncoming, pageMotionDir, pageShiftPx)
                } else Modifier)
        ) {
            // r674（用户 10-22：Apple 进出歌词页顶部小横条瞬间消失/出现）：拖动条原本在随页面淡出淡入的列里，
            // 切换时会跟着整页 1→0→1 闪一下。改为画在列外的常驻层（见下方 PlayerDragHandle），列里只留同高占位，布局不变。
            if (!defaultLayout) Spacer(Modifier.fillMaxWidth().height(PLAYER_DRAG_HANDLE_SLOT_DP.dp))

            if (defaultLayout) {
                DefaultPlayerRoute(lyricLayout, midPanel == "queue", defaultPlayerState, defaultPlayerActions,
                    queueListState, queueVisibilityState, Modifier.weight(1f),
                    coverOnlyImmersive = playerUsesCoverOnlyImmersion(layout))
            } else if (pageLyrics) { IsolatedColumnSection { // r673：歌词页分支独立成 composable lambda
                Spacer(Modifier.height(if (immersiveCoverLyricsPage) immersiveLyricsEntrySpacer else 31.dp))
                if (midPanel != "queue" && !immersiveLayout && !immersiveCoverLayout) {
                  if (presentationMode != "apple") {
                    PlayerLyricsPageHeader(
                        songTitle = song.name,
                        artistName = song.singer,
                        onArtistClick = { TrackNavigation.openArtist(ctx, song) },
                        coverScale = coverScale,
                        immersiveMode = immersiveMode,
                        playing = playing,
                        liked = liked,
                        primaryColor = white,
                        secondaryColor = soft,
                        moreIconTint = if (LocalThemeVisual.current.menuIconWhite) Color.White else white,
                        artworkContent = { artworkModifier ->
                            // ACCEPT5 收编：默认封面常驻垫底——加载/失败不再透明透出页面底。
                            Box(artworkModifier) { DefaultLandscapeCover(Modifier.fillMaxSize())
                                if (song.cover.isNotEmpty()) AsyncImage(model = CacheManager.coverModel(ctx, song.cover) ?: song.cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        },
                        onCoverClick = { toggleCoverLyricsMode() },
                        onToggle = onToggle,
                        onNext = onNext,
                        onLike = onLike,
                        onMore = { menuLevel = 0; showMenu = true }
                    )
                  }
                }
                if (immersiveCoverLyricsPage) {
                    // 沉浸歌词页不重复画方形封面；保留与普通歌词页等高的标题/播放区。
                    val headerPrimary = if (brightImmersiveCoverLyricsHeader) immersiveCoverLyricsHeaderPrimaryColor(coverArtworkLuminance) else white
                    val headerSecondary = if (brightImmersiveCoverLyricsHeader) immersiveCoverLyricsHeaderSecondaryColor(coverArtworkLuminance) else soft
                    PlayerImmersiveCoverLyricsHeader(
                        songTitle = song.name,
                        artistName = song.singer.ifEmpty { "未知歌手" },
                        onArtistClick = { TrackNavigation.openArtist(ctx, song) },
                        primaryColor = headerPrimary,
                        secondaryColor = headerSecondary,
                        surfaceColor = if (brightImmersiveCoverLyricsHeader && FrostedPrefs.themeMode != "light") immersiveCoverLyricsHeaderSurfaceColor(coverArtworkLuminance) else null,
                        playing = playing,
                        onToggle = onToggle,
                        onNext = onNext
                    )
                    Spacer(
                        Modifier.height(
                            if (immersiveCoverLyricsPage) IMMERSIVE_LYRICS_HEADER_TO_LYRICS_GAP_DP.dp
                            else 8.dp
                        )
                    )
                }
                // 所有全屏歌词样式共享同一歌词视口起点与顶部间距。
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            top = when {
                                immersiveCoverLyricsPage -> IMMERSIVE_LYRICS_CONTENT_TOP_PADDING_DP.dp
                                else -> 12.dp
                            }
                        )
                ) { IsolatedBoxSection {
                    if (lyrics.isEmpty()) {
                        xyz.cncnlp.soundwave.ui.player.PlayerLyricsEmptyState(resolvingState.value, SoundwaveDesignPalette.Accent, soft, Modifier.fillMaxSize(), onLongPress = { showEmptyLyricActions = true })
                    } else if (presentationMode == "apple" && immersiveCoverLyricsPage) {
                        // Use the default route's actual follow spring, stagger, scale and timed-word renderer.
                        SharedPlayerLyricFlow(
                            defaultPlayerState,
                            defaultPlayerColors().copy(ink = white, muted = soft), Modifier.fillMaxSize(), true,
                            onLyricLine = { playLyricLine(it) }, onLyricLongPress = { openLyricSelection(it) }
                        )
                    } else {
                        val curMs = (displayTime * 1000f).toLong() + lyricOffset
                        val timedLyricIndices = remember(lyrics) {
                            lyrics.mapIndexedNotNull { index, line ->
                                line.timeMs.takeIf { it >= 0L }?.let { index to it }
                            }
                        }
                        val active = playerActiveTimedLyricIndex(timedLyricIndices, curMs)
                        val focusedLyricIndex = playerLyricFocusedIndex(lyricBrowseState, active)
                        val immersiveCoverLyricsMode = immersiveCoverLayout && immersiveMode
                        val fullLyricsPageScrollMode = lyricLayout && !immersiveCoverLyricsMode
                        // Full-page lyric routes keep the complete song in the lazy flow for Apple-style follow scrolling.
                        val lyricWindowIndices = playerLyricsViewportIndices(lyrics.size, active, fullLyricsPage = lyricLayout)
                        val lyricHorizontalAlignment = playerFullscreenLyricsHorizontalAlignment(immersiveMode)
                        val lyricTextAlignment = playerFullscreenLyricsTextAlignment(immersiveMode)
                        val lyricScrollTrackKey = playerImmersiveLyricsScrollTrackKey(
                            immersiveCoverLyricsMode,
                            song?.key().orEmpty()
                        )
                        val lyricScrollRouteKey = if (fullLyricsPageScrollMode) "$layout:${song.key()}:${lyrics.size}" else ""
                        PlayerLyricsRoute(lyrics = lyrics,
                            lyricWindowIndices = lyricWindowIndices,
                            activeIndex = active,
                            selectedIndex = focusedLyricIndex,
                            browseState = lyricBrowseState,
                            manualBrowsing = lyricBrowseState.isBrowsing,
                            currentTimeMs = curMs,
                            duration = duration,
                            lyricOffset = lyricOffset,
                            lyricLayout = lyricLayout,
                            immersiveMode = immersiveMode,
                            immersiveCoverLyricsMode = immersiveCoverLyricsMode,
                            fullLyricsPageScrollMode = fullLyricsPageScrollMode,
                            lyricScrollTrackKey = lyricScrollTrackKey,
                            lyricScrollRouteKey = lyricScrollRouteKey,
                            lyricScale = lyricScale,
                            lyricFontSp = lyricFontSp,
                            lyricImmersiveSp = lyricImmersiveSp,
                            lyricGapDp = lyricGapDp,
                            lyricImmersiveGapDp = lyricImmersiveGapDp,
                            lyricBlurDp = lyricBlurDp,
                            lyricHighlightHex = playerThemeLyricHex(lyricHighlightHex, appleWhiteFrost, focused = true),
                            lyricUnhighlightHex = playerThemeLyricHex(lyricUnhighlightHex, appleWhiteFrost, focused = false),
                            lyricBold = lyricBold,
                            lyricShowTranslation = lyricShowTranslation,
                            lyricShowRomanization = lyricShowRomanization,
                            lyricWordKaraoke = lyricWordKaraoke,
                            lyricWordLiftEnabled = lyricWordLiftEnabled,
                            reduceMotion = reduceMotion,
                            horizontalAlignment = lyricHorizontalAlignment,
                            textAlignment = lyricTextAlignment,
                            onBeginBrowsing = { beginLyricBrowsing() },
                            onSelect = { selectLyric(it) },
                            onSettleBrowsing = { settleLyricBrowsing(it) },
                            onPlay = { playLyricLine(it) },
                            onLongPress = { openLyricSelection(it) }
                        )
                    }
                } } // IsolatedBoxSection: lyrics viewport
                Spacer(Modifier.height(if (immersiveCoverLyricsPage) IMMERSIVE_LYRICS_CONTROLS_GAP_DP.dp else 24.dp))
                if (!immersiveCoverLyricsPage) {
                    if (immersiveCoverLayout) {
                        val coverChromeVisible = !chromeAutoHide || chromeVisible
                        val coverChromeAlpha by animateFloatAsState(
                            targetValue = if (coverChromeVisible) 1f else 0f,
                            animationSpec = tween(180),
                            label = "immersiveCoverChromeAlpha"
                        )
                        // 仅淡出、不收缩测量高度，避免歌词视口变化后自动滚动造成纵向晃动。
                        Box(Modifier.fillMaxWidth().onSizeChanged { playerBottomChromeHeightPx = it.height }) {
                            PlayerBottomControlChrome(
                                Modifier.graphicsLayer { alpha = coverChromeAlpha }
                                    .then(if (coverChromeVisible) Modifier else Modifier.clearAndSetSemantics {})
                            )
                            if (!coverChromeVisible) {
                                Box(
                                    Modifier.matchParentSize()
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            chromeVisible = true
                                            interactionTick++
                                        }
                                        .semantics { contentDescription = "显示播放器控件" }
                                )
                            }
                        }
                    } else {
                        androidx.compose.animation.AnimatedVisibility(visible = !chromeAutoHide || chromeVisible) {
                            PlayerBottomControlChrome()
                        }
                    }
                }
            } } else { IsolatedColumnSection { // r673：封面页分支独立成 composable lambda
                if (presentationMode == "apple") {
                    xyz.cncnlp.soundwave.ui.player.ApplePlayerCoverMorph(
                        progress = appleCoverProgress,
                        normalSize = LocalConfiguration.current.screenWidthDp.dp - ThemeTokens.PlayerControls.HorizontalPadding * 2,
                        immersiveHeight = immersiveCoverHeroHeight,
                        artworkHeight = immersiveCoverArtworkHeight,
                        surfaceTopPx = playerSurfaceTopPx,
                        coverScale = coverScale,
                        hasArtwork = song.cover.isNotEmpty(),
                        coverLower = coverLower,
                        playerBackground = ThemeTokens.PlayerBackground,
                        primaryColor = playerPrimary,
                        resolving = resolvingState.value, status = statusState.value,
                        onClick = { toggleCoverLyricsMode() },
                        bridgeColor = { appleFadeBridge },
                        // ACCEPT5 收编：默认封面垫底——painter 无图/加载中不再是透明空框（外层 morph 裁切不变）。
                        artworkContent = { m -> Box(m) { DefaultLandscapeCover(Modifier.fillMaxSize()); Image(immersiveCoverPainter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                    )
                } else {
                // Immersive cover artwork lives in the bounded upper Hero layer; never render a floating square card.
                Spacer(
                    Modifier.height(if (immersiveCoverLayout) 68.dp else 52.dp).fillMaxWidth()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { toggleCoverLyricsMode() }
                )
                if (fullBleedArtwork && presentationMode != "apple") {
                    // Keep the prior vertical rhythm while the artwork itself remains an edge-to-edge background layer.
                    Spacer(
                        Modifier.fillMaxWidth().height(immersiveCoverHeroHeight)
                            .semantics { contentDescription = "封面，点按切换封面呈现" }
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { toggleCoverLyricsMode() }
                    )
                } else if (immersiveCoverLayout) {
                    Spacer(
                        Modifier.fillMaxWidth().height(immersiveCoverHeroHeight)
                            .semantics { contentDescription = "封面，点按切换封面呈现" }
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { toggleCoverLyricsMode() }
                    )
                } else {
                    PlayerCoverArtworkCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ThemeTokens.PlayerControls.HorizontalPadding)
                            .semantics { contentDescription = if (presentationMode == "apple") "封面，点按切换封面呈现" else "封面，点按查看歌词" },
                        coverFraction = mainCoverFraction,
                        coverScale = coverScale,
                        fullBleedCoverPage = fullBleedCoverPage,
                        hasArtwork = song.cover.isNotEmpty(),
                        coverLower = coverLower,
                        playerBackground = ThemeTokens.PlayerBackground,
                        primaryColor = playerPrimary,
                        resolving = resolvingState.value,
                        status = statusState.value,
                        onClick = { toggleCoverLyricsMode() },
                        artworkContent = { artworkModifier ->
                            AsyncImage(
                                model = CacheManager.coverModel(ctx, song.cover) ?: song.cover,
                                contentDescription = null,
                                modifier = artworkModifier,
                                contentScale = if (immersiveCoverLayout) ContentScale.Fit else ContentScale.Crop,
                                onSuccess = { result -> sampleCoverBackground(result.result.drawable)?.let { coverPalette = it } }
                            )
                        }
                    )
                }
                }
                // Apple Music：来源/队列/设备入口统一放到底部控制区

                if (lyricsStyleLayout) {
                    PlayerLyricsStylePreview(
                        lyrics, lyricIdx, lyricBrowseState, layout, layoutSpec.coverFraction,
                        displayTime, duration, lyricOffset, lyricScale, lyricFontSp, lyricImmersiveSp,
                        lyricGapDp, lyricImmersiveGapDp, lyricBlurDp, lyricHighlightHex, lyricUnhighlightHex,
                        lyricBold, lyricShowTranslation, lyricShowRomanization, lyricWordKaraoke, lyricWordLiftEnabled, reduceMotion, white,
                        onOpenLyrics = { openLyricsImmersive() }, onSelect = { selectLyric(it) },
                        onPlay = { playLyricLine(it) }, onLongPress = { openLyricSelection(it) },
                        onCompactPreviewTap = { index -> selectLyric(index); openLyricsImmersive() }
                    )
                }
                Spacer(
                    Modifier
                        .weight(1f)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            toggleCoverLyricsMode()
                        }
                )

                FullPlayerFooterRenderer(
                    song, liked, white, soft, LocalThemeVisual.current.menuIconWhite, hiddenVolumeShift,
                    onLike, onMore = { menuLevel = 0; showMenu = true }
                ) { PlayerBottomControlChrome(applyOffset = false) }
            } } // IsolatedColumnSection: cover page
        }
        if (!defaultLayout) PlayerDragHandle(Modifier.align(Alignment.TopCenter).statusBarsPadding()) { if (!returnToPreviousPlayerMode()) onBack() }

        // r673：覆盖层（队列抽屉 / 设备 / 菜单 / 歌词操作 / 修正对话框）拆成独立 composable lambda，避免 FullPlayer 单个 dex 方法过大。
        IsolatedBoxSection {
        if (!defaultLayout && midPanel == "queue") {
            // r672（用户 10-22）：Apple Music 样式的播放队列改用「默认 / 标准」同一个底部抽屉（覆盖在当前页之上，页面不切换）；
            // 旧的 Apple 专用整页队列（FullPlayerQueueRoute / PlayerQueuePanel / PlayerQueueSurface）已删除。
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                AnimatedVisibility(visibleState = queueVisibilityState, enter = EnterTransition.None, exit = ExitTransition.None) {
                    DefaultPlayerQueuePage(defaultPlayerState, defaultPlayerActions, queueListState, enterScope = this)
                }
            }
        }
        if (showDeviceSheet) {
            PlayerDeviceSheet(
                outputSnapshot = outputSnapshot,
                dlnaSnapshot = dlnaSnapshot,
                onDismissRequest = { showDeviceSheet = false },
                onSelectLocalRoute = { route -> toast(systemOutputController.selectRoute(route)) },
                onSelectSystemRoute = { route -> toast(systemOutputController.selectRoute(route)) },
                onOpenSystemOutput = { systemOutputController.openSystemOutput()?.let(::toast) },
                onRefreshDlna = { scanDlna() },
                onCastDlna = { device -> castDlna(device) },
                onPauseDlna = { dlnaPause() },
                onResumeDlna = { dlnaResume() },
                onStopDlna = { dlnaStop() }, songTitle = song.name, songCover = song.cover
            )
        }
        if (showMenu) {
            FullPlayerMenuSheet(
                song = song,
                liked = liked,
                playMode = playMode,
                sleepRemain = sleepRemain,
                lyricGapDp = lyricGapDp,
                lyricBlurDp = lyricBlurDp,
                lyricOffset = lyricOffset,
                lyricFontSp = lyricFontSp,
                playerLayout = playerLayout,
                menuLevel = menuLevel,
                transitionSeconds = transitionSeconds,
                showVolumeInMenu = showVolumeInMenu,
                isDisliked = { xyz.cncnlp.soundwave.ContextData(ctx, menuOwner).disliked(song) },
                onMode = onMode,
                onDismiss = { showMenu = false; menuLevel = 0 },
                onSetMenuLevel = { menuLevel = it },
                // ACCEPT4（审计 §9）：纯动作类回调不再同步关菜单——关闭由内容侧退场句柄 + 菜单 onDismiss 承担；
                // ACCEPT5（退出动画普查 P2）：打开其它表面类的回调（设备/取链/心动/睡眠/过渡）同样只落
                // 目标表面状态、不再同步清 showMenu，本层退场由内容侧句柄驱动、onDismiss 收尾清。
                onLike = { onLike() },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onAddNext = { onAddNext(song) },
                onDownload = { onDownload() },
                onOpenDevice = { showDeviceSheet = true },
                onPlaybackLink = { showPlaybackLink = true },
                onSimilar = { onSimilar(song) },
                onHeartMode = { onHeartMode(song) },
                onToggleDislike = {
                    val now = ContextData(ctx, menuOwner).toggleDislike(song)
                    toast(if (now) "已加入不喜欢，心动模式将跳过" else "已取消不喜欢")
                },
                onOpenSleepTimer = { showSleepBar = true },
                onOpenTransitionSettings = { showTransitionSettings = true },
                onToggleVolume = { toggleVolumeVisibility() },
                onSleepSelected = { m ->
                    // ACCEPT5（退出动画普查 P3）：只落定时+提示；showMenu/menuLevel 由菜单退场后
                    // onDismiss 清（此前在此同步清、内容侧句柄的退场被截断）。
                    onSleep(m)
                    toast(when {
                        m == 0 -> "已取消定时"
                        m < 0 -> "定时：播完当前"
                        else -> "定时 $m 分钟（到点后播完当前）"
                    })
                },
                onLyricGap = onLyricGap,
                onLyricBlur = onLyricBlur,
                onLyricOffset = { delta -> lyricOffset = PlayerLyricsSettingsModel.stepOffset(lyricOffset, delta); onLyricOffset(lyricOffset) },
                onLyricFont = onLyricFont,
                onSelectPlayerLayout = { key ->
                    // ACCEPT5（退出动画普查 P5）：只落样式；清状态归菜单退场后 onDismiss。
                    onPlayerLayout(key)
                }
            )
        }
        if (showTransitionSettings) {
            PlaybackTransitionSettingsSheet(onDismiss = { showTransitionSettings = false })
        }
    if (showPlaybackLink) {
        PlayerPlaybackLinkSheet(song = song, onDismiss = { showPlaybackLink = false })
    }
    if (showSleepBar) {
        SleepPlayerTimerDialog(remainMs = sleepRemain, onMinutes = { onSleep(it) }, onDismiss = { showSleepBar = false })
    }
    val openLyricCorrectionSearch = { lyricCorrection.selectedCandidateIndex = -1; lyricCorrection.showDialog = true
        lyricCorrection.search(ctx, song, playerScope, (duration * 1000f).toLong().takeIf { it > 0L } ?: song.duration) { song.key() } }
    xyz.cncnlp.soundwave.ui.player.PlayerLyricActionSheets(
        selecting = lyricBrowseState.isSelecting,
        selectedLine = lyrics.getOrNull(lyricBrowseState.selectedIndex ?: lyricIdx),
        emptyOpen = showEmptyLyricActions,
        showTranslation = lyricShowTranslation, showRomanization = lyricShowRomanization,
        hasCorrection = lyricCorrection.hasCorrection,
        onDismissSelecting = { lyricBrowseState = playerLyricCloseSelection(lyricBrowseState) },
        onDismissEmpty = { showEmptyLyricActions = false },
        onCorrectionSelecting = { openLyricCorrectionSearch() },
        onCorrectionEmpty = { openLyricCorrectionSearch() },
        onToggleTranslationSelecting = { onLyricShowTranslation(!lyricShowTranslation) },
        onToggleRomanizationSelecting = { onLyricShowRomanization(!lyricShowRomanization) },
        onToggleTranslationEmpty = { onLyricShowTranslation(!lyricShowTranslation) },
        onToggleRomanizationEmpty = { onLyricShowRomanization(!lyricShowRomanization) },
        onRefetch = { onRefetchLyrics() },
        lyricFontSp = lyricFontSp, lyricGapDp = lyricGapDp, lyricBlurDp = lyricBlurDp, lyricOffset = lyricOffset,
        onLyricFont = onLyricFont, onLyricGap = onLyricGap, onLyricBlur = onLyricBlur,
        onLyricOffsetDelta = { delta -> lyricOffset = PlayerLyricsSettingsModel.stepOffset(lyricOffset, delta); onLyricOffset(lyricOffset) },
        onToggleFloatingSelecting = if (defaultLayout) ({ toggleFloatingLyric(ctx) }) else null,
        onToggleFloatingEmpty = if (defaultLayout) ({ toggleFloatingLyric(ctx) }) else null
    )
    if (lyricCorrection.showDialog) {
        PlayerLyricCorrectionDialog(
            song = song,
            correctionCandidates = lyricCorrection.candidates,
            correctionSearching = lyricCorrection.searching,
            correctionMessage = lyricCorrection.message,
            hasLyricCorrection = lyricCorrection.hasCorrection,
            selectedCandidateIndex = lyricCorrection.selectedCandidateIndex,
            onSelectCandidate = { lyricCorrection.selectedCandidateIndex = it },
            onSearchLyricCorrection = { lyricCorrection.search(ctx, song, playerScope, (duration * 1000f).toLong().takeIf { it > 0L } ?: song.duration) { song.key() } },
            onApplyLyricCorrection = { lyricCorrection.apply(ctx, song, it, menuOwner, onLyricCorrectionRefresh) { m -> toast(m) } },
            onClearLyricCorrection = { lyricCorrection.clear(ctx, song, menuOwner, onLyricCorrectionRefresh) { m -> toast(m) } },
            onDismiss = { lyricCorrection.showDialog = false }
        )
    }
        } // IsolatedBoxSection: overlays
    }
    }
    } // IsolatedSection: render tree
}


