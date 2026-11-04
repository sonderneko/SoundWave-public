package xyz.cncnlp.soundwave.source.direct

import xyz.cncnlp.soundwave.Playlist

internal data class OfficialPlaylistReference(val source: String, val id: String)

/** Compatibility wrapper: playlist-only callers must never reinterpret an album ID. */
internal fun parseOfficialPlaylistReference(raw: String): OfficialPlaylistReference? {
    val reference = parseOnlineImportReference(raw) ?: return null
    if (reference.kind != OnlineImportKind.PLAYLIST) return null
    return OfficialPlaylistReference(reference.source, reference.id)
}

internal suspend fun importOfficialPlaylistDirect(raw: String): Playlist? {
    val reference = parseOfficialPlaylistReference(raw) ?: return null
    val detail = fetchPlaylistDetailPayloadDirect(reference.source, reference.id) ?: return null
    if (detail.songs.isEmpty()) return null
    return Playlist(id = reference.id, remoteId = reference.id, source = reference.source,
        name = detail.name.ifBlank { "导入歌单" }, cover = detail.cover, author = detail.author,
        description = detail.description, count = detail.count.takeIf { it > 0 } ?: detail.songs.size,
        songs = detail.songs.toMutableList(), local = false)
}
