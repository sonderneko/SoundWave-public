package xyz.cncnlp.soundwave.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import xyz.cncnlp.soundwave.Song

/** 服务端歌词回退的逻辑轮数上限；单轮在有歌曲 ID 时最多尝试两种既有参数形式。 */
internal const val LYRIC_SERVER_FALLBACK_ROUNDS = 4

/**
 * 单次平台 adapter 调用的歌词契约：匿名直连只试一次；未命中时按原有次数、参数变体和可取消退避回退服务端。
 * 该函数不负责本地缓存、sidecar 或 ID3，它们仍由 LyricSourceLoader 按原优先级处理。
 */
internal suspend fun fetchPlatformLyric(
    song: Song,
    direct: suspend (Song) -> String?,
    server: (Song) -> String,
    pause: suspend (Long) -> Unit = { delay(it) },
): String? {
    currentCoroutineContext().ensureActive()
    val directText = try {
        direct(song)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
    currentCoroutineContext().ensureActive()
    if (!directText.isNullOrBlank()) return directText

    fun serverText(request: Song): String = try {
        server(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ""
    }

    var raw = ""
    for (attempt in 0 until LYRIC_SERVER_FALLBACK_ROUNDS) {
        currentCoroutineContext().ensureActive()
        raw = serverText(song)
        currentCoroutineContext().ensureActive()
        if (raw.isBlank() && song.id.isNotBlank()) {
            // 保持历史参数形式：仅复制 singer 为空白时会改变字段，source/id 始终原样保留。
            raw = serverText(song.copy(singer = song.singer.ifBlank { " " }.trim()))
            currentCoroutineContext().ensureActive()
        }
        if (raw.isNotBlank()) break
        if (attempt < LYRIC_SERVER_FALLBACK_ROUNDS - 1) {
            try {
                pause(220L * (attempt + 1))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InterruptedException) {
                // Preserve the legacy interrupted-backoff behavior; coroutine cancellation is rethrown above.
            }
        }
    }
    return raw.takeIf { it.isNotEmpty() }
}
