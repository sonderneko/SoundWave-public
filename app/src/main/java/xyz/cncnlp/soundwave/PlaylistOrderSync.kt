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

/**
 * Favorites-only ordering （收藏视图内部排序）: mirrors [PlaylistOrderSync]'s stamp mechanism
 * (per-playlist rank + shared revision, stored inside the pls element, so it rides the same
 * WebDAV/backup projection), but on the separate `favSyncOrder` field pair — the imported/created
 * pools and the favorites never read or write each other's order stamps.
 *
 * Candidate membership is decided by the caller (favorited playlists group / favorited albums
 * group are stamped independently); this object only resolves and stamps the given members.
 */
internal object FavoriteOrderSync {

    /**
     * Display order for one favorites group. [candidates] must arrive in the group's current
     * fallback arrangement (the order entries appear in today when no stamps exist); a complete
     * latest stamped revision wins, missing/new entries follow in their existing order, and
     * stamp-less bundles fall back to the legacy local id list.
     */
    fun displayOrder(candidates: List<Playlist>, legacyOrder: List<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val ranked = candidates.withIndex().filter { it.value.favSyncOrder != null }
        val latestRevision = ranked.maxOfOrNull { it.value.favSyncOrderAt }
        if (latestRevision != null) {
            val latest = ranked
                .filter { it.value.favSyncOrderAt == latestRevision }
                .sortedWith(compareBy<IndexedValue<Playlist>> { it.value.favSyncOrder }.thenBy { it.index })
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
     * Stamp one completed favorites reorder across the group's current members only. Entries
     * outside [memberIds] are returned untouched — neither their pool stamps nor their favorites
     * stamps change — and members omitted by an older caller keep their relative order at the tail.
     */
    fun withOrder(
        playlists: List<Playlist>,
        memberIds: Set<String>,
        requestedOrder: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<Playlist> {
        val members = playlists.filter { it.id in memberIds }
        if (members.isEmpty()) return playlists

        val available = members.mapTo(hashSetOf()) { it.id }
        val orderedIds = linkedSetOf<String>()
        requestedOrder.forEach { if (it in available) orderedIds.add(it) }
        members.forEach { orderedIds.add(it.id) }

        val rankById = orderedIds.withIndex().associate { (rank, id) -> id to rank }
        val previousRevision = members
            .filter { it.favSyncOrder != null }
            .maxOfOrNull { it.favSyncOrderAt } ?: 0L
        val nextRevision = if (previousRevision == Long.MAX_VALUE) Long.MAX_VALUE else previousRevision + 1L
        val revision = maxOf(nowMs.coerceAtLeast(1L), nextRevision)

        return playlists.map { playlist ->
            val rank = rankById[playlist.id] ?: return@map playlist
            playlist.copy(favSyncOrder = rank, favSyncOrderAt = revision)
        }
    }
}
