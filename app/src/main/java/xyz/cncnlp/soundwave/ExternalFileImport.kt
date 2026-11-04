package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xyz.cncnlp.soundwave.SchemeActionHolder.Action

/**
 * r657：ACTION_VIEW 打开的 content:// 文件路由到现有导入流程。
 * - `.js`（或 JavaScript MIME）→ 本地音源脚本导入（与“导入本地脚本”同一条有界读取路径）
 * - `.json`（或 application/json）→ “备份与恢复”页的备份预览（先预览，确认后才恢复）
 * - 其它 → 明确提示不支持
 */
object ExternalFileImport {
    const val UNSUPPORTED_MESSAGE = "不支持打开该文件：SoundWave 只能导入 .js 音源脚本或 .json 备份文件"
    private const val MAX_NAME_LENGTH = 255

    private val scriptMimes = setOf("application/javascript", "text/javascript", "application/x-javascript", "application/ecmascript", "text/ecmascript")
    private val backupMimes = setOf("application/json", "text/json")

    internal enum class Kind { SCRIPT, BACKUP, UNSUPPORTED }

    /** 纯函数：显示名后缀优先，其次 MIME。 */
    internal fun kindOf(displayName: String?, mime: String?): Kind {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }?.lowercase()
        val type = mime?.substringBefore(';')?.trim()?.lowercase()
        return when {
            name != null && name.endsWith(".js") -> Kind.SCRIPT
            name != null && name.endsWith(".json") -> Kind.BACKUP
            type in scriptMimes -> Kind.SCRIPT
            type in backupMimes -> Kind.BACKUP
            else -> Kind.UNSUPPORTED
        }
    }

    internal fun actionFor(uri: Uri, mime: String?, displayName: String?): Action =
        when (kindOf(displayName ?: uri.lastPathSegment, mime)) {
            Kind.SCRIPT -> Action.ImportScript(uri)
            Kind.BACKUP -> Action.ImportBackup(uri)
            Kind.UNSUPPORTED -> Action.Notice(UNSUPPORTED_MESSAGE)
        }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    /** 外部打开的备份文件暂存：AppRoot 跳到备份页后，由 BackupRestorePage 观察并取走，走原有预览。 */
    private val pending = MutableStateFlow<Uri?>(null)
    val pendingBackup: StateFlow<Uri?> = pending

    fun offerBackup(uri: Uri) { pending.value = uri }

    fun takePendingBackup(): Uri? = pending.value?.also { pending.compareAndSet(it, null) }
}
