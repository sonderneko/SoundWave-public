package xyz.cncnlp.soundwave

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss
import xyz.cncnlp.soundwave.ui.common.SheetActionButtons
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.migration.ProfileContext
import xyz.cncnlp.soundwave.migration.ProfileCatalog

private sealed class UnactivatedPreviewState {
    data object Loading : UnactivatedPreviewState()
    data class Ready(val preview: LocalStore.UnactivatedProfilePreview) : UnactivatedPreviewState()
    data class Failed(val reason: String) : UnactivatedPreviewState()
}

@Composable
internal fun UnactivatedProfilePreviewDialog(
    profileId: ProfileId,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    var state by remember(profileId) { mutableStateOf<UnactivatedPreviewState>(UnactivatedPreviewState.Loading) }

    LaunchedEffect(profileId) {
        state = try {
            val preview = withContext(Dispatchers.IO) {
                val validated = ProfileCatalog.inactive(context, profileId) ?: error("资料来源或索引无效")
                LocalStore.readUnactivatedProfilePreview(context, validated)
            }
            UnactivatedPreviewState.Ready(preview)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            UnactivatedPreviewState.Failed(failure.message?.takeIf { it.isNotBlank() } ?: "资料文件缺失或无法校验")
        }
    }

    // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层（滚动改由弹层承接）；
    // 预览文案与内嵌导出/WebDAV/回滚动作逐字不变（它们是正文内容，不是对话框边框）。
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = true) {
        // 收尾棒 J8 句柄补齐：「关闭」钮走关闭句柄退场；正文内嵌的导出/WebDAV/回滚动作不关弹层，不动。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        Box(Modifier.semantics { heading() }) { DesignSheetHeader("Profile", "本机未启用资料预览") }
        Spacer(Modifier.height(10.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                Text(
                    "资料尚未启用；预览、返回或取消均不写入，不改变当前收藏或播放。下方导出与 WebDAV 是独立明确确认的动作；未选择 WebDAV 时默认仅本机。",
                    color = MaterialTheme.colorScheme.onSurface
                )
                when (val current = state) {
                    UnactivatedPreviewState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner(18.dp)
                        Text("正在本机校验资料…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UnactivatedPreviewState.Failed -> Text(
                        "无法读取本机资料：${current.reason}。未回退读取旧本机资料。",
                        color = MaterialTheme.colorScheme.error
                    )
                    is UnactivatedPreviewState.Ready -> {
                        val preview = current.preview
                        Text("收藏歌曲：${preview.favoriteCount} 首")
                        Text("歌单：${preview.playlistCount} 个（不含“喜欢 / 本地歌单”默认入口）")
                        Text("歌单中歌曲项：${preview.playlistSongCount} 首")
                        Text("播放历史：${preview.historyCount} 条")
                        Spacer(Modifier.height(2.dp))
                        Text("导出范围：私人收藏、歌单及其歌曲、必要的删除/排序标记与播放历史。")
                        Text(
                            "不会导出原始 ProfileId、账号/用户名/密码/token/服务器地址、vault 原文、脚本 ID/代码/启用状态/来源绑定、头像、下载记录/已下载文件/本地路径/SAF URI、播放断点、ListenStats、SmartSource/记住的网址、WebDAV 或应用全局偏好。封面仅保留无 user-info、query、fragment 的公开 HTTP(S) URI。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "这是私人歌单与历史数据。点击下方“导出私人数据”即确认本次导出；随后由系统文件选择器选择保存位置。不会自动分享或上传；该按钮只导出这份未启用资料，不会恢复到当前数据、激活或切换资料。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        InactiveProfileBackupExportAction(profileId)
                        InactiveProfileRemoteAction(profileId)
                        InactiveCopyRollbackAction(profileId, onDismiss)

                        if (preview.playlistNames.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text("歌单名称", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            preview.playlistNames.forEach { name -> Text("• $name") }
                        }
                        if (preview.songNames.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text("歌曲名称", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            preview.songNames.forEach { name -> Text("• $name") }
                        }
                        if (preview.favoriteCount == 0 && preview.playlistCount == 0 && preview.historyCount == 0) {
                            Text("该资料有效，但当前没有可预览的收藏、歌单或播放历史。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
        }
        SheetActionButtons(secondary = "关闭", onSecondary = dismiss, primary = null)
    }
}
