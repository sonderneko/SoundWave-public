#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
controller = (base / 'HomeFeedController.kt').read_text()
main = (base / 'MainActivity.kt').read_text()
home_page = (base / 'HomePage.kt').read_text()
library = (base / 'LibraryPage.kt').read_text()
session = (base / 'ui/home/HomeFeedSession.kt').read_text()
interface = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
direct = (base / 'source/direct/FeedDirectFetch.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

manual_start = controller.find('    fun refreshDiscover()')
manual_end = controller.find('\n    /**', manual_start)
if manual_start < 0 or manual_end < 0:
    raise SystemExit('FAIL: refreshDiscover boundary is missing')
manual = controller[manual_start:manual_end]
home_feed_start = controller.find('    fun refreshHomeFeed(')
if home_feed_start < 0:
    raise SystemExit('FAIL: refreshHomeFeed boundary is missing')
home_feed = controller[home_feed_start:]

def section(start_token, end_token):
    start = home_feed.find(start_token)
    end = home_feed.find(end_token, start) if start >= 0 else -1
    return home_feed[start:end] if start >= 0 and end >= 0 else ''

hot_req = section('val hotReq = async(Dispatchers.IO)', 'val rankReq = async(Dispatchers.IO)')
rank_req = section('val rankReq = async(Dispatchers.IO)', 'val playlistReq = async(Dispatchers.IO)')
playlist_req = section('val playlistReq = async(Dispatchers.IO)', 'val discoverReq = async(Dispatchers.IO)')
discover_req = section('val discoverReq = async(Dispatchers.IO)', 'val keywordReq = async(Dispatchers.IO)')
keyword_start = home_feed.find('val keywordReq = async(Dispatchers.IO)')
keyword_req = home_feed[keyword_start:] if keyword_start >= 0 else ''

id_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', id_match.group(1)) if id_match else []
expected_ids = ['wy', 'kg', 'kw', 'tx', 'mg']
filter_start = library.find('val filterOrder = listOf(')
filter_end = library.find('\n        )', filter_start)
filter_ids = re.findall(r'^\s*"([^"]*)"\s+to ', library[filter_start:filter_end], re.M) if filter_start >= 0 and filter_end >= 0 else []
session_selects_on_change_and_forces_refresh = re.search(
    r'fun selectPlatform\(platform: String, currentPlatform: String, onSelected: \(String\) -> Unit\)\s*\{\s*if \(currentPlatform != platform\)\s*\{\s*onSelected\(platform\)\s*controller\.refreshHomeFeed\(forceNetwork = true\)\s*\}\s*\}',
    session,
)
home_picker_callback = re.findall(
    r'onPlatform\s*=\s*\{\s*p\s*->\s*homeFeed\.selectPlatform\(p, homePlatform\)\s*\{\s*homePlatform = it\s*\}\s*\}',
    main,
)
library_platform_callback = re.search(
    r'onPlatformClick\s*=\s*\{\s*code\s*->\s*if\s*\(homePlatform != code\)\s*\{\s*homePlatform = code\s*homeFeed\.refreshHomeFeed\(forceNetwork = true\)\s*\}\s*\}',
    main,
)

api_discover_start = api.find('suspend fun discover(source: String = "wy", limit: Int = 18)')
api_discover_end = api.find('internal fun discoverServerOutcome', api_discover_start)
api_discover = api[api_discover_start:api_discover_end] if api_discover_start >= 0 and api_discover_end >= 0 else ''

checks = {
    'homePlatform begins at wy; home selection delegates via it and force-refreshes only on change; blank library option never writes it': 'var homePlatform by remember { mutableStateOf("wy") }' in main and re.findall(r'\bhomePlatform\s*=\s*(\w+)', main) == ['it', 'code'] and len(home_picker_callback) == 1 and session_selects_on_change_and_forces_refresh is not None and library_platform_callback is not None and 'val platforms = listOf("wy" to "网易云", "kg" to "酷狗", "kw" to "酷我", "tx" to "QQ", "mg" to "咪咕")' in home_page and platform_ids == expected_ids and filter_ids == ['', 'wy', 'tx', 'kg', 'kw', 'mg'] and 'if (source.isNotBlank()) onPlatformClick(source)' in library,
    'MusicSource adds legacy List and typed home DiscoverCard capabilities': 'import xyz.cncnlp.soundwave.DiscoverCard' in interface and 'suspend fun discoverCards(limit: Int): List<DiscoverCard> = emptyList()' in interface and 'suspend fun discoverCardsOutcome(limit: Int): FeedListOutcome<List<DiscoverCard>>' in interface,
    'platform adapter legacy discover reuses the typed outcome; direct cards are injected and the fallback is server-only': 'override suspend fun discoverCards(limit: Int): List<DiscoverCard> =' in adapter and 'discoverCardsOutcome(limit).legacyListValue()' in adapter and 'directDiscoverCards?.let { fetch ->' in adapter and 'api.discoverServerOutcome(source = platform, limit = limit)' in adapter and 'api.discoverOutcome(source = platform, limit = limit)' not in adapter,
    'manual refresh routes exact canonical p0 through Registry and keeps failures from overwriting old state': manual.count('val p0 = getHomePlatform()') == 1 and 'if (p0 in MusicSourceRegistry.platformIds)' in manual and manual.count('MusicSourceRegistry.platformSource(p0).discoverCardsOutcome(20)') == 1 and manual.count('ApiClient.discoverOutcome(p0, 20)') == 1 and 'is FeedListOutcome.Success' in manual and 'FeedListOutcome.Failure -> Unit' in manual and all(token not in manual for token in ('.lowercase()', '.uppercase()', '.trim()')),
    'concurrent discover request routes exact canonical p through typed Source/API outcomes': 'it.toHomeFeedRequestResult()' in discover_req and 'HomeFeedRequestResult.Failure' in discover_req and home_feed.count('MusicSourceRegistry.platformSource(p).discoverCardsOutcome(20)') == 1 and home_feed.count('ApiClient.discoverOutcome(p, 20)') == 1 and controller.count('ApiClient.discoverOutcome(') == 2 and all(token not in discover_req for token in ('p.lowercase()', 'p.uppercase()', 'p.trim()')),
    'the other concurrent requests keep their exact hot, rank, playlist, and typed keyword routes': 'MusicSourceRegistry.platformSource(p).hotSongs()' in hot_req and 'ApiClient.hot(p)' in hot_req and 'MusicSourceRegistry.platformSource(p).rankBoards()' in rank_req and 'ApiClient.rank(p)' in rank_req and 'PlaylistFeedRepository.fetchFeaturedResult(context, p, featuredCycle, 10, featuredTasteProfile, forceNetwork)' in playlist_req and keyword_req.count('MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)') == 1 and keyword_req.count('ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)') == 1,
    'manual and concurrent success-empty results update discover cache while failures retain the old snapshot': 'discoverMap = updateHomeFeedListCache(discoverMap, p0, outcome.toHomeFeedRequestResult())' in manual and 'discoverRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)' in home_feed and 'discoverMap = updateHomeFeedListCache(discoverMap, p, request)' in home_feed and 'finally { discoverLoading = false }' in manual,
    'ApiClient retains direct-first fallback, legacy List mapping, request failures, and server source validation': 'runDirectFallback { fetchDiscoverDirect(source.ifBlank { "wy" }, limit) }' in api_discover and 'return discoverServerOutcome(source, limit)' in api_discover and 'discoverOutcome(source, limit).legacyListValue()' in api and 'classifyListHttpResponse(code, text)' in api and 'captureListRequestOutcome {' in api and '!actualSource.equals(requested, true)' in api and 'if (id.isBlank() || name.isBlank() || !actualSource.equals(requested, true)) continue' in api,
    'direct discover still validates platform source and normalizes returned cards': 'val src = normalizeRankSource(source) ?: return emptyList()' in direct and 'normalizeDiscoverCards(cards, src, limit)' in direct,
    'matrix records the concurrent and manual HomeFeedController entries as distinct typed routes': 'refreshHomeFeed' in matrix and 'HomeFeedController.refreshDiscover()' in matrix and 'ApiClient.discoverOutcome(p, 20)' in matrix and 'ApiClient.discoverOutcome(p0, 20)' in matrix and 'platformSource(p).discoverCardsOutcome(20)' in matrix and 'platformSource(p0).discoverCardsOutcome(20)' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Home manual and concurrent discover Source Registry contract guard passed')
PY
