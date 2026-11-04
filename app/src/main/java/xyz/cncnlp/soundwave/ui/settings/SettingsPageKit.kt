package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.SettingsMetrics
import xyz.cncnlp.soundwave.SoundwaveDanger
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding

/*
 * r656 设置维护类页面（缓存管理 / 播放取链报告 / 备份与恢复 / 数据流向 / 崩溃诊断 / 关于 APP）共用的小部件。
 * 全部建立在 r655 设置详情页语言之上：SettingsDetailHero（珊瑚眉题 + 衬线大标题）→ 透明行 + 1dp 线；
 * 需要承载数据的地方用 r18 线框卡（同首页切换按钮线框色），确认类弹窗统一走 DesignSheetDialog 抽屉。
 * 不含任何无限动画。
 */

/** 维护页专用线性图标（24×24 / stroke 1.8 / 圆端点），与 SettingsLineGlyphs 同一笔法。 */
object SettingsPageGlyphs {
    private fun g(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    val Limit: ImageVector by lazy { g("swp_limit", "M4 18h16", "M6 18V9M10 18V6M14 18v-5M18 18v-8", "M3.5 4.5h17") }
    val Clean: ImageVector by lazy { g("swp_clean", "M14.5 3.5 20.5 9.5", "M12.5 5.5l6 6-7.5 7.5H5v-6z", "M8 13l3 3") }
    val Copy: ImageVector by lazy { g("swp_copy", "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M5 15H4.5A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5V5") }
    val Send: ImageVector by lazy { g("swp_send", "M21 3 10.5 13.5", "M21 3l-6.5 18-4-7.5L3 9.5z") }
    val Export: ImageVector by lazy { g("swp_export", "M12 15V3.5M7.5 8 12 3.5 16.5 8", "M4.5 13v6A1.5 1.5 0 0 0 6 20.5h12a1.5 1.5 0 0 0 1.5-1.5v-6") }
    val Import: ImageVector by lazy { g("swp_import", "M12 3.5V15M7.5 10.5 12 15l4.5-4.5", "M4.5 13v6A1.5 1.5 0 0 0 6 20.5h12a1.5 1.5 0 0 0 1.5-1.5v-6") }
    val CloudUp: ImageVector by lazy { g("swp_cloud_up", "M17.5 19H6a4 4 0 0 1-.7-7.9A7 7 0 0 1 19 9a5 5 0 0 1-1.5 10z", "M12 16v-5M9.8 13.2 12 11l2.2 2.2") }
    val CloudDown: ImageVector by lazy { g("swp_cloud_down", "M17.5 19H6a4 4 0 0 1-.7-7.9A7 7 0 0 1 19 9a5 5 0 0 1-1.5 10z", "M12 11v5M9.8 13.8 12 16l2.2-2.2") }
    val Server: ImageVector by lazy { g("swp_server", "M4.5 4h15v6h-15zM4.5 14h15v6h-15z", "M8 7h.01M8 17h.01") }
    val Vault: ImageVector by lazy { g("swp_vault", "M4 5h16v14H4z", "M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5z", "M7 19v1.5M17 19v1.5") }
    val Convert: ImageVector by lazy { g("swp_convert", "M4 8h13l-3.5-3.5", "M20 16H7l3.5 3.5") }
    val Eye: ImageVector by lazy { g("swp_eye", "M2.5 12s3.5-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5z") }
    val Device: ImageVector by lazy { g("swp_device", "M8 2.5h8a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z", "M11 18.5h2") }
    val Bug: ImageVector by lazy { g("swp_bug", "M8.5 9a3.5 3.5 0 0 1 7 0v5a3.5 3.5 0 0 1-7 0z", "M12 9v8.5M8.5 12H4M20 12h-4.5M5 7l3.5 2.5M19 7l-3.5 2.5M5 18l3.5-2M19 18l-3.5-2", "M10 5.5 9 3.5M14 5.5l1-2") }
    val Idea: ImageVector by lazy { g("swp_idea", "M9 17.5h6M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z") }
    val Refresh: ImageVector by lazy { g("swp_refresh", "M20 11a8 8 0 0 0-14.5-4.5L3.5 9", "M3.5 4v5h5", "M4 13a8 8 0 0 0 14.5 4.5l2-2.5", "M20.5 20v-5h-5") }
    val License: ImageVector by lazy { g("swp_license", "M6.5 3h8l4 4v13.5a.5.5 0 0 1-.5.5H6.5a.5.5 0 0 1-.5-.5v-17a.5.5 0 0 1 .5-.5z", "M14 3v4.5h4.5", "M9 12h6M9 15.5h6M9 8.5h2.5") }
    val Shield: ImageVector by lazy { g("swp_shield", "M12 2.8 4.5 5.8v5.7c0 4.6 3.2 8.4 7.5 9.7 4.3-1.3 7.5-5.1 7.5-9.7V5.8z", "m9 12 2.2 2.2L15.5 10") }
    val Globe: ImageVector by lazy { g("swp_globe", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z", "M3.5 9h17M3.5 15h17", "M12 3c2.6 2.6 3.8 5.6 3.8 9s-1.2 6.4-3.8 9c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3z") }
    val Code: ImageVector by lazy { g("swp_code", "m8 7-5 5 5 5M16 7l5 5-5 5") }
    val Logout: ImageVector by lazy { g("swp_logout", "M14 4.5h4.5a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H14", "M10 16.5 5.5 12 10 7.5M5.5 12H16") }
    val Box: ImageVector by lazy { g("swp_box", "M12 3 3.5 7.5v9L12 21l8.5-4.5v-9z", "M3.5 7.5 12 12l8.5-4.5M12 12v9") }
    val Pulse: ImageVector by lazy { g("swp_pulse", "M2.5 12h4l2.5-6.5 4.5 13 2.5-6.5h5.5") }
}

/** 维护页统一滚动容器：左右 14dp，与设置详情页同距；底部让出 Dock。 */
@Composable
fun SettingsPageList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = SettingsMetrics.SubPadH.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = dockContentBottomPadding()),
        content = content
    )
}

/** 卡片线框色与底色：日间白底 + #D8D9D2 线；夜间 #191C19 + #323730。 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    // r657 第三轮：子页面卡片与设置主页原型 .grp 统一为 r24 表面色卡 + 1dp 线框。
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, designLineColor(), shape)
            .padding(padding),
        content = content
    )
}

/** 小节眉题：珊瑚小圆点 + DM Mono 大写（比 SettingsDetailSection 多一个标记点，用于卡片之间的分组）。 */
@Composable
fun SettingsPageSection(title: String, trailing: String = "", titleColor: Color? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(SoundwaveDesignPalette.Accent))
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(), color = titleColor ?: settingsMutedColor(), fontSize = 9.sp, fontFamily = DesignFonts.DmMono,
            fontWeight = FontWeight.Bold, letterSpacing = 0.14.em, maxLines = 1, modifier = Modifier.weight(1f)
        )
        if (trailing.isNotBlank()) Text(
            trailing, color = SoundwaveDesignPalette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.then(if (onTrailing != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTrailing) else Modifier)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

enum class SettingsButtonKind { Primary, Ghost, Danger }

/** 胶囊按钮：Primary = 墨色实底（夜间反白）；Ghost = 线框；Danger = 线框 + 危险色字。高 40dp。 */
@Composable
fun SettingsActionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: SettingsButtonKind = SettingsButtonKind.Ghost,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(999.dp)
    val fg = when (kind) {
        SettingsButtonKind.Primary -> paper
        SettingsButtonKind.Ghost -> ink
        SettingsButtonKind.Danger -> SoundwaveDanger
    }
    Row(
        modifier.heightIn(min = 40.dp).clip(shape)
            .then(
                if (kind == SettingsButtonKind.Primary) Modifier.background(ink)
                else Modifier.border(1.dp, if (kind == SettingsButtonKind.Danger) SoundwaveDanger.copy(alpha = 0.45f) else designLineColor(), shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = text }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
    }
}

/** 分段选择（筛选 / 类型）：线框胶囊，选中项墨色实底。 */
@Composable
fun SettingsSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier.clip(shape).border(1.dp, designLineColor(), shape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            // ACCEPT6-motiondraft（稿通则：底色/字色 .3s 级过渡）：选中填充与字色改渐变，
            // 不再硬切换；布局与逐项独立胶囊形态不变（滑动指示块属布局变更，不采纳）。
            val segBg by animateColorAsState(
                if (on) ink else Color.Transparent,
                tween(xyz.cncnlp.soundwave.ui.motion.MotionTokens.Duration.Medium, xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.Emphasized),
                label = "settingsSegmentedBg"
            )
            val segFg by animateColorAsState(
                if (on) paper else settingsMutedColor(),
                tween(xyz.cncnlp.soundwave.ui.motion.MotionTokens.Duration.Medium, xyz.cncnlp.soundwave.ui.motion.MotionTokens.Easing.Emphasized),
                label = "settingsSegmentedFg"
            )
            Box(
                Modifier.weight(1f).heightIn(min = 32.dp).clip(shape)
                    .background(segBg)
                    .clickable { onSelect(i) }
                    .semantics { this.selected = on; role = Role.Tab; contentDescription = label }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = segFg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** 统计小格：DM Mono 眉题 + 衬线数字 + 单位。 */
@Composable
fun SettingsStatTile(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier) {
        Text(label.uppercase(), color = settingsMutedColor(), fontSize = 8.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            Text(value, color = accent ?: MaterialTheme.colorScheme.onBackground, fontSize = 26.sp, lineHeight = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em, maxLines = 1)
            if (unit.isNotBlank()) Text(unit, color = settingsMutedColor(), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 3.dp, bottom = 4.dp))
        }
    }
}

/** 状态小圆点。 */
@Composable
fun SettingsStatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** 等宽日志块：浅底 r12，DM Mono 10sp；可限定最大高度并内部滚动。 */
@Composable
fun SettingsMonoBlock(text: String, modifier: Modifier = Modifier, maxHeight: Dp = 220.dp) {
    val dark = settingsIsDark()
    val bg = if (dark) Color.White.copy(alpha = 0.05f) else Color(0xFF131915).copy(alpha = 0.04f)
    Box(
        modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(12.dp)).background(bg)
            .verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f), fontSize = 10.sp, lineHeight = 15.sp, fontFamily = DesignFonts.DmMono)
    }
}

/** 键值行：左 muted 标签，右粗体值；行间 1dp 线。 */
@Composable
fun SettingsKeyValue(label: String, value: String, showDivider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = settingsMutedColor(), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.DmMono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showDivider) SettingsHairline()
    }
}

/** 空状态：居中 muted 文案。 */
@Composable
fun SettingsEmptyNote(text: String) {
    Text(text, color = settingsMutedColor(), fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
}

/** 抽屉按钮：确认（Primary 墨底 / danger 危险色底）与取消（线框）；用于 [SettingsConfirmSheet]。 */
@Composable
fun SettingsSheetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(16.dp)
    val bg = when {
        // SW-12-09 / P1 契约：危险色唯一来源 SoundwaveDanger（Theme.kt，与原 DesignSheetDanger 同值 0xFFB84F43）。
        danger -> SoundwaveDanger
        primary -> ink
        else -> Color.Transparent
    }
    val fg = if (danger || primary) MaterialTheme.colorScheme.surface else ink
    Row(
        modifier.heightIn(min = 50.dp).clip(shape)
            .background(bg)
            .then(if (!danger && !primary) Modifier.border(1.dp, designLineColor(), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides fg,
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                color = fg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope
            )
        ) { content() }
    }
}

/**
 * 统一设计风格的确认抽屉（取代维护页里的 Material AlertDialog）：珊瑚眉题 + 24sp 标题 → 正文 → 底部「取消 / 确认」并排。
 * 形参刻意与 AlertDialog 对齐（onDismissRequest / text / confirmButton / dismissButton），保持各确认流程语义不变。
 */
@Composable
fun SettingsConfirmSheet(
    onDismissRequest: () -> Unit,
    eyebrow: String,
    title: String,
    text: @Composable ColumnScope.() -> Unit,
    confirmButton: @Composable RowScope.() -> Unit,
    dismissButton: (@Composable RowScope.() -> Unit)? = null
) {
    DesignSheetDialog(onDismiss = onDismissRequest) {
        DesignSheetHeader(eyebrow, title)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f), fontSize = 12.sp, lineHeight = 19.sp
                )
            ) { text() }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (dismissButton != null) dismissButton()
            confirmButton()
        }
        Spacer(Modifier.height(6.dp))
    }
}
