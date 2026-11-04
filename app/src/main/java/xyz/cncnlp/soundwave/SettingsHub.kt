package xyz.cncnlp.soundwave
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding
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
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
// Switch/AlertDialog via material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity

@Composable
fun SettingsHub(
    settingsSub: String? = null,
    onSettingsSub: (String?) -> Unit = {},
    // accept6-backnav：导航栈（当前页 = 栈顶）与弹栈返回回调；预测性返回弹一层回直接父页。
    settingsStack: List<String> = emptyList(),
    onSettingsBack: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onSmartSource: () -> Unit = {},
    downloads: List<DownloadItem> = emptyList(),
    onMoveDownload: (DownloadItem, Int) -> Unit = { _, _ -> },
    onPauseDownload: (DownloadItem) -> Unit = {},
    onResumeDownload: (DownloadItem) -> Unit = {},
    onDeleteDownload: (DownloadItem) -> Unit = {},
    onDeleteDownloadRecord: (DownloadItem) -> Unit = {},
    onDeleteDownloads: (List<DownloadItem>) -> Unit = {},
    onDeleteDownloadRecords: (List<DownloadItem>) -> Unit = {},
    onPauseDownloads: (List<DownloadItem>) -> Unit = {},
    onResumeDownloads: (List<DownloadItem>) -> Unit = {},
    onRedownloads: (List<DownloadItem>) -> Unit = {},
    onPlayDownload: (DownloadItem) -> Unit = {},
    onOpenDownloadDir: () -> Unit = {},
    onPickDownloadDir: () -> Unit = {},
    onClearDownloadHistory: (Boolean) -> Unit = {},
    onRedownload: (DownloadItem) -> Unit = {},
    serverUrl: String, preferQ: String, downloadQ: String = "320k", loggedIn: Boolean, user: String, status: String, sleepRemain: Long,
    avatarPath: String = "",
    playSeconds: Long = 0L,
    likedCount: Int = 0,
    customPlaylistCount: Int = 0,
    collectedPlaylistCount: Int = 0,
    scriptReady: Int = 0,
    scripts: List<Script>,
    allowMix: Boolean = false,
    cacheInfo: String = "",
    onUrl: (String) -> Unit, onQuality: (String) -> Unit, onDownloadQuality: (String) -> Unit = {},
    onSleep: (Int) -> Unit,
    onScriptsChange: (List<Script>) -> Unit,
    onImportCode: (String) -> Unit,
    onImportUrl: (String) -> Unit = {},
    onImportFile: () -> Unit = {},
    onScriptMove: (String, Int) -> Unit = { _, _ -> },
    themeMode: String = "dark",
    onThemeMode: (String) -> Unit = {},
    globalFontPref: String = "system",
    lyricFontPref: String = "system",
    onFontFamily: (String, String) -> Unit = { _, _ -> },
    onAppearanceSetting: (String, Float) -> Unit = { _, _ -> },
    onAllowMix: (Boolean) -> Unit = {},
    onClearCache: () -> Unit = {},
    onCacheMax: (Long) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    crossPlatformSearchUi: Boolean = true,
    onCrossPlatformSearch: (Boolean) -> Unit = {},
    onProbe: (Script, (String) -> Unit) -> Unit = { _, cb -> cb("未接入探测") },
    onProbeAll: (((Float) -> Unit), (String) -> Unit) -> Unit = { _, done -> done("未接入批量检测") },
    onSmartScriptSort: (ScriptProbeCache.SortMode) -> Unit = {},
    onSmartAutoConfigure: () -> Unit = {},
    prefetchEnabled: Boolean = true,
    prefetchIntervalSec: Int = 6,
    prefetchCount: Int = 1,
    onPrefetch: (Boolean) -> Unit = {},
    onPrefetchInterval: (Int) -> Unit = {},
    onPrefetchCount: (Int) -> Unit = {},
    lyricGapDp: Int = 9,
    lyricFontSp: Int = 18,
    lyricBlurDp: Int = 0,
    lyricShowTranslation: Boolean = false,
    lyricShowRomanization: Boolean = false,
    lyricHighlightHex: String = "#FFFFFF",
    lyricUnhighlightHex: String = "#A8A8B3",
    lyricWordKaraoke: Boolean = true,
    onLyricWordKaraoke: (Boolean) -> Unit = {},
    lyricFloatEnabled: Boolean = false,
    onLyricFloat: (Boolean) -> Unit = {},
    onLyricShowTranslation: (Boolean) -> Unit = {},
    onLyricShowRomanization: (Boolean) -> Unit = {},
    onLyricHighlight: (String) -> Unit = {},
    onLyricUnhighlight: (String) -> Unit = {},
    onLyricGap: (Int) -> Unit = {},
    onLyricFont: (Int) -> Unit = {},
    onLyricBlur: (Int) -> Unit = {},
    lyricOffsetMs: Int = 0,
    onLyricOffset: (Int) -> Unit = {},
    downloadMultiThread: Boolean = true,
    saveExtLyric: Boolean = true,
    saveExtCover: Boolean = true,
    onDownloadMultiThread: (Boolean) -> Unit = {},
    onSaveExtLyric: (Boolean) -> Unit = {},
    onSaveExtCover: (Boolean) -> Unit = {},
    playerLayout: String = "standard",
    onPlayerLayout: (String) -> Unit = {},
    detailCoverStyle: String = DetailCoverStyles.DEFAULT,
    onDetailCoverStyle: (String) -> Unit = {},
    menuIconWhite: Boolean = false,
    autoPlayOnStart: Boolean = false,
    onAutoPlay: (Boolean) -> Unit = {},
    historySongs: List<Song> = emptyList(),
    onPlayHistorySong: (Song) -> Unit = {},
    onDeleteHistory: (Song) -> Unit = {},
    onDeleteHistoryBatch: (List<Song>, Boolean) -> Unit = { _, _ -> },
    onClearHistory: () -> Unit = {},
    onBackupRestored: (LocalStore.UserBundle) -> Unit = {},
    onPrepareWebDavAvatarSync: () -> xyz.cncnlp.soundwave.sync.WebDavAvatarSync? = { null },
    onPrepareWebDavProfileExtrasSync: () -> xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync? = { null },
    onPrepareWebDavAccountDataSync: () -> xyz.cncnlp.soundwave.sync.WebDavAccountDataSync? = { null },
    onImportWebDavProfileName: suspend (String) -> Boolean = { false },
    webDavBundle: LocalStore.UserBundle,
    onWebDavBundleApplied: (LocalStore.UserBundle) -> Unit
) = xyz.cncnlp.soundwave.ui.motion.SettingsPushHost(settingsSub, stack = settingsStack, onPredictiveBack = onSettingsBack) { settingsSub -> // r657 第二轮：设置子页推入 / 推出转场；accept6-backnav：预测性返回改为弹栈回直接父页（深度-1 时即设置主页）
    val primary = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground
    // 分类下的深层页面保留明确返回入口；不重复展示账户头像和通用品牌图案。
    val fullChrome = settingsSub in setOf("smart", "scripts", "cache", "crash", "stats", "history", "backup", "downloads", "about", "data_flow", "notifications")
    if (fullChrome) {
        Column(Modifier.fillMaxSize()) {
            val title = when (settingsSub) {
                "notifications" -> "通知中心"
                "smart" -> "智能换源"
                "scripts" -> "音源脚本"
                // r657（v3 #11）：每个子页页头即页面名，页内只留一行说明（不再重复衬线大标题）。
                "cache" -> "缓存管理"
                "crash" -> "崩溃诊断"
                "backup" -> "备份与恢复"
                "data_flow" -> "数据流向"
                "about" -> "关于 APP"
                "stats" -> "听歌统计"
                "history" -> "播放历史"
                "downloads" -> "查看下载"
                else -> "设置"
            }
            AppPageHeader(
                title = title,
                loggedIn = loggedIn,
                avatarPath = avatarPath,
                onProfileClick = onLoginClick,
                // 设置深层页沿用现有返回手势；通知中心自身不重复显示通知入口。
                showAvatar = false,
                trailing = if (settingsSub == "notifications") null else {
                    { SettingsNotificationButton { onSettingsSub("notifications") } }
                },
                avatarSizeDp = 42f, titleSizeSp = 31, serifTitle = true, designTitle = true, startPadding = 14.dp
            )
            // 设置孙级页：脚本按最新要求整体 +5dp；其余孙级保留既定 +3dp；查看下载例外不下移。
            Box(Modifier.fillMaxSize().padding(top = when (settingsSub) { "downloads" -> 0.dp; "scripts" -> 5.dp; else -> 3.dp })) {
                when (settingsSub) {
                    "notifications" -> SettingsNotificationsPage(
                        scripts = scripts,
                        onOpenScripts = { onSettingsSub("scripts") }
                    )
                    "smart" -> SmartSourcePage(
                        scripts = scripts,
                        onScriptsChange = onScriptsChange,
                        onReResolveNow = onSmartSource,
                        onAutoConfigure = onSmartAutoConfigure
                    )
                    "scripts" -> ScriptsPage(
                        scripts = scripts,
                        onChange = onScriptsChange,
                        onImportCode = onImportCode,
                        onImportUrl = onImportUrl,
                        onImportFile = onImportFile,
                        onMove = onScriptMove,
                        onRefresh = onProbeAll,
                        onProbe = onProbe,
                        onSmartSort = onSmartScriptSort
                    )
                    "cache" -> CacheManagePage(onClear = onClearCache)
                    "crash" -> StartupCrashPage()
                    "stats" -> ListenStatsPage(onOpenHistory = { onSettingsSub("history") })
                    "history" -> PlayHistoryPage(
                        history = historySongs,
                        onPlay = onPlayHistorySong,
                        onDelete = { s -> onDeleteHistory(s) },
                        onClear = onClearHistory,
                        onDeleteBatch = onDeleteHistoryBatch
                    )
                    "backup" -> BackupRestorePage(
                        onPrepareWebDavAvatarSync = onPrepareWebDavAvatarSync,
                        onPrepareWebDavProfileExtrasSync = onPrepareWebDavProfileExtrasSync,
                        onPrepareWebDavAccountDataSync = onPrepareWebDavAccountDataSync,
                        onImportWebDavProfileName = onImportWebDavProfileName,
                        webDavBundle = webDavBundle,
                        onWebDavBundleApplied = onWebDavBundleApplied,
                        onRestored = onBackupRestored
                    )
                    // r656：查看下载页重做（DownloadsPage.kt）：概览卡 + 状态筛选 + 失败分组 + 任务行，弹窗统一设计抽屉。
                    "downloads" -> DownloadsPage(
                        downloads = downloads,
                        onMoveDownload = onMoveDownload,
                        onPauseDownload = onPauseDownload,
                        onResumeDownload = onResumeDownload,
                        onDeleteDownloads = onDeleteDownloads,
                        onDeleteDownloadRecords = onDeleteDownloadRecords,
                        onPauseDownloads = onPauseDownloads,
                        onResumeDownloads = onResumeDownloads,
                        onRedownloads = onRedownloads,
                        onPlayDownload = onPlayDownload,
                        onOpenDownloadDir = onOpenDownloadDir,
                        onPickDownloadDir = onPickDownloadDir,
                        onClearDownloadHistory = onClearDownloadHistory,
                        onRedownload = onRedownload
                    )
                    "about" -> AboutAppPage(status = status, serverUrl = serverUrl, onCheckUpdate = onCheckUpdate, onOpenDataFlow = { onSettingsSub("data_flow") })
                    "data_flow" -> DataFlowPage()
                }
            }
        }
        return@SettingsPushHost
    }
    SoundwaveDesignTheme(resolveIsDark(themeMode, isSystemInDarkTheme())) {
    MePage(
            serverUrl = serverUrl, preferQ = preferQ, downloadQ = downloadQ, loggedIn = loggedIn, user = user, status = status, sleepRemain = sleepRemain,
            avatarPath = avatarPath, playSeconds = playSeconds, likedCount = likedCount,
            customPlaylistCount = customPlaylistCount, collectedPlaylistCount = collectedPlaylistCount,
            scriptReady = scriptReady,
            allowMix = allowMix, cacheInfo = cacheInfo,
            onUrl = onUrl, onQuality = onQuality, onDownloadQuality = onDownloadQuality, onSleep = onSleep,
            themeMode = themeMode, onThemeMode = onThemeMode,
            globalFontPref = globalFontPref, lyricFontPref = lyricFontPref, onFontFamily = onFontFamily,
            onAppearanceSetting = onAppearanceSetting,
            onAllowMix = onAllowMix, onClearCache = onClearCache, onCacheMax = onCacheMax,
            onOpenScripts = { onSettingsSub("scripts") },
            onOpenDownloads = { onSettingsSub("downloads") },
            onOpenAbout = { onSettingsSub("about") },
            onLoginClick = onLoginClick,
            onSmartSource = { onSettingsSub("smart") },
            onOpenCache = { onSettingsSub("cache") },
            onOpenStats = { onSettingsSub("stats") },
            onOpenHistory = { onSettingsSub("history") },
            onOpenBackup = { onSettingsSub("backup") },
            crossPlatformSearchUi = crossPlatformSearchUi,
            onCrossPlatformSearch = onCrossPlatformSearch,
            prefetchEnabled = prefetchEnabled,
            prefetchIntervalSec = prefetchIntervalSec,
            prefetchCount = prefetchCount,
            onPrefetch = onPrefetch,
            onPrefetchInterval = onPrefetchInterval,
            onPrefetchCount = onPrefetchCount,
            lyricGapDp = lyricGapDp,
            lyricFontSp = lyricFontSp,
            lyricBlurDp = lyricBlurDp,
            lyricShowTranslation = lyricShowTranslation,
            lyricShowRomanization = lyricShowRomanization,
            lyricHighlightHex = lyricHighlightHex,
            lyricUnhighlightHex = lyricUnhighlightHex,
            lyricWordKaraoke = lyricWordKaraoke,
            onLyricWordKaraoke = onLyricWordKaraoke,
            lyricFloatEnabled = lyricFloatEnabled,
            onLyricFloat = onLyricFloat,
            onLyricShowTranslation = onLyricShowTranslation,
            onLyricShowRomanization = onLyricShowRomanization,
            onLyricHighlight = onLyricHighlight,
            onLyricUnhighlight = onLyricUnhighlight,
            onLyricGap = onLyricGap,
            onLyricFont = onLyricFont,
            onLyricBlur = onLyricBlur,
            lyricOffsetMs = lyricOffsetMs,
            onLyricOffset = onLyricOffset,
            downloadMultiThread = downloadMultiThread,
            saveExtLyric = saveExtLyric,
            saveExtCover = saveExtCover,
            onDownloadMultiThread = onDownloadMultiThread,
            onSaveExtLyric = onSaveExtLyric,
            onSaveExtCover = onSaveExtCover,
            playerLayout = playerLayout,
            onPlayerLayout = onPlayerLayout,
            detailCoverStyle = detailCoverStyle,
            onDetailCoverStyle = onDetailCoverStyle,
            autoPlayOnStart = autoPlayOnStart,
            onAutoPlay = onAutoPlay,
            historySongs = historySongs,
            settingsSub = settingsSub,
            onSettingsSub = onSettingsSub
        )
    }
}
