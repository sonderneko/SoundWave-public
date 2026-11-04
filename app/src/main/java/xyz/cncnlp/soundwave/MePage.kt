package xyz.cncnlp.soundwave
import xyz.cncnlp.soundwave.ui.common.TypeScale
import xyz.cncnlp.soundwave.ui.common.staggerEnter
import xyz.cncnlp.soundwave.ui.common.FrostedPrefs
import xyz.cncnlp.soundwave.source.ScriptHttpCredentialGuardSetting
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.VolumeUp

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.em
import xyz.cncnlp.soundwave.ui.common.DesignGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignCircleIconButton
import xyz.cncnlp.soundwave.ui.settings.*
import androidx.compose.ui.platform.testTag
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

/** r655：每个设置项对应 SettingsLineGlyphs 中独立的一枚线性图标（主页分类行与详情页子选项互不重复）。 */
internal enum class SettingGlyphSpec(val glyphKey: String) {
    AutoPlay("autoplay"),
    OnlineQuality("quality"),
    StreamDetail("stream_mobile"),
    Prefetch("prefetch"),
    PrefetchToggle("prefetch_toggle"),
    PrefetchCount("prefetch_count"),
    RequestInterval("request_interval"),
    SleepTimer("sleep"),
    SleepDetail("sleep_timer"),
    LyricsCategory("lyrics"),
    LyricsTranslation("translation"),
    LyricsRomanization("romanization"),
    LyricBlur("distance_blur"),
    LyricFontSize("lyric_font_size"),
    LyricLineSpacing("lyric_line_spacing"),
    LyricOffset("lyric_offset"),
    WordHighlight("word_highlight"),
    CurrentWordLift("word_lift"),
    FloatingLyrics("floating_lyrics"),
    UnlockFloatingLyrics("float_unlock"),
    FloatingLyricLines("float_lines"),
    FloatingLyricFont("float_font"),
    FloatingBackgroundOpacity("float_bg_opacity"),
    FloatingTextOpacity("float_text_opacity"),
    FloatingWidth("float_width"),
    FloatingTextColor("float_color"),
    LyricFontFamily("lyric_font_family"),
    Theme("theme"),
    ThemeLight("theme_light"),
    ThemeNight("theme_night"),
    ThemeAuto("theme_auto"),
    FrostedBackground("frosted_bg"),
    FrostedTint("frosted_tint"),
    GlobalFont("global_font"),
    ReduceMotion("reduce_motion"),
    PlayerLayout("player_layout"),
    DetailCover("detail_cover"),
    BottomNavStyle("dock"),
    MiniPlayerStyle("dock_style"),
    PlaybackSpeed("speed"),
    Crossfade("crossfade"),
    SpeedDetail("speed_value"),
    Equalizer("equalizer"),
    EqDetail("eq_preset"),
    AudioFocus("audio_focus"),
    MixDetail("mix_playback"),
    SmartSource("smart_source"),
    ScriptCatalog("scripts"),
    CrossPlatformSearch("cross_search"),
    CrossSearchDetail("cross_search_toggle"),
    CredentialProtection("credential_guard"),
    DownloadsCategory("download_settings"),
    DownloadQuality("download_quality"),
    MultiThreadDownload("multi_thread"),
    SaveCover("save_cover"),
    SaveLyrics("save_lyrics"),
    DownloadFilename("download_filename"),
    OpenDownloads("open_downloads"),
    DownloadTasks("download_tasks"),
    Cache("cache"),
    ListeningStats("stats"),
    PlaybackHistory("history"),
    BackupRestore("backup"),
    AccountSync("account_sync"),
    DataFlow("data_flow"),
    CrashDiagnostics("crash"),
    AboutApp("about")
}

/**
 * ACCEPT6 iconcolor（用户 2026-11-04 23:25 裁定，取代 ACCEPT5 V3 逐行配色）：设置图标颜色按组统一——
 * 每组只用该组英文副标题（kicker）的颜色，即 [SettingsGroupMarks] 组色（与组头眉题同色），一组一色；
 * 主页行与子页行同规、无底色。归组见 [settingGlyphGroup]：主页 spec 归其行所在组，子页 spec 归其
 * 入口行所在组；声明未用的 spec 按名义归组（全枚举覆盖由 when 穷尽性保证）。
 */
internal enum class SettingGlyphGroup { Playback, Sources, Downloads, Appearance, Data, Support }

/** spec → 所属设置组（与主页六组同构）。 */
internal fun settingGlyphGroup(spec: SettingGlyphSpec): SettingGlyphGroup = when (spec) {
    SettingGlyphSpec.AutoPlay,
    SettingGlyphSpec.OnlineQuality,
    SettingGlyphSpec.PlaybackSpeed,
    SettingGlyphSpec.Equalizer,
    SettingGlyphSpec.AudioFocus,
    SettingGlyphSpec.Crossfade,
    SettingGlyphSpec.Prefetch,
    SettingGlyphSpec.SleepTimer,
    SettingGlyphSpec.PrefetchToggle,
    SettingGlyphSpec.PrefetchCount,
    SettingGlyphSpec.RequestInterval,
    SettingGlyphSpec.StreamDetail,
    SettingGlyphSpec.SpeedDetail,
    SettingGlyphSpec.EqDetail,
    SettingGlyphSpec.MixDetail,
    SettingGlyphSpec.SleepDetail -> SettingGlyphGroup.Playback
    SettingGlyphSpec.SmartSource,
    SettingGlyphSpec.ScriptCatalog,
    SettingGlyphSpec.CrossPlatformSearch,
    SettingGlyphSpec.CrossSearchDetail,
    SettingGlyphSpec.CredentialProtection -> SettingGlyphGroup.Sources
    SettingGlyphSpec.DownloadsCategory,
    SettingGlyphSpec.OpenDownloads,
    SettingGlyphSpec.DownloadQuality,
    SettingGlyphSpec.MultiThreadDownload,
    SettingGlyphSpec.SaveCover,
    SettingGlyphSpec.SaveLyrics,
    SettingGlyphSpec.DownloadFilename,
    SettingGlyphSpec.DownloadTasks -> SettingGlyphGroup.Downloads
    SettingGlyphSpec.Theme,
    SettingGlyphSpec.PlayerLayout,
    SettingGlyphSpec.DetailCover,
    SettingGlyphSpec.BottomNavStyle,
    SettingGlyphSpec.LyricsCategory,
    SettingGlyphSpec.ThemeLight,
    SettingGlyphSpec.ThemeNight,
    SettingGlyphSpec.ThemeAuto,
    SettingGlyphSpec.FrostedBackground,
    SettingGlyphSpec.FrostedTint,
    SettingGlyphSpec.GlobalFont,
    SettingGlyphSpec.ReduceMotion,
    SettingGlyphSpec.LyricsTranslation,
    SettingGlyphSpec.LyricsRomanization,
    SettingGlyphSpec.LyricFontSize,
    SettingGlyphSpec.LyricLineSpacing,
    SettingGlyphSpec.LyricBlur,
    SettingGlyphSpec.LyricOffset,
    SettingGlyphSpec.WordHighlight,
    SettingGlyphSpec.CurrentWordLift,
    SettingGlyphSpec.LyricFontFamily,
    SettingGlyphSpec.FloatingLyrics,
    SettingGlyphSpec.UnlockFloatingLyrics,
    SettingGlyphSpec.FloatingLyricLines,
    SettingGlyphSpec.FloatingLyricFont,
    SettingGlyphSpec.FloatingBackgroundOpacity,
    SettingGlyphSpec.FloatingTextOpacity,
    SettingGlyphSpec.FloatingWidth,
    SettingGlyphSpec.FloatingTextColor,
    SettingGlyphSpec.MiniPlayerStyle -> SettingGlyphGroup.Appearance
    SettingGlyphSpec.BackupRestore,
    SettingGlyphSpec.Cache,
    SettingGlyphSpec.ListeningStats,
    SettingGlyphSpec.PlaybackHistory,
    SettingGlyphSpec.DataFlow,
    SettingGlyphSpec.AccountSync -> SettingGlyphGroup.Data
    SettingGlyphSpec.CrashDiagnostics,
    SettingGlyphSpec.AboutApp -> SettingGlyphGroup.Support
}

internal fun settingGlyphColor(spec: SettingGlyphSpec): Color = when (settingGlyphGroup(spec)) {
    SettingGlyphGroup.Playback -> SettingsGroupMarks.Playback
    SettingGlyphGroup.Sources -> SettingsGroupMarks.Sources
    SettingGlyphGroup.Downloads -> SettingsGroupMarks.Downloads
    SettingGlyphGroup.Appearance -> SettingsGroupMarks.Appearance
    SettingGlyphGroup.Data -> SettingsGroupMarks.Data
    SettingGlyphGroup.Support -> SettingsGroupMarks.Support
}

/** accept5-listencard：独立听歌卡的后台快照（三件套一次 IO 读齐的结果）。 */
internal data class ListeningCardSnapshot(
    val weekSec: Long = 0L,
    val prevWeekSec: Long = 0L,
    val last7Days: List<Long> = emptyList()
)

/** 七日柱数据规整：只取最近 7 天、不足左补 0（与删除前旧卡口径一致）。 */
internal fun listeningCardDailySeconds(raw: List<Long>): List<Long> =
    raw.takeLast(7).let { List(7 - it.size) { 0L } + it }

/** 差值胶囊文案：与上周差（小时，↑/↓ + 一位小数），与删除前旧卡口径一致。 */
internal fun listeningCardDiffText(weekSec: Long, prevWeekSec: Long): String {
    val diffHours = (weekSec - prevWeekSec) / 3600f
    val text = String.format(java.util.Locale.ROOT, "%.1f", kotlin.math.abs(diffHours))
    return (if (diffHours >= 0f) "↑ " else "↓ ") + text + "h"
}

@Composable
private fun rememberPrototypeAccountBackground(): Modifier {
    val reduce = MotionPolicy.rememberReduceMotion()
    // r657 第三轮 b（卡顿）：相位 State 只在绘制阶段读取——旧写法在组合中读 .value，整张账号卡每帧重组。
    val phaseState: androidx.compose.runtime.State<Float>? = if (MotionPolicy.allowDecorativeLoops(reduce)) {
        val loop = rememberInfiniteTransition(label = "accountPrototypeGlow")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "accountPrototypePhase")
    } else null
    return Modifier.drawBehind {
        val phase = phaseState?.value ?: 0f
        drawRect(Color(0xFF11141A))
        val driftX = 26.dp.toPx() * phase
        val driftY = 18.dp.toPx() * phase
        fun glow(color: Color, center: Offset, radius: Float) {
            drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.85f), Color.Transparent), center = center, radius = radius))
        }
        glow(Color(0xFFFF6A4F), Offset(size.width - 60.dp.toPx() - driftX, 20.dp.toPx() + driftY), 110.dp.toPx())
        glow(SoundwaveDesignPalette.Accent, Offset(50.dp.toPx() + driftX, size.height + driftY), 120.dp.toPx())
        glow(Color(0xFF38A98D), Offset(size.width * 0.4f + 80.dp.toPx() - driftX, size.height * 0.3f + 70.dp.toPx()), 80.dp.toPx())
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), end = Offset(size.width * 0.38f, size.height)))
    }
}

private fun settingIcon(glyph: SettingGlyphSpec): ImageVector = SettingsLineGlyphs.of(glyph.glyphKey)

/** 每个 SettingGlyphSpec 的图标键：供唯一性单测读取。 */
internal fun settingGlyphKeys(): List<String> = SettingGlyphSpec.values().map { it.glyphKey }

private val EQ_NAMES = listOf("关闭", "流行", "摇滚", "爵士", "古典", "人声")
private val SETTINGS_SWITCH_LABELS = setOf("开", "关", "已开启", "已关闭")
private val SETTINGS_SWITCH_ON_LABELS = setOf("开", "已开启")


internal data class SettingsPickerRequest(
    val kicker: String, val title: String, val copy: String,
    val options: List<String>, val selected: Int, val onPick: (Int) -> Unit
)

/** E13：带预览卡的样式选择请求（播放器界面样式 / 详情页封面样式专用弹层）。 */
internal enum class StylePickerKind { PLAYER_LAYOUT, DETAIL_COVER }

internal data class SettingsStylePickerRequest(
    val kind: StylePickerKind, val kicker: String, val title: String, val copy: String,
    val options: List<String>, val selected: Int, val onPick: (Int) -> Unit
)

/** 设计稿 settingDetails：子页眉题（分类）/ 大标题 / 说明。 */
internal val SETTINGS_DETAIL_HEROES: Map<String, Triple<String, String, String>> = mapOf(
    "preload" to Triple("播放与音质", "预解析", "提前解析即将播放的歌曲，减少切歌等待。"),
    "lyrics" to Triple("外观", "歌词与悬浮歌词", "统一设置歌词阅读、逐字效果与悬浮窗口。"),
    "theme" to Triple("外观", "主题与字体", "选择主题模式、界面字体与动效强度。"),
    "dock" to Triple("外观", "底栏与迷你条", "切换底部导航与迷你播放条的外形，以及隐藏方式。"),
    "search" to Triple("音源与脚本", "跨平台搜索", "搜索时同时查询五个平台并去重合并。"),
    "download" to Triple("下载", "下载管理设置", "配置音质、并发、封面、歌词和文件名。")
)

/**
 * ACCEPT4（v8 稿 pLyricsSet）：歌词设置分组标题头——稿 .glabel：中文 15sp/20sp 粗体 + 英文 mono 眉题
 * （8.5sp、字距 .14em、珊瑚，按 GCOL「播放与歌词」组色口径走 designAccentTextColor），上 28 / 下 10。
 * 注：设置主页分类标题颜色属冻结区，本组件仅用于详情页内分组，不动主页。
 */
@Composable
internal fun LyricsGroupHeader(zh: String, en: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            zh, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
            letterSpacing = (-0.01).em
        )
        Spacer(Modifier.width(8.dp))
        Text(
            en.uppercase(), color = xyz.cncnlp.soundwave.designAccentTextColor(),
            fontSize = 8.5.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, letterSpacing = 0.14.em,
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

/**
 * ACCEPT4（v8 稿 pLyricsSet）：歌词设置页顶部 Live Preview 实时预览卡（稿 .lprev 卡面：
 * 表面色 + 1dp 线 + r24，内距 18）。样例三行随当前设置实时变化：字号取歌词字号（当前行 +5）、
 * 行间松紧取行距、译文/罗马音小字随对应开关显隐——改设置即见效果，不是静态示意图。
 * 固定高（用户 2026-11-04）：样例区按最坏情况（字号封顶 30 + 当前行 +5 + 行距最大档 36 +
 * 译文/罗马音都在场）预留恒定高度，样例在盒内垂直居中——调任何档位卡高不变，下方选项不位移。
 */
@Composable
internal fun LyricsLivePreviewCard(fontSp: Float, gapDp: Float, showTranslation: Boolean, showRomanization: Boolean) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    val muted = if (dark) SoundwaveDesignPalette.NightMuted else SoundwaveDesignPalette.Muted
    val ink = colors.onSurface
    val dimInk = ink.copy(alpha = 0.42f)
    val base = fontSp.coerceIn(12f, 30f)
    Column(
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .shadow(6.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .border(1.dp, line, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Text(
            "Live preview · 实时预览", color = xyz.cncnlp.soundwave.designAccentTextColor(),
            fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, letterSpacing = 0.08.em
        )
        Spacer(Modifier.height(12.dp))
        // 固定高样例盒：高度由下方各行 lineHeight 公式在上限处求和（sp 部分经 density 换 dp，含 fontScale），
        // 与当前字号/行距/开关状态无关；内容实时变化只在盒内居中呈现，超盒裁剪。
        val sampleBoxHeight = with(LocalDensity.current) {
            ((30f + 36f) + (30f + 5f + 36f) + (30f * 0.62f + 4f) + (30f * 0.58f + 4f) + (30f + 36f)).sp.toDp()
        } + (36f + 3f + 2f + 36f).dp
        Box(Modifier.fillMaxWidth().height(sampleBoxHeight).clip(androidx.compose.ui.graphics.RectangleShape)) {
            Column(Modifier.fillMaxWidth().align(Alignment.Center)) {
                Text(
                    "I keep the lights on low", color = dimInk,
                    fontSize = base.sp, lineHeight = (base + gapDp).sp, fontWeight = FontWeight.Medium,
                    fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1
                )
                Column(Modifier.padding(top = gapDp.dp)) {
                    Text(
                        "Every horizon bends to you", color = ink,
                        fontSize = (base + 5f).sp, lineHeight = (base + 5f + gapDp).sp, fontWeight = FontWeight.Bold,
                        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (showTranslation) Text(
                        "每一道地平线都为你弯曲", color = muted,
                        fontSize = (base * 0.62f).sp, lineHeight = (base * 0.62f + 4f).sp,
                        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    if (showRomanization) Text(
                        "ěvrī hərīzən bendz tu yu", color = muted,
                        fontSize = (base * 0.58f).sp, lineHeight = (base * 0.58f + 4f).sp,
                        fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    "And the city hums in blue", color = dimInk,
                    fontSize = base.sp, lineHeight = (base + gapDp).sp, fontWeight = FontWeight.Medium,
                    fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1,
                    modifier = Modifier.padding(top = gapDp.dp)
                )
            }
        }
    }
}

@Composable
fun MePage(
    serverUrl: String, preferQ: String, downloadQ: String = "320k", loggedIn: Boolean, user: String, status: String, sleepRemain: Long,
    avatarPath: String = "",
    playSeconds: Long = 0L,
    likedCount: Int = 0,
    customPlaylistCount: Int = 0,
    collectedPlaylistCount: Int = 0,
    historySongs: List<Song> = emptyList(),
    scriptReady: Int = 0,
    allowMix: Boolean = false,
    cacheInfo: String = "",
    onUrl: (String) -> Unit, onQuality: (String) -> Unit, onDownloadQuality: (String) -> Unit = {},
    onSleep: (Int) -> Unit,
    themeMode: String = "dark",
    onThemeMode: (String) -> Unit = {},
    globalFontPref: String = "system",
    lyricFontPref: String = "system",
    onFontFamily: (String, String) -> Unit = { _, _ -> },
    onAppearanceSetting: (String, Float) -> Unit = { _, _ -> },
    onAllowMix: (Boolean) -> Unit = {},
    onClearCache: () -> Unit = {},
    onCacheMax: (Long) -> Unit = {},
    onOpenScripts: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onLoginClick: () -> Unit = {},
    onSmartSource: () -> Unit = {},
    onOpenCache: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    crossPlatformSearchUi: Boolean = true,
    onCrossPlatformSearch: (Boolean) -> Unit = {},
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
    /** r658c P：歌词偏移（与播放器「⋯ → 歌词设置」、长按歌词共用 lyric_offset_ms；回调收到新的绝对值）。 */
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
    onDeleteHistory: (Song) -> Unit = {},
    onClearHistory: () -> Unit = {},
    menuIconWhite: Boolean = false,
    autoPlayOnStart: Boolean = false,
    onAutoPlay: (Boolean) -> Unit = {},
    settingsSub: String? = null,
    onSettingsSub: (String?) -> Unit = {}
) {
    var showPlayQuality by remember { mutableStateOf(false) }
    var showDownloadQuality by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showCacheSheet by remember { mutableStateOf(false) }
    val mePageCtx = androidx.compose.ui.platform.LocalContext.current
    val settingsOwner = remember(mePageCtx) { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() }
    val mePagePrefs = ContextPreferences.open(mePageCtx, "soundwave", 0, settingsOwner)
    FrostedPrefs.load(mePagePrefs)
    var appReduceMotionEnabled by remember(mePageCtx) {
        mutableStateOf(MotionPolicy.appReduceMotion(mePageCtx))
    }
    fun updateAppReduceMotion(enabled: Boolean) {
        appReduceMotionEnabled = enabled
        MotionPolicy.setAppReduceMotion(mePageCtx, enabled)
    }
    val lyricWordLiftEnabled by rememberLyricWordLiftEnabled()
    // 播放速度 / 均衡器预置：MePage 本地状态（读 prefs 持久化）
    var localPlaySpeed by remember { mutableFloatStateOf(mePagePrefs.getFloat("play_speed", 1f).coerceIn(0.5f, 2f)) }
    var localEqPreset by remember { mutableIntStateOf(mePagePrefs.getInt("eq_preset", 0).coerceIn(0, 5)) }
    var showMixSheet by remember { mutableStateOf(false) }
    var showTransitionSettings by remember { mutableStateOf(false) }
    val transitionSeconds by rememberPlaybackTransitionSeconds(mePagePrefs)
    // r656：速度 / 均衡器 / 音频焦点不再有子页，行右侧显示当前值，点按弹统一设计抽屉。
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showEqSheet by remember { mutableStateOf(false) }
    var audioFocus by remember(mePagePrefs) { mutableStateOf(xyz.cncnlp.soundwave.playback.AudioFocusSettingsStore.read(mePagePrefs)) }
    fun applyAudioFocus(next: xyz.cncnlp.soundwave.playback.AudioFocusSettings) {
        audioFocus = next
        xyz.cncnlp.soundwave.playback.AudioFocusSettingsStore.write(mePagePrefs, next)
        PlayerService.setAudioFocusSettings(next)
    }
    var showCrossSearchSheet by remember { mutableStateOf(false) }
    // r656：数据流向改为独立设置详情页（SettingsSupportPages.kt）。
    val bugCtx = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground
    val settingsContext = LocalContext.current
    var scriptHttpCredentialGuardEnabled by remember(mePagePrefs) {
        mutableStateOf(ScriptHttpCredentialGuardSetting.isEnabled(mePagePrefs))
    }
    fun setScriptHttpCredentialGuard(enabled: Boolean) {
        scriptHttpCredentialGuardEnabled = enabled
        ScriptHttpCredentialGuardSetting.setEnabled(mePagePrefs, enabled)
    }
    // r654：底栏与迷你条样式合并为一项「底栏/迷你条样式」（dock_style），旧的两项偏好任一为胶囊即迁移为胶囊。
    val mergedDockStyle = remember(mePagePrefs) { DockStylePrefs.read(mePagePrefs) }
    var bottomNavStyle by remember(mePagePrefs) { mutableStateOf(mergedDockStyle) }
    var miniPlayerStyle by remember(mePagePrefs) { mutableStateOf(mergedDockStyle) }
    fun setBottomNavStyle(style: String) {
        bottomNavStyle = if (style == "capsule") "capsule" else "default"
        mePagePrefs.edit().putString("bottom_nav_style", bottomNavStyle).apply()
        onAppearanceSetting("navstyle", if (bottomNavStyle == "capsule") 1f else 0f)
    }
    fun setMiniPlayerStyle(style: String) {
        miniPlayerStyle = if (style == "capsule") "capsule" else "default"
        mePagePrefs.edit().putString("mini_player_style", miniPlayerStyle).apply()
        onAppearanceSetting("ministyle", if (miniPlayerStyle == "capsule") 1f else 0f)
    }
    fun setDockStyle(style: String) {
        val v = if (style == "capsule") "capsule" else "default"
        mePagePrefs.edit().putString(DockStylePrefs.KEY, v).apply()
        setBottomNavStyle(v)
        setMiniPlayerStyle(v)
    }
    val surface = MaterialTheme.colorScheme.surface

    @Composable
    fun RightBtn(text: String, selected: Boolean = true, onClick: () -> Unit) {
        // r655：开/关类取值统一为新版无文字开关；其余取值为圆角浅底小框（无下划线）。
        if (text in SETTINGS_SWITCH_LABELS) SettingsSwitch(checked = selected && text in SETTINGS_SWITCH_ON_LABELS, onClick = onClick)
        else SettingsValueChip(text, picker = false, onClick = onClick)
    }

    @Composable
    fun SettingGlyph(glyph: SettingGlyphSpec, tint: Color, size: androidx.compose.ui.unit.Dp = 24.dp) {
        Icon(settingIcon(glyph), "设置项", tint = tint, modifier = Modifier.size(size))
    }

    @Composable
    fun GroupBox(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
    @Composable
    fun Hair() {
        // r657 第三轮（用户 10-18）：原型 .srow+.srow::before——分割线从文字处（63dp）开始。
        xyz.cncnlp.soundwave.ui.settings.SettingsRowDivider()
    }

    /**
     * E13 样式预览（J 波改保真微缩，见 ui.settings.StylePreviewMiniatures）：
     * 播放器——默认 = 居中方封面 + 标题 + 进度 + 控件；仅封面沉浸 = 封面铺满 + 底部控件压盖；
     * Apple Music = 大圆角封面 + 左对齐标题 + 粗进度丸。
     * 详情——默认 = 封面卡 + 标题 + 播放钮 + 列表；标准 = 大封面居中 + 眉题 + 标题；
     * 沉浸封面 = 封面满幅无圆角 + 顶部浮钮 + 底部渐隐到页底。
     */
    @Composable
    fun StylePreviewArt(kind: StylePickerKind, index: Int) {
        // J 波：旧示意画法（浅粉方块 + 灰杠 + 圆点）整体退役，改保真微缩渲染器
        // （ui.settings.StylePreviewMiniatures：按真实布局等比微缩、渐变封面、本体字形）。
        when (kind) {
            StylePickerKind.PLAYER_LAYOUT -> xyz.cncnlp.soundwave.ui.settings.PlayerStyleMiniature(index)
            StylePickerKind.DETAIL_COVER -> xyz.cncnlp.soundwave.ui.settings.DetailStyleMiniature(index)
        }
    }

    /**
     * E12：底栏与迷你条样式的双选项预览卡（参照用户设计稿该页）。
     * 左「默认通栏」/ 右「圆角胶囊」，各带示意预览（通栏 = 贴边横条 + 迷你条；
     * 胶囊 = 两条悬浮圆条 + 珊瑚圆钮）；选中项珊瑚描边 + 珊瑚标题。
     * 只改选择呈现：样式值（default/capsule）与联动切换语义由调用方 setDockStyle 保证不变。
     * E13 起选项卡本体与样式弹层共用 ui.settings.StylePreviewOptionCard（E9 复用）。
     */
    @Composable
    fun DockStylePreviewSelector(current: String, onSelect: (String) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            xyz.cncnlp.soundwave.ui.settings.StylePreviewOptionCard(
                title = "默认通栏",
                selected = current != "capsule",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("default") },
            ) {
                // J 波：保真微缩（迷你条 + 四列底栏），两选项内容/文案不变、只升渲染。
                xyz.cncnlp.soundwave.ui.settings.DockStyleMiniature(capsule = false)
            }
            xyz.cncnlp.soundwave.ui.settings.StylePreviewOptionCard(
                title = "圆角胶囊",
                selected = current == "capsule",
                modifier = Modifier.weight(1f),
                onClick = { onSelect("capsule") },
            ) {
                xyz.cncnlp.soundwave.ui.settings.DockStyleMiniature(capsule = true)
            }
        }
    }

    /** 设置主页行（设计稿 .setting-row）：64dp / 左右 7dp；30dp 图标区 + 21dp 单色线性图标；标题 14sp 粗 / 19 行高、副标题 11sp / 15 行高（r657 第二轮按原型 .grp .srow）。 */
    @Composable
    fun MainRow(
        title: String,
        subtitle: String = "",
        glyph: SettingGlyphSpec,
        onClick: (() -> Unit)? = null,
        accent: Color = SoundwaveDesignPalette.Accent,
        toggleChecked: Boolean? = null,
        trailing: @Composable () -> Unit = {}
    ) {
        val ink = MaterialTheme.colorScheme.onSurface
        // r657 第三轮（用户 10-18：照原型）：.srow 62dp / 内距 10·14 / 间距 13；36dp r12 色块图标；Manrope 700 14/19 + 11/15（#ph .grp .srow b）。
        // r658b 16：去掉卡片后回到通栏 64dp / 左右 7dp。
        SettingsDesignRow(mark = accent, onClick = onClick, minHeight = 64.dp, toggleChecked = toggleChecked) {
            xyz.cncnlp.soundwave.ui.settings.SettingsIconTile(settingIcon(glyph), xyz.cncnlp.soundwave.ui.settings.settingsToneForMark(accent), tint = settingGlyphColor(glyph))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.Center) {
                Text(title, color = ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.sp,  /* J 波：对齐设计稿 .srow 标题 14/19（原 13.5/18） */ fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(
                    subtitle, color = settingsMutedColor(), fontSize = TypeScale.Micro.fontSize, lineHeight = 15.sp, fontFamily = DesignFonts.Manrope,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            trailing()
        }
    }

    /** 设置详情页行（设计稿 .detail-setting-row）：透明底 / 13sp 标题 / 9.5sp 说明；每项独立线性图标。分隔线由 Hair() 给出。 */
    @Composable
    fun RowCard(
        title: String,
        subtitle: String = "",
        glyph: SettingGlyphSpec,
        onClick: (() -> Unit)? = null,
        toggleChecked: Boolean? = null,
        trailing: @Composable RowScope.() -> Unit = {}
    ) {
        SettingsDetailRow(title = title, subtitle = subtitle, icon = settingIcon(glyph), iconTint = settingGlyphColor(glyph), onClick = onClick, showDivider = false, compact = settingsSub == "lyrics", toggleChecked = toggleChecked, trailing = trailing)
    }

    @Composable
    fun NavRow(title: String, subtitle: String, glyph: SettingGlyphSpec, accent: Color, action: () -> Unit) {
        MainRow(title, subtitle, glyph = glyph, onClick = action, accent = accent) { SettingsChevron() }
    }

    @Composable
    fun ThemeCheck(on: Boolean) {
        if (on) Icon(DesignLineGlyphs.Check, "当前主题", tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(19.dp))
        else Spacer(Modifier.size(19.dp))
    }

    var picker by remember { mutableStateOf<SettingsPickerRequest?>(null) }
    var stylePicker by remember { mutableStateOf<SettingsStylePickerRequest?>(null) }
    fun openPicker(kicker: String, title: String, copy: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        picker = SettingsPickerRequest(kicker, title, copy, options, selected, onPick)
    }
    fun sleepLabel(): String = sleepValueLabel(sleepMinutesFromRemain(sleepRemain))
    fun themeLabel(): String = when (normalizeThemeMode(themeMode)) { "light" -> "纯白"; "night" -> "夜间"; else -> "自适应" }
    fun applySpeed(next: Float) {
        localPlaySpeed = next
        mePagePrefs.edit().putFloat("play_speed", next).apply()
        PlayerService.setPlaybackSpeed(next)
    }
    fun applyEq(next: Int) {
        localEqPreset = next
        mePagePrefs.edit().putInt("eq_preset", next).apply()
        PlayerService.setEqualizerPreset(next)
    }

    val settingsStagger = xyz.cncnlp.soundwave.ui.common.rememberStaggerGate()
    @Composable
    fun EditorialGroup(title: String, eyebrow: String, accent: Color, topPad: androidx.compose.ui.unit.Dp = 34.dp, stagger: Int = -1, content: @Composable ColumnScope.() -> Unit) {
        // 原型 .glabel：标题 15sp/20 Manrope 700 + 8.5sp DM Mono 600 .14em 分组色眉题（r657 第二轮，原 16/7）；行间 1dp 线，首行上线、末行下线；组间 34dp。
        // r657 修复（审计第 20 项）：设置首页各组 60ms 错峰入场（stagger ≥ 0 时）。
        // r657 第三轮（用户 10-18：照原型 app.css）：一行 .glabel（中文 13sp + 灰色 DM Mono 英文）+ 白色 r24 .grp 卡；组间 28dp。
        Column(Modifier.fillMaxWidth().then(if (stagger >= 0) Modifier.staggerEnter(stagger, settingsStagger) else Modifier).padding(top = if (topPad == 34.dp) 28.dp else topPad)) {
            xyz.cncnlp.soundwave.ui.settings.SettingsGroupLabel(title, eyebrow, Modifier.padding(horizontal = 0.dp).semantics { heading() }, eyebrowColor = accent)
            xyz.cncnlp.soundwave.ui.settings.SettingsGroupCard(content)
        }
    }

    @Composable
    fun AccountIdentityCard() {
        // ACCEPT5 账号卡（按 v8 稿 `.v8acc-id` 身份行）+ accept5-listencard 页面级听歌呈现，
        // ACCEPT6 合并为一体卡（用户 2026-11-04 晚真机反馈推翻独立卡呈现：「账号卡和听歌统计
        // 以及时长不应该是一体的吗？怎么分开了」）：同一深色 hero 卡面（近黑渐变 + 漂移光晕 +
        // 白内描边 + r28）内含身份行 + 本周时长 + 七日柱，中间以一道白 10% 细线分隔、共享同一底，
        // 不是两张卡上下拼接。身份行 = 60dp 双环头像 + 衬线名 24sp + WebDAV 状态行（状态点 +
        // ACCEPT4 三态文案，凭据/同步时刻与同步面板同源）。
        // 冻结规矩不变：点身份区弹出的账号中心**对话框**内无任何统计——统计只在页面卡面呈现。
        // 点击分区（用户定版）：身份区整区 → 账号中心弹层；时长区（眉题 + 时长 + 差值胶囊整列）
        // → 播放历史；七日柱区 → 听歌统计；两听歌区各自 pressScale 热区。
        // 性能红线不变：听歌数据走 produceState + IO 后台快照（旧卡同步主线程读不得带回），
        // 先进页先出卡框、数据异步填；刷新键与旧卡一致（playSeconds/30，每 30 秒播放进度触发一刷）。
        // 右侧两态：已连接 = 玻璃 chevron 圆钮；未登录 / 待重输密码 = 珊瑚渐变「登录」胶囊，
        // 未登录卡由此自成完整形态，而非只剩一行灰副标题。
        val cardInk = Color.White
        val accent = SoundwaveDesignPalette.Accent
        val cardShape = RoundedCornerShape(28.dp)
        val webDavState = runCatching { xyz.cncnlp.soundwave.sync.WebDavCredentialStore.loadState(settingsContext) }
            .getOrDefault(xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.None)
        val connected = webDavState is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.Ready
        val webDavSubtitle = when (val st = webDavState) {
            is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.Ready -> {
                val lastSync = runCatching { xyz.cncnlp.soundwave.sync.WebDavSyncState.lastSyncAt(settingsContext) }.getOrDefault(0L)
                buildString {
                    append("WebDAV 已连接 · ")
                    append(xyz.cncnlp.soundwave.ui.common.webDavHostOf(st.credentials.url))
                    if (lastSync > 0L) {
                        append(" · ")
                        append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(lastSync)))
                        append(" 已同步")
                    }
                }
            }
            is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.PasswordUnavailable ->
                "WebDAV 待重输密码 · ${xyz.cncnlp.soundwave.ui.common.webDavHostOf(st.url)}"
            else -> "WebDAV 未登录 · 点按登录同步"
        }
        // 听歌区数据快照（原独立听歌卡口径原样迁入，见函数头注）。
        val snapshot by produceState<ListeningCardSnapshot?>(null, settingsContext, playSeconds / 30L) {
            value = withContext(Dispatchers.IO) {
                try {
                    ListeningCardSnapshot(
                        weekSec = ListenStats.weekSec(settingsContext).coerceAtLeast(0L),
                        prevWeekSec = ListenStats.previousWeekSec(settingsContext).coerceAtLeast(0L),
                        last7Days = ListenStats.last7DaySeconds(settingsContext)
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val weekSeconds = snapshot?.weekSec ?: 0L
        val hours = String.format(java.util.Locale.ROOT, "%.1f", weekSeconds / 3600f)
        val dailySeconds = listeningCardDailySeconds(snapshot?.last7Days ?: emptyList())
        Box(
            Modifier.fillMaxWidth()
                .shadow(18.dp, cardShape, ambientColor = Color(0xFF11141A).copy(alpha = 0.20f), spotColor = Color(0xFF11141A).copy(alpha = 0.28f))
                .clip(cardShape)
                // r657（v3 #11）：深色底 + 三团缓慢漂移的珊瑚 / 蓝 / 青光晕 + 玻璃高光 + 细内描边（简洁动效下静止）。
                .then(rememberPrototypeAccountBackground())
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f))), cardShape)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clickable { onLoginClick() }.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(60.dp).border(2.dp, accent.copy(alpha = 0.55f), CircleShape).padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalDesignAvatarFill provides accent) {
                            UserAvatarBadge(loggedIn = true, avatarPath = avatarPath, onClick = onLoginClick, sizeDp = 52f, elevated = false, initial = user)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            user.ifBlank { "我的音乐" }, color = cardInk,
                            fontSize = 24.sp, lineHeight = 30.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.03).em, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(7.dp).clip(CircleShape)
                                    .background(if (connected) Color(0xFF34C759) else cardInk.copy(alpha = 0.35f))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                webDavSubtitle, color = cardInk.copy(alpha = 0.6f),
                                fontSize = 11.5.sp, lineHeight = 15.sp, fontFamily = DesignFonts.Manrope, fontWeight = FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    if (connected) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(cardInk.copy(alpha = 0.10f)).border(1.dp, cardInk.copy(alpha = 0.24f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.KeyboardArrowRight, "资料详情", tint = cardInk, modifier = Modifier.size(17.dp)) }
                    } else {
                        Box(
                            Modifier.height(32.dp)
                                .pressScale(PressMotion.SCALE_ROW)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Brush.horizontalGradient(listOf(accent, Color(0xFFFF8A73))))
                                .clickable { onLoginClick() }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("登录", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
                        }
                    }
                }
                // accept6-uipolish2（用户 2026-11-04 22:21）：一体卡内身份区与聆听区之间的 1dp 分隔线删除，
                // 两区仅靠各自 padding（上区 bottom 16dp + 本区 top 16dp）自然分开，卡面其余不变。
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
                    Column(
                        Modifier.weight(1f)
                            .pressScale(PressMotion.SCALE_ROW)
                            .clickable { onOpenHistory() }
                            .semantics { contentDescription = "本周听歌时长，点按查看播放历史" }
                    ) {
                        Text("本周聆听 · THIS WEEK", color = cardInk.copy(alpha = 0.6f), fontSize = 10.sp, lineHeight = 12.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                            xyz.cncnlp.soundwave.ui.common.RollingNumber(hours, cardInk, 40.sp, DesignFonts.Newsreader, FontWeight.SemiBold, (-0.04).em)
                            Text("小时", color = cardInk.copy(alpha = 0.7f), fontSize = TypeScale.BodySmall.fontSize, lineHeight = 13.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp, bottom = 5.dp))
                        }
                        // 差值胶囊放在「xx 小时」正下方（删除前 r658b 口径）。
                        Text(
                            listeningCardDiffText(weekSeconds, snapshot?.prevWeekSec ?: 0L),
                            color = Color(0xFFFF8A73), fontSize = TypeScale.Micro.fontSize, lineHeight = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(top = 4.dp).height(20.dp).clip(RoundedCornerShape(999.dp))
                                .background(accent.copy(alpha = 0.22f)).padding(horizontal = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    val maxSeconds = dailySeconds.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                    val dayNames = remember {
                        val names = listOf("日", "一", "二", "三", "四", "五", "六")
                        val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
                        List(7) { i -> names[((todayDow - (6 - i)) % 7 + 7) % 7] }
                    }
                    Row(
                        Modifier.height(64.dp)
                            .pressScale(PressMotion.SCALE_ROW)
                            .clickable { onOpenStats() }
                            .semantics { contentDescription = "近七日听歌柱图，点按查看听歌统计" },
                        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom
                    ) {
                        dailySeconds.forEachIndexed { index, seconds ->
                            val today = index == dailySeconds.lastIndex
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier.width(9.dp).height((44f * seconds / maxSeconds).coerceAtLeast(6f).dp)
                                        .then(if (today) Modifier.shadow(8.dp, RoundedCornerShape(5.dp), ambientColor = accent.copy(alpha = 0.7f), spotColor = accent.copy(alpha = 0.7f)) else Modifier)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (today) Brush.verticalGradient(listOf(Color(0xFFFF8A73), accent)) else Brush.verticalGradient(listOf(cardInk.copy(alpha = 0.22f), cardInk.copy(alpha = 0.22f))))
                                )
                                Text(dayNames[index], color = cardInk.copy(alpha = 0.6f), fontSize = TypeScale.Badge.fontSize, lineHeight = 11.sp, fontWeight = FontWeight.Medium, fontFamily = DesignFonts.Manrope, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun EditorialValueRow(title: String, subtitle: String, glyph: SettingGlyphSpec, value: String, accent: Color, action: () -> Unit) {
        // 动作词（打开/查看/管理…）按设计稿显示 chevron；真实取值显示 .outer-setting-cycle（值 + 下划线 + 珊瑚点）。
        val navigation = value in setOf("打开", "查看", "管理", "设置", "反馈", "关于", "开始", "同步")
        MainRow(title, subtitle, glyph = glyph, onClick = action, accent = accent) {
            if (navigation) {
                SettingsChevron()
            } else if (value == "开" || value == "关") {
                // 开/关 二值项与开关行统一为设计稿 .outer-setting-toggle 胶囊。
                SettingsOuterToggle(value == "开") { action() }
            } else SettingsOuterCycle(value) { action() }
        }
    }

    /** r656：值行 —— 当前值显示在行最右侧（圆角浅底小框），点按弹出统一设计抽屉，不再进入子详情页。 */
    @Composable
    fun ValueRow(title: String, subtitle: String, glyph: SettingGlyphSpec, value: String, accent: Color, highlight: Boolean = false, action: () -> Unit) {
        MainRow(title, subtitle, glyph = glyph, onClick = action, accent = accent) {
            if (highlight) xyz.cncnlp.soundwave.ui.settings.SettingsValueChip(value = value, picker = true, accent = true) { action() }
            else SettingsOuterCycle(value) { action() }
        }
    }

    @Composable
    fun EditorialToggleRow(title: String, subtitle: String, glyph: SettingGlyphSpec, enabled: Boolean, accent: Color, action: () -> Unit) {
        MainRow(title, subtitle, glyph = glyph, onClick = action, accent = accent, toggleChecked = enabled) {
            SettingsOuterToggle(enabled) { action() }
        }
    }

    fun cycleThemeMode() {
        val next = when (normalizeThemeMode(themeMode)) {
            "light" -> "night"
            "night" -> "auto"
            else -> "light"
        }
        onThemeMode(next)
    }

    Column(Modifier.fillMaxSize()) {
        val settingsThemeIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        // 设置页右上角统一使用通知铃铛；账户入口保留在主页账户卡片。
        // 标题与首页标题同 14dp 左距（返回走系统返回 / 预测性返回手势）。
        AppPageHeader(
            // r657（v3 #11）：子页页头直接是页面名（预解析、歌词与悬浮歌词、主题与字体…），页内不再重复大标题。
            title = if (settingsSub == null) "设置" else SETTINGS_DETAIL_HEROES[settingsSub]?.second ?: "设置详情",
            loggedIn = loggedIn,
            avatarPath = avatarPath,
            onProfileClick = onLoginClick,
            showAvatar = false,
            avatarSizeDp = 42f, titleSizeSp = 31, serifTitle = true, designTitle = true, startPadding = 14.dp,
            trailing = {
                if (settingsSub == null) {
                    DesignCircleIconButton(
                        if (settingsThemeIsDark) Icons.Rounded.LightMode else DesignLineGlyphs.Moon,
                        "切换浅色或夜间主题"
                    ) { onThemeMode(if (settingsThemeIsDark) "light" else "night") }
                    Spacer(Modifier.width(9.dp))
                }
                SettingsNotificationButton { onSettingsSub("notifications") }
            }
        )
        val wideSettings = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 760
        val settingsPointer = remember { SettingsPointerTracker() }
        // r654/r655 曾在 Dock 避让之外再额外留一个迷你条高度（+62+14=76dp）；
        // accept6-uipolish2（用户 2026-11-04 22:24：底部空出太多）改回全站标准避让——
        // designPageBottomInset()（= 底栏 66 + 间距 14 + 迷你条实际高 + 舒适余量 12 + 系统导航栏）
        // 已保证末行不被迷你条/底栏遮挡，不再叠加额外一段。
        val settingsBottomInset = designPageBottomInset()
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingsPointer provides settingsPointer) {
        LazyColumn(
            Modifier.fillMaxSize().trackSettingsPointer(settingsPointer).padding(horizontal = if (wideSettings) 30.dp else 14.dp).testTag("settings_list"),
            // r655（第 17 项）：底部留白放在滚动容器的 contentPadding 上（而非末尾 Spacer 项），保证末行可滚出 Dock 区域并可点。
            contentPadding = PaddingValues(bottom = settingsBottomInset),
            userScrollEnabled = true
        ) {
            if (settingsSub == null) {
                // accept6-topbars（用户 2026-11-04 22:44）：顶栏上移后首块内容同步跟上移约 4dp，20→16dp。
                item { Spacer(Modifier.height(16.dp)) }
                item { Box(Modifier.staggerEnter(0, settingsStagger)) { AccountIdentityCard() } }
                item {
                    val mark = SettingsGroupMarks.Playback
                    // ACCEPT5 设置重分组（用户 2026-11-04 定案）：主页 6 组——播放与音质 / 音源与脚本 / 下载 / 外观 / 同步与数据 / 系统与实验；
                    // 原「过渡效果」组解散（淡入淡出并入本组、智能过渡伪行折叠进过渡弹层）；H3 实验行自跨平台搜索子页上提到「系统与实验」。行行为不变。
                    EditorialGroup("播放与音质", "Playback", mark, topPad = 24.dp, stagger = 1) {
                        EditorialToggleRow("启动 APP 自动播放", "打开应用后继续上次播放", SettingGlyphSpec.AutoPlay, autoPlayOnStart, mark) { onAutoPlay(!autoPlayOnStart) }
                        Hair()
                        ValueRow("在线播放音质", "音源不支持时自动降级", SettingGlyphSpec.OnlineQuality, USER_AUDIO_QUALITY_OPTIONS.firstOrNull { it.first == preferQ }?.second ?: preferQ, mark,
                            // r657：无损及以上档位用珊瑚 chip（原型「无损」），有损档位保持中性。
                            highlight = preferQ in setOf("master", "hires", "flac24bit", "flac")) { showPlayQuality = true }
                        Hair()
                        ValueRow("播放速度", "保持音高，切歌后保留", SettingGlyphSpec.PlaybackSpeed, playbackSpeedLabel(localPlaySpeed), mark) { showSpeedSheet = true }
                        Hair()
                        ValueRow("均衡器", "声音风格预设，立即生效", SettingGlyphSpec.Equalizer, EQ_NAMES[localEqPreset], mark) { showEqSheet = true }
                        Hair()
                        ValueRow("和其他 APP 同时播放", "音频焦点 · 通话后${audioFocus.callLabel}", SettingGlyphSpec.AudioFocus, audioFocus.mode.label, mark) { showMixSheet = true }
                        Hair()
                        ValueRow("淡入淡出", "顺序播放曲尾预切/已就绪的手动下一首；否则正常切歌", SettingGlyphSpec.Crossfade, if (transitionSeconds == 0) "关闭" else "${transitionSeconds}秒", mark) { showTransitionSettings = true }
                        Hair()
                        NavRow("预解析", if (prefetchEnabled) "提前解析下一首 · ${prefetchCount} 首 · 间隔 ${prefetchIntervalSec}s" else "已关闭", SettingGlyphSpec.Prefetch, mark) { onSettingsSub("preload") }
                        Hair()
                        ValueRow("睡眠定时", "到点后播完当前歌曲再停止", SettingGlyphSpec.SleepTimer, sleepLabel(), mark) { showSleepSheet = true }
                    }
                }
                item {
                    val mark = SettingsGroupMarks.Sources
                    EditorialGroup("音源与脚本", "Listening & sources", mark, stagger = 2) {
                        NavRow("智能换源", "失败时无缝切换可用音源", SettingGlyphSpec.SmartSource, mark) { onSmartSource() }
                        Hair()
                        NavRow("音源脚本", "导入、检测与调整优先级", SettingGlyphSpec.ScriptCatalog, mark) { onOpenScripts() }
                        Hair()
                        NavRow("跨平台搜索", "网易云 / QQ / 酷狗 / 酷我 / 咪咕 · ${if (crossPlatformSearchUi) "已开启" else "已关闭"}", SettingGlyphSpec.CrossPlatformSearch, mark) { onSettingsSub("search") }
                    }
                }
                item {
                    val mark = SettingsGroupMarks.Downloads
                    EditorialGroup("下载", "Downloads", mark, stagger = 3) {
                        NavRow("下载设置", "$downloadQ · 多线程 · 封面与歌词 · 文件名", SettingGlyphSpec.DownloadsCategory, mark) { onSettingsSub("download") }
                        Hair()
                        NavRow("查看下载", "任务 / 进度 / 失败重试", SettingGlyphSpec.OpenDownloads, mark) { onOpenDownloads() }
                    }
                }
                item {
                    val mark = SettingsGroupMarks.Appearance
                    EditorialGroup("外观", "Appearance & lyrics", mark, stagger = 4) {
                        NavRow("主题", "纯白 / 夜间 / 自适应 · 当前${themeLabel()} · 字体与动效", SettingGlyphSpec.Theme, mark) { onSettingsSub("theme") }
                        Hair()
                        EditorialValueRow("播放器界面样式", PLAYER_LAYOUT_CHOICES.joinToString(" / ") { it.second }, SettingGlyphSpec.PlayerLayout, playerLayoutLabel(playerLayout), mark) {
                            // SW-12-09：由循环切换改选择式——选项集合与取值域与原 cycle 完全一致（PLAYER_LAYOUT_CHOICES）。
                            val order = PLAYER_LAYOUT_CHOICES.map { it.first }
                            // E13：改走带预览卡的样式弹层（值域/回写逐字不变）。
                            stylePicker = SettingsStylePickerRequest(StylePickerKind.PLAYER_LAYOUT, "外观", "播放器界面样式", "选择播放器界面的呈现样式", PLAYER_LAYOUT_CHOICES.map { it.second }, order.indexOf(selectablePlayerLayout(playerLayout)).coerceAtLeast(0)) { onPlayerLayout(order[it]) }
                        }
                        Hair()
                        EditorialValueRow("详情页封面样式", "默认 / 标准 / 沉浸封面", SettingGlyphSpec.DetailCover, DetailCoverStyles.label(detailCoverStyle), mark) {
                            // SW-12-09：由循环切换改选择式——选项集合与原 DetailCoverStyles.next 的取值域一致（CHOICES）。
                            // E13：改走带预览卡的样式弹层（值域/回写逐字不变）。
                            stylePicker = SettingsStylePickerRequest(StylePickerKind.DETAIL_COVER, "外观", "详情页封面样式", "选择详情页头部的封面呈现样式", DetailCoverStyles.CHOICES.map { it.second }, DetailCoverStyles.CHOICES.indexOfFirst { it.first == DetailCoverStyles.normalize(detailCoverStyle) }.coerceAtLeast(0)) { onDetailCoverStyle(DetailCoverStyles.CHOICES[it].first) }
                        }
                        Hair()
                        NavRow("底栏与迷你条", "${if (bottomNavStyle == "capsule") "圆角胶囊" else "默认通栏"} · 磨砂", SettingGlyphSpec.BottomNavStyle, mark) { onSettingsSub("dock") }
                        Hair()
                        NavRow("歌词", "翻译 · 罗马音 · 逐字高亮 · 字号行距 · 悬浮歌词", SettingGlyphSpec.LyricsCategory, mark) { onSettingsSub("lyrics") }
                    }
                }
                item {
                    val mark = SettingsGroupMarks.Data
                    EditorialGroup("同步与数据", "Data & sync", mark, stagger = 5) {
                        NavRow("备份与恢复", "导出或恢复本机资料", SettingGlyphSpec.BackupRestore, mark) { onOpenBackup() }
                        Hair()
                        NavRow("缓存管理", cacheInfo.ifBlank { "查看缓存与空间占用" }, SettingGlyphSpec.Cache, mark) { onOpenCache() }
                        Hair()
                        NavRow("听歌统计", "热力图、歌手与歌曲排行", SettingGlyphSpec.ListeningStats, mark) { onOpenStats() }
                        Hair()
                        NavRow("播放历史", "带时间戳的播放记录", SettingGlyphSpec.PlaybackHistory, mark) { onOpenHistory() }
                        Hair()
                        NavRow("数据流向", "了解本机资料的存储与同步范围", SettingGlyphSpec.DataFlow, mark) { onSettingsSub("data_flow") }
                    }
                }
                item {
                    val mark = SettingsGroupMarks.Support
                    EditorialGroup("系统与实验", "System & labs", mark, stagger = 6) {
                        NavRow("崩溃诊断", "查看日志与最近异常", SettingGlyphSpec.CrashDiagnostics, mark) { onSettingsSub("crash") }
                        Hair()
                        NavRow("关于 APP", "版本 ${BuildConfig.VERSION_NAME} · 许可与开源声明", SettingGlyphSpec.AboutApp, mark) { onOpenAbout() }
                    }
                }
            } else {
                val hero = SETTINGS_DETAIL_HEROES[settingsSub]
                if (hero != null) item { SettingsDetailHero(hero.first, hero.second, hero.third) }
                item { Hair() }
                when (settingsSub) {
                    "preload" -> item {
                        GroupBox {
                            RowCard("启用预解析", "提前解析下一首音源", glyph = SettingGlyphSpec.PrefetchToggle, onClick = { onPrefetch(!prefetchEnabled) }, toggleChecked = prefetchEnabled) {
                                RightBtn(if (prefetchEnabled) "开" else "关", selected = prefetchEnabled) { onPrefetch(!prefetchEnabled) }
                            }
                            Hair()
                            Box(Modifier.graphicsLayer { alpha = if (prefetchEnabled) 1f else 0.45f }) {
                                RowCard("预解析数量", "可设置 1–5 首：先缓存直链，仅最近下一首进双缓冲", glyph = SettingGlyphSpec.PrefetchCount, onClick = if (prefetchEnabled) ({ openPicker("播放与音质", "预解析数量", "可设置 1–5 首，仅预取最近队列", (1..5).map { "$it 首" }, prefetchCount - 1) { onPrefetchCount(it + 1) } }) else null) {
                                    SettingsValueChip("${prefetchCount} 首") { if (prefetchEnabled) openPicker("播放与音质", "预解析数量", "可设置 1–5 首，仅预取最近队列", (1..5).map { "$it 首" }, prefetchCount - 1) { onPrefetchCount(it + 1) } }
                                }
                            }
                            Hair()
                            Box(Modifier.graphicsLayer { alpha = if (prefetchEnabled) 1f else 0.45f }) {
                                val intervals = listOf(3, 6, 9, 12, 15)
                                RowCard("请求间隔", "播放稳定后再开始预取", glyph = SettingGlyphSpec.RequestInterval, onClick = if (prefetchEnabled) ({ openPicker("播放与音质", "请求间隔", "播放稳定后开始预取", intervals.map { "$it 秒" }, intervals.indexOf(prefetchIntervalSec).coerceAtLeast(0)) { onPrefetchInterval(intervals[it]) } }) else null) {
                                    SettingsValueChip("${prefetchIntervalSec} 秒") { if (prefetchEnabled) openPicker("播放与音质", "请求间隔", "播放稳定后开始预取", intervals.map { "$it 秒" }, intervals.indexOf(prefetchIntervalSec).coerceAtLeast(0)) { onPrefetchInterval(intervals[it]) } }
                                }
                            }
                        }
                    }
                    "lyrics" -> {
                        item {
                            LyricsLivePreviewCard(
                                fontSp = lyricFontSp.toFloat(), gapDp = lyricGapDp.toFloat(),
                                showTranslation = lyricShowTranslation, showRomanization = lyricShowRomanization
                            )
                        }
                        item {
                            Column(Modifier.fillMaxWidth()) {
                                LyricsGroupHeader("歌词阅读", "Lyrics")
                                GroupBox {
                                RowCard("显示歌词翻译", "在歌词行下方显示现有译文", glyph = SettingGlyphSpec.LyricsTranslation, onClick = { onLyricShowTranslation(!lyricShowTranslation) }, toggleChecked = lyricShowTranslation) {
                                    RightBtn(if (lyricShowTranslation) "开" else "关", selected = lyricShowTranslation) { onLyricShowTranslation(!lyricShowTranslation) }
                                }
                                Hair()
                                RowCard("显示歌词罗马音", "在歌词行下方显示现有罗马音", glyph = SettingGlyphSpec.LyricsRomanization, onClick = { onLyricShowRomanization(!lyricShowRomanization) }, toggleChecked = lyricShowRomanization) {
                                    RightBtn(if (lyricShowRomanization) "开" else "关", selected = lyricShowRomanization) { onLyricShowRomanization(!lyricShowRomanization) }
                                }
                                Hair()
                                PlayerLyricsSettingSlider(
                                    spec = PlayerLyricsSettingsModel.font(lyricFontSp),
                                    fg = MaterialTheme.colorScheme.onSurface,
                                    leading = { SettingGlyph(SettingGlyphSpec.LyricFontSize, settingGlyphColor(SettingGlyphSpec.LyricFontSize)) },
                                    onValueChange = onLyricFont,
                                )
                                Hair()
                                PlayerLyricsSettingSlider(
                                    spec = PlayerLyricsSettingsModel.lineHeight(lyricGapDp),
                                    fg = MaterialTheme.colorScheme.onSurface,
                                    leading = { SettingGlyph(SettingGlyphSpec.LyricLineSpacing, settingGlyphColor(SettingGlyphSpec.LyricLineSpacing)) },
                                    onValueChange = onLyricGap,
                                )
                                Hair()
                                PlayerLyricOffsetStepper(
                                    offsetMs = lyricOffsetMs,
                                    fg = MaterialTheme.colorScheme.onSurface,
                                    leading = { SettingGlyph(SettingGlyphSpec.LyricOffset, settingGlyphColor(SettingGlyphSpec.LyricOffset)) },
                                    onDelta = { delta -> onLyricOffset(PlayerLyricsSettingsModel.stepOffset(lyricOffsetMs, delta)) },
                                )
                                Hair()
                                RowCard("逐字歌词高亮", "真实词轴随字推进；普通行级歌词按播放进度刷亮", glyph = SettingGlyphSpec.WordHighlight, onClick = { onLyricWordKaraoke(!lyricWordKaraoke) }, toggleChecked = lyricWordKaraoke) {
                                    RightBtn(if (lyricWordKaraoke) "已开启" else "已关闭", selected = lyricWordKaraoke) { onLyricWordKaraoke(!lyricWordKaraoke) }
                                }
                                Hair()
                                RowCard("当前词轻微上浮", "独立于逐字高亮 · 遵循系统减少动态效果", glyph = SettingGlyphSpec.CurrentWordLift, onClick = {
                                    setLyricWordLiftEnabled(mePageCtx, !lyricWordLiftEnabled, settingsOwner)
                                }, toggleChecked = lyricWordLiftEnabled) {
                                    RightBtn(if (lyricWordLiftEnabled) "已开启" else "已关闭", selected = lyricWordLiftEnabled) {
                                        setLyricWordLiftEnabled(mePageCtx, !lyricWordLiftEnabled, settingsOwner)
                                    }
                                }
                                Hair()
                                // SW-12-09：歌词字体由循环切换改选择式——选项集合与原 nextFontFamily 取值域一致（FONT_FAMILY_CHOICES）。
                                RowCard("歌词字体", fontLabel(lyricFontPref), glyph = SettingGlyphSpec.LyricFontFamily, onClick = { openPicker("外观", "歌词字体", "选择歌词使用的字体", FONT_FAMILY_CHOICES.map { fontLabel(it) }, FONT_FAMILY_CHOICES.indexOf(lyricFontPref).coerceAtLeast(0)) { onFontFamily("lyric", FONT_FAMILY_CHOICES[it]) } }) {
                                    SettingsValueChip(fontLabel(lyricFontPref)) { openPicker("外观", "歌词字体", "选择歌词使用的字体", FONT_FAMILY_CHOICES.map { fontLabel(it) }, FONT_FAMILY_CHOICES.indexOf(lyricFontPref).coerceAtLeast(0)) { onFontFamily("lyric", FONT_FAMILY_CHOICES[it]) } }
                                }
                            }
                            LyricsGroupHeader("悬浮歌词", "Floating")
                            GroupBox {
                                RowCard("悬浮歌词", "仅其它界面显示 · 点按改色 · 长按锁定/解锁", glyph = SettingGlyphSpec.FloatingLyrics, onClick = { onLyricFloat(!lyricFloatEnabled) }, toggleChecked = lyricFloatEnabled) {
                                    RightBtn(if (lyricFloatEnabled) "已开启" else "已关闭", selected = lyricFloatEnabled) { onLyricFloat(!lyricFloatEnabled) }
                                }
                                Hair()
                                val floatLocked = remember {
                                    mutableStateOf(ContextPreferences.open(settingsContext, "soundwave", 0, settingsOwner).getBoolean("lyric_float_locked", false))
                                }
                                RowCard("解锁悬浮歌词", if (floatLocked.value) "当前已锁定 · 点此解锁并可拖动" else "未锁定 · 可拖动，长按锁定", glyph = SettingGlyphSpec.UnlockFloatingLyrics, onClick = {
                                    LyricFloat.setLocked(settingsContext, false)
                                    floatLocked.value = false
                                }) {
                                    RightBtn(if (floatLocked.value) "解锁" else "已开", selected = !floatLocked.value) {
                                        LyricFloat.setLocked(settingsContext, false)
                                        floatLocked.value = false
                                    }
                                }
                                Hair()
                                val floatLines = remember {
                                    mutableStateOf(ContextPreferences.open(settingsContext, "soundwave", 0, settingsOwner).getInt("lyric_float_lines", 1))
                                }
                                // ACCEPT5（缺一收敛）：悬浮组由点按循环改选择式——选项集合与原 cycle 取值域（单行/双行）完全一致，写回同一 LyricFloat 样式。
                                fun applyFloatLines(v: Int) {
                                    floatLines.value = v
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(maxLines = v))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatLinesPicker() = openPicker("外观", "悬浮歌词行数", "选择悬浮歌词显示一行还是两行", listOf("单行", "双行"), if (floatLines.value >= 2) 1 else 0) { applyFloatLines(it + 1) }
                                RowCard("悬浮歌词行数", if (floatLines.value >= 2) "双行（当前+下一句）" else "单行", glyph = SettingGlyphSpec.FloatingLyricLines, onClick = { openFloatLinesPicker() }) {
                                    SettingsValueChip(if (floatLines.value >= 2) "双行" else "单行") { openFloatLinesPicker() }
                                }
                                Hair()
                                val floatSp = remember {
                                    mutableStateOf(LyricFloat.loadStyle(settingsContext).fontSp)
                                }
                                val floatSpSteps = listOf(12f, 14f, 16f, 18f, 20f, 22f, 24f, 26f, 28f)
                                fun applyFloatSp(v: Float) {
                                    floatSp.value = v
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(fontSp = v))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatSpPicker() = openPicker("外观", "悬浮歌词字号", "应用外悬浮窗字号", floatSpSteps.map { "${it.toInt()}sp" }, floatSpSteps.indexOfFirst { it >= floatSp.value }.let { if (it < 0) floatSpSteps.lastIndex else it }) { applyFloatSp(floatSpSteps[it]) }
                                RowCard("悬浮歌词字号", "应用外悬浮窗字号", glyph = SettingGlyphSpec.FloatingLyricFont, onClick = { openFloatSpPicker() }) {
                                    SettingsValueChip("${floatSp.value.toInt()}sp") { openFloatSpPicker() }
                                }
                                Hair()
                                val floatBg = remember {
                                    mutableStateOf(LyricFloat.loadStyle(settingsContext).bgAlpha)
                                }
                                val floatBgSteps = listOf(0f, 0.15f, 0.35f, 0.55f, 0.75f)
                                fun applyFloatBg(v: Float) {
                                    floatBg.value = v
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(bgAlpha = v))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatBgPicker() = openPicker("外观", "悬浮背景透明度", "0% 为全透明底，越高越实", floatBgSteps.map { "${(it * 100).toInt()}%" }, floatBgSteps.indices.minByOrNull { kotlin.math.abs(floatBgSteps[it] - floatBg.value) } ?: 0) { applyFloatBg(floatBgSteps[it]) }
                                RowCard("悬浮背景透明度", "0%=全透明底 · 越高越实", glyph = SettingGlyphSpec.FloatingBackgroundOpacity, onClick = { openFloatBgPicker() }) {
                                    SettingsValueChip("${(floatBg.value * 100).toInt()}%") { openFloatBgPicker() }
                                }
                                Hair()
                                val floatTextA = remember {
                                    mutableStateOf(LyricFloat.loadStyle(settingsContext).textAlpha)
                                }
                                val floatTextSteps = listOf(0.40f, 0.55f, 0.75f, 0.90f, 1.0f)
                                fun applyFloatTextA(v: Float) {
                                    floatTextA.value = v
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(textAlpha = v))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatTextPicker() = openPicker("外观", "悬浮文字透明度", "歌词字本身的不透明度", floatTextSteps.map { "${(it * 100).toInt()}%" }, floatTextSteps.indices.minByOrNull { kotlin.math.abs(floatTextSteps[it] - floatTextA.value) } ?: 0) { applyFloatTextA(floatTextSteps[it]) }
                                RowCard("悬浮文字透明度", "歌词字本身的不透明度", glyph = SettingGlyphSpec.FloatingTextOpacity, onClick = { openFloatTextPicker() }) {
                                    SettingsValueChip("${(floatTextA.value * 100).toInt()}%") { openFloatTextPicker() }
                                }
                                Hair()
                                val floatW = remember {
                                    mutableStateOf(LyricFloat.loadStyle(settingsContext).widthDp)
                                }
                                val floatWSteps = listOf(0, 200, 280, 360)
                                fun applyFloatW(v: Int) {
                                    floatW.value = v
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(widthDp = v))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatWPicker() = openPicker("外观", "悬浮显示宽度", "选择悬浮窗的显示宽度", listOf("自适应", "200dp", "280dp", "360dp"), floatWSteps.indexOf(floatW.value).let { if (it < 0) 0 else it }) { applyFloatW(floatWSteps[it]) }
                                RowCard("悬浮显示宽度", if (floatW.value <= 0) "随内容自适应" else "${floatW.value}dp 固定宽", glyph = SettingGlyphSpec.FloatingWidth, onClick = { openFloatWPicker() }) {
                                    SettingsValueChip(if (floatW.value <= 0) "自适应" else "${floatW.value}dp") { openFloatWPicker() }
                                }
                                Hair()
                                val floatColor = remember {
                                    mutableStateOf(LyricFloat.loadStyle(settingsContext).playedColor)
                                }
                                fun floatColorName(c: Int): String = when (c) {
                                    android.graphics.Color.rgb(42, 120, 255) -> "蓝"
                                    android.graphics.Color.WHITE -> "白"
                                    android.graphics.Color.rgb(255, 224, 102) -> "黄"
                                    android.graphics.Color.rgb(120, 220, 255) -> "青"
                                    android.graphics.Color.rgb(255, 140, 180) -> "粉"
                                    android.graphics.Color.rgb(140, 255, 170) -> "绿"
                                    android.graphics.Color.rgb(255, 170, 100) -> "橙"
                                    android.graphics.Color.rgb(200, 170, 255) -> "紫"
                                    else -> "当前颜色"
                                }
                                fun applyFloatColor(c: Int) {
                                    floatColor.value = c
                                    val dim = android.graphics.Color.argb(160, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                                    LyricFloat.saveStyle(settingsContext, LyricFloat.loadStyle(settingsContext).copy(playedColor = c, dimColor = dim))
                                    LyricFloat.applyLive(settingsContext)
                                }
                                fun openFloatColorPicker() = openPicker("外观", "悬浮文字颜色", "已唱色；未唱自动半透明同色", LyricFloat.COLOR_PRESETS.map { floatColorName(it) }, LyricFloat.COLOR_PRESETS.indexOfFirst { it == floatColor.value }.let { if (it < 0) 0 else it }) { applyFloatColor(LyricFloat.COLOR_PRESETS[it]) }
                                RowCard("悬浮文字颜色", "已唱色；未唱自动半透明同色", glyph = SettingGlyphSpec.FloatingTextColor, onClick = { openFloatColorPicker() }) {
                                    SettingsValueChip(floatColorName(floatColor.value)) { openFloatColorPicker() }
                                }
                                }
                            }
                        }
                    }
                    "theme" -> item {
                        GroupBox {
                            val mode = normalizeThemeMode(themeMode)
                            RowCard("纯白", "固定浅色调色板", glyph = SettingGlyphSpec.ThemeLight, onClick = { onThemeMode("light") }) { ThemeCheck(mode == "light") }
                            Hair()
                            RowCard("夜间", "统一深色底色与浅色文字", glyph = SettingGlyphSpec.ThemeNight, onClick = { onThemeMode("night") }) { ThemeCheck(mode == "night") }
                            Hair()
                            RowCard("自适应", "跟随系统浅色 / 深色外观", glyph = SettingGlyphSpec.ThemeAuto, onClick = { onThemeMode("auto") }) { ThemeCheck(mode != "light" && mode != "night") }
                            Hair()
                            // SW-12-09：全局字体由循环切换改选择式——选项集合与原 nextFontFamily 取值域一致（FONT_FAMILY_CHOICES）。
                            RowCard("全局字体", "应用界面字体", glyph = SettingGlyphSpec.GlobalFont, onClick = { openPicker("外观", "全局字体", "选择应用界面使用的字体", FONT_FAMILY_CHOICES.map { fontLabel(it) }, FONT_FAMILY_CHOICES.indexOf(globalFontPref).coerceAtLeast(0)) { onFontFamily("global", FONT_FAMILY_CHOICES[it]) } }) {
                                SettingsValueChip(fontLabel(globalFontPref)) { openPicker("外观", "全局字体", "选择应用界面使用的字体", FONT_FAMILY_CHOICES.map { fontLabel(it) }, FONT_FAMILY_CHOICES.indexOf(globalFontPref).coerceAtLeast(0)) { onFontFamily("global", FONT_FAMILY_CHOICES[it]) } }
                            }
                            Hair()
                            // r657：透明磨砂背景 + 磨砂随封面取色（默认关闭 = 原实色外观；取色只在磨砂打开时可切换）。
                            RowCard("透明磨砂背景", "页面与默认播放器底层改为封面模糊磨砂", glyph = SettingGlyphSpec.FrostedBackground, onClick = { FrostedPrefs.setPage(mePagePrefs, !FrostedPrefs.pageFrosted) }, toggleChecked = FrostedPrefs.pageFrosted) {
                                RightBtn(if (FrostedPrefs.pageFrosted) "开" else "关", selected = FrostedPrefs.pageFrosted) { FrostedPrefs.setPage(mePagePrefs, !FrostedPrefs.pageFrosted) }
                            }
                            Hair()
                            Box(Modifier.graphicsLayer { alpha = if (FrostedPrefs.pageFrosted) 1f else 0.45f }) {
                                RowCard("磨砂随封面取色", if (FrostedPrefs.pageFrosted) "光晕与背景取自当前歌曲封面" else "需先开启透明磨砂背景", glyph = SettingGlyphSpec.FrostedTint, onClick = { if (FrostedPrefs.pageFrosted) FrostedPrefs.setTint(mePagePrefs, !FrostedPrefs.coverTint) }, toggleChecked = if (FrostedPrefs.pageFrosted) FrostedPrefs.coverTint else null) {
                                    RightBtn(if (FrostedPrefs.coverTint && FrostedPrefs.pageFrosted) "开" else "关", selected = FrostedPrefs.coverTint && FrostedPrefs.pageFrosted) { if (FrostedPrefs.pageFrosted) FrostedPrefs.setTint(mePagePrefs, !FrostedPrefs.coverTint) }
                                }
                            }
                            Hair()
                            RowCard("减少动效", "关闭非必要装饰动画；系统级减少动效也会同时生效", glyph = SettingGlyphSpec.ReduceMotion, onClick = { updateAppReduceMotion(!appReduceMotionEnabled) }, toggleChecked = appReduceMotionEnabled) {
                                RightBtn(if (appReduceMotionEnabled) "开" else "关", selected = appReduceMotionEnabled) { updateAppReduceMotion(!appReduceMotionEnabled) }
                            }
                        }
                    }
                    "dock" -> item {
                        GroupBox {
                            // E12（验收二轮）：样式选择照设计稿该页——双选项预览卡 + 珊瑚选中描边/标题；
                            // 两个样式值与底栏/迷你条联动语义逐字不变（setDockStyle 同一写回路径）。
                            DockStylePreviewSelector(current = bottomNavStyle, onSelect = { setDockStyle(it) })
                            Hair()
                            // r657：底栏与迷你条磨砂（独立开关，默认关闭 = 实色；开启 = 半透明磨砂，不透明度 ≥ 80%）。
                            RowCard("底栏与迷你条磨砂", "默认 / 圆角胶囊两种样式都生效；关闭时为实色", glyph = SettingGlyphSpec.BottomNavStyle, onClick = { FrostedPrefs.setDock(mePagePrefs, !FrostedPrefs.dockFrosted) }, toggleChecked = FrostedPrefs.dockFrosted) {
                                RightBtn(if (FrostedPrefs.dockFrosted) "开" else "关", selected = FrostedPrefs.dockFrosted) { FrostedPrefs.setDock(mePagePrefs, !FrostedPrefs.dockFrosted) }
                            }
                            // r657 第三轮（用户 10-18）：「下滑迷你条隐藏底栏」「底栏自动隐藏」及等待时长已移除。
                        }
                    }
                    "search" -> item {
                        GroupBox {
                            RowCard("启用跨平台搜索", "网易云、QQ、酷狗、酷我、咪咕同时查询并去重合并", glyph = SettingGlyphSpec.CrossSearchDetail, onClick = { onCrossPlatformSearch(!crossPlatformSearchUi) }, toggleChecked = crossPlatformSearchUi) {
                                RightBtn(if (crossPlatformSearchUi) "开" else "关", selected = crossPlatformSearchUi) { onCrossPlatformSearch(!crossPlatformSearchUi) }
                            }
                            Hair()
                            RowCard("脚本 HTTP 令牌/密钥保护", "拦截 lx.request 明文发送令牌/密钥头；默认关闭", glyph = SettingGlyphSpec.CredentialProtection, onClick = { setScriptHttpCredentialGuard(!scriptHttpCredentialGuardEnabled) }, toggleChecked = scriptHttpCredentialGuardEnabled) {
                                RightBtn(if (scriptHttpCredentialGuardEnabled) "开" else "关", selected = scriptHttpCredentialGuardEnabled) { setScriptHttpCredentialGuard(!scriptHttpCredentialGuardEnabled) }
                            }
                        }
                    }
                    "download" -> {
                        item {
                            GroupBox {
                                RowCard("下载音质设置", "可按平台单独设置", glyph = SettingGlyphSpec.DownloadQuality, onClick = { showDownloadQuality = true }) { RightBtn(downloadQ) { showDownloadQuality = true } }
                                Hair()
                                RowCard("多线程下载", "同一文件分段并发", glyph = SettingGlyphSpec.MultiThreadDownload, onClick = { onDownloadMultiThread(!downloadMultiThread) }, toggleChecked = downloadMultiThread) { RightBtn(if (downloadMultiThread) "开" else "关", selected = downloadMultiThread) { onDownloadMultiThread(!downloadMultiThread) } }
                                Hair()
                                RowCard("保存封面", "下载时写入封面图", glyph = SettingGlyphSpec.SaveCover, onClick = { onSaveExtCover(!saveExtCover) }, toggleChecked = saveExtCover) { RightBtn(if (saveExtCover) "开" else "关", selected = saveExtCover) { onSaveExtCover(!saveExtCover) } }
                                Hair()
                                RowCard("保存外挂歌词", "下载时另存 lrc", glyph = SettingGlyphSpec.SaveLyrics, onClick = { onSaveExtLyric(!saveExtLyric) }, toggleChecked = saveExtLyric) { RightBtn(if (saveExtLyric) "开" else "关", selected = saveExtLyric) { onSaveExtLyric(!saveExtLyric) } }
                                Hair()
                                val nameTpl = remember {
                                    mutableStateOf(ContextPreferences.open(settingsContext, "soundwave", 0, settingsOwner).getString("download_name_tpl", "title_singer") ?: "title_singer")
                                }
                                // SW-12-09：下载文件名由循环切换改选择式——选项集合与原 cycle 的取值域完全一致，写回同一偏好键。
                                val nameTplOrder = listOf("title_singer", "title_only", "album_title")
                                fun applyNameTpl(index: Int) {
                                    nameTpl.value = nameTplOrder[index]
                                    ContextPreferences.open(settingsContext, "soundwave", 0, settingsOwner).edit().putString("download_name_tpl", nameTpl.value).apply()
                                }
                                fun nameTplLabel(v: String) = when (v) {
                                    "title_singer" -> "歌名-歌手"
                                    "title_only" -> "仅歌名"
                                    "album_title" -> "专辑 - 歌名"
                                    else -> "歌名-歌手"
                                }
                                fun openNameTplPicker() = openPicker("下载", "下载文件名", "选择下载文件的命名方式", nameTplOrder.map { nameTplLabel(it) }, nameTplOrder.indexOf(nameTpl.value).coerceAtLeast(0)) { applyNameTpl(it) }
                                RowCard("下载文件名", nameTplLabel(nameTpl.value), glyph = SettingGlyphSpec.DownloadFilename, onClick = { openNameTplPicker() }) {
                                    SettingsValueChip(nameTplLabel(nameTpl.value)) { openNameTplPicker() }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        }
    }
    picker?.let { req ->
        SettingsPickerSheet(
            kicker = req.kicker, title = req.title, copy = req.copy, options = req.options, selectedIndex = req.selected,
            onPick = { i -> req.onPick(i) }, onDismiss = { picker = null }
        )
    }

    stylePicker?.let { req ->
        xyz.cncnlp.soundwave.ui.settings.SettingsStylePreviewSheet(
            kicker = req.kicker, title = req.title, copy = req.copy, options = req.options, selectedIndex = req.selected,
            onPick = { i -> req.onPick(i) }, onDismiss = { stylePicker = null },
            cardPreview = { i -> StylePreviewArt(req.kind, i) }
        )
    }

    if (showMixSheet) AudioFocusSheet(audioFocus, onChange = { applyAudioFocus(it) }, onDismiss = { showMixSheet = false })
    if (showTransitionSettings) PlaybackTransitionSettingsSheet(onDismiss = { showTransitionSettings = false })
    if (showSpeedSheet) PlaybackSpeedSheet(localPlaySpeed, onPick = { applySpeed(it) }, onDismiss = { showSpeedSheet = false })
    if (showEqSheet) EqualizerSheet(EQ_NAMES, localEqPreset, onPick = { applyEq(it) }, onDismiss = { showEqSheet = false })
    if (showCrossSearchSheet) ChoiceSheet("跨平台搜索", listOf("false" to "关闭", "true" to "开启"), crossPlatformSearchUi.toString(), { onCrossPlatformSearch(it == "true") }, { showCrossSearchSheet = false })
    if (showPlayQuality) PlaybackQualitySheet(preferQ, onPick = { onQuality(it) }, onDismiss = { showPlayQuality = false })
    // r658h（用户 10-20）：下载音质与「在线播放音质」同一套设计抽屉（眉题 + 说明 + 两列音质卡）。
    if (showDownloadQuality) DownloadQualitySettingSheet(downloadQ, onPick = { onDownloadQuality(it) }, onDismiss = { showDownloadQuality = false })
    if (showSleepSheet) SleepTimerSheet(remainMs = sleepRemain, onApply = { onSleep(it) }, onDismiss = { showSleepSheet = false })
    if (showCacheSheet) {
        val opts = listOf(
            "100" to "100 MB", "200" to "200 MB", "500" to "500 MB",
            "1024" to "1 GB", "2048" to "2 GB", "5120" to "5 GB", "0" to "不限"
        )
        val limNow = try { CacheManager.maxBytes(bugCtx) } catch (_: Exception) { 500L * 1024 * 1024 }
        val curKey = when {
            limNow >= Long.MAX_VALUE / 8 -> "0"
            limNow >= 5L * 1024 * 1024 * 1024 -> "5120"
            limNow >= 2L * 1024 * 1024 * 1024 -> "2048"
            limNow >= 1024L * 1024 * 1024 -> "1024"
            limNow >= 500L * 1024 * 1024 -> "500"
            limNow >= 200L * 1024 * 1024 -> "200"
            limNow >= 100L * 1024 * 1024 -> "100"
            else -> "0"
        }
        ChoiceSheet("缓存管理", opts, curKey, { key ->
            val bytes = when (key) {
                "100" -> 100L * 1024 * 1024
                "200" -> 200L * 1024 * 1024
                "500" -> 500L * 1024 * 1024
                "1024" -> 1024L * 1024 * 1024
                "2048" -> 2L * 1024 * 1024 * 1024
                "5120" -> 5L * 1024 * 1024 * 1024
                else -> Long.MAX_VALUE / 4
            }
            onCacheMax(bytes)
        }, { showCacheSheet = false }, extraActionLabel = "清空缓存（含解密媒体缓存）", onExtraAction = {
            onClearCache()
        })
    }
}
