package xyz.cncnlp.soundwave

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.migration.ProfileCatalog
import xyz.cncnlp.soundwave.ui.common.DesignConfirmSheet

/** Separate recovery list survives registry/core removal. No general orphan catalog access. */
@Composable
internal fun PendingInactiveRollbacks() {
    val context = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf<List<ProfileId>>(emptyList()) }
    LaunchedEffect(context) { pending = withContext(Dispatchers.IO) { InactiveRollbackJournal.pending(context) } }
    pending.forEach { id ->
        Text("待继续撤销的副本：${id.value}")
        InactiveCopyRollbackAction(id) { pending = pending.filterNot { it == id } }
    }
}

@Composable
internal fun InactiveCopyRollbackAction(profileId: ProfileId, onRolledBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var confirm by remember(profileId) { mutableStateOf(false) }
    var busy by remember(profileId) { mutableStateOf(false) }
    var message by remember(profileId) { mutableStateOf("") }
    TextButton(onClick = { confirm = true }, enabled = !busy) { Text("撤销此未启用副本") }
    if (message.isNotBlank()) Text(message)
    if (confirm) DesignConfirmSheet(
        title = "仅撤销新建副本？",
        body = "只删除这一份未启用资料并撤销其本机 WebDAV 绑定。旧版本机数据、原备份、下载文件与远端文件全部保留；不登录、不访问网络。",
        confirmLabel = "确认撤销此副本",
        cancelLabel = "取消",
        danger = true,
        // 验收 B 波：确认框统一走 DesignConfirmSheet 同壳呈现；busy 语义（执行中不可关、两键不可点）等价承接，
        // 下方确认逻辑体与原 AlertDialog 版本逐字一致（journal 读取 → rollbackInactiveLocalCopy 调用顺序不动）。
        buttonsEnabled = !busy,
        onConfirm = {
            busy = true
            scope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        val token = InactiveRollbackJournal.read(context, profileId)?.token()
                            ?: ProfileCatalog.inactive(context, profileId) ?: return@withContext false
                        LocalStore.rollbackInactiveLocalCopy(context, token)
                    }
                    if (success) { confirm = false; onRolledBack() }
                    else message = "撤销未确认；不会清理其他资料或原文件。"
                } finally { busy = false }
            }
        },
        onDismiss = { confirm = false }
    )
}
