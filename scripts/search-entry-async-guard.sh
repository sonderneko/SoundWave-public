#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
search = (base / 'SearchController.kt').read_text()
controller = (base / 'HomeFeedController.kt').read_text()
home_page = (base / 'HomePage.kt').read_text()
session = (base / 'ui/home/HomeFeedSession.kt').read_text()
extra = (base / 'ExtraPages.kt').read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[search-entry-async-guard] FAIL: {message}')


# --- Search entry (the reported ~2s freeze): stats gathering + profile build on the IO boundary ---
refresh = search[search.index('fun refreshPersonalRecommendations'):search.index('fun runSearch')]
io_at = refresh.find('withContext(Dispatchers.IO)')
require(io_at >= 0, 'search recommendation refresh must gather on the IO dispatcher')
require(refresh.find('.topArtists(20)') > io_at, 'topArtists must run inside the IO boundary, not on the calling thread')
require(refresh.find('.topSongs(60)') > io_at, 'topSongs must run inside the IO boundary, not on the calling thread')
require(refresh.find('PersonalRecommendations.buildProfile(') > io_at, 'buildProfile must run inside the IO boundary, not on the calling thread')
require(refresh.find('ListenStats.localDailyListenStats(') > io_at, 'daily listen stats projection must run inside the IO boundary')
require('withContext(Dispatchers.IO) { try { HomeFeedCache.loadSearch(' in search,
        'search cache reads inside runSearch must stay on the IO boundary')
require('withContext(Dispatchers.IO) { try { HomeFeedCache.saveSearch(' in search,
        'search cache writes inside runSearch must stay on the IO boundary')

# --- Home feed refresh: the taste-profile provider must not run synchronously on the caller thread ---
feed = controller[controller.index('fun refreshHomeFeed(forceNetwork: Boolean = false)'):]
launch_at = feed.find('refreshJob = scope.launch')
provider_at = feed.find('playlistTasteProfileProvider()')
require(launch_at >= 0 and provider_at >= 0, 'refreshHomeFeed must keep its refresh job and taste profile provider')
require(provider_at > launch_at, 'playlistTasteProfileProvider must be invoked after the refresh job launches, not in the synchronous prologue')
require('val playlistReq = async(Dispatchers.IO)' in feed and provider_at > feed.index('val playlistReq = async(Dispatchers.IO)'),
        'playlistTasteProfileProvider must be invoked inside the playlist IO async block')

# --- Home page: no synchronous stats/profile remember blocks in composition ---
require('val listenedArtists = remember(' not in home_page, 'HomePage must not gather topArtists synchronously in a remember block')
require('val listenedSongs = remember(' not in home_page, 'HomePage must not gather topSongs synchronously in a remember block')
require('val tasteProfile = recommendationProfile ?: remember(' not in home_page,
        'HomePage must not build the taste profile synchronously in a remember block')
require('homeStatsProfile' in home_page and 'withContext(Dispatchers.IO)' in home_page,
        'HomePage must fill its stats/profile snapshot from the IO boundary')

# --- Recommendation library (root composition): stats inputs come from an async snapshot ---
lib_block_start = session.index('return remember(recommendationOwner')
lib_block = session[lib_block_start:session.index('\n}\n', lib_block_start)]
require('ContextData(' not in lib_block,
        'rememberRecommendationLibrary must not gather stats synchronously in its remember block')
require('ListenStats.localDailyListenStats(' not in lib_block,
        'rememberRecommendationLibrary must not project daily stats synchronously in its remember block')
require('RecommendationStatsSnapshot' in session and 'recommendationStats' in session,
        'rememberRecommendationLibrary must consume the async stats snapshot')

# --- Listen stats page: whole-page stats come from one async snapshot, not composition-body reads ---
stats_page = extra[extra.index('fun ListenStatsPage'):]
require('produceState<ListenStatsPageSnapshot?>' in stats_page,
        'ListenStatsPage must load its stats through the async snapshot')
require('val monthTotalSec = statsSnapshot?.monthTotalSec' in stats_page,
        'ListenStatsPage must read values from the snapshot, not synchronous ContextData calls')
require('val today = xyz.cncnlp.soundwave.ContextData' not in stats_page,
        'ListenStatsPage composition body must not call ContextData synchronously')

print('[search-entry-async-guard] PASS: search entry, home feed, home page, recommendation library and stats page keep full stats/profile work on the IO boundary')
PY
