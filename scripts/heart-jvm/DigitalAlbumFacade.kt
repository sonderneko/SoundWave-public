package xyz.cncnlp.soundwave.source.direct

suspend fun fetchMgDigitalAlbumDetailDirect(id: String): xyz.cncnlp.soundwave.Playlist? =
    xyz.cncnlp.soundwave.FixtureCatalog.fetch("mg", id, "digital")
