package xyz.cncnlp.soundwave

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog

@Composable
internal fun rememberPlaybackTransitionSeconds(prefs: android.content.SharedPreferences): MutableState<Int> {
    val seconds = remember(prefs) { mutableStateOf(PlaybackTransitionSettingsStore.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PlaybackTransitionSettingsStore.KEY_DURATION) {
                seconds.value = PlaybackTransitionSettingsStore.read(prefs)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return seconds
}

/** Shared by the player overflow menu and MePage; reads the same profile-scoped prefs as MePage. */
@Composable
internal fun PlaybackTransitionSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime() }
    val prefs = remember(context, owner) { ContextPreferences.open(context, "soundwave", 0, owner) }
    val secondsState = rememberPlaybackTransitionSeconds(prefs)
    val seconds by secondsState

    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // ACCEPT4（审计 §9 B 类）：选项行改走退场句柄（原直调 onDismiss 同步卸载、退场动画被跳过）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onDismiss)
        PlayerMenuSheetHeader("PLAYBACK TRANSITION", "淡入淡出", MaterialTheme.colorScheme.onSurface)
        Text(
            "自动曲尾预切仅限顺序播放，且下一首已缓冲可播并满足过渡条件；手动下一首也仅在下一首已就绪时淡化。条件不满足就正常切歌，不影响首播或暂停后的恢复。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        PlaybackTransitionSettingsStore.OPTIONS_SECONDS.forEach { option ->
            val label = if (option == 0) "关闭" else "$option 秒"
            PlayerMenuChoiceRow(label, if (option == 0) "正常切歌" else "固定交叉淡化时长", seconds == option, MaterialTheme.colorScheme.onSurface) {
                PlaybackTransitionSettingsStore.write(prefs, option)
                secondsState.value = PlaybackTransitionSettingsStore.read(prefs)
                // A 波修复：与其他选择器一致，选中即关闭弹层（此前只写值不关，用户反馈「选了以后对话框没消失」）。
                dismiss()
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("智能过渡", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "尚未启用，目前不可设置。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(18.dp))
    }
}
