package xyz.cncnlp.soundwave

/** Stable ordering policy for user-created playlists across local preferences and account sync. */
internal object PlaylistOrderSync {
    private val fixedPlaylistIds = setOf("liked", "local")

    /**
     * Return the sortable user-playlist ids. A complete latest cloud revision wins; missing/new
     * entries follow in their existing order. Legacy accounts fall back to the old local id list.
     */
    fun displayOrder(playlists: List<Playlist>, legacyOrder: List<String>): List<String> {
        val candidates = playlists.filter(::isSortable)
        if (candidates.isEmpty()) return emptyList()

        val ranked = candidates.withIndex().filter { it.value.syncOrder != null }
        val latestRevision = ranked.maxOfOrNull { it.value.syncOrderAt }
        if (latestRevision != null) {
            val latest = ranked
                .filter { it.value.syncOrderAt == latestRevision }
                .sortedWith(compareBy<IndexedValue<Playlist>> { it.value.syncOrder }.thenBy { it.index })
            val orderedIds = latest.mapTo(linkedSetOf()) { it.value.id }
            return orderedIds.toList() + candidates.asSequence()
                .map { it.id }
                .filterNot { it in orderedIds }
                .toList()
        }

        val available = candidates.mapTo(hashSetOf()) { it.id }
        val orderedIds = linkedSetOf<String>()
        legacyOrder.forEach { if (it in available) orderedIds.add(it) }
        candidates.forEach { orderedIds.add(it.id) }
        return orderedIds.toList()
    }

    /**
     * Stamp one completed reorder across every current sortable playlist. Stale ids are discarded,
     * and entries omitted by an older caller are appended in current bundle order for stability.
     */
    fun withOrder(
        playlists: List<Playlist>,
        requestedOrder: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Playlist> {
        val candidates = playlists.filter(::isSortable)
        if (candidates.isEmpty()) return playlists

        val available = candidates.mapTo(hashSetOf()) { it.id }
        val orderedIds = linkedSetOf<String>()
        requestedOrder.forEach { if (it in available) orderedIds.add(it) }
        candidates.forEach { orderedIds.add(it.id) }

        val rankById = orderedIds.withIndex().associate { (rank, id) -> id to rank }
        val previousRevision = candidates
            .filter { it.syncOrder != null }
            .maxOfOrNull { it.syncOrderAt } ?: 0L
        val nextRevision = if (previousRevision == Long.MAX_VALUE) Long.MAX_VALUE else previousRevision + 1L
        val revision = maxOf(nowMs.coerceAtLeast(1L), nextRevision)

        return playlists.map { playlist ->
            val rank = rankById[playlist.id] ?: return@map playlist
            playlist.copy(syncOrder = rank, syncOrderAt = revision)
        }
    }

    private fun isSortable(playlist: Playlist): Boolean =
        playlist.id !in fixedPlaylistIds &&
            !playlist.id.startsWith("rank_") &&
            !playlist.id.startsWith("hot_") &&
            !playlist.id.startsWith("reco_")
}
