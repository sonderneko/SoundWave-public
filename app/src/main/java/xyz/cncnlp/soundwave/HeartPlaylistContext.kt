package xyz.cncnlp.soundwave

import java.security.MessageDigest

/** Local collection identity is retained as well as the provider/remote identity.
 * Album and playlist collections may have the same remote number; never merge them.
 * Names/covers/song order are editable and are deliberately not identity fields.
 */
internal fun heartPlaylistKey(playlist: Playlist): String =
    listOf(playlist.source, playlist.id, playlist.remoteId).joinToString("|") { "${it.length}:$it" }

internal data class HeartPlaylistContext(
    val key: String,
    val name: String,
    val source: String,
    val profile: HeartRecommendationPolicy.Profile
) {
    // Only catalog platforms with an existing server search implementation. Script/local/unknown
    // providers cannot be treated as search providers or silently relabelled as another platform.
    val searchSources: List<String>
        get() = (listOf(source) + profile.seeds.map { it.source })
            .filter { it in listOf("wy", "kg", "kw", "tx", "mg") }.distinct().take(2)

    val seenPreferenceKey: String
        get() = "heart_seen_playlist_v1_" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        fun from(playlist: Playlist, songs: List<Song>): HeartPlaylistContext = HeartPlaylistContext(
            key = heartPlaylistKey(playlist),
            name = playlist.name,
            source = playlist.source,
            // Snapshot the explicit source list, never the mutable playback queue or global likes.
            profile = HeartRecommendationPolicy.profile(songs.toList())
        )
    }
}
