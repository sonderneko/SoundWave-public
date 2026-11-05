@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.cncnlp.soundwave
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.common.DesignConfirmSheet
import xyz.cncnlp.soundwave.ui.common.dragLift
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.StateBlock
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
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
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
import xyz.cncnlp.soundwave.ui.settings.SettingsSwitch
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailHero
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
// width/height via layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import kotlin.math.abs
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScriptsPage(
    scripts: List<Script>,
    onChange: (List<Script>) -> Unit,
    onImportCode: (String) -> Unit,
    onImportUrl: (String) -> Unit,
    onImportFile: () -> Unit,
    onMove: (String, Int) -> Unit,
    /** 批量检测：进度回调 (0..1) → 完成回调（报告文本，主线程） */
    onRefresh: (((Float) -> Unit), (String) -> Unit) -> Unit = { _, done -> done("未接入批量检测") },
    /** 真实探测：脚本 → 回调报告文本（主线程） */
    onProbe: (Script, (String) -> Unit) -> Unit = { sc, cb ->
        cb(if (sc.code.isBlank()) "无代码 · 需重新导入" else "待真实检测 · v${sc.version.ifEmpty { "-" }}")
    },
    onSmartSort: (ScriptProbeCache.SortMode) -> Unit = {}
) {
    var showImportCode by remember { mutableStateOf(false) }
    var showImportUrl by remember { mutableStateOf(false) }
    var importCode by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var detectMsg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sortMode by remember { mutableStateOf(ScriptProbeCache.SortMode.BALANCED) }
    // 长按后手指上下拖动连续排序；r656 起卡片底部另有上/下箭头（位于「检测」左侧）。
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragAcc by remember { mutableStateOf(0f) }
    val primary = MaterialTheme.colorScheme.primary
    val onBg = MaterialTheme.colorScheme.onBackground
    val surface = MaterialTheme.colorScheme.surface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val danger = MaterialTheme.colorScheme.error
    val listReduceMotion = MotionPolicy.rememberReduceMotion()
    val scriptContext = LocalContext.current
    var scheduledProbeEnabled by remember { mutableStateOf(ScriptProbeScheduler.enabled(scriptContext)) }
    var scheduledProbeMinutes by remember { mutableStateOf(ScriptProbeScheduler.intervalMinutes(scriptContext)) }
    // r656：批量检测 / 单脚本检测 / 详情 / 定时检测均为统一设计抽屉。
    var showBatchDetect by remember { mutableStateOf(false) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchResult by remember { mutableStateOf("") }
    var batchProgress by remember { mutableStateOf(0f) }
    var showIntervalSheet by remember { mutableStateOf(false) }
    var checkScriptId by remember { mutableStateOf<String?>(null) }
    // r658c S：单个检测——每个脚本独立的检测中状态与报告；多个单检排队进入同一探测通道，互不禁用。
    var runningIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var checkReports by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var detailScriptId by remember { mutableStateOf<String?>(null) }

    fun staticHint(sc: Script): String {
        if (sc.code.isBlank()) return "无代码 · 需重新导入"
        if (!sc.enabled) return "已关闭 · 打开开关后可播放"
        ScriptProbeCache.summaryLine(sc)?.let { return it }
        return "已导入可尝试播放 · 尚未检测（检测不通过不影响播放）"
    }

    LaunchedEffect(scripts.map { it.id }.joinToString()) {
        // 回填上次真实检测缓存；不自动全量打爆
        detectMsg = scripts.associate { it.id to (detectMsg[it.id] ?: staticHint(it)) }
    }
    val darkUi = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val lineColor = if (darkUi) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    val accent = SoundwaveDesignPalette.Accent
    fun cycleSort() {
        // Compose 状态写入不是同步读回：必须把计算出的 next 直接交给排序，不能传旧模式。
        val nextMode = when (sortMode) {
            ScriptProbeCache.SortMode.BALANCED -> ScriptProbeCache.SortMode.QUALITY
            ScriptProbeCache.SortMode.QUALITY -> ScriptProbeCache.SortMode.SPEED
            ScriptProbeCache.SortMode.SPEED -> ScriptProbeCache.SortMode.BALANCED
        }
        sortMode = nextMode
        onSmartSort(nextMode)
    }
    fun startBatchDetect() {
        batchRunning = true
        showBatchDetect = true
        batchProgress = 0f
        batchResult = ""
        onRefresh({ frac ->
            batchProgress = frac.coerceIn(0f, 1f)
        }, { report ->
            batchRunning = false
            batchProgress = 1f
            batchResult = report
            detectMsg = scripts.associate { it.id to staticHint(it) }
            showBatchDetect = true
        })
    }
    fun startProbe(sc: Script) {
        if (sc.id in runningIds || batchRunning) { checkScriptId = sc.id; return }
        val queued = runningIds.isNotEmpty()
        runningIds = runningIds + sc.id
        checkScriptId = sc.id
        checkReports = checkReports - sc.id
        detectMsg = detectMsg + (sc.id to if (queued) "排队检测中…（前一个脚本完成后开始）" else "检测中…完整遍历声明的平台与音质")
        onProbe(sc) { report ->
            runningIds = runningIds - sc.id
            detectMsg = detectMsg + (sc.id to (ScriptProbeCache.summaryLine(sc) ?: report.lineSequence().firstOrNull().orEmpty()))
            checkReports = checkReports + (sc.id to report)
        }
    }
    if (showIntervalSheet) {
        ScriptScheduleCustomSheet(
            enabled = scheduledProbeEnabled,
            minutes = scheduledProbeMinutes,
            onSave = { on, m ->
                // 归一化后的生效值回填：显示值 = 保存值 = Scheduler 实际值
                val normalized = ScriptProbeScheduler.normalizeIntervalMinutes(m)
                scheduledProbeMinutes = normalized
                scheduledProbeEnabled = on
                ScriptProbeScheduler.setIntervalMinutes(scriptContext, normalized)
                ScriptProbeScheduler.setEnabled(scriptContext, on)
            },
            onDismiss = { showIntervalSheet = false }
        )
    }
    if (showBatchDetect) {
        ScriptBatchSheet(scripts, batchRunning, batchProgress, batchResult) { showBatchDetect = false }
    }
    checkScriptId?.let { id ->
        scripts.firstOrNull { it.id == id }?.let { sc ->
            ScriptCheckSheet(sc, running = id in runningIds, rawReport = checkReports[id].orEmpty()) { checkScriptId = null }
        }
    }
    detailScriptId?.let { id ->
        val index = scripts.indexOfFirst { it.id == id }
        if (index >= 0) ScriptDetailSheet(
            scripts[index], index, running = id in runningIds, canProbe = !batchRunning && scripts[index].code.isNotBlank(),
            onProbe = { startProbe(scripts[index]) }
        ) { detailScriptId = null }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = dockContentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // r656：页头已是「音源脚本」，不再重复设计 hero 标题；直接从脚本库概览卡开始。
        item {
            val enabledCount = scripts.count { it.enabled && it.code.isNotBlank() }
            // N 波：已停服计数与行健康同源——只读现行检测报告（ScriptHealth 派生），
            // 键随脚本集合与最近一轮检测文案变化重算；纯统计呈现，不触发任何检测。
            val stoppedCount = remember(scripts.map { it.id }.joinToString(","), detectMsg) {
                scripts.count { sc ->
                    val report = ScriptProbeCache.latestReport(sc.id)
                    ScriptProbeCache.ScriptHealth.of(report?.takeIf { ScriptProbeCache.isReportCurrent(it, sc) }).state ==
                        ScriptProbeCache.ScriptHealth.State.STOPPED
                }
            }
            // 验收 B 波（用户指示）：概览区去掉深色大卡片底，改扁平页眉（页面底 + 主题墨色），与全 App 轻量分组一致。
            // N 波：页眉改排版式统计细条（总数 / 可取链 / 已停服，衬线数字 + 竖发丝线分隔），
            // 三个页头动作改无框排版钮（竖线分隔），下方一条发丝线收口。
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)
            ) {
                Text("SCRIPT LIBRARY", color = accent, fontSize = 9.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    ScriptStatColumn("${scripts.size}", "总数", onBg, Modifier.weight(1f), startPad = 0.dp)
                    Box(Modifier.width(1.dp).height(46.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
                    ScriptStatColumn("$enabledCount", "可取链", onBg, Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(46.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
                    ScriptStatColumn("$stoppedCount", "已停服", if (stoppedCount > 0) Color(0xFFD23C2E) else onBg, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                    ScriptHeroPill(Icons.AutoMirrored.Outlined.Sort, "智能排序", sortMode.label, Modifier.weight(1f), contentDescription = "按${sortMode.label}排序", onLongClick = { onSmartSort(sortMode) }) { cycleSort() }
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(36.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
                    ScriptHeroPill(Icons.Outlined.Refresh, "批量检测", if (batchRunning) "检测中 ${(batchProgress * 100).toInt()}%" else "全部脚本", Modifier.weight(1f), contentDescription = "刷新并批量检测", enabled = runningIds.isEmpty() || batchRunning) {
                        if (batchRunning) showBatchDetect = true else startBatchDetect()
                    }
                    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(36.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
                    ScriptHeroPill(Icons.Outlined.Schedule, "定时检测", if (scheduledProbeEnabled) "每${scheduledProbeMinutes}分钟" else "已关闭", Modifier.weight(1f)) { showIntervalSheet = true }
                }
                Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
            }
        }
        item {
            // ACCEPT4（第 10 项重做）：导入区补分组头（与智能换源/歌词设置的分组语言统一），导入条下发丝线收口。
            Column(Modifier.fillMaxWidth()) {
                ScriptGroupHeader("导入脚本", "Import")
                ScriptImportStrip(onFile = onImportFile, onLink = { showImportUrl = true }, onCode = { showImportCode = true })
                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
            }
        }
        if (scripts.isEmpty()) {
            item { StateBlock(DesignLineGlyphs.Lyrics, "还没有脚本", "请导入本地文件或链接") }
        } else {
            item {
                // ACCEPT4（第 10 项重做）：列表头改分组头 + 右侧计数（原 16sp 裸标题与页面分组语言不一致）。
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScriptGroupHeader("脚本列表", "Scripts", Modifier.weight(1f))
                    Text("${scripts.size} 个 · 优先级自上而下 · 长按拖动排序", color = secondary, fontSize = 10.sp)
                }
            }
        }
        items(scripts.size, key = { scripts[it].id }) { index ->
            val sc = scripts[index]
            // SW-12-02（G 波）：拖拽排序起止触感。
            val hapticView = LocalView.current
            val name = sc.name.ifEmpty { sc.id }
            val selected = draggingId == sc.id
            val probing = sc.id in runningIds || (batchRunning && sc.enabled && sc.code.isNotBlank())
            val statusText = when {
                selected -> "拖动中"
                batchRunning && sc.id !in runningIds && probing -> "批量检测中…"
                else -> detectMsg[sc.id] ?: staticHint(sc)
            }
            val ok = sc.enabled && sc.code.isNotBlank()
            // ACCEPT4（第 10 项重做）：健康判定提前到行头之前——状态行并入主列（见下），判定口径不变。
                // J 波（对齐设计稿状态点三色语义）：未检测/报告过期=琥珀、检测通过（有有效链接）=绿、
                // 检测无有效链接=红；判定只读 ScriptProbeCache 的现行报告，不新增检测行为。
                // 检测中沿用珊瑚（与转圈同色）。此前只有「可用=珊瑚/否则灰」两色，语义全无。
                // latestReport 每次调用都全量解析报告 JSON，故按 (脚本, 状态文案) 记忆化——
                // 检测完成时 detectMsg 更新、statusText 随之变化，判定正好失效重算。
                // J-健康度：同一记忆化判定顺带派生健康摘要（可用·平均耗时 / 已停服），渲染在状态行尾；
                // 数据只读现行检测报告（ScriptHealth.of），不新增检测、不写存储、不影响解析。
                // ScriptVerdictStore 在 speed-h1 分支（合并列车在本分支之前），不在本基线——
                // 合入后「已停服」以裁决存储为准，本处短语扫描仅为显示侧近似。
                val probeUi = remember(sc.id, statusText) {
                    val report = ScriptProbeCache.latestReport(sc.id)
                    val current = report?.takeIf { ScriptProbeCache.isReportCurrent(it, sc) }
                    val verdict = when {
                        current == null -> 0
                        current.valid.isNotEmpty() -> 1
                        // ACCEPT5：跳过/中断的定论记录单列一档——不是「未检测」（没结论），
                        // 也不是「检测未通过」（检了没过），文案由 summaryLine 给出跳过原因。
                        current.skippedReason.isNotBlank() -> 3
                        else -> 2
                    }
                    verdict to ScriptProbeCache.ScriptHealth.of(current)
                }
                val probeVerdict = probeUi.first
                val scriptHealth = probeUi.second
                val statusDotColor = when {
                    probing -> accent
                    probeVerdict == 1 -> Color(0xFF3FA35B)
                    probeVerdict == 2 -> Color(0xFFD23C2E)
                    else -> Color(0xFFE6953B)
                }
            Column(
                // SW-12-12：拖动换位 / 增删时平滑让位与淡入（key = 脚本 id，数据稳定 id）。
                // 验收 B 波：去掉整行卡片壳（底色/描边），仅拖动中保留 6% 强调淡染作状态提示；手势块原样不动。
                swAnimateItem(listReduceMotion)
                    .fillMaxWidth()
                    // ACCEPT6-motiondraft（稿 .lift）：拖动中行浮起大柔影（共享 dragLift；淡染仍为下方 background 的事）。
                    .dragLift(selected, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) accent.copy(alpha = 0.06f) else Color.Transparent)
                    .pointerInput(sc.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = sc.id
                                dragAcc = 0f
                                xyz.cncnlp.soundwave.ui.common.AppHaptics.dragStart(hapticView)
                            },
                            onDragEnd = { draggingId = null; dragAcc = 0f; xyz.cncnlp.soundwave.ui.common.AppHaptics.dragEnd(hapticView) },
                            onDragCancel = { draggingId = null; dragAcc = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragAcc += dragAmount.y
                                val pitch = size.height + 10.dp.toPx()
                                val thr = pitch * 0.27f
                                while (dragAcc <= -thr) {
                                    dragAcc += thr
                                    onMove(sc.id, -1)
                                    xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(hapticView)
                                }
                                while (dragAcc >= thr) {
                                    dragAcc -= thr
                                    onMove(sc.id, +1)
                                    xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(hapticView)
                                }
                            }
                        )
                    }
                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // N 波：索引改排版式——DmMono 裸数字（可取链=墨色，否则次色），下方
                    // 14×2dp 青柠短线表「链路可用」语义；去掉 32dp 青柠圆片（大色块）。
                    Column(Modifier.width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%02d".format(index + 1), color = if (ok) onBg else secondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                        Box(Modifier.padding(top = 4.dp).width(14.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (ok) SoundwaveDesignPalette.Lime else Color.Transparent))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = if (sc.enabled) onBg else onBg.copy(alpha = 0.45f), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sourceMeta = listOfNotNull(
                            sc.author.takeIf { it.isNotBlank() }?.let { "作者 $it" },
                            sc.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            sc.description.takeIf { it.isNotBlank() },
                            sc.id.takeIf { sc.author.isBlank() && sc.version.isBlank() && sc.description.isBlank() }?.let { "ID $it" }
                        ).joinToString(" · ")
                        Text(sourceMeta, color = secondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        // ACCEPT4（第 10 项重做）：状态行并入主列作第三行（健康点 + 状态文案 + 健康摘要），
                        // 与名称/元信息构成一条完整的编辑式条目，不再单独占一行缩进排布；判定与文案口径不变。
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (probing) xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner(12.dp)
                            else Box(Modifier.size(6.dp).clip(CircleShape).background(statusDotColor))
                            Spacer(Modifier.width(7.dp))
                            Text(statusText, color = if (selected) primary else secondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp, modifier = Modifier.weight(1f, fill = false))
                            if (!probing && (scriptHealth.state == ScriptProbeCache.ScriptHealth.State.USABLE || scriptHealth.state == ScriptProbeCache.ScriptHealth.State.STOPPED || scriptHealth.state == ScriptProbeCache.ScriptHealth.State.SKIPPED)) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    scriptHealth.label,
                                    color = when (scriptHealth.state) {
                                        ScriptProbeCache.ScriptHealth.State.STOPPED -> Color(0xFFD23C2E)
                                        ScriptProbeCache.ScriptHealth.State.SKIPPED -> Color(0xFFE6953B)
                                        else -> Color(0xFF3FA35B)
                                    },
                                    fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.semantics { contentDescription = if (sc.enabled) "关闭 $name" else "启用 $name" }) {
                        SettingsSwitch(checked = sc.enabled, onClick = {
                            if (!sc.enabled && sc.code.contains("http://")) {
                                println("[SoundWave] 脚本「$name」源码含 http:// 明文取链端点，存在同网窃听风险（F-06，仅提示不阻断）")
                            }
                            onChange(scripts.map { if (it.id == sc.id) it.copy(enabled = !it.enabled) else it })
                        })
                    }
                }
                // J 波：操作行按设计稿无顶线（删除状态行与操作行之间的第二条分隔线，行内只保留行底一条）。
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    // 单个检测：只对本脚本完整检测；检测中点按打开本脚本进度/报告，批量检测期间不可单检。
                    ScriptActionChip(Icons.Outlined.Science, if (sc.id in runningIds) "检测中" else "检测", onBg, lineColor, "检测 $name", enabled = !batchRunning) { startProbe(sc) }
                    Spacer(Modifier.width(6.dp))
                    ScriptActionChip(Icons.Outlined.Info, "详情", onBg, lineColor, "详情 $name") { detailScriptId = sc.id }
                    Spacer(Modifier.width(6.dp))
                    ScriptActionChip(Icons.Outlined.Delete, "删除", danger, lineColor, "删除 $name") { deleteId = sc.id }
                }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(lineColor)))
            }
        }
    }
    if (showImportCode) {
        ScriptImportInputSheet(
            "Import code", "粘贴脚本代码", "粘贴 LX 兼容自定义源脚本的完整源码；导入后默认启用，可在列表中调整优先级。",
            importCode, true, { importCode = it },
            // ACCEPT4（§9）：导入动作只执行导入与清输入，关闭由抽屉退场句柄 + onDismiss 承担。
            onImport = { if (importCode.isNotBlank()) { onImportCode(importCode); importCode = "" } },
            onDismiss = { showImportCode = false }
        )
    }
    if (showImportUrl) {
        ScriptImportInputSheet(
            "Import link", "链接导入脚本", "输入脚本的 https 下载地址；仅下载脚本文本，读取大小有上限。",
            importUrl, false, { importUrl = it },
            onImport = { if (importUrl.isNotBlank()) { onImportUrl(importUrl.trim()); importUrl = "" } },
            onDismiss = { showImportUrl = false }
        )
    }
    if (deleteId != null) {
        DesignConfirmSheet(
            title = "删除脚本",
            body = "确定删除该脚本？",
            confirmLabel = "删除",
            cancelLabel = "取消",
            danger = true,
            // ACCEPT5（退出动画普查 T27）：删除为同步动作，确认后走退场（dismissOnConfirm）；
            // deleteId 只由退场后的 onDismiss 清（此前 onConfirm 同步清、弹层瞬时消失）。
            dismissOnConfirm = true,
            onConfirm = {
                onChange(scripts.filter { it.id != deleteId })
            },
            onDismiss = { deleteId = null }
        )
    }
}


/**
 * ACCEPT4（第 10 项，音源脚本页自主重做）：分组标题头——中文粗体 + 珊瑚 DmMono 英文眉题，
 * 与歌词设置分组头、智能换源各组同语言（本页原先只有页眉统计与裸列表标题，分组层级缺失）。
 */
@Composable
private fun ScriptGroupHeader(cn: String, en: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(cn, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope)
        Spacer(Modifier.width(8.dp))
        Text(
            en.uppercase(), color = designAccentTextColor(), fontSize = 8.5.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
            fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp, modifier = Modifier.padding(bottom = 2.dp)
        )
    }
}

/** 脚本库页眉统计细条的一列（N 波）：衬线大数字 + DmMono 小标签，列间由调用方竖发丝线分隔。 */
@Composable
private fun ScriptStatColumn(value: String, label: String, valueColor: Color, modifier: Modifier = Modifier, startPad: androidx.compose.ui.unit.Dp = 14.dp) {
    Column(modifier.padding(start = startPad)) {
        Text(value, color = valueColor, fontSize = 28.sp, lineHeight = 30.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Newsreader, fontWeight = FontWeight.Medium)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 脚本库概览区内的三个页头动作（验收 B 波为线框胶囊；N 波去框改排版钮：图标 + 标题 + 值，
 * 列间竖发丝线分隔；点按/长按语义与回调与此前完全一致）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptHeroPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, modifier: Modifier = Modifier,
    contentDescription: String? = null, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit
) {
    val pressHapticView = LocalView.current
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick?.let { cb -> { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); cb() } })
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.92f else 0.4f), modifier = Modifier.size(16.dp))
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
    }
}

/** 脚本行底部的小线框操作钮（检测 / 详情 / 删除）。J 波按设计稿改无底线框：高 28dp、字 11.5sp、删除为危险色 35% 线框（此前为 36dp 带磨砂填充的胶囊 chip）。 */
@Composable
private fun ScriptActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, line: Color, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val danger = label == "删除"
    val borderColor = if (danger) tint.copy(alpha = 0.35f) else line
    Row(
        Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = tint.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * SW-12-12：主列表增删 / 换位动画。淡入淡出走 MotionTokens 效果弹簧（1.0 / 1600），
 * 让位走审计规格 spring(0.9, 700)；「减少动效」时返回空 Modifier（无淡入、无位移），
 * 与仓内 MotionPolicy 判定一致。key 由各 items 调用点的数据稳定 id 提供，本修饰符不涉及 key。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.swAnimateItem(reduceMotion: Boolean): Modifier =
    if (reduceMotion) Modifier
    else Modifier.animateItem(
        fadeInSpec = MotionTokens.Spring.EffectsSpec,
        placementSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
        fadeOutSpec = MotionTokens.Spring.EffectsSpec
    )
