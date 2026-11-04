package xyz.cncnlp.soundwave

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.migration.ProfileCatalog
import xyz.cncnlp.soundwave.migration.ProfileOwner
import xyz.cncnlp.soundwave.sync.*
import xyz.cncnlp.soundwave.ui.settings.WebDavLoginSheet

/** Independent opt-in is not activation. Only this inactive profile's core/history can transfer. */
@Composable
internal fun InactiveProfileRemoteAction(profileId: ProfileId) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var showBinding by remember(profileId) { mutableStateOf(false) }
    var busy by remember(profileId) { mutableStateOf(false) }
    var status by remember(profileId) { mutableStateOf("默认仅本机；同步需单独确认。范围仅收藏、歌单和历史，冲突条件合并重试一次。") }
    TextButton(onClick = { showBinding = true }, enabled = !busy) { Text("为此未启用资料单独选择 WebDAV") }
    TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try { status = withContext(Dispatchers.IO) {
                val token = ProfileCatalog.inactive(context, profileId) ?: return@withContext "资料身份无效"
                if (ProfileRemoteBindings.revoke(context, token)) "已撤销本机远端绑定；原本机和远端数据均未删除。" else "撤销未确认"
            } } finally { busy = false }
        }
    }) { Text("撤销此资料的远端绑定（不删数据）") }
    if (showBinding) WebDavLoginSheet(
        initialUrl = "", initialUsername = "", initialPassword = "", busy = busy,
        status = status, statusIsError = false,
        onTestConnection = { url, username, password ->
            busy = true
            scope.launch {
                try {
                    status = WebDavOperations.run {
                        val token = ProfileCatalog.inactive(context, profileId)
                        if (token == null || !xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.remoteReady(token))
                            "资料运行边界尚未验收；未请求"
                        else WebDavSyncClient().testConnection(url, username, password)
                    }
                        ?: "连接成功；未保存绑定，未上传。点击同步才明确授权该资料。"
                } finally { busy = false }
            }
        },
        onSync = { url, username, password ->
            busy = true
            scope.launch {
                try {
                    status = WebDavOperations.run {
                        val token = ProfileCatalog.inactive(context, profileId)
                            ?: return@run "资料来源无效；未读凭据、未请求"
                        if (!ProfileRemoteBindings.optIn(context, token, WebDavSavedCredentials(url, username, password), confirmed = true))
                            return@run "资料绑定未确认；未请求"
                        val client = WebDavSyncClient(localPlaybackHistory = { LocalStore.loadHistory(context, profileId) })
                        when (val result = client.syncBoundProfile(context, token)) {
                            is WebDavSyncResult.Success -> "该未启用资料同步完成；当前资料和播放未改变。"
                            is WebDavSyncResult.Failure -> result.message
                        }
                    }
                } finally { busy = false }
            }
        },
        onDismiss = { if (!busy) showBinding = false }
    )
}
