package xyz.cncnlp.soundwave

import java.text.Normalizer
import java.util.Locale

/** Explicit source-list policy: playlist seeds or legacy likes teach; playback queue only excludes. */
internal object HeartRecommendationPolicy {
    const val SEEN_LIMIT = 512
    data class Profile(val seeds: List<Song>, val artists: List<String>, val queries: List<String>)

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun artistParts(value: String): List<String> = value.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+|[/、;&＆]+"))
        .map { it.trim() }.filter { normalized(it).isNotEmpty() }

    private fun artists(value: String): List<String> = artistParts(value).map(::normalized).distinct().sorted()

    /** Do not strip live/remix/version words: different recordings must not become aliases. */
    fun identity(song: Song): String? {
        val title = normalized(song.name)
        val artist = artists(song.singer)
        return if (title.isEmpty() || artist.isEmpty()) null else "$title|${artist.joinToString("+")}"
    }

    fun profile(sourceSongs: List<Song>): Profile {
        val seeds = sourceSongs.filter { identity(it) != null }.distinctBy(::identity)
        val queries = seeds.flatMap { artistParts(it.singer) }.distinctBy(::normalized)
        return Profile(seeds, queries.map(::normalized), queries)
    }

    fun remember(seen: List<String>, song: Song): List<String> {
        val id = identity(song) ?: return seen.takeLast(SEEN_LIMIT)
        return (seen.filter { it != id } + id).takeLast(SEEN_LIMIT)
    }

    fun encodeSeen(seen: List<String>): String = seen.takeLast(SEEN_LIMIT).joinToString("\n")

    fun decodeSeen(raw: String): List<String> = raw.lineSequence()
        .filter { it.length in 3..1024 && '|' in it }.toList().distinct().takeLast(SEEN_LIMIT)

    fun candidates(profile: Profile, candidates: List<Song>, excluded: List<Song>, seen: List<String>, preferredSource: String): List<Song> {
        if (profile.seeds.isEmpty()) return emptyList()
        val excludedSongs = profile.seeds + excluded
        val excludedKeys = excludedSongs.map { it.key() }.toSet()
        val excludedIdentities = excludedSongs.mapNotNull(::identity).toSet()
        val seedTitles = profile.seeds.map { normalized(it.name) }.toSet()
        val preferred = preferredSource.lowercase(Locale.ROOT)
        val eligible = candidates.sortedBy { if (it.source.lowercase(Locale.ROOT) == preferred) 0 else 1 }
            .filter { song ->
                val id = identity(song)
                id != null && song.key() !in excludedKeys && id !in excludedIdentities &&
                    artists(song.singer).any { it in profile.artists } &&
                    // A liked title by a different performer is not a similarity signal.
                    normalized(song.name) !in seedTitles
            }.distinctBy(::identity)
        val fresh = eligible.filter { identity(it) !in seen }
        // Recycle least recently played only after fresh eligible results are exhausted.
        val ranked = if (fresh.isNotEmpty()) fresh else eligible.sortedBy { seen.indexOf(identity(it)) }
        val counts = mutableMapOf<String, Int>()
        return ranked.filter { song ->
            val artist = artists(song.singer).joinToString("+")
            val count = counts.getOrDefault(artist, 0)
            if (count >= 3) false else { counts[artist] = count + 1; true }
        }.take(24)
    }
}
