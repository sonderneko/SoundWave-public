package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.decrypt.LyricQrcKrc
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect

internal suspend fun <T> lyricSourceAttemptOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** 统一歌词来源：外挂同名 .lrc / ID3v2 USLT 内嵌 / 网络与缓存。 */
object LyricSourceLoader {
    /**
     * r656-G（P1-6）：改为 suspend，网络回退直接挂起而不是阻塞式等待调用线程；
     * 整体切到 IO，即使从主线程协程调用也不会阻塞 UI。调用方协程取消时网络回退随之取消。
     */
    suspend fun load(context: Context, song: Song, priority: String, owner: xyz.cncnlp.soundwave.migration.ProfileContext = xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()): String = withContext(Dispatchers.IO) {
        if (!xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.isWork(owner)) return@withContext ""
        suspend fun external(): String = loadSidecar(song).ifBlank { loadNetworkCached(context, song, owner) }
        fun embedded(): String = loadEmbedded(song)
        preferUserLyricCorrection(LyricCorrectionStorage.load(context, song, owner)) {
            if (priority == "embedded") embedded().ifBlank { external() }
            else external().ifBlank { embedded() }
        }
    }

    private fun localFile(song: Song): File? {
        val raw = song.resolvedUrl.trim()
        if (raw.isEmpty()) return null
        return try {
            val path = if (raw.startsWith("file:")) Uri.parse(raw).path.orEmpty() else raw
            File(path).takeIf { it.isFile }
        } catch (_: Exception) { null }
    }

    private fun loadSidecar(song: Song): String {
        val audio = localFile(song) ?: return ""
        val base = audio.nameWithoutExtension
        val parent = audio.parentFile
        // 同名变体：音频 base 名 > "歌名 - 歌手" > 歌名
        val names = listOf(base, "${song.name} - ${song.singer}", song.name)
        // 优先级：明文 .lrc > QQ .qrc > 酷狗 .krc；明文优先，同 base 下只取第一个可读/可解密的
        val candidates = buildList {
            // .lrc/.LRC 明文（沿用原有 4 个候选与顺序，直接 readText，行为不变）
            add(File(parent, "$base.lrc"))
            add(File(parent, "$base.LRC"))
            add(File(parent, "${names[1]}.lrc"))
            add(File(parent, "${names[2]}.lrc"))
            // .qrc/.QRC：QQ音乐加密歌词，readBytes 后交 LyricQrcKrc.decryptQrc 解密为明文 LRC
            names.forEach { add(File(parent, "$it.qrc")); add(File(parent, "$it.QRC")) }
            // .krc/.KRC：酷狗加密歌词，readBytes 后交 LyricQrcKrc.decryptKrc 解密为明文 LRC
            names.forEach { add(File(parent, "$it.krc")); add(File(parent, "$it.KRC")) }
        }
        // 逐个尝试：明文直读、加密先解密；解密失败(返回 null)或内容为空都视为该候选不可用，
        // 继续下一个候选，全部失败返回 ""（与原仅支持 .lrc 时的兜底语义一致）
        for (candidate in candidates) {
            if (!candidate.isFile) continue
            val text = runCatching {
                when {
                    candidate.name.endsWith(".lrc", ignoreCase = true) -> candidate.readText()
                    candidate.name.endsWith(".qrc", ignoreCase = true) ->
                        LyricQrcKrc.decryptQrc(candidate.readBytes()).orEmpty()
                    candidate.name.endsWith(".krc", ignoreCase = true) ->
                        LyricQrcKrc.decryptKrc(candidate.readBytes()).orEmpty()
                    else -> ""
                }
            }.getOrDefault("")
            if (text.isNotBlank()) return text
        }
        return ""
    }

    internal fun persistentLyricKey(song: Song, owner: xyz.cncnlp.soundwave.migration.ProfileContext): String =
        "${xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.encodeOwner(owner)}:" + lyricCacheKey(song, "v2yrc")

    private suspend fun loadNetworkCached(context: Context, song: Song, owner: xyz.cncnlp.soundwave.migration.ProfileContext): String {
        val lyricKey = persistentLyricKey(song, owner)
        var raw = CacheManager.loadLyric(context, lyricKey)
        // Only explicit legacy ownership can read pre-namespace cache; never a profile fallback.
        if (raw.isBlank() && owner.owner == xyz.cncnlp.soundwave.migration.ProfileOwner.LegacyUnassigned) {
            raw = CacheManager.loadLyric(context, lyricCacheKey(song, "v2yrc"))
        }
        if (raw.isBlank()) {
            val platformId = MusicSourceRegistry.lyricPlatformId(song.source)
            if (platformId != null) {
                // 一次 adapter 调用封装“匿名直连一次 → 最多四轮服务端回退”，避免每轮重发平台直连。
                raw = lyricSourceAttemptOrNull {
                    MusicSourceRegistry.platformSource(platformId).lyric(song)
                }.orEmpty()
            } else {
                // 非五大平台（包括 Meting 类来源）保持原有直连探测与 ApiClient 服务端链路。
                val direct = lyricSourceAttemptOrNull {
                    fetchLyricDirect(song.source, song)
                }
                if (!direct.isNullOrBlank()) {
                    raw = direct
                } else {
                    for (attempt in 0..3) {
                        // SW-02-05（SW-03 移交）：重试循环不得吞 CancellationException——
                        // 走 lyricSourceAttemptOrNull（重抛取消、仅吞普通异常），否则歌词
                        // 任务取消后仍继续重试并回写过期结果。
                        raw = lyricSourceAttemptOrNull { ApiClient.lyric(song) }.orEmpty()
                        if (raw.isBlank() && song.id.isNotBlank()) {
                            // 无 singer 时按原契约再试一次精简参数。
                            raw = lyricSourceAttemptOrNull {
                                ApiClient.lyric(song.copy(singer = song.singer.ifBlank { " " }.trim()))
                            }.orEmpty()
                        }
                        if (raw.isNotBlank()) break
                        if (attempt < 3) delay(220L * (attempt + 1))
                    }
                }
            }
            if (raw.isNotBlank()) xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.commitWork(owner) { CacheManager.saveLyric(context, lyricKey, raw) }
        }
        return raw
    }

    /** 读取 ID3v2 USLT（非同步歌词）帧；支持 ISO-8859-1、UTF-16、UTF-16BE、UTF-8。 */
    private fun loadEmbedded(song: Song): String {
        val file = localFile(song) ?: return ""
        return runCatching { readUslt(file) }.getOrDefault("")
    }

    private fun readUslt(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10) return ""
            val header = ByteArray(10); raf.readFully(header)
            if (header[0].toInt() != 'I'.code || header[1].toInt() != 'D'.code || header[2].toInt() != '3'.code) return ""
            val version = header[3].toInt() and 0xff
            val tagSize = syncSafe(header, 6)
            var pos = 10L
            val end = (10L + tagSize).coerceAtMost(raf.length())
            while (pos + 10 <= end) {
                raf.seek(pos)
                val fh = ByteArray(10); raf.readFully(fh)
                val id = fh.copyOfRange(0, 4).toString(Charsets.ISO_8859_1)
                if (id.all { it == '\u0000' }) break
                val size = if (version >= 4) syncSafe(fh, 4) else ((fh[4].toInt() and 0xff) shl 24) or ((fh[5].toInt() and 0xff) shl 16) or ((fh[6].toInt() and 0xff) shl 8) or (fh[7].toInt() and 0xff)
                if (size <= 0 || pos + 10L + size > end) break
                if (id == "USLT") {
                    val data = ByteArray(size); raf.readFully(data)
                    return decodeUslt(data)
                }
                pos += 10L + size
            }
        }
        return ""
    }

    private fun syncSafe(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0x7f) shl 21) or ((bytes[off + 1].toInt() and 0x7f) shl 14) or
            ((bytes[off + 2].toInt() and 0x7f) shl 7) or (bytes[off + 3].toInt() and 0x7f)

    private fun decodeUslt(data: ByteArray): String {
        if (data.size < 5) return ""
        val enc = data[0].toInt() and 0xff
        val charset = when (enc) {
            1 -> Charset.forName("UTF-16")
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        var p = 4 // encoding + 3 byte language
        val terminator = if (enc == 1 || enc == 2) 2 else 1
        while (p + terminator <= data.size) {
            val zero = if (terminator == 2) data[p].toInt() == 0 && data[p + 1].toInt() == 0 else data[p].toInt() == 0
            p += terminator
            if (zero) break
        }
        if (p >= data.size) return ""
        return data.copyOfRange(p, data.size).toString(charset).trim('\u0000', ' ', '\n', '\r')
    }
}
