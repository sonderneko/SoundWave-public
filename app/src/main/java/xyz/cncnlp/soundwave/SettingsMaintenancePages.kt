package xyz.cncnlp.soundwave

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.settings.SettingsActionButton
import xyz.cncnlp.soundwave.ui.settings.SettingsButtonKind
import xyz.cncnlp.soundwave.ui.settings.SettingsCard
import xyz.cncnlp.soundwave.ui.settings.SettingsConfirmSheet
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailHero
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailRow
import xyz.cncnlp.soundwave.ui.settings.SettingsEmptyNote
import xyz.cncnlp.soundwave.ui.settings.SettingsHairline
import xyz.cncnlp.soundwave.ui.settings.SettingsKeyValue
import xyz.cncnlp.soundwave.ui.settings.SettingsMonoBlock
import xyz.cncnlp.soundwave.ui.settings.SettingsPageGlyphs
import xyz.cncnlp.soundwave.ui.settings.SettingsPageList
import xyz.cncnlp.soundwave.ui.settings.SettingsPageSection
import xyz.cncnlp.soundwave.ui.settings.SettingsPickerSheet
import xyz.cncnlp.soundwave.ui.settings.SettingsSegmented
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetButton
import xyz.cncnlp.soundwave.ui.settings.SettingsStatTile
import xyz.cncnlp.soundwave.ui.settings.SettingsStatusDot
import xyz.cncnlp.soundwave.ui.settings.SettingsValueChip
import xyz.cncnlp.soundwave.ui.settings.settingsMutedColor

/*
 * r656 item 11 / 14：缓存管理、播放取链报告、崩溃诊断按设计稿 settings-detail 语言重做
 * （SettingsDetailHero → 数据卡 → 透明设置行 / 等宽日志块），数据源与清理语义保持不变。
 */

internal data class MaintenancePageThemeColors(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val error: Color
)

internal fun maintenancePageThemeColors(colorScheme: ColorScheme) = MaintenancePageThemeColors(
    onSurface = colorScheme.onSurface,
    onSurfaceVariant = colorScheme.onSurfaceVariant,
    primary = colorScheme.primary,
    error = colorScheme.error
)

/** 缓存分类（键与 CacheManager.usageParts 对齐）+ 设计色。 */
internal val CACHE_PART_LABELS: List<Triple<String, String, Color>> = listOf(
    Triple("audio", "歌曲", SoundwaveDesignPalette.Accent),
    Triple("cover", "封面", SoundwaveDesignPalette.Accent),
    Triple("lyric", "歌词", Color(0xFF9BC53D)),
    Triple("other", "其他", Color(0xFF9A9E98))
)

/** 成功 / 正常状态点（青柠绿，日夜通用）。 */
internal val MaintenanceOkColor = Color(0xFF7FB23A)

/** 缓存上限可选值（MB；0 = 不限）。 */
internal val CACHE_LIMIT_OPTIONS_MB: List<Long> = listOf(100L, 200L, 500L, 1024L, 2048L, 5120L, 0L)

internal fun cacheLimitLabel(mb: Long): String = when {
    mb == 0L -> "不限"
    mb >= 1024L -> "${mb / 1024L} GB"
    else -> "$mb MB"
}

/** 把字节上限映射回最接近的可选项下标（不限 = 最后一项）。 */
internal fun cacheLimitIndex(maxBytes: Long): Int {
    if (maxBytes >= Long.MAX_VALUE / 8) return CACHE_LIMIT_OPTIONS_MB.lastIndex
    val mb = maxBytes / (1024L * 1024L)
    val exact = CACHE_LIMIT_OPTIONS_MB.indexOf(mb)
    if (exact >= 0) return exact
    return CACHE_LIMIT_OPTIONS_MB.dropLast(1).withIndex().minByOrNull { kotlin.math.abs(it.value - mb) }?.index ?: 2
}

@Composable
fun CacheManagePage(
    onBackHint: String = "缓存管理",
    onClear: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val themeColors = maintenancePageThemeColors(MaterialTheme.colorScheme)
    val onBg = themeColors.onSurface
    val primary = themeColors.primary
    val muted = themeColors.onSurfaceVariant.copy(alpha = 0.8f)
    var tick by remember { mutableIntStateOf(0) }
    // 目录体积统计是磁盘 IO：放到 IO 线程，首帧先显示占位，不阻塞进入页面。
    var parts by remember { mutableStateOf<Map<String, Long>?>(null) }
    LaunchedEffect(tick) {
        parts = withContext(Dispatchers.IO) { runCatching { CacheManager.usageParts(ctx) }.getOrDefault(emptyMap()) }
    }
    val max = remember(tick) { CacheManager.maxBytes(ctx) }
    val used = parts?.values?.sum() ?: 0L
    val unlimited = max >= Long.MAX_VALUE / 8
    val ratio = if (!unlimited && max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else 0f
    var showLimitPicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    fun clearNow() {
        try {
            val result = CacheManager.clearAll(ctx)
            onClear(); tick++
            val skippedDetails = listOfNotNull(
                if (result.skippedActiveFiles > 0) "跳过 ${result.skippedActiveFiles} 个正在使用或生成中的媒体文件" else null,
                if (result.skippedActiveMediaResources > 0) "跳过 ${result.skippedActiveMediaResources} 个正在读取的 Media3 缓存资源" else null
            ).joinToString("；")
            val message = if (skippedDetails.isNotEmpty()) {
                "已清理其他缓存；$skippedDetails，播放未中断"
            } else {
                "已清空播放、歌词和临时缓存（保留封面与头像）"
            }
            AppNotice.toast(message, AppNotice.Kind.SUCCESS)
        } catch (_: Exception) {
            tick++
            AppNotice.toast("缓存清理失败，请稍后重试", AppNotice.Kind.FAILURE)
        }
    }
    SettingsPageList {
        item { SettingsDetailHero("同步与数据", onBackHint.ifBlank { "缓存管理" }, "查看并清理图片、歌词与音频缓存；上限与清空均真实生效。") }
        item {
            SettingsCard {
                Text("已用空间".uppercase(), color = muted, fontSize = 8.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    // r657 第二轮：已用空间数字滚动（清理 / 统计完成时逐位滚到新值；简洁动效下直接显示）。
                    Box(Modifier.weight(1f).padding(bottom = 2.dp)) {
                        xyz.cncnlp.soundwave.ui.common.RollingNumber(
                            if (parts == null) "—" else CacheManager.format(used), onBg, 38.sp, FontFamily.Serif, FontWeight.SemiBold, (-0.04).em
                        )
                    }
                    Text("上限 ${CacheManager.formatLimit(max)}", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(14.dp))
                CacheUsageBar(parts.orEmpty(), if (unlimited) used.coerceAtLeast(1L) else max.coerceAtLeast(1L))
                Spacer(Modifier.height(14.dp))
                CACHE_PART_LABELS.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        pair.forEach { (key, name, color) ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                SettingsStatusDot(color)
                                Spacer(Modifier.width(8.dp))
                                Text(name, color = muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text(CacheManager.format(parts?.get(key) ?: 0L), color = onBg, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(end = 14.dp))
                            }
                        }
                    }
                }
                if (!unlimited) Text(
                    "已使用上限的 ${(ratio * 100).toInt()}%；超出后按最近最少使用自动淘汰。", color = muted, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item { SettingsPageSection("缓存策略") }
        item { SettingsHairline() }
        item {
            SettingsDetailRow("缓存上限", "超过上限后清理较早内容；正在播放的文件不会被淘汰", icon = SettingsPageGlyphs.Limit, onClick = { showLimitPicker = true }) {
                SettingsValueChip(cacheLimitLabel(CACHE_LIMIT_OPTIONS_MB[cacheLimitIndex(max)])) { showLimitPicker = true }
            }
        }
        item {
            SettingsDetailRow("清空播放缓存", "清空歌曲、歌词与临时缓存；保留封面与头像，正在播放的不中断", icon = SettingsPageGlyphs.Clean, onClick = { showClearConfirm = true }) {
                // accept6-uipolish2（用户 2026-11-04 22:21）：行尾「清空」去掉浅粉底——裸文字按钮（危险红字，
                // 与全站裸文字动作口径一致）；padding 只作点按热区，不再有底色/圆角裁剪。
                // 普查同类：设置各页行尾浅底小钮仅此一处（区头 trailing 本就是裸字、弹层主钮为实心，均非同类）。
                Text("清空", color = themeColors.error, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showClearConfirm = true }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        item {
            val stats = remember(tick) { CacheManager.cacheStats(ctx) }
            SettingsDetailRow("缓存命中", stats, icon = SettingsPageGlyphs.Pulse, showDivider = true)
        }
        // r657 第三轮（用户 10-18）：删除「最近缓存」歌曲列表整块（列表只有「01 master / 网易云」这类无意义条目）。
        item { SettingsPageSection("播放取链报告", trailing = "复制诊断", titleColor = SoundwaveDesignPalette.Accent, onTrailing = { // 用户 10-18：区块标题珊瑚红
            val text = PlaybackDiagnostics.diagnosticsText()
            val sdk = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            sdk.setPrimaryClip(android.content.ClipData.newPlainText("soundwave-diag", text))
            AppNotice.toast("全部诊断已复制，可直接粘贴发送", AppNotice.Kind.SUCCESS)
        }) }
        item { PlaybackLinkReport(tick = tick, primary = SoundwaveDesignPalette.Accent) }
    }
    if (showLimitPicker) {
        SettingsPickerSheet(
            kicker = "数据与同步", title = "缓存上限", copy = "超过上限后按最近最少使用自动清理；不限则只在手动清空时释放。",
            options = CACHE_LIMIT_OPTIONS_MB.map(::cacheLimitLabel), selectedIndex = cacheLimitIndex(max),
            onPick = { i ->
                val mb = CACHE_LIMIT_OPTIONS_MB[i]
                CacheManager.setMaxBytes(ctx, if (mb == 0L) Long.MAX_VALUE / 4 else mb * 1024L * 1024L)
                tick++
            },
            onDismiss = { showLimitPicker = false }
        )
    }
    if (showClearConfirm) {
        SettingsConfirmSheet(
            onDismissRequest = { showClearConfirm = false },
            eyebrow = "Cache · Clear",
            title = "清空播放缓存？",
            text = {
                Text("将清空歌曲音频、歌词与临时缓存，共约 ${CacheManager.format(((parts?.get("audio") ?: 0L) + (parts?.get("lyric") ?: 0L) + (parts?.get("other") ?: 0L)))}。")
                Text("封面与头像会保留；正在播放或生成中的文件会被跳过，播放不会中断。", modifier = Modifier.padding(top = 6.dp))
            },
            // 收尾棒 J8 句柄补齐（SettingsConfirmSheet 逐点审时机后迁移）：槽内取关闭句柄，
            // 动作即时执行、清状态（showClearConfirm = false）延迟到退场后经 onDismissRequest 触发。
            dismissButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearConfirm = false }; SettingsSheetButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") } },
            confirmButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearConfirm = false }; SettingsSheetButton(onClick = { clearNow(); dismiss() }, modifier = Modifier.weight(1f), danger = true) { Text("清空") } }
        )
    }
}

/** 分段占用条：各分类按占上限比例着色，剩余为浅槽。 */
@Composable
private fun CacheUsageBar(parts: Map<String, Long>, total: Long) {
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(track)) {
        var usedFraction = 0f
        CACHE_PART_LABELS.forEach { (key, _, color) ->
            val f = ((parts[key] ?: 0L).toFloat() / total).coerceIn(0f, 1f - usedFraction)
            if (f > 0.002f) {
                Box(Modifier.weight(f).fillMaxHeight().background(color))
                usedFraction += f
            }
        }
        val rest = 1f - usedFraction
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}

/** 取链报告条目（内存 + 持久化合并，新→旧，最多 30 条）。 */
internal fun playbackLinkReportEntries(): List<PlaybackDiagnostics.Entry> {
    val mem = PlaybackDiagnostics.recent(50)
    val memKeys = mem.map { "${it.ts}_${it.songKey}" }.toSet()
    val persisted = PlaybackDiagnostics.persistedRecent(50).filter { "${it.ts}_${it.songKey}" !in memKeys }
    return (mem + persisted).sortedByDescending { it.ts }.take(30)
}

internal fun linkRouteLabel(e: PlaybackDiagnostics.Entry): String = when {
    e.failureLayer.isNotBlank() -> PlaybackDiagnostics.explainFailure(e.failureLayer)
    e.prefetched -> "预取命中"
    e.platform == "local" -> "本地文件"
    e.fromUrlCache -> "URL 缓存"
    else -> "脚本取链"
}

/** r656 item 11：播放取链报告——概览统计 + 结果筛选 + 逐条时间线（平台 / 路径 / 音质 / 各阶段耗时）。 */
@Composable
private fun PlaybackLinkReport(tick: Int, primary: Color) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = settingsMutedColor()
    var filter by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val entries = remember(tick) { playbackLinkReportEntries() }
    val ok = entries.filter { it.failureLayer.isBlank() }
    val successRate = if (entries.isEmpty()) "—" else "${ok.size * 100 / entries.size}"
    val resolveAvg = ok.mapNotNull { e -> e.resolveMs.takeIf { it >= 0 } }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    val totalAvg = ok.mapNotNull { e -> e.totalMs.takeIf { it >= 0 } }.takeIf { it.isNotEmpty() }?.average()?.toLong()
    SettingsCard {
        Row(Modifier.fillMaxWidth()) {
            SettingsStatTile("成功率", successRate, if (entries.isEmpty()) "" else "%", Modifier.weight(1f))
            SettingsStatTile("平均取链", resolveAvg?.toString() ?: "—", if (resolveAvg == null) "" else "ms", Modifier.weight(1f))
            SettingsStatTile("平均起播", totalAvg?.toString() ?: "—", if (totalAvg == null) "" else "ms", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        SettingsSegmented(listOf("全部 ${entries.size}", "成功 ${ok.size}", "失败 ${entries.size - ok.size}"), filter, { filter = it }, Modifier.fillMaxWidth())
        val shown = when (filter) {
            1 -> ok
            2 -> entries.filter { it.failureLayer.isNotBlank() }
            else -> entries
        }
        val visible = if (expanded) shown else shown.take(6)
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            SettingsEmptyNote(
                if (entries.isEmpty()) "暂无播放记录。播放一首歌后这里会显示各阶段耗时；复现播放失败后点击\"复制诊断\"即可导出全部分析日志。"
                else "该筛选下暂无记录。"
            )
        }
        visible.forEachIndexed { i, e ->
            LinkReportRow(e, onBg, muted)
            if (i < visible.lastIndex) SettingsHairline()
        }
        if (shown.size > 6) {
            Text(
                if (expanded) "收起" else "查看全部 ${shown.size} 条", color = SoundwaveDesignPalette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, // 用户 10-18：珊瑚红
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun LinkReportRow(e: PlaybackDiagnostics.Entry, onBg: Color, muted: Color) {
    val failed = e.failureLayer.isNotBlank()
    val plat = when {
        failed -> ""
        // 统一来源标识：平台码/跨源标注/脚本名，历史哈希值不原样展示。
        else -> PlaybackDiagnostics.sourceLabel(e)
    }
    val clock = remember(e.ts) { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(e.ts)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp)) { SettingsStatusDot(if (failed) SoundwaveDanger else MaintenanceOkColor, 7.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.songName.ifBlank { e.songKey } + if (e.singer.isNotBlank()) " — ${e.singer}" else "",
                color = onBg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(e.purpose, plat, linkRouteLabel(e), e.quality).filter { it.isNotBlank() }.joinToString(" · "),
                color = if (failed) SoundwaveDanger else muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(clock, color = muted, fontSize = 9.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(if (e.totalMs >= 0) "${e.totalMs}ms" else "—", color = onBg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.DmMono)
            Text(
                "取链 ${if (e.resolveMs >= 0) e.resolveMs else "—"} · 准备 ${if (e.prepareMs >= 0) e.prepareMs else "—"}",
                color = muted, fontSize = 9.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** 启动/崩溃诊断：即使异常发生在首页前，也会在下次能进入设置时保留可读副本。 */
@Composable
fun StartupCrashPage() {
    val ctx = LocalContext.current
    val themeColors = maintenancePageThemeColors(MaterialTheme.colorScheme)
    val onBg = themeColors.onSurface
    val muted = themeColors.onSurfaceVariant.copy(alpha = 0.8f)
    var tick by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val last = remember(tick) { CrashGuard.last(ctx) }
    SettingsPageList {
        item { SettingsDetailHero("系统与实验", "崩溃诊断", "检查最近异常并查看诊断日志。崩溃先脱敏后只保存在本机，不会上传，不读取 Android ID。") }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsStatusDot(if (last.isBlank()) MaintenanceOkColor else SoundwaveDanger, 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (last.isBlank()) "运行正常" else "发现启动崩溃记录", color = onBg, fontSize = 22.sp, lineHeight = 26.sp,
                        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em
                    )
                }
                Text(
                    if (last.isBlank()) "暂无未处理崩溃记录。" else "最近一次异常已保存在本机，可在下方查看、复制或清除。",
                    color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
                SettingsHairline()
                SettingsKeyValue("应用版本", BuildConfig.VERSION_NAME)
                SettingsKeyValue("设备", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                SettingsKeyValue("Android", "API ${Build.VERSION.SDK_INT}", showDivider = false)
            }
        }
        item {
            SettingsDetailRow("报告内容", "报告包含时间、版本、机型/API、线程和异常堆栈；网址参数、令牌/Cookie、歌曲 key、邮箱、设备标识和本机路径会先打码。报告只保存在本机，不会上传；最近记录可在这里查看。", icon = SettingsPageGlyphs.License)
        }
        item { SettingsPageSection("最近启动崩溃", trailing = if (last.isBlank()) "正常" else "已记录") }
        item {
            if (last.isBlank()) {
                SettingsEmptyNote("暂无未处理崩溃记录。发生崩溃后，日志会在下次打开 APP 时出现在这里。")
            } else {
                Column {
                    SettingsMonoBlock(last, maxHeight = 260.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsActionButton("复制", Modifier.weight(1f), icon = SettingsPageGlyphs.Copy) {
                            val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("soundwave-startup-crash", last))
                            AppNotice.toast("启动崩溃诊断已复制", AppNotice.Kind.SUCCESS)
                        }
                        SettingsActionButton("清除", Modifier.weight(1f), kind = SettingsButtonKind.Danger) { showClearConfirm = true }
                    }
                }
            }
        }
    }
    if (showClearConfirm) {
        SettingsConfirmSheet(
            onDismissRequest = { showClearConfirm = false },
            eyebrow = "Diagnostics · Clear",
            title = "清除本机崩溃记录？",
            text = { Text("将同时删除最近记录与本机待查看副本，此操作不可恢复。") },
            dismissButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearConfirm = false }; SettingsSheetButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") } },
            confirmButton = {
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearConfirm = false }
                SettingsSheetButton(onClick = {
                    CrashGuard.clear(ctx); CrashGuard.clearPending(ctx); tick++
                    AppNotice.toast("已清除本机崩溃记录", AppNotice.Kind.SUCCESS)
                    dismiss()
                }, modifier = Modifier.weight(1f), danger = true) { Text("清除") }
            }
        )
    }
}
