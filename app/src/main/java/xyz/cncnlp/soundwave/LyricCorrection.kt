package xyz.cncnlp.soundwave

import java.text.Normalizer
import java.util.Locale

/** A candidate is only offered after its metadata and lyric payload pass the correction trust checks. */
internal data class LyricCorrectionCandidate(
    val song: Song,
    val lyrics: String,
    val similarity: Float,
    val exactNativeId: Boolean = false,
)

internal data class LyricCorrectionScoredSong(
    val song: Song,
    val similarity: Float,
)

internal sealed interface LyricCorrectionSearchResult {
    data class Found(val candidates: List<LyricCorrectionCandidate>) : LyricCorrectionSearchResult
    data class NoTrustedCandidates(val message: String) : LyricCorrectionSearchResult
    data class Failed(val message: String) : LyricCorrectionSearchResult
}

/** Stable, length-prefixed override identity; does not mutate Song.key() or provider identities. */
internal fun lyricCorrectionIdentity(song: Song): String {
    val source = song.source.trim().lowercase(Locale.ROOT).ifBlank { "unknown" }
    val key = song.key()
    val lyricIdentity = lyricCacheKey(song, "user-correction-v1")
    // Migu Song.key may be based on contentId; copyrightId distinguishes its lyric/version resource.
    val providerVariant = if (lyricCorrectionPlatformId(song) == "mg") song.copyrightId.trim() else ""
    return buildString {
        append("lyric-user-correction-v1")
        listOf(source, key, lyricIdentity, providerVariant).forEach { part ->
            append(':').append(part.toByteArray(Charsets.UTF_8).size).append(':').append(part)
        }
    }
}

/** Only provider-native IDs that have stable source semantics are eligible for an exact lookup. */
internal fun lyricCorrectionPlatformId(song: Song): String? = when (song.source.trim().lowercase(Locale.ROOT)) {
    "wy", "netease" -> "wy"
    "kg", "kugou" -> "kg"
    "kw", "kuwo" -> "kw"
    "mg", "migu" -> "mg"
    "tx", "qq", "tencent" -> "tx"
    else -> null
}

/**
 * Migu's search `Song.id` may be `contentId`; only `copyrightId` addresses its lyric endpoint.
 * QQ's `Song.id` is `songmid`; `mediaMid` is a file/vkey identity and must never be substituted.
 */
internal fun lyricCorrectionNativeId(song: Song): String? {
    val candidate = when (lyricCorrectionPlatformId(song)) {
        "wy" -> song.id.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.any { c -> c != '0' } }
        "kg" -> (song.hash.ifBlank { song.id }).trim().takeIf { Regex("(?i)^[0-9a-f]{32}$").matches(it) }
        "kw" -> song.id.trim().removePrefix("MUSIC_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.any { c -> c != '0' } }
        "mg" -> song.copyrightId.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) && it != "0" }
        // A QQ mediaMid is deliberately not a fallback: it identifies a playable file, not songmid.
        "tx" -> song.id.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) && it != "0" }
        else -> null
    }
    return candidate
}

/** Normalize provider display strings without conflating different source IDs or Song.key values. */
private fun normalizedMatchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

/** Kuwo search returns 60..10000 as seconds; player and other providers use milliseconds. */
internal fun lyricCorrectionDurationMs(duration: Long): Long =
    if (duration in 60L..10_000L) duration * 1000L else duration

internal fun lyricCorrectionTextSimilarity(left: String, right: String): Float {
    val a = normalizedMatchText(left)
    val b = normalizedMatchText(right)
    if (a.isEmpty() || b.isEmpty()) return if (a == b) 1f else 0f
    if (a == b) return 1f
    val previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return (1f - previous[b.length].toFloat() / maxOf(a.length, b.length)).coerceIn(0f, 1f)
}

/** Exact source + title/artist + duration match; incomplete duration never silently admits another version. */
internal fun rankTrustedLyricCorrectionSongs(
    target: Song,
    candidates: List<Song>,
): List<LyricCorrectionScoredSong> {
    val platform = lyricCorrectionPlatformId(target) ?: return emptyList()
    val targetTitle = normalizedMatchText(target.name)
    val targetArtist = normalizedMatchText(target.singer)
    val targetDuration = lyricCorrectionDurationMs(target.duration)
    if (targetTitle.isEmpty() || targetArtist.isEmpty() || targetDuration <= 0L) return emptyList()
    val source = target.source.trim().lowercase(Locale.ROOT)
    val scored = candidates.mapNotNull { candidate ->
        if (lyricCorrectionPlatformId(candidate) != platform) return@mapNotNull null
        if (candidate.source.trim().lowercase(Locale.ROOT) != source &&
            lyricCorrectionPlatformId(candidate) != platform) return@mapNotNull null
        val candidateDuration = lyricCorrectionDurationMs(candidate.duration)
        if (lyricCorrectionNativeId(candidate) == null || candidateDuration <= 0L) return@mapNotNull null
        val titleSimilarity = lyricCorrectionTextSimilarity(target.name, candidate.name)
        val artistSimilarity = lyricCorrectionTextSimilarity(target.singer, candidate.singer)
        val toleranceMs = maxOf(6_000L, (targetDuration * 4L) / 100L)
        val durationDelta = kotlin.math.abs(targetDuration - candidateDuration)
        if (titleSimilarity < 0.84f || artistSimilarity < 0.65f || durationDelta > toleranceMs) return@mapNotNull null
        val durationSimilarity = (1f - durationDelta.toFloat() / toleranceMs.toFloat()).coerceIn(0f, 1f)
        val similarity = (titleSimilarity * 0.50f + artistSimilarity * 0.25f + durationSimilarity * 0.25f)
        if (similarity < 0.80f) return@mapNotNull null
        LyricCorrectionScoredSong(candidate, similarity)
    }
    return scored.distinctBy { lyricCorrectionIdentity(it.song) }
        .sortedByDescending { it.similarity }
}

/** Prefer the confirmed correction lazily; if absent, preserve the legacy sidecar/cache/embedded chain. */
internal inline fun preferUserLyricCorrection(correction: String, fallback: () -> String): String =
    correction.takeIf(String::isNotBlank) ?: fallback()

internal fun isUsableLyricCorrectionText(raw: String): Boolean {
    if (raw.isBlank() || raw.trim().length < 24) return false
    val lines = LyricParser.parse(raw)
    return lines.size >= 2 && lines.count { it.text.isNotBlank() } >= 2
}

/** Storage contract allows the confirmation and undo rules to be tested without Android or disk. */
internal interface LyricCorrectionOverrideStore {
    fun load(identity: String): String
    fun save(identity: String, lyrics: String): Boolean
    fun clear(identity: String): Boolean
}

internal class LyricCorrectionRepository(private val store: LyricCorrectionOverrideStore) {
    fun load(song: Song): String = store.load(lyricCorrectionIdentity(song))

    /** This is called only by the user's explicit Apply action, never by search completion. */
    fun confirm(song: Song, candidate: LyricCorrectionCandidate): Boolean {
        val sourcePlatform = lyricCorrectionPlatformId(song) ?: return false
        if (lyricCorrectionPlatformId(candidate.song) != sourcePlatform) return false
        if (candidate.similarity < 0.80f || !isUsableLyricCorrectionText(candidate.lyrics)) return false
        return store.save(lyricCorrectionIdentity(song), candidate.lyrics)
    }

    fun clear(song: Song): Boolean = store.clear(lyricCorrectionIdentity(song))
}
