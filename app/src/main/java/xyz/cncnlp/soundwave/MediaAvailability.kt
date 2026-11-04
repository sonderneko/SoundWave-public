package xyz.cncnlp.soundwave

import android.content.Context

/**
 * 统一媒体可用性模型：点播、预取、Service 恢复、下载试听共用同一判定。
 * - Local：本地可读文件（file:// 或 content://），必须精确身份命中
 * - Remote：已解析的远程 URL（保留 ResolveResult 全部事实链）
 * - Unavailable：失败原因保留，禁止吞成空字符串
 */
sealed interface MediaAvailability {
    data class Local(val uri: String, val isReadOnly: Boolean = false) : MediaAvailability
    data class Remote(val result: ResolveResult) : MediaAvailability
    data class Unavailable(val failure: ResolveFailure, val detail: String = "") : MediaAvailability
}

/**
 * 本地优先统一入口。四个播放入口（点播、预取、Service 恢复、下载试听）必须先走
 * [LocalFirst.findLocal]：命中即 Local 直接播放，绝不联网取链、绝不按歌名/歌手模糊猜文件。
 *
 * 身份规则（与 LocalStore.findReadableDownload 完全一致）：
 * 同平台官方 ID，或同平台 hash，或严格 song.key()。跨平台/同名/live/翻唱一律不命中。
 */
object LocalFirst {

    /** 本地命中结果：[download] 非空当且仅当命中的正是该条已完成下载记录的文件（供音质回填与记录属实）。 */
    data class LocalHit(val uri: String, val download: DownloadItem?)

    /**
     * ACCEPT5（本地直读）：与 [findLocal] 同一判定，额外带回命中的下载记录。
     * 记录只在胜出 URI 与记录路径一致时附带——resolvedUrl 指向别的文件
     * （如解密缓存）时不冒认下载身份，音质不得张冠李戴。
     */
    fun findLocalHit(song: Song, context: Context): LocalHit? {
        val uri = findLocal(song, context) ?: return null
        val item = try { LocalStore.findReadableDownload(context, song) } catch (_: Exception) { null }
        return LocalHit(uri, item?.takeIf { it.path == uri })
    }

    /** 返回本地可读 URI；无精确命中返回 null，调用方继续走受控解析链。 */
    fun findLocal(song: Song, context: Context): String? {
        // resolvedUrl 只要是可读的本地 URI，就优先直放；保留原 Song.source，
        // 使下载后的业务身份仍是 wy:id/kg:id，队列回切和事务 gate 不会改 key。
        if (song.resolvedUrl.isNotBlank() && !song.resolvedUrl.startsWith("http") && readable(song.resolvedUrl, context)) {
            return song.resolvedUrl
        }
        // r658c D#1：本地歌曲（source=local）的 resolvedUrl 可能被旧恢复/换源路径清空；
        // 按扫描时写入的身份（绝对路径 / file:// / content:// / MediaStore _ID）重建 URI，绝不联网。
        LocalPlayback.candidateUri(song)?.takeIf { it != song.resolvedUrl && readable(it, context) }?.let { return it }
        val item = LocalStore.findReadableDownload(context, song) ?: return null
        return item.path
    }

    private fun readable(path: String, context: Context): Boolean = try {
        when {
            path.isBlank() || path.startsWith("pending:") -> false
            path.startsWith("content://") ->
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(path), "r")?.use { true } ?: false
            else -> java.io.File(path.removePrefix("file://")).isFile
        }
    } catch (_: Exception) { false }
}

/**
 * ACCEPT5（本地直读）：本地命中时对外展示的音质——**实测优先于存储标签**。
 * 实测 = 文件格式线索 + 字节 + 时长走 [xyz.cncnlp.soundwave.download.AudioQualityEvidence]
 * 的实测口径；实测无证据（无扩展名线索/缺字节或时长）才回退下载记录的
 * 存储值；两者皆无返回空串（展示层回退旧「本地」描述，不编造档位）。
 */
object LocalPlayQuality {
    fun forHit(song: Song, hit: LocalFirst.LocalHit): String {
        val item = hit.download
        val bytes = when {
            item != null && item.size > 0L -> item.size
            else -> plainLength(hit.uri)
        }
        val durationMs = listOf(item?.song?.duration ?: 0L, song.duration).firstOrNull { it > 0L } ?: 0L
        val measured = xyz.cncnlp.soundwave.download.AudioQualityEvidence
            .measuredFileQuality(hit.uri, bytes, durationMs)
        return measured ?: item?.quality.orEmpty()
    }

    private fun plainLength(uri: String): Long = try {
        if (uri.startsWith("content://")) -1L
        else java.io.File(uri.removePrefix("file://")).takeIf { it.isFile }?.length() ?: -1L
    } catch (_: Exception) { -1L }
}
/**
 * r658c D#1：本地音乐（扫描/导入的本机文件、content:// URI）直接播放，永远不进入脚本/官方取链。
 * 纯函数，供点播、预取、Service 续播、失败恢复和 Resolver 入口共用同一判定。
 */
object LocalPlayback {
    const val MEDIASTORE_AUDIO_PREFIX = "content://media/external/audio/media/"

    /** 业务身份就是本机文件：source=local。下载过的在线歌曲（wy/kg…）不算，文件丢失后仍可联网取链。 */
    fun isLocalSong(song: Song): Boolean = song.source.trim().equals("local", ignoreCase = true)

    /** 本地歌曲可直接交给播放器的 URI；无法从身份推出时返回 null。 */
    fun candidateUri(song: Song): String? {
        val url = song.resolvedUrl.trim()
        if (isDeviceLocalReference(url)) return url
        if (!isLocalSong(song)) return null
        val id = song.id.trim()
        return when {
            isDeviceLocalReference(id) -> id
            id.isNotEmpty() && id.all { it.isDigit() } -> MEDIASTORE_AUDIO_PREFIX + id
            else -> null
        }
    }
}
