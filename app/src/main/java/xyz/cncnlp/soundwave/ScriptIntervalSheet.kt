@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.cncnlp.soundwave

import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun filterIntervalInput(value: TextFieldValue): TextFieldValue {
    val filteredText = value.text.filter { it.isDigit() }.take(4)
    fun filteredOffset(offset: Int): Int = value.text
        .take(offset.coerceIn(0, value.text.length))
        .count { it.isDigit() }
        .coerceAtMost(filteredText.length)
    return TextFieldValue(
        text = filteredText,
        selection = TextRange(filteredOffset(value.selection.start), filteredOffset(value.selection.end)),
        composition = null
    )
}

/** 活跃入口：开关 / 滑杆（30 分钟–24 小时）/ 保存。旧存值经 normalize 照常生效。 */
@Composable
internal fun ScriptScheduleCustomSheet(
    enabled: Boolean,
    minutes: Int,
    onSave: (Boolean, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        ScriptScheduleCustomSheetContent(enabled, minutes, onSave, onDismiss)
    }
}

/** 独立可组合的定时检测弹层内容，草稿只在本次打开时从调用方值初始化。 */
@Composable
internal fun ColumnScope.ScriptScheduleCustomSheetContent(
    enabled: Boolean,
    minutes: Int,
    onSave: (Boolean, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var scheduled by remember { mutableStateOf(enabled) }
    var raw by remember { mutableStateOf(ScriptProbeScheduler.normalizeIntervalMinutes(minutes).toFloat()) }
    var saved by remember { mutableStateOf(false) }
    val value = ScriptProbeScheduler.normalizeIntervalMinutes(raw.toInt())
    // ACCEPT4（审计 §9 B 类）：保存/取消改走退场句柄——保存先落值再退场，onDismiss 归退场完成后（原直调同步卸载）。
    val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
    run {
        xyz.cncnlp.soundwave.ui.common.DesignSettingSheetHeader("Scheduled check", "定时检测")
        xyz.cncnlp.soundwave.ui.settings.SettingsSheetCopy("后台按间隔对已启用脚本做完整检测，结果用于智能排序与一键配置；检测不通过不影响播放。")
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(16.dp))
                .border(1.dp, xyz.cncnlp.soundwave.ui.common.designLineColor(), RoundedCornerShape(16.dp))
                .semantics { stateDescription = if (scheduled) "已开启" else "已关闭" }
                .toggleable(
                    value = scheduled,
                    role = Role.Switch,
                    onValueChange = { scheduled = it },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("开启定时检测", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (scheduled) "每 $value 分钟检测一次" else "已关闭", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            // The row is the sole switch hit target/semantic node; this is visual-only.
            Box(Modifier.clearAndSetSemantics {}) {
                xyz.cncnlp.soundwave.ui.settings.SettingsSwitch(checked = scheduled, onClick = null)
            }
        }
        // 读数 ≥60 分钟按小时显示，滑杆右端直接到 24 小时。
        val (rv, ru) = xyz.cncnlp.soundwave.ui.settings.sleepReadout(value)
        xyz.cncnlp.soundwave.ui.settings.SettingsSheetReadout(rv, ru, "MAX 24H")
        xyz.cncnlp.soundwave.ui.settings.SettingsSheetSlider(raw, ScriptProbeScheduler.MIN_INTERVAL_MINUTES.toFloat()..ScriptProbeScheduler.MAX_INTERVAL_MINUTES.toFloat(), "30 MIN", "24 H") {
            scheduled = true
            raw = it
        }
        xyz.cncnlp.soundwave.ui.settings.SettingsSheetButtons(
            secondary = "取消",
            onSecondary = dismiss,
            primary = "保存",
            primaryEnabled = !saved,
        ) {
            if (!saved) {
                saved = true
                onSave(scheduled, value)
                dismiss()
            }
        }
    }
}

