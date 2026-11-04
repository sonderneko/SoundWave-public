package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InactiveProfileBackupExportAction(profileId: ProfileId) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember(profileId) { mutableStateOf(false) }
    var pendingPayload by remember(profileId) { mutableStateOf<ByteArray?>(null) }
    var status by remember(profileId) { mutableStateOf<String?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { destination: Uri? ->
        val payload = pendingPayload
        pendingPayload = null
        if (destination == null) {
            busy = false
            status = "已取消导出；未创建文件，也未修改本机资料。"
            return@rememberLauncherForActivityResult
        }
        if (payload == null) {
            busy = false
            status = "导出已中止：待写入内容不可用；本机资料未修改。"
            return@rememberLauncherForActivityResult
        }

        status = "正在写入并关闭文件…"
        scope.launch {
            try {
                val completed = withContext(Dispatchers.IO) {
                    InactiveProfileBackupDocumentWriter.writeSelectedDestination(
                        destination = destination,
                        payload = payload
                    ) { selected -> openBackupOutput(context, selected) }
                }
                status = if (completed) {
                    "导出完成（文件已写入并关闭）。本机资料未修改，文件未自动分享或上传。"
                } else {
                    "已取消导出；本机资料未修改。"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "写入失败：无法完成所选文件的写入；本机资料未修改。"
            } finally {
                busy = false
            }
        }
    }

    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            status = "正在重新校验同一份未启用资料…"
            scope.launch {
                var pickerLaunched = false
                try {
                    val payload = withContext(Dispatchers.IO) {
                        LocalStore.exportUnactivatedProfileBackup(context, profileId)
                    }
                    pendingPayload = payload
                    status = "资料已重新校验；请在系统文件选择器中选择保存位置。取消不会写回资料。"
                    createDocument.launch("SoundWave-private-playlists-history.json")
                    pickerLaunched = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    pendingPayload = null
                    status = "导出失败：资料已失效或内容无法校验；未写入文件，也未修改本机资料。"
                } finally {
                    if (!pickerLaunched) busy = false
                }
            }
        }
    ) {
        Text(if (busy) "正在导出…" else "导出私人数据")
    }
    status?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openBackupOutput(context: Context, destination: Uri) =
    context.contentResolver.openOutputStream(destination, "wt")
