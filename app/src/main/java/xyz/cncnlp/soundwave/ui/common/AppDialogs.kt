package xyz.cncnlp.soundwave.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ApiClient
import xyz.cncnlp.soundwave.BuildConfig

// 自 MainActivity.AppRoot 迁出的纯展示/确认对话框（P1-1 SLICE 2）：
// show-flag 状态仍留在 AppRoot；这里只接收显式参数，文案/颜色/按钮顺序/关闭语义与迁出前保持一致。

/** 退出登录二次确认（原 showAvatarLogout）。 */
@Composable
internal fun LogoutConfirmDialog(busy: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // 验收 B 波：确认类对话框统一走 DesignConfirmSheet（与下载弹层同壳）；文案/忙碌语义/回调不变——
    // busy 时确认钮文案仍为「正在安全保存…」、整层不可关、两键不可点（buttonsEnabled 承接原 enabled = !busy）。
    DesignConfirmSheet(
        title = "退出登录",
        body = "确定退出当前账号？",
        confirmLabel = if (busy) "正在安全保存…" else "退出",
        cancelLabel = "取消",
        danger = true,
        buttonsEnabled = !busy,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** 在线歌单 / 专辑长链接导入；未知/短链不会退化为文本歌曲。 */
@Composable
internal fun OnlineImportDialog(onImport: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var plUrl by remember(initial) { mutableStateOf(initial) }
    PlaylistInputDialog(
        title = "导入在线歌单", eyebrow = "Playlist",
        value = plUrl, onValueChange = { plUrl = it },
        label = "歌单链接 / ID", placeholder = "粘贴歌单链接或分享文案",
        supportingText = "支持五平台长链接、短链及分享文案。导入目录不代表取得付费播放权限。",
        confirmLabel = "导入", confirmEnabled = plUrl.isNotBlank(),
        onConfirm = {
            val raw = plUrl.trim()
            if (raw.isNotEmpty()) onImport(raw)
        },
        onDismiss = onDismiss
    )
}

/** 关于 SoundWave（原 showAboutSheet；更新检测状态随对话框存活）。对话框清扫：AlertDialog 白框改 DesignSheet 弹层，文案与检测/下载行为逐字不变。 */
@Composable
internal fun AboutDialog(serverUrl: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    val g = DesignSheetGlyphs
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // 收尾棒 J8 句柄补齐：「关闭」钮走关闭句柄退场（检测更新/前往下载行不关弹层，不动）。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        androidx.compose.foundation.layout.Box(Modifier.semantics { heading() }) {
            DesignSheetHeader("About", "关于 SoundWave")
        }
        Spacer(Modifier.height(14.dp))
        Text("SoundWave · ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("APP 简介", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("SoundWave 是面向个人使用的音乐播放器，支持本地音乐管理、播放与歌词显示；在线音源由用户自行配置。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("免责声明：仅供个人学习研究，请自备合法音源并遵守相关法规。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        if (updateInfo != null) {
            Text(updateInfo.orEmpty(), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        } else if (checking) {
            Text("正在检测更新…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        DesignSheetRow(g.Switch, "检测更新", if (checking) "检测中" else "检查 GitHub 发布") {
            checking = true
            scope.launch {
                updateInfo = withContext(Dispatchers.IO) { xyz.cncnlp.soundwave.GitHubReleases.check(BuildConfig.VERSION_NAME) }
                checking = false
            }
        }
        if (updateInfo != null) {
            DesignSheetRow(g.Download, "前往下载", "打开下载页") {
                val url = xyz.cncnlp.soundwave.GitHubReleases.downloadPage()
                if (url.startsWith("http")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
        }
        SheetActionButtons(
            secondary = "关闭", onSecondary = dismiss,
            primary = null, onPrimary = {}
        )
    }
}
