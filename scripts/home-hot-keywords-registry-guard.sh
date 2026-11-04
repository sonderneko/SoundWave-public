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
interface = (base / 'source/MusicSource.kt').read_text()
adapter = (base / 'source/PlatformSourceAdapter.kt').read_text()
registry = (base / 'source/MusicSourceRegistry.kt').read_text()
api = (base / 'ApiClient.kt').read_text()
matrix = (root / 'docs/source-capability-matrix.md').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()

start = controller.find('val keywordReq = async(Dispatchers.IO)')
end = controller.find('\n\n                var h = emptyList<Song>()', start)
keyword_req = controller[start:end] if start >= 0 and end >= 0 else ''
required_request_tokens = [
    'val keywordReq = async(Dispatchers.IO) {',
    'runCatching {',
    'if (p in MusicSourceRegistry.platformIds) {',
    'MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)',
    'ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)',
    'it.toHomeFeedRequestResult()',
    'HomeFeedRequestResult.Failure',
]
id_match = re.search(r'val platformIds: List<String> = listOf\(([^)]*)\)', registry)
platform_ids = re.findall(r'"([^"]+)"', id_match.group(1)) if id_match else []
api_start = api.find('suspend fun hotKeywords(source: String, limit: Int = 9)')
api_end = api.find('internal fun hotKeywordsServerOutcome', api_start)
api_hot_keywords = api[api_start:api_end] if api_start >= 0 and api_end >= 0 else ''

checks = {
    'home platform is retained raw and Registry uses exactly the five canonical IDs': 'val p = getHomePlatform()' in controller and platform_ids == ['wy', 'kg', 'kw', 'tx', 'mg'] and all(token not in keyword_req for token in ('p.lowercase()', 'p.uppercase()', 'p.trim()')),
    'only keywordReq routes exact canonical p through Registry and preserves ApiClient fallback': all(token in keyword_req for token in required_request_tokens) and keyword_req.index('MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)') < keyword_req.index('ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)') and controller.count('MusicSourceRegistry.platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)') == 1 and controller.count('ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)') == 1,
    'MusicSource declares an unsupported-safe List<String> hotKeywords(limit) capability': 'suspend fun hotKeywords(limit: Int): List<String> = emptyList()' in interface,
    'PlatformSourceAdapter legacy call reuses the typed outcome; direct hot words are injected and the fallback is server-only': adapter.count('override suspend fun hotKeywords(limit: Int): List<String> =') == 1 and 'hotKeywordsOutcome(limit).legacyListValue()' in adapter and 'directHotKeywords?.let { fetch ->' in adapter and 'api.hotKeywordsServerOutcome(source = platform, limit = limit)' in adapter and 'api.hotKeywordsOutcome(source = platform, limit = limit)' not in adapter and 'fetchHotWordsDirect' not in adapter and 'suspend fun hotKeywordsOutcome(limit: Int): FeedListOutcome<List<String>>' in interface,
    'ApiClient keeps direct-first fallback, old List mapping, HTTP classification, and thrown-request failure': 'runDirectFallback { fetchHotWordsDirect(source.ifBlank { "wy" }, limit) }' in api_hot_keywords and '?.takeIf { it.isNotEmpty() }' in api_hot_keywords and 'return hotKeywordsServerOutcome(source, limit)' in api_hot_keywords and 'hotKeywordsOutcome(source, limit).legacyListValue()' in api and '/music/hotword?source=$api&limit=$limit' in api and 'classifyListHttpResponse(code, text)' in api and 'captureListRequestOutcome {' in api,
    'limit, platform-scoped timestamp, successful-empty TTL snapshot, and failure status are retained': 'private const val HOT_WORDS_FETCH_LIMIT = 20' in controller and 'snapshot.sourceStates[platform]?.hotKeywordsFetchedAt ?: 0L' in controller and 'hasUsableHomeKeywordSnapshot(' in controller and 'kws = if (resolved.failed) resolved.value else received.ifEmpty { previous }' in controller and 'keywordRequestFailed = isHomeFeedListRequestFailed(request, emptyIsFailure = false)' in controller and 'hotKeywords = updateHomeFeedListCache(hotKeywords, p, request)' in controller and 'HOT_KEYWORDS_MS' in (base / 'HomeFeedCache.kt').read_text(),
    'MainActivity and search do not acquire a new hotKeywords Registry route': 'MusicSourceRegistry.platformSource(p).hotKeywords' not in main,
    'matrix records the concurrent typed keyword route and preserves ordinary List/MainActivity callers': 'platformSource(p).hotKeywordsOutcome(HOT_WORDS_FETCH_LIMIT)' in matrix and 'ApiClient.hotKeywordsOutcome(p, HOT_WORDS_FETCH_LIMIT)' in matrix and '`hotKeywords`' in matrix and '`MainActivity`/search 热词入口仍不变' in matrix,
    'version declarations remain synchronized': ('versionName "' + version + '"' in build) and ('versionCode ' + version.rsplit('.', 1)[1] in build),
}
for label, passed in checks.items():
    if not passed:
        raise SystemExit(f'FAIL: {label}')
print('Home concurrent hot-keywords Registry contract guard passed')
PY
