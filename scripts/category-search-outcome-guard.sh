#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlaylistDirectSearch.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/PlaylistCategoryDataController.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt" \
  "$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomeFeedCache.kt" \
  "$ROOT/app/src/test/java/xyz/cncnlp/soundwave/PlaylistFeedRepositoryTest.kt" \
  "$ROOT/app/src/test/java/xyz/cncnlp/soundwave/ui/home/PlaylistCategoryDataControllerTest.kt" \
  "$ROOT/app/src/test/java/xyz/cncnlp/soundwave/source/direct/PlaylistDirectSearchTest.kt" <<'PY'
import re
import sys

(
    music_source_path,
    adapter_path,
    registry_path,
    direct_path,
    api_path,
    feed_path,
    controller_path,
    home_path,
    cache_path,
    feed_test_path,
    controller_test_path,
    direct_test_path,
) = sys.argv[1:]

def read(path):
    with open(path, encoding="utf-8") as stream:
        return stream.read()

music_source = read(music_source_path)
adapter = read(adapter_path)
registry = read(registry_path)
direct = read(direct_path)
api = read(api_path)
feed = read(feed_path)
controller = read(controller_path)
home = read(home_path)
cache = read(cache_path)
feed_test = read(feed_test_path)
controller_test = read(controller_test_path)
direct_test = read(direct_test_path)

def require(condition, message):
    if not condition:
        print(f"[category-search-outcome-guard] FAIL: {message}")
        raise SystemExit(1)

require(
    "sealed interface PlaylistSearchOutcome" in music_source
    and "data class Success(val playlists: List<Playlist>, val hasMore: Boolean? = null)" in music_source
    and "object Failure : PlaylistSearchOutcome" in music_source,
    "category source contract must distinguish successful empty lists from failures",
)
require(
    "searchPlaylistsPageOutcome" in adapter
    and "directPlaylistSearchOutcome.invoke" in adapter
    and "api.searchPlaylistsCategoryOutcome" in adapter
    and "chooseCategoryPlaylistSearchResult(directEmpty, serverResult)" in adapter,
    "the category adapter must preserve direct status through server fallback",
)
require(
    "directPlaylistSearchOutcome = { keyword, page, lim -> searchPlaylistDirectOutcome" in registry,
    "the five-platform registry must inject the typed direct-search path",
)
for parser in (
    "parseWyPlaylistSquareOutcome",
    "parseWyPlaylistKeywordOutcome",
    "parseKgPlaylistSearchOutcome",
    "parseKwPlaylistSearchOutcome",
    "parseMgPlaylistSearchOutcome",
):
    require(parser in direct, f"missing typed direct parser: {parser}")
require(
    "if (result.available) PlaylistSearchOutcome.Success(result.playlists)" in direct
    and "successfulResponse" in direct,
    "QQ's existing availability status and NetEase partial-success status must be retained",
)
require(
    "searchPlaylistsCategoryOutcome" in api
    and "parseCategoryPlaylistPayload" in api
    and "if (!hasPlaylistArray) PlaylistSearchOutcome.Failure" in api,
    "service fallback must validate an expected playlist-array envelope",
)
require(
    "resolveCategoryPlaylistRefresh" in feed
    and "cacheSnapshot = fetched" in feed
    and "cacheSnapshot = null" in feed,
    "only success may supply a cache snapshot; failures must leave it absent",
)
require(
    "fetchPage(platform, category, 1, CATEGORY_PAGE_SIZE)" in controller
    and "refresh.cacheSnapshot?.let" in controller
    and "categoryController.refreshCategoryPlaylists(" in home
    and "LaunchedEffect(platform, group, cat, allPls, catRetryNonce)" in home
    and "catRetryNonce += 1" in home
    and "分类加载失败，点击重试第 ${catPage} 页" in home,
    "HomePage must avoid failure caching and expose same-page retry",
)
require(
    "PLAYLIST_CATALOG_MS = 20L * 60L * 1000L" in cache,
    "successful empty category snapshots must retain the existing 20-minute TTL",
)
require(
    "categoryRefreshCachesSuccessfulEmptyButLeavesFailedRequestUncachedAndRetryable" in feed_test
    and "categoryServerPayloadDistinguishesValidEmptyFromMalformedOrExplicitFailure" in feed_test,
    "category cache/outcome regression tests are missing",
)
require(
    "categoryFallbackKeepsOnlyAnAuthoritativeDirectEmptyWhenServerAlsoFails" in feed_test,
    "direct/service fallback failure-versus-valid-empty regression test is missing",
)
require(
    "successfulEmptyCategoryResultIsNegativeCachedAndKeysStayIsolated" in controller_test
    and "firstPageFailureFallsBackToCachedResultsThenMatchingHomeTitlesWithoutCachingFailure" in controller_test,
    "category controller cache-key and failure-fallback integration tests are missing",
)
require(
    "category direct parsers distinguish valid empty provider lists from malformed responses" in direct_test,
    "direct provider empty-versus-malformed parser regression test is missing",
)

print("[category-search-outcome-guard] PASS: typed direct/service outcomes, success-empty caching, no failure cache writes, 20-minute TTL, and same-page retry are wired and tested")
PY
