package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.unit.TextUnit
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.AppHaptics
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.TypeScale

/**
 * r655 设置详情页组件（设计稿 v2 `settings-detail`）：
 * 页头标题左对齐（与首页标题同 14dp 左距、无返回箭头、无「返回设置」行）→ 珊瑚眉题 + 衬线大标题 + 说明 →
 * 透明行（13sp 粗标题 / 9.5sp 说明 / 底部 1dp 线）+ 右侧控件。
 */
@Composable
internal fun settingsIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 设计稿 settings-detail-hero：眉题（珊瑚 DM Mono 8sp）+ 衬线 40sp 标题 + 说明。 */
@Composable
fun SettingsDetailHero(kicker: String, title: String, copy: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp)) {
        Text(
            "$kicker · SOUNDWAVE".uppercase(), color = SoundwaveDesignPalette.Accent,
            fontSize = TypeScale.Badge.fontSize, lineHeight = 12.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold,
            letterSpacing = 0.16.em, maxLines = 1,
            modifier = Modifier.semantics { heading() }
        )
        // r657（v3 #11）：页名已在页头，这里不再重复衬线大标题，只留眉题 + 一行说明（[title] 仅作无障碍描述）。
        if (copy.isNotBlank()) Text(
            copy, color = settingsMutedColor(), fontSize = TypeScale.Micro.fontSize, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = "$title：$copy" }
        )
    }
}

/** 详情页小节标题（同一页有多组设置时使用）。 */
@Composable
fun SettingsDetailSection(title: String) {
    Text(
        title.uppercase(), color = settingsMutedColor(), fontSize = TypeScale.Badge.fontSize, fontFamily = DesignFonts.DmMono,
        fontWeight = FontWeight.Bold, letterSpacing = 0.14.em,
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp).semantics { heading() }
    )
}

/**
 * 设计稿 detail-setting-row：透明底、上下 15dp、底部 1dp 线；左侧独立线性图标（每项不同），
 * 标题 13sp 粗 / 说明 9.5sp muted，右侧控件。整行可点（若给 onClick）。
 */
@Composable
fun SettingsDetailRow(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    // V3 逐行配色（用户 2026-11-04）：设置行按 SettingGlyphSpec 传行色；Unspecified = 回退墨色（非设置行调用方）。
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    compact: Boolean = false,
    // SW-12-09 / P4：整行点击驱动的开关行传入切换前状态；行点击完成切换后补 TOGGLE 触感
    // （开关本体被行点击绕过时其自身 onClick 不触发）。非开关行保持 null，行为不变。
    toggleChecked: Boolean? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val view = LocalView.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 60.dp else 64.dp)
                // 按压反馈与全站行同口径（用户裁定 2026-11-04）：只有 pressScale 行档缩放，无涟漪、无整行变色水洗（旧通栏染色已退场）。
                .then(if (onClick != null && enabled) {
                    val ix = remember { MutableInteractionSource() }
                    Modifier.settingsPressFeedback(ix, bleed = 16.dp).clickable(interactionSource = ix, indication = null, onClick = {
                        onClick()
                        if (toggleChecked != null) AppHaptics.toggle(view, !toggleChecked)
                    }).semantics { role = Role.Button }
                } else Modifier)
                .padding(vertical = if (compact) 8.dp else 13.dp)
                .then(if (enabled) Modifier else Modifier.graphicsAlpha(0.45f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(Modifier.width(30.dp).height(34.dp), contentAlignment = Alignment.CenterStart) {
                    Icon(icon, null, tint = if (iconTint == Color.Unspecified) ink else iconTint, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = (-0.01).em, maxLines = if (LocalDensity.current.fontScale >= 1.3f) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(
                    subtitle, color = settingsMutedColor(), fontSize = TypeScale.Micro.fontSize, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showDivider) SettingsHairline()
    }
}

private fun Modifier.graphicsAlpha(a: Float): Modifier = this.alpha(a)

/**
 * r655 新开关：去掉「开启/关闭」文字与外描边。50×28 胶囊：
 * 关 = 墨色 9% 浅槽 + 白色圆钮（柔和投影）；开 = 珊瑚渐变槽（带淡珊瑚光晕）+ 白色圆钮右移。
 * 仅绘制，点击交给所在行或 [onClick]。
 */
@Composable
fun SettingsSwitch(checked: Boolean, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val view = LocalView.current
    val dark = settingsIsDark()
    val accent = SoundwaveDesignPalette.Accent
    // SW-12-09 / P4：仅绘制模式（onClick = null，点击由所在行驱动）时行点击不经过开关本体，
    // 改为监听 checked 变化（跳过首帧）补 TOGGLE 触感；本体可点时仍走下方 onClick 路径，不重复发。
    var prevChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (onClick == null && prevChecked != checked) AppHaptics.toggle(view, checked)
        prevChecked = checked
    }
    // r657 第三轮（用户 10-18：照原型）：app.css .tgl——46×28、关 = surface2 + 1dp 线色内描边、开 = 实心珊瑚；
    // 22dp 白钮 + 0 2 6 rgba(0,0,0,.2)，开时右移 18dp（弹簧）。
    val offTrack = if (dark) Color(0xFF232722) else Color(0xFFECECE6)
    val switchInteraction = remember { MutableInteractionSource() }
    val switchPressed by switchInteraction.collectIsPressedAsState()
    // SW-12-09 / B.5：按下时滑块拉伸 22→26dp（仅交互态，静止尺寸不变）；开态拉伸时右缘锚定（x 21→17）。
    val knobW by animateDpAsState(if (switchPressed) 26.dp else 22.dp, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 1400f), label = "settingsSwitchKnobW")
    val knobX by animateDpAsState(if (checked) 43.dp - (if (switchPressed) 26.dp else 22.dp) else 3.dp, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 520f), label = "settingsSwitchKnob")
    val glow = Color.Transparent
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .then(if (onClick != null) Modifier.clickable(
                enabled = enabled, interactionSource = switchInteraction, indication = null,
                // P1 / SW-12-02 + B.3：切换生效时按目标状态发 TOGGLE 触感（onClick 为切换动作，目标态 = !checked）。
                onClick = { onClick(); AppHaptics.toggle(view, !checked) }
            ) else Modifier)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .padding(vertical = 4.dp)
            .graphicsAlpha(if (enabled) 1f else 0.45f)
    ) {
        Box(
            Modifier.width(46.dp).height(28.dp)
                .clip(shape)
                .then(if (checked) Modifier else Modifier.border(1.dp, settingsLineColor(), shape))
                .background(
                    if (checked) Brush.horizontalGradient(listOf(accent, accent))
                    else Brush.horizontalGradient(listOf(offTrack, offTrack))
                )
        ) {
            // accept6-switchfix（用户 2026-11-04 真机截图）：圆钮禁用 Modifier.shadow——它是均匀围影
            //（ambient+spot 沿轮廓整圈发散），22dp 小钮上形成一整圈灰环、轮廓分面成八边形感，呈
            //「灰环＋八边形芯」靶心样。照 CSS 原型 0 2 6 rgba(0,0,0,.2) 的方向性柔影改画假阴影：
            //钮后垫一层略大、向下偏移的径向渐变圆盘（中心黑 0.20、边缘全透明），只在钮下缘露出一
            //弯柔和月牙；圆钮本体只剩 clip＋实心白，保证真圆实心盘。
            Box(
                Modifier.offset(x = knobX - 1.dp, y = 4.dp).size(width = knobW + 2.dp, height = 24.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.20f),
                            0.7f to Color.Black.copy(alpha = 0.20f),
                            1f to Color.Transparent
                        )
                    )
            )
            Box(
                Modifier.offset(x = knobX, y = 3.dp).size(width = knobW, height = 22.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * J 波（用户 2026-11-03 20:05 裁定）：设置行右侧只保留当前值的纯文字——灰胶囊底与下拉/跳转
 * 箭头全部去掉，全站设置行统一这一种右侧语言。此前 [picker]=true 为浅底胶囊+⌄、false 为
 * 文字+›，两分支呈现自此归一为纯文字（次级色，accent 时珊瑚）；onClick 与所在行 onClick 为
 * 同一回调（打开既有选择弹层/子页，设置域内无内联下拉菜单），保留点按、行为不变。
 */
@Composable
fun SettingsValueChip(value: String, picker: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 32.dp).widthIn(max = 160.dp).clickable(onClick = onClick).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            value, color = if (accent) SoundwaveDesignPalette.Accent else settingsMutedColor(),
            fontSize = TypeScale.BodySmall.fontSize, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * J 波（用户 2026-11-03 20:05 裁定）：导航行右侧的裸 › 一并去掉，跳转行右侧不再渲染任何
 * 元素（整行点按进入子页的交互不变）。函数保留为空实现，存量调用点（主页 NavRow、
 * EditorialValueRow 的动作词行、关于页隐私说明行、播放历史行）零改动即生效。
 */
@Composable
fun SettingsChevron() {
    Box(Modifier.width(0.dp))
}

/**
 * 设计稿 #preferencePickerSheet：底部面板；珊瑚 DM Mono 眉题「XX · OPTION」+ 25sp 标题 + 说明，
 * 选项为 r13 圆角卡（序号圆 + 文字 + 选中勾），选中项珊瑚描边/浅底。
 */
@Composable
fun SettingsPickerSheet(
    kicker: String,
    title: String,
    copy: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // SW-12-04：外壳统一为 DesignSheetDialog（r28 / 遮罩 38% / 把手 46×4 / 进 420 出 220 / 可拖拽关闭）；
    // 眉题 / 标题 / 说明 / 选项行内容与回调保持原样，滚动交给面板（scrollContent = true）。
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // J 波：选项点击走动画关闭句柄（退场后才触发 onDismiss 清状态）；调用方的 onPick 只执行动作、不再清状态。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = SoundwaveDesignPalette.Accent
        Text("$kicker · OPTION".uppercase(), color = accent, fontSize = TypeScale.Badge.fontSize, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em, maxLines = 1)
        Text(title, color = ink, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em, modifier = Modifier.padding(top = 5.dp, bottom = 3.dp).semantics { heading() })
        if (copy.isNotBlank()) Text(copy, color = settingsMutedColor(), fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(13.dp))
        // accept6-uipolish2（用户 2026-11-04 22:21 定版）：短选项（见 settingsPickerUsesTwoColumns）
        // 改两列网格、瓦片更高（纵向 padding 11→17dp）；长文案/长列表保持原竖排全宽。
        // 选中态语言不变（浅底 + 珊瑚描边 + 勾），两列时奇数尾项占半行、左对齐成格。
        if (settingsPickerUsesTwoColumns(options)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.withIndex().chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { (i, label) ->
                            SettingsPickerOptionTile(label, i == selectedIndex, Modifier.weight(1f), verticalPadding = 17.dp) { onPick(i); dismiss() }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { i, label ->
                    SettingsPickerOptionTile(label, i == selectedIndex, Modifier.fillMaxWidth(), verticalPadding = 11.dp) { onPick(i); dismiss() }
                }
            }
        }
    }
}

/**
 * accept6-uipolish2：[SettingsPickerSheet] 的两列归类——选项数 2..10 且每个标签 ≤8 字
 * （悬浮行数/字号/颜色/透明度/百分比这类短文案、数值型）走两列网格；其余（长文案或
 * 数量 >10 的长列表）保持竖排。由选项本身决定，全部调用点零改动、自动归类。
 */
internal fun settingsPickerUsesTwoColumns(options: List<String>): Boolean =
    options.size in 2..10 && options.all { it.length <= 8 }

/** [SettingsPickerSheet] 单个选项瓦片：竖排与两列网格共用，选中态语言一致，仅纵向 padding 不同。 */
@Composable
private fun SettingsPickerOptionTile(label: String, on: Boolean, modifier: Modifier, verticalPadding: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val dark = settingsIsDark()
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = SoundwaveDesignPalette.Accent
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.clip(shape)
            .background(if (on) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (on) accent.copy(alpha = 0.45f) else line, shape)
            .clickable(onClick = onClick)
            .semantics { selected = on; contentDescription = label }
            .padding(horizontal = 12.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // V1（用户 2026-11-04 裁定）：选项左侧不带序号——原 DmMono 数字圆删除，只留选项本体 + 选中勾。
        Text(label, color = ink, fontSize = TypeScale.Caption.fontSize, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (on) Icon(DesignLineGlyphs.Check, "已选择", tint = accent, modifier = Modifier.size(18.dp))
    }
}

/**
 * E12/E13 共享样式预览选项卡（E9 复用；v3 改皮）：白面卡（colorScheme.surface）+ 圆角 18dp +
 * 黑系柔投影（深色主题加深），选中三重表达 = 珊瑚 1.5dp 描边 + 珊瑚标题 + 右上角珊瑚勾徽，
 * 未选 = 发丝线 + 深墨标题；按压 pressScale(SCALE_ROW)。
 * 底栏样式页内选择（E12）与样式 picker 弹层（E13）共用同一组件，预览内容由调用方给
 * （v3 微缩见 StylePreviewMiniatures 的统一舞台/单一封面语言）。
 */
@Composable
fun StylePreviewOptionCard(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    titleFontSize: TextUnit = 15.sp,
    titleReserveTwoLines: Boolean = false,
    onClick: () -> Unit,
    preview: @Composable ColumnScope.() -> Unit,
) {
    val dark = settingsIsDark()
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .shadow(
                2.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.30f else 0.05f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.40f else 0.08f)
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) SoundwaveDesignPalette.Accent else hairline, shape)
            .pressScale(PressMotion.SCALE_ROW)
            .clickable(role = Role.Button, onClickLabel = title) { onClick() }
            .semantics { contentDescription = if (selected) "$title（当前）" else title; this.selected = selected }
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                color = if (selected) SoundwaveDesignPalette.Accent else ink,
                fontSize = titleFontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = DesignFonts.Manrope,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleReserveTwoLines) Modifier.heightIn(min = 36.dp) else Modifier,
            )
            Spacer(Modifier.height(10.dp))
            preview()
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)
                    .shadow(2.dp, CircleShape, clip = false)
                    .size(16.dp).clip(CircleShape).background(SoundwaveDesignPalette.Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(DesignLineGlyphs.Check, "已选择", tint = Color.White, modifier = Modifier.size(9.5.dp))
            }
        }
    }
}

/**
 * E13：带预览卡的样式选择弹层——外壳与 [SettingsPickerSheet] 同族（DesignSheetDialog +
 * 眉题/标题/说明规格逐字一致），选项区改为并排预览卡（[StylePreviewOptionCard]）。
 * 仅供「样式」类设置使用（播放器界面样式 / 详情页封面样式）；选项值域与回写由调用方保证。
 */
@Composable
fun SettingsStylePreviewSheet(
    kicker: String,
    title: String,
    copy: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    cardPreview: @Composable (Int) -> Unit,
) {
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // J 波：同 SettingsPickerSheet，选项点击走动画关闭句柄。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = SoundwaveDesignPalette.Accent
        Text("$kicker · OPTION".uppercase(), color = accent, fontSize = TypeScale.Badge.fontSize, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Bold, letterSpacing = 0.14.em, maxLines = 1)
        Text(title, color = ink, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.035).em, modifier = Modifier.padding(top = 5.dp, bottom = 3.dp).semantics { heading() })
        if (copy.isNotBlank()) Text(copy, color = settingsMutedColor(), fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { i, label ->
                StylePreviewOptionCard(
                    title = label,
                    selected = i == selectedIndex,
                    titleFontSize = 13.sp,
                    titleReserveTwoLines = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(i); dismiss() },
                ) { cardPreview(i) }
            }
        }
    }
}
