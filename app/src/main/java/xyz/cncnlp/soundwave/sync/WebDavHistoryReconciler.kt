package xyz.cncnlp.soundwave.sync

import xyz.cncnlp.soundwave.Song
import xyz.cncnlp.soundwave.PlaybackHistoryPolicy

/** One duplicate/retention policy shared by WebDAV and account-cloud history merges. */
internal object WebDavHistoryReconciler {
    const val HISTORY_LIMIT = PlaybackHistoryPolicy.CLOUD_SYNC_LIMIT

    private data class Candidate(
        val song: Song,
        val sourcePriority: Int,
        val sourceIndex: Int
    )

    /**
     * Selects one record per stable song key. Newer plays win; exact timestamp ties prefer the
     * independent on-device history, then the local bundle, then the WebDAV document.
     * Different songs with the same timestamp are ordered by stable song key.
     */
    fun reconcile(
        independentHistory: List<Song>,
        localBundleHistory: List<Song>,
        webDavHistory: List<Song>,
        historyLimit: Int = HISTORY_LIMIT,
        nowMs: Long = System.currentTimeMillis(),
        deletedHistoryEventKeys: Set<String> = emptySet()
    ): List<Song> {
        val winners = HashMap<String, Candidate>()

        fun collect(songs: List<Song>, priority: Int) {
            songs.forEachIndexed { index, song ->
                if (!PlaybackHistoryPolicy.isRetainedForCloudSync(song, nowMs)) return@forEachIndexed
                if (PlaybackHistoryPolicy.historyEventKey(song) in deletedHistoryEventKeys) return@forEachIndexed
                val candidate = Candidate(song, priority, index)
                val key = song.key()
                val previous = winners[key]
                if (previous == null || isPreferred(candidate, previous)) {
                    winners[key] = candidate
                }
            }
        }

        collect(independentHistory, priority = 2)
        collect(localBundleHistory, priority = 1)
        collect(webDavHistory, priority = 0)

        return winners.values
            .sortedWith(compareByDescending<Candidate> { it.song.playedAt }.thenBy { it.song.key() })
            .take(historyLimit.coerceAtLeast(0))
            .map { it.song }
    }

    private fun isPreferred(candidate: Candidate, previous: Candidate): Boolean = when {
        candidate.song.playedAt != previous.song.playedAt ->
            candidate.song.playedAt > previous.song.playedAt
        candidate.sourcePriority != previous.sourcePriority ->
            candidate.sourcePriority > previous.sourcePriority
        else -> candidate.sourceIndex < previous.sourceIndex
    }
}
