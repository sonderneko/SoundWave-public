package xyz.cncnlp.soundwave.source.direct

/** Local collection identity includes provider and entity kind; remoteId remains unchanged. */
internal fun onlineImportLocalPlaylistId(raw: String): String? {
    val reference = parseOnlineImportReference(raw) ?: return null
    return "imp_${reference.source}_${reference.kind.name.lowercase(java.util.Locale.ROOT)}_${reference.id}"
}

/** r658c D#3：短链导入时原文无法离线解析，按已规范化的导入结果生成同一格式的本地 ID。 */
internal fun onlineImportLocalPlaylistId(raw: String, imported: xyz.cncnlp.soundwave.Playlist): String? =
    onlineImportLocalPlaylistId(raw) ?: imported.remoteId.takeIf { it.isNotBlank() && imported.source.isNotBlank() }?.let { id ->
        val kind = if (imported.songs.isNotEmpty() && imported.songs.all { it.albumId == id }) "album" else "playlist"
        "imp_${imported.source}_${kind}_$id"
    }
