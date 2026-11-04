#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_PAGE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt"
CONTROLLER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/PlaylistCategoryDataController.kt"
REPOSITORY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlaylistFeedRepository.kt"
REPOSITORY_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/PlaylistFeedRepositoryTest.kt"
CONTROLLER_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/ui/home/PlaylistCategoryDataControllerTest.kt"
python3 - "$HOME_PAGE" "$CONTROLLER" "$REPOSITORY" "$REPOSITORY_TEST" "$CONTROLLER_TEST" <<'PY'
import re
import sys

source, controller, repository, repository_test, controller_test = [
    open(path, encoding="utf-8").read() for path in sys.argv[1:]
]

def fail(message):
    print(f"[home-pagination-guard] FAIL: {message}")
    raise SystemExit(1)

# Keep presentation in HomePage while routing persistence and request policy through the data controller.
if not re.search(r'var\s+catPage\s+by\s+remember\(platform,\s*group,\s*cat\)\s*\{\s*mutableStateOf\(1\)\s*\}', source):
    fail("category page state is no longer scoped/reset for platform, group and category")
effect = re.search(r'LaunchedEffect\(platform,\s*group,\s*cat,\s*allPls,\s*catRetryNonce\)\s*\{(?P<body>.*?)\n\s*\}\n\s*fun\s+loadMoreCategoryPlaylists', source, re.S)
if effect is None:
    fail("category refresh effect or load-more boundary was not found")
refresh = effect.group("body")
if not re.search(r'catPage\s*=\s*1', refresh):
    fail("a category refresh must reset the current page to 1")
if not re.search(r'categoryController\.refreshCategoryPlaylists\(', refresh):
    fail("the category refresh effect must delegate data coordination to the controller")
if not re.search(r'fetchPage\(platform,\s*category,\s*1,\s*CATEGORY_PAGE_SIZE\)', controller):
    fail("the controller must request page 1 for the initial category fetch")
if not re.search(r'finally\s*\{\s*catLoading\s*=\s*false\s*\}', refresh):
    fail("initial category loading state must be released on completion or failure")

load_match = re.search(r'fun\s+loadMoreCategoryPlaylists\(\)\s*\{(?P<body>.*?)\n\s*\}\n\s*val\s+displayPls', source, re.S)
if load_match is None:
    fail("load-more function was not found")
body = load_match.group("body")
checks = [
    (r'if\s*\(showAllCategory\s*\|\|\s*catLoading\s*\|\|\s*catLoadingMore\s*\|\|\s*!catHasMore\)\s*return', "must reject full-list, busy, duplicate and exhausted requests"),
    (r'catLoadingMore\s*=\s*true', "must acquire the load-more lock before launching a request"),
    (r'categoryController\.loadMoreCategoryPlaylists\(', "HomePage must delegate next-page coordination to the controller"),
    (r'catPls\s*=\s*completed\.playlists', "must apply the controller's playlist snapshot"),
    (r'catPage\s*=\s*completed\.page', "must apply the controller's retry cursor"),
    (r'catHasMore\s*=\s*completed\.hasMore', "must apply the controller's has-more state"),
    (r'catLoadingMore\s*=\s*completed\.loadingMore', "must apply the controller's loading state"),
    (r'finally\s*\{\s*catLoadingMore\s*=\s*false\s*\}', "must release the loading lock on every completion path"),
]
for pattern, message in checks:
    if not re.search(pattern, body, re.S):
        fail(message)
if not re.search(r'fetchPage\(platform,\s*category,\s*nextPage,\s*CATEGORY_PAGE_SIZE\)', controller):
    fail("the controller must pass the computed next page and page size to the source")
if not re.search(r'val\s+nextPage\s*=\s*current\.page\s*\+\s*1', controller):
    fail("the controller must compute the next page monotonically")
if not re.search(r'finishCategoryPlaylistPageLoad\(current,\s*incoming,\s*platform,\s*nextPage\)', controller):
    fail("successful pages must use the existing pure merge/state policy")
if not re.search(r'finishCategoryPlaylistPageLoad\(current,\s*null,\s*platform,\s*nextPage\)', controller):
    fail("failed or empty pages must keep the same retry cursor through the existing policy")
helper_match = re.search(
    r'internal fun finishCategoryPlaylistPageLoad\(.*?\)\s*:\s*CategoryPlaylistPageState\s*\{(?P<body>.*?)\n\}',
    repository,
    re.S,
)
if helper_match is None:
    fail("the existing pure category page completion helper was not found")
helper = helper_match.group("body")
if not re.search(r'if\s*\(incoming\.isNullOrEmpty\(\)\)\s*return current\.copy\(loadingMore\s*=\s*false\)', helper):
    fail("an empty or failed page must preserve the current snapshot and release loading")
if not re.search(r'mergeCategoryPlaylistPages\(current\.playlists,\s*incoming,\s*platform\)', helper):
    fail("non-empty pages must retain provider-aware merge/de-duplication")
if not re.search(r'page\s*=\s*nextPage', helper) or not re.search(r'hasMore\s*=\s*incoming\.isNotEmpty\(\)', helper):
    fail("non-empty provider pages must advance the cursor even when merge de-duplicates their contents")
if not re.search(r'loadingMore\s*=\s*false', helper):
    fail("the pure completion helper must release loading on every outcome")
for test_name in (
    "emptyOrFailedCategoryPagePreservesRetryStateAndReleasesLoading",
    "nonEmptyDuplicateCategoryPageKeepsNextPageAvailable",
):
    if not re.search(rf'fun\s+{test_name}\(\)', repository_test):
        fail(f"the existing repository regression test is missing: {test_name}")
for test_name in (
    "emptyAndFailedPagesRetrySamePageWhileDuplicateNonEmptyPageAdvances",
    "successfulEmptyCategoryResultIsNegativeCachedAndKeysStayIsolated",
):
    if not re.search(rf'fun\s+{test_name}\(\)', controller_test):
        fail(f"the controller integration regression test is missing: {test_name}")
if not re.search(r'if\s*\(!showAllCategory\s*&&\s*catHasMore\s*&&\s*displayPls\.isNotEmpty\(\)\)', source):
    fail("load-more row must only appear for a non-empty paged category with more data")
if not re.search(r'\.clickable\(enabled\s*=\s*!catLoadingMore\s*&&\s*!catLoading\)', source):
    fail("the load-more control must disable taps while either category request is active")
if '"加载更多歌单 · 已显示 ${displayPls.size} 个"' not in source or '"正在加载更多歌单…"' not in source:
    fail("load-more row must retain its count and in-progress feedback")

print("[home-pagination-guard] PASS: controller boundary, category reset, sequential request, retryable empty/failure pages, duplicate-page progress, merge/de-duplication and loading feedback are present")
PY
