package xyz.cncnlp.soundwave.ui.home

import xyz.cncnlp.soundwave.RankBoard
import xyz.cncnlp.soundwave.Song

/** Loads up to ten songs from the selected new-song board, preferring its detail when needed. */
internal suspend fun loadHomeNewSongExpress(
    board: RankBoard?,
    loadDetail: suspend (boardId: String) -> List<Song>,
): List<Song> {
    if (board == null) return emptyList()

    val preview = board.songs
    if (preview.size >= 10 || board.id.isBlank()) return preview.take(10)

    // Keep the legacy runCatching boundary: it intentionally also catches CancellationException.
    val detail = runCatching { loadDetail(board.id) }.getOrDefault(emptyList())
    return if (detail.isNotEmpty()) detail.take(10) else preview.take(10)
}
