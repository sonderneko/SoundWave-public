package xyz.cncnlp.soundwave

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.ui.motion.MotionTokens
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.TypeScale
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.download.DownloadTaskState
import xyz.cncnlp.soundwave.download.DownloadTaskStateMachine
import xyz.cncnlp.soundwave.ui.common.DesignSheetRow
import xyz.cncnlp.soundwave.ui.common.StateBlock
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetCopy

/*
 * r656（设置第 9 项）：「查看下载」页重做。
 * 概览卡（已完成数 + 进行中/失败 + 四枚操作胶囊）→ 状态筛选（全部 / 下载中 / 已完成 / 失败）→
 * 失败原因分组（一键重试）→ 任务行（封面 / 歌名歌手 / 状态与来源 / 进度条 / 右侧圆形操作钮）。
 * 长按进入多选；目录、删除、清空历史均为统一设计抽屉（DesignSheetDialog）。
 */

internal val DOWNLOAD_ACTIVE_STATUSES = DownloadTaskStateMachine.activePersistedValues

private fun DownloadItem.state(): DownloadTaskState? = DownloadTaskState.fromPersisted(status)

internal enum class DownloadFilter(val label: String) { ALL("全部"), ACTIVE("下载中"), DONE("已完成"), FAILED("失败") }

internal fun DownloadFilter.accepts(item: DownloadItem): Boolean = when (this) {
    DownloadFilter.ALL -> true
    DownloadFilter.ACTIVE -> DownloadTaskStateMachine.isActive(item.status) || item.state() == DownloadTaskState.PAUSED
    DownloadFilter.DONE -> item.state() == DownloadTaskState.COMPLETED
    DownloadFilter.FAILED -> item.state() == DownloadTaskState.FAILED
}

internal fun downloadStatusLine(d: DownloadItem): String {
    // ACCEPT5（本地直读/下载盖章）：已完成行的音质以文件实测为准——旧记录的
    // 存储标签可能来自脚本自报档（master 名而 128k 实），路径有扩展名线索
    // 时按实测档显示；无实测证据（SAF 无扩展名等）才回退存储值。
    val q = if (d.state() == DownloadTaskState.COMPLETED) {
        xyz.cncnlp.soundwave.download.AudioQualityEvidence.measuredFileQuality(d.path, d.size, d.song.duration)
            ?: d.quality.ifEmpty { d.song.actualQuality }
    } else {
        d.quality.ifEmpty { d.song.actualQuality }
    }.ifEmpty { "—" }
    val sz = when {
        d.size >= 1024 * 1024 -> "%.1fMB".format(d.size / 1024f / 1024f)
        d.size >= 1024 -> "${d.size / 1024}KB"
        else -> "${d.size}B"
    }
    return when (d.state()) {
        DownloadTaskState.QUEUED -> "等待下载…"
        DownloadTaskState.RESOLVING -> "取链中…"
        DownloadTaskState.RETRY_WAIT -> "等待重试…"
        DownloadTaskState.VERIFYING -> "校验中…"
        DownloadTaskState.PAUSED -> "已暂停"
        DownloadTaskState.DOWNLOADING -> "下载中 ${(d.progress * 100).toInt()}%"
        DownloadTaskState.FAILED -> "失败：" + d.error.ifEmpty { "未知错误" }
        DownloadTaskState.CANCELLED -> "已取消"
        DownloadTaskState.COMPLETED, null -> {
            // r658d：不再显示脚本 id（一串哈希，用户看不懂），只留平台。
            val via = d.platform.ifBlank { d.song.source }.takeIf { it.isNotBlank() }?.let(::platformName).orEmpty()
            if (d.state() == DownloadTaskState.COMPLETED && d.error.isNotBlank()) "已完成；${d.error}"
            else listOf(via, q, sz).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsPage(
    downloads: List<DownloadItem>,
    onMoveDownload: (DownloadItem, Int) -> Unit,
    onPauseDownload: (DownloadItem) -> Unit,
    onResumeDownload: (DownloadItem) -> Unit,
    onDeleteDownloads: (List<DownloadItem>) -> Unit,
    onDeleteDownloadRecords: (List<DownloadItem>) -> Unit,
    onPauseDownloads: (List<DownloadItem>) -> Unit,
    onResumeDownloads: (List<DownloadItem>) -> Unit,
    onRedownloads: (List<DownloadItem>) -> Unit,
    onPlayDownload: (DownloadItem) -> Unit,
    onOpenDownloadDir: () -> Unit,
    onPickDownloadDir: () -> Unit,
    onClearDownloadHistory: (Boolean) -> Unit,
    onRedownload: (DownloadItem) -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.ALL) }
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showDirSheet by remember { mutableStateOf(false) }
    var showClearSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    // 多选模式下系统返回 = 退出多选，而不是退回设置页
    BackHandler(enabled = batchMode) { batchMode = false; selected = emptySet() }
    val colors = MaterialTheme.colorScheme
    val line = designLineColor()
    val listReduceMotion = MotionPolicy.rememberReduceMotion()
    val visible = downloads.filter { filter.accepts(it) }
    fun exitBatch() { batchMode = false; selected = emptySet() }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = dockContentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                if (batchMode) DownloadBatchBar(
                    count = selected.size,
                    allSelected = selected.size == visible.size && visible.isNotEmpty(),
                    onToggleAll = { selected = if (selected.size == visible.size) emptySet() else visible.map { it.path }.toSet() },
                    onResume = { onResumeDownloads(downloads.filter { it.path in selected && it.state() == DownloadTaskState.PAUSED }); exitBatch() },
                    onPause = { onPauseDownloads(downloads.filter { it.path in selected && DownloadTaskStateMachine.isActive(it.status) }); exitBatch() },
                    onRetry = { onRedownloads(downloads.filter { it.path in selected && it.state() == DownloadTaskState.FAILED }); exitBatch() },
                    onDelete = { if (selected.isNotEmpty()) pendingDelete = selected },
                    onDone = { exitBatch() }
                ) else DownloadOverviewCard(
                    downloads = downloads,
                    onResumeAll = { onResumeDownloads(downloads.filter { it.state() == DownloadTaskState.PAUSED }) },
                    onRetryAll = { onRedownloads(downloads.filter { it.state() == DownloadTaskState.FAILED }) },
                    onFolder = { showDirSheet = true },
                    onSelect = { batchMode = true; selected = emptySet() }
                )
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadFilter.values().forEach { f ->
                        val count = downloads.count { f.accepts(it) }
                        val on = f == filter
                        Row(
                            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(999.dp))
                                .background(if (on) colors.onSurface else Color.Transparent)
                                .border(1.dp, if (on) colors.onSurface else line, RoundedCornerShape(999.dp))
                                .clickable { filter = f }
                                .semantics { contentDescription = "${f.label} $count"; role = Role.Tab }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(f.label, color = if (on) colors.surface else colors.onSurface, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.width(6.dp))
                            Text("$count", color = if (on) colors.surface.copy(alpha = 0.7f) else colors.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono)
                        }
                    }
                }
            }
            val failedGroups = LocalStore.groupDownloadFailures(downloads)
            if (!batchMode && failedGroups.isNotEmpty() && (filter == DownloadFilter.ALL || filter == DownloadFilter.FAILED)) {
                item {
                    val shape = RoundedCornerShape(20.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(SoundwaveDanger.copy(alpha = 0.06f)).border(1.dp, SoundwaveDanger.copy(alpha = 0.25f), shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("失败原因", color = SoundwaveDanger, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em)
                        failedGroups.forEach { (label, group) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = colors.onSurface, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${group.size} 首", color = colors.onSurfaceVariant, fontSize = TypeScale.Micro.fontSize)
                                Spacer(Modifier.width(10.dp))
                                DownloadPill("全部重试", DesignLineGlyphs.Repeat) { onRedownloads(group) }
                            }
                        }
                    }
                }
            }
            // r657（v3 #12）：「下载任务」行（多选入口在这一行）。
            if (!batchMode && visible.isNotEmpty()) item {
                DownloadTaskHeader(visible.size) { batchMode = true; selected = emptySet() }
            }
            if (visible.isEmpty()) {
                item { StateBlock(DesignLineGlyphs.Download, if (downloads.isEmpty()) "暂无下载" else "该分类下暂无任务") }
            }
            items(visible, key = { it.taskId.ifBlank { it.path } }) { d ->
                val index = downloads.indexOf(d)
                // SW-12-12：增删时邻行平滑让位、插入淡入（key = taskId，空时回退文件路径，均为数据稳定 id）。
                Box(swAnimateItem(listReduceMotion)) {
                DownloadTaskRow(
                    d = d,
                    batchMode = batchMode,
                    checked = d.path in selected,
                    canUp = index > 0,
                    canDown = index < downloads.lastIndex,
                    onTap = {
                        if (batchMode) selected = if (d.path in selected) selected - d.path else selected + d.path
                        else if (d.state() == DownloadTaskState.COMPLETED) onPlayDownload(d)
                    },
                    onLongPress = { batchMode = true; selected = selected + d.path },
                    onPause = { onPauseDownload(d) },
                    onResume = { onResumeDownload(d) },
                    onRetry = { onRedownload(d) },
                    onDelete = { pendingDelete = setOf(d.path) },
                    onMove = { delta -> onMoveDownload(d, delta) }
                )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ACCEPT4（审计 §9 C 类）：本页三张 sheet 的行改走退场句柄——动作立即执行，状态清除推迟到退场后（onDismiss）。
    if (pendingDelete.isNotEmpty()) {
        val pending = downloads.filter { it.path in pendingDelete }
        DesignSheetDialog(onDismiss = { pendingDelete = emptySet() }) {
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { pendingDelete = emptySet() }
            DesignSheetHeader("Delete downloads", "删除下载")
            SettingsSheetCopy("已选择 ${pending.size} 项。可只删除下载记录，或连同已下载的歌曲文件一起删除；删除后会先暂存，可在提示中撤销。")
            Spacer(Modifier.height(18.dp))
            DesignSheetRow(DesignSheetGlyphs.Delete, "删除文件与记录", "删除后可撤销", danger = true) {
                onDeleteDownloads(pending); exitBatch(); dismiss()
            }
            DesignSheetRow(DesignSheetGlyphs.Remove, "仅删除记录", "保留文件") {
                onDeleteDownloadRecords(pending); exitBatch(); dismiss()
            }
            DesignSheetRow(DesignLineGlyphs.X, "取消") { dismiss() }
        }
    }
    if (showDirSheet) {
        DesignSheetDialog(onDismiss = { showDirSheet = false }) {
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showDirSheet = false }
            DesignSheetHeader("Download folder", "下载目录")
            SettingsSheetCopy("歌曲、封面与外挂歌词保存在所选目录；更换目录只影响之后的下载。")
            Spacer(Modifier.height(18.dp))
            DesignSheetRow(DesignLineGlyphs.Library, "查看目录", "打开文件管理") { onOpenDownloadDir(); dismiss() }
            DesignSheetRow(DesignLineGlyphs.Plus, "选择目录", "系统目录授权") { onPickDownloadDir(); dismiss() }
            // ACCEPT6 dialogfrost：危险区上方空带（全站动作表统一语言）。
            Spacer(Modifier.height(ThemeTokens.Sheet.DangerZoneTopGap))
            DesignSheetRow(DesignLineGlyphs.History, "清空下载历史", "${downloads.size} 条", danger = true, enabled = downloads.isNotEmpty()) {
                showClearSheet = true; dismiss()
            }
        }
    }
    if (showClearSheet) {
        DesignSheetDialog(onDismiss = { showClearSheet = false }) {
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearSheet = false }
            DesignSheetHeader("Clear history", "清空下载历史")
            SettingsSheetCopy("选择删除方式：连同歌曲文件本体一起删除，或仅删除历史记录。")
            Spacer(Modifier.height(18.dp))
            DesignSheetRow(DesignSheetGlyphs.Delete, "连同文件删除", "不可恢复", danger = true) { onClearDownloadHistory(true); dismiss() }
            DesignSheetRow(DesignSheetGlyphs.Remove, "仅删记录", "保留文件") { onClearDownloadHistory(false); dismiss() }
            DesignSheetRow(DesignLineGlyphs.X, "取消") { dismiss() }
        }
    }
}

@Composable
private fun DownloadOverviewCard(downloads: List<DownloadItem>, onResumeAll: () -> Unit, onRetryAll: () -> Unit, onFolder: () -> Unit, onSelect: () -> Unit) {
    // r657（v3 #12）：无卡片进度环摘要（总进度 %、已完成 N / 总数、各状态彩点）+ 三枚线框操作；多选移到「下载任务」行。
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val total = downloads.size
    val done = downloads.count { it.state() == DownloadTaskState.COMPLETED }
    val active = downloads.count { DownloadTaskStateMachine.isActive(it.status) }
    val paused = downloads.count { it.state() == DownloadTaskState.PAUSED }
    val failed = downloads.count { it.state() == DownloadTaskState.FAILED }
    val progress = if (total == 0) 0f else (done + downloads.filter { it.state() == DownloadTaskState.DOWNLOADING }.sumOf { it.progress.coerceIn(0f, 1f).toDouble() }.toFloat()) / total
    val ringTrack = colors.onSurface.copy(alpha = 0.08f)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp).semantics { contentDescription = "总进度 ${(progress * 100).toInt()}%" }, contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 7.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(ringTrack, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    if (progress > 0f) drawArc(
                        accent, -90f, 360f * progress, false, topLeft, arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(progress * 100).toInt()}", color = colors.onSurface, fontSize = TypeScale.Title.fontSize, lineHeight = 26.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.Medium)
                    Text("%", color = colors.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(bottom = 4.dp, start = 1.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("DOWNLOADS", color = accent, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    // r657 第二轮：原型 .donen data-roll —— 已完成数字滚动。
                    xyz.cncnlp.soundwave.ui.common.RollingNumber("$done", colors.onSurface, 30.sp, DesignFonts.Newsreader, FontWeight.Medium, modifier = Modifier.padding(bottom = 2.dp))
                    Text(" / $total 已完成", color = colors.onSurfaceVariant, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    DownloadStatusDot(accent, "下载中 $active")
                    DownloadStatusDot(Color(0xFFE6953B), "暂停 $paused")
                    DownloadStatusDot(SoundwaveDanger, "失败 $failed")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadOverviewAction(DesignLineGlyphs.Play, "全部继续", paused > 0, Modifier.weight(1f), onResumeAll)
            DownloadOverviewAction(DesignLineGlyphs.Repeat, "全部重试", failed > 0, Modifier.weight(1f), onRetryAll)
            DownloadOverviewAction(DesignLineGlyphs.Library, "下载目录", true, Modifier.weight(1f), onFolder)
        }
    }
}

@Composable
private fun DownloadStatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, maxLines = 1)
    }
}

@Composable
private fun DownloadTaskHeader(count: Int, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("下载任务", color = colors.onBackground, fontSize = TypeScale.Headline.fontSize, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, modifier = Modifier.semantics { heading() })
        Text("$count", color = colors.onSurfaceVariant, fontSize = TypeScale.Micro.fontSize, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(start = 6.dp).weight(1f))
        DownloadCircleButton(DesignLineGlyphs.Select, "多选", colors.onSurface, onClick = onSelect)
    }
}

@Composable
private fun DownloadOverviewAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.35f
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, designLineColor(), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = colors.onSurface.copy(alpha = alpha), modifier = Modifier.size(16.dp))
        Text(label, color = colors.onSurface.copy(alpha = alpha), fontSize = TypeScale.Micro.fontSize, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun DownloadBatchBar(
    count: Int, allSelected: Boolean, onToggleAll: () -> Unit, onResume: () -> Unit, onPause: () -> Unit,
    onRetry: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.onSurface).padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $count 项", color = colors.surface, fontSize = TypeScale.Headline.fontSize, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(if (allSelected) "取消全选" else "全选", color = colors.surface, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onToggleAll).padding(horizontal = 10.dp, vertical = 8.dp))
            Text("完成", color = SoundwaveDesignPalette.Accent, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onDone).padding(horizontal = 10.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(DesignLineGlyphs.Play, "继续", onResume), Triple(DesignLineGlyphs.Pause, "暂停", onPause),
                Triple(DesignLineGlyphs.Repeat, "重试", onRetry), Triple(DesignSheetGlyphs.Delete, "删除", onDelete)
            ).forEach { (icon, label, action) ->
                val danger = label == "删除"
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface.copy(alpha = 0.10f))
                        .clickable(enabled = count > 0, onClick = action)
                        .semantics { contentDescription = "${label}所选"; role = Role.Button }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    val tint = (if (danger) Color(0xFFFF8A7A) else colors.surface).copy(alpha = if (count > 0) 1f else 0.4f)
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadPill(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(999.dp)).border(1.dp, designLineColor(), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DownloadCircleButton(icon: ImageVector, description: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(16.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadTaskRow(
    d: DownloadItem, batchMode: Boolean, checked: Boolean, canUp: Boolean, canDown: Boolean,
    onTap: () -> Unit, onLongPress: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onRetry: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val failed = d.state() == DownloadTaskState.FAILED
    val lineColor = designLineColor()
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    val rowInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // r657（v3 #12）：无卡片任务行（底部 1dp 线），细进度线，无底操作图标，右侧小号上 / 下箭头调整队列。
    Column(
        // ACCEPT5-press（稿 §3）：下载行改行档 pressScale（原走全局默认 Card 档 0.965 一刀切）。
        Modifier.fillMaxWidth().pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_ROW).clip(RoundedCornerShape(12.dp))
            // 验收二轮 E1（用户裁定）：选中行不铺任何背景色（B 波的 primaryContainer 整行实铺
            // 在根主题下呈淡紫，已废止）；选中只由复选框勾选态表达，分隔线与未选中行完全一致、恒绘制。
            .combinedClickable(interactionSource = rowInteraction, indication = null, onClick = onTap, onLongClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); onLongPress() })
            .drawBehind { drawRect(lineColor, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 2.dp, end = 0.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (batchMode) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (checked) accent else Color.Transparent)
                        .border(1.5.dp, if (checked) accent else colors.onSurfaceVariant.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { xyz.cncnlp.soundwave.ui.common.AnimatedCheckMark(checked, DesignLineGlyphs.Check, Color.White, 13.dp, contentDescription = "已选择") }
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                // 统一封面占位垫底（与全 App 同体系），图片成功后盖住。
                // ACCEPT5 收编：此前传 Note glyph 走字形圆盘分支、并非母题；去掉 glyph 即黑胶母题。
                DefaultCoverPlaceholder(
                    Modifier.fillMaxSize(),
                    contentDescription = if (d.song.cover.isBlank()) "歌曲" else null
                )
                if (d.song.cover.isNotBlank()) AsyncImage(model = d.song.cover, contentDescription = "下载歌曲封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.song.name, color = colors.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.song.singer.isNotBlank()) Text(d.song.singer, color = colors.onSurfaceVariant, fontSize = TypeScale.Micro.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                Text(
                    downloadStatusLine(d),
                    color = when { failed -> SoundwaveDanger; d.state() == DownloadTaskState.COMPLETED && d.error.isNotBlank() -> Color(0xFFB26A00); d.state() == DownloadTaskState.COMPLETED -> colors.onSurfaceVariant; else -> accent },
                    fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
            }
            if (!batchMode) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        DownloadTaskStateMachine.isActive(d.status) -> {
                            DownloadCircleButton(DesignLineGlyphs.Pause, "暂停下载", colors.onSurface, onClick = onPause)
                            DownloadCircleButton(DesignLineGlyphs.X, "取消下载", colors.onSurface, onClick = onDelete)
                        }
                        d.state() == DownloadTaskState.PAUSED -> {
                            DownloadCircleButton(DesignLineGlyphs.Play, "继续下载", accent, onClick = onResume)
                            DownloadCircleButton(DesignLineGlyphs.X, "取消下载", colors.onSurface, onClick = onDelete)
                        }
                        failed -> {
                            DownloadCircleButton(DesignLineGlyphs.Repeat, "重试", accent, onClick = onRetry)
                            DownloadCircleButton(DesignSheetGlyphs.Delete, "删除", SoundwaveDanger, onClick = onDelete)
                        }
                        else -> DownloadCircleButton(DesignLineGlyphs.Play, "播放下载", colors.onSurface, onClick = onTap)
                    }
                }
            }
            // 只重排尚未完成的任务；已完成文件保持时间顺序，避免误解为“播放顺序”。
            if (!batchMode && d.state() != DownloadTaskState.COMPLETED) {
                Column(Modifier.padding(start = 2.dp)) {
                    DownloadQueueArrow("上移下载", canUp, flip = false) { onMove(-1) }
                    DownloadQueueArrow("下移下载", canDown, flip = true) { onMove(1) }
                }
            }
        }
        if (d.status == "downloading") {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, start = 64.dp).height(2.dp).clip(RoundedCornerShape(999.dp)).background(accent.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(d.progress.coerceIn(0f, 1f)).height(2.dp).clip(RoundedCornerShape(999.dp)).background(accent))
            }
        }
    }
}

@Composable
private fun DownloadQueueArrow(description: String, enabled: Boolean, flip: Boolean, onClick: () -> Unit) {
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    Box(
        // ACCEPT5-press（稿 §4）：上下移一格 = drag 类触感（稿 mvup/mvdn 同例）。
        Modifier.size(width = 28.dp, height = 22.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.dragTick(pressHapticView); onClick() })
            .semantics { contentDescription = description; role = Role.Button }
            .graphicsLayer { if (flip) rotationZ = 180f },
        contentAlignment = Alignment.Center
    ) { Icon(DesignLineGlyphs.Up, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.25f), modifier = Modifier.size(12.dp)) }

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
