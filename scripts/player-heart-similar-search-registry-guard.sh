#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
controller = (base / "PlaybackController.kt").read_text()
context = (base / "HeartPlaylistContext.kt").read_text()
policy = (base / "HeartRecommendationPolicy.kt").read_text()
registry = (base / "source/MusicSourceRegistry.kt").read_text()
routing = (base / "source/PlaybackSearchRouting.kt").read_text()
tests = (root / "app/src/test/java/xyz/cncnlp/soundwave/source/PlaybackSearchRoutingTest.kt").read_text()
matrix = (root / "docs/source-capability-matrix.md").read_text()
version = (root / "VERSION").read_text().strip()
build = (root / "app/build.gradle").read_text()


def require(ok, message):
    if not ok:
        raise SystemExit(f"FAIL: {message}")


# Ignore comments while preserving string literals: a commented-out call cannot
# keep the guard green after disconnecting the actual consumer.
def code_only(source):
    return re.sub(
        r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/',
        lambda m: "" if m.group().startswith(("//", "/*")) else m.group(),
        source,
    )


controller = code_only(controller)
context = code_only(context)
policy = code_only(policy)
routing = code_only(routing)
registry = code_only(registry)
require(
    'source.takeIf { it in MusicSourceRegistry.platformIds }' in routing,
    "route selector accepts only exact canonical platform IDs",
)
require(
    re.search(r'val platformIds:\s*List<String>\s*=\s*listOf\("wy", "kg", "kw", "tx", "mg"\)', registry),
    "registry retains all five canonical platform IDs",
)
require(
    re.search(
        r'private suspend fun searchHeartSimilarServer\(keyword: String, source: String\): List<Song> \{'
        r'\s*val registrySource = heartSimilarSearchRegistrySourceOrNull\(source\)'
        r'\s*return if \(registrySource != null\) \{'
        r'\s*MusicSourceRegistry\.platformSource\(registrySource\)\.searchServer\(keyword\)'
        r'\s*\} else \{\s*ApiClient\.search\(keyword, source\)\s*\}\s*\}',
        controller,
    ),
    "canonical sources use server-only search and other sources keep the exact ApiClient fallback",
)
# Select the real method by its declaration, and stop at the next controller-level declaration.
entries = list(re.finditer(r'^    suspend fun playHeartSimilar\(\): Unit = coroutineScope heart@ \{', controller, re.M))
require(len(entries) == 1, "expected one playHeartSimilar entry")
start = entries[0].start()
next_method = re.search(r'^    (?:(?:private|internal|public|suspend)\s+)*fun [A-Za-z0-9_]+\(',
                        controller[entries[0].end():], re.M)
require(next_method is not None, "cannot isolate playHeartSimilar body")
end = entries[0].end() + next_method.start()
body = controller[start:end]
require(
    not re.search(r'\bApiClient\.search\s*\(|\.search(?:Server|Typed|TypedResult)?\s*\(', body),
    "playHeartSimilar delegates searches through the canonical helper",
)
require(
    'val profile = playlistContext?.profile ?: HeartRecommendationPolicy.profile(getUserBundle().favs.toList())' in body
    and re.search(r'if \(profile\.seeds\.isEmpty\(\)\)\s*\{[^{}]*return@heart', body)
    and 'val excluded = PlayerService.snapshot().queue + item' in body
    and 'HeartRecommendationPolicy.candidates(profile,' in body,
    "playlist profile or legacy likes teach; queue and current item only exclude",
)
require(
    'val queries = seeds.flatMap { artistParts(it.singer) }.distinctBy(::normalized)' in policy
    and 'return Profile(seeds, queries.map(::normalized), queries)' in policy
    and 'seeds.map { it.name }' not in policy,
    "heart search queries derive from artist names, never song titles",
)
require(
    'val searchSources: List<String>' in context
    and re.search(r'get\(\) = \(listOf\(source\) \+ profile\.seeds\.map \{ it\.source \}\)'
                  r'\s*\.filter \{ it in listOf\("wy", "kg", "kw", "tx", "mg"\) \}'
                  r'\.distinct\(\)\.take\(2\)', context)
    and 'profile = HeartRecommendationPolicy.profile(songs.toList())' in context,
    "playlist search sources are exact-canonical, whitelisted, deduplicated and capped at two",
)
require(
    not re.search(r'\.shuffled\(\)', body),
    "heart artist sampling is affinity-weighted via TasteProfileV2, never unweighted shuffle",
)
require(
    'val plats = playlistContext?.searchSources' in body
    and re.search(r'\?: \(listOf\(preferredSource\) \+ listOf\("wy", "kg"\)\)\.distinct\(\)\.take\(2\)', body)
    and 'val queryArtists = TasteProfileV2.heartQueryPlan(profile.queries, taste, item, Random)' in body
    and re.search(r'queryArtists\.flatMap \{ singer -> plats\.map \{ plat ->'
                  r'\s*async \{[\s\S]*?withTimeoutOrNull\(6000L\) \{ searchHeartSimilarServer\(singer, plat\) \}', body)
    and '.flatMap { it.await() }' in body
    and 'HeartRecommendationPolicy.candidates(profile, DislikeStore.filter(context, acc + cachedCandidates), excluded, seen, preferredSource)' in body,
    "playlist sources or the two-source legacy fallback run at most two artist queries concurrently with a 6s per-request timeout, then filter candidates",
)
require(
    'val taste = getTasteProfile()' in body
    and 'if (taste != null) similar.sortedByDescending { taste.songScore(it) } else similar' in body,
    "heart continuation ranks eligible candidates by unified taste score and plays the highest",
)
require(
    re.search(r'fun searchIsCurrent\(\): Boolean =\s*heartSessionRevision == sessionRevision'
              r'[\s\S]*?playbackTransactionGate\.isCurrent\(transaction, currentSong\?\.key\(\), queueRevision\)'
              r'[\s\S]*?PlayerService\.currentGeneration\(\) == serviceGeneration'
              r'[\s\S]*?PlayerService\.isPlaybackIntentCurrent\(serviceIntent\)', body)
    and 'if (!searchIsCurrent()) return@heart' in body
    and 'heartSessionRevision == sessionRevision' in body
    and 'playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision)' in body,
    "session revision, transaction gate and playback-intent guards reject stale search and playback results",
)
require(
    re.search(r'catch \(e: CancellationException\) \{ throw e \}\s*catch \(_: Exception\) \{ emptyList\(\) \}', body)
    and 'HeartRecommendationPolicy.identity(it) != HeartRecommendationPolicy.identity(target)' in body
    and re.search(r'fun identity\(song: Song\): String\?', policy)
    and 'id != null && song.key() !in excludedKeys && id !in excludedIdentities' in policy
    and 'artists(song.singer).any { it in profile.artists }' in policy
    and 'normalized(song.name) !in seedTitles' in policy
    and '.distinctBy(::identity)' in policy,
    "cancellation propagates and only identity-valid, nonexcluded same-artist candidates survive",
)
require(
    'listOf("wy", "kg", "kw", "tx", "mg")' in tests
    and '"WY"' in tests and '" wy"' in tests and '"wy "' in tests
    and '"netease"' in tests and '"qq"' in tests and '"script:test"' in tests and '"cloud"' in tests,
    "pure JVM routing test retains canonical and representative noncanonical source coverage",
)
require(
    "按歌单上下文和同歌手" in matrix
    and "缺少逐曲曲风" in matrix
    and "专用相似歌曲 API" in matrix
    and "本能力不是推荐接口" in matrix,
    "source matrix documents playlist-context search capability and its non-recommendation limits",
)
match = re.fullmatch(r"1\.0\.(\d+)", version)
require(
    match is not None and f'versionCode {match.group(1)}' in build and f'versionName "{version}"' in build,
    "VERSION and Gradle version declarations remain synchronized",
)
print("Heart-similar playlist-context Source Registry contract guard passed")
PY
