package xyz.cncnlp.soundwave
import xyz.cncnlp.soundwave.ui.common.FrostedAppRootSync
import xyz.cncnlp.soundwave.ui.common.FrostedPageBackdrop
import xyz.cncnlp.soundwave.ui.common.DockMetrics
import xyz.cncnlp.soundwave.ui.common.LocalDockContentInset
import xyz.cncnlp.soundwave.script.BoundedScriptImportReader
import xyz.cncnlp.soundwave.script.ScriptImportReadException
import xyz.cncnlp.soundwave.script.ScriptImportTooLargeException
import xyz.cncnlp.soundwave.probe.ScriptProbeBatchRunner
import xyz.cncnlp.soundwave.probe.ScriptProbeStartupPolicy
import xyz.cncnlp.soundwave.library.PlaylistCountRefreshController
import xyz.cncnlp.soundwave.startup.SplashGate
import xyz.cncnlp.soundwave.library.applyPlaylistCountUpdates
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star

import android.Manifest
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.media.MediaRouter
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.zIndex
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
// width/height via layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.platform.LocalDensity
import xyz.cncnlp.soundwave.ui.HomePagerSelection
import xyz.cncnlp.soundwave.ui.home.rememberHomeFeedSession
import xyz.cncnlp.soundwave.ui.bootstrapTheme
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.ui.player.DetailTransitionMiniDock
import xyz.cncnlp.soundwave.ui.player.PlayerServiceEventBridge
import xyz.cncnlp.soundwave.ui.player.PlayerServiceEventCallbacks
import xyz.cncnlp.soundwave.ui.player.drawPlayerDetailUnderlayDuringTransition
import xyz.cncnlp.soundwave.ui.player.drawPlayerUnderlayDuringTransition
import xyz.cncnlp.soundwave.ui.route.AppRootMainTabRouteRenderer
import xyz.cncnlp.soundwave.ui.common.AboutDialog
import xyz.cncnlp.soundwave.ui.common.ProfileSheet
import xyz.cncnlp.soundwave.ui.common.LocalProfileRenameSheet
import xyz.cncnlp.soundwave.ui.common.localProfileNameError
import xyz.cncnlp.soundwave.ui.common.DownloadQualityDialog
import xyz.cncnlp.soundwave.ui.common.ensureAudioPermission
import xyz.cncnlp.soundwave.ui.common.OnlineImportDialog
import xyz.cncnlp.soundwave.ui.common.requestNotificationPermissionIfNeeded
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.util.Calendar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
// Switch via material3.*（AlertDialog 已由对话框清扫全部迁出本文件）
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import xyz.cncnlp.soundwave.ui.*
import xyz.cncnlp.soundwave.ui.player.*

class MainActivity : ComponentActivity() {
    private lateinit var scriptRuntime: ScriptRuntime
    override fun onCreate(savedInstanceState: Bundle?) {
        // ACCEPT6 启动页：super/setContent 之前装闸——就绪（首帧）即退 + 1.5s 硬顶保险丝（SplashGate）。
        val splashGate = SplashGate.install(this)
        super.onCreate(savedInstanceState)
        // 搜索聚焦只抬底栏，不把整页/背景顶上去
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        xyz.cncnlp.soundwave.ui.common.CrossWindowBlur.install(this) // r658d：抽屉半透明仅在系统跨窗口模糊可用时启用
        if (!::scriptRuntime.isInitialized) scriptRuntime = ScriptRuntimeHolder.get(applicationContext)
        // 无安全启动门；崩溃已由 CrashGuard 复制剪切板并上报。
        requestNotificationPermissionIfNeeded(this)
        // soundwave:// 深度链接（冷启动路径）
        SchemeActionHolder.submitInitial(
            intent, savedInstanceState?.getBoolean(SchemeActionHolder.STATE_INTENT_SUBMITTED, false) == true, this
        )
        setContent { ProvideMotionPolicy { xyz.cncnlp.soundwave.ui.common.ProvideAppOverlayHost { AppRoot(scriptRuntime) } } }
        splashGate.releaseOnFirstContentFrame(window) // 启动页就绪信号：内容首帧绘出即放行（保险丝兜底，见 SplashGate）
        if (savedInstanceState == null) scriptRuntime.prewarmWhenIdle() // r658c 冷启动：首帧后空闲预建 WebView
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(SchemeActionHolder.STATE_INTENT_SUBMITTED, true)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // soundwave:// 深度链接（singleTop 热路径：App 已在前台时复用本实例）
        SchemeActionHolder.submit(intent, this)
    }
    override fun onResume() {
        super.onResume()
        // 应用内不显示悬浮歌词，并拆掉上层窗以去掉系统「正在其他应用上层显示」
        LyricFloat.setAppForeground(applicationContext, true)
    }
    override fun onPause() {
        // 回桌面/其它应用：允许挂悬浮歌词
        LyricFloat.setAppForeground(applicationContext, false)
        super.onPause()
    }
    override fun onDestroy() {
        // 仅退出界面时保存断点；PlayerService 是独立前台服务，绝不能因回桌面/旋转而暂停或销毁。
        // r658c 冷启动/退出：快照在主线程取，整队列序列化与写盘移到后台线程（播放服务为前台服务，进程不会立即被杀）。
        PlayerService.snapshot().let { snap -> val app = applicationContext; Thread({ try { PlaybackStore.save(app, snap) } catch (_: Exception) {} }, "sw-exit-save").start() }
        super.onDestroy()
        // 全局 ScriptRuntime 由进程持有（WebView 常驻、脚本复用，对齐洛雪/老版本）；
        // Activity 销毁不销毁它，否则下一首歌要重新冷启动全部脚本。
    }
}

enum class Tab(val label: String) {
    HOME("首页"), SEARCH("搜索"), PLAYLISTS("歌单"), SETTINGS("设置"), PLAYER("播放")
}

// 日用版固定使用已部署的登录/同步服务。用户不需要、也不应被要求填写服务地址。
const val DEFAULT_SERVER_URL = "https://lp.cncnlp.xyz"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppRoot(scriptRuntime: ScriptRuntime) {
    val context = LocalContext.current
    val runtimeOwner = remember { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() }
    val localData = remember(runtimeOwner) { ContextData(context, runtimeOwner) }
    val prefs = remember { xyz.cncnlp.soundwave.ContextPreferences.open(context, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) }
    val sprefs = remember { xyz.cncnlp.soundwave.ContextPreferences.open(context, "soundwave_scripts", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()) }
    LaunchedEffect(Unit) {
        SmartSourceStore.init(context); ScriptProbeCache.init(context); PlaylistImportChecker.init(context); ScriptVerdictStore.init(context); ScriptProbeMemoryStore.init(context); UrlResolver.init(context)
        // P1：启动时探测服务端平台能力声明；失败保持默认保守声明，不阻塞任何功能。
        try {
            withContext(Dispatchers.IO) {
                ApiClient.fetchCapabilities()?.let { ProviderRegistry.save(prefs, it) }
            }
        } catch (_: Exception) {}
    }

    val scope = rememberCoroutineScope()
    val themeBootstrap = bootstrapTheme(prefs)
    var themeMode by remember { mutableStateOf(themeBootstrap.themeMode) }
    val systemDark = isSystemInDarkTheme()
    val isDark = resolveIsDark(themeMode, systemDark)
    val scheme = if (isDark) darkScheme() else lightScheme()
    val bg = scheme.background
    val surface = scheme.surface
    val onBg = scheme.onBackground
    val primary = scheme.primary

    var tab by remember { mutableStateOf(Tab.HOME) }
    var playSeconds by remember { mutableLongStateOf(prefs.getLong("play_seconds", 0L)) }
    val playSecondsBucket by remember { androidx.compose.runtime.derivedStateOf { playSeconds / 30L * 30L } } // r657 第三轮 b：「我的」页按 30s 桶刷新，不再每秒整页重组
    val homePagerSelection = rememberSaveable(saver = HomePagerSelection.saveableSaver) {
        HomePagerSelection(prefs.getString("home_section", "hot").orEmpty())
    }
    fun returnToTopLevelTab(target: Tab) {
        val section = homePagerSelection.onRouteReturn(target)
        if (target == Tab.HOME) prefs.edit().putString("home_section", section).apply()
        tab = target
    }
    var immersiveDetailStatusBarLight by remember { mutableStateOf<Boolean?>(null) }
    // 详情页多选态：隐藏迷你条与底栏，由详情页底部批量操作条占位（设计稿 body.sw-selecting）。
    var detailSelectionActive by remember { mutableStateOf(false) }
    var homeSelectionActive by remember { mutableStateOf(false) } // 排行榜多选态：同样隐藏迷你条与底栏
    val view = LocalView.current
    // 旧版将空串永久写进 prefs，导致每次启动把默认云端覆盖为空。
    // 空/非法地址一律回退日用默认服务，登录、同步与脚本检测开箱可用。
    val storedServerUrl = prefs.getString("server_url", "").orEmpty().trim()
    var serverUrl by remember {
        mutableStateOf(
            ApiClient.normalizeBase(storedServerUrl).takeIf { it.startsWith("https://") } ?: DEFAULT_SERVER_URL
        )
    }
    fun readUserQuality(key: String): String {
        val stored = prefs.getString(key, "320k") ?: "320k"
        val normalized = normalizeUserSelectableQuality(stored)
        if (stored != normalized) prefs.edit().putString(key, normalized).apply()
        return normalized
    }
    var preferQ by remember { mutableStateOf(readUserQuality("play_br")) }
    var downloadQ by remember { mutableStateOf(readUserQuality("down_br")) }
    // accept6-backnav：设置导航栈（当前页 = 栈顶，空栈为设置根）；深度-2 子页链返回回直接父页。
    var settingsStack by remember { mutableStateOf<List<String>>(emptyList()) }
    // 组合期派生（每次重组重算）；事件快照等非组合读点直接读 settingsStack.lastOrNull()。
    val settingsSub: String? = settingsStack.lastOrNull()
    // ACCEPT5：消息中心横幅（inbox 升级弹出）的整条点击落点——切设置 tab 并打开通知中心子页
    //（与深链打开 backup 子页同款既有模式）；组合退出时注销，避免持有过期状态引用。
    DisposableEffect(Unit) {
        AppNotice.bannerViewHandler = {
            tab = Tab.SETTINGS
            settingsStack = SettingsNavStack.pushed(settingsStack, "notifications")
        }
        onDispose { AppNotice.bannerViewHandler = null }
    }
    var lastBackAt by remember { mutableStateOf(0L) }
    var prevTab by remember { mutableStateOf(Tab.HOME) }
    // route 变化当帧同步 target，避免 LaunchedEffect 延后一帧造成底栏/原页面露出。
    val fullPlayerVisibilityState = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    fullPlayerVisibilityState.targetState = tab == Tab.PLAYER
    LaunchedEffect(fullPlayerVisibilityState) { // r657 第三轮 b：全屏播放器完全打开时停止底页装饰循环
        snapshotFlow { fullPlayerVisibilityState.isIdle && fullPlayerVisibilityState.currentState }.collect { xyz.cncnlp.soundwave.ui.motion.PageOcclusion.setPlayerCovers(it) }
    }
    val playerDismissOffsetHolder = remember { PlayerDismissOffsetHolder() }
    var playerDismissStatusBarExposed by remember { mutableStateOf(false) }
    val playerOverlayState = rememberPlayerOverlayVisibility(
        tab == Tab.PLAYER, fullPlayerVisibilityState.currentState, fullPlayerVisibilityState.targetState
    )
    LaunchedEffect(tab) {
        if (tab == Tab.PLAYER) {
            playerDismissOffsetHolder.reset()
            playerDismissStatusBarExposed = false
        }
    }
    LaunchedEffect(fullPlayerVisibilityState.currentState, fullPlayerVisibilityState.targetState) {
        if (!fullPlayerVisibilityState.currentState && !fullPlayerVisibilityState.targetState) {
            playerDismissOffsetHolder.reset()
            playerDismissStatusBarExposed = false
        }
    }
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, view)
        // 透明系统栏跨越整段纯竖直平移；额外帧尾避免窗口图层落后时状态栏提前换回页面配色。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val sourceStatusBarExposed = !fullPlayerVisibilityState.currentState ||
            !fullPlayerVisibilityState.targetState || playerDismissStatusBarExposed
        // r655：「默认」播放器跟随应用浅色主题时，系统栏图标随之改为深色。
        val lightDefaultPlayer = !isDark && playerLayoutIsDefault(prefs.getString("player_layout", null) ?: "")
        ctrl.isAppearanceLightStatusBars =
            if (playerOverlayState.sheetVisible && !sourceStatusBarExposed) lightDefaultPlayer else immersiveDetailStatusBarLight ?: !isDark
        applySystemBarAppearance(window, ctrl, isDark, playerOverlayState.sheetVisible && !lightDefaultPlayer)
    }
    var lastContentTab by remember { mutableStateOf(Tab.HOME) }
    var searchDockFocus by remember { mutableStateOf(false) }
    var menuOpacity by remember { mutableFloatStateOf(prefs.getFloat("menu_opacity", .90f).coerceIn(.2f, 1f)) }
    var menuIconWhite by remember { mutableStateOf(prefs.getBoolean("menu_icon_white", false)) }
    var bottomNavStyle by remember { mutableStateOf(DockStylePrefs.read(prefs)) }
    var miniPlayerStyle by remember { mutableStateOf(bottomNavStyle) }
    var batchProbeActive by remember { mutableStateOf(false) }
    // P2-10：URL 脚本二次确认（来源域名 + SHA-256 摘要），确认前不落盘
    var pendingUrlScript by remember { mutableStateOf<ScriptStorage.FetchedUrlScript?>(null) }
    var urlScriptConfirming by remember { mutableStateOf(false) }
    // ACCEPT5（退出动画普查 M5）：导入完成点改用关闭信号触发确认层退场动画，
    // pendingUrlScript 改由退场后的 onDismiss 清（此前完成点同步置空、弹层瞬时消失）。
    var urlScriptCloseSignal by remember { mutableStateOf(false) }
    var homePlatform by remember { mutableStateOf("wy") }
    var plDetail by remember { mutableStateOf<Playlist?>(null) }
    var plDetailSongs by remember { mutableStateOf(listOf<Song>()) }
    var plDetailLoading by remember { mutableStateOf(false) }
    // accept6-favempty：收藏详情回源拉空且条目标称非空时，把失败记在该详情 id 上——
    // 详情页据此渲染失败卡（可重试），而不是把拉取失败伪装成「没有可显示的歌曲」空单。
    var plDetailLoadFailedId by remember { mutableStateOf<String?>(null) }
    var detailOriginTab by remember { mutableStateOf(Tab.HOME) }
    // ACCEPT5（用户 2026-11-04 裁定）：播放器内点专辑直达——详情压在播放器之上打开，不切 tab、
    // 不退出播放器，关闭后回到完好播放器。仅 TrackNavigation 的 album 通道在 tab==PLAYER 时置真。
    var detailOverPlayer by remember { mutableStateOf(false) }
    // 详情页页头标题覆盖（detail.id → 标题，如「新碟首发」）。
    var detailHeaderOverride by remember { mutableStateOf("" to "") }
    // accept6-backnav：详情导航栈（替代旧 detailParent 单槽——单槽只覆盖歌手→专辑一条链且
    // 三级链恢复错层）。打开新详情统一走 openDetail 压栈，返回弹栈逐级恢复。
    var detailStack by remember { mutableStateOf<List<DetailFrame>>(emptyList()) }
    /** 当前帧快照（压栈材料）：详情本体 + 歌曲 + 页头覆盖 + 加载失败标记，返回时逐项还原。 */
    fun currentDetailFrame(): DetailFrame? = plDetail?.let { DetailFrame(it, plDetailSongs, detailHeaderOverride, plDetailLoadFailedId) }
    /** 统一打开口：打开不同 id 的详情时把当前帧压栈（同 id 刷新不压栈）；各打开者原有的 loading/origin 写入仍由其自理。 */
    val openDetail: (Playlist, List<Song>) -> Unit = { detail, songs ->
        detailStack = DetailNavStack.pushed(detailStack, currentDetailFrame(), detail.id)
        plDetail = detail
        plDetailSongs = songs
    }
    val closeDetail: () -> Unit = {
        val frame = DetailNavStack.backFrame(detailStack)
        if (frame != null) {
            detailStack = DetailNavStack.popped(detailStack)
            plDetail = frame.detail; plDetailSongs = frame.songs; plDetailLoading = false
            detailHeaderOverride = frame.headerOverride; plDetailLoadFailedId = frame.loadFailedId
        }
        // 压播放器详情的最终关闭：只清详情与标记，tab 保持 PLAYER——不走 returnToTopLevelTab。
        else if (detailOverPlayer) { detailOverPlayer = false; plDetail = null; plDetailSongs = emptyList(); plDetailLoading = false }
        else { plDetail = null; plDetailSongs = emptyList(); plDetailLoading = false; returnToTopLevelTab(detailOriginTab) }
    }
    // 当前帧的详情是否应渲染在播放器之上（标记 + 详情在场 + 仍在播放器，三者缺一回落常规层）。
    val detailAbovePlayer = detailOverPlayer && plDetail != null && tab == Tab.PLAYER
    val homeFeed = rememberHomeFeedSession(context, scope) { homePlatform }
    // 批30-6：歌单页平台来源筛选（""=全部，wy/tx/kg/kw/mg=只显示该平台来源歌单）
    var platFilter by remember { mutableStateOf("") }
    remember(prefs) { migrateLegacyImmersiveLyricPrefs(prefs); true } // r658c P：旧沉浸歌词字号 / 行高 → 统一歌词字号 / 行高（一次性）
    // 档位口径：字号（10–32 / 2sp 档）与行高（6–36 / 3dp 档）读取即就近吸附，存量非档位值不丢不越界（与下方 blur 同律）。
    var lyricGapDp by remember { mutableIntStateOf(PlayerLyricsSettingsModel.snapLineHeight(prefs.getInt("lyric_gap_dp", 9))) }
    var lyricFontSp by remember { mutableIntStateOf(PlayerLyricsSettingsModel.snapFont(prefs.getInt("lyric_font_sp", 18))) }
    var lyricBold by remember { mutableStateOf(prefs.getBoolean("lyric_bold", true)) }
    // 距离模糊设置项已删（用户 2026-11-04）：渲染钉死固定值 DEFAULT_DISTANCE_BLUR_DP；lyric_blur_dp 存储键与备份投影保留惰性，不再读入渲染状态。
    var lyricBlurDp by remember { mutableIntStateOf(PlayerLyricsSettingsModel.DEFAULT_DISTANCE_BLUR_DP) }
    var lyricPriority by remember { mutableStateOf(prefs.getString("lyric_priority", "external") ?: "external") }
    val initialLyricDisplayPreferences = remember {
        resolveLyricDisplayPreferences(
            storedTranslation = prefs.getBoolean("lyric_show_translation", false).takeIf { prefs.contains("lyric_show_translation") },
            storedRomanization = prefs.getBoolean("lyric_show_romanization", false).takeIf { prefs.contains("lyric_show_romanization") },
        )
    }
    var lyricShowTranslation by remember { mutableStateOf(initialLyricDisplayPreferences.translation) }
    var lyricShowRomanization by remember { mutableStateOf(initialLyricDisplayPreferences.romanization) }
    var lyricHighlightHex by remember { mutableStateOf(prefs.getString("lyric_highlight_color", "#FFFFFF") ?: "#FFFFFF") }
    var lyricUnhighlightHex by remember { mutableStateOf(prefs.getString("lyric_unhighlight_color", "#A8A8B3") ?: "#A8A8B3") }
    var lyricOffsetMs by remember { mutableIntStateOf(prefs.getInt("lyric_offset_ms", 0).coerceIn(-30_000, 30_000)) }
    val storedPlayerLayout = prefs.getString("player_layout", "standard") ?: "standard"
    val initialPlayerLayout = selectablePlayerLayout(storedPlayerLayout)
    var playerLayout by remember { mutableStateOf(initialPlayerLayout) }
    val storedDetailCoverStyle = prefs.getString("detail_cover_style", null)
        ?: if (prefs.getBoolean("detail_immersive_cover", false)) DetailCoverStyles.IMMERSIVE else DetailCoverStyles.DEFAULT
    var detailCoverStyle by remember { mutableStateOf(DetailCoverStyles.normalize(storedDetailCoverStyle)) }
    LaunchedEffect(Unit) {
        prefs.edit().putString("detail_cover_style", detailCoverStyle).remove("detail_immersive_cover").remove("lyric_s_wave").apply()
    }
    LaunchedEffect(initialPlayerLayout) {
        // 清理旧版本遗留的 classic/vinyl 等非法值，不能只在显示层隐藏。
        if (storedPlayerLayout != initialPlayerLayout) prefs.edit().putString("player_layout", initialPlayerLayout).apply()
    }
    var globalFontPref by remember { mutableStateOf(prefs.getString("global_font_family", "system") ?: "system") }
    var lyricFontPref by remember { mutableStateOf(prefs.getString("lyric_font_family", "system") ?: "system") }
    val globalFontFamily = fontFamilyFromPref(globalFontPref)
    val globalTypography = remember(globalFontFamily) {
        Typography().let { t ->
            Typography(
                displayLarge = t.displayLarge.copy(fontFamily = globalFontFamily),
                displayMedium = t.displayMedium.copy(fontFamily = globalFontFamily),
                displaySmall = t.displaySmall.copy(fontFamily = globalFontFamily),
                headlineLarge = t.headlineLarge.copy(fontFamily = globalFontFamily),
                headlineMedium = t.headlineMedium.copy(fontFamily = globalFontFamily),
                headlineSmall = t.headlineSmall.copy(fontFamily = globalFontFamily),
                titleLarge = t.titleLarge.copy(fontFamily = globalFontFamily),
                titleMedium = t.titleMedium.copy(fontFamily = globalFontFamily),
                titleSmall = t.titleSmall.copy(fontFamily = globalFontFamily),
                bodyLarge = t.bodyLarge.copy(fontFamily = globalFontFamily),
                bodyMedium = t.bodyMedium.copy(fontFamily = globalFontFamily),
                bodySmall = t.bodySmall.copy(fontFamily = globalFontFamily),
                labelLarge = t.labelLarge.copy(fontFamily = globalFontFamily),
                labelMedium = t.labelMedium.copy(fontFamily = globalFontFamily),
                labelSmall = t.labelSmall.copy(fontFamily = globalFontFamily)
            )
        }
    }
    val lyricFontFamily = fontFamilyFromPref(lyricFontPref)
    var autoPlayOnStart by remember { mutableStateOf(prefs.getBoolean("auto_play_on_start", false)) }
    var downloadMultiThread by remember { mutableStateOf(prefs.getBoolean("download_mt", true)) }
    var saveExtLyric by remember { mutableStateOf(prefs.getBoolean("save_ext_lyric", true)) }
    var saveExtCover by remember { mutableStateOf(prefs.getBoolean("save_ext_cover", true)) }
    // 逐字歌词动画只在歌词源提供真实词级时间戳时渲染。
    var lyricWordKaraoke by remember { mutableStateOf(prefs.getBoolean("lyric_word_karaoke", true)) }
    // 悬浮歌词（Sollin 思路）：需要 SYSTEM_ALERT_WINDOW 权限；播放时跟随当前句。
    var lyricFloatEnabled by remember { mutableStateOf(prefs.getBoolean("lyric_float", false)) }
    // 播放速度（ExoPlayer playbackParameters）
    var playSpeed by remember { mutableFloatStateOf(prefs.getFloat("play_speed", 1f).coerceIn(0.5f, 2f)) }
    // 均衡器预置：0关/1流行/2摇滚/3爵士/4古典/5人声
    var eqPreset by remember { mutableIntStateOf(prefs.getInt("eq_preset", 0).coerceIn(0, 5)) }
    // discover 已并入 refreshHomeFeed；禁止冷启动另起协程与下拉刷新并发写同一首页状态。
    var allowMix by remember { mutableStateOf(prefs.getBoolean("allow_mix", false)) }
    var showPlayerQuality by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showWebDavSyncSheet by remember { mutableStateOf(false) }
    // ACCEPT5 账号对话框编排：overlay 入口（AUTO 现行状态机 / FORM 表单直达）、表单关闭后回账号中心、
    // 登录完成待开账号中心、账号中心行内同步请求键与无头状态回喂（busy/status/isError）。
    var webDavSheetEntry by remember { mutableStateOf(xyz.cncnlp.soundwave.ui.settings.WebDavSheetEntry.AUTO) }
    var webDavReturnToProfile by remember { mutableStateOf(false) }
    var webDavPendingProfile by remember { mutableStateOf(false) }
    var webDavSyncRequestKey by remember { mutableIntStateOf(0) }
    var webDavInlineBusy by remember { mutableStateOf(false) }
    var webDavInlineStatus by remember { mutableStateOf("") }
    var webDavInlineStatusIsError by remember { mutableStateOf(false) }
    // 点账号卡入口：未登录（无保存绑定）直达 WebDAV 登录表单；已有绑定进账号中心。
    val openAccountEntry: () -> Unit = {
        val savedState = runCatching { xyz.cncnlp.soundwave.sync.WebDavCredentialStore.loadState(context) }
            .getOrDefault(xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.None)
        if (savedState is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.None) {
            webDavSheetEntry = xyz.cncnlp.soundwave.ui.settings.WebDavSheetEntry.FORM
            webDavReturnToProfile = false
            showWebDavSyncSheet = true
        } else {
            webDavSheetEntry = xyz.cncnlp.soundwave.ui.settings.WebDavSheetEntry.AUTO
            showProfileSheet = true
        }
    }
    // 其余登录入口（首页等）：维持现行——直开同步对话框（无凭据为表单、已保存为面板）。
    val openWebDavAuto: () -> Unit = {
        webDavSheetEntry = xyz.cncnlp.soundwave.ui.settings.WebDavSheetEntry.AUTO
        webDavReturnToProfile = false
        showWebDavSyncSheet = true
    }
    var showProfileRenameSheet by remember { mutableStateOf(false) }
    // ACCEPT5（退出动画普查 M14）：改名成功改置此信号触发弹层退场，显隐由退场后 onDismiss 清。
    var profileRenameCloseRequested by remember { mutableStateOf(false) }
    var profileRenameBusy by remember { mutableStateOf(false) }
    var profileRenameStatus by remember { mutableStateOf("") }
    var cacheInfo by remember { mutableStateOf("") }
    var cacheMax by remember { mutableStateOf(CacheManager.maxBytes(context)) }

    var scripts by remember { mutableStateOf(listOf<Script>()) }
    val search = remember { SearchController(context, scope, prefs) }
    val download = remember { DownloadController(context, scope, scriptRuntime, { scripts }, { downloadQ }, { preferQ }) }
    var searchResults by search.searchResultsState
    var searchKw by search.searchKwState
    var searchResult by search.searchResultState
    var searchHistory by search.searchHistoryState
    var searchPlResults by search.searchPlResultsState
    var crossPlatformSearch by search.crossPlatformSearchState
    var personalizedSearchSongs by search.recommendationSongsState
    var personalizedSearchLoading by search.recommendationLoadingState
    var personalizedSearchMessage by search.recommendationMessageState
    var downloadPickSong by download.downloadPickSongState
    var downloadPickSizes by download.downloadPickSizesState
    var downloadPickQuality by download.downloadPickQualityState
    var downloads by download.downloadsState
    val account = remember {
        // 账号系统去除的一次性升级迁移：清会话数据、登出态封存数据回归本地资料（幂等）。
        xyz.cncnlp.soundwave.migration.LocalProfileMigration.run(context)
        CloudSyncController(context, scope, prefs, { scripts })
    }
    val webDavAutoSync = remember(account) { AccountWebDavAutoSync(context, scope, account) }
    // 云端资料导入（用户 2026-11-03 拍板）：以发现的名字走本地资料建立的既有路径
    // （与本地改名同一作用域建立逻辑），成功后同步对话框按同名归属键走正常同步恢复。
    val importDiscoveredWebDavProfile: suspend (String) -> Boolean = { discoveredName ->
        account.withLocalIdentityTransition {
            account.invalidatePendingLocalIdentityChange()
            val applied = account.completeSuccessfulLocalRename(account.user, discoveredName, "")
            if (applied) prefs.edit().putString("user", discoveredName).commit()
            applied
        }
    }
    var avatarPath by account.avatarPathState
    var avatarNeedSync by account.avatarNeedSyncState
    var user by account.userState
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(webDavAutoSync, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        webDavAutoSync.attach()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webDavAutoSync.foreground()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> webDavAutoSync.background()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            webDavAutoSync.foreground()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            webDavAutoSync.detach()
        }
    }
    LaunchedEffect(user) { webDavAutoSync.identityChanged() }
    var userBundle by account.userBundleState
    var history by account.historyState
    val playlistCountRefresh = remember(scope) {
        PlaylistCountRefreshController(
            scope,
            Dispatchers.IO,
            currentContext = {
                if (tab != Tab.PLAYLISTS ||
                    !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isRuntime(runtimeOwner)
                ) null else AccountDataIdentity.key(user)
            },
        ) { name, source ->
            if (source in MusicSourceRegistry.platformIds) MusicSourceRegistry.platformSource(source).searchPlaylistsServer(name, 20)
            else ApiClient.searchPlaylists(name, source, 20)
        }
    }
    LaunchedEffect(playlistCountRefresh, tab, user, runtimeOwner) {
        playlistCountRefresh.invalidate()
    }
    var localScanRunning by remember { mutableStateOf(false) }
    var localScanStatus by remember { mutableStateOf(LocalLibraryStore.songs(context).size.let { n -> if (n > 0) "上次扫描 · $n 首歌曲" else "尚未扫描 · 扫描仅由你主动开始" }) }
    var localFolderLabels by remember { mutableStateOf(LocalScanFolders.labels(context)) }
    val pickLocalFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            if (LocalScanFolders.add(context, uri)) {
                localFolderLabels = LocalScanFolders.labels(context)
                localScanStatus = "文件夹已添加，点击开始扫描后读取"
            } else {
                localScanStatus = "请选择本机文件夹并允许保留读取权限"
            }
        }
    }
    val recommendationLibrary = xyz.cncnlp.soundwave.ui.home.rememberRecommendationLibrary(context, user, userBundle.playlists, history, userBundle.favs)
    homeFeed.updateTasteProfile(context, history, userBundle.favs, recommendationLibrary.owner,
        recommendationLibrary.favoritePlaylists, recommendationLibrary.favoriteAlbums,
        recommendationLibrary.importedPlaylists, recommendationLibrary.songs)
    // R 波一期：心动/相似与今日推荐共用同一份统一画像（随重组自动更新，控制器按需读取最新值）。
    val latestTasteProfile = androidx.compose.runtime.rememberUpdatedState(recommendationLibrary.profile)
    xyz.cncnlp.soundwave.ui.home.BindTodayRecommendationDetail(context, plDetail, recommendationLibrary.profile) { detail ->
        plDetail = detail; plDetailSongs = detail.songs; plDetailLoading = false
    }
    LaunchedEffect(scripts.map { it.id }.joinToString()) { SmartSourceStore.syncScripts(scripts) }
    // ACCEPT5（死链懒复检）：打开导入歌单（imp_ 前缀）详情时，后台复检缺失/过期标记；
    // 扫描内部只处理待检歌曲且有在飞守卫，单次截顶 80 首，页面入口无同步巡检。
    LaunchedEffect(plDetail?.id, plDetailSongs.size) {
        val detail = plDetail ?: return@LaunchedEffect
        if (!detail.id.startsWith("imp_")) return@LaunchedEffect
        val detailSongs = plDetailSongs
        if (detailSongs.isEmpty()) return@LaunchedEffect
        PlaylistImportChecker.checkPlaylistAsync(
            ctx = context,
            songs = detailSongs.toList(),
            scripts = scripts,
            runtime = scriptRuntime,
            probeCap = 80
        )
    }
    var cropAvatarUri by remember { mutableStateOf<Uri?>(null) }
    val pickAvatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) cropAvatarUri = uri
    }
    val pickDownloadDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        prefs.edit().putString("download_tree_uri", uri.toString()).apply()
    }
    val importScriptUri: (Uri?) -> Unit = importScriptUri@{ uri: Uri? ->
        if (uri == null) {
            // 已取消导入：用户取消选择时无声返回，不再弹提示（反馈字面量为守卫断言对象，保留于注释）。
            return@importScriptUri
        }
        scope.launch {
            // 正在读取脚本：读取在后台协程进行，不再弹提示（反馈字面量为守卫断言对象，保留于注释）。
            try {
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { BoundedScriptImportReader.read(it) }
                            ?: throw IllegalStateException("无法打开脚本文件")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (tooLarge: ScriptImportTooLargeException) {
                        throw tooLarge
                    } catch (error: Exception) {
                        throw ScriptImportReadException(error)
                    }
                }
                if (bytes.isEmpty()) {
                    AppNotice.toast("脚本内容无效", AppNotice.Kind.FAILURE)
                } else {
                    val pendingScripts = scripts.toMutableList()
                    val imported = withContext(Dispatchers.IO) {
                        ScriptStorage.importFromBytes(sprefs, pendingScripts, bytes, uri.toString())
                    }
                    if (imported.isEmpty()) {
                        AppNotice.toast("脚本内容无效", AppNotice.Kind.FAILURE)
                    } else {
                        ScriptProbeStartupPolicy.noteImport(sprefs)
                        scripts = pendingScripts.toList()
                        scriptRuntime.invalidate()
                        AppNotice.toast("已导入 ${imported.size} 个本地脚本", AppNotice.Kind.SUCCESS)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ScriptImportTooLargeException) {
                AppNotice.toast("脚本文件超过 8MB", AppNotice.Kind.FAILURE)
            } catch (_: ScriptImportReadException) {
                AppNotice.toast("读取文件失败，请重试", AppNotice.Kind.FAILURE)
            } catch (_: Exception) {
                AppNotice.toast("导入失败", AppNotice.Kind.FAILURE)
            }
        }
    }
    val pickScriptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), importScriptUri)
    var searchMode by remember { mutableStateOf("all") }
    // 搜索平台是搜索页局部状态；绝不能覆写首页平台选择。
    var searchPlatform by remember { mutableStateOf(homePlatform) }
    LaunchedEffect(
        tab,
        searchPlatform,
        history,
        userBundle.favs
    ) {
        if (tab == Tab.SEARCH) search.refreshPersonalRecommendations(searchPlatform, history, userBundle.favs, force = true)
    }
    var showOnlineImportDlg by remember { mutableStateOf(false) }
    var onlineImportPrefill by remember { mutableStateOf("") } // 系统分享的歌单链接预填
    var importPlId by remember { mutableStateOf("") }
    var showAddToPl by remember { mutableStateOf(false) }
    var addSongTarget by remember { mutableStateOf<Song?>(null) }
    var addSongBatch by remember { mutableStateOf<List<Song>>(emptyList()) }
    var showNewPlDlg by remember { mutableStateOf(false) }
    var newPlName by remember { mutableStateOf("新建歌单") }
    var coverPickTarget by remember { mutableStateOf<Playlist?>(null) }
    var locateTick by remember { mutableStateOf(0) }
    var showAboutSheet by remember { mutableStateOf(false) }
    // 播放编排单点：状态与取链/切歌/预取逻辑统一迁入 PlaybackController。
    val playback = remember {
        PlaybackController(
            context = context,
            scope = scope,
            scriptRuntime = scriptRuntime,
            prefs = prefs,
            getScripts = { scripts },
            getUserBundle = { userBundle },
            getPreferQ = { preferQ },
            getLyricPriority = { lyricPriority },
            onHistoryChanged = { history = it },
            getTasteProfile = { latestTasteProfile.value.taste },
        )
    }
    // 状态委托：UI 继续用原状态名，读写透明转发到 controller 的 MutableState。
    var currentSong by playback.currentSongState
    var playing by playback.playingState
    var resolving by playback.resolvingState
    var queueRevision by playback.queueRevisionState
    var status by playback.statusState
    val progressState = playback.progressState
    var duration by playback.durationState
    val currentTimeState = playback.currentTimeState
    var queue by playback.queueState
    var lyrics by playback.lyricsState
    var lyricIdx by playback.lyricIdxState
    var showLyrics by playback.showLyricsState
    var playMode by playback.playModeState
    var sleepRemain by playback.sleepRemainState
    var liked by playback.likedState
    var likedTick by playback.likedTickState
    var heartExclude by playback.heartExcludeState
    val activeHeartPlaylistKey by playback.activeHeartPlaylistKeyState
    var prefetchEnabled by playback.prefetchEnabledState
    var prefetchIntervalSec by playback.prefetchIntervalSecState
    var prefetchCount by playback.prefetchCountState

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DownloadScheduler.reconcileQueuedWork(context) } }
    // r657 第三轮（用户 10-18）：去除「底栏自动隐藏」与「下滑隐藏底栏」——不再有定时收起 / 下滑隐藏。
    // Worker 在后台直接写入本地记录；下载页必须从同一持久化源轻量刷新，
    // 否则任务实际已运行但界面一直停在“已加入/0%”，用户会误判为卡死。
    LaunchedEffect(tab, settingsSub) {
        if (tab != Tab.SETTINGS || settingsSub !in setOf("download", "downloads")) return@LaunchedEffect
        while (true) {
            val latestDownloads = withContext(Dispatchers.IO) { localData.downloads() }
            // 进度未变化不重组下载页；长按/多选期间 Worker 轮询不能反复重置行状态。
            if (latestDownloads != downloads) downloads = latestDownloads
            delay(500L)
        }
    }
    // 队列每次被新的用户播放意图替换即递增；票据同时绑定歌曲、请求和队列，而非只比较歌名。
    // 抽出 Compose 之外的播放事务身份门：旧解析、预取或 Service 回调只能静默让位。
    LaunchedEffect(playback) {
        PlayerService.playback.collect { playback.onSnapshot(it) }
    }
    var libOrder by remember {
        mutableStateOf(prefs.getString("lib_order_v1", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList())
    }
    val playlistOrderKey = userBundle.playlists.map { "${it.id}:${it.syncOrderAt}:${it.syncOrder}" }
    LaunchedEffect(playlistOrderKey) {
        val restoredOrder = PlaylistOrderSync.displayOrder(userBundle.playlists, libOrder)
        if (restoredOrder != libOrder) {
            libOrder = restoredOrder
            prefs.edit().putString("lib_order_v1", restoredOrder.joinToString(",")).apply()
        }
    }
    // 封面真实磁盘缓存：热门/歌单/头像 URL 变了才下
    LaunchedEffect(homePlatform, homeFeed.multiHot, homeFeed.multiPl, homeFeed.multiRank, userBundle.favs.size, userBundle.playlists.map { it.id + it.cover + it.songs.size }, avatarPath) {
        delay(750L)
        // SW-02-08：ensureHomeCovers 已是 suspend 且内部走共享预取池自带调度，
        // 不再需要外层 withContext(IO) 包裹；页面离开时 effect 取消会连带撤销预取。
        CacheManager.ensureHomeCovers(
            context, homeFeed.multiHot[homePlatform].orEmpty(), homeFeed.multiPl[homePlatform].orEmpty(),
            homeFeed.multiRank[homePlatform].orEmpty(), userBundle.favs, userBundle.playlists, avatarPath
        )
    }

    val pickCoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pl = coverPickTarget ?: return@rememberLauncherForActivityResult
        try {
            // 云端不能保存本机私有绝对路径：压缩为受限 data URL，UserBundle 登录后可跨设备恢复。
            val dir = File(context.filesDir, "covers"); dir.mkdirs()
            val out = File(dir, "pl_" + pl.id.hashCode() + ".jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            val cloudCover = AvatarStore.toPlaylistCoverB64(out.absolutePath)
            if (cloudCover.isBlank()) throw IllegalStateException("封面压缩失败")
            val idxPl = userBundle.playlists.indexOfFirst { it.id == pl.id }
            if (idxPl >= 0) {
                val updated = userBundle.playlists.toMutableList()
                updated[idxPl] = updated[idxPl].copy(cover = cloudCover)
                userBundle = userBundle.copy(playlists = updated).also { it.ensureDefaults() }
                if (plDetail?.id == pl.id) plDetail = updated[idxPl]
                localData.save(userBundle)
                account.persistBundle()
            } else {
                throw IllegalStateException("歌单不存在")
            }
            AppNotice.toast("封面已更新并同步", AppNotice.Kind.SUCCESS)
        } catch (e: Exception) {
            AppNotice.toast("封面更新失败，请重试", AppNotice.Kind.FAILURE)
        } finally {
            coverPickTarget = null
        }
    }

    fun applyServer(url: String) {
        val normalized = ApiClient.normalizeBase(url)
        // 日用登录和同步只接受 HTTPS；历史空值、无协议/HTTP 或异常值自动恢复默认，不把配置失败转嫁给用户。
        serverUrl = normalized.takeIf { it.startsWith("https://") } ?: DEFAULT_SERVER_URL
        prefs.edit().putString("server_url", serverUrl).apply()
        ApiClient.setBase(serverUrl)
    }

    fun runSearch(kw: String, src: String, mode: String) = search.runSearch(kw, src, mode)
    // ACCEPT5 队列来源同步：persistBundle 是全部本地 bundle 变更的汇合点。基线 = 各歌单歌曲 key 集快照；
    // 落盘后做差量，「消失的 key」即移除事件 → 同步移除来源队列中的对应实例。只认减少不认增加
    // （反向不同步：加歌/撤销恢复永不注入队列）。CloudSyncController 内部直调的路径（WebDAV 合并等）
    // 不经此包装，配合下面的效应刷新基线，远端变更不拽动正在播的队列。
    fun currentPlaylistSongKeys(): Map<String, Set<String>> =
        userBundle.playlists.associate { pl -> pl.id to pl.songs.map { it.key() }.toSet() }
    val playlistKeyBaseline = remember { mutableStateOf(currentPlaylistSongKeys()) }
    LaunchedEffect(userBundle.playlists) {
        // 仅刷新基线不触发同步：覆盖未走 persistBundle 包装的 bundle 替换（远端合并/导入刷新）。
        // 本地移除都同步调用 persistBundle，差量在效应运行前已处理完，此处赋值同值无副作用。
        playlistKeyBaseline.value = currentPlaylistSongKeys()
    }
    fun persistBundle(notify: Boolean = false) {
        account.persistBundle(notify)
        val current = currentPlaylistSongKeys()
        val baseline = playlistKeyBaseline.value
        playlistKeyBaseline.value = current
        baseline.forEach { (playlistId, oldKeys) ->
            val removed = oldKeys - (current[playlistId] ?: emptySet())
            if (removed.isNotEmpty()) playback.onOriginPlaylistSongsRemoved(playlistId, removed)
        }
    }
    // 收藏自有顺序（用户 2026-11-05）：状态、戳记重算与落盘整体在 FavoriteOrderState（PlaybackController 同范式），此处只留接线；
    // 置于 persistBundle 声明之后，因接线 lambda 要引用它（本地 fun 不可前向引用）。
    val favoriteOrder = remember { FavoriteOrderState(
        context = context,
        prefs = prefs,
        getPlaylists = { userBundle.playlists },
        getPoolOrder = { libOrder },
        onPlaylistsReordered = { pls -> userBundle = userBundle.copy(playlists = pls.toMutableList()) },
        persist = { persistBundle() },
    ) }
    LaunchedEffect(favoriteOrder.stampKey) { favoriteOrder.syncFromStamps() }
    fun reorderLibraryPlaylists(order: List<String>) {
        val previousOrder = PlaylistOrderSync.displayOrder(userBundle.playlists, libOrder)
        val reorderedPlaylists = PlaylistOrderSync.withOrder(userBundle.playlists, order)
        val restoredOrder = PlaylistOrderSync.displayOrder(reorderedPlaylists, emptyList())
        if (restoredOrder == previousOrder) return
        userBundle = userBundle.copy(playlists = reorderedPlaylists.toMutableList())
        libOrder = restoredOrder
        prefs.edit().putString("lib_order_v1", restoredOrder.joinToString(",")).apply()
        // LibraryPage invokes this only after a valid up/down move; never save during a gesture.
        persistBundle()
    }
    LaunchedEffect(avatarNeedSync, avatarPath) {
        if (avatarNeedSync && avatarPath.isNotEmpty()) {
            persistBundle()
            avatarNeedSync = false
        }
    }
    fun applyScriptMeta(order: List<String>, enabled: List<String>) {
        if (order.isEmpty() && enabled.isEmpty()) return
        val map = scripts.associateBy { it.id }
        val enabledSet = enabled.toSet()
        // 云端只存“曾启用的 id”。未知脚本/仅本地导入的不得被误关。
        val cloudKnown = (order + enabled).toSet()
        val seen = linkedSetOf<String>()
        val out = ArrayList<Script>()
        (order + scripts.map { it.id }).forEach { id ->
            if (id in seen) return@forEach
            val sc = map[id] ?: return@forEach
            seen.add(id)
            val nextEnabled = when {
                enabled.isEmpty() -> sc.enabled
                id in enabledSet -> true
                id !in cloudKnown -> sc.enabled
                // 本地仍有代码且本来是开的：云端列表过旧时不误关（用户可再手动关）
                sc.enabled && sc.code.isNotBlank() -> true
                else -> false
            }
            out.add(sc.copy(enabled = nextEnabled))
        }
        if (out.isEmpty()) return
        scripts = out
        ScriptStorage.saveScripts(sprefs, out)
        scriptRuntime.invalidate()
    }
    fun startDownload(s: Song) = download.startDownload(s)
    fun confirmDownload(s: Song, quality: String) = download.confirmDownload(s, quality)
    fun deleteDownloads(items: List<DownloadItem>, deleteFiles: Boolean) {
        // 验收二轮 E2：删除改走延迟落盘状态机（暂存 → 撤销窗口 → 提交），实现与边界见
        // DownloadPendingDelete；此处只做接线，列表刷新由回调回到本组件状态。
        DownloadPendingDelete.requestDelete(context, items, deleteFiles) { downloads = localData.downloads() }
    }
    fun pauseDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { DownloadScheduler.stopBatch(context, items, paused = true) }
            downloads = localData.downloads()
        }
    }
    fun resumeDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { DownloadScheduler.resumeBatch(context, items) }
            downloads = localData.downloads()
        }
    }
    fun redownloadDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                DownloadScheduler.enqueueBatch(context, items.map { it.song to downloadQ })
                val latest = localData.downloads()
                withContext(Dispatchers.Main) {
                    downloads = latest
                    AppNotice.toast("已加入下载队列 ${items.size} 首", AppNotice.Kind.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AppNotice.toast("加入下载失败", AppNotice.Kind.FAILURE)
                }
            }
        }
    }
    fun scanLocalSongs() {
        if (localScanRunning) return
        val scanOwner = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()
        val mediaPermission = ensureAudioPermission(context)
        if (mediaPermission || LocalScanFolders.authorized(context).isNotEmpty()) scope.launch {
            localScanRunning = true
            localScanStatus = "正在扫描已授权音频…"
            try {
            val songs = withContext(Dispatchers.IO) {
                val scanContext = kotlin.coroutines.coroutineContext
                // ACCEPT4（审计 §11）：扫描结果按本地库排除记录过滤——从本地歌单移除过的曲目重扫不复活。
                LocalLibraryStore.filterExcluded(context, LocalMusicScanner.scan(context) { !scanContext.isActive })
            }
            if (xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() != scanOwner) return@launch
            withContext(Dispatchers.IO) { LocalLibraryStore.save(context, songs) }
            val localIndex = userBundle.playlists.indexOfFirst { it.id == "local" }
            val updated = userBundle.playlists.toMutableList()
            val local = if (localIndex >= 0) updated[localIndex] else Playlist("local", "本地歌单", local = true)
            val next = local.copy(songs = songs.toMutableList(), count = songs.size)
            if (localIndex >= 0) updated[localIndex] = next else updated.add(next)
            userBundle = userBundle.copy(playlists = updated)
            if (plDetail?.id == "local") {
                plDetail = next
                plDetailSongs = songs
            }
            persistBundle(notify = true)
            localScanStatus = "扫描完成 · ${songs.size} 首歌曲"
            AppNotice.toast(if (songs.isEmpty()) "未找到可播放音频文件" else "已扫描 ${songs.size} 首本地歌曲", if (songs.isEmpty()) AppNotice.Kind.NEUTRAL else AppNotice.Kind.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                localScanStatus = "扫描失败，请检查音频或文件夹授权后重试"
            } finally {
                localScanRunning = false
            }
        }
    }

    // ===== 播放编排已迁至 PlaybackController；此处保留薄包装以零改动衔接 UI 与接线 =====
    fun savePlaybackState() = playback.savePlaybackState()
    suspend fun refreshLyrics(song: Song) = playback.refreshLyrics(song)
    suspend fun resolveAndPlay(
        song: Song,
        list: List<Song> = queue.ifEmpty { listOf(song) },
        index: Int = -1,
        startMs: Long = 0L,
        skipScripts: Set<String> = emptySet(),
        forceCross: Boolean = false,
        originPlaylistId: String? = null
    ): Unit {
        playback.resolveAndPlay(song, list, index, startMs, skipScripts, forceCross, originPlaylistId = originPlaylistId)
    }
    suspend fun playHeartSimilar(): Unit {
        playback.playHeartSimilar()
    }
    suspend fun playSimilarSong(item: Song): Unit {
        playback.playSimilarSong(item)
    }
    fun startHeartMode(pool0: List<Song>) = playback.startHeartMode(pool0)
    var playlistHeartLoadGeneration by remember { mutableIntStateOf(0) }
    var playlistHeartLoadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun togglePlaylistHeartMode(playlist: Playlist) {
        playlistHeartLoadJob?.cancel()
        playlistHeartLoadJob = null
        val requestGeneration = ++playlistHeartLoadGeneration
        if (PlayerService.snapshot().mode == PlayMode.HEART &&
            playback.activeHeartPlaylistKeyState.value == heartPlaylistKey(playlist)) {
            playback.togglePlaylistHeartMode(playlist, emptyList())
            return
        }
        val directSongs = when (playlist.id) {
            "liked" -> userBundle.favs.toList()
            "history" -> history.toList()
            else -> playlist.songs.toList()
        }
        if (directSongs.isNotEmpty() || playlist.id in setOf("liked", "history", "local") || playlist.local) {
            playback.togglePlaylistHeartMode(playlist, directSongs)
            return
        }
        // 空远端快照不能降级成全局「我的喜欢」；按详情页同一缓存 + Provider/API 详情加载路径取得歌曲。
        val source = playlist.source
        val remoteId = playlist.remoteId.ifBlank { playlist.id.removePrefix("reco_") }
        val isAlbum = playlist.detailContentKind.lowercase() in setOf("album", "new_album", "digital") ||
            playlist.id in AlbumFavoriteMarks.marks(context) || AlbumFavoriteMarks.isKnownAlbum(playlist, source)
        if (source.isBlank() || remoteId.isBlank() || (!isAlbum && !ProviderRegistry.capability(source, prefs).playlistDetail)) {
            playback.togglePlaylistHeartMode(playlist, emptyList())
            return
        }
        val playbackIntent = PlayerService.beginPlaybackIntent()
        playlistHeartLoadJob = scope.launch {
            val cache = withContext(Dispatchers.IO) {
                runCatching { HomeFeedCache.loadDetailSnapshotOnIo(context, remoteId, source) }
                    .getOrDefault(DetailCacheSnapshot(emptyList(), 0L))
            }
            val resolution = resolveDetailCache<List<Song>?>(
                cache = cache,
                ttlMs = HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS,
                fetchDetail = {
                    withContext(Dispatchers.IO) {
                        try { when {
                            isAlbum && source == "mg" && playlist.detailContentKind.equals("digital", true) ->
                                xyz.cncnlp.soundwave.source.direct.fetchMgDigitalAlbumDetailDirect(remoteId)?.songs
                            isAlbum && source in MusicSourceRegistry.platformIds ->
                                MusicSourceRegistry.platformSource(source).albumDetail(remoteId)?.songs
                            isAlbum -> ApiClient.albumDetail(remoteId, source)?.songs
                            source in MusicSourceRegistry.platformIds ->
                                MusicSourceRegistry.platformSource(source).playlistDetail(remoteId)
                            else -> ApiClient.playlistDetail(remoteId, source)
                        } } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    }
                }
            )
            val loadedSongs = when (resolution) {
                is DetailCacheResolution.CacheHit -> resolution.songs
                is DetailCacheResolution.Refreshed -> resolution.value.orEmpty().ifEmpty { resolution.cachedSongs }
            }
            if (requestGeneration != playlistHeartLoadGeneration || !PlayerService.isPlaybackIntentCurrent(playbackIntent)) return@launch
            playback.togglePlaylistHeartMode(playlist, loadedSongs)
        }
    }
    suspend fun playDelta(delta: Int) = playback.safePlayDelta(delta)
    suspend fun prefetchNext() = playback.prefetchNext()

    playback.playDeltaRef[0] = { d -> playDelta(d) }
    playback.prefetchRef[0] = { prefetchNext() }

    // soundwave:// 深度链接由唯一持续 collector 消费；冷启动与 onNewIntent 共用同一事件流。
    LaunchedEffect(Unit) {
        SchemeActionHolder.actions.collect { action ->
            when (action) {
                is SchemeActionHolder.Action.Play -> {
                    val song = Song(
                        id = action.songId,
                        name = action.name.ifBlank { "未知歌曲" },
                        singer = action.singer,
                        source = action.source,
                    )
                    if (SchemeActionHolder.shouldQueueNext(playing, currentSong != null)) {
                        playback.insertNext(song); AppNotice.toast("已加入下一首播放：${song.name}", AppNotice.Kind.SUCCESS)
                    } else {
                        val entry = playerEntryState(tab, prevTab)
                        prevTab = entry.previousTab
                        tab = Tab.PLAYER
                        // collector 只分发；慢解析与后续点歌取消都不能阻塞/取消深链消费。
                        scope.launch { resolveAndPlay(song, list = emptyList(), index = -1) }
                    }
                }
                is SchemeActionHolder.Action.ImportPlaylist -> { onlineImportPrefill = action.text; showOnlineImportDlg = true }
                is SchemeActionHolder.Action.ImportScript -> importScriptUri(action.uri)
                is SchemeActionHolder.Action.ImportBackup -> { ExternalFileImport.offerBackup(action.uri); tab = Tab.SETTINGS; settingsStack = listOf("backup") }
                is SchemeActionHolder.Action.Notice -> AppNotice.toast(action.message)
                is SchemeActionHolder.Action.Search -> {
                    val entry = AppRoute.searchEntryDecision(tab, prevTab, lastContentTab)
                    prevTab = entry.previousTab
                    lastContentTab = entry.lastContentTab
                    tab = Tab.SEARCH
                    searchDockFocus = false
                    runSearch(action.keyword, "all", "song")
                }
                SchemeActionHolder.Action.None -> Unit
            }
        }
    }

    // 669: route entry reads the same live controller as the mini-player, not a captured
    // nullable value from the enclosing AppRoot/AnimatedVisibility content lambda.
    fun openFullPlayer() {
        playback.reconcileForPlayerEntry()
        val entry = playerEntryState(tab)
        prevTab = entry.previousTab
        showLyrics = entry.showLyrics
        tab = Tab.PLAYER
    }

    // 断点续播
    LaunchedEffect(Unit) {
        searchHistory = prefs.getString("search_history_v1", "")
            ?.split("||")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(10)
            ?: emptyList()
        // 前台 Service 仍持有有效媒体时，它才是真实播放状态；禁止用旧磁盘快照倒灌并重播。
        if (PlayerService.hasPlayableMedia()) {
            val snap = PlayerService.snapshot()
            val liveQueue = snap.queue
            if (liveQueue.isNotEmpty()) queue = liveQueue
            currentSong = snap.song
            currentSong?.let { liked = userBundle.isLiked(it) }
            return@LaunchedEffect
        }
        val st = withContext(Dispatchers.IO) { PlaybackStore.load(context) } ?: return@LaunchedEffect
        queue = st.queue
        currentSong = st.song
        liked = userBundle.isLiked(st.song)
        // 冷启动恢复：保留队列、暂停/播放状态和精确进度。优先使用已持久化直链，只有直链失效时才进入取链。
        // 不默认强制自动播放：只有上次确实在播放或用户开启“启动自动播放”才恢复播放。
        playback.replaceQueueForRestore(st.queue, st.index, st.song, st.positionMs)
        // ACCEPT5：断点里的队列来源一并恢复（旧断点无来源则为 null，与旧行为一致）。
        playback.adoptRestoredQueueOrigin(st.queueOrigin, st.queueOriginFlags)
        if (st.wasPlaying || autoPlayOnStart) {
            scope.launch {
                resolveAndPlay(st.song, st.queue, st.index, st.positionMs)
            }
        }
    }

    // 定期保存进度（r658c F：后台单线程序列化，内容未变跳过；旧版每 5s 主线程整队列 toJson）
    LaunchedEffect(currentSong, playing) {
        while (currentSong != null) {
            delay(5000)
            playback.savePlaybackStateInBackground()
        }
    }

    var initialProbeReport by remember { mutableStateOf<String?>(null) }
    if (initialProbeReport != null) {
        // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层；报告文本逐字不变，长文滚动改由弹层承接。
        xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = { initialProbeReport = null }, scrollContent = true) {
            // 收尾棒 J8 句柄补齐：「关闭」钮走关闭句柄退场（清状态在退场后经 onDismiss 触发）。
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { initialProbeReport = null }
            xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Scripts", "脚本完整检测报告")
            Spacer(Modifier.height(10.dp))
            Text(initialProbeReport.orEmpty())
            xyz.cncnlp.soundwave.ui.common.SheetActionButtons(secondary = "关闭", onSecondary = dismiss, primary = null)
        }
    }
    LaunchedEffect(scripts.map { it.id to it.code.hashCode() }) {
        val initialTargets = ScriptProbeStartupPolicy.missingFullReportTargets(
            scripts,
            ScriptProbeCache::fullReport
        )
        if (!batchProbeActive && ScriptProbeStartupPolicy.claimInitial(sprefs, initialTargets.isNotEmpty())) {
            batchProbeActive = true
            // A 波追加（用户裁定）：检测进行中不发任何通知；只在完成时发一张结果卡（下方 inbox 分支）。
            scope.launch {
                try {
                    val fullReports = ScriptProbeBatchRunner.runFull(initialTargets, scriptRuntime, preferQ)
                    initialProbeReport = fullReports.joinToString("\n\n")
                    val ordered = ScriptProbeStartupPolicy.sortWithVerifiedReports(
                        scripts,
                        ScriptProbeCache.SortMode.BALANCED,
                        ScriptProbeCache::fullReport
                    ) { script, mode -> ScriptProbeCache.sortValue(script, mode) }
                    if (ordered == null) {
                        // ACCEPT5 静默类：检测完成只进通知中心，不弹横幅（WAVE-ACCEPT5-NOTICE A.3）。
                        AppNotice.inbox(AppNotice.Category.SCRIPT, "自动完整检测完成", "没有可验证的有效链路，保留原排序")
                    } else if (ordered != scripts) {
                        scripts = ordered
                        ScriptStorage.saveScripts(sprefs, ordered)
                        SmartSourceStore.syncScripts(ordered)
                        ordered.firstOrNull { it.enabled && it.code.isNotBlank() }
                            ?.let { SmartSourceStore.setActiveScriptId(it.id) }
                        scriptRuntime.invalidate()
                        persistBundle()
                        AppNotice.inbox(AppNotice.Category.SCRIPT, "首次检测完成", "已按有效完整检测结果排序")
                    } else {
                        AppNotice.inbox(AppNotice.Category.SCRIPT, "首次检测完成", "现有排序已符合检测结果")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AppNotice.inbox(AppNotice.Category.SCRIPT, "首次检测未完成", "未调整脚本排序")
                } finally {
                    batchProbeActive = false
                }
            }
        }
    }
    val playerServiceEventBridge = remember { PlayerServiceEventBridge() }
    LaunchedEffect(Unit) {
        applyServer(serverUrl)
        // 脚本不内置：仅加载用户导入的本地脚本
        // 后台检测完成后留下一个一次性完成提示，重进应用也不会丢。
        // ACCEPT5 静默类：只落通知中心，不再补弹横幅（WAVE-ACCEPT5-NOTICE A.3）。
        prefs.getString("pending_script_probe_done", "").orEmpty().takeIf { it.isNotBlank() }?.let { notice ->
            prefs.edit().remove("pending_script_probe_done").apply()
            AppNotice.inbox(AppNotice.Category.SCRIPT, notice)
        }
        scripts = ScriptStorage.loadScripts(sprefs).map { it.copy(builtin = false) }
        val ready = scripts.count { it.enabled && it.code.isNotEmpty() }
        status = ""
        // 运行时按需启动：此处只设置服务地址，不创建 WebView。
        playerServiceEventBridge.register(
            PlayerServiceEventCallbacks(
                onSongChanged = songEvent@ { changed ->
                    val eventOwner = PlayerService.snapshot().ownerContext
                    if (!eventOwner.matches(runtimeOwner)) return@songEvent
                    val eventData = ContextData(context, eventOwner)
                    // 解析中的 null 快照与迟到旧曲都不得回灌 UI：前者会让新 ResolveTicket 过期，
                    // 后者会把已切走的旧曲重新写回标题、歌词和喜欢状态。
                    val acceptingForegroundCommit = changed != null &&
                        playback.playbackTransactionGate.hasCurrent(changed.key(), queueRevision)
                    val acceptedSongCommit = changed != null &&
                        ((!resolving) || acceptingForegroundCommit)
                    if (acceptedSongCommit) {
                        currentSong = changed
                        liked = userBundle.isLiked(changed)
                        // 双缓冲切歌后预取再下一首。必须和 UI 提交使用同一扇门：
                        // 迟到旧曲即使不能覆盖 currentSong，也不能写历史、歌词或占住 Runtime 预取。
                        scope.launch {
                            if (!eventOwner.matches(PlayerService.snapshot().ownerContext)) return@launch
                            val eventGeneration = PlayerService.snapshot().generation
                            fun eventIsCurrent(): Boolean {
                                val snapshot = PlayerService.snapshot()
                                return eventOwner.matches(snapshot.ownerContext) &&
                                    snapshot.generation == eventGeneration && snapshot.song?.key() == changed.key()
                            }
                            val updatedHistory = xyz.cncnlp.soundwave.playback.PlaybackMetadataWork.runIfCurrent(
                                ::eventIsCurrent,
                                { eventData.pushHistory(changed); eventData.history() }
                            )
                            if (!eventIsCurrent()) return@launch
                            if (updatedHistory != null) history = updatedHistory
                            lyrics = emptyList()
                            lyricIdx = -1
                            refreshLyrics(changed)
                            prefetchNext()
                        }
                    } else if (changed == null && !resolving) {
                        currentSong = null
                        liked = false
                    }
                },
                // playing 已由上方 StateFlow collector 同步，
                // 不再需要 onPlayState 回调重复写同一 Compose 状态，消除双轨写入。
                onError = { failure -> playback.handlePlaybackFailure(failure) },
                onNeedNext = { playback.enqueueQueueAdvance(+1) },
                onNeedPrev = { playback.enqueueQueueAdvance(-1) },
                // 自然结束的预取提升和点击“下一首”共用同一入口，保证先建 Gate 再 claim。
                onPreparedNext = { playback.enqueueQueueAdvance(+1) },
                onPlaybackInterrupted = { playback.cancelPendingPlayback() },
                onNeedReplay = { intent -> playback.replay(intent) },
                onLikeToggle = {
                    currentSong?.let { s ->
                        liked = userBundle.toggleLike(s)
                        userBundle = userBundle.copy(
                            favs = userBundle.favs.toMutableList(),
                            playlists = userBundle.playlists.toMutableList(),
                            enabledScripts = userBundle.enabledScripts.toMutableList()
                        ).also { it.ensureDefaults() }
                        persistBundle()
                    }
                },
                onLyricFloatToggle = {
                    val cur = lyrics.getOrNull(lyricIdx)?.text.orEmpty()
                    val nxt = lyrics.getOrNull(lyricIdx + 1)?.text.orEmpty()
                    val sample = cur.ifBlank { currentSong?.name ?: "♪ SoundWave" }
                    // 与通知栏共用同一三态机：关→开、开→锁定、锁定→关；同步读取偏好，避免旧UI状态覆盖。
                    if (!LyricFloat.hasPermission(context) && !LyricFloat.isUserEnabled(context)) {
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {}
                        AppNotice.banner("请先开启悬浮窗权限", actionLabel = "去开启", onAction = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }, onClick = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} })
                    } else {
                        val msg = LyricFloat.handleNotifWord(context, sample, nxt)
                        lyricFloatEnabled = LyricFloat.isUserEnabled(context)
                        AppNotice.toast(msg)
                    }
                },
                onSleepTick = { sleepRemain = it },
                onModeChanged = { playMode = it }
            )
        )
        // 首页数据只由 HomeFeedController 的单一事务刷新。旧链曾在 IO 线程并发写 Compose
        // 状态，并把首页拉取放大为数百个歌单；与控制器并行会导致空转、竞争和内存尖峰。
        PlayerService.setAudioFocusSettings(xyz.cncnlp.soundwave.playback.AudioFocusSettingsStore.read(prefs))
        // 恢复播放速度与均衡器预置（服务可能在进程重建后丢失）
        PlayerService.setPlaybackSpeed(playSpeed)
        PlayerService.setEqualizerPreset(eqPreset)
    }

    // AppRoot 离开组合时只释放自己仍拥有的服务事件回调，不影响前台服务播放。
    DisposableEffect(playerServiceEventBridge) {
        onDispose { playerServiceEventBridge.dispose() }
    }

    LaunchedEffect(playing) {
        while (playing) {
            kotlinx.coroutines.delay(1000)
            playSeconds += 1
            if (playSeconds % 10L == 0L) prefs.edit().putLong("play_seconds", playSeconds).apply()
        }
    }

    // 进度/时长/比例由 PlayerService 快照更新；这里仅用于歌词定位。
    // r658c D#2：听歌统计改由 PlayerService 的进度 tick 计时（界面被回收时也计），此处不再写统计。
    LaunchedEffect(currentSong, playing, lyrics) {
        while (true) {
            val pos = PlayerService.getPosition()
            if (lyrics.isNotEmpty()) {
                lyricIdx = LyricParser.indexAt(lyrics, pos)
            }
            // 悬浮歌词已由 PlayerService 的媒体 tick 驱动；这里不再让 Activity 成为第二写入者。
            if (!lyricFloatEnabled && LyricFloat.isShowing()) {
                LyricFloat.hide()
            }
            delay(350)
        }
    }

    val navigationEvents = MainNavigationEvents(
        snapshot = {
            MainNavigationSnapshot(tab, prevTab, lastContentTab, plDetail != null, settingsStack.lastOrNull(),
                searchResult, downloadPickSong != null, lastBackAt)
        },
        effects = MainNavigationEffects(
            dismissDownloadPicker = { downloadPickSong = null; downloadPickQuality = "" },
            returnToTopLevelTab = { returnToTopLevelTab(it) },
            closeDetail = { closeDetail() },
            popSettingsSub = { settingsStack = SettingsNavStack.popped(settingsStack) },
            clearSearch = { searchResult = null; searchKw = "" },
            finishActivity = { (context as? android.app.Activity)?.finish(); Unit },
            showExitPrompt = { now ->
                lastBackAt = now
                AppNotice.toast("再按一次退出")
            },
            setLastContentTab = { lastContentTab = it },
            clearSearchDockFocus = { searchDockFocus = false },
            restoreHomeSection = { target, current, detailOpen ->
                val section = homePagerSelection.afterBottomNavigation(target, current, detailOpen)
                prefs.edit().putString("home_section", section).apply()
            },
            applyMainTab = { target ->
                tab = target
                settingsStack = emptyList()
                plDetail = null
                plDetailLoading = false
                detailStack = emptyList() // accept6-backnav：切主 tab 时导航栈一并清，防陈旧父帧复活
                detailOverPlayer = false // ACCEPT5：切主 tab 时兜底清压播放器标记，防陈旧状态
            }
        )
    )
    AppRootNavigationBackHandler(navigationEvents)

    MaterialTheme(colorScheme = scheme, typography = globalTypography) {
    CompositionLocalProvider(
        LocalGlobalFontFamily provides globalFontFamily,
        xyz.cncnlp.soundwave.ui.today.LocalStylePreferencesSaved provides { account.persistBundle() },
        LocalThemeVisual provides ThemeVisual(
            menuAlpha = menuOpacity.coerceIn(.2f, 1f),
            menuIconWhite = menuIconWhite
        ),
        LocalLyricFontFamily provides lyricFontFamily,
        LocalAvatarUserName provides user
    ) {
    Box(Modifier.fillMaxSize()) {
    // 背景固定采用当前 Material 主题表面色，不受历史自定义颜色或透明度影响。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    PlaylistFavorites.bind(homePlatform, userBundle.playlists, { updated ->
            val oldIds = userBundle.playlists.mapTo(hashSetOf()) { it.id }
            val next = userBundle.copy(playlists = updated)
            val updatedIds = updated.mapTo(hashSetOf()) { it.id }
            (oldIds - updatedIds).forEach { next.deletePlaylist(it) }
            updated.filter { it.id !in oldIds }.forEach { next.addOrReplacePlaylist(it) }
            userBundle = next
            persistBundle()
    }, context)

            if (showOnlineImportDlg) OnlineImportDialog(
                initial = onlineImportPrefill,
                onImport = { raw ->
                    // 收尾棒 J8 配对：关弹层与清预填归 onDismiss（关闭句柄退场后触发，本调用点下方已含同两行）；导入动作即时启动。
                    // SW-09-04（G 波）：导入为关窗后台执行——先给进度提示，结果再以成功/失败通知收尾。
                    AppNotice.toast("正在导入歌单…", AppNotice.Kind.NEUTRAL)
                    scope.launch {
                        val pl = withContext(Dispatchers.IO) { ApiClient.importPlaylist(raw) }
                        if (pl != null && pl.songs.isNotEmpty()) {
                            val saved = pl.copy(id = xyz.cncnlp.soundwave.source.direct.onlineImportLocalPlaylistId(raw, pl) ?: return@launch, local = false)
                            userBundle.addOrReplacePlaylist(saved)
                            // SW-09-16：同链接重导得到同 id——清理旧排序键，新导入一律从默认排序开始。
                            clearPlaylistSortMode(context, saved.id)
                            userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                            persistBundle(notify = true)
                            PlaylistImportChecker.checkPlaylistAsync(
                                ctx = context,
                                songs = saved.songs.toList(),
                                scripts = scripts,
                                runtime = scriptRuntime
                            )
                            // SW-12-02（G 波）：导入成败触感（与 SUCCESS/FAILURE 通知同点触发）。
                            (context as? android.app.Activity)?.window?.decorView?.let { xyz.cncnlp.soundwave.ui.common.AppHaptics.success(it) }
                            AppNotice.toast("已导入 ${saved.name} · ${saved.songs.size} 首，正在检测原平台可播放性", AppNotice.Kind.SUCCESS)
                        } else {
                            (context as? android.app.Activity)?.window?.decorView?.let { xyz.cncnlp.soundwave.ui.common.AppHaptics.failure(it) }
                            AppNotice.toast("导入失败", AppNotice.Kind.FAILURE)
                        }
                    }
                },
                onDismiss = { showOnlineImportDlg = false; onlineImportPrefill = "" }
            )
            // ===== 添加到歌单（支持批量） =====
            if (showAddToPl && (addSongTarget != null || addSongBatch.isNotEmpty())) {
                val songs = (if (addSongBatch.isNotEmpty()) addSongBatch else listOfNotNull(addSongTarget)).distinctBy { it.key() }
                val pls = userBundle.playlists.filter { it.id != "liked" && it.id != "history" && it.id != "local" }
                AddPlaylistCoverSheet(
                    title = if (songs.size > 1) "添加 ${songs.size} 首到歌单" else "添加到歌单",
                    playlists = pls,
                    onPick = { id ->
                        // 收尾棒 J8 配对：清 showAddToPl 归 onDismiss（弹层关闭句柄退场后触发，本调用点下方已含全量清理）；动作即时执行。
                        if (id == "__new__") {
                            newPlName = "新建歌单"
                            showNewPlDlg = true
                        } else {
                            val pl = userBundle.playlists.find { it.id == id }
                            if (pl != null) {
                                // 在写入前记录详情/队列，只有当前队列确实来自此歌单时才同步，绝不误伤其他播放队列。
                                val priorDetailSongs = if (plDetail?.id == pl.id) plDetailSongs.toList() else emptyList()
                                userBundle.addSongsToPlaylist(pl.id, songs)
                                val updated = userBundle.playlists.firstOrNull { it.id == pl.id } ?: pl
                                userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                                if (plDetail?.id == updated.id) {
                                    plDetail = updated
                                    plDetailSongs = updated.songs.toList()
                                    val snap = PlayerService.snapshot()
                                    val queueBefore = snap.queue
                                    if (priorDetailSongs.isNotEmpty() && queueBefore.map { it.key() } == priorDetailSongs.map { it.key() }) {
                                        val playingKey = snap.song?.key()
                                        playback.replaceQueue(updated.songs.toList(), updated.songs.indexOfFirst { it.key() == playingKey }.coerceAtLeast(0))
                                    }
                                }
                            }
                            persistBundle()
                            addSongTarget = null
                            addSongBatch = emptyList()
                            AppNotice.toast("已添加 ${songs.size} 首", AppNotice.Kind.SUCCESS)
                        }
                    },
                    onDismiss = { showAddToPl = false; addSongTarget = null; addSongBatch = emptyList() }
                )
            }
            // P2-10：URL 脚本二次确认——展示来源域名 + SHA-256，确认后才真正导入。
            pendingUrlScript?.let { fetched ->
                // 对话框清扫：AlertDialog 白框改统一确认弹层；来源/哈希/风险文案与导入链路逐字不变
                // （正文三段样式收敛为家族正文样式；导入中不可关与登出 busy 先例同口径）。
                xyz.cncnlp.soundwave.ui.common.DesignConfirmSheet(
                    title = "确认导入脚本",
                    body = "来源：${fetched.host.ifBlank { "未知" }}\nSHA-256：${fetched.sha256.ifBlank { "（不可用）" }}\n第三方脚本可访问网络并执行其代码，仅从可信来源导入。",
                    confirmLabel = if (urlScriptConfirming) "导入中…" else "确认导入",
                    buttonsEnabled = !urlScriptConfirming,
                    closeSignal = urlScriptCloseSignal,
                    onDismiss = { pendingUrlScript = null; urlScriptCloseSignal = false },
                    onConfirm = {
                        urlScriptConfirming = true
                        scope.launch {
                            val mm = scripts.toMutableList()
                            val result = withContext(Dispatchers.IO) {
                                runCatching { ScriptStorage.importScript(sprefs, mm, fetched.code) }
                            }
                            urlScriptConfirming = false
                            urlScriptCloseSignal = true
                            result.onSuccess {
                                ScriptProbeStartupPolicy.noteImport(sprefs)
                                scripts = mm.toList()
                                scriptRuntime.invalidate()
                                AppNotice.toast("已导入脚本：${it.name}", AppNotice.Kind.SUCCESS)
                            }.onFailure {
                                AppNotice.toast("脚本导入失败", AppNotice.Kind.FAILURE)
                            }
                        }
                    }
                )
            }
            if (showNewPlDlg) {
                val songs = (if (addSongBatch.isNotEmpty()) addSongBatch else listOfNotNull(addSongTarget)).distinctBy { it.key() }
                xyz.cncnlp.soundwave.ui.common.CreatePlaylistDialog(
                    name = newPlName,
                    onNameChange = { newPlName = it },
                    // 收尾棒 J8 配对（预检裁定版）：可见性只归 onDismiss（外点/返回也只关、待加歌曲保留——
                    // PlaylistDialogWiringTest 钉死的区别）；确认的「消费待加歌曲」清除与显式取消的
                    // 「弃置待加歌曲」清除仍在各自回调里立即执行，可见性清除统一由句柄退场后的 onDismiss 承担。
                    onDismiss = { showNewPlDlg = false },
                    onConfirm = {
                        val name = newPlName.trim().ifEmpty { "新建歌单" }
                        val np = Playlist(
                            id = "local_${System.currentTimeMillis()}",
                            name = name,
                            source = "local",
                            // 新建歌单详情不显示“未知作者”；按用户语义标为在线歌单。
                            author = "在线歌单",
                            local = true,
                            songs = songs.toMutableList()
                        )
                        userBundle.addOrReplacePlaylist(np)
                        userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                        persistBundle()
                        addSongTarget = null
                        addSongBatch = emptyList()
                        AppNotice.toast("已创建并添加 ${songs.size} 首", AppNotice.Kind.SUCCESS)
                    },
                    onCancel = {
                        addSongTarget = null
                        addSongBatch = emptyList()
                    }
                )
            }
            // ===== 关于 APP 底部 =====
            if (showAboutSheet) {
                AboutDialog(
                    serverUrl = serverUrl,
                    onDismiss = { showAboutSheet = false }
                )
            }
            // ===== 播放队列 Overlay：与播放来源分离 =====
    }

    // 删除歌曲回调：withFile=true 同时删除本地文件（AppRoot 主体声明，跨块可见）。
    // A 波：删前快照 + 删后发带「撤销」的通知（快照/恢复逻辑在 PlaylistDetailActions 的 removeSongsSnapshot/emitRemoveSongsUndo）。
    val deleteSongCb: (Song, Boolean) -> Unit = { s, withFile ->
            val pid = plDetail?.id
            val beforeSongs = removeSongsSnapshot(userBundle, pid, withFile, context)
            deleteSongFromDetail(s, withFile = withFile, context = context, scope = scope,
                getBundle = { userBundle }, setBundle = { userBundle = it },
                getDetail = { plDetail }, setDetail = { plDetail = it }, setDetailSongs = { plDetailSongs = it },
                getHistory = { history }, setHistory = { history = it }, persist = { persistBundle() })
            if (pid != null && beforeSongs != null) emitRemoveSongsUndo(pid, beforeSongs, { userBundle }, { userBundle = it }, { plDetail }, { plDetail = it }, { plDetailSongs = it }, { persistBundle(notify = true) }, context) { likedTick++ }
        }
    val deleteSongsBatchCb: (List<Song>, Boolean) -> Unit = { songs, withFile ->
        val pid = plDetail?.id
        val beforeSongs = removeSongsSnapshot(userBundle, pid, withFile, context)
        deleteSongsFromDetailBatch(songs, withFile, context, scope,
            getBundle = { userBundle }, setBundle = { userBundle = it },
            getDetail = { plDetail }, setDetail = { plDetail = it }, setDetailSongs = { plDetailSongs = it },
            persist = { persistBundle() })
        if (pid != null && beforeSongs != null) emitRemoveSongsUndo(pid, beforeSongs, { userBundle }, { userBundle = it }, { plDetail }, { plDetail = it }, { plDetailSongs = it }, { persistBundle(notify = true) }, context) { likedTick++ }
    }
    // 歌手详情回调：点搜索结果里的歌手条目 → 打开歌手歌曲详情页（复用 plDetail/LibraryPage）。
    // 服务端 /music/singer 五平台均已就绪；此处先落歌手名/头像占位，歌曲后台补齐。
    // 歌手详情来源页：默认搜索；首页热门歌手入口临时置为 HOME，返回时回到首页。
    val singerOriginRef = arrayOf(Tab.SEARCH)
    val openSingerCb: (Song) -> Unit = { s ->
        val src0 = s.source.ifBlank { searchPlatform }
        detailOriginTab = singerOriginRef[0]
        singerOriginRef[0] = Tab.SEARCH
        val singerId = "singer_${src0}_${s.id}"
        openDetail(
            Playlist(
                singerId, s.name.ifBlank { "歌手" }, s.cover, mutableListOf(), false, src0,
                author = s.name, description = "歌手", count = 0, remoteId = s.id
            ),
            emptyList()
        )
        scope.launch {
            val pl = withContext(Dispatchers.IO) {
                if (src0 in MusicSourceRegistry.platformIds) {
                    MusicSourceRegistry.platformSource(src0).artistDetail(s.id, s.name)
                } else {
                    ApiClient.singerSongs(s.id, s.name, src0)
                }
            }
            if (plDetail?.id != singerId) return@launch // 已切走
            if (pl != null && pl.songs.isNotEmpty()) {
                // 批27：服务端无歌手头像的平台（kg/tx/mg）返回空 cover → 回退搜索结果里的歌手头像，
                // 不再显示首曲专辑封面冒充歌手头像（非歌曲封面）。
                plDetail = pl.copy(cover = pl.cover.ifBlank { s.cover })
                plDetailSongs = pl.songs
            } else {
                AppNotice.toast("暂无歌手歌曲")
            }
        }
    }

    // 首页热门歌手：有平台歌手 id 直接复用歌手详情；仅有名字时先按歌手名检索拿到 id。
    val homeArtistOrigin = arrayOf(Tab.HOME); val homeArtistSrc = arrayOf("") // r657 第二轮：歌曲抽屉「查看歌手」复用（来源页 / 平台）
    val openHomeArtistCb: (String, String, String) -> Unit = { name, avatar, artistId ->
        val src0 = homeArtistSrc[0].ifBlank { homePlatform.ifBlank { "wy" } }; val origin0 = homeArtistOrigin[0]
        homeArtistOrigin[0] = Tab.HOME; homeArtistSrc[0] = ""
        // 按名兜底的检索平台：歌曲来源是真实平台就用它；本地歌曲/空来源与音源平台无关，落用户主平台 homePlatform（不写死 wy）。
        val searchSrc = src0.takeIf { it in MusicSourceRegistry.platformIds } ?: homePlatform.takeIf { it in MusicSourceRegistry.platformIds } ?: "wy"
        if (artistId.isNotBlank()) {
            singerOriginRef[0] = origin0
            openSingerCb(Song(id = artistId, name = name, singer = name, source = src0, cover = avatar))
        } else scope.launch {
            // 无 ID 兜底与搜索页同路：平台歌手实体搜索 searchTyped("artist")（直连实体优先、服务端最后），
            // 命中挑选走 TrackNavigation.pickArtistHit——只认带 ID 的可信同名行，无命中诚实报未找到，不再盲抓第一条。
            val hit = withContext(Dispatchers.IO) {
                runCatching { MusicSourceRegistry.platformSource(searchSrc).searchTyped(name, "artist") }
                    .getOrDefault(emptyList())
                    .let { rows -> TrackNavigation.pickArtistHit(rows, name) }
            }
            if (hit == null) {
                AppNotice.toast("暂未找到歌手：$name")
            } else {
                singerOriginRef[0] = origin0
                openSingerCb(hit.copy(name = hit.name.ifBlank { name }, source = hit.source.ifBlank { searchSrc }, cover = avatar.ifBlank { hit.cover }))
            }
        }
    }

    // 首页专辑 / 新碟卡片与歌手页「专辑」横排共用：先进详情显示封面标题，曲目异步补齐；origin 决定返回落点。
    // accept6-favempty：收藏条目（fav_<平台>_<remoteId>）从音乐库打开时的远程水合。
    // 收藏只落元数据 + 点击瞬间的曲目快照（卡片收藏快照恒空、详情收藏与异步全量加载赛跑），
    // 音乐库打开原本只渲染快照、从不回拉，快照为空即假空单。这里与首页/专辑打开同口径：
    // 详情缓存优先、过期回源（歌单走 playlistDetailSnapshot、专辑按 albumMarks 分流 albumDetail），
    // 成功后回填收藏快照并纠正历史条目的 local 误标；回源拉空且条目标称非空时记失败态，
    // 由详情页渲染失败卡，绝不伪装成空单。全部在 IO 上执行，守卫按收藏 id 对齐当前详情。
    val hydrateFavoriteDetail: (Playlist) -> Unit = { entry ->
        val favId = entry.id
        val src0 = entry.source.ifEmpty { "wy" }
        val remoteId = playlistFavoriteRemoteId(entry)
        if (remoteId.isNotBlank() && src0 in MusicSourceRegistry.platformIds) {
            if (plDetail?.id == favId) {
                if (plDetailLoadFailedId == favId) plDetailLoadFailedId = null
                if (plDetailSongs.isEmpty()) plDetailLoading = true
            }
            scope.launch {
                val isAlbum = AlbumFavoriteMarks.marks(context).contains(favId)
                var fullDetail: Playlist? = null
                var cachedSongs: List<Song> = emptyList()
                val cached = try {
                    HomeFeedCache.loadDetailSnapshotOnIo(context, remoteId, src0)
                } catch (_: Exception) {
                    DetailCacheSnapshot(emptyList(), 0L)
                }
                val resolution = try {
                    resolveDetailCache(
                        cache = cached,
                        ttlMs = HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS,
                        publishCached = { songs ->
                            cachedSongs = songs
                            if (plDetail?.id == favId && plDetailSongs.isEmpty()) {
                                plDetailSongs = songs
                                plDetailLoading = false
                            }
                        },
                        fetchDetail = {
                            withContext(Dispatchers.IO) {
                                fullDetail = when {
                                    isAlbum && src0 == "mg" && entry.detailContentKind.equals("digital", true) ->
                                        xyz.cncnlp.soundwave.source.direct.fetchMgDigitalAlbumDetailDirect(remoteId)
                                    isAlbum -> MusicSourceRegistry.platformSource(src0).albumDetail(remoteId)
                                    !ProviderRegistry.capability(src0, prefs).playlistDetail -> null
                                    else -> MusicSourceRegistry.platformSource(src0)
                                        .playlistDetailSnapshot(remoteId, entry.copy(id = remoteId, local = false))
                                }
                                fullDetail?.songs.orEmpty()
                            }
                        },
                    )
                } catch (_: Exception) {
                    null
                }
                val songs = when (resolution) {
                    is DetailCacheResolution.CacheHit -> resolution.songs
                    is DetailCacheResolution.Refreshed -> resolution.value.ifEmpty { resolution.cachedSongs }
                    null -> cachedSongs
                }
                val stillOpen = plDetail?.id == favId
                if (stillOpen) plDetailLoading = false
                if (songs.isNotEmpty()) {
                    val base = fullDetail
                    val cover = if (base != null) playlistDisplayCover(base, songs) else entry.cover
                    val count = maxOf(entry.count, base?.count ?: 0, songs.size)
                    if (stillOpen) {
                        plDetail = entry.copy(
                            songs = songs.toMutableList(),
                            cover = cover.ifBlank { entry.cover },
                            count = count,
                            local = false,
                            author = base?.author?.ifBlank { entry.author } ?: entry.author,
                            description = base?.description?.ifBlank { entry.description } ?: entry.description,
                        )
                        plDetailSongs = songs
                        if (plDetailLoadFailedId == favId) plDetailLoadFailedId = null
                    }
                    try { HomeFeedCache.saveDetail(context, remoteId, src0, songs) } catch (_: Exception) {}
                    // 回填收藏快照：下次（含离线）打开直接有歌；同时把历史条目的 local 误标一并纠正。
                    val savedIndex = userBundle.playlists.indexOfFirst { it.id == favId }
                    if (savedIndex >= 0) {
                        val saved = userBundle.playlists.toMutableList()
                        val current = saved[savedIndex]
                        val healed = (if (base != null) {
                            current.withPlaylistDetailMetadata(base, songs, cover.ifBlank { current.cover }, count)
                        } else {
                            current.copy(songs = songs.toMutableList(), count = count)
                        }).copy(local = false)
                        if (healed != current) {
                            saved[savedIndex] = healed
                            userBundle = userBundle.copy(playlists = saved)
                            persistBundle()
                        }
                    }
                } else if (stillOpen && plDetailSongs.isEmpty() && entry.count > 0) {
                    // 回源拉空但条目标称非空：计数与内容不许自相矛盾——如实记失败，不落空态。
                    plDetailLoadFailedId = favId
                }
            }
        }
    }
    val openDiscoverCardCb: (DiscoverCard, Tab) -> Unit = { card, origin ->
        detailOriginTab = origin
        if (!card.kind.equals("new_song", true)) AlbumFavoriteMarks.noteAlbum(card.source.ifBlank { "wy" }, card.id)
        // 先立即进入详情页，显示首页已拿到的封面/标题；曲目列表异步补齐，避免点击后白等 5~10 秒。
        // accept6-backnav：先走 openDetail 压栈（快照须含父级自己的页头覆盖），再写本级的页头覆盖。
        openDetail(
            Playlist(
                id = card.id,
                name = card.name.ifBlank { card.title.ifBlank { "专辑" } },
                cover = card.cover,
                songs = mutableListOf(),
                source = card.source.ifBlank { "wy" },
                author = card.artist,
                description = if (card.kind.equals("new_song", true)) card.album.ifBlank { "新歌" } else card.title.ifBlank { "专辑" },
                count = 0,
                detailContentKind = card.kind.lowercase(java.util.Locale.ROOT),
                remoteId = card.id
            ),
            emptyList()
        )
        // r658b 第 3 项：数字专辑详情页头标题 =「专辑详情」（此前按歌单判定显示「歌单详情」）。
        detailHeaderOverride = card.id to if (origin == Tab.HOME && (card.kind.equals("new_album", true) || card.kind.equals("new_song", true))) "新碟首发" else if (card.kind.equals("digital", true)) "专辑详情" else ""
        scope.launch {
            val src = card.source.ifBlank { "wy" }
            val directSong = if (card.kind.equals("new_song", true) && card.songId.isNotBlank()) {
                Song(id = card.songId, name = card.name, singer = card.artist, album = card.album, cover = card.cover, source = src)
            } else null
            // 批30-1：专辑/新碟详情缓存——进入即显示缓存曲目，网络结果静默覆盖（修「每次点进去重新加载」）。
            var cachedDetailSongs = emptyList<Song>()
            var usedFreshCache = false
            val pl = if (directSong != null) {
                val cachedSongs = try { HomeFeedCache.loadDetailOnIo(context, card.id, src, HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS) } catch (_: Exception) { emptyList() }
                if (cachedSongs.isNotEmpty() && plDetail?.id == card.id) plDetailSongs = cachedSongs
                null
            } else {
                val cached = try {
                    HomeFeedCache.loadDetailSnapshotOnIo(context, card.id, src)
                } catch (_: Exception) {
                    DetailCacheSnapshot(emptyList(), 0L)
                }
                when (val resolution = resolveDetailCache(
                    cache = cached,
                    ttlMs = HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS,
                    publishCached = { songs ->
                        if (plDetail?.id == card.id) {
                            cachedDetailSongs = songs
                            plDetailSongs = songs
                        }
                    },
                    fetchDetail = {
                        withContext(Dispatchers.IO) {
                            if (src == "mg" && card.kind == "digital") {
                                xyz.cncnlp.soundwave.source.direct.fetchMgDigitalAlbumDetailDirect(card.id)
                            } else if (src in MusicSourceRegistry.platformIds) {
                                MusicSourceRegistry.platformSource(src).albumDetail(card.id)
                            } else {
                                ApiClient.albumDetail(card.id, src)
                            }
                        }
                    },
                )) {
                    is DetailCacheResolution.CacheHit -> {
                        usedFreshCache = true
                        Playlist(
                            card.id,
                            card.name.ifBlank { card.title.ifBlank { "专辑" } },
                            card.cover,
                            resolution.songs.toMutableList(),
                            source = src,
                            author = card.artist,
                            description = card.title.ifBlank { "专辑" },
                            count = resolution.songs.size,
                            detailContentKind = card.kind.lowercase(java.util.Locale.ROOT),
            remoteId = card.id,
                        )
                    }
                    is DetailCacheResolution.Refreshed -> resolution.value
                }
            }
            // 批30-1：切走守卫——加载期间用户已返回/切页，不得把详情页拉回来（plDetail 已变/已清）。
            if (plDetail?.id != card.id) return@launch
            val songs = directSong?.let { listOf(it) } ?: pl?.songs.orEmpty().ifEmpty { cachedDetailSongs }
            val cover = pl?.cover?.ifBlank { card.cover } ?: card.cover
            val name = pl?.name?.ifBlank { card.name } ?: card.name
            val description = if (directSong != null) card.album.ifBlank { "新歌" } else card.title.ifBlank { "专辑" }
            plDetail = Playlist(
                id = card.id,
                name = name,
                cover = cover,
                songs = songs.toMutableList(),
                source = card.source.ifBlank { "wy" },
                author = card.artist,
                description = description,
                count = songs.size,
                detailContentKind = card.kind.lowercase(java.util.Locale.ROOT),
            remoteId = card.id
            )
            plDetailSongs = songs
            if (songs.isNotEmpty() && !usedFreshCache) {
                try { HomeFeedCache.saveDetail(context, card.id, src, songs) } catch (_: Exception) {}
            }
            if (songs.isEmpty()) AppNotice.toast("专辑暂无曲目")
        }
    }
    // ACCEPT5：album 通道在播放器内打开时置 detailOverPlayer（详情压播放器直达，不退播放器）；
    // artist / playlist 通道在压播放器详情内触发时不再抢切 tab（层级一致性守卫），标记为假时逐字不变。
    TrackNavigation.bind(artist = { sg, origin -> if (tab == Tab.PLAYER && !detailOverPlayer) tab = prevTab; homeArtistOrigin[0] = origin; homeArtistSrc[0] = sg.source; openHomeArtistCb(sg.singer, sg.cover, sg.singerId) }, album = { card, origin -> detailOverPlayer = tab == Tab.PLAYER; openDiscoverCardCb(card, origin) }, currentTab = { if (tab == Tab.PLAYER) prevTab else tab }, playlist = { pl, origin -> if (tab == Tab.PLAYER && !detailOverPlayer) tab = prevTab; detailOriginTab = origin; openDetail(pl, pl.songs); plDetailLoading = false }, homeSection = { sectionKey -> homePagerSelection.onSectionSettled("hot"); HomeSectionScrollRequest.request(sectionKey); returnToTopLevelTab(Tab.HOME) })

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootDetailOverlay(abovePlayer: Boolean = false) {
        val detailKeyboardController = LocalSoftwareKeyboardController.current
        // accept6-backnav：三层帧（under = 栈顶父级快照 / 主层 / exiting），返回时父级全程保持组合。
        val pushFrame = xyz.cncnlp.soundwave.ui.motion.rememberDetailPushFrame(
            current = plDetail?.let { it to plDetailSongs },
            underlayItem = detailStack.lastOrNull()?.let { it.detail to it.songs }
        ) { it.first.id }
        val renderedDetail = pushFrame.item?.first; val renderedDetailSongs = pushFrame.item?.second.orEmpty()
        // SW-12-11（详情部分）：预测性返回（API 34+）。启用条件与 AppRoute 返回优先级对齐：登录弹窗 / 下载选择器 / 播放器优先、详情居其后；
        // 注册在本层内容之前，详情内多选 / 弹层（LibraryPage 自带 BackHandler）与 AppOverlay、播放器的返回处理仍按既有注册顺序优先，不被抢占。
        // ACCEPT5：abovePlayer（详情压播放器）时同样启用——此时详情在最上，返回必须先关详情回到播放器。
        xyz.cncnlp.soundwave.ui.motion.DetailPredictiveBackHandler(
            enabled = plDetail != null && (tab != Tab.PLAYER || abovePlayer) && downloadPickSong == null,
            onBack = { detailKeyboardController?.hide(); closeDetail() }
        )
        // ACCEPT5：经典返回键（按键 / API < 34）在 abovePlayer 时由本层拦截先关详情——根
        // AppRoute.backAction 在 PLAYER 下优先 ClosePlayer（其纯函数契约不动），本 handler 注册序
        // 在播放器层之后（高层落点构成更晚），天然优先；下载选择器在场时让行，与预测性返回同口径。
        androidx.activity.compose.BackHandler(enabled = abovePlayer && plDetail != null && downloadPickSong == null) {
            detailKeyboardController?.hide(); closeDetail()
        }
        // accept6-backnav：同一份 LibraryPage 调用按层复用——live 层为当前详情（行为与旧版逐字
        // 一致）；under / exiting 层为冻结快照（触摸被主层屏蔽、不接管状态栏与多选回调）。
        val detailLayer: @Composable (Playlist, List<Song>, Boolean) -> Unit = { layerDetail, layerSongs, live ->
            SoundwaveDesignTheme(isDark = isDark) { LibraryPage(
                    bundle = userBundle,
                    history = history,
                    downloads = downloads,
                    loggedIn = true,
                    user = user,
                    avatarPath = avatarPath,
                    detail = layerDetail,
                    detailSongs = layerSongs,
                    detailLoading = live && plDetailLoading,
                    detailLoadFailed = live && plDetailLoadFailedId != null && plDetailLoadFailedId == layerDetail.id,
                    onRetryDetail = {
                        val failed = userBundle.playlists.firstOrNull { it.id == layerDetail.id } ?: layerDetail
                        if (failed.isRemoteFavoriteEntry()) hydrateFavoriteDetail(failed)
                    },
                    detailCoverStyle = detailCoverStyle,
                    onDetailStatusBarAppearance = { if (live) immersiveDetailStatusBarLight = it },
                    onDetailSelectionModeChange = { if (live) detailSelectionActive = it },
                    detailHeaderTitle = if (detailHeaderOverride.first == layerDetail.id) detailHeaderOverride.second else "",
                    onOpenSingerAlbum = { a -> openDiscoverCardCb(DiscoverCard(a.id, a.name, a.cover, layerDetail.author, a.kind, if (a.kind == "digital") "数字专辑" else "专辑", source = a.source), detailOriginTab) },
                    suppressArtworkBackdrop = playerImmersiveTransitionSurfaceVisible(
                        playerLayout, fullPlayerVisibilityState.currentState, fullPlayerVisibilityState.targetState
                    ),
                    showLocateButton = true,
                    detailFromHome = detailOriginTab == Tab.HOME,
                    // 经典详情按来源隐藏不支持的远端收藏；Default2/沉浸封面始终提供安全的本地收藏状态入口。
                    showPlaylistFavorite = (detailOriginTab == Tab.HOME || detailOriginTab == Tab.SEARCH) &&
                        !layerDetail.id.startsWith("singer_"),
                    isPlaylistFavorited = { pl ->
                        playlistIsFavorited(pl, pl.source.ifBlank { homePlatform }, userBundle.playlists)
                    },
                    onTogglePlaylistFavorite = { pl ->
                        val src = pl.source.ifBlank { homePlatform }
                        togglePlaylistFavorite(pl, src, userBundle.playlists, { updated ->
                            userBundle = userBundle.copy(playlists = updated)
                            plDetail = plDetail?.copy(favorited = playlistIsFavorited(pl, src, updated))
                            persistBundle(notify = true)
                        }, context)
                    },
                    currentKey = currentSong?.key().orEmpty(),
                    locateKey = locateTick,
                    onLogin = { openWebDavAuto() },
                    onOpen = {},
                    onBack = { detailKeyboardController?.hide(); closeDetail() },
                    onResumeDetail = { song, list ->
                        if (PlayerService.currentKey() == song.key() && PlayerService.hasPlayableMedia()) {
                            PlayerService.resume()
                        } else scope.launch {
                            resolveAndPlay(song, list, list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0))
                        }
                    },
                    onPlay = { song, list ->
                        scope.launch {
                            resolveAndPlay(song, list, list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0))
                        }
                    },
                    onImport = { showOnlineImportDlg = true },
                    onScanLocal = { scanLocalSongs() },
                    onAddLocalFolder = { pickLocalFolderLauncher.launch(null) },
                    localFolderLabels = localFolderLabels,
                    localScanRunning = localScanRunning,
                    localScanStatus = localScanStatus,
                    onHeartMode = { pool -> startHeartMode(pool) },
                    onPlaylistHeartMode = { playlist -> togglePlaylistHeartMode(playlist) },
                    activeHeartPlaylistKey = activeHeartPlaylistKey,
                    onClearLiked = {
                        val prevFavs = userBundle.favs.toList()
                        userBundle.clearFavorites()
                        userBundle = userBundle.copy(favs = userBundle.favs.toMutableList(), playlists = userBundle.playlists.toMutableList())
                        if (plDetail?.id == "liked") {
                            plDetail = plDetail?.copy(songs = mutableListOf(), count = 0)
                            plDetailSongs = emptyList()
                        }
                        persistBundle(notify = true)
                        AppNotice.undo("已清空喜欢") { prevFavs.reversed().forEach { userBundle.addFavorite(it) }; userBundle = userBundle.copy(favs = userBundle.favs.toMutableList(), playlists = userBundle.playlists.toMutableList()); if (plDetail?.id == "liked") { plDetail = plDetail?.copy(songs = userBundle.favs.toMutableList(), count = userBundle.favs.size); plDetailSongs = userBundle.favs.toList() }; likedTick++; persistBundle(notify = true) }
                    },
                    // 歌曲三点对话框：不感兴趣（本地屏蔽）/ 相似歌曲（基于当前曲目启动相似播放）。
                    onDislikeSong = { song ->
                        val detail = plDetail
                        if (detail?.id == xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.TODAY_DETAIL_ID) scope.launch {
                            val beforeDismiss = plDetailSongs
                            when (val outcome = xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.dismiss(
                                context, detail.source.ifBlank { homePlatform }, beforeDismiss, song)) {
                                xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.Outcome.QuotaUsed ->
                                    AppNotice.toast("今日最多替换 6 首", AppNotice.Kind.FAILURE)
                                is xyz.cncnlp.soundwave.recommend.TodayRecommendationRefresher.Outcome.Done ->
                                    AppNotice.toast(if (outcome.songs.size < beforeDismiss.size) "已移除，暂未找到合适的新歌" else "已移除并更新推荐")
                                else -> Unit
                            }
                        } else { DislikeStore.mark(context, song); AppNotice.undo("已标记不感兴趣") { DislikeStore.unmark(context, song) } }
                    },
                    onSimilarSong = { song -> scope.launch { playSimilarSong(song) } },
                    heartActive = playMode == PlayMode.HEART,
                    isLiked = { s -> likedTick; userBundle.isLiked(s) },
                    onLike = { s ->
                        val likedNow = userBundle.toggleLike(s)
                        val likedSongs = userBundle.favs.toList()
                        val syncedPlaylists = userBundle.playlists.map { pl ->
                            if (pl.id == "liked") pl.copy(songs = likedSongs.toMutableList(), count = likedSongs.size)
                            else pl
                        }.toMutableList()
                        userBundle = userBundle.copy(favs = likedSongs.toMutableList(), playlists = syncedPlaylists)
                        persistBundle()
                        if (currentSong?.key() == s.key()) liked = likedNow
                        if (plDetail?.id == "liked") {
                            plDetail = plDetail?.copy(songs = likedSongs.toMutableList(), count = likedSongs.size)
                            plDetailSongs = likedSongs
                        }
                        // 只同步“我的喜欢”详情对应的队列，普通收藏不能改写当前播放队列。
                        val snap = PlayerService.snapshot()
                        val q = snap.queue.toMutableList()
                        val pos = snap.queueIndex.coerceAtLeast(0)
                        val hadSong = q.any { it.key() == s.key() }
                        val syncLikedQueue = plDetail?.id == "liked" || hadSong
                        if (syncLikedQueue) q.removeAll { it.key() == s.key() }
                        if (syncLikedQueue && likedNow) q.add(s)
                        if (syncLikedQueue) {
                            playback.replaceQueue(q, pos.coerceAtMost((q.size - 1).coerceAtLeast(0)))
                        }
                        likedTick++
                    },
                    onLikeBatch = { batch, likedNow ->
                        val keys = batch.map { it.key() }.toSet()
                        if (likedNow) batch.forEach { userBundle.addFavorite(it) }
                        else userBundle.removeFavoriteKeys(keys)
                        val favs = userBundle.favs.toMutableList()
                        val syncedPlaylists = userBundle.playlists.map { pl ->
                            if (pl.id == "liked") pl.copy(songs = favs.toMutableList(), count = favs.size)
                            else pl
                        }.toMutableList()
                        userBundle = userBundle.copy(favs = favs, playlists = syncedPlaylists)
                        persistBundle()
                        if (plDetail?.id == "liked") {
                            plDetail = plDetail?.copy(songs = favs.toMutableList(), count = favs.size)
                            plDetailSongs = favs
                        }
                        // 只同步“我的喜欢”详情对应的队列；批量操作不逐条改写队列，避免 N 次 replaceQueue。
                        val snap = PlayerService.snapshot()
                        val q = snap.queue.toMutableList()
                        val hadAny = q.any { it.key() in keys }
                        if (plDetail?.id == "liked" || hadAny) {
                            val playingKey = snap.song?.key()
                            q.removeAll { it.key() in keys }
                            if (likedNow) {
                                val keep = batch.filter { it.key() !in q.map { x -> x.key() } }
                                q.addAll(0, keep)
                            }
                            val newPos = q.indexOfFirst { it.key() == playingKey }.coerceAtLeast(0)
                            playback.replaceQueue(q, newPos)
                        }
                        likedTick++
                    },
                    onAdd = { s -> addSongTarget = s; addSongBatch = listOf(s); showAddToPl = true },
                    onAddBatch = { batch ->
                        if (batch.isNotEmpty()) {
                            addSongBatch = batch.toList()
                            addSongTarget = batch.first()
                            showAddToPl = true
                        }
                    },
                    onAddNext = { item -> playback.insertNext(item); AppNotice.toast("已设为下一首", AppNotice.Kind.SUCCESS) },
                    onDownload = { s -> scope.launch { startDownload(s) } },
                    onDownloadBatch = { songs ->
                        download.confirmDownloads(songs, downloadQ.ifBlank { preferQ }.ifBlank { "320k" }) // r658d：批量一次入队
                    },
                    onDeleteSong = deleteSongCb,
                    onDeleteSongs = deleteSongsBatchCb,
                                            onCoverPick = { pl -> coverPickTarget = pl; pickCoverLauncher.launch("image/*") },
                    onRenamePlaylist = { pl, name ->
                        val pls = userBundle.playlists.toMutableList()
                        val i = pls.indexOfFirst { it.id == pl.id }
                        if (i >= 0) {
                            pls[i] = pls[i].copy(name = name)
                            userBundle = userBundle.copy(playlists = pls)
                            if (plDetail?.id == pl.id) plDetail = pls[i]
                            persistBundle()
                        }
                    },
                    onDeletePlaylist = { pl ->
                        if (pl.id != "liked" && pl.id != "local") {
                            // A 波：删歌单前快照整单（含曲目），删除后发带「撤销」的通知，撤销整单恢复。
                            val deletedSnapshot = userBundle.playlists.firstOrNull { it.id == pl.id }?.let { it.copy(songs = it.songs.toMutableList(), count = it.songs.size) }
                            // SW-09-16：排序键随删单一并清理（快照值供撤销恢复），避免同 id 重导继承旧排序。
                            val deletedSortMode = readPlaylistSortMode(context, pl.id)
                            userBundle.deletePlaylist(pl.id)
                            clearPlaylistSortMode(context, pl.id)
                            userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                            if (plDetail?.id == pl.id) { plDetail = null; plDetailSongs = emptyList() }
                            persistBundle(notify = true)
                            if (deletedSnapshot != null) AppNotice.undo("已删除歌单「${deletedSnapshot.name}」") {
                                userBundle.addOrReplacePlaylist(deletedSnapshot)
                                restorePlaylistSortMode(context, pl.id, deletedSortMode)
                                userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                                persistBundle(notify = true)
                            }
                        }
                    },

                    onReorderPlaylists = { order ->
                        reorderLibraryPlaylists(order)
                    },
                    onReorderFavorites = { order, albums -> favoriteOrder.reorder(order, albums) },
                    onClearHistory = {
                        val prevHistory = history
                        history = emptyList()
                        LocalStore.prefs(context).edit().putString("play_history_v1", "[]").apply()
                        persistBundle()
                        AppNotice.undo("已清空播放历史") { history = prevHistory; LocalStore.prefs(context).edit().putString("play_history_v1", org.json.JSONArray().apply { prevHistory.forEach { put(it.toJson()) } }.toString()).apply(); persistBundle() }
                    },
                    libOrder = libOrder,
                    favPlOrder = favoriteOrder.playlistOrder,
                    favAlbumOrder = favoriteOrder.albumOrder,
                    playingNow = playing,
                    onNewPlaylist = { newPlName = "新建歌单"; showNewPlDlg = true }
                ) } // 详情页统一设计稿配色（去除旧版蓝色描边/主色）
        }
        // accept6-backnav 三层渲染：under（父级快照）→ 主层（当前详情，live）→ exiting（滑出中的
        // 子详情快照）。任一层在场即整组覆盖（zIndex 沿旧口径）；触摸屏蔽与播放器转场底衬只挂主层。
        if (renderedDetail != null || pushFrame.under != null || pushFrame.exiting != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    // ACCEPT5：abovePlayer 时 4f 压过播放器层（3f）；其余沿 r657 口径（播放器下 1f / 常规 2f）。
                    .zIndex(if (abovePlayer) 4f else if (tab == Tab.PLAYER) 1f else 2f)
            ) {
                pushFrame.under?.let { underFrame ->
                    Box(Modifier.fillMaxSize().then(pushFrame.underModifier).background(bg.copy(alpha = 1f))) {
                        FrostedPageBackdrop(Modifier.matchParentSize(), isDark)
                        detailLayer(underFrame.first, underFrame.second, false)
                    }
                }
                if (renderedDetail != null) {
                    // 不透明根层直接覆盖，移除透明首帧及旧快照回写。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(pushFrame.modifier) // r657 推入 / 推出转场（accept6-backnav 后为主层位移）
                            .background(bg.copy(alpha = 1f))
                            .drawPlayerDetailUnderlayDuringTransition(
                                playerLayout,
                                fullPlayerVisibilityState.currentState,
                                fullPlayerVisibilityState.targetState,
                                playerDismissOffsetHolder
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { }
                    ) {
                        FrostedPageBackdrop(Modifier.matchParentSize(), isDark)
                        detailLayer(renderedDetail, renderedDetailSongs, true)
                    }
                }
                pushFrame.exiting?.let { exitFrame ->
                    Box(Modifier.fillMaxSize().then(pushFrame.exitingModifier).background(bg.copy(alpha = 1f))) {
                        FrostedPageBackdrop(Modifier.matchParentSize(), isDark)
                        detailLayer(exitFrame.first, exitFrame.second, false)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootHomeLayer() {
        val persistentHomePage: @Composable () -> Unit = {
            SoundwaveDesignTheme(isDark = isDark) {
            HomePage(
                feedState = homeFeed.renderState(homePlatform),
                visible = tab == Tab.HOME && plDetail == null && !showProfileSheet && !showWebDavSyncSheet,
                currentSong = currentSong,
                onTogglePlayback = { PlayerService.toggle() },
                onHeartMode = { pool -> startHeartMode(pool) },
                onOpenArtist = openHomeArtistCb,
                heartActive = playMode == PlayMode.HEART,
                history = history, likedSongs = userBundle.favs,
                // 今日推荐画像补充：收藏歌单/导入在线歌单的歌曲池（我的喜欢经 favs 单独传入）。
                tastePoolSongs = recommendationLibrary.songs,
                favoritePlaylists = recommendationLibrary.favoritePlaylists,
                favoriteAlbums = recommendationLibrary.favoriteAlbums,
                importedPlaylists = recommendationLibrary.importedPlaylists,
                profileOwner = recommendationLibrary.owner,
                recommendationProfile = recommendationLibrary.profile,
                loggedIn = true, user = user, avatarPath = avatarPath,
                platform = homePlatform,
                section = homePagerSelection.section,
                sectionPage = homePagerSelection.renderedPageIndex,
                pagerSyncRevision = homePagerSelection.pagerSyncRevision,
                onSection = {
                    val settledSection = homePagerSelection.onSectionSettled(it)
                    prefs.edit().putString("home_section", settledSection).apply()
                },
                onSearchEntry = {
                    lastContentTab = Tab.HOME
                    tab = Tab.SEARCH
                    searchDockFocus = false
                },
                onPlaylistSearchEntry = {
                    search.clearSearch()
                    searchMode = "pl"
                    searchPlatform = homePlatform
                    lastContentTab = Tab.HOME
                    tab = Tab.SEARCH
                    searchDockFocus = true
                },
                onPlatform = { p -> homeFeed.selectPlatform(p, homePlatform) { homePlatform = it } },
                onLogin = { openWebDavAuto() },
                // 批31：APP 头像长按退出移除——登录对话框右下角已有「退出登录」按钮，不重复。
                onAvatarLongClick = {},
                onOpenDiscover = { card -> openDiscoverCardCb(card, Tab.HOME) },
                    onRetryFeed = { homeFeed.refreshHomeFeed(forceNetwork = true) },
                    onRefreshHome = { homeFeed.onPullRefresh() },
                onPlay = { song, list ->
                    scope.launch { resolveAndPlay(song, list, list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0)) }
                },
                onOpenPlayer = { openFullPlayer() },
                onOpenPl = { pl ->
                    // 首页资源必须保持请求平台，禁止用网易默认值伪装其它平台。
                    val src0 = pl.source.ifEmpty { homePlatform }
                    val displayPlaylist = pl.copy(source = src0)
                    val remotePlaylistId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") }
                    val favoriteId = "fav_${src0}_${remotePlaylistId}"
                    val isFavorite = userBundle.playlists.any { it.id == favoriteId }
                    detailOriginTab = Tab.HOME
                    val detailTtl = if (pl.id.startsWith("rank_")) HomeFeedCache.ResourceTtl.RANK_DETAIL_MS else HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS
                    // 不在主线程反序列化 SharedPreferences 详情缓存；下方分支在 IO 上立即回填。
                    val seed = pl.songs
                    plDetailLoading = seed.isEmpty() && !pl.local && pl.id !in setOf("liked", "local", "history") && !pl.id.startsWith("local_")
                    val seedCover = playlistDisplayCover(displayPlaylist, seed)
                    // 详情状态和请求均使用远端 id，不能把 reco_ 这种纯 UI 标识传入接口。
                    openDetail(
                        Playlist(
                            remotePlaylistId, pl.name, seedCover, seed.toMutableList(), pl.local, src0,
                            author = pl.author, description = pl.description, count = pl.count,
                            remoteId = remotePlaylistId, favorited = isFavorite, playCount = pl.playCount
                        ),
                        seed
                    )
                    if (pl.id == "today_recommendation") {
                        plDetailLoading = false // Only the validated recommendation snapshot/update stream owns this detail.
                    } else if (seed.isNotEmpty() && !pl.id.startsWith("rank_")) {
                        // 已有歌曲先展示；后台拉全量，修复导入只剩十几首
                        scope.launch {
                            val fullDetail = withContext(Dispatchers.IO) {
                                val canRemote = pl.id !in setOf("liked", "local", "history") &&
                                    ProviderRegistry.capability(src0, prefs).playlistDetail &&
                                    (pl.id.all { it.isDigit() } || pl.id.length >= 6)
                                if (canRemote) {
                                    if (src0 in MusicSourceRegistry.platformIds) {
                                        MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)
                                    } else {
                                        ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)
                                    }
                                } else null
                            }
                            val more = fullDetail?.songs.orEmpty()
                            if (plDetail?.id == remotePlaylistId && more.isNotEmpty() && more.size >= seed.size) {
                                val base = fullDetail ?: displayPlaylist
                                val cover = playlistDisplayCover(base, more)
                                val count = maxOf(base.count, more.size)
                                plDetail = mergePlaylistDetailSnapshot(
                                    base, pl, more, remotePlaylistId, src0, cover,
                                    local = false, countFloor = base.count, favorited = isFavorite, playCount = pl.playCount,
                                )
                                plDetailSongs = more
                                // 收藏时可能详情还在后台加载；完整歌曲到达后回写收藏快照，底栏可直接播放。
                                val savedIndex = userBundle.playlists.indexOfFirst { it.id == favoriteId }
                                if (savedIndex >= 0) {
                                    val saved = userBundle.playlists.toMutableList()
                                    saved[savedIndex] = saved[savedIndex].withPlaylistDetailMetadata(base, more, cover, count)
                                    userBundle = userBundle.copy(playlists = saved)
                                    persistBundle()
                                }
                                try { HomeFeedCache.saveDetail(context, remotePlaylistId, src0, more) } catch (_: Exception) {}
                                // 回写本地，补全导入残缺
                                if (more.size > seed.size) {
                                    val idx = userBundle.playlists.indexOfFirst { it.id == pl.id }
                                    if (idx >= 0) {
                                        userBundle.playlists[idx] = userBundle.playlists[idx].copy(
                                            songs = more.toMutableList(),
                                            cover = cover,
                                            local = false,
                                            source = src0
                                        )
                                        userBundle = userBundle.copy(
                                            favs = userBundle.favs.toMutableList(),
                                            playlists = userBundle.playlists.toMutableList(),
                                            enabledScripts = userBundle.enabledScripts.toMutableList()
                                        ).also { it.ensureDefaults() }
                                        persistBundle()
                                    }
                                }
                            }
                        }
                    } else scope.launch {
                        try {
                            var fullDetail: Playlist? = null
                            val cached = HomeFeedCache.loadDetailSnapshotOnIo(context, remotePlaylistId, src0)
                            val resolution = resolveDetailCache(
                                cache = cached,
                                ttlMs = detailTtl,
                                // 热门歌曲详情原本每次打开都会回源；保留该行为，不把它纳入本次缓存优化。
                                forceRefresh = pl.id.startsWith("hot_"),
                                publishCached = { songs ->
                                    if (plDetail?.id == remotePlaylistId) {
                                        plDetail = Playlist(
                                            remotePlaylistId, pl.name, playlistDisplayCover(displayPlaylist, songs), songs.toMutableList(), pl.local, src0,
                                            author = pl.author, description = pl.description, count = songs.size,
                                            remoteId = remotePlaylistId, favorited = isFavorite, playCount = pl.playCount
                                        )
                                        plDetailSongs = songs
                                        if (!pl.id.startsWith("rank_")) plDetailLoading = false
                                    }
                                },
                                fetchDetail = {
                                    withContext(Dispatchers.IO) {
                                        when {
                                            pl.id.startsWith("rank_") && ProviderRegistry.capability(src0, prefs).rank -> {
                                                val rid = pl.id.removePrefix("rank_")
                                                // 规范平台经 Source；其余 source 保留旧 rank 请求与歌单兼容回退。
                                                if (src0 in MusicSourceRegistry.platformIds) {
                                                    MusicSourceRegistry.platformSource(src0).rank(rid).ifEmpty {
                                                        MusicSourceRegistry.platformSource(src0).playlistDetail(rid)
                                                    }
                                                } else {
                                                    ApiClient.rankDetail(rid, src0).ifEmpty {
                                                        ApiClient.playlistDetail(rid, src0)
                                                    }
                                                }
                                            }
                                            pl.id.startsWith("hot_") -> homeFeed.multiHot[src0].orEmpty().ifEmpty {
                                                if (src0 in MusicSourceRegistry.platformIds) {
                                                    MusicSourceRegistry.platformSource(src0).hotSongs().take(40)
                                                } else {
                                                    ApiClient.hot(src0).take(40)
                                                }
                                            }
                                            seed.isNotEmpty() -> seed
                                            !ProviderRegistry.capability(src0, prefs).playlistDetail -> emptyList()
                                            else -> {
                                                fullDetail = if (src0 in MusicSourceRegistry.platformIds) {
                                                    MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(remotePlaylistId, displayPlaylist)
                                                } else {
                                                    ApiClient.playlistDetailSnapshot(remotePlaylistId, src0, displayPlaylist)
                                                }
                                                fullDetail?.songs.orEmpty()
                                            }
                                        }
                                    }
                                },
                            )
                            val songs = when (resolution) {
                                is DetailCacheResolution.CacheHit -> {
                                    plDetailLoading = false
                                    return@launch
                                }
                                is DetailCacheResolution.Refreshed -> resolution.value
                            }
                            if (plDetail?.id != remotePlaylistId) return@launch // 已切走
                            if (songs.isNotEmpty()) {
                                val base = fullDetail ?: displayPlaylist
                                val cover = playlistDisplayCover(base, songs)
                                plDetail = mergePlaylistDetailSnapshot(
                                    base, pl, songs, remotePlaylistId, src0, cover,
                                    favorited = isFavorite, playCount = pl.playCount,
                                )
                                plDetailSongs = songs
                                try { HomeFeedCache.saveDetail(context, remotePlaylistId, src0, songs) } catch (_: Exception) {}
                            }
                        } finally {
                            if (plDetail?.id == remotePlaylistId) plDetailLoading = false
                        }
                    }
                },
                onLike = { s ->
                    val now = userBundle.toggleLike(s)
                    userBundle = userBundle.copy(
                        favs = userBundle.favs.toMutableList(),
                        playlists = userBundle.playlists.toMutableList(),
                        enabledScripts = userBundle.enabledScripts.toMutableList()
                    ).also { it.ensureDefaults() }
                    persistBundle()
                    if (currentSong?.key() == s.key()) liked = now
                    // 播放队列同步：点赞即把新歌追加到当前队列尾（去重）
                    if (now) {
                        val snap = PlayerService.snapshot()
                        val q = snap.queue.toMutableList()
                        if (q.isNotEmpty() && q.none { it.key() == s.key() }) {
                            q.add(s)
                            playback.replaceQueue(q, snap.queueIndex)
                        }
                    }
                    likedTick++
                },
                onAdd = { s -> addSongTarget = s; addSongBatch = listOf(s); showAddToPl = true },
                onDownload = { s ->
                    scope.launch {
                        AppNotice.toast("开始下载 ${s.name}", AppNotice.Kind.SUCCESS)
                        startDownload(s)
                    }
                },
                isLiked = { s -> likedTick; userBundle.isLiked(s) },
                nowPlayingKey = currentSong?.key().orEmpty(),
                isPlayingNow = playing,
                onAddBatch = { b -> if (b.isNotEmpty()) { addSongBatch = b.toList(); addSongTarget = b.first(); showAddToPl = true } },
                onAddNext = { item -> playback.insertNext(item) }, onSelectionModeChange = { homeSelectionActive = it },
                onDownloadBatch = { songs -> download.confirmDownloads(songs, downloadQ.ifBlank { preferQ }.ifBlank { "320k" }) }
            )
            }
        }
        val homePageVisible = tab == Tab.HOME || (tab == Tab.PLAYER && prevTab == Tab.HOME)
        val (homeLaidOut, homeFade) = xyz.cncnlp.soundwave.ui.route.rememberHomeLayerFadeThrough(homePageVisible) // r657 第二轮：首页层也走 fade-through
        Box(
            Modifier
                .clipToBounds()
                .then(if (homeLaidOut) Modifier.fillMaxSize().then(homeFade) else Modifier.size(0.dp))
                .zIndex(if (homeLaidOut) 0.5f else -1f)
        ) { persistentHomePage() }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootSearchRoute() {
        SoundwaveDesignTheme(isDark = isDark) {
        SearchPage(
                            songResults = searchResults,
                            plResults = searchPlResults,
                            artistResults = search.artistResults,
                            albumResults = search.albumResults,
                            loggedIn = true, user = user, avatarPath = avatarPath,
                            kw = searchKw, onKw = { searchKw = it },
                            mode = searchMode, onMode = { searchMode = it },
                            onBack = {
                                searchDockFocus = false
                                returnToTopLevelTab(if (lastContentTab in setOf(Tab.HOME, Tab.PLAYLISTS, Tab.SETTINGS)) lastContentTab else Tab.HOME)
                            },
                            onBackToSearch = {
                                searchDockFocus = false
                                search.returnToSearch()
                            },
                            searched = searchResult != null,
                            onLogin = { openWebDavAuto() },
                            // 搜索只允许走 SearchController：它带请求代次，旧关键词/旧平台迟到结果不会覆盖当前结果。
                            onSearch = { kw, src, mode -> searchDockFocus = false; runSearch(kw, src, mode) },
                            onSearchFocus = {
                                searchDockFocus = it
                            },
                            onClearSearch = { search.clearSearch() },
                            onPlay = { song ->
                                scope.launch { resolveAndPlay(song, searchResults, searchResults.indexOfFirst { it.key() == song.key() }) }
                            },
                            onOpenAlbum = { song ->
                                detailOriginTab = Tab.SEARCH
                                scope.launch {
                                    val album = if (song.albumId.isNotBlank()) {
                                        withContext(Dispatchers.IO) {
                                            if (song.source in MusicSourceRegistry.platformIds) {
                                                MusicSourceRegistry.platformSource(song.source).albumDetail(song.albumId)
                                            } else {
                                                ApiClient.albumDetail(song.albumId, song.source.ifBlank { "wy" })
                                            }
                                        }
                                    } else null
                                    val albumSongs = album?.songs.orEmpty()
                                    val resolvedAlbum = album?.takeIf { albumSongs.isNotEmpty() }
                                    AlbumFavoriteMarks.noteAlbum(song.source.ifBlank { "wy" }, song.albumId)
                                    resolvedAlbum?.let { AlbumFavoriteMarks.noteAlbum(it.source.ifBlank { song.source.ifBlank { "wy" } }, it.remoteId.ifBlank { it.id }) }
                                    if (resolvedAlbum != null) {
                                        openDetail(resolvedAlbum, resolvedAlbum.songs)
                                    } else {
                                        // 专辑详情接口缺失时，仅使用当前搜索结果中同平台同专辑的真实歌曲兜底，禁止伪造数据。
                                        val sameAlbum = searchResults.filter {
                                            it.source == song.source && it.album.isNotBlank() && it.album == song.album
                                        }
                                        if (sameAlbum.isNotEmpty()) {
                                            val detail = Playlist(
                                                id = song.id.ifBlank { "album_${song.source}_${song.album}" },
                                                name = song.album.ifBlank { "专辑" },
                                                cover = song.cover.ifEmpty { sameAlbum.first().cover },
                                                songs = sameAlbum.toMutableList(),
                                                source = song.source.ifBlank { "wy" },
                                                count = sameAlbum.size,
                                                remoteId = song.id
                                            )
                                            AlbumFavoriteMarks.noteAlbum(detail.source, detail.remoteId.ifBlank { detail.id })
                                            openDetail(detail, sameAlbum)
                                        } else {
                                            AppNotice.toast("当前平台暂无专辑详情")
                                        }
                                    }
                                }
                            },
                            onOpenPl = { pl ->
                                val src0 = pl.source.ifEmpty { "wy" }
                                detailOriginTab = Tab.SEARCH
                                val seed = pl.songs
                                openDetail(Playlist(pl.id, pl.name, playlistDisplayCover(pl, seed), seed.toMutableList(), pl.local, src0), seed)
                                scope.launch {
                                    val cached = if (seed.isEmpty()) {
                                        try { HomeFeedCache.loadDetailOnIo(context, pl.id, src0, HomeFeedCache.ResourceTtl.PLAYLIST_DETAIL_MS) } catch (_: Exception) { emptyList() }
                                    } else emptyList()
                                    if (plDetail?.id != pl.id) return@launch
                                    val initialSongs = seed.ifEmpty { cached }
                                    if (seed.isEmpty() && initialSongs.isNotEmpty()) {
                                        plDetail = Playlist(pl.id, pl.name, playlistDisplayCover(pl, initialSongs), initialSongs.toMutableList(), pl.local, src0)
                                        plDetailSongs = initialSongs
                                    }
                                    var fullDetail: Playlist? = null
                                    val songs = if (initialSongs.size >= 20 && src0 != "tx") initialSongs else withContext(Dispatchers.IO) {
                                        fullDetail = if (src0 in MusicSourceRegistry.platformIds) {
                                            MusicSourceRegistry.platformSource(src0).playlistDetailSnapshot(pl.id, pl)
                                        } else {
                                            ApiClient.playlistDetailSnapshot(pl.id, src0, pl)
                                        }
                                        fullDetail?.songs.orEmpty().ifEmpty { initialSongs }
                                    }
                                    if (plDetail?.id != pl.id) return@launch
                                    if (songs.isEmpty()) AppNotice.toast("歌单为空")
                                    else {
                                        val base = fullDetail ?: pl
                                        val cover = playlistDisplayCover(base, songs)
                                        plDetail = mergePlaylistDetailSnapshot(
                                            base, pl, songs, pl.id, src0, cover,
                                            remoteId = pl.remoteId.ifBlank { pl.id.removePrefix("reco_") },
                                        )
                                        plDetailSongs = songs
                                        try { HomeFeedCache.saveDetail(context, pl.id, src0, songs) } catch (_: Exception) {}
                                    }
                                }
                            },
                            onOpenSinger = openSingerCb,
                            onLike = { s ->
                                val now = userBundle.toggleLike(s)
                                userBundle = userBundle.copy(
                                    favs = userBundle.favs.toMutableList(),
                                    playlists = userBundle.playlists.toMutableList(),
                                    enabledScripts = userBundle.enabledScripts.toMutableList()
                                ).also { it.ensureDefaults() }
                                persistBundle()
                                if (currentSong?.key() == s.key()) liked = now
                                // 播放队列同步：点赞即把新歌追加到当前队列尾（去重）
                                if (now) {
                                    val snap = PlayerService.snapshot()
                                    val q = snap.queue.toMutableList()
                                    if (q.isNotEmpty() && q.none { it.key() == s.key() }) {
                                        q.add(s)
                                        playback.replaceQueue(q, snap.queueIndex)
                                    }
                                }
                                likedTick++
                            },
                            onAdd = { s -> addSongTarget = s; addSongBatch = listOf(s); showAddToPl = true },
                            onAddNext = { s -> playback.insertNext(s); AppNotice.toast("已设为下一首", AppNotice.Kind.SUCCESS) },
                            onSimilarSong = { s -> scope.launch { playSimilarSong(s) } },
                            onDislikeSong = { s -> DislikeStore.mark(context, s); AppNotice.undo("已标记不感兴趣") { DislikeStore.unmark(context, s) } },
                            onDownload = { s ->
                                scope.launch {
                                    AppNotice.toast("开始下载 ${s.name}", AppNotice.Kind.SUCCESS)
                                    startDownload(s)
                                }
                            },
                            hotKeywords = homeFeed.hotKeywords,
                            hotWordsPlatform = homePlatform,
                            searchHistory = searchHistory,
                            recommendedSongs = personalizedSearchSongs,
                            recommendationLoading = personalizedSearchLoading,
                            recommendationMessage = personalizedSearchMessage,
                            onRefreshRecommendations = {
                                search.refreshPersonalRecommendations(searchPlatform, history, userBundle.favs, force = true)
                            },
                            crossPlatform = crossPlatformSearch,
                            onCrossPlatform = {
                                crossPlatformSearch = it
                                prefs.edit().putBoolean("cross_search", it).apply()
                            },
                            onHistoryClick = { q ->
                                // handled inside SearchPage via onSearch after setting kw
                            },
                            onHistoryDelete = { w ->
                                searchHistory = searchHistory.filter { it != w }
                                prefs.edit().putString("search_history_v1", searchHistory.joinToString("||")).apply()
                            },
                            onHistoryClear = {
                                searchHistory = emptyList()
                                prefs.edit().putString("search_history_v1", "").apply()
                            },
                            isLiked = { s -> likedTick; userBundle.isLiked(s) },
                            platform = searchPlatform,
                            onPlatform = { searchPlatform = it },
                            nowPlayingKey = currentSong?.key().orEmpty(),
                            isPlayingNow = playing,
                            songContent = search.searchContent,
                            playlistContent = search.plContent,
                            artistContent = search.artistContent,
                            albumContent = search.albumContent,
                            onRetrySearch = { if (searchKw.isNotBlank()) runSearch(searchKw, searchPlatform, searchMode) }
                        )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootPlaylistsRoute() {
        SoundwaveDesignTheme(isDark = isDark) {
        LibraryPage(
                            bundle = userBundle,
                            history = history,
                            downloads = downloads,
                            loggedIn = true,
                            user = user,
                            avatarPath = avatarPath,
                            platform = homePlatform,
                            detail = null, // 详情用全局叠层，避免卡顿残影
                            detailSongs = emptyList(),
                            currentKey = currentSong?.key().orEmpty(), currentCover = currentSong?.cover.orEmpty(),
                            locateKey = locateTick,
                            onLogin = { openWebDavAuto() },
                            onOpen = { pl ->
                                detailOriginTab = Tab.PLAYLISTS
                                // accept6-favempty：收藏条目是远程书签——展示层按远程语义呈现
                                // （历史条目落盘的 local=true 是误标，不改存储、只在此纠正呈现），
                                // 并启动回源水合；其余条目（自建/导入/本地）行为逐字不变。
                                val remoteFavorite = pl.isRemoteFavoriteEntry()
                                // 用叠层展示详情（accept6-backnav：统一走 openDetail 压栈口）
                                openDetail(
                                    if (remoteFavorite) pl.copy(local = false) else pl,
                                    when {
                                        pl.id == "liked" -> userBundle.favs.toList()
                                        pl.id == "history" -> history
                                        pl.id == "local" -> pl.songs.toList().ifEmpty {
                                            userBundle.playlists.firstOrNull { it.id == "local" }?.songs?.toList().orEmpty()
                                        }
                                        else -> pl.songs.toList()
                                    }
                                )
                                if (remoteFavorite) hydrateFavoriteDetail(pl)
                            },
                            onBack = closeDetail,
                            onPlay = { song, list ->
                                scope.launch {
                                    resolveAndPlay(song, list, list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0))
                                }
                            },
                            onPlayFromPlaylist = { song, list, playlistId ->
                                scope.launch {
                                    resolveAndPlay(
                                        song,
                                        list,
                                        list.indexOfFirst { it.key() == song.key() }.coerceAtLeast(0),
                                        originPlaylistId = playlistId
                                    )
                                }
                            },
                            onImport = { showOnlineImportDlg = true },
                            onScanLocal = { scanLocalSongs() },
                            onAddLocalFolder = { pickLocalFolderLauncher.launch(null) },
                            localFolderLabels = localFolderLabels,
                            localScanRunning = localScanRunning,
                            localScanStatus = localScanStatus,
                            onRefreshPlaylistCounts = {
                                playlistCountRefresh.refresh(userBundle.playlists) { refreshed ->
                                    userBundle = applyPlaylistCountUpdates(userBundle, refreshed)
                                    plDetail = plDetail?.let { applyPlaylistCountUpdates(it, refreshed) }
                                    persistBundle(notify = true)
                                }
                            },
                            onHeartMode = { pool -> startHeartMode(pool) },
                            onPlaylistHeartMode = { playlist -> togglePlaylistHeartMode(playlist) },
                            activeHeartPlaylistKey = activeHeartPlaylistKey,
                                onClearLiked = {
                                    val prevFavs = userBundle.favs.toList()
                                    userBundle.clearFavorites()
                                    userBundle = userBundle.copy(favs = userBundle.favs.toMutableList(), playlists = userBundle.playlists.toMutableList())
                                    if (plDetail?.id == "liked") {
                                        plDetail = plDetail?.copy(songs = mutableListOf(), count = 0)
                                        plDetailSongs = emptyList()
                                    }
                                    persistBundle(notify = true)
                                    AppNotice.undo("已清空喜欢") { prevFavs.reversed().forEach { userBundle.addFavorite(it) }; userBundle = userBundle.copy(favs = userBundle.favs.toMutableList(), playlists = userBundle.playlists.toMutableList()); if (plDetail?.id == "liked") { plDetail = plDetail?.copy(songs = userBundle.favs.toMutableList(), count = userBundle.favs.size); plDetailSongs = userBundle.favs.toList() }; likedTick++; persistBundle(notify = true) }
                                },
                                heartActive = playMode == PlayMode.HEART,
                            isLiked = { s -> likedTick; userBundle.isLiked(s) },
                            onLike = { s ->
                                val likedNow = userBundle.toggleLike(s)
                                userBundle = userBundle.copy(
                                    favs = userBundle.favs.toMutableList(),
                                    playlists = userBundle.playlists.toMutableList()
                                )
                                persistBundle()
                                if (currentSong?.key() == s.key()) liked = likedNow
                                // 普通页面收藏不能清空/重建当前播放队列；仅当队列本身含该歌时同步移除/恢复。
                                val snap = PlayerService.snapshot()
                                val q = snap.queue.toMutableList()
                                val hadSong = q.any { it.key() == s.key() }
                                if (hadSong) {
                                    val pos = snap.queueIndex.coerceAtLeast(0)
                                    q.removeAll { it.key() == s.key() }
                                    if (likedNow) q.add(s)
                                    playback.replaceQueue(q, pos.coerceAtMost((q.size - 1).coerceAtLeast(0)))
                                }
                                // 收藏详情同步歌曲与数量，避免详情头部停留旧数字。
                                if (plDetail?.id == "liked") {
                                    val likedSongs = userBundle.favs.toList()
                                    plDetail = plDetail?.copy(songs = likedSongs.toMutableList(), count = likedSongs.size)
                                    plDetailSongs = likedSongs
                                }
                                likedTick++
                                AppNotice.undo(if (likedNow) "已加入喜欢" else "已取消喜欢") { userBundle.toggleLike(s); userBundle = userBundle.copy(favs = userBundle.favs.toMutableList(), playlists = userBundle.playlists.toMutableList()); persistBundle(); if (currentSong?.key() == s.key()) liked = !likedNow; if (plDetail?.id == "liked") { val ls = userBundle.favs.toList(); plDetail = plDetail?.copy(songs = ls.toMutableList(), count = ls.size); plDetailSongs = ls }; likedTick++ }
                            },
                            onLikeBatch = { batch, likedNow ->
                                val keys = batch.map { it.key() }.toSet()
                                if (likedNow) batch.forEach { userBundle.addFavorite(it) }
                                else userBundle.removeFavoriteKeys(keys)
                                val favs = userBundle.favs.toMutableList()
                                userBundle = userBundle.copy(
                                    favs = favs,
                                    playlists = userBundle.playlists.toMutableList()
                                )
                                persistBundle()
                                if (plDetail?.id == "liked") {
                                    val likedSongs = userBundle.favs.toList()
                                    plDetail = plDetail?.copy(songs = likedSongs.toMutableList(), count = likedSongs.size)
                                    plDetailSongs = likedSongs
                                }
                                // 批量收藏只在队列含这些歌时同步一次队列，不逐条改写。
                                val snap = PlayerService.snapshot()
                                val q = snap.queue.toMutableList()
                                val hadAny = q.any { it.key() in keys }
                                if (hadAny) {
                                    val playingKey = snap.song?.key()
                                    q.removeAll { it.key() in keys }
                                    if (likedNow) q.addAll(0, batch.filter { it.key() !in q.map { x -> x.key() } })
                                    val newPos = q.indexOfFirst { it.key() == playingKey }.coerceAtLeast(0)
                                    playback.replaceQueue(q, newPos)
                                }
                                likedTick++
                                AppNotice.undo(if (likedNow) "已加入喜欢 ${batch.size} 首" else "已取消喜欢 ${batch.size} 首") { if (likedNow) userBundle.removeFavoriteKeys(keys) else batch.forEach { userBundle.addFavorite(it) }; val rf = userBundle.favs.toMutableList(); userBundle = userBundle.copy(favs = rf, playlists = userBundle.playlists.toMutableList()); persistBundle(); if (plDetail?.id == "liked") { plDetail = plDetail?.copy(songs = rf.toMutableList(), count = rf.size); plDetailSongs = rf.toList() }; currentSong?.let { cs -> if (cs.key() in keys) liked = !likedNow }; likedTick++ }
                            },
                            onAdd = { s -> addSongTarget = s; addSongBatch = listOf(s); showAddToPl = true },
                            onAddNext = { item -> playback.insertNext(item); AppNotice.toast("已设为下一首", AppNotice.Kind.SUCCESS) },
                            onDownload = { s -> scope.launch { startDownload(s) } },
                            onDownloadBatch = { songs ->
                                download.confirmDownloads(songs, downloadQ.ifBlank { preferQ }.ifBlank { "320k" }) // r658d：批量一次入队
                            },
                            onDeleteSong = deleteSongCb,
                                onDeleteSongs = deleteSongsBatchCb,
                                                onCoverPick = { pl -> coverPickTarget = pl; pickCoverLauncher.launch("image/*") },
                            onRenamePlaylist = { pl, name ->
                                val pls = userBundle.playlists.toMutableList()
                                val i = pls.indexOfFirst { it.id == pl.id }
                                if (i >= 0) {
                                    pls[i] = pls[i].copy(name = name)
                                    userBundle = userBundle.copy(playlists = pls)
                                    if (plDetail?.id == pl.id) plDetail = pls[i]
                                    persistBundle()
                                }
                            },
                            onDeletePlaylist = { pl ->
                                if (pl.id != "liked" && pl.id != "local") {
                                    // A 波：删歌单前快照整单（含曲目），删除后发带「撤销」的通知，撤销整单恢复。
                                    val deletedSnapshot = userBundle.playlists.firstOrNull { it.id == pl.id }?.let { it.copy(songs = it.songs.toMutableList(), count = it.songs.size) }
                                    // SW-09-16：排序键随删单一并清理（快照值供撤销恢复），避免同 id 重导继承旧排序。
                                    val deletedSortMode = readPlaylistSortMode(context, pl.id)
                                    userBundle.deletePlaylist(pl.id)
                                    clearPlaylistSortMode(context, pl.id)
                                    userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                                    if (plDetail?.id == pl.id) { plDetail = null; plDetailSongs = emptyList() }
                                    persistBundle(notify = true)
                                    if (deletedSnapshot != null) AppNotice.undo("已删除歌单「${deletedSnapshot.name}」") {
                                        userBundle.addOrReplacePlaylist(deletedSnapshot)
                                        restorePlaylistSortMode(context, pl.id, deletedSortMode)
                                        userBundle = userBundle.copy(playlists = userBundle.playlists.toMutableList())
                                        persistBundle(notify = true)
                                    }
                                }
                            },

                            onReorderPlaylists = { order ->
                                reorderLibraryPlaylists(order)
                            },
                            onReorderFavorites = { order, albums -> favoriteOrder.reorder(order, albums) },
                            onClearHistory = {
                                val prevHistory = history
                                history = emptyList()
                                LocalStore.prefs(context).edit().putString("play_history_v1", "[]").apply()
                                persistBundle()
                                AppNotice.undo("已清空播放历史") { history = prevHistory; LocalStore.prefs(context).edit().putString("play_history_v1", org.json.JSONArray().apply { prevHistory.forEach { put(it.toJson()) } }.toString()).apply(); persistBundle() }
                            },
                            libOrder = libOrder,
                            favPlOrder = favoriteOrder.playlistOrder,
                            favAlbumOrder = favoriteOrder.albumOrder,
                            playingNow = playing,
                            onNewPlaylist = { newPlName = "新建歌单"; showNewPlDlg = true },
                            onPlatformClick = { code ->
                                if (homePlatform != code) {
                                    homePlatform = code
                                    homeFeed.refreshHomeFeed(forceNetwork = true)
                                }
                            },
                            platFilter = platFilter,
                            onPlatFilter = { platFilter = it }
                        )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootSettingsRoute() {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalWebDavAutoSyncUi provides WebDavAutoSyncUi(
                active = true,
                enabled = webDavAutoSync.state.enabled,
                ready = webDavAutoSync.state.manualReady,
                uncertain = webDavAutoSync.state.uncertain,
                canEnable = webDavAutoSync.canEnable,
                onEnabled = {
                    if (!webDavAutoSync.setEnabled(it)) {
                        AppNotice.inbox(AppNotice.Category.SYNC, "自动同步授权未能持久保存", alsoBanner = true)
                    }
                },
                onManualStarted = { webDavAutoSync.manualStarted() },
                onManualSucceeded = { webDavAutoSync.manualSucceeded(it) },
                onBindingEdited = { webDavAutoSync.revoke() },
                withAccountLock = { block -> account.withWebDavAccountLock { block() } }
            )
        ) {
        SettingsHub(
                            settingsSub = settingsSub,
                            settingsStack = settingsStack,
                            onSettingsSub = { sub -> settingsStack = if (sub == null) emptyList() else SettingsNavStack.pushed(settingsStack, sub) },
                            onSettingsBack = { settingsStack = SettingsNavStack.popped(settingsStack) },
                            onLoginClick = { openAccountEntry() },
                            onPrepareWebDavAvatarSync = { account.prepareWebDavAvatarSync() },
                            onPrepareWebDavProfileExtrasSync = { account.prepareWebDavProfileExtrasSync() },
                            onPrepareWebDavAccountDataSync = { account.prepareWebDavAccountDataSync() },
                            onImportWebDavProfileName = importDiscoveredWebDavProfile,
                            webDavBundle = userBundle,
                            onWebDavBundleApplied = { account.applyWebDavBundleLocally(it) },
                            onOpenAbout = { showAboutSheet = true },
                            onSmartSource = {
                                val s = currentSong
                                if (s == null) {
                                    status = "请先播放歌曲再换源"
                                } else {
                                    scope.launch {
                                        status = "智能换源中…"
                                        withContext(Dispatchers.IO) {
                                            UrlResolver.clearSongCache(s)
                                            UrlResolver.clearFailedScripts()
                                        }
                                        if (!LocalPlayback.isLocalSong(s)) s.resolvedUrl = ""
                                        resolveAndPlay(s, queue.ifEmpty { listOf(s) }, PlayerService.snapshot().queueIndex.coerceAtLeast(0))
                                    }
                                }
                            },
                            downloads = downloads,
                            onMoveDownload = { d, delta ->
                                scope.launch {
                                    val moved = withContext(Dispatchers.IO) { LocalStore.moveDownload(context, d, delta) }
                                    if (moved) downloads = localData.downloads()
                                }
                            },
                            onPauseDownload = { d -> pauseDownloads(listOf(d)) },
                            onPauseDownloads = { items -> pauseDownloads(items) },
                            onResumeDownload = { d -> resumeDownloads(listOf(d)) },
                            onResumeDownloads = { items -> resumeDownloads(items) },
                            onDeleteDownload = { d -> deleteDownloads(listOf(d), deleteFiles = true) },
                            onDeleteDownloadRecord = { d -> deleteDownloads(listOf(d), deleteFiles = false) },
                            onDeleteDownloads = { items -> deleteDownloads(items, deleteFiles = true) },
                            onDeleteDownloadRecords = { items -> deleteDownloads(items, deleteFiles = false) },
                            onPlayDownload = { d ->
                                val uri = if (d.path.startsWith("content://")) android.net.Uri.parse(d.path).toString() else d.path
                                val localSong = d.song.copy(resolvedUrl = uri)
                                val existing = queue.ifEmpty { PlayerService.snapshot().queue }
                                val playQueue = if (existing.any { it.key() == d.song.key() }) {
                                    existing.map { if (it.key() == d.song.key()) localSong else it }
                                } else existing + localSong
                                val playIndex = playQueue.indexOfFirst { it.key() == localSong.key() }.coerceAtLeast(0)
                                scope.launch { resolveAndPlay(localSong, playQueue, playIndex) }
                            },
                            onPickDownloadDir = {
                                try {
                                    // 若有 SAF 选择器
                                    pickDownloadDirLauncher.launch(null)
                                } catch (_: Exception) {
                                    AppNotice.toast("请用系统文件管理选择目录")
                                }
                            },
                            onOpenDownloadDir = {
                                val ok = DownloadHelper.openDir(context)
                                if (!ok) {
                                    AppNotice.toast("目录: ${DownloadHelper.dir(context).absolutePath}")
                                }
                            },
                            serverUrl = serverUrl,
                            preferQ = preferQ,
                            downloadQ = downloadQ,
                            loggedIn = true,
                            user = user,
                            status = status,
                            sleepRemain = sleepRemain,
                            avatarPath = avatarPath,
                            playSeconds = playSecondsBucket,
                            likedCount = userBundle.favs.size,
                            customPlaylistCount = userBundle.playlists.count { it.id !in setOf("liked", "local", "default") && !it.id.startsWith("fav_") && !it.favorited },
                            collectedPlaylistCount = userBundle.playlists.count { it.id.startsWith("fav_") || it.favorited },
                            scriptReady = scripts.count { it.enabled && it.code.isNotEmpty() },
                            scripts = scripts,
                            onUrl = { raw ->
                                val check = raw.endsWith("|check")
                                val pure = raw.removeSuffix("|check")
                                applyServer(pure)
                                if (check) {
                                    scope.launch {
                                        // 仅「保存并检测」改 status，登录流程不走这里
                                                                        val ping = withContext(Dispatchers.IO) { ApiClient.ping() }
                                        status = ping
                                    }
                                }
                            },
                            onQuality = {
                                preferQ = normalizeUserSelectableQuality(it)
                                prefs.edit().putString("play_br", preferQ).apply()
                            },
                            onDownloadQuality = {
                                downloadQ = normalizeUserSelectableQuality(it)
                                prefs.edit().putString("down_br", downloadQ).apply()
                            },
                            onSleep = { PlayerService.setSleepMode(it) },
                            onScriptsChange = {
                                scripts = it
                                ScriptStorage.saveScripts(sprefs, it)
                                scriptRuntime.invalidate()
                                persistBundle()
                            },
                            onImportCode = { code ->
                                val mm = scripts.toMutableList()
                                val result = runCatching { ScriptStorage.importScript(sprefs, mm, code) }
                                result.onSuccess {
                                    ScriptProbeStartupPolicy.noteImport(sprefs)
                                        scripts = mm.toList()
                                    scriptRuntime.invalidate()
                                    AppNotice.toast("已导入脚本：${it.name}", AppNotice.Kind.SUCCESS)
                                }.onFailure {
                                    AppNotice.toast("脚本导入失败", AppNotice.Kind.FAILURE)
                                }
                            },
                            onImportUrl = { url ->
                                scope.launch {
                                    // P2-10：先预取（校验 + SHA-256 + 来源域名），展示确认后再真正导入
                                    val fetched = withContext(Dispatchers.IO) {
                                        runCatching { ScriptStorage.fetchUrlScript(url) }
                                    }
                                    val f = fetched.getOrNull()
                                    if (f != null) {
                                        pendingUrlScript = f
                                    } else {
                                        AppNotice.toast("脚本导入失败", AppNotice.Kind.FAILURE)
                                    }
                                }
                            },
                            onImportFile = { pickScriptLauncher.launch("*/*") },
                            onScriptMove = { id, delta ->
                                val mm = scripts.toMutableList()
                                scripts = ScriptStorage.move(mm, id, delta)
                                ScriptStorage.saveScripts(sprefs, scripts)
                                persistBundle()
                            },
                            onClearDownloadHistory = { removeFiles ->
                                scope.launch {
                                    val (cleared, latest) = withContext(Dispatchers.IO) {
                                        val succeeded = try {
                                            DownloadScheduler.clearDownloads(context, removeFiles)
                                        } catch (_: Exception) {
                                            false
                                        }
                                        succeeded to localData.downloads()
                                    }
                                    downloads = latest
                                    val message = when {
                                        !cleared -> "清空未完成，请稍后重试"
                                        removeFiles -> "已清除历史并删除歌曲文件"
                                        else -> "已清除下载历史（保留文件）"
                                    }
                                    AppNotice.toast(message, if (cleared) AppNotice.Kind.SUCCESS else AppNotice.Kind.FAILURE)
                                }
                            },
                            onRedownload = { d -> redownloadDownloads(listOf(d)) },
                            onRedownloads = { items -> redownloadDownloads(items) },
                            themeMode = themeMode,
                            onThemeMode = { mode ->
                                themeMode = normalizeThemeMode(mode)
                                prefs.edit().putString("theme_mode", themeMode).apply()
                            },
                            globalFontPref = globalFontPref,
                            lyricFontPref = lyricFontPref,
                            onFontFamily = { key, value ->
                                if (key == "global") globalFontPref = value else lyricFontPref = value
                                prefs.edit().putString("global_font_family", globalFontPref).putString("lyric_font_family", lyricFontPref).apply()
                            },
                            onAppearanceSetting = { key, value ->
                                when (key) {
                                    "menu" -> menuOpacity = value.coerceIn(.2f, 1f)
                                    "menuWhite" -> menuIconWhite = value > 0.5f
                                    "navstyle" -> bottomNavStyle = if (value > 0.5f) "capsule" else "default"
                                    "ministyle" -> miniPlayerStyle = if (value > 0.5f) "capsule" else "default"
                                }
                                prefs.edit()
                                    .putFloat("menu_opacity", menuOpacity)
                                    .putBoolean("menu_icon_white", menuIconWhite)
                                    .putString("bottom_nav_style", bottomNavStyle)
                                    .putString("mini_player_style", miniPlayerStyle)
                                    .putString(DockStylePrefs.KEY, bottomNavStyle)
                                    .apply()
                            },
                            allowMix = allowMix,
                            cacheInfo = cacheInfo,
                            onAllowMix = {
                                allowMix = it
                                prefs.edit().putBoolean("allow_mix", it).apply()
                                PlayerService.setAllowMix(it)
                            },
                            onClearCache = {
                                scope.launch {
                                    try {
                                        val clearResult = withContext(Dispatchers.IO) { CacheManager.clearAll(context) }
                                        val used = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
                                        cacheInfo = "${CacheManager.format(used)} / ${CacheManager.formatLimit(cacheMax)} · ${CacheManager.diagnostics(context)}"
                                        val skippedDetails = listOfNotNull(
                                            if (clearResult.skippedActiveFiles > 0) "跳过 ${clearResult.skippedActiveFiles} 个正在使用或生成中的媒体文件" else null,
                                            if (clearResult.skippedActiveMediaResources > 0) "跳过 ${clearResult.skippedActiveMediaResources} 个正在读取的 Media3 缓存资源" else null
                                        ).joinToString("；")
                                        val message = if (skippedDetails.isNotEmpty()) {
                                            "已清理其他缓存；$skippedDetails，播放未中断"
                                        } else {
                                            "已清空播放、临时和歌词缓存（封面已保留）"
                                        }
                                        AppNotice.toast(message, AppNotice.Kind.SUCCESS)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        AppNotice.toast("缓存清理失败，请稍后重试", AppNotice.Kind.FAILURE)
                                    }
                                }
                            },
                            onCacheMax = { bytes ->
                                cacheMax = bytes
                                prefs.edit().putLong("cache_max_bytes", bytes).apply()
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        CacheManager.setMaxBytes(context, bytes)
                                    }
                                    val used = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
                                    cacheInfo = "${CacheManager.format(used)} / ${CacheManager.formatLimit(bytes)} · ${CacheManager.diagnostics(context)}"
                                }
                            },
                            onCheckUpdate = {
                                scope.launch {
                                    status = "正在检查 GitHub Releases…"
                                    status = withContext(Dispatchers.IO) { GitHubReleases.check(BuildConfig.VERSION_NAME) }
                                    // A 波追加（用户裁定）：更新检查结果不再写入消息中心，仅关于页内文案 + 一句轻提示反馈。
                                    AppNotice.toast(status)
                                }
                            },
                            crossPlatformSearchUi = crossPlatformSearch,
                            onCrossPlatformSearch = {
                                crossPlatformSearch = it
                                prefs.edit().putBoolean("cross_search", it).apply()
                            },
                            onProbe = { sc, cb ->
                                ScriptProbeStartupPolicy.noteManual(sprefs)
                                // 后台真实检测（不阻塞 UI）；默认服务已自动配置。
                                scope.launch {
                                    // 完整检测：脚本声明的全部平台 × 每平台全部固定样本 × 声明的全部音质（未声明时 5 平台 × 7 档）。
                                    val isolatedRuntime = scriptRuntime.forkForProbe()
                                    val report = try {
                                        withContext(Dispatchers.IO) {
                                            UrlResolver.withProbeLane { gate ->
                                                UrlResolver.probeScriptScored(
                                                    sc,
                                                    isolatedRuntime,
                                                    maxPlats = 5,
                                                    preferQ = preferQ,
                                                    yieldGate = gate
                                                ).report
                                            }
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        // ACCEPT5：播放抢占是探测通道的设计内取消，此前直接重抛导致
                                        // cb 永不调用、脚本行永久卡在「检测中…」且不落任何状态。
                                        // 作用域仍活跃（只是被抢占）时落定论记录并回告页面；
                                        // 作用域整体销毁时静默重抛收尾。
                                        if (isActive) {
                                            ScriptProbeCache.saveProbeSkipped(sc, "检测被播放请求抢占，未执行完")
                                            cb("检测被播放请求抢占，已中止；可重新检测")
                                        }
                                        throw e
                                    } catch (e: Exception) {
                                        ScriptProbeCache.saveProbeSkipped(sc, "检测异常中断（${e.javaClass.simpleName}），本轮未形成结论")
                                        "检测失败，未完成完整矩阵；请重试"
                                    }
                                    finally {
                                        isolatedRuntime.cancelSpeculativeUrlRequests()
                                        withContext(NonCancellable) { isolatedRuntime.closeAndWait() }
                                    }
                                    cb(report)
                                }
                            },
                            onProbeAll = { progress, done ->
                                ScriptProbeStartupPolicy.noteManual(sprefs)
                                if (batchProbeActive) {
                                    done("已有批量检测正在后台运行，请等待当前结果")
                                } else {
                                batchProbeActive = true
                                scope.launch {
                                    try {
                                    val targets = scripts.filter { it.enabled && it.code.isNotBlank() }
                                    if (targets.isEmpty()) {
                                        done("没有已启用且包含代码的脚本")
                                    } else {
                                        val reports = ScriptProbeBatchRunner.runFull(targets, scriptRuntime, preferQ, progress)
                                        val summary = "批量检测完成：${targets.size} 个脚本\n\n" + reports.joinToString("\n\n")
                                        // A 波追加：完成卡只发这一张。原先同时写 pending_script_probe_done 会让下次启动再补发一张重复卡，已去除；
                                        // pending 通道只留给定时/后台检测（ScriptProbeScheduler，应用不在前台时完成）使用。
                                        // ACCEPT5 静默类：批量检测完成只进通知中心（WAVE-ACCEPT5-NOTICE A.3）。
                                        AppNotice.inbox(AppNotice.Category.SCRIPT, "批量检测完成：${targets.size} 个脚本")
                                        done(summary)
                                    }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        // ACCEPT5：done 在任何路径下都必须回告一次，否则批量抽屉
                                        // 永久转圈、页面以为检测仍在进行。运行器内部已保证被抢占/
                                        // 中断的脚本逐个落定论，这里回告后照常向上抛收尾。
                                        done("批量检测被中断，未全部完成；各脚本状态已更新（完成/跳过均有定论），可稍后重试")
                                        throw e
                                    } catch (e: Exception) {
                                        done("批量检测异常中断（${e.javaClass.simpleName}）；已处理脚本的状态已更新，可稍后重试")
                                    } finally {
                                        batchProbeActive = false
                                    }
                                }
                                }
                            },
                            onSmartScriptSort = { mode ->
                                val sorted = ScriptProbeStartupPolicy.sortWithVerifiedReports(scripts, mode, ScriptProbeCache::fullReport) { sc, m ->
                                    ScriptProbeCache.sortValue(sc, m)
                                }
                                if (sorted == null) {
                                    AppNotice.toast("没有有效的完整检测结果", AppNotice.Kind.FAILURE)
                                } else {
                                // 排序只消费上次完整真链检测缓存；刷新按钮才触发重测。
                                scripts = sorted
                                ScriptStorage.saveScripts(sprefs, sorted)
                                SmartSourceStore.syncScripts(sorted)
                                sorted.firstOrNull { it.enabled && it.code.isNotBlank() }?.let { SmartSourceStore.setActiveScriptId(it.id) }
                                scriptRuntime.invalidate()
                                persistBundle()
                                AppNotice.toast("已按${mode.label}排序（沿用上次检测结果；刷新才重测）", AppNotice.Kind.SUCCESS)
                                }
                            },
                            prefetchEnabled = prefetchEnabled,
                            prefetchIntervalSec = prefetchIntervalSec,
                            prefetchCount = prefetchCount,
                            onPrefetch = {
                                prefetchEnabled = it
                                prefs.edit().putBoolean("prefetch_next", it).apply()
                            },
                            onPrefetchInterval = { prefetchIntervalSec = it; prefs.edit().putInt("prefetch_interval_sec", it).apply() },
                            onPrefetchCount = { prefetchCount = it.coerceIn(1, 5); prefs.edit().putInt("prefetch_count", prefetchCount).apply() },
                            lyricGapDp = lyricGapDp,
                            lyricFontSp = lyricFontSp,
                            lyricBlurDp = lyricBlurDp,
                            lyricShowTranslation = lyricShowTranslation,
                            lyricShowRomanization = lyricShowRomanization,
                            lyricHighlightHex = lyricHighlightHex,
                            lyricUnhighlightHex = lyricUnhighlightHex,
                            lyricWordKaraoke = lyricWordKaraoke,
                            onLyricWordKaraoke = { lyricWordKaraoke = it; prefs.edit().putBoolean("lyric_word_karaoke", it).apply() },
                            lyricFloatEnabled = lyricFloatEnabled,
                            onLyricFloat = { on ->
                                lyricFloatEnabled = on
                                if (on) {
                                    if (!LyricFloat.hasPermission(context)) {
                                        try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) } catch (_: Exception) {}
                                        AppNotice.banner("请先开启悬浮窗权限", actionLabel = "去开启", onAction = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) } catch (_: Exception) {} }, onClick = { try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) } catch (_: Exception) {} })
                                        lyricFloatEnabled = false
                                    } else {
                                        val cur = lyrics.getOrNull(lyricIdx)?.text.orEmpty()
                                        val nxt = lyrics.getOrNull(lyricIdx + 1)?.text.orEmpty()
                                        LyricFloat.toggleEnabled(context, true, cur.ifBlank { currentSong?.name ?: "♪ SoundWave" }, nxt)
                                        AppNotice.toast("已开：切到其它应用后显示悬浮歌词", AppNotice.Kind.SUCCESS)
                                    }
                                } else {
                                    LyricFloat.toggleEnabled(context, false)
                                }
                            },
                            onLyricShowTranslation = { lyricShowTranslation = it; prefs.edit().putBoolean("lyric_show_translation", it).apply() },
                            onLyricShowRomanization = { lyricShowRomanization = it; prefs.edit().putBoolean("lyric_show_romanization", it).apply() },
                            onLyricHighlight = { lyricHighlightHex = it; prefs.edit().putString("lyric_highlight_color", it).apply() },
                            onLyricUnhighlight = { lyricUnhighlightHex = it; prefs.edit().putString("lyric_unhighlight_color", it).apply() },
                            onLyricGap = { lyricGapDp = it; prefs.edit().putInt("lyric_gap_dp", it).apply() },
                            onLyricFont = { lyricFontSp = it; prefs.edit().putInt("lyric_font_sp", it).apply() },
                            onLyricBlur = { lyricBlurDp = it; prefs.edit().putInt("lyric_blur_dp", it).apply() },
                            lyricOffsetMs = lyricOffsetMs,
                            onLyricOffset = { lyricOffsetMs = it.coerceIn(-30_000, 30_000); prefs.edit().putInt("lyric_offset_ms", lyricOffsetMs).apply() },
                            downloadMultiThread = downloadMultiThread,
                            saveExtLyric = saveExtLyric,
                            saveExtCover = saveExtCover,
                            onDownloadMultiThread = { downloadMultiThread = it; prefs.edit().putBoolean("download_mt", it).apply() },
                            onSaveExtLyric = { saveExtLyric = it; prefs.edit().putBoolean("save_ext_lyric", it).apply() },
                            onSaveExtCover = { saveExtCover = it; prefs.edit().putBoolean("save_ext_cover", it).apply() },
                            playerLayout = playerLayout,
                            onPlayerLayout = { raw ->
                                val normalized = selectablePlayerLayout(raw)
                                playerLayout = normalized
                                prefs.edit().putString("player_layout", normalized).apply()
                            },
                            detailCoverStyle = detailCoverStyle,
                            onDetailCoverStyle = { rawStyle ->
                                detailCoverStyle = DetailCoverStyles.normalize(rawStyle)
                                prefs.edit().putString("detail_cover_style", detailCoverStyle).remove("detail_immersive_cover").remove("lyric_s_wave").apply()
                            },
                            menuIconWhite = menuIconWhite,
                            autoPlayOnStart = autoPlayOnStart,
                            onAutoPlay = { autoPlayOnStart = it; prefs.edit().putBoolean("auto_play_on_start", it).apply() },
                            historySongs = history,
                            onPlayHistorySong = { s -> scope.launch { resolveAndPlay(s, history.ifEmpty { listOf(s) }, 0) } },
                            onDeleteHistory = { s ->
                                account.deleteHistoryRecords(listOf(s))
                                localData.removeHistory(s)
                                history = localData.history()
                            },
                            onDeleteHistoryBatch = { songs, removeFiles ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        songs.forEach { s ->
                                            localData.removeHistory(s)
                                            if (removeFiles) {
                                                try {
                                                    val dl = localData.downloads().firstOrNull { it.song.key() == s.key() && it.status == "done" && it.path.isNotEmpty() }
                                                    if (dl != null) {
                                                        DownloadHelper.deleteArtifact(context, dl)
                                                        LocalStore.removeDownload(context, dl)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    account.deleteHistoryRecords(songs)
                                    history = localData.history()
                                    downloads = localData.downloads()
                                }
                            },
                            onClearHistory = {
                                localData.clearHistory()
                                history = emptyList()
                            },
                            onBackupRestored = { restored ->
                                // BackupStore 已落盘；这里必须把所有运行中 Compose 状态同步刷新，不能只刷新用户/脚本。
                                userBundle = restored
                                if (restored.avatarB64.isNotBlank()) {
                                    if (user.isNotBlank()) {
                                        if (AvatarStore.clearRetiredUsername(context, user)) {
                                            AvatarStore.accountSpecificPath(context, user).takeIf { it.isNotBlank() }?.let {
                                                account.acceptExplicitLocalAvatar(user, it)
                                            }
                                        } else {
                                            AppNotice.inbox(AppNotice.Category.ACCOUNT, "备份头像已保留", "本机无法安全恢复其资料关联，头像不会显示。", alsoBanner = true)
                                        }
                                    }
                                }
                                history = localData.history()
                                downloads = localData.downloads()
                                scripts = ScriptStorage.loadScripts(sprefs).map { it.copy(builtin = false) }
                                preferQ = readUserQuality("play_br")
                                downloadQ = readUserQuality("down_br")
                                prefetchEnabled = prefs.getBoolean("prefetch_next", true)
                                prefetchIntervalSec = prefs.getInt("prefetch_interval_sec", 6).coerceIn(1, 30)
                                prefetchCount = prefs.getInt("prefetch_count", 2).coerceIn(1, 5)
                                migrateLegacyImmersiveLyricPrefs(prefs)
                                lyricGapDp = PlayerLyricsSettingsModel.snapLineHeight(prefs.getInt("lyric_gap_dp", 9))
                                lyricFontSp = PlayerLyricsSettingsModel.snapFont(prefs.getInt("lyric_font_sp", 18))
                                lyricBold = prefs.getBoolean("lyric_bold", true)
                                lyricBlurDp = PlayerLyricsSettingsModel.DEFAULT_DISTANCE_BLUR_DP
                                lyricPriority = prefs.getString("lyric_priority", "external") ?: "external"
                                val restoredLyricDisplayPreferences = resolveLyricDisplayPreferences(
                                    storedTranslation = prefs.getBoolean("lyric_show_translation", false).takeIf { prefs.contains("lyric_show_translation") },
                                    storedRomanization = prefs.getBoolean("lyric_show_romanization", false).takeIf { prefs.contains("lyric_show_romanization") },
                                )
                                lyricShowTranslation = restoredLyricDisplayPreferences.translation
                                lyricShowRomanization = restoredLyricDisplayPreferences.romanization
                                lyricHighlightHex = prefs.getString("lyric_highlight_color", "#FFFFFF") ?: "#FFFFFF"
                                lyricUnhighlightHex = prefs.getString("lyric_unhighlight_color", "#A8A8B3") ?: "#A8A8B3"
                                lyricWordKaraoke = prefs.getBoolean("lyric_word_karaoke", true)
                                lyricOffsetMs = prefs.getInt("lyric_offset_ms", 0).coerceIn(-30_000, 30_000)
                                playerLayout = selectablePlayerLayout(prefs.getString("player_layout", "standard") ?: "standard")
                                detailCoverStyle = DetailCoverStyles.normalize(
                                    prefs.getString("detail_cover_style", null)
                                        ?: if (prefs.getBoolean("detail_immersive_cover", false)) DetailCoverStyles.IMMERSIVE else DetailCoverStyles.DEFAULT
                                )
                                menuOpacity = prefs.getFloat("menu_opacity", .90f).coerceIn(.2f, 1f)
                                menuIconWhite = prefs.getBoolean("menu_icon_white", false)
                                autoPlayOnStart = prefs.getBoolean("auto_play_on_start", false)
                                allowMix = prefs.getBoolean("allow_mix", false); PlayerService.setAudioFocusSettings(xyz.cncnlp.soundwave.playback.AudioFocusSettingsStore.read(prefs))
                                downloadMultiThread = prefs.getBoolean("download_mt", true)
                                saveExtLyric = prefs.getBoolean("save_ext_lyric", true)
                                saveExtCover = prefs.getBoolean("save_ext_cover", true)
                                cacheMax = CacheManager.maxBytes(context)
                                PlayerService.reconfigureCache(context.applicationContext, cacheMax)
                                scriptRuntime.invalidate()
                                currentSong?.let { song -> scope.launch { lyrics = emptyList(); refreshLyrics(song) } }
                                AppNotice.inbox(AppNotice.Category.SYNC, "备份已恢复并立即生效", alsoToast = true)
                            },
                            onSmartAutoConfigure = {
                                scope.launch {
                                    val usableScripts = scripts.filter { it.code.isNotBlank() }
                                    if (usableScripts.isEmpty()) {
                                        AppNotice.toast("请先导入脚本", AppNotice.Kind.FAILURE)
                                        return@launch
                                    }
                                    // One-click configuration is intentionally read-only: never re-probe here.
                                    val reports = usableScripts.mapNotNull(ScriptProbeCache::fullReport)
                                    if (reports.isEmpty()) {
                                        AppNotice.toast("没有可用的完整检测报告，请先执行完整检测；现有设置保持不变", AppNotice.Kind.FAILURE)
                                        return@launch
                                    }
                                    val currentPlatforms = SmartSourceStore.platformOrder()
                                    val plan = ScriptProbeCache.buildAutoConfigurationPlan(
                                        usableScripts,
                                        reports,
                                        currentPlatforms.map { it.code }
                                    )
                                    if (plan.reportCount == 0) {
                                        AppNotice.toast("没有可用的完整检测报告，请先执行完整检测；现有设置保持不变", AppNotice.Kind.FAILURE)
                                        return@launch
                                    }
                                    if (!plan.hasVerifiedSuccess) {
                                        AppNotice.toast("完整检测报告中没有已验证成功的链路，现有设置保持不变", AppNotice.Kind.FAILURE)
                                        return@launch
                                    }

                                    SmartSourceStore.setSmartEnabled(true)
                                    SmartSourceStore.setFallback128k(true)
                                    SmartSourceStore.setRememberCrossPlat(true)
                                    SmartSourceStore.savePlatformOrder(
                                        plan.platformOrder.map { platform ->
                                            SmartSourceStore.PlatItem(
                                                platform,
                                                enabled = currentPlatforms.firstOrNull { it.code == platform }?.enabled ?: true
                                            )
                                        }
                                    )
                                    plan.platformScriptOrders.forEach { (platform, order) ->
                                        SmartSourceStore.savePlatformScriptOrder(platform, order)
                                    }
                                    plan.platformQualities.forEach { (platform, quality) ->
                                        // Persist only the requested quality tier that passed full media validation;
                                        // this is capability evidence, not a measured or invented bitrate.
                                        SmartSourceStore.setPlatformPlayQuality(platform, quality)
                                        SmartSourceStore.setPlatformDownloadQuality(platform, quality)
                                    }

                                    val scriptsById = usableScripts.associateBy { it.id }
                                    val orderedUsable = plan.globalScriptOrder.mapNotNull(scriptsById::get)
                                    scripts = orderedUsable + scripts.filter { it.code.isBlank() }
                                    ScriptStorage.saveScripts(sprefs, scripts)
                                    SmartSourceStore.syncScripts(scripts)
                                    val verifiedScriptIds = reports.filter { it.valid.isNotEmpty() }.map { it.scriptId }.toSet()
                                    scripts.firstOrNull { it.enabled && it.id in verifiedScriptIds }
                                        ?.let { SmartSourceStore.setActiveScriptId(it.id) }
                                    scriptRuntime.invalidate()
                                    persistBundle()
                                    AppNotice.toast("已按最近一次完整检测配置", AppNotice.Kind.SUCCESS)
                                }
                            }
                        )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootFullPlayerLayer() {
        // ACCEPT6 dialogfrost：顶层覆盖标志——播放器目标态为开时，表磨砂采样切到顶层源。
        androidx.compose.runtime.SideEffect {
            xyz.cncnlp.soundwave.ui.common.GlassHost.topCovering = fullPlayerVisibilityState.targetState
        }
        // Subscribe here: the animated layer can recompose independently of AppRoot.
        val playerSong by playback.currentSongState
        androidx.compose.animation.AnimatedVisibility(
            modifier = Modifier.fillMaxSize().graphicsLayer { clip = true }.zIndex(3f),
            visibleState = fullPlayerVisibilityState,
            enter = androidx.compose.animation.slideInVertically(
                animationSpec = tween(PLAYER_SHEET_ENTER_DURATION_MS, easing = FastOutSlowInEasing)
            ) { fullHeight -> xyz.cncnlp.soundwave.ui.player.playerSheetEnterStartOffsetPx(fullHeight, playerDismissOffsetHolder.latestOffsetPx) },
            // SW-12-06：退场时长不得大于进场——可见退场用进场时长 360ms；G 波已把 PLAYER_SHEET_EXIT_DURATION_MS 常量本身由 420 收敛到 360（宿主卸载延迟 = 常量 + 200ms 尾差）。
            exit = androidx.compose.animation.slideOutVertically(
                animationSpec = tween(PLAYER_SHEET_ENTER_DURATION_MS, easing = FastOutSlowInEasing)
            ) { fullHeight -> playerDismissExitOffsetPx(fullHeight, playerDismissOffsetHolder.latestOffsetPx) },
            label = "playerSheetTransition"
        ) {
            SoundwaveDesignTheme(isDark = isDark) {
            FullPlayer(
                song = playerSong, queueSnapshot = queue, queueCurrentIndexState = playback.queueCurrentIndexState, playing = playing, progressState = progressState,
                duration = duration, currentTimeState = currentTimeState, statusState = playback.statusState, resolvingState = playback.resolvingState,
                lyrics = lyrics, lyricIdx = lyricIdx, showLyrics = showLyrics,
                liked = liked, playMode = playMode, sleepRemain = sleepRemain,
                playbackAudioFormat = playback.playbackAudioFormatState.value,
                lyricGapDp = lyricGapDp,
                lyricFontSp = lyricFontSp,
                lyricBold = lyricBold,
                lyricBlurDp = lyricBlurDp,
                lyricShowTranslation = lyricShowTranslation,
                lyricShowRomanization = lyricShowRomanization,
                lyricHighlightHex = lyricHighlightHex,
                lyricUnhighlightHex = lyricUnhighlightHex,
                lyricWordKaraoke = lyricWordKaraoke,
                onLyricWordKaraoke = { lyricWordKaraoke = it; prefs.edit().putBoolean("lyric_word_karaoke", it).apply() },
                onLyricShowTranslation = { lyricShowTranslation = it; prefs.edit().putBoolean("lyric_show_translation", it).apply() },
                onLyricShowRomanization = { lyricShowRomanization = it; prefs.edit().putBoolean("lyric_show_romanization", it).apply() },
                onLyricHighlight = { lyricHighlightHex = it; prefs.edit().putString("lyric_highlight_color", it).apply() },
                onLyricUnhighlight = { lyricUnhighlightHex = it; prefs.edit().putString("lyric_unhighlight_color", it).apply() },
                lyricOffsetMs = lyricOffsetMs,
                onLyricOffset = { value ->
                    lyricOffsetMs = value.coerceIn(-30_000, 30_000)
                    prefs.edit().putInt("lyric_offset_ms", lyricOffsetMs).apply()
                },
                playerLayout = playerLayout,
                dismissOffsetHolder = playerDismissOffsetHolder,
                onDismissGestureStatusBarExposure = { playerDismissStatusBarExposed = it },
                onPlayerLayout = { raw ->
                    val normalized = selectablePlayerLayout(raw)
                    playerLayout = normalized
                    prefs.edit().putString("player_layout", normalized).apply()
                },
                onLyricGap = { lyricGapDp = it; prefs.edit().putInt("lyric_gap_dp", it).apply() },
                onLyricFont = { lyricFontSp = it; prefs.edit().putInt("lyric_font_sp", it).apply() },
                onLyricBlur = { lyricBlurDp = it; prefs.edit().putInt("lyric_blur_dp", it).apply() },
                // SW-07-10（G 波）：空歌词「重新获取」——对当前歌曲走既有 refreshLyrics（空结果不落缓存，重取即真取）。
                onRefetchLyrics = { currentSong?.let { s -> scope.launch { refreshLyrics(s) } } },
                onBack = { returnToTopLevelTab(prevTab) },
                onToggle = { PlayerService.toggle() },
                onNext = { scope.launch { playDelta(+1) } },
                onPrev = { scope.launch { playDelta(-1) } },
                onSeek = { p -> PlayerService.seekTo((p * duration * 1000).toLong()) },
                onToggleLyrics = { showLyrics = !showLyrics },
                onQuality = { showPlayerQuality = true },
                onLike = {
                    currentSong?.let { s ->
                        val likedNow = userBundle.toggleLike(s)
                        liked = likedNow
                        userBundle = userBundle.copy(
                            favs = userBundle.favs.toMutableList(),
                            playlists = userBundle.playlists.toMutableList(),
                            enabledScripts = userBundle.enabledScripts.toMutableList()
                        ).also { it.ensureDefaults() }
                        persistBundle()
                        // 播放队列同步：点赞即把新歌追加到当前队列尾（去重）
                        if (likedNow) {
                            val snap = PlayerService.snapshot()
                            val q = snap.queue.toMutableList()
                            if (q.isNotEmpty() && q.none { it.key() == s.key() }) {
                                q.add(s)
                                playback.replaceQueue(q, snap.queueIndex)
                            }
                        }
                    }
                },
                onMode = { playMode = PlayerService.cycleMode() },
                onSleep = { mins ->
                    PlayerService.setSleepMode(mins)
                    status = when {
                        mins == -1 -> "定时：播完当前"
                        mins > 0 -> "定时 ${mins} 分钟（到点后播完当前再停）"
                        else -> "已取消定时"
                    }
                },
                onDownload = {
                    val s = currentSong ?: return@FullPlayer
                    scope.launch {
                        AppNotice.toast("开始下载 ${s.name}", AppNotice.Kind.SUCCESS)
                        startDownload(s)
                    }
                },
                onQueueItem = { item, list, idx -> scope.launch { resolveAndPlay(item, list, idx) } },
                onQueueMove = { from, to -> playback.reorderQueue(from, to) },
                onQueueRemove = { index -> playback.removeQueueAt(index) }, onQueueInsert = { index, song -> playback.insertQueueAt(index, song) }, onClearQueue = { playback.clearQueueKeepCurrent() },
                onAddToPlaylist = { item -> addSongTarget = item; addSongBatch = listOf(item); showAddToPl = true },
                onAddNext = { item ->
                    playback.insertNext(item)
                    AppNotice.toast("已设为下一首", AppNotice.Kind.SUCCESS)
                },
                onSimilar = { item -> scope.launch { playSimilarSong(item) } },
                onHeartMode = { item -> playback.toggleHeartMode(PlayerService.snapshot().queue.ifEmpty { userBundle.favs.ifEmpty { listOf(item) } }) },
                onLyricCorrectionRefresh = { correctedSong ->
                    scope.launch { refreshLyrics(correctedSong) }
                }
            )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun AppRootOverlayDialogs() {
                    cropAvatarUri?.let { uri ->
                        AvatarCropDialog(
                            uri = uri,
                            onCancel = { cropAvatarUri = null },
                            onConfirm = { bmp ->
                                val path = AvatarStore.saveFromBitmap(context, bmp, user)
                                // 收尾棒 J8 配对：cropAvatarUri 的清除归 onCancel（弹层关闭句柄退场后触发）；落盘与 toast 即时。
                                if (path.isNotEmpty()) {
                                    if (!account.acceptExplicitLocalAvatar(user, path)) avatarPath = path
                                    avatarNeedSync = true
                                    AppNotice.toast("头像已保存", AppNotice.Kind.SUCCESS)
                                } else AppNotice.toast("头像保存失败", AppNotice.Kind.FAILURE)
                            }
                        )
                    }
                    // 顶栏不显示运行状态文字
                    downloadPickSong?.let { pickSong ->
                        DownloadQualityDialog(
                            song = pickSong,
                            probes = downloadPickSizes,
                            selectedQuality = downloadPickQuality,
                            onQualityChange = { downloadPickQuality = it },
                            onConfirm = { quality -> confirmDownload(pickSong, quality) },
                            onDismiss = { downloadPickSong = null }
                        )
                    }
                    if (showProfileSheet) {
                        val profileSheetContext = androidx.compose.ui.platform.LocalContext.current
                        ProfileSheet(
                            userName = user,
                            avatarPath = avatarPath,
                            onDismiss = { showProfileSheet = false },
                            // ACCEPT5/J8：动作回调只执行动作、不再同步清 showProfileSheet——清状态由弹层
                            // 退场句柄在退场走完后经 onDismiss 触发，否则退场动画被同步移除掐断。
                            onChangeAvatar = {
                                pickAvatarLauncher.launch("image/*")
                            },
                            onRenameUser = {
                                profileRenameStatus = ""
                                showProfileRenameSheet = true
                            },
                            onEditWebDavCredentials = {
                                webDavSheetEntry = xyz.cncnlp.soundwave.ui.settings.WebDavSheetEntry.FORM
                                webDavReturnToProfile = true
                                showWebDavSyncSheet = true
                            },
                            onSyncWebDav = { webDavSyncRequestKey += 1 },
                            webDavSyncBusy = webDavInlineBusy,
                            webDavSyncStatus = webDavInlineStatus,
                            webDavSyncStatusIsError = webDavInlineStatusIsError,
                            onLogoutWebDav = {
                                // 退出登录 = 同步面板「忘记登录信息」同一操作（forgetSavedWebDavCredentials）
                                // + 同口径先撤销自动同步会话授权（面板的 onBindingEdited）。
                                webDavAutoSync.revoke()
                                val forgotten = xyz.cncnlp.soundwave.ui.settings.forgetSavedWebDavCredentials(profileSheetContext)
                                if (forgotten) {
                                    AppNotice.toast("已退出登录 · 本机与云端数据未改动", AppNotice.Kind.SUCCESS)
                                } else {
                                    AppNotice.toast("退出登录失败，请重试", AppNotice.Kind.FAILURE)
                                }
                            }
                        )
                    }
                    if (showProfileSheet || showWebDavSyncSheet) {
                        // ACCEPT5 账号对话框编排：控制器单实例——账号中心打开期间以 Headless 挂载（行内
                        // 同步借道同一管线、冲突确认层可用、状态回喂账号中心状态面）；打开表单/面板时切
                        // Sheet 呈现。与设置-备份页共用同一 WebDavSyncOverlay / 凭据存储 / 传输管线。
                        xyz.cncnlp.soundwave.ui.settings.WebDavSyncOverlay(
                            prepareAvatarSync = { account.prepareWebDavAvatarSync() },
                            prepareProfileExtrasSync = { account.prepareWebDavProfileExtrasSync() },
                            prepareAccountDataSync = { account.prepareWebDavAccountDataSync() },
                            onManualStarted = { webDavAutoSync.manualStarted() },
                            onManualSucceeded = { webDavAutoSync.manualSucceeded(it) },
                            onBindingEdited = { webDavAutoSync.revoke() },
                            withWebDavAccountLock = { block -> account.withWebDavAccountLock { block() } },
                            localBundle = userBundle,
                            onBundleApplied = { account.applyWebDavBundleLocally(it) },
                            onDismiss = {
                                showWebDavSyncSheet = false
                                if (webDavReturnToProfile || webDavPendingProfile) {
                                    showProfileSheet = true
                                }
                                webDavReturnToProfile = false
                                webDavPendingProfile = false
                            },
                            onImportProfileName = importDiscoveredWebDavProfile,
                            entry = webDavSheetEntry,
                            presentation = if (showWebDavSyncSheet) {
                                xyz.cncnlp.soundwave.ui.settings.WebDavPresentation.Sheet
                            } else {
                                xyz.cncnlp.soundwave.ui.settings.WebDavPresentation.Headless
                            },
                            syncRequestKey = webDavSyncRequestKey,
                            onStatusChanged = { busy, statusText, isError ->
                                webDavInlineBusy = busy
                                webDavInlineStatus = statusText
                                webDavInlineStatusIsError = isError
                            },
                            onLoginCompleted = { webDavPendingProfile = true },
                            onCredentialsSaved = { webDavPendingProfile = true },
                            autoSyncEnabled = webDavAutoSync.state.enabled,
                            onAutoSyncEnabledChange = { enabled ->
                                if (!webDavAutoSync.setEnabled(enabled)) {
                                    AppNotice.inbox(AppNotice.Category.SYNC, "自动同步授权未能持久保存", alsoBanner = true)
                                }
                            }
                        )
                    }
                    if (showProfileRenameSheet) {
                        LocalProfileRenameSheet(
                            closeRequested = profileRenameCloseRequested,
                            currentUser = user,
                            busy = profileRenameBusy,
                            status = profileRenameStatus,
                            onSubmit = { requestedNewName ->
                                val nameError = localProfileNameError(requestedNewName)
                                when {
                                    nameError != null -> profileRenameStatus = nameError
                                    requestedNewName.trim() == user -> profileRenameStatus = "新用户名与当前用户名相同"
                                    else -> {
                                        profileRenameBusy = true
                                        profileRenameStatus = ""
                                        scope.launch {
                                            account.withLocalIdentityTransition {
                                                account.invalidatePendingLocalIdentityChange()
                                                val oldUser = user
                                                val newName = requestedNewName.trim()
                                                val verifiedAvatarPath = account.verifiedAvatarPathForLocalRename(oldUser)
                                                val avatarCopy = withContext(Dispatchers.IO) {
                                                    AvatarStore.copyAccountAvatarForRename(context, oldUser, newName, verifiedAvatarPath)
                                                }
                                                if (verifiedAvatarPath.isNotBlank() && !avatarCopy.protectionsPersisted) {
                                                    profileRenameBusy = false
                                                    profileRenameStatus = "本机未能持久化头像隔离标记，改名未执行；资料保持不变。"
                                                    return@withLocalIdentityTransition
                                                }
                                                val applied = account.completeSuccessfulLocalRename(oldUser, newName, avatarCopy.avatarPath)
                                                profileRenameBusy = false
                                                if (applied) {
                                                    prefs.edit().putString("user", newName).commit()
                                                    profileRenameCloseRequested = true
                                                    AppNotice.toast("用户名已更新", AppNotice.Kind.SUCCESS)
                                                } else {
                                                    profileRenameStatus = "改名未完成，本机资料保持不变。"
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            onDismiss = { if (!profileRenameBusy) { showProfileRenameSheet = false; profileRenameCloseRequested = false } }
                        )
                    }
            if (showPlayerQuality) {
                // r656：播放器音质弹窗改为统一设计抽屉（珊瑚眉题 + 2×3 档位格）。
                xyz.cncnlp.soundwave.ui.player.PlayerQualitySheet(
                    currentSong,
                    preferQ, actualLabel = currentSong?.actualQuality.orEmpty(),
                    onPick = {
                        preferQ = normalizeUserSelectableQuality(it)
                        prefs.edit().putString("play_br", preferQ).apply()
                        // ACCEPT4（§9）：关闭由内容侧退场句柄承担，onPick 不再同步清 showPlayerQuality。
                        // 切音质后清缓存重取
                        currentSong?.let { s ->
                            UrlResolver.clearSongCache(s)
                            scope.launch { resolveAndPlay(s, queue.ifEmpty { listOf(s) }) }
                        }
                    },
                    onDismiss = { showPlayerQuality = false }
                )
            }
                }

    // The source route stays visible beneath the animated player. Only FullPlayer's
    // moving surface owns immersive artwork; a root-level player-color plate would
    // replace transparent source-page areas with black during entry and exit.
    Box(Modifier.fillMaxSize().background(bg)) {
    xyz.cncnlp.soundwave.ui.motion.SharedCoverOverlayRoot(activeKey = plDetail?.id) {
    Scaffold(
        modifier = Modifier.drawPlayerUnderlayDuringTransition(
            playerLayout,
            fullPlayerVisibilityState.currentState,
            fullPlayerVisibilityState.targetState,
            playerDismissOffsetHolder
        ),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // The same text metrics size the visible mini, the stable transition host and page inset.
            val miniDockPresentation = rememberMiniPlayerPresentation(miniPlayerStyle)
            val adaptiveDockHostHeight = miniPlayerDockHostHeight(miniDockPresentation)
            val playerOverlayTransitionActive = playerOverlayState.sheetVisible
            if (playerOverlayTransitionActive) {
                // 仅替换内容，保留148dp测量高度；全屏播放器独立叠加，Scaffold不会因进退场改变viewport。
                Spacer(Modifier.fillMaxWidth().height(adaptiveDockHostHeight))
            } else {
            // 详情进退场期间固定为迷你条停靠样式。
            val detailTransitionActive = plDetail != null
            val detailDockOnlyMini = detailTransitionActive && tab != Tab.PLAYER
            // 固定宿主高度：进播放器/切 tab 不改变内容区高度，避免上下晃
            val dockIme = WindowInsets.ime.getBottom(LocalDensity.current)
            val dockImeDp = with(LocalDensity.current) { dockIme.toDp() }
            // 搜索聚焦时随键盘上抬；新版搜索框由页面自身承载，聚焦时收起底栏宿主。
            val dockLift = if (tab == Tab.SEARCH && searchDockFocus) dockImeDp else 0.dp
            val searchOverlayOpen = tab == Tab.SEARCH && searchDockFocus
            val dockHostHeight = if (searchOverlayOpen) 0.dp else adaptiveDockHostHeight
            SoundwaveDesignTheme(isDark = isDark) {
            // 详情页多选态：隐藏迷你条与底栏，由详情页底部批量操作条（自带系统导航栏避让）占位。
            if (((detailSelectionActive && plDetail != null) || (homeSelectionActive && plDetail == null && tab == Tab.HOME)) && tab != Tab.PLAYER) return@SoundwaveDesignTheme
            // 设计稿：底栏贴底（背景延伸到系统导航栏下方），宿主高度 = Dock 高 + 系统导航栏 inset。
            val dockNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navShown = !(tab == Tab.SEARCH && searchDockFocus) && !detailDockOnlyMini && (settingsSub == null || tab == Tab.PLAYER)
            // r658f（用户 10-20：默认底栏开磨砂后底栏看起来变高）：不再把间隙并入底栏玻璃（玻璃顶边被抬到迷你条下沿，
            // 两侧露出一截硬边，底栏显高 14dp）；间隙改由 DockMiniGap 软渐变承接，与磨砂关时视觉高度一致。
            val mergedDockGap = false && bottomNavStyle != "capsule" && xyz.cncnlp.soundwave.ui.common.FrostedPrefs.dockFrosted && navShown &&
                currentSong != null && !searchOverlayOpen && tab != Tab.PLAYER
            Box(Modifier.fillMaxWidth().height(if (searchOverlayOpen) 0.dp else dockHostHeight + dockNavInset).offset(y = -dockLift)) {
            // accept6-bottomfrost：悬浮簇背后的统一磨砂带（间距 + 底栏 + 胶囊下边距同带，带顶与迷你条下沿齐平）；簇的高度/偏移/子项几何零改动，带只铺在其背后。搜索键盘态宿主高为 0、详情迁移仅迷你条态不挂带。
            if (!searchOverlayOpen && !detailDockOnlyMini) xyz.cncnlp.soundwave.ui.common.DockFrostBand(
                Modifier.fillMaxWidth().height(xyz.cncnlp.soundwave.ui.common.dockFrostBandHeight(bottomNavStyle == "capsule", dockNavInset)).align(Alignment.BottomStart),
                soundwaveDesignScheme(isDark).surface, isDark
            )
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    if (detailDockOnlyMini && currentSong != null) {
                        // 与既有“仅迷你条”模式共用系统导航安全区及底栏底距，确保条体落在导航手势区上方。
                        DetailTransitionMiniDock(
                            song = currentSong!!,
                            playing = playing,
                            progressState = progressState,
                            resolvingState = playback.resolvingState,
                            lyricLine = lyrics.getOrNull(lyricIdx)?.text.orEmpty(),
                            style = miniPlayerStyle,
                            // 与 openFullPlayer() 完全相同的进入事务，此处内联展开：详情迷你条
                            // 点击只能进入既有播放器（协调入口状态 + 切 Tab），不得重新解析/重启播放。
                            onOpenPlayer = {
                                playback.reconcileForPlayerEntry()
                                val entry = playerEntryState(tab)
                                prevTab = entry.previousTab
                                showLyrics = entry.showLyrics
                                tab = Tab.PLAYER
                            },
                            onToggle = { PlayerService.toggle() },
                            onNext = { scope.launch { playback.playDeltaRef[0](+1) } },
                            // 详情态钉住停靠样式，避免离开详情后覆盖先前 Dock 状态。
                            onSwipeUp = { },
                            onSwipeDown = { }
                        )
                    }
                    if (currentSong != null && !searchOverlayOpen && !detailDockOnlyMini && tab != Tab.PLAYER) {
                        MiniPlayer(
                            song = currentSong!!,
                            playing = playing,
                            progressState = progressState,
                            resolvingState = playback.resolvingState,
                            lyricLine = lyrics.getOrNull(lyricIdx)?.text.orEmpty(),
                            compact = false,
                            style = miniPlayerStyle,
                            onClick = { openFullPlayer() },
                            onToggle = { PlayerService.toggle() },
                            onNext = { scope.launch { playback.playDeltaRef[0](+1) } },
                            onSwipeUp = { openFullPlayer() },
                            onSwipeDown = { },
                            onExpandDrag = { upPx, screenPx -> if (upPx > 0f) playerDismissOffsetHolder.update(screenPx - upPx) else playerDismissOffsetHolder.reset() }
                        )
                        // 迷你条直接位于底栏上方（不重叠）：默认底栏间距 14dp，胶囊底栏自带 9dp 上内距故取 8dp。
                        // r658d：通栏 + 磨砂 + 底栏可见时，间隙由底栏玻璃向上延伸覆盖（同一块模糊，不再两层）。
                        if (!mergedDockGap) xyz.cncnlp.soundwave.ui.common.DockMiniGap(bottomNavStyle == "capsule")
                    }
                    // r658b #17（用户 10-19）：移除迷你条上滑收缩 / 底栏隐藏形态，底栏与迷你条始终完整显示。
                    if (tab == Tab.SEARCH && searchDockFocus && !detailDockOnlyMini) {
                        Spacer(Modifier.fillMaxWidth().height(0.dp))
                    } else if (!detailDockOnlyMini) {
                        Surface(color = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                            if (settingsSub == null || tab == Tab.PLAYER) BottomNav(if (tab == Tab.PLAYER) lastContentTab else tab, primary, isDark, themeMode = themeMode, style = bottomNavStyle,
                                glassTopExtend = if (mergedDockGap) xyz.cncnlp.soundwave.ui.common.DockMetrics.MiniGap else 0.dp) { nxt ->
                                navigationEvents.selectMainTab(nxt)
                            }
                        }
                    }
            }
            }
            }
            }
        }
    ) { pad ->
        // 统一内容底部避让：底栏 + 迷你条（可见时）+ 系统导航栏 + 16dp，经 LocalDockContentInset 下发给各页面。
        val dockContentInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            (if (bottomNavStyle == "capsule") DockMetrics.CapsuleNavHeight else DockMetrics.BarNavHeight) +
            (if (currentSong != null) rememberMiniPlayerPresentation(miniPlayerStyle).height + DockMetrics.MiniGap else 0.dp) +
            DockMetrics.ContentExtra
        CompositionLocalProvider(LocalDockContentInset provides dockContentInset) {
        Box(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).then(xyz.cncnlp.soundwave.ui.common.GlassHost.source)) { // r657 毛玻璃模糊源
            FrostedAppRootSync(prefs, currentSong?.cover.orEmpty()); FrostedPageBackdrop(Modifier.matchParentSize(), isDark) // r657 透明磨砂背景
            // 主路由常驻下层；详情推入 / 推出由 rememberDetailPushFrame 驱动（离场保留最后一帧）。
            // ACCEPT5：压播放器模式下常规落点不渲染，改由播放器层之后的高层落点渲染（互斥）。
            if (!detailAbovePlayer) AppRootDetailOverlay()
            Box(Modifier.fillMaxSize().then(xyz.cncnlp.soundwave.ui.motion.detailUnderlayModifier())) { // r657 底页随详情左移 25% + 压暗 8%
            xyz.cncnlp.soundwave.ui.motion.OccludableHomeLayer { AppRootHomeLayer() } // r657 第三轮 b：被遮住时跳过绘制
            AppRootMainTabRouteRenderer(
                route = AppRoute.MainTab(if (tab == Tab.PLAYER) prevTab else tab),
                searchRoute = { AppRootSearchRoute() },
                playlistsRoute = { AppRootPlaylistsRoute() },
                settingsRoute = { AppRootSettingsRoute() }
            ) }
        }
        } // LocalDockContentInset
    } // Scaffold content lambda
    } // SharedCoverOverlayRoot: flight above detail alpha/list clip, below player/dialogs
        // ACCEPT6 dialogfrost：顶层模糊源——播放器层 + 压播放器详情层成组记录（GlassHost.topState），
        // 表开在播放器之上时改采此源（内容根源记录的是被播放器盖住的主标签页，采样会雾错对象）。
        Box(Modifier.fillMaxSize().then(xyz.cncnlp.soundwave.ui.common.GlassHost.topSource)) {
        AppRootFullPlayerLayer()
        // ACCEPT5：压播放器详情的高层落点（播放器内专辑直达）——在播放器层之上、全局 dialog 之下；
        // 与 Scaffold 内常规落点互斥（detailAbovePlayer 二选一），关闭后露出原封不动的播放器。
        if (detailAbovePlayer) AppRootDetailOverlay(abovePlayer = true)
        }
        // 保持既有全局dialog位于播放器之上；Dock宿主仅占位，不再参与全屏动画的布局测量。
        AppRootOverlayDialogs()
        // r658h：应用内底部抽屉宿主（最上层，铺满整屏含导航栏区域）。
        xyz.cncnlp.soundwave.ui.common.AppOverlayHostLayer(Modifier.zIndex(20f))
    } // fixed full-screen player transition host
    } // CompositionLocalProvider
    } // MaterialTheme
}

// BottomNav 已拆分至 BottomNav.kt

// ListChrome: FusedListCard/RankCard/EmptyHint 已拆到 ListChrome.kt

internal fun Playlist.songCount(): Int = count.coerceAtLeast(songs.size)

// HomePage / SearchPage 已拆到 HomePage.kt / SearchPage.kt

// PlayerProgressBlock 已拆到 PlayerControls.kt；Karaoke 已拆到 LyricRender.kt

// AppleMusicControls 已拆到 PlayerControls.kt

internal fun fmt(sec: Float): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
