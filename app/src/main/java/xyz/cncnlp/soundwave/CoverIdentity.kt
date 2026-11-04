package xyz.cncnlp.soundwave

/** Stable playlist identity for lazy item reuse; mutable title/artwork are intentionally excluded. */
internal fun featuredPlaylistStableKey(playlist: Playlist, fallbackSource: String): String {
    fun part(value: String) = "${value.length}:$value"
    val source = canonicalSourceId(playlist.source.ifBlank { fallbackSource })
    val providerId = playlist.remoteId.ifBlank { playlist.id }
    return "featured:${part(source)}${part(providerId)}"
}

/** Per-card request state is distinct by owning item, normalized URI and decode size. */
internal fun coverRequestStateKey(source: String, itemId: String, rawUrl: String, targetSizePx: Int): String {
    fun part(value: String) = "${value.length}:$value"
    val normalized = normalizeCoverUrl(rawUrl, targetSizePx)
    return "cover-state:${part(canonicalSourceId(source))}${part(itemId)}${part(targetSizePx.coerceAtLeast(1).toString())}${part(normalized)}"
}

/** Coil cache content identity follows the canonical URI and actual target pixels, not card order. */
internal fun featuredCoverMemoryCacheKey(rawUrl: String, targetSizePx: Int): String {
    val size = targetSizePx.coerceAtLeast(1)
    val normalized = normalizeCoverUrl(rawUrl, size)
    return "home-featured-v6:${size}x$size:$normalized"
}
