package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

internal enum class PlayerMenuActionColorRole {
    PRIMARY, SECONDARY, TERTIARY, ON_SURFACE_VARIANT, OUTLINE, INVERSE_PRIMARY, ERROR
}

internal fun playerMenuActionColorRole(glyph: String): PlayerMenuActionColorRole = when (glyph) {
    "menu_playlist", "menu_cast" -> PlayerMenuActionColorRole.PRIMARY
    "menu_next", "sparkles_outline", "sparkles_filled", "menu_heart" -> PlayerMenuActionColorRole.SECONDARY
    "menu_download", "menu_hide" -> PlayerMenuActionColorRole.TERTIARY
    "menu_similar", "menu_lyrics" -> PlayerMenuActionColorRole.OUTLINE
    "menu_timer" -> PlayerMenuActionColorRole.ON_SURFACE_VARIANT
    "volume_on", "volume_off" -> PlayerMenuActionColorRole.INVERSE_PRIMARY
    "menu_delete" -> PlayerMenuActionColorRole.ERROR
    else -> error("Player menu action has no explicit semantic color role: $glyph")
}

internal fun playerMenuActionTint(glyph: String, colors: ColorScheme): Color = when (playerMenuActionColorRole(glyph)) {
    PlayerMenuActionColorRole.PRIMARY -> colors.primary
    PlayerMenuActionColorRole.SECONDARY -> colors.secondary
    PlayerMenuActionColorRole.TERTIARY -> colors.tertiary
    PlayerMenuActionColorRole.ON_SURFACE_VARIANT -> colors.onSurfaceVariant
    PlayerMenuActionColorRole.OUTLINE -> colors.outline
    PlayerMenuActionColorRole.INVERSE_PRIMARY -> colors.inversePrimary
    PlayerMenuActionColorRole.ERROR -> colors.error
}

// ===== 播放器菜单/操作卡片/按钮（自 MainActivity 迁入；private→internal） =====
@Composable
internal fun MilkPlayerBtn(size: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    // 保留兼容：无硬白圆底，乳白透明
    Box(
        Modifier.size(size),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
internal fun PlayerMenuLine(title: String, subtitle: String = "", onClick: () -> Unit) {
    PlayerMenuLineColored(title = title, subtitle = subtitle, fg = Color(0xFF1A1A22.toInt()), onClick = onClick)
}

@Composable
internal fun PlayerMenuLineColored(
    title: String,
    subtitle: String = "",
    fg: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = fg.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun PlayerMenuLineFlash(
    title: String,
    subtitle: String = "",
    fg: Color,
    sel: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val color = if (pressed) sel else fg
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = color.copy(alpha = 0.55f), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun PlayerLyricsSettingSlider(
    spec: PlayerLyricsSettingSpec,
    fg: Color,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onValueChange: (Int) -> Unit,
) {
    // 裸行（用户 2026-11-04：「半透明背景太难看了」）：滑杆行一律无填充无卡框，直接落面板——
    // 旧 cardBackground 半透明底（fg 6.5% + r16 + 8dp 内距）已全仓摘除，三呈现面（⋯ 菜单/设置页/歌词操作框）一致。
    // ACCEPT5-press（稿 §4）：滑杆每跨一格（Int 吸附值变化）发 segmentTick，AppHaptics 内置 40ms 节流。
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    var lastTickValue by remember { mutableStateOf(spec.valueAt(spec.sliderValue)) }
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                spec.title,
                color = fg,
                // J 波：滑杆行标题对齐全站设置行字阶 14 Bold（原 15 SemiBold 自成一派）。
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                spec.valueLabel,
                color = fg.copy(alpha = 0.62f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        AppRoundSlider(
            value = spec.sliderValue,
            onValueChange = { raw ->
                val v = spec.valueAt(raw)
                if (v != lastTickValue) { lastTickValue = v; xyz.cncnlp.soundwave.ui.common.AppHaptics.segmentTick(pressHapticView) }
                onValueChange(v)
            },
            valueRange = spec.sliderRange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (leading == null) 2.dp else 0.dp),
            interactionHeight = 36.dp,
            // v8 稿形态（用户 2026-11-04）：档位滑杆带可见刻度，刻度数 = 该规格的档位数。
            tickSteps = spec.values.size,
        )
    }
}

/**
 * 歌词设置核心段（用户 2026-11-04 合并）：⋯ 菜单「歌词设置」级与长按「歌词操作」表共用同一实现，
 * 只此一份——字号 / 行高档位滑杆（带刻度、裸行）+ 歌词偏移步进器；两入口的专属段（菜单头 /
 * 当前歌词卡与行操作）留在各自入口侧。距离模糊条目已删（渲染钉死固定值，见模型注记）。
 */
@Composable
internal fun PlayerLyricsSettingsCore(
    lyricFontSp: Int,
    lyricGapDp: Int,
    lyricOffset: Int,
    fg: Color,
    onLyricFont: (Int) -> Unit,
    onLyricGap: (Int) -> Unit,
    onLyricOffset: (Int) -> Unit,
) {
    PlayerLyricsSettingsModel.menuSettings(fontSp = lyricFontSp, lineHeightDp = lyricGapDp).forEach { spec ->
        PlayerLyricsSettingSlider(spec = spec, fg = fg) { value ->
            when (spec.key) {
                PlayerLyricsSettingKey.FONT -> onLyricFont(value)
                PlayerLyricsSettingKey.LINE_HEIGHT -> onLyricGap(value)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    PlayerLyricOffsetStepper(offsetMs = lyricOffset, fg = fg, onDelta = onLyricOffset)
}

@Composable
internal fun PlayerMenuActionCard(
    modifier: Modifier = Modifier,
    title: String,
    glyph: String,
    fg: Color,
    accent: Color,
    onClick: () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp)
                .drawBehind {
                    val stroke = 0.7.dp.toPx()
                    drawLine(
                        color = dividerColor,
                        start = androidx.compose.ui.geometry.Offset(0f, stroke / 2f),
                        end = androidx.compose.ui.geometry.Offset(size.width, stroke / 2f),
                        strokeWidth = stroke
                    )
                }
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WhiteGlyph(glyph, 19.dp, androidx.compose.ui.graphics.lerp(fg, accent, 0.14f))
            Spacer(Modifier.width(13.dp))
            Text(title, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 1.0.655 播放器 ⋯ 弹窗行（参考 1000073118）：左侧新线性图标 + 粗体标题 + 右侧灰色副标题，
 * 行间分隔线 = 首页顶栏切换按钮描边色（主题 outline）。透明底、保留裁切水波纹。
 */
@Composable
internal fun PlayerMenuSheetRow(
    modifier: Modifier = Modifier,
    title: String,
    glyph: String,
    fg: Color,
    accent: Color,
    subtitle: String = "",
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outline
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (danger) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.lerp(fg, accent, 0.10f)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(58.dp)
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = xyz.cncnlp.soundwave.ui.common.frostLine(dividerColor), // r657 第二轮：磨砂开时隐藏
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - stroke / 2f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke
                    )
                }
                .pressScale(PressMotion.SCALE_ROW) // r657 第二轮：与设计抽屉行同一按压反馈
                .clip(RoundedCornerShape(10.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.forMenuKey(glyph), null, Modifier.size(22.dp), tint = tint)
            Spacer(Modifier.width(16.dp))
            Text(
                title, color = if (danger) tint else fg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(subtitle, color = muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 弹窗头部：珊瑚色等宽英文眉题 + 大标题（参考 1000073118）。 */
@Composable
internal fun PlayerMenuSheetHeader(eyebrow: String, title: String, fg: Color) {
    Text(
        eyebrow, color = designAccentTextColor(), fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
        fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 3.sp, maxLines = 1
    )
    Spacer(Modifier.height(6.dp))
    Text(title, color = fg, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    Spacer(Modifier.height(14.dp))
}

/** 子级选项行：标题 + 右侧副标题/勾选，与主行同款分隔线。 */
@Composable
internal fun PlayerMenuChoiceRow(title: String, subtitle: String, selected: Boolean, fg: Color, onClick: () -> Unit) {
    val dividerColor = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(54.dp)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(dividerColor, androidx.compose.ui.geometry.Offset(0f, size.height - stroke / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height - stroke / 2f), stroke)
            }
            .clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = if (selected) accent else fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(xyz.cncnlp.soundwave.ui.player.DefaultPlayerGlyphs.Check, null, Modifier.size(20.dp), tint = accent)
        }
    }
}

@Composable
internal fun PlayerMenuPrimaryActionCard(
    modifier: Modifier = Modifier,
    title: String,
    glyph: String,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            WhiteGlyph(glyph, 19.dp, accent)
            Spacer(Modifier.height(3.dp))
            Text(
                title,
                color = Color(0xFF1A1A22.toInt()),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun PlayerMenuTile(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String = "",
    fg: Color,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3EEE6.toInt()))
            .clickable { onClick() }
            .height(SettingsMetrics.HubRowH.dp).padding(horizontal = SettingsMetrics.SectionPadH.dp)
    ) {
        Text(title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = fg.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
internal fun PlayerMenuSheet(onDismiss: () -> Unit, onMode: () -> Unit, onSleep: (Int) -> Unit, onLyricBigger: () -> Unit, onLyricSmaller: () -> Unit, onLyricOffset: (Int) -> Unit) {
    // 底部弹层风格（音质已移到进度条下方，这里不重复）
    // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层；菜单行与点击语义逐字不变（字体/偏移不关层、定时关层）。
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // ACCEPT5（退出动画普查 P1）：定时行/关闭钮走退场句柄（此前直调 onDismiss 瞬时消失）。
        // 本表面当前无生产调用点，按家族标准接线归位，行语义不变（字体/偏移不关层、定时关层）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Player", "更多")
        Spacer(Modifier.height(6.dp))
        MenuLine("歌词字体 +", Icons.Outlined.TextIncrease) { onLyricBigger() }
        MenuLine("歌词字体 -", Icons.Outlined.TextDecrease) { onLyricSmaller() }
        MenuLine("歌词提前 0.5s", Icons.Outlined.Sync) { onLyricOffset(-500) }
        MenuLine("歌词延后 0.5s", Icons.Outlined.Sync) { onLyricOffset(500) }
        MenuLine("定时 15 分钟", Icons.Outlined.Timer) { onSleep(15); dismiss() }
        MenuLine("定时 30 分钟", Icons.Outlined.Timer) { onSleep(30); dismiss() }
        MenuLine("定时 60 分钟", Icons.Outlined.Timer) { onSleep(60); dismiss() }
        xyz.cncnlp.soundwave.ui.common.SheetActionButtons(secondary = "关闭", onSecondary = dismiss, primary = null)
    }
}

@Composable
internal fun MenuLine(label: String, icon: ImageVector, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, label, tint = primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(label, color = primary, fontSize = 14.sp) }
}

@Composable
internal fun PlayerIconBtn(icon: ImageVector, description: String, color: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable { onClick() }
            .padding(12.dp)
            .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
    ) {
        Icon(icon, description, tint = color, modifier = Modifier.size(34.dp))
    }
}

/**
 * r658c P：统一的歌词偏移调节行（播放器「⋯ → 歌词设置」、长按歌词「歌词操作」、设置页共用）。
 * 「提前 0.5s / 延后 0.5s」每次 ±[PlayerLyricsSettingsModel.OFFSET_STEP_MS]，「归零」仅在非 0 时可点；写入同一偏好 lyric_offset_ms。
 */
@Composable
internal fun PlayerLyricOffsetStepper(
    offsetMs: Int,
    fg: Color,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onDelta: (Int) -> Unit,
) {
    val step = PlayerLyricsSettingsModel.OFFSET_STEP_MS
    val line = xyz.cncnlp.soundwave.ui.common.designLineColor()
    @Composable
    fun Pill(label: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
        Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(99.dp))
                .drawBehind {
                    drawRoundRect(line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = description; role = Role.Button }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = fg.copy(alpha = if (enabled) 1f else 0.38f), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text("歌词偏移", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            Text(lyricOffsetLabel(offsetMs), color = fg.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("提前 0.5s", "歌词提前 0.5 秒") { onDelta(-step) }
            Pill("延后 0.5s", "歌词延后 0.5 秒") { onDelta(step) }
            Spacer(Modifier.weight(1f))
            Pill("归零", "歌词偏移归零", enabled = offsetMs != 0) { onDelta(-offsetMs) }
        }
    }
}

internal fun lyricOffsetLabel(offsetMs: Int): String = when {
    offsetMs == 0 -> "0ms · 不偏移"
    offsetMs > 0 -> "+${offsetMs}ms"
    else -> "${offsetMs}ms"
}
