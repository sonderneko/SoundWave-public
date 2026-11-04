package xyz.cncnlp.soundwave

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * soundwave:// Scheme 深度链接与外部导入（系统分享文本、打开 .js / 备份文件）的动作暂存器。
 *
 * MainActivity（onCreate/onNewIntent）把外部 intent 解析成动作后交给 AppRoot 的持续
 * collector；单槽 conflated channel 保留尚未消费的最新动作，消费后的动作不会重放。
 *
 * r657 P0-4 加固：外部输入一律视为不可信——`source` 只接受 [xyz.cncnlp.soundwave.source.MusicSourceRegistry]
 * 的平台 id，`id ≤ 128`、`name/singer/q ≤ 200`，含控制字符/非法字符/超长/缺参都解析为 None；
 * 正在播放时外部歌曲插入为下一首（[shouldQueueNext]），不打断当前播放。
 */
object SchemeActionHolder {
    const val MAX_ID_LENGTH = 128
    const val MAX_TEXT_LENGTH = 200

    sealed interface Action {
        /** soundwave://play?id=<songId>&source=<src>&name=<name>&singer=<singer> */
        data class Play(val songId: String, val source: String, val name: String, val singer: String) : Action

        /** soundwave://search?q=<keyword> */
        data class Search(val keyword: String) : Action

        /** 系统分享的歌单链接：打开“导入在线歌单”并预填，由用户确认后走 ApiClient.importPlaylist。 */
        data class ImportPlaylist(val text: String, val source: String) : Action

        /** ACTION_VIEW 打开的 .js 音源脚本：走本地脚本导入流程。 */
        data class ImportScript(val uri: Uri) : Action

        /** ACTION_VIEW 打开的 SoundWave 备份 JSON：进入“备份与恢复”并先预览、再确认恢复。 */
        data class ImportBackup(val uri: Uri) : Action

        /** 外部输入能识别但当前不支持：给出明确提示，不做任何写入或播放。 */
        data class Notice(val message: String) : Action

        data object None : Action
    }

    private val dispatcher = SchemeActionDispatcher()

    /** 单个 AppRoot collector 接收事件；未消费期间采用 last-submitted-wins。 */
    val actions: Flow<Action> = dispatcher.actions

    /** 解析外部 intent；非支持来源或不可解析返回 None。[context] 仅用于读取被打开文件的显示名。 */
    fun parse(intent: Intent?, context: Context? = null): Action {
        intent ?: return Action.None
        return try {
            if (intent.action == Intent.ACTION_SEND) {
                return ShareLinkParser.parse(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            val uri: Uri = intent.data ?: return Action.None
            when (uri.scheme?.lowercase()) {
                "soundwave" -> parseScheme(uri.host) { key -> uri.getQueryParameter(key) }
                "content" -> ExternalFileImport.actionFor(uri, intent.type, context?.let { ExternalFileImport.displayName(it, uri) })
                else -> Action.None
            }
        } catch (_: Exception) {
            Action.None
        }
    }

    /** Scheme 主体解析（纯函数，JVM 单测直接覆盖）。 */
    internal fun parseScheme(host: String?, param: (String) -> String?): Action = when (host?.lowercase()) {
        "play" -> {
            val id = param("id")?.trim().orEmpty()
            val rawSource = param("source")?.trim()?.lowercase().orEmpty().ifEmpty { "wy" }
            val name = cleanText(param("name"))
            val singer = cleanText(param("singer"))
            if (!isValidSongId(id) || !isKnownPlatform(rawSource) || name == null || singer == null) Action.None
            else Action.Play(songId = id, source = rawSource, name = name, singer = singer)
        }
        "search" -> {
            val q = cleanText(param("q"))
            if (q.isNullOrEmpty()) Action.None else Action.Search(q)
        }
        else -> Action.None
    }

    /** 当前有歌在播放时，外部唤起的歌曲插入为下一首而不是立即切歌。 */
    fun shouldQueueNext(isPlaying: Boolean, hasCurrentSong: Boolean): Boolean = isPlaying && hasCurrentSong

    internal fun isKnownPlatform(source: String): Boolean =
        source in xyz.cncnlp.soundwave.source.MusicSourceRegistry.platformIds

    private val songIdPattern = Regex("[A-Za-z0-9_.\\-]{1,$MAX_ID_LENGTH}")

    internal fun isValidSongId(id: String): Boolean = songIdPattern.matches(id)

    /** null = 非法（超长或含控制字符）；空串 = 缺省。 */
    internal fun cleanText(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.length > MAX_TEXT_LENGTH) return null
        if (value.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        return value
    }

    /** Saved-state marker records submission, not collection: the process-local conflated slot owns pending work. */
    internal const val STATE_INTENT_SUBMITTED = "soundwave.external_intent_submitted"

    fun submitInitial(intent: Intent?, alreadySubmitted: Boolean, context: Context? = null) {
        if (!alreadySubmitted) submit(intent, context)
    }

    /** MainActivity 在 onCreate/onNewIntent 调用：把解析结果交给 AppRoot collector。 */
    fun submit(intent: Intent?, context: Context? = null) {
        dispatcher.submit(parse(intent, context))
    }
}

/** 单消费者、单槽 last-submitted-wins 事件交付；供 Holder 与确定性 JVM 测试共用。 */
internal class SchemeActionDispatcher {
    private val channel = Channel<SchemeActionHolder.Action>(Channel.CONFLATED)
    val actions: Flow<SchemeActionHolder.Action> = channel.receiveAsFlow()

    fun submit(action: SchemeActionHolder.Action) {
        if (action != SchemeActionHolder.Action.None) channel.trySend(action)
    }
}
