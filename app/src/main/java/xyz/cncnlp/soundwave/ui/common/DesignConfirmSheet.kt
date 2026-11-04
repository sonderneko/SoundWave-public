package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.SoundwaveDanger
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

/*
 * SW-12-08 统一确认抽屉（替代散落的 Material AlertDialog）：基于 DesignSheetDialog 外壳——
 * 标题 24sp + 正文 14sp/20sp + 底部按钮行。规则（审计 B.4）：取消在左（描边）、确认在右（实心）；
 * danger 时确认钮用全仓唯一危险色 SoundwaveDanger 底白字；只有确认一个按钮时整宽实心。
 */

@Composable
fun DesignConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String? = "取消",
    danger: Boolean = false,
    // 验收 B 波追加：忙碌态支持（默认 true = 原行为）。false 时整层不可关、两键不可点并降透明，
    // 供「退出登录安全保存中」「撤销副本执行中」这类带 busy 语义的确认框从 AlertDialog 迁入时保持等价行为。
    buttonsEnabled: Boolean = true,
    // ACCEPT5（退出动画普查 T27）：确认后即退场的调用点（如删除脚本——动作同步完成、无 busy 留驻）
    // 传 true：确认钮执行 onConfirm 后接着走退场句柄。默认 false 保持 busy 留驻类调用点现状。
    dismissOnConfirm: Boolean = false,
    // ACCEPT5（退出动画普查 M5）：异步完成点关闭信号——宿主完成异步后置 true 请求动画退场，
    // 取代宿主在完成点同步清可见状态（瞬时消失）；可见状态仍由退场后的 onDismiss 清理。
    closeSignal: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, dismissEnabled = buttonsEnabled) {
        // 收尾棒 J8 句柄补齐（仅取消钮）：取消走关闭句柄退场——四处调用点的取消均无副效应、
        // busy 时按钮与外壳 dismissEnabled 双重门控，安全。确认钮默认不自动退场：URL 导入/撤销副本/
        // 退出登录三处为 busy 留驻语义（确认后弹层须保持开启至异步完成）；同步完成的调用点
        // 经 dismissOnConfirm 归位退场，异步完成点经 closeSignal 在完成时退场（ACCEPT5）。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        androidx.compose.runtime.LaunchedEffect(closeSignal) { if (closeSignal) dismiss() }
        DesignSheetHeader(if (danger) "Warning" else "Confirm", title)
        Spacer(Modifier.height(14.dp))
        Text(
            body, color = colors.onSurface.copy(alpha = 0.78f), fontSize = 14.sp, lineHeight = 20.sp,
            fontFamily = DesignFonts.Manrope
        )
        Spacer(Modifier.height(22.dp))
        val shape = RoundedCornerShape(999.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cancelLabel != null) {
                Box(
                    Modifier.weight(1f).height(48.dp).pressScale(PressMotion.SCALE_ROW, enabled = buttonsEnabled).clip(shape)
                        .alpha(if (buttonsEnabled) 1f else 0.45f)
                        .border(1.dp, designLineColor(), shape)
                        .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (buttonsEnabled) dismiss() }
                        .semantics { contentDescription = cancelLabel; role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Text(cancelLabel, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope)
                }
            }
            Box(
                Modifier.weight(1f).height(48.dp).pressScale(PressMotion.SCALE_ROW, enabled = buttonsEnabled).clip(shape)
                    .alpha(if (buttonsEnabled) 1f else 0.45f)
                    // dialog-polish 波：非危险主钮由墨色对齐设计稿珊瑚口径（与 SettingsSheetButtons/SheetActionButtons 统一）；danger 仍唯一危险色。
                    .background(if (danger) SoundwaveDanger else SoundwaveDesignPalette.Accent)
                    .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (buttonsEnabled) { onConfirm(); if (dismissOnConfirm) dismiss() } }
                    .semantics { contentDescription = confirmLabel; role = Role.Button }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    confirmLabel,
                    color = Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
