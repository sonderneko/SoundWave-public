package xyz.cncnlp.soundwave.source

/** Preserve legacy server-search routing: only exact canonical platform IDs may enter the Registry. */
internal fun heartSimilarSearchRegistrySourceOrNull(source: String): String? =
    source.takeIf { it in MusicSourceRegistry.platformIds }
