package xyz.cncnlp.soundwave

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import xyz.cncnlp.soundwave.source.direct.fetchLyricDirect
import xyz.cncnlp.soundwave.source.direct.fetchMiguLyricByCopyrightId

internal object LyricCorrectionSearch {
    private const val SEARCH_LIMIT = 30
    private const val LYRIC_FETCH_LIMIT = 12

    fun hasValidatedInternet(context: Context): Boolean = runCatching {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    suspend fun search(song: Song, playbackDurationMs: Long): LyricCorrectionSearchResult {
        val platform = lyricCorrectionPlatformId(song)
            ?: return LyricCorrectionSearchResult.Failed(
                "当前来源（${song.source.ifBlank { "未知" }}）暂不支持精准歌词搜索；当前歌词保持不变。"
            )
        val target = song.copy(duration = playbackDurationMs.takeIf { it > 0L } ?: song.duration)
        if (target.name.isBlank() || target.singer.isBlank() || target.duration <= 0L) {
            return LyricCorrectionSearchResult.Failed("歌曲标题、歌手或播放时长信息不足，无法安全匹配版本；当前歌词保持不变。")
        }

        val nativeId = lyricCorrectionNativeId(target)
        if (nativeId != null) {
            val exactLyrics = fetchLyricSafely(platform, target, nativeId).orEmpty()
            if (isUsableLyricCorrectionText(exactLyrics)) {
                return LyricCorrectionSearchResult.Found(
                    listOf(LyricCorrectionCandidate(target, exactLyrics, similarity = 1f, exactNativeId = true))
                )
            }
        }

        val query = listOf(target.name.trim(), target.singer.trim()).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) {
            return LyricCorrectionSearchResult.Failed("歌曲标题或歌手为空，无法搜索歌词候选；当前歌词保持不变。")
        }
        val foundSongs = try {
            withContext(Dispatchers.IO) {
                MusicSourceRegistry.platformSource(platform).search(query, page = 1, limit = SEARCH_LIMIT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return LyricCorrectionSearchResult.Failed(
                "歌词候选搜索失败：${error.message?.takeIf(String::isNotBlank) ?: "平台请求异常"}；当前歌词保持不变。"
            )
        }

        // Search only the original platform. Never reinterpret one provider's ID as another provider's ID.
        val samePlatformSongs = foundSongs.filter { lyricCorrectionPlatformId(it) == platform }
        val ranked = rankTrustedLyricCorrectionSongs(target, samePlatformSongs).take(LYRIC_FETCH_LIMIT)
        if (ranked.isEmpty()) {
            return LyricCorrectionSearchResult.NoTrustedCandidates(
                "没有找到标题、歌手、时长和来源均可信的歌词候选；也可能是网络或平台暂不可用。当前歌词保持不变。"
            )
        }

        val candidates = coroutineScope {
            ranked.map { match ->
                async {
                    val candidateId = lyricCorrectionNativeId(match.song) ?: return@async null
                    val raw = fetchLyricSafely(platform, match.song, candidateId).orEmpty()
                    if (!isUsableLyricCorrectionText(raw)) null
                    else LyricCorrectionCandidate(match.song, raw, match.similarity)
                }
            }.awaitAll().filterNotNull()
        }.distinctBy { lyricCorrectionIdentity(it.song) }
            .sortedByDescending { it.similarity }

        if (candidates.isEmpty()) {
            return LyricCorrectionSearchResult.NoTrustedCandidates(
                "找到的歌曲信息不足以确认版本，或平台未返回有效歌词；也请检查网络后重试。当前歌词保持不变。"
            )
        }
        return LyricCorrectionSearchResult.Found(candidates)
    }

    private suspend fun fetchLyricSafely(platform: String, song: Song, nativeId: String): String? = try {
        fetchLyricForNativeId(platform, song, nativeId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchLyricForNativeId(platform: String, song: Song, nativeId: String): String? =
        if (platform == "mg") {
            // Migu getLyric is keyed by copyrightId, not the search result's contentId/Song.id.
            fetchMiguLyricByCopyrightId(nativeId)
        } else {
            // QQ uses songmid (Song.id); mediaMid is retained only as separate playable-file metadata.
            val exactSong = if (platform == "kg") song.copy(id = nativeId, hash = nativeId)
            else song.copy(id = nativeId)
            fetchLyricDirect(platform, exactSong)
        }
}
