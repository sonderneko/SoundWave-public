package xyz.cncnlp.soundwave

/** Resume only a verified member of this detail, never a title-based substitute. */
internal object DetailResumePolicy {
    private fun identity(song: Song): String? =
        if (song.source.isBlank() || song.id.isBlank()) null else song.key()

    fun candidate(
        songs: List<Song>,
        history: List<Song>,
        nowMs: Long = System.currentTimeMillis()
    ): Song? {
        val members = songs.mapNotNull { song -> identity(song)?.let { it to song } }.toMap()
        val latest = history.asSequence()
            .filter { PlaybackHistoryPolicy.isRetained(it, nowMs) }
            .filter { identity(it)?.let(members::containsKey) == true }
            .sortedWith(compareByDescending<Song> { it.playedAt }.thenBy { it.key() })
            .firstOrNull() ?: return null
        // Use fresh catalog metadata and media identity, not historical URLs.
        return members[identity(latest)]
    }

    fun label(song: Song): String =
        listOf(song.name.trim(), song.singer.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
}
