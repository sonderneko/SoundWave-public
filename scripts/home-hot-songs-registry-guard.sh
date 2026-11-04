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
api = (base / 'ApiClient.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start = controller.find('val hotReq = async(Dispatchers.IO)')
end = controller.find('val rankReq = async(Dispatchers.IO)', start)
if start < 0 or end < 0:
    raise SystemExit('FAIL: refreshHomeFeed hot request boundary is missing')
hot_req = controller[start:end]
normalized = [line.strip() for line in hot_req.splitlines()]
expected_route = [
    'if (p in MusicSourceRegistry.platformIds) {',
    'MusicSourceRegistry.platformSource(p).hotSongs()',
    '} else {',
    'ApiClient.hot(p)',
    '}',
]
route_at = next((i for i in range(len(normalized) - len(expected_route) + 1)
                 if normalized[i:i + len(expected_route)] == expected_route), -1)
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

checks = {
    'home platform starts at wy; home picker delegates the selected value and only changed selections force-refresh': 'val p = getHomePlatform()' in controller and 'var homePlatform by remember { mutableStateOf("wy") }' in main and re.findall(r'\bhomePlatform\s*=\s*(\w+)', main) == ['it', 'code'] and len(home_picker_callback) == 1 and session_selects_on_change_and_forces_refresh is not None and library_platform_callback is not None and all(token not in controller for token in ('p.lowercase()', 'p.uppercase()', 'p.trim()')),
    'home picker offers only the five canonical platform IDs': 'val platforms = listOf("wy" to "网易云", "kg" to "酷狗", "kw" to "酷我", "tx" to "QQ", "mg" to "咪咕")' in home_page,
    'library picker has only the five canonical IDs and does not write its blank all-option': filter_ids == ['', 'wy', 'tx', 'kg', 'kw', 'mg'] and 'if (source.isNotBlank()) onPlatformClick(source)' in library,
    'MusicSource exposes an unsupported-safe hotSongs capability': 'suspend fun hotSongs(): List<Song> = emptyList()' in interface,
    'platform adapter tries the injected direct hot provider first and falls back only to the server-only catalog port': 'directHotSongs?.let { fetch ->' in adapter and 'api.hotServer(source = platform)' in adapter and 'api.hot(source = platform)' not in adapter,
    'only exact canonical IDs use Registry and all other p values keep ApiClient.hot(p)': route_at >= 0 and hot_req.count('MusicSourceRegistry.platformSource(p).hotSongs()') == 1 and hot_req.count('ApiClient.hot(p)') == 1,
    'ApiClient hot direct-first and original hotServer fallback remain': 'runDirectFallback { fetchHotDirect(source) }' in api and 'return hotServer(source)' in api,
    'concurrent discover and exact-canonical keyword use typed Registry outcomes while rank and playlist stay unchanged': controller.count('MusicSourceRegistry.platformSource(p).discoverCardsOutcome(20)') == 1 and controller.count('ApiClient.discoverOutcome(p, 20)') == 1 and controller.count('MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)') == 1 and controller.count('ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)') == 1 and 'if (p in MusicSourceRegistry.platformIds)' in controller and 'MusicSourceRegistry.platformSource(p).rankBoards()' in controller and 'ApiClient.rank(p)' in controller and 'PlaylistFeedRepository.fetchFeaturedResult(context, p, featuredCycle, 10, featuredTasteProfile, forceNetwork)' in controller,
    'hot result failures and the stale fallback remain explicit': 'hotRequestFailed = resolved.failed || received.isEmpty()' in controller and 'h = if (resolved.failed) resolved.value else received.ifEmpty { previous }' in controller,
    'keyword and discover success-empty results cache while typed failures enter failed-resource status': 'keywordRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)' in controller and 'discoverRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)' in controller and 'hotKeywords = updateHomeFeedListCache(hotKeywords, p, request)' in controller and 'discoverMap = updateHomeFeedListCache(discoverMap, p, request)' in controller and 'if (keywordRequestFailed) add("热词")' in controller and 'if (discoverRequestFailed) add("发现")' in controller,
    'matrix distinguishes typed discover entry points and documents concurrent keyword routing': 'HomeFeedController.refreshDiscover' in matrix and 'refreshHomeFeed' in matrix and 'ApiClient.discoverOutcome(p0, 20)' in matrix and 'ApiClient.discoverOutcome(p, 20)' in matrix and 'platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)' in matrix and 'ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Home hot-songs Registry contract guard passed')
PY
