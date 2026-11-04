package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.ui.graphics.graphicsLayer
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.sheetFocusGlow
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import xyz.cncnlp.soundwave.ui.common.particleBurst
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.AppRoundSlider
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.SoundwaveDanger
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.USER_AUDIO_QUALITY_OPTIONS
import xyz.cncnlp.soundwave.playback.AudioFocusMode
import xyz.cncnlp.soundwave.playback.AudioFocusSettings
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.TypeScale
import xyz.cncnlp.soundwave.ui.common.DesignSettingSheetHeader
import xyz.cncnlp.soundwave.ui.common.designLineColor
import kotlin.math.roundToInt

/*
 * r656（设置第 7 / 9 项）：设置页“值行”点按后弹出的统一设计抽屉。
 * 外壳 = DesignSheetDialog（与「⋯ 歌曲操作」、播放器音质抽屉同一套）：珊瑚 DM Mono 眉题 + 24sp 标题 →
 * 灰色说明 → 双列/三列选项格（选中珊瑚描边 + 浅珊瑚底 + 勾）或大号数值 + 滑杆 → 底部胶囊按钮。
 */

/** [detail] 为选项的中文上下文行（V4 缺三：不只给档名/预设名，给码率、效果、风格说明）。 */
data class SettingsSheetOption(val key: String, val label: String, val tag: String = "", val detail: String = "")

@Composable
fun SettingsSheetCopy(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = TypeScale.Caption.fontSize, lineHeight = 17.sp,
        fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun SettingsSheetSection(title: String, hint: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.weight(1f))
        if (hint.isNotBlank()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = TypeScale.Micro.fontSize, fontFamily = DesignFonts.Manrope, maxLines = 1)
    }
}

@Composable
fun SettingsSheetLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(designLineColor())))

/** 选项格：选中珊瑚描边/浅底/勾；未选 1dp 线框。 */
@Composable
fun SettingsOptionTile(option: SettingsSheetOption, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val shape = RoundedCornerShape(16.dp)
    // r657 修复（审计第 12 / 21 项）：按下 0.965 弹簧 + 珊瑚 22% ripple-glow；新选中时对勾 spring 弹出（0→1.15→1）并迸发 8 颗粒子。
    val burst = xyz.cncnlp.soundwave.ui.common.rememberParticleBurstState()
    val checkScale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (selected) 1f else 0f) }
    val reduce = xyz.cncnlp.soundwave.MotionPolicy.rememberReduceMotion()
    var wasSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(selected) }
    androidx.compose.runtime.LaunchedEffect(selected, reduce) {
        if (selected && !wasSelected && !reduce) {
            burst.burst()
            checkScale.snapTo(0f)
            checkScale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 520f))
        } else checkScale.snapTo(if (selected) 1f else 0f)
        wasSelected = selected
    }
    Row(
        modifier.heightIn(min = 56.dp)
            .pressScale(0.965f)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) accent else designLineColor(), shape)
            .rippleGlow(accent.copy(alpha = 0.22f))
            // SW-12-01 收口：反馈已由 pressScale(0.965) + rippleGlow 承担，selectable 必须关掉全局默认
            // PressGlowIndication，否则缩放/光晕各叠一层（与 PressFeedback.pressScale 注释要求一致）。
            .selectable(selected = selected, role = Role.RadioButton, interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.label, color = colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (option.detail.isNotBlank()) Text(
                option.detail, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                fontFamily = DesignFonts.Manrope, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
            )
            if (option.tag.isNotBlank()) Text(
                option.tag, color = if (selected) accent else colors.onSurfaceVariant, fontSize = TypeScale.Badge.fontSize, lineHeight = 12.sp,
                fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (selected) Icon(
            DesignLineGlyphs.Check, null, tint = accent,
            modifier = Modifier.padding(start = 4.dp).size(17.dp)
                .particleBurst(burst, accent, accent.copy(alpha = 0.55f), count = 8, radius = 22.dp, particleSize = 2.dp, behind = false)
                .graphicsLayer { scaleX = checkScale.value; scaleY = checkScale.value }
        )
    }
}

/** 单列行式选项（V4 缺一收敛：声音类弹层与标准选择器同语法——整行单选、左文案右勾，不再各长一张脸）。 */
@Composable
fun SettingsOptionRows(options: List<SettingsSheetOption>, selectedKey: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            SettingsOptionTile(option, option.key == selectedKey, Modifier.fillMaxWidth()) { onPick(option.key) }
        }
    }
}

@Composable
fun SettingsOptionGrid(options: List<SettingsSheetOption>, selectedKey: String, columns: Int = 2, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { option -> SettingsOptionTile(option, option.key == selectedKey, Modifier.weight(1f)) { onPick(option.key) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 底部胶囊按钮：左侧线框次按钮（可空）+ 右侧珊瑚实心主按钮（dialog-polish 波由墨色对齐设计稿珊瑚口径）；[primaryDanger] 时主按钮为全仓唯一危险色实心底白字（SW-12-08）。 */
@Composable
fun SettingsSheetButtons(secondary: String?, onSecondary: () -> Unit, primary: String, primaryEnabled: Boolean = true, primaryDanger: Boolean = false, onPrimary: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    // SW-12-01/P4 核查（W4）：两按钮原 clickable 均未指定 indication，全局默认 PressGlowIndication 会再叠 0.965 缩放，
    // 与 pressScale(SCALE_ROW 0.98) 叠加成双重缩放 —— 按 PressFeedback.pressScale 注释要求补自备 interactionSource + indication = null；
    // pressScale 本身只在 Initial pass 观察指针、不消费 interactionSource，缩放唯一来源回归 pressScale。
    val secondaryInteraction = remember { MutableInteractionSource() }
    val primaryInteraction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (secondary != null) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).pressScale(PressMotion.SCALE_ROW).clip(shape).border(1.dp, designLineColor(), shape)
                    .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f)).clickable(interactionSource = secondaryInteraction, indication = null, onClick = onSecondary)
                    .semantics { contentDescription = secondary; role = Role.Button },
                contentAlignment = Alignment.Center
            ) { Text(secondary, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope) }
        }
        Box(
            Modifier.weight(1f).heightIn(min = 48.dp).pressScale(PressMotion.SCALE_ROW, enabled = primaryEnabled).clip(shape)
                // dialog-polish 波：主钮颜色口径与设计稿对齐为珊瑚（原墨色 onSurface 与 SheetActionButtons/WebDavPill 的珊瑚主钮并存，
                // 同家族两种主钮色）；危险主钮仍为全仓唯一 SoundwaveDanger 不变。
                .background((if (primaryDanger) SoundwaveDanger else SoundwaveDesignPalette.Accent).copy(alpha = if (primaryEnabled) 1f else 0.3f))
                .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                .clickable(enabled = primaryEnabled, interactionSource = primaryInteraction, indication = null, onClick = onPrimary)
                .semantics { contentDescription = primary; role = Role.Button },
            contentAlignment = Alignment.Center
        ) { Text(primary, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope) }
    }
}

/** 大号数值读数（Newsreader）+ 单位 + 右侧说明。 */
@Composable
fun SettingsSheetReadout(value: String, unit: String, caption: String = "", onValueClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.Bottom) {
        // r657 第三轮第 32/33 项：数值可点 → 弹出自定义输入（虚线下划线提示可编辑）。
        Text(value, color = if (onValueClick != null) SoundwaveDesignPalette.Accent else colors.onSurface,
            modifier = if (onValueClick != null) Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).clickable(onClick = onValueClick).semantics { contentDescription = "自定义数值：$value $unit"; role = Role.Button } else Modifier, fontSize = 46.sp, lineHeight = 50.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em, maxLines = 1)
        if (unit.isNotBlank()) Text(unit, color = colors.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 9.dp))
        Spacer(Modifier.weight(1f))
        if (caption.isNotBlank()) Text(caption, color = xyz.cncnlp.soundwave.designAccentTextColor(), fontSize = 10.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.1.em, modifier = Modifier.padding(bottom = 12.dp))
    }
}

/** 滑杆 + 两端刻度。 */
@Composable
fun SettingsSheetSlider(value: Float, range: ClosedFloatingPointRange<Float>, startLabel: String, endLabel: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // 第 34 项：设置抽屉内滑杆统一珊瑚红。
        AppRoundSlider(
            value = value, onValueChange = onChange, valueRange = range,
            interactionHeight = 48.dp, accent = SoundwaveDesignPalette.Accent,
            modifier = Modifier.semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                setProgress { target ->
                    if (!target.isFinite()) false else { onChange(target.coerceIn(range)); true }
                }
            }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(startLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.weight(1f))
            Text(endLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = DesignFonts.DmMono)
        }
    }
}

internal fun qualityTag(key: String): String = when (key) {
    "master" -> "MASTER · 2000K+"
    "hires" -> "HI-RES · 24BIT/96K"
    "flac24bit" -> "24 BIT · 1400K+"
    "flac" -> "LOSSLESS · ≈1000K"
    "320k" -> "320 KBPS"
    "128k" -> "128 KBPS"
    else -> key.uppercase()
}

/** V4 缺三：每档音质的判断上下文（码率概念 + 体积量级），弹层底部按当前选择整句呈现。 */
internal fun qualityContextLine(key: String): String = when (key) {
    "master" -> "母带级无损，码率通常在 2000K 以上，音质最好、文件也最大"
    "hires" -> "高解析无损（24bit / 96kHz 起），细节比 CD 无损更丰富，文件较大"
    "flac24bit" -> "24bit 无损，码率约 1400–2000K，兼顾细节与体积"
    "flac" -> "CD 级无损，码率约 700–1100K，一首 4 分钟约 25 MB"
    "320k" -> "有损高码率 320K，一首 4 分钟约 9.6 MB，日常听感与无损差别很小"
    "128k" -> "有损标准 128K，一首 4 分钟约 3.8 MB，最省流量与空间"
    else -> ""
}

internal fun qualityOptions(): List<SettingsSheetOption> =
    USER_AUDIO_QUALITY_OPTIONS.map { (k, l) -> SettingsSheetOption(k, l, qualityTag(k), detail = qualityContextLine(k)) }

// ---------------------------------------------------------------- 在线播放音质

@Composable
fun PlaybackQualitySheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) { PlaybackQualitySheetContent(current, onPick) }
}

@Composable
fun ColumnScope.PlaybackQualitySheetContent(current: String, onPick: (String) -> Unit) {
    // J 波：选项点击后走 hosting DesignSheetDialog 的动画关闭（内容函数只在 DesignSheetDialog 内使用，句柄恒有值）。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss {}
    DesignSettingSheetHeader("Stream quality", "在线播放音质")
    SettingsSheetCopy("在线播放时优先请求的音质；音源不支持时自动降级，实际音质以音源返回为准。")
    Spacer(Modifier.height(18.dp))
    // V4 缺一/缺三收敛：双列格改单列行（与标准选择器同语法）；每档自带码率与体积上下文（detail 行）。
    SettingsOptionRows(qualityOptions(), current) { k -> onPick(k); dismiss() }
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------- 下载音质（r658h：与在线播放音质同款）

@Composable
fun DownloadQualitySettingSheet(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // J 波：选项点击走动画关闭句柄；调用方 onPick 只执行动作。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        DesignSettingSheetHeader("Download quality", "下载音质")
        SettingsSheetCopy("下载时优先请求的音质；音源不支持时自动降级，实际音质以音源返回为准。")
        Spacer(Modifier.height(18.dp))
        // V4 缺一/缺三收敛：同在线音质——单列行 + 每档码率/体积上下文。
        SettingsOptionRows(qualityOptions(), current) { k -> onPick(k); dismiss() }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------- 睡眠定时

@Composable
fun SleepTimerSheet(remainMs: Long, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) { SleepTimerSheetContent(remainMs, onApply, onDismiss) }
}

@Composable
fun ColumnScope.SleepTimerSheetContent(remainMs: Long, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    // J 波：底部按钮走动画关闭句柄（应用动作先执行，退场后才清状态）；预设网格只改草稿、不关闭，保持原样。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
    // Snapshot only when opening: service ticks must not overwrite an in-progress draft.
    val initial = remember { sleepMinutesFromRemain(remainMs) }
    var afterCurrent by remember { mutableStateOf(initial < 0) }
    var minutes by remember { mutableFloatStateOf(if (initial > 0) initial.toFloat() else 30f) }
    // r658c C：已去掉「自定义」入口；旧版存下的非 5 分钟步进值照常显示/生效（夹到 1–1440）；拖动滑杆后清除，回到 5 分钟步进。
    var exact by remember { mutableStateOf(if (initial > 0 && initial % 5 != 0) initial.coerceIn(SLEEP_MIN_MINUTES, SLEEP_MAX_MINUTES) else 0) }
    val snapped = if (exact > 0) exact else ((minutes / 5f).roundToInt() * 5).coerceIn(5, SLEEP_MAX_MINUTES)
    DesignSettingSheetHeader("Sleep timer", "睡眠定时")
    SettingsSheetCopy(if (initial == 0) "拖动滑杆或选预设（5 分钟–24 小时）；到点后播完当前歌曲再停止。" else "当前：${sleepValueLabel(initial)}。拖动滑杆或选预设可修改（最长 24 小时）。")
    if (afterCurrent) SettingsSheetReadout("播完", "当前歌曲", "AFTER TRACK")
    else {
        val (v, u) = sleepReadout(snapped)
        SettingsSheetReadout(v, u, "MAX 24H")
    }
    SettingsSheetSlider(
        value = if (afterCurrent) 5f else snapped.toFloat(), range = 5f..SLEEP_MAX_MINUTES.toFloat(), startLabel = "5 MIN", endLabel = "24 H"
    ) { afterCurrent = false; exact = 0; minutes = it }
    Spacer(Modifier.height(14.dp))
    val selectedKey = if (afterCurrent) "-1" else snapped.toString()
    val pickPreset: (String) -> Unit = { key ->
        val m = key.toInt()
        if (m < 0) afterCurrent = true else { afterCurrent = false; exact = 0; minutes = m.toFloat() }
    }
    // 用户 2026-11-04 裁定（覆盖 V4 单列收敛）：主预设改双列格——30 分钟/1 小时、2 小时/4 小时、6 小时/播完当前；
    // 既有 8 小时/24 小时预设不删，降为格外整行保留（滑杆另可达 5 分钟–24 小时任意 5 分钟档，功能零丢失）。
    // 草稿式点选语义不变：点预设只改草稿，仍需底部「开始定时」生效。
    SettingsOptionGrid(
        listOf(
            SettingsSheetOption("30", "30 分钟"), SettingsSheetOption("60", "1 小时"),
            SettingsSheetOption("120", "2 小时"), SettingsSheetOption("240", "4 小时"),
            SettingsSheetOption("360", "6 小时"), SettingsSheetOption("-1", "播完当前")
        ),
        selectedKey, columns = 2, onPick = pickPreset
    )
    Spacer(Modifier.height(10.dp))
    SettingsOptionRows(
        listOf(SettingsSheetOption("480", "8 小时"), SettingsSheetOption("1440", "24 小时")),
        selectedKey, onPick = pickPreset
    )
    SettingsSheetButtons(
        secondary = if (initial != 0) "关闭定时" else "取消",
        onSecondary = { if (initial != 0) onApply(0); dismiss() },
        primary = "开始定时"
    ) { onApply(if (afterCurrent) -1 else snapped); dismiss() }
}

// ---------------------------------------------------------------- 播放速度 / 均衡器

val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

fun playbackSpeedLabel(speed: Float): String = if (speed == 1f) "1.0×" else "${speed}×"

@Composable
fun PlaybackSpeedSheet(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) { PlaybackSpeedSheetContent(current, onPick) }
}

@Composable
fun ColumnScope.PlaybackSpeedSheetContent(current: Float, onPick: (Float) -> Unit) {
    // J 波：选项点击后走 hosting DesignSheetDialog 的动画关闭。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss {}
    DesignSettingSheetHeader("Playback speed", "播放速度")
    SettingsSheetCopy("调节当前与之后歌曲的播放速度，保持原音高；播放中可随时再次修改。")
    Spacer(Modifier.height(18.dp))
    // V4 缺一/缺二收敛：三列格改单列行，每档带适用场景说明（选档即生效、主页值行同步回显）。
    val details = mapOf(
        0.5f to "慢放 · 适合跟练与听写", 0.75f to "略慢 · 语速放缓", 1f to "原速播放",
        1.25f to "略快 · 节省时间", 1.5f to "快速浏览内容", 2f to "倍速 · 更适合语音类内容"
    )
    val options = PLAYBACK_SPEEDS.map { s -> SettingsSheetOption(s.toString(), playbackSpeedLabel(s), detail = details[s] ?: "") }
    SettingsOptionRows(options, current.toString()) { onPick(it.toFloat()); dismiss() }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun EqualizerSheet(names: List<String>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) { EqualizerSheetContent(names, current, onPick) }
}

/** 当前预设的 5 段增益曲线（数据与 PlayerService 共用 EqualizerPresets，呈现即实际增益，不漂移）。 */
@Composable
internal fun EqualizerCurvePreview(gainsMb: IntArray) {
    val accent = SoundwaveDesignPalette.Accent
    val baseline = designLineColor()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(62.dp)) {
        val midY = size.height / 2f
        drawLine(baseline, androidx.compose.ui.geometry.Offset(0f, midY), androidx.compose.ui.geometry.Offset(size.width, midY), strokeWidth = 1.dp.toPx())
        if (gainsMb.size >= 2) {
            val stepX = size.width / (gainsMb.size - 1)
            val pts = gainsMb.mapIndexed { i, g ->
                val clamped = g.coerceIn(-800, 800) / 800f
                androidx.compose.ui.geometry.Offset(stepX * i, midY - clamped * (size.height / 2f - 6.dp.toPx()))
            }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(pts.first().x, pts.first().y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            pts.forEach { drawCircle(accent, radius = 3.dp.toPx(), center = it) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("低频", color = muted, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.weight(1f))
        Text("中频", color = muted, fontSize = 10.sp, fontFamily = DesignFonts.DmMono)
        Text("高频", color = muted, fontSize = 10.sp, fontFamily = DesignFonts.DmMono, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun ColumnScope.EqualizerSheetContent(names: List<String>, current: Int, onPick: (Int) -> Unit) {
    // J 波：选项点击后走 hosting DesignSheetDialog 的动画关闭。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss {}
    DesignSheetHeader("Equalizer", "均衡器")
    SettingsSheetCopy("选择声音风格预设，立即作用于当前播放；“关闭”保持原始音色。")
    Spacer(Modifier.height(18.dp))
    // V4 缺一/二/三收敛：三列格改单列行；每预设带风格说明（缺三），底部呈现当前预设的真实增益曲线（缺二）。
    SettingsOptionRows(
        names.mapIndexed { i, n -> SettingsSheetOption(i.toString(), n, detail = xyz.cncnlp.soundwave.EqualizerPresets.description(i)) },
        current.toString()
    ) { onPick(it.toInt()); dismiss() }
    SettingsSheetSection("当前预设 · 频段曲线", names.getOrElse(current) { "" })
    EqualizerCurvePreview(xyz.cncnlp.soundwave.EqualizerPresets.gains(current))
    Spacer(Modifier.height(12.dp))
}

// ---------------------------------------------------------------- 和其他 APP 同时播放

@Composable
fun AudioFocusSheet(settings: AudioFocusSettings, onChange: (AudioFocusSettings) -> Unit, onDismiss: () -> Unit) {
    DesignSheetDialog(onDismiss = onDismiss) { AudioFocusSheetContent(settings, onChange, onDismiss) }
}

@Composable
fun ColumnScope.AudioFocusSheetContent(settings: AudioFocusSettings, onChange: (AudioFocusSettings) -> Unit, onDismiss: () -> Unit) {
    DesignSheetHeader("Audio focus", "和其他 APP 同时播放")
    SettingsSheetCopy("决定其他应用开始播放时 SoundWave 的行为，修改后立即生效。")
    SettingsSheetSection("音频焦点", "多应用音频策略")
    // V4 缺三收敛：模式行为说明（hint 本是中文说明句）从 DmMono 小标签位移到正文 detail 行。
    val modes = AudioFocusMode.values().map { SettingsSheetOption(it.key, it.label, detail = it.hint) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modes.forEach { option ->
            SettingsOptionTile(option, option.key == settings.mode.key, Modifier.fillMaxWidth()) {
                AudioFocusMode.of(option.key)?.let { onChange(settings.copy(mode = it)) }
            }
        }
    }
    SettingsSheetSection("通话打断", "通话结束后")
    // V4 缺一收敛：通话打断由双列格改单列行，与上方模式组同语法，并给出行为说明。
    SettingsOptionRows(
        listOf(
            SettingsSheetOption("keep", "保持暂停", detail = "通话结束后保持暂停，需手动继续"),
            SettingsSheetOption("resume", "自动继续", detail = "通话结束后自动恢复播放")
        ),
        if (settings.resumeAfterCall) "resume" else "keep"
    ) { onChange(settings.copy(resumeAfterCall = it == "resume")) }
    SettingsSheetCopy("来电或通话时总会暂停；“同时播放”模式同样会在来电时暂停。")
    // ACCEPT5（退出动画普查 T20）：「完成」走退场句柄（此前直调 onDismiss 瞬时消失）；
    // 模式/通话选项为表单留驻语义不变。不在 DesignSheetDialog 内独立渲染时回退 onDismiss。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
    SettingsSheetButtons(secondary = null, onSecondary = {}, primary = "完成") { dismiss() }
}


/** 睡眠定时大数字：≥60 分钟时显示「h:mm」+「小时」，否则「N」+「分钟」。 */
internal fun sleepReadout(minutes: Int): Pair<String, String> =
    if (minutes >= 60) (if (minutes % 60 == 0) "${minutes / 60}" to "小时" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}" to "小时")
    else "$minutes" to "分钟"

/** 自定义数值输入：只接受 [min]..[max] 内的整数。 */
internal fun parseCustomNumber(text: String, min: Int, max: Int): Int? = text.trim().toIntOrNull()?.takeIf { it in min..max }

/**
 * r657 第三轮第 32/33 项：点数值弹出的自定义输入（统一设计抽屉，夜间非磨砂不透明）。
 * 数字键盘输入，越界时确认键禁用并提示范围。
 */
@Composable
fun SettingsNumberInputDialog(
    title: String, hint: String, unit: String, initial: Int, min: Int, max: Int,
    onDismiss: () -> Unit, onConfirm: (Int) -> Unit
) {
    DesignSheetDialog(onDismiss = onDismiss) {
        // ACCEPT6（用户真机反馈 2026-11-04，同 SheetInputField 口径）：编辑状态持 TextFieldValue。
        // 原实现走 String 重载 + onValueChange 内做数字过滤变换——变换回填正是选区被重同步
        // 吞掉的典型路径；现改为在字段状态上先过滤再持有（过滤改写文本时选区落到过滤后末尾，
        // 未改写时用户选区原样保留），初值选区置于末尾。
        val initialText = initial.coerceIn(min, max).toString()
        var fieldValue by remember {
            mutableStateOf(TextFieldValue(text = initialText, selection = TextRange(initialText.length)))
        }
        val parsed = parseCustomNumber(fieldValue.text, min, max)
        val colors = MaterialTheme.colorScheme
        DesignSheetHeader("Custom", title)
        SettingsSheetCopy(hint)
        Spacer(Modifier.height(14.dp))
        // dialog-polish 波：原裸 OutlinedTextField 默认形态（小圆角/默认字号）与家族字段规格不一致——
        // 圆角/字号/边框/容器/光标色收敛到 SheetInputField 同集；后缀单位与错误态为本框专属能力，保留。
        // ACCEPT6 dialogfrost：聚焦晕与 SheetInputField 同语言（错误态下抑制，错误红框优先）。
        val numberInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val numberFocused by numberInteraction.collectIsFocusedAsState()
        androidx.compose.material3.OutlinedTextField(
            value = fieldValue, onValueChange = { v ->
                val filtered = v.text.filter { it.isDigit() }.take(4)
                fieldValue = if (filtered == v.text) v
                    else TextFieldValue(text = filtered, selection = TextRange(filtered.length))
            },
            interactionSource = numberInteraction,
            singleLine = true, suffix = { Text(unit) },
            isError = parsed == null,
            textStyle = TextStyle(
                fontSize = ThemeTokens.PlaylistDialog.FieldTextSize,
                fontFamily = DesignFonts.Manrope,
                fontWeight = FontWeight.Medium
            ),
            shape = RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = designLineColor(),
                disabledBorderColor = designLineColor(),
                errorBorderColor = colors.error,
                focusedTextColor = colors.onSurface,
                unfocusedTextColor = colors.onSurface,
                disabledTextColor = colors.onSurfaceVariant,
                errorTextColor = colors.onSurface,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                disabledContainerColor = colors.surface,
                errorContainerColor = colors.surface,
                cursorColor = colors.primary,
                errorCursorColor = colors.error
            ),
            modifier = Modifier.fillMaxWidth().sheetFocusGlow(numberFocused && parsed != null, RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius))
        )
        if (parsed == null) Text("请输入 $min–$max 之间的整数", color = SoundwaveDesignPalette.Accent, fontSize = TypeScale.Micro.fontSize, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        // ACCEPT5（退出动画普查 T21）：取消走退场句柄；确定改为「确认后退场」（此前永不关闭 = D）。
        // 本组件当前无生产调用点，按家族标准接线归位，语义除关闭时序外不变。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        SettingsSheetButtons(secondary = "取消", onSecondary = dismiss, primary = "确定", primaryEnabled = parsed != null) { parsed?.let { onConfirm(it); dismiss() } }
    }
}
