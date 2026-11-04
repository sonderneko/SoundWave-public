#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
holder = (base / "SchemeActionHolder.kt").read_text(encoding="utf-8")
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
route = (base / "AppRoute.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/SchemeActionHolderTest.kt").read_text(encoding="utf-8")
route_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/AppRouteBackActionTest.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[scheme-action-delivery-guard] FAIL: {message}")
require("Channel<SchemeActionHolder.Action>(Channel.CONFLATED)" in holder,
        "pending actions must use a single-slot last-submitted-wins channel")
require("channel.receiveAsFlow()" in holder and "channel.trySend(action)" in holder,
        "submitted actions must be consumable by an active collector and remain buffered before collection")
require("if (action != SchemeActionHolder.Action.None)" in holder,
        "invalid/empty URI actions must remain ignored")
require("SchemeActionHolder.actions.collect { action ->" in main,
        "AppRoot must own the single continuous deep-link collector")
for fragment in (
    "is SchemeActionHolder.Action.Play ->",
    "Song(",
    "id = action.songId",
    'name = action.name.ifBlank { "未知歌曲" }',
    "singer = action.singer",
    "source = action.source",
    "is SchemeActionHolder.Action.Search ->",
    'runSearch(action.keyword, "all", "song")',
    "SchemeActionHolder.Action.None -> Unit",
):
    require(fragment in main, f"existing deep-link route behavior must remain: {fragment}")
play_route = main.split("is SchemeActionHolder.Action.Play ->", 1)[1].split("is SchemeActionHolder.Action.Search ->", 1)[0]
require("val entry = playerEntryState(tab, prevTab)" in play_route and "prevTab = entry.previousTab" in play_route,
        "Play deep links must use AppRoute's player-entry return-target decision")
search_route = main.split("is SchemeActionHolder.Action.Search ->", 1)[1].split("SchemeActionHolder.Action.None -> Unit", 1)[0]
require("val entry = AppRoute.searchEntryDecision(tab, prevTab, lastContentTab)" in search_route and
        "prevTab = entry.previousTab" in search_route and "lastContentTab = entry.lastContentTab" in search_route,
        "Search deep links must use AppRoute's normalized return-target decision")
for fragment in (
    "internal fun searchEntryDecision(",
    "previousTab.takeIf(::isSearchReturnTab)",
    "lastContentTab.takeIf(::isSearchReturnTab)",
    "return SearchEntryDecision(previousTab = returnTab, lastContentTab = returnTab)",
):
    require(fragment in route, f"Search deep-link return-target routing must remain normalized: {fragment}")
for fragment in (
    "action submitted before collection is delivered once without replay",
    "action submitted after collection starts is delivered immediately",
    "unconsumed actions use last submitted wins semantics",
):
    require(fragment in test, f"deterministic JVM regression coverage is missing: {fragment}")
for fragment in (
    "fun deepLinkEntryFromNonPlayerTabRecordsTheCurrentTab",
    "fun playDeepLinkWhileInPlayerPreservesOriginForClosePlayer",
    "fun playerEntryFallsBackToHomeForPlayerAsInvalidSavedOrigin",
    "fun searchDeepLinkFromPlayerPreservesPlaylistForBothReturnFields",
    "fun searchDeepLinkFromPlayerUsesLastContentWhenPreviousTabIsTransient",
    "fun searchDeepLinkFromPlayerFallsBackHomeWhenBothReturnFieldsAreTransient",
    "fun searchDeepLinkFromHomeAndSettingsRetainsExistingEntryBehavior",
    "fun searchDeepLinkAlreadyOnSearchRetainsExistingLastContentBehavior",
    "fun playerSearchDeepLinkClearsResultsBeforeReturningToNormalizedTarget",
):
    require(fragment in route_test, f"player-entry return-target JVM regression is missing: {fragment}")
require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675")
require("scripts/scheme-action-delivery-guard.sh" in workflow,
        "the deep-link delivery guard must run in portable quality gates")
print("[scheme-action-delivery-guard] PASS: cold/hot delivery, player return-target routing, preserved routes, JVM regressions, and 1.0.675")
PY
