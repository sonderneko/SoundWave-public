#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
resolver = (base / 'UrlResolver.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
interface = (base / 'source/MusicSource.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
cache = (base / 'ScriptProbeCache.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
probe_matrix = (base / 'ScriptProbeMatrix.kt').read_text()
budget = (base / 'ResolveBudget.kt').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start_marker = '    suspend fun probeScriptScored('
end_marker = '    /** Compatibility entry point; always uses the validated structured probe. */'
start = resolver.find(start_marker)
end = resolver.find(end_marker, start)
if start < 0 or end < 0 or resolver.find(start_marker, start + 1) >= 0:
    raise SystemExit('FAIL: expected one bounded probeScriptScored implementation')
probe = re.sub(r'//[^\n]*', '', resolver[start:end])

sample_start = probe.find('        val samples = plan.samples')
fixed_samples = re.search(r'val fixedSamples = listOf\((.*?)\n    \)', probe_matrix, re.S)
if sample_start < 0 or fixed_samples is None:
    raise SystemExit('FAIL: centralized fixed sample matrix boundary is missing')
sample_block = ' '.join(fixed_samples.group(1).split())
expected_samples = ' '.join((
    'ScriptProbeSample("wy", "鲜花", ""), ScriptProbeSample("wy", "夜曲", "周杰伦"), '
    'ScriptProbeSample("kw", "晴天", "周杰伦"), ScriptProbeSample("kw", "演员", "薛之谦"), '
    'ScriptProbeSample("tx", "晴天", "周杰伦"), ScriptProbeSample("tx", "稻香", "周杰伦"), '
    'ScriptProbeSample("kg", "演员", "薛之谦"), ScriptProbeSample("kg", "丑八怪", "薛之谦"), '
    'ScriptProbeSample("mg", "最佳损友", "陈奕迅"), ScriptProbeSample("mg", "K歌之王", "陈奕迅")'
).split())

search_call = 'crossSourceSearchSongs(plat, kw)'
api_call = 'ApiClient.search(kw, plat)'
route_count = probe.count(search_call)
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

quality_loop = probe.find('            for (q in qOrder)')
search_index = probe.find('val results = ResolveBlockingStage.run { ' + search_call + ' }')

    # ACCEPT6 re-pin (qqslow delivered-duration integrity, 2026-11-04): validateProbeMedia
    # gained a trailing expectedDurationMs argument (song/music duration at the call
    # sites) so delivered files can be rejected as preview fragments. The three pins
    # below follow the new call shapes verbatim; strictness is unchanged (exact
    # call-site strings, now including the duration source at each site).
checks = {
    'blocking stage propagates caller cancellation and suppresses stale delivery':
        'continuation.invokeOnCancellation { job.cancel() }' in budget
        and 'if (continuation.isActive) continuation.resume(value)' in budget,
    'probe still rejects blank/incompatible scripts before using fixed samples and has no ProviderRegistry/canRemote gate':
        0 <= probe.find('if (sc.code.isBlank())') < sample_start
        and 0 <= probe.find('ScriptStorage.compatibilityError(sc.code)') < sample_start
        and 'ProviderRegistry' not in probe and 'canRemote' not in probe,
    'sample platforms and order remain fixed canonical wy, kw, tx, kg, mg with complete (no sampling) traversal':
        sample_block == expected_samples
        and re.findall(r'ScriptProbeSample\("(wy|kw|tx|kg|mg)"', sample_block) == ['wy', 'wy', 'kw', 'kw', 'tx', 'tx', 'kg', 'kg', 'mg', 'mg']
        and 'val platformOrder = listOf("wy", "kw", "tx", "kg", "mg")' in probe_matrix
        and 'val platforms = platformOrder.take(maxPlats.coerceIn(1, platformOrder.size))' in probe_matrix
        and 'return fixedSamples.filter { it.platform in platforms }' in probe_matrix
        and 'take(1)' not in probe_matrix,
    'sample query stays non-empty and the shared local-first search helper is the only search in this function':
        'val kw = listOf(name, singer).filter { it.isNotBlank() }.joinToString(" ")' in probe
        and route_count == 1 and api_call not in probe and 'searchServer' not in probe,
    # ACCEPT6 re-pin (cross-source server-leg retirement, 2026-11-04): both
    # /music/search server legs in the resolve chain are deleted outright
    # (coverage verified in WAVE-ACCEPT6-CROSSLEGS §1). The shared helper
    # is direct-only and both admission predicates must stay absent — a
    # deletion contract, equal-or-stricter than the previous ordering pin.
    'the shared cross-source search helper is direct-only and both server-leg predicates stay deleted (retirement contract)': (
        0 <= resolver.find('private suspend fun crossSourceSearchSongs')
        and 'MusicSourceRegistry.directSearchSongs(plat, kw)' in resolver[resolver.find('private suspend fun crossSourceSearchSongs'):resolver.find('private suspend fun crossSourceSearchSongs') + 900]
        and 'serverSearchFallbackAdmitted' not in resolver
        and 'searchServer' not in resolver
    ),
    'sample search remains before the same serial quality-probe loop':
        search_index >= 0 and quality_loop > search_index
        and 'for (sample in samples)' in probe
        and 'val (plat, name, singer) = sample' in probe,
    'selected sample is consumed by the PROBE runtime and validated with its target source':
        'val song = try {' in probe
        and 'runtime.getMusicUrl(sc.id, sc.code, buildMusicInfo(song), plat, q, ResolveTicket.Purpose.PROBE, scriptMeta = scriptMetaJson(sc))' in probe
        and 'validateProbeMedia(plat, q, mediaUrl, dt, ResolveTicket.Purpose.PROBE, song.duration)' in probe
        and 'val mediaUrl = normalizeKgMediaUrl(u)' in probe
        and 'attempts += attempt' in probe,
    'single-slot media validation dispatcher is exclusive to probes; PLAY and DOWNLOAD use the shared client':
        'if (purpose == ResolveTicket.Purpose.PROBE) probeMediaClient else AppHttp.shared' in resolver
        and 'mediaValidationClient(purpose).newBuilder()' in resolver
        and 'maxRequests = 1; maxRequestsPerHost = 1' in resolver
        and 'connectionPool(okhttp3.ConnectionPool(1, 30, java.util.concurrent.TimeUnit.SECONDS))' in resolver
        and 'callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)' in resolver
        and 'validateProbeMedia(hit.platform, hit.actualQuality, hitUrl, 0L, purpose, song.duration)' in resolver
        and 'val hitUrl = normalizeKgMediaUrl(hit.url)' in resolver
        and 'validateProbeMedia(source, q, mediaUrl, attemptElapsed, purpose, music.duration)' in resolver
        and 'val mediaUrl = normalizeKgMediaUrl(u)' in resolver,
    'search cache is not read before the sample query and the report is saved only after probing':
        not re.search(r'ScriptProbeCache\.(?:reportData|score|avgMs|report)\s*\(', probe)
        and probe.count('ScriptProbeCache.save(data)') == 1
        and probe.find('ScriptProbeCache.save(data)') > probe.find('val data = ScriptProbeCache.Report('),
    'candidate selection order, catch behavior, and empty/no-match classification stay unchanged':
        'results.firstOrNull { probeSongMatches(it, name, singer) }' in probe
        and '?: results.firstOrNull { SongMatcher.normalize(it.name) == SongMatcher.normalize(name) }' in probe
        and '?: results.firstOrNull { SongMatcher.normalize(it.name).contains(SongMatcher.normalize(name)) }' in probe
        and re.search(r'catch \(e: Exception\) \{\s*if \(e is kotlinx.coroutines.CancellationException\) throw e\s*if \(searchStarted > 0L\)', probe) is not None
        and 'searchFailureReason = "搜索失败：${e.javaClass.simpleName}"' in probe
        and 'ScriptProbeCache.Outcome.UNSUPPORTED' in probe
        and 'ScriptProbeCache.Outcome.FAILED' in probe,
    'canonical IDs and factory retain the same ApiClient; server-only adapter adds no direct candidates':
        re.findall(r'"([a-z]{2})"', re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry).group(1))
        == ['wy', 'kg', 'kw', 'tx', 'mg']
        and 'api = ApiClientCatalogGateway' in registry and 'platform = id' in registry
        and f'override suspend fun searchServer(keyword: String, type: String): List<Song> =\n        api.searchCancellable(keyword, source = platform, type = type)' in adapter,
    'MusicSource default remains server-only song search and ApiClient preserves cancellable transport, both request variants, and parser':
        'suspend fun searchServer(keyword: String, type: String = "song"): List<Song> = emptyList()' in interface
        and 'fun search(keyword: String, source: String, type: String = "song"): List<Song>' in api
        and 'internal suspend fun searchCancellable(keyword: String, source: String, type: String = "song")' in api
        and 'getScoped("/music/search?keyword=$s&source=$source&type=$t"' in api
        and 'if (raw.isEmpty()) raw = getScoped("/music/search?keyword=$s&source=$source"' in api
        and 'parseSongList(' in api,
    'matrix records source order, no cache short-circuit, unchanged matching, and the client-only evidence boundary':
        '### UrlResolver 脚本探测固定样本搜索的 Source Registry 委派' in matrix
        and 'wy → kw → tx → kg → mg' in matrix
        and '搜索前不读取' in matrix
        and 'probeSongMatches' in matrix
        and '不验证服务端在线行为' in matrix,
    'version declarations remain synchronized':
        version_match is not None and gradle_name is not None and gradle_code is not None
        and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('UrlResolver probe sample search Registry contract guard passed')
PY
