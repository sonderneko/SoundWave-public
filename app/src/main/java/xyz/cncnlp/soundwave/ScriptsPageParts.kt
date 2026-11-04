package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.sheetFocusGlow
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetButtons
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetCopy
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetLine
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetReadout
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetSection
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetSlider
import xyz.cncnlp.soundwave.ui.settings.SettingsSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * r656（设置第 9 项）：音源脚本页的导入区、定时检测 / 批量检测 / 单脚本检测 / 脚本详情抽屉。
 * 全部复用 DesignSheetDialog + DesignSheetHeader（珊瑚眉题 + 24sp 标题），报告改为结构化简报：
 * 概要统计格 → 按平台一行（已验证音质胶囊 + 平均耗时），原始逐项报告折叠在「原始报告」里。
 */

internal const val SCRIPT_INTERVAL_SLIDER_MAX = 180

/** 定时检测滑杆取值：最短 30 分钟（Scheduler 下限）、最长可滑到 180 分钟，5 分钟一档。 */
internal fun snapScriptInterval(raw: Float): Int =
    ((raw / 5f).roundToInt() * 5).coerceIn(ScriptProbeScheduler.MIN_INTERVAL_MINUTES, SCRIPT_INTERVAL_SLIDER_MAX)

internal fun scriptProbeTime(ts: Long): String =
    if (ts <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

internal fun scriptProbePlatformName(code: String): String = when (code) {
    "wy" -> "网易云"; "kw" -> "酷我"; "kg" -> "酷狗"; "tx" -> "QQ"; "mg" -> "咪咕"; else -> code
}

/** 导入区：三等分（文件 / 链接 / 代码），竖向细线分隔。验收 B 波：去掉整条圆角卡壳，与页面轻量排版一致。 */
@Composable
internal fun ScriptImportStrip(onFile: () -> Unit, onLink: () -> Unit, onCode: () -> Unit) {
    val line = designLineColor()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScriptImportCell(DesignLineGlyphs.Download, "文件", "本地 .js", Modifier.weight(1f), onFile)
        Box(Modifier.width(1.dp).height(46.dp).background(line))
        ScriptImportCell(DesignLineGlyphs.Cloud, "链接", "远程地址", Modifier.weight(1f), onLink)
        Box(Modifier.width(1.dp).height(46.dp).background(line))
        ScriptImportCell(DesignLineGlyphs.Lyrics, "代码", "粘贴源码", Modifier.weight(1f), onCode)
    }
}

@Composable
private fun ScriptImportCell(icon: ImageVector, title: String, hint: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 64.dp).clickable(onClick = onClick)
            .semantics { contentDescription = "${title}导入"; role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(SoundwaveDesignPalette.Accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
        }
    }
}

private fun Modifier.graphicsRotate180(): Modifier = this.graphicsLayer { rotationZ = 180f }

// ------------------------------------------------------------------ 定时检测

@Composable
internal fun ScriptScheduleSheet(enabled: Boolean, minutes: Int, onSave: (Boolean, Int) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss) { ScriptScheduleSheetContent(enabled, minutes, onSave, onDismiss) }
}

@Composable
internal fun ColumnScope.ScriptScheduleSheetContent(enabled: Boolean, minutes: Int, onSave: (Boolean, Int) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(enabled) }
    var raw by remember { mutableFloatStateOf(minutes.coerceIn(ScriptProbeScheduler.MIN_INTERVAL_MINUTES, SCRIPT_INTERVAL_SLIDER_MAX).toFloat()) }
    // r658c C：去掉「自定义」入口；旧存的超滑杆值（≤24 小时，经 Scheduler 归一化）照常显示生效，拖滑杆回到 5 分钟步进。
    var exact by remember { mutableStateOf(if (minutes > SCRIPT_INTERVAL_SLIDER_MAX) ScriptProbeScheduler.normalizeIntervalMinutes(minutes) else 0) }
    val value = if (exact > 0) exact else snapScriptInterval(raw)
    DesignSheetHeader("Scheduled check", "定时检测")
    SettingsSheetCopy("后台按间隔对已启用脚本做完整检测，结果用于智能排序与一键配置；检测不通过不影响播放。")
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, designLineColor(), RoundedCornerShape(16.dp))
            .clickable { on = !on }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("开启定时检测", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(if (on) "每 $value 分钟检测一次" else "已关闭", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        SettingsSwitch(checked = on, onClick = { on = !on })
    }
    Column(Modifier.then(if (on) Modifier else Modifier.graphicsAlpha(0.4f))) {
        SettingsSheetReadout("$value", "分钟", "30 MIN – 24 H")
        SettingsSheetSlider(value.coerceAtMost(SCRIPT_INTERVAL_SLIDER_MAX).toFloat(), ScriptProbeScheduler.MIN_INTERVAL_MINUTES.toFloat()..SCRIPT_INTERVAL_SLIDER_MAX.toFloat(), "30 MIN", "180 MIN") { on = true; exact = 0; raw = it }
    }
    // ACCEPT5（退出动画普查 T5）：取消/保存走退场句柄（此前直调 onDismiss 瞬时消失）；
    // 独立渲染（截图测试）时句柄回退 onDismiss，行为不变。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
    SettingsSheetButtons("取消", dismiss, "保存") { onSave(on, value); dismiss() }
}

private fun Modifier.graphicsAlpha(a: Float): Modifier = this.graphicsLayer { alpha = a }

// ------------------------------------------------------------------ 结构化检测简报

@Composable
internal fun ScriptStatTile(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, lineHeight = 22.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 单脚本简报：状态行 + 三枚统计格 + 平台行。只投影缓存中的真实尝试，不推断未测试能力。 */
@Composable
internal fun ScriptProbeBrief(snapshot: SmartSourceScriptProbeSnapshot, score: Int, avgMs: Long) {
    val report = snapshot.report
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = SoundwaveDesignPalette.Accent
    SettingsSheetSection("检测报告", report?.let { "${it.attempts.size} 项" }.orEmpty())
    if (report == null) {
        Text("尚无真实检测记录。点「检测」后台真实取链后生成检测报告；检测不通过不影响播放。", color = muted, fontSize = 12.sp, lineHeight = 17.sp)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ScriptBriefTag(
            when {
                report.type == ScriptProbeCache.ReportType.QUICK -> "旧版抽检记录"
                report.complete -> report.type.label
                else -> report.type.label + " · 未完成"
            },
            accent
        )
        Spacer(Modifier.width(6.dp))
        ScriptBriefTag(if (snapshot.current) "当前报告" else "已过期", if (snapshot.current) muted else MaterialTheme.colorScheme.error)
        Spacer(Modifier.weight(1f))
        Text(scriptProbeTime(report.checkedAt), color = muted, fontSize = 11.sp, fontFamily = DesignFonts.DmMono)
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScriptStatTile("有效链路", "${snapshot.validAttemptCount}/${snapshot.attemptCount}", Modifier.weight(1f))
        ScriptStatTile("平均耗时", if (avgMs > 0) "${avgMs}ms" else "—", Modifier.weight(1f))
        ScriptStatTile("综合分", if (score >= 0) compactScore(score) else "—", Modifier.weight(1f))
    }
    if (!snapshot.current) {
        Text("脚本代码或运行时已变化：仅作历史参考，不参与排序与自动配置。", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
    }
    if (report.coverage.isNotBlank()) {
        Text(report.coverage, color = muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 10.dp))
    }
    SettingsSheetSection("按平台")
    if (snapshot.platforms.isEmpty()) {
        Text("报告中没有实际尝试记录。", color = muted, fontSize = 12.sp)
    }
    snapshot.platforms.forEach { platform ->
        SettingsSheetLine()
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scriptProbePlatformName(platform.code), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(56.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (platform.verifiedQualities.isEmpty()) Text("无有效链路 · ${platform.attemptCount} 项尝试", color = muted, fontSize = 11.sp, maxLines = 1)
                else platform.verifiedQualities.forEach { q -> ScriptBriefTag(q.uppercase(), accent) }
            }
            Text(platform.averageLatencyMs?.let { "${it}ms" } ?: "—", color = muted, fontSize = 11.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.padding(start = 8.dp))
        }
    }
    Text(
        if (report.type == ScriptProbeCache.ReportType.QUICK) "旧版抽检记录只含部分平台与音质；重新检测后生成完整检测报告。"
        else "完整检测覆盖脚本声明支持的全部平台与音质；脚本未声明的平台不在报告内，不代表失败。",
        color = muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp)
    )
    ScriptProbeItems(report)
}

/** 逐项结果：平台 → 样本 → 每个请求音质一行（成功/失败 · 耗时 · 原因），覆盖报告内全部尝试。 */
@Composable
private fun ScriptProbeItems(report: ScriptProbeCache.Report) {
    if (report.attempts.isEmpty()) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val valid = report.valid.toSet()
    SettingsSheetSection("逐项结果", "成功 ${report.valid.size}/${report.attempts.size}")
    report.attempts.groupBy { it.platform }.forEach { (platform, attempts) ->
        attempts.groupBy { it.sampleQueryName.ifBlank { it.sampleName } to it.sampleQuerySinger.ifBlank { it.sampleSinger } }
            .forEach { (sample, rows) ->
                SettingsSheetLine()
                Text(
                    scriptProbePlatformName(platform) + " · " + sample.first.ifBlank { "未知样本" } + sample.second.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty(),
                    color = ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                rows.forEach { a -> ScriptProbeItemRow(a, a in valid, muted, ink) }
            }
    }
}

@Composable
private fun ScriptProbeItemRow(a: ScriptProbeCache.Attempt, ok: Boolean, muted: Color, ink: Color) {
    val (status, color) = when {
        ok -> "成功" to SoundwaveDesignPalette.Accent
        a.outcome == ScriptProbeCache.Outcome.OK -> "未通过校验" to MaterialTheme.colorScheme.error
        a.outcome == ScriptProbeCache.Outcome.UNSUPPORTED -> "无链接" to muted
        a.outcome == ScriptProbeCache.Outcome.NO_MATCH -> "样本未匹配" to muted
        a.outcome == ScriptProbeCache.Outcome.EXCLUDED -> "已排除" to muted
        else -> "失败" to MaterialTheme.colorScheme.error
    }
    val ms = a.latencyMs.takeIf { it > 0L } ?: a.searchMs.takeIf { it > 0L }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(a.quality.uppercase(Locale.ROOT), color = ink, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, maxLines = 1, modifier = Modifier.width(72.dp))
        Text(status, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(64.dp))
        Text(
            if (ok) "" else a.reason.ifBlank { "—" },
            color = muted, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text(ms?.let { "${it}ms" } ?: "—", color = muted, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun compactScore(score: Int): String = when {
    score >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", score / 1_000_000f)
    score >= 1000 -> String.format(Locale.ROOT, "%.1fK", score / 1000f)
    else -> score.toString()
}

@Composable
internal fun ScriptBriefTag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 9.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 「原始报告」折叠区：默认收起，展开后等宽小字可滚动。 */
@Composable
internal fun ScriptRawReport(text: String) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (open) "收起原始报告" else "查看原始报告", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Icon(DesignLineGlyphs.Down, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).then(if (open) Modifier.graphicsRotate180() else Modifier))
    }
    if (open) Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = DesignFonts.DmMono,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
    )
}

// ------------------------------------------------------------------ 检测 / 批量检测 / 详情

/** 单脚本检测抽屉：进行中显示进度条与说明，完成后显示结构化简报。 */
@Composable
internal fun ScriptCheckSheet(script: Script, running: Boolean, rawReport: String, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT4（审计 §9 B 类）：「完成 / 后台继续」改走退场句柄（原直调 onDismiss 同步卸载）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSheetHeader("Script check", script.name.ifEmpty { script.id })
        if (running) {
            SettingsSheetCopy("正在完整检测：按脚本声明的全部平台 × 每平台全部样本 × 全部音质逐项真实取链，可离开本页；完成后在这里显示检测报告。")
            ScriptProgressBar(null)
        } else {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ScriptProbeBrief(SmartSourceScriptProbeSnapshot.load(script), ScriptProbeCache.score(script), ScriptProbeCache.avgMs(script))
                ScriptRawReport(rawReport)
            }
        }
        SettingsSheetButtons(null, {}, if (running) "后台继续" else "完成") { dismiss() }
    }
}

@Composable
private fun ScriptProgressBar(progress: Float?) {
    val accent = SoundwaveDesignPalette.Accent
    Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(6.dp).clip(RoundedCornerShape(999.dp)).background(accent.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(progress?.coerceIn(0.04f, 1f) ?: 0.35f).height(6.dp).clip(RoundedCornerShape(999.dp)).background(accent))
    }
}

/** 批量检测抽屉：大号百分比 + 进度条；完成后每个脚本一行简报（有效 / 最高音质 / 平均耗时）。 */
@Composable
internal fun ScriptBatchSheet(scripts: List<Script>, running: Boolean, progress: Float, rawReport: String, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT4（审计 §9 B 类）：「完成 / 后台继续」改走退场句柄。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSheetHeader("Batch check", "批量检测")
        if (running) {
            SettingsSheetCopy("逐脚本、逐平台、逐音质真实检测，可离开本页，完成后自动刷新排序依据。")
            SettingsSheetReadout("${(progress * 100).roundToInt()}", "%", "RUNNING")
            ScriptProgressBar(progress)
        } else {
            val usable = scripts.filter { it.code.isNotBlank() }
            val snapshots = remember(usable) { usable.map { SmartSourceScriptProbeSnapshot.load(it) } }
            SettingsSheetCopy("检测完成：${snapshots.count { it.validAttemptCount > 0 }}/${snapshots.size} 个脚本有有效链路。智能排序/一键配置只使用与当前脚本代码和运行时匹配的完整检测结果。")
            Column(Modifier.padding(top = 12.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                snapshots.forEach { snap -> ScriptBatchRow(snap) }
                ScriptRawReport(rawReport)
            }
        }
        SettingsSheetButtons(null, {}, if (running) "后台继续" else "完成") { dismiss() }
    }
}

@Composable
private fun ScriptBatchRow(snap: SmartSourceScriptProbeSnapshot) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ok = snap.validAttemptCount > 0
    SettingsSheetLine()
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) SoundwaveDesignPalette.Accent else muted.copy(alpha = 0.5f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(snap.scriptName.ifBlank { snap.scriptId }, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val best = snap.platforms.flatMap { it.verifiedQualities }.distinct()
            Text(
                when {
                    snap.report == null -> "未检测"
                    !ok -> "无有效链路 · ${snap.attemptCount} 项尝试"
                    else -> "有效 ${snap.validAttemptCount}/${snap.attemptCount} · ${best.take(3).joinToString(" / ") { it.uppercase() }}"
                },
                color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        val ms = snap.platforms.mapNotNull { it.averageLatencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
        Text(ms?.let { "${it}ms" } ?: "—", color = muted, fontSize = 11.sp, fontFamily = DesignFonts.DmMono)
    }
}

/** 脚本详情抽屉：名称/作者/版本/描述 + 结构化上次检测简报。 */
@Composable
internal fun ScriptDetailSheet(
    script: Script, index: Int, running: Boolean = false, canProbe: Boolean = false,
    onProbe: () -> Unit = {}, onDismiss: () -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT4（审计 §9 B 类）：「完成」改走退场句柄。
        // ACCEPT5（退出动画普查 T8）：「检测此脚本」换面动作改从即时切换归位家族口径——
        // 先落检测抽屉状态、再走退场句柄，本层 detailScriptId 由退场后 onDismiss 清。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSheetHeader("Script detail", script.name.ifEmpty { script.id })
        val meta = listOfNotNull(
            "第 ${index + 1} 优先",
            script.author.takeIf { it.isNotBlank() }?.let { "作者 $it" },
            script.version.takeIf { it.isNotBlank() }?.let { "v$it" },
            if (script.enabled) "已启用" else "已关闭"
        ).joinToString(" · ")
        SettingsSheetCopy(meta + if (script.description.isNotBlank()) "\n" + script.description else "")
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
            ScriptProbeBrief(SmartSourceScriptProbeSnapshot.load(script), ScriptProbeCache.score(script), ScriptProbeCache.avgMs(script))
            Text("智能排序/一键配置只使用与当前脚本代码和运行时匹配的完整检测结果；旧版快速抽检记录不参与排序。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            ScriptRawReport(ScriptProbeCache.latestReport(script.id)?.let(ScriptProbeCache::report).orEmpty())
        }
        // r658c S：详情内也可单独检测本脚本（批量检测进行中时隐藏）。
        SettingsSheetButtons(if (canProbe || running) (if (running) "检测中…" else "检测此脚本") else null, { onProbe(); dismiss() }, "完成") { dismiss() }
    }
}

/** 链接 / 代码导入抽屉（统一设计风格）；输入框为圆角线框。 */
@Composable
internal fun ScriptImportInputSheet(
    eyebrow: String, title: String, copy: String, value: String, multiLine: Boolean,
    onValue: (String) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT4（审计 §9 B 类）：取消/导入改走退场句柄；宿主 onImport 不再同步清可见状态（关闭归 onDismiss）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSheetHeader(eyebrow, title)
        SettingsSheetCopy(copy)
        // dialog-polish 波：圆角对齐家族字段规格（ThemeTokens.PlaylistDialog.FieldRadius，原 16dp），
        // 并补聚焦态珊瑚边（原边框聚焦时不变色，与 SheetInputField 的焦点语言不一致）；纯样式，交互源仅驱动边框色。
        val shape = RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius)
        val ink = MaterialTheme.colorScheme.onSurface
        val fieldInteraction = remember { MutableInteractionSource() }
        val fieldFocused by fieldInteraction.collectIsFocusedAsState()
        // ACCEPT6（用户真机反馈 2026-11-04，同 SheetInputField 口径）：编辑状态持 TextFieldValue，
        // 初值与程序化回填选区置于末尾；对上仍是 String 接口（宿主导入逻辑不变），仅文本变化时上抛。
        var fieldValue by remember {
            mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
        }
        LaunchedEffect(value) {
            if (value != fieldValue.text) {
                fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
            }
        }
        BasicTextField(
            value = fieldValue, onValueChange = { edited ->
                fieldValue = edited
                if (edited.text != value) onValue(edited.text)
            }, singleLine = !multiLine,
            interactionSource = fieldInteraction,
            textStyle = TextStyle(color = ink, fontSize = if (multiLine) 11.sp else 14.sp, fontFamily = if (multiLine) DesignFonts.DmMono else DesignFonts.Manrope),
            cursorBrush = SolidColor(SoundwaveDesignPalette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (multiLine) KeyboardType.Text else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = if (multiLine) 160.dp else 52.dp, max = 260.dp)
                // ACCEPT6 dialogfrost：聚焦晕补齐（珊瑚边已有），与对话框输入家族同语言。
                .sheetFocusGlow(fieldFocused, shape)
                .clip(shape).border(1.dp, if (fieldFocused) SoundwaveDesignPalette.Accent else designLineColor(), shape).padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics { contentDescription = title },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(if (multiLine) "在此粘贴脚本源码…" else "https://…", color = ink.copy(alpha = 0.35f), fontSize = 13.sp)
                    inner()
                }
            }
        )
        SettingsSheetButtons("取消", dismiss, "导入", primaryEnabled = value.isNotBlank(), onPrimary = { onImport(); dismiss() })
    }
}
