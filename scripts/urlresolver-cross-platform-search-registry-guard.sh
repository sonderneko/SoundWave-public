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
models = (base / 'ResolutionModels.kt').read_text()
store = (base / 'SmartSourceStore.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
interface = (base / 'source/MusicSource.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start_marker = 'val searchResults = try {'
end_marker = '// 可解释淘汰：'
if resolver.count(start_marker) != 1:
    raise SystemExit('FAIL: expected exactly one cross-platform candidate search request')
start = resolver.index(start_marker)
end = resolver.find(end_marker, start)
if end < 0:
    raise SystemExit('FAIL: cross-platform candidate search result boundary is missing')
route = re.sub(r'//[^\n]*', '', resolver[start:end])
normalized_route = ' '.join(route.split())
expected_route = (
    'val searchResults = try { '
    'if (plat in MusicSourceRegistry.platformIds) { '
    'ResolveBlockingStage.run { crossSourceSearchSongs(plat, kw) } '
    '} else { ApiClient.searchCancellable(kw, plat) } '
    '} catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e emptyList<Song>() }'
)

stage_start = resolver.find('SmartSourceStore.STAGE_CROSS_PLAT -> {')
stage_end = resolver.find('val qs = (highQs + lowQs + listOf("320k", "128k")).distinct()', stage_start)
stage = re.sub(r'//[^\n]*', '', resolver[stage_start:stage_end]) if stage_start >= 0 and stage_end > stage_start else ''
budget = (base / 'ResolveBudget.kt').read_text()
ids_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', ids_match.group(1)) if ids_match else []
version_match = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)', version)
gradle_name = re.search(r'versionName\s+"([^"]+)"', build)
gradle_code = re.search(r'versionCode\s+(\d+)', build)

checks = {
    'blocking stage propagates caller cancellation and suppresses stale delivery':
        'continuation.invokeOnCancellation { job.cancel() }' in budget
        and 'if (continuation.isActive) continuation.resume(value)' in budget,
    # ACCEPT5 re-pin (user ruling 2, 2026-11-04): candidate platform iteration
    # must follow the user's configured platform order — the stage now derives
    # targets via crossPlatTargetsInUserOrder (user order; store default order
    # as the only fallback, no call-site hardcoded platform list, which the
    # ruling forbids). The four-platform bound moved into that function's
    # default limit and is pinned against ResolutionModels below. Every other
    # protection of the old check (origin-only, source exclusion, keyword,
    # timeout) is preserved verbatim; strictness is unchanged or higher.
    'the cross-platform stage keeps origin-only, user-order source exclusion, keyword, timeout, and four-platform bounds': all(
        fragment in stage for fragment in (
            'if (originOnly) continue',
            'crossPlatTargetsInUserOrder(SmartSourceStore.enabledPlatformsInOrder(), src)',
            'crossPlatTargetsInUserOrder(SmartSourceStore.defaultPlatformOrder(), src)',
            'if (kw.isBlank()) continue',
            'for (plat in plats)',
            'if (System.currentTimeMillis() - started > globalMs - 600) break',
        )
    ) and 'fun crossPlatTargetsInUserOrder(' in models and 'limit: Int = 4' in models
        and 'if (plat.isBlank() || plat == source || plat in out) continue' in models
        and 'if (out.size >= limit) break' in models,
    'only exact canonical IDs use the local-first cross-source search helper; all other source values keep the exact legacy API call and catch': platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'] and normalized_route == expected_route,
    # ACCEPT6 re-pin (cross-source server-leg retirement, 2026-11-04): both
    # /music/search server legs in the resolve chain are deleted outright
    # (coverage verified in WAVE-ACCEPT6-CROSSLEGS §1: the five direct
    # searches are line-by-line ports of the server search functions and
    # carry equal-or-better matcher fields). The helper is direct-only and
    # both admission predicates must stay absent — a deletion contract,
    # equal-or-stricter than the previous ordering pin. searchServer itself
    # is NOT deleted (heart/similar and the search page keep server-only
    # consumers); its interface/adapter pins below are unchanged.
    'the shared cross-source search helper is direct-only and both server-leg predicates stay deleted (retirement contract)': (
        resolver.count('crossSourceSearchSongs(plat, kw)') == 2
        and 0 <= resolver.find('private suspend fun crossSourceSearchSongs')
        and 'MusicSourceRegistry.directSearchSongs(plat, kw)' in resolver[resolver.find('private suspend fun crossSourceSearchSongs'):resolver.find('private suspend fun crossSourceSearchSongs') + 900]
        and 'serverSearchFallbackAdmitted' not in resolver
        and 'searchServer' not in resolver
        and 'fun serverSearchFallbackAdmitted' not in models
    ),
    'configured platform ordering still permits custom IDs, so noncanonical IDs retain the old request': 'val c = o.optString("c").ifEmpty { o.optString("code") }' in store and 'if (c.isNotEmpty()) out.add(PlatItem(c, o.optBoolean("e", true)))' in store and 'else { ApiClient.searchCancellable(kw, plat) }' in normalized_route,
    'the candidate request remains after quality selection and propagates cancellation before empty fallback': stage.find('val platPreferQ') >= 0 and stage.find('val platPreferQ') < stage.find(start_marker) and 'catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e emptyList<Song>() }' in normalized_route,
    # ACCEPT5 re-pin (E3): per-candidate reject rows are now capped stricter
    # than the old eight-row bound — at most three individual rows plus one
    # aggregated reject_summary row per result set — and matching runs against
    # matchSource (the source song with its duration filled from the same
    # platform's own search by identical id; identity gates themselves are
    # unchanged and still SongMatcher's). Rank limit preserved verbatim.
    'candidate diagnostics stay bounded (three rows plus summary), identity filtering and rank limit preserved': 'rejects.take(3)' in stage and '"reject_summary:$dominant:${rejects.size}"' in stage and 'SongMatcher.rejectReason(matchSource, cand)' in stage and 'SongMatcher.rank(matchSource, searchResults, limit = 2)' in stage and 'song.copy(duration = it.duration)' in stage,
    'ranked candidates try the target-platform direct resolve first, then its scripts, with identity matching unchanged':
        'SmartSourceStore.orderedScripts(plat, scripts)' in stage
        and 'canAttemptAnonymousResolutionBeforeScripts(plat, platPreferQ, false, false)' in stage
        and 0 <= stage.find('directResolveUrlOrNull(plat, cand.id, platPreferQ)') < stage.find('for (sc in platPool)')
        and re.search(r'for \(sc in platPool\) \{\s*val hit = tryScript\(sc, plat, cand, qs, true, stageDeadline\)', stage) is not None,
    'MusicSource exposes the same server-only song-search default without adding direct candidates': 'suspend fun searchServer(keyword: String, type: String = "song"): List<Song> = emptyList()' in interface,
    'the adapter server-only method delegates to the identical cancellable ApiClient source, type, and List result': 'override suspend fun searchServer(keyword: String, type: String): List<Song> =\n        api.searchCancellable(keyword, source = platform, type = type)' in adapter,
    'Registry factory binds the canonical source to the ApiClient catalog gateway': 'api = ApiClientCatalogGateway' in registry and 'platform = id' in registry,
    'ApiClient keeps the same default song route, cancellable transport, empty-response type-less retry, and parser return path': 'fun search(keyword: String, source: String, type: String = "song"): List<Song>' in api and 'internal suspend fun searchCancellable(keyword: String, source: String, type: String = "song")' in api and 'getScoped("/music/search?keyword=$s&source=$source&type=$t"' in api and 'if (raw.isEmpty()) raw = getScoped("/music/search?keyword=$s&source=$source"' in api and 'parseSongList(' in api,
    'the matrix documents exact canonical routing, custom-source fallback, unchanged cross-platform orchestration, and the separate fixed-sample probe delegate': '## UrlResolver 跨平台候选搜索的 Source Registry 迁移点' in matrix and 'searchServer(kw)' in matrix and 'ApiClient.searchCancellable(kw, plat)' in matrix and 'crossSourceSearchSongs(plat, kw)' in matrix and 'directSearchSongs' in matrix and '固定样本脚本探测搜索已单独迁移，见下一节' in matrix and '### UrlResolver 脚本探测固定样本搜索的 Source Registry 委派' in matrix,
    'version declarations remain synchronized': version_match is not None and gradle_name is not None and gradle_code is not None and gradle_name.group(1) == version and gradle_code.group(1) == version_match.group(3),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('UrlResolver cross-platform search Registry contract guard passed')
PY
