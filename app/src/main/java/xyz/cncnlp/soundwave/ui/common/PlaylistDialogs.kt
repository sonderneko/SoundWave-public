package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.SoundwaveDanger
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetCopy

/** Presentation only: creation defaults, import parsing and rename persistence stay with callers. */
@Composable
internal fun PlaylistInputDialog(
    title: String,
    eyebrow: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    // 收尾棒 J8 句柄补齐：onCancel 收窄为「取消时的即时副效应」——不得在此清可见状态；
    // 可见状态的清除一律归 onDismiss，由关闭句柄在退场结束后触发（此前默认 = onDismiss 直清，
    // 按钮关闭永远绕过退场动画）。确认同理：onConfirm 只执行动作，关闭走句柄。
    onCancel: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        Box(Modifier.semantics { heading() }) { DesignSheetHeader(eyebrow, title) }
        Spacer(Modifier.height(ThemeTokens.PlaylistDialog.FieldGap))
        SheetInputField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // Done only hides the keyboard, as before; persistence requires the explicit button.
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
        )
        SettingsSheetCopy(supportingText)
        // SW-09-04（G 波）：防重复提交——确认是一次性动作，退场动画窗口内的连点不再重复触发 onConfirm
        //（新建防双份歌单、导入防双份请求、改名幂等亦同）。忙碌/错误展示：三者均为本地同步操作无异步段；
        // 在线导入是关窗后后台执行，结果以 AppNotice 成功/失败 + 触感反馈（MainActivity 侧）。
        var submitted by remember { mutableStateOf(false) }
        SheetActionButtons(
            secondary = "取消", onSecondary = { onCancel(); dismiss() },
            primary = confirmLabel, primaryEnabled = confirmEnabled && !submitted,
            onPrimary = { if (confirmEnabled && !submitted) { submitted = true; onConfirm(); dismiss() } }
        )
    }
}

@Composable
internal fun CreatePlaylistDialog(
    name: String, onNameChange: (String) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onCancel: () -> Unit
) = PlaylistInputDialog(
    title = "新建歌单", eyebrow = "Playlist", value = name, onValueChange = onNameChange,
    label = "歌单名称", placeholder = "输入歌单名称",
    supportingText = "可编辑歌单名称；留空时使用“新建歌单”。", confirmLabel = "创建",
    onConfirm = onConfirm, onDismiss = onDismiss, onCancel = onCancel
)

@Composable
internal fun RenamePlaylistDialog(
    name: String, onNameChange: (String) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) = PlaylistInputDialog(
    title = "编辑名称", eyebrow = "Playlist", value = name, onValueChange = onNameChange,
    label = "歌单名称", placeholder = "输入歌单名称",
    supportingText = "修改歌单的显示名称；留空保存时保留原名称。", confirmLabel = "保存",
    onConfirm = onConfirm, onDismiss = onDismiss
)

/**
 * 改名弹层近景规格的按钮行（歌单编辑器、账号改名/注销等输入弹层共用，规格同一份实现）：
 * 不等宽并排胶囊——取消较窄（白底发丝描边、深墨粗字），确认更宽（约 60%：珊瑚渐变实心 +
 * 柔和珊瑚光晕、白粗字）；[primaryDanger] 时确认键为全仓唯一危险色实心（SW-12-08 家族语言）。
 * secondary / primary 任一为 null 时，另一键整宽。
 */
@Composable
internal fun SheetActionButtons(
    secondary: String?,
    onSecondary: () -> Unit,
    primary: String?,
    primaryEnabled: Boolean = true,
    primaryDanger: Boolean = false,
    onPrimary: () -> Unit = {},
    secondaryEnabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val tokens = ThemeTokens.PlaylistDialog
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.ButtonGap)
    ) {
        if (secondary != null) {
            val secondaryInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(if (primary != null) tokens.SecondaryWeight else 1f)
                    .height(tokens.ButtonHeight)
                    .pressScale(PressMotion.SCALE_ROW, enabled = secondaryEnabled)
                    .clip(shape)
                    .alpha(if (secondaryEnabled) 1f else 0.45f)
                    .background(colors.surface)
                    .border(1.dp, designLineColor(), shape)
                    .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                    .clickable(enabled = secondaryEnabled, interactionSource = secondaryInteraction, indication = null, onClick = onSecondary)
                    .semantics { contentDescription = secondary; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Text(secondary, color = colors.onSurface, fontSize = tokens.ButtonTextSize, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
            }
        }
        if (primary != null) {
            val primaryInteraction = remember { MutableInteractionSource() }
            val glowColor = if (primaryDanger) SoundwaveDanger else SoundwaveDesignPalette.Accent
            Box(
                Modifier.weight(if (secondary != null) tokens.PrimaryWeight else 1f)
                    .height(tokens.ButtonHeight)
                    .pressScale(PressMotion.SCALE_ROW, enabled = primaryEnabled)
                    .cssShadow(shape, glowColor.copy(alpha = 0.42f), blur = 16.dp, offsetY = 6.dp)
                    .clip(shape)
                    .alpha(if (primaryEnabled) 1f else 0.45f)
                    .then(
                        if (primaryDanger) Modifier.background(SoundwaveDanger)
                        else Modifier.background(
                            Brush.linearGradient(
                                listOf(
                                    SoundwaveDesignPalette.AccentLight,
                                    SoundwaveDesignPalette.Accent,
                                    SoundwaveDesignPalette.AccentDeep
                                )
                            )
                        )
                    )
                    .rippleGlow(Color.White.copy(alpha = 0.18f))
                    .clickable(enabled = primaryEnabled, interactionSource = primaryInteraction, indication = null, onClick = onPrimary)
                    .semantics { contentDescription = primary; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Text(primary, color = Color.White, fontSize = tokens.ButtonTextSize, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
            }
        }
    }
}

/**
 * 设计稿输入框（用户近景规格，歌单编辑器与账号输入弹层共用同一份实现）：
 * 大圆角近胶囊、白/极浅底 + 发丝描边、无浮动标签——[label] 退为字段的无障碍 contentDescription；
 * 内文 19sp（ThemeTokens.PlaylistDialog.FieldTextSize）。颜色角色与旧描边框一致（聚焦 primary 边、
 * surface 底、onSurface 文字），仅形态按规格变更。
 */
@Composable
internal fun SheetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = MaterialTheme.colorScheme
    // ACCEPT6（用户真机反馈 2026-11-04）：输入状态改持 TextFieldValue。原 String 重载下选区/
    // 组合区由字段内部临时状态托管、随宿主 String 回填重同步而重置——改名时删空原名再输入，
    // 光标跳回最前面、文字从光标后插入。现由本字段持有选区：初值与程序化回填（换目标歌单等）
    // 都把选区置于末尾，用户编辑的选区/组合区原样保留，仅文本变化时以 String 上抛（调用方 API 不变）。
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }
    // ACCEPT6 dialogfrost：聚焦态按稿——珊瑚边（既有 focusedBorderColor = 主题珊瑚）+ 外圈柔光晕。
    val sheetInputInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val sheetInputFocused by sheetInputInteraction.collectIsFocusedAsState()
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { edited ->
            fieldValue = edited
            if (edited.text != value) onValueChange(edited.text)
        },
        interactionSource = sheetInputInteraction,
        modifier = modifier.fillMaxWidth().sheetFocusGlow(sheetInputFocused, RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius)).semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        placeholder = {
            Text(
                placeholder,
                fontSize = ThemeTokens.PlaylistDialog.FieldTextSize,
                fontFamily = DesignFonts.Manrope,
                color = colors.onSurfaceVariant
            )
        },
        textStyle = TextStyle(
            fontSize = ThemeTokens.PlaylistDialog.FieldTextSize,
            fontFamily = DesignFonts.Manrope,
            fontWeight = FontWeight.Medium
        ),
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = designLineColor(),
            disabledBorderColor = designLineColor(),
            focusedTextColor = colors.onSurface,
            unfocusedTextColor = colors.onSurface,
            disabledTextColor = colors.onSurfaceVariant,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surface,
            cursorColor = colors.primary
        ),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}
