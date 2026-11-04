package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.AppHaptics
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.PressMotion

/**
 * 设置页行的「指针所在」状态（设计稿 `.setting-row:hover` / `::before` 竖条）。
 *
 * Compose 的 pressed 只属于按下时的那一行，手指拖到别的行就会取消；设计稿要求手指/鼠标移到哪一行，
 * 哪一行就高亮并弹出左侧竖条。做法：列表容器在 Initial 阶段只“观察”指针位置（不消费事件，滚动/点击照常），
 * 每行用自己的 boundsInRoot 判断指针是否落在行内。
 */
@Stable
class SettingsPointerTracker {
    var position by mutableStateOf<Offset?>(null)
}

val LocalSettingsPointer = staticCompositionLocalOf<SettingsPointerTracker?> { null }

/** 放在设置列表容器上：记录按下中的手指或悬停中的鼠标位置（root 坐标）。 */
fun Modifier.trackSettingsPointer(tracker: SettingsPointerTracker): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    this
        .onGloballyPositioned { coords = it }
        .pointerInput(tracker) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val mouseHover = change.type == PointerType.Mouse || change.type == PointerType.Stylus
                    val active = when (event.type) {
                        PointerEventType.Exit, PointerEventType.Release -> mouseHover && event.type == PointerEventType.Release
                        else -> change.pressed || mouseHover
                    }
                    val c = coords
                    tracker.position = if (active && c != null && c.isAttached) c.localToRoot(change.position) else null
                }
            }
        }
}

/** 设计稿分组色（.settings-group:nth-child(n) --group-mark）。 */
object SettingsGroupMarks {
    val Playback = Color(0xFFFF6255)
    val Appearance = Color(0xFF9D72E0)
    val Audio = Color(0xFF3A9FC8)
    val Sources = Color(0xFF9EC33F)
    val Downloads = Color(0xFFE6953B)
    val Data = Color(0xFF38A98D)
    val Support = Color(0xFFD45F8A)
}

@Composable
internal fun settingsLineColor(): Color =
    ThemeTokens.Settings.line(MaterialTheme.colorScheme.background.luminance() < 0.5f)

@Composable
internal fun settingsMutedColor(): Color =
    ThemeTokens.Settings.muted(MaterialTheme.colorScheme.background.luminance() < 0.5f)

/** 1dp 设计稿分隔线（var(--line)）。 */
@Composable
fun SettingsHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(ThemeTokens.Settings.Hairline).background(xyz.cncnlp.soundwave.ui.common.frostLine(settingsLineColor())))
}

/**
 * 设计稿设置行：64dp，左右 7dp，行内图标/文字/尾部。
 * 按压反馈（用户裁定 2026-11-04，accept5-press 具名必修面）：只许 pressScale 行档缩放，
 * 禁止任何整行变色水洗——旧 r657/r658 的分组色通栏染色（settingsPressTint）已按此裁定退场，
 * 截图实证其呈淡粉矩形水洗、违站规且稿里没有。开关行触感口径不变（见 [toggleChecked]）。
 */
@Composable
fun SettingsDesignRow(
    mark: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = ThemeTokens.Settings.RowMinHeight,
    horizontalPadding: androidx.compose.ui.unit.Dp = ThemeTokens.Settings.RowHorizontalPadding,
    // SW-12-09 / P4：整行点击驱动的开关行传入切换前状态；行点击完成切换后补 TOGGLE 触感
    // （点在开关本体上时由 SettingsSwitch 自身发触感且行点击不重复触发）。非开关行保持 null。
    toggleChecked: Boolean? = null,
    content: @Composable RowScope.() -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .pressScale(PressMotion.SCALE_ROW, enabled = onClick != null)
            .then(
                if (onClick != null) Modifier
                    .clickable(interactionSource = interaction, indication = null) {
                        onClick()
                        if (toggleChecked != null) AppHaptics.toggle(view, !toggleChecked)
                    }
                    .semantics { role = Role.Button }
                else Modifier
            )
            .padding(horizontal = horizontalPadding, vertical = ThemeTokens.Settings.RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** r655：主页内联开关改为新版 [SettingsSwitch]（去掉「开启/关闭」文字与外描边）。 */
@Composable
fun SettingsOuterToggle(on: Boolean, onClick: () -> Unit) {
    SettingsSwitch(checked = on, onClick = onClick)
}

/**
 * 主页值行右侧呈现。SW-12-09 起归一为选择式 Value 样式（SettingsValueChip picker = true：
 * 值 12sp + ⌄），与跳转 Nav 的 ›、开关 Toggle 明确区分；函数名保留兼容存量调用点。
 */
@Composable
fun SettingsOuterCycle(value: String, onClick: () -> Unit) {
    SettingsValueChip(value = value, picker = true, onClick = onClick)
}


/**
 * 主 Tab / 子页面底部留白：统一委托给 [xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding]
 * （迷你条 + 底栏 + 系统导航栏 + 16dp，随 Dock 样式与迷你条可见性变化），保证最后一项不被遮挡且可点。
 */
@Composable
fun designPageBottomInset(): androidx.compose.ui.unit.Dp = xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding()

/**
 * r655：智能换源等页面的开关与设置页统一为 [SettingsSwitch]（仅绘制，点击由所在行处理）。
 * SW-12-09 / P4：行点击切换后的 TOGGLE 触感由 [SettingsSwitch] 仅绘制模式的 checked 变化监听补发。
 */
@Composable
fun DesignSwitchVisual(checked: Boolean, enabled: Boolean = true) {
    SettingsSwitch(checked = checked, enabled = enabled)
}

/** 设计稿 .detail-setting-card：白色（夜间 surface）r16 卡、1dp 线框。 */
fun Modifier.designCard(dark: Boolean): Modifier =
    this.clip(androidx.compose.foundation.shape.RoundedCornerShape(ThemeTokens.Settings.DetailCardRadius))
        .background(if (dark) SoundwaveDesignPalette.NightSurface else SoundwaveDesignPalette.Surface)
        .border(ThemeTokens.Settings.Hairline, ThemeTokens.Settings.line(dark), androidx.compose.foundation.shape.RoundedCornerShape(ThemeTokens.Settings.DetailCardRadius))

/** 按住加深时长（150–250ms 区间取 220ms）。保留：Batch2PureContractTest 钉住的令牌值；其染色应用已退场（见下）。 */
const val SettingsPressDeepenMs = ThemeTokens.Settings.PressDeepenMs

/** r658c #9：分组色按压底色的加深时长。保留：同上，令牌值被契约测试钉住，应用已退场。 */
const val SettingsPressDeepenTintMs = ThemeTokens.Settings.TintedPressDeepenMs

/**
 * 子页面行用按压反馈，与 [SettingsDesignRow] 同一口径：只有 pressScale 行档缩放、无任何变色水洗
 * （用户裁定 2026-11-04；旧实现为 settingsPressTint 通栏染色，已退场）。[interaction]/[bleed]
 * 参数保留仅为调用点兼容（各调用点均已 indication = null），不再参与绘制。
 */
fun Modifier.settingsPressFeedback(interaction: MutableInteractionSource, bleed: androidx.compose.ui.unit.Dp = 0.dp): Modifier =
    this.pressScale(PressMotion.SCALE_ROW)
