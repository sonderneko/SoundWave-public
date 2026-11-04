package xyz.cncnlp.soundwave.ui.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.LyricCorrectionCandidate
import xyz.cncnlp.soundwave.LyricCorrectionSearch
import xyz.cncnlp.soundwave.LyricCorrectionSearchResult
import xyz.cncnlp.soundwave.LyricCorrectionStorage
import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.migration.ProfileContext

/**
 * Holds the lyric-correction dialog state and operations for one song.
 * FullPlayer creates one instance per song key; all storage/search behavior
 * matches the previous in-composable implementation exactly.
 */
internal class FullPlayerLyricCorrectionState {
    var showDialog by mutableStateOf(false)
    var selectedCandidateIndex by mutableIntStateOf(-1)
    var candidates by mutableStateOf(emptyList<LyricCorrectionCandidate>())
    var searching by mutableStateOf(false)
    var message by mutableStateOf("")
    var hasCorrection by mutableStateOf(false)

    suspend fun loadInitial(ctx: Context, song: Song, owner: ProfileContext) {
        hasCorrection = withContext(Dispatchers.IO) {
            LyricCorrectionStorage.load(ctx, song, owner).isNotBlank()
        }
    }

    fun search(
        ctx: Context,
        song: Song,
        scope: CoroutineScope,
        playbackDurationMs: Long,
        currentSongKey: () -> String
    ) {
        if (searching) return
        candidates = emptyList()
        message = ""
        if (!LyricCorrectionSearch.hasValidatedInternet(ctx)) {
            message = "当前网络不可用；原歌词和播放状态未改变。连接网络后可重试。"
            return
        }
        searching = true
        val targetSong = song
        val targetKey = targetSong.key()
        scope.launch {
            val result = try {
                LyricCorrectionSearch.search(targetSong, playbackDurationMs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                searching = false
                throw cancelled
            } catch (error: Exception) {
                LyricCorrectionSearchResult.Failed(
                    "歌词候选搜索失败：${error.message?.takeIf(String::isNotBlank) ?: "发生未知错误"}；当前歌词保持不变。"
                )
            }
            if (currentSongKey() != targetKey) return@launch
            searching = false
            when (result) {
                is LyricCorrectionSearchResult.Found -> {
                    candidates = result.candidates
                    message = "请选择一个候选并点击“应用所选歌词”；未确认前不会替换当前歌词。"
                }
                is LyricCorrectionSearchResult.NoTrustedCandidates -> message = result.message
                is LyricCorrectionSearchResult.Failed -> message = result.message
            }
        }
    }

    fun apply(
        ctx: Context,
        song: Song,
        candidate: LyricCorrectionCandidate,
        owner: ProfileContext,
        onLyricCorrectionRefresh: (Song) -> Unit,
        toast: (String) -> Unit
    ): Boolean {
        if (!LyricCorrectionStorage.confirm(ctx, song, candidate, owner)) {
            message = "修正未保存：候选来源或歌词校验未通过；当前歌词保持不变。"
            return false
        }
        hasCorrection = true
        message = "歌词修正已保存。"
        onLyricCorrectionRefresh(song)
        toast("已应用歌词修正")
        return true
    }

    fun clear(
        ctx: Context,
        song: Song,
        owner: ProfileContext,
        onLyricCorrectionRefresh: (Song) -> Unit,
        toast: (String) -> Unit
    ): Boolean {
        if (!LyricCorrectionStorage.clear(ctx, song, owner)) {
            message = "清除修正失败；当前歌词保持不变。"
            return false
        }
        hasCorrection = false
        message = "已清除修正，将恢复默认歌词来源。"
        onLyricCorrectionRefresh(song)
        toast("已恢复默认歌词")
        return true
    }
}
