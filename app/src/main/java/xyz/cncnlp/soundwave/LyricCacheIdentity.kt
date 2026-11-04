package xyz.cncnlp.soundwave

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Lyrics-only cache identity. Do not replace Song.key(): that key also defines queue and playback transaction identity.
 * For songs without provider IDs, retain raw metadata and available provider identifiers rather than Song.key()'s
 * lossy normalization. Truly identical ID-less metadata still cannot be distinguished without a stable provider ID.
 */
internal fun lyricCacheKey(song: Song, format: String = "plain"): String {
    val source = song.source.trim().lowercase(Locale.ROOT).ifBlank { "wy" }
    val parts = if (song.id.isNotBlank()) {
        listOf("provider-id", source, song.id, format)
    } else {
        listOf(
            "metadata",
            source,
            song.name,
            song.singer,
            song.duration.toString(),
            song.album,
            song.albumId,
            song.hash,
            song.copyrightId,
            song.mediaMid,
            format
        )
    }
    return buildString {
        append("lyric-key-v2")
        parts.forEach { value ->
            val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
            append(':').append(byteLength).append(':').append(value)
        }
    }
}

/** A versioned SHA-256 filename over the complete cache key; legacy sanitized/truncated filenames are not read. */
internal fun lyricCacheFileName(key: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("lyric-file-v3\u0000$key".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "lyric-v3-$digest"
}
