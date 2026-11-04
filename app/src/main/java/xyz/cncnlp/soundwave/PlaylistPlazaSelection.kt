package xyz.cncnlp.soundwave

internal object PlaylistPlazaSelection {
    private fun identity(p: Playlist) = "${p.source}:${p.remoteId.ifBlank { p.id }}"
    fun featured(rows: List<Playlist>): List<Playlist> = rows.distinctBy(::identity)
        .sortedByDescending { it.playCount }.take(3)
    fun remainder(rows: List<Playlist>, featured: List<Playlist>): List<Playlist> {
        val excluded = featured.map(::identity).toSet()
        return rows.distinctBy(::identity).filter { identity(it) !in excluded }
    }
}
