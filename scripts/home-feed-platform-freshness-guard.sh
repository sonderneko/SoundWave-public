#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
cache = (base / 'HomeFeedCache.kt').read_text()
controller = (base / 'HomeFeedController.kt').read_text()
models = (base / 'Models.kt').read_text()
build = (root / 'app/build.gradle').read_text()
version = (root / 'VERSION').read_text().strip()
workflow = (root / '.github/workflows/build-apk.yml').read_text()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[home-feed-platform-freshness-guard] FAIL: {message}')

fresh_start = cache.find('fun isResourceFresh(resource: Resource, platform: String')
fresh_end = cache.find('fun isPlatformFeedStale', fresh_start)
freshness = cache[fresh_start:fresh_end] if fresh_start >= 0 and fresh_end >= 0 else ''
seed_start = controller.find('private fun seedContentFromCache()')
seed_end = controller.find('private val mutableHotSongsState', seed_start)
seed = controller[seed_start:seed_end] if seed_start >= 0 and seed_end >= 0 else ''
keyword_start = controller.find('internal fun hasUsableHomeKeywordSnapshot(')
keyword_end = controller.find('internal fun shouldShortCircuitHomeFeed(', keyword_start)
keyword_check = controller[keyword_start:keyword_end] if keyword_start >= 0 and keyword_end >= 0 else ''
featured_start = controller.find('internal fun hasCurrentFeaturedPlaylistSnapshot(')
featured = controller[featured_start:] if featured_start >= 0 else ''

required_resource_fields = ('hotSongsFetchedAt', 'rankBoardsFetchedAt', 'featuredPlaylistFetchedAt', 'hotKeywordsFetchedAt')
require(all(field in models and field in freshness for field in required_resource_fields), 'each resource must use its platform FeedSourceState timestamp')
require('sourceStates[platform]' in freshness and 'savedAt' not in freshness, 'resource freshness must not use the whole-snapshot ts')
require('isTimestampFresh(fetchedAt, ttl, now)' in freshness, 'resource TTL boundaries must share the strict timestamp helper')
require(all(f'{field} = state.optLong("{field}", 0L)' in cache for field in required_resource_fields), 'missing legacy resource timestamps must decode as stale zero values')
require(all(f'put("{field}", state.{field})' in cache for field in required_resource_fields), 'all resource timestamps must be persisted in the existing state object')

require(all(f'snap.isResourceFresh(HomeFeedCache.Resource.{resource}, p, now)' in seed for resource in ('HOT', 'RANK', 'PLAYLIST')), 'cache seed status must use the seeded platform and resource TTL')
require('LoadableContent.Available(hot, hotFetchedAt)' in seed and 'LoadableContent.Stale(hot, hotFetchedAt' in seed, 'hot seed status must report its successful resource time')
require('LoadableContent.Available(rank, rankFetchedAt)' in seed and 'LoadableContent.Stale(rank, rankFetchedAt' in seed, 'rank seed status must report its successful resource time')
require('LoadableContent.Available(pl, playlistFetchedAt)' in seed and 'LoadableContent.Stale(pl, playlistFetchedAt' in seed, 'playlist seed status must report its successful resource time')
require('snap.isPlatformFeedStale(getHomePlatform())' in controller, 'aggregate stale state must follow the active platform')

require('snapshot.sourceStates[platform]?.hotKeywordsFetchedAt ?: 0L' in controller, 'keyword short-circuit must use that platform timestamp')
require('HomeFeedCache.isTimestampFresh(hotKeywordsFetchedAt, HomeFeedCache.ResourceTtl.HOT_KEYWORDS_MS, now)' in keyword_check, 'a 20-keyword count or an empty result must not bypass keyword TTL')
require('return cached.isEmpty() || cached.size >= HOT_WORDS_FETCH_LIMIT' in keyword_check, 'keyword completeness policy must remain empty-or-full after freshness passes')
require('!HomeFeedCache.isTimestampFresh(featuredPlaylistFetchedAt, HomeFeedCache.ResourceTtl.PLAYLIST_CATALOG_MS, now)' in featured, 'both empty and non-empty featured lists must pass TTL before they can count')
require('return playlists.isEmpty() || hasUsableFeaturedPlaylist(platform, playlists)' in featured, 'featured cache identity rules and valid-empty negative caching must remain')
short_start = controller.find('internal fun shouldShortCircuit(')
short_end = controller.find('\n/** The platform-keyed cache', short_start)
short = controller[short_start:short_end] if short_start >= 0 and short_end >= 0 else ''
require('!forceNetwork' in short, 'forced platform-switch/manual refresh must continue to bypass short-circuiting')
require('shouldShortCircuitHomeFeed(snap, p, forceNetwork)' in controller, 'production refresh must use the tested platform-scoped short-circuit policy')

for field, succeeded in (
    ('hotSongsFetchedAt', '!hotRequestFailed'),
    ('rankBoardsFetchedAt', '!rankRequestFailed'),
    ('featuredPlaylistFetchedAt', 'featuredPlaylistUpdated'),
    ('hotKeywordsFetchedAt', '!keywordRequestFailed'),
):
    require(f'{field} = successfulTimestampAfterRequest(previousState?.{field} ?: 0L, {succeeded}, fetchedNow)' in controller,
            f'{field} must advance only for a successful resource response, including the established empty-result policy')
require('successfulTimestampAfterRequest(lastFullySuccessfulAt, failedResources.isEmpty(), fetchedNow)' in controller,
        'refreshedAt must retain its all-resources-success meaning')
require('val lastFullySuccessfulAt = feedSourceStates[p]?.refreshedAt ?: 0L' in controller,
        'full-success time must not fall back to the whole snapshot save time')

require(version == '1.0.675' and 'versionName "1.0.675"' in build and re.search(r'versionCode\s+675\b', build),
        'VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675')
require('scripts/home-feed-platform-freshness-guard.sh' in workflow, 'the dedicated guard must run in portable quality gates')
print('[home-feed-platform-freshness-guard] PASS: platform/resource TTL, partial-success timestamps, legacy JSON staleness, keyword/playlist negative-cache expiry, force refresh and 1.0.675 are synchronized')
PY
