#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
source_root = root / "app/src/main/java/xyz/cncnlp/soundwave"
full = (source_root / "FullPlayer.kt").read_text(encoding="utf-8")
coordinator_path = source_root / "ui/player/FullPlayerPlaybackCoordinator.kt"
coordinator = coordinator_path.read_text(encoding="utf-8") if coordinator_path.is_file() else ""
lyrics_route_path = source_root / "ui/player/PlayerLyricsRoute.kt"
lyrics_route = lyrics_route_path.read_text(encoding="utf-8") if lyrics_route_path.is_file() else ""
lyric_row_path = source_root / "ui/player/PlayerLyricRow.kt"
lyric_row = lyric_row_path.read_text(encoding="utf-8") if lyric_row_path.is_file() else ""
lyrics_viewport_path = source_root / "ui/player/FullPlayerLyricsViewport.kt"
lyrics_viewport = lyrics_viewport_path.read_text(encoding="utf-8") if lyrics_viewport_path.is_file() else ""


def fail(message: str) -> None:
    print(f"[player-mode-coordinator-guard] FAIL: {message}")
    raise SystemExit(1)


def normalize(source: str) -> str:
    return re.sub(r"\s+", " ", source).strip()


def call_arguments(source: str, name: str) -> str:
    call = re.search(rf"\b{re.escape(name)}\s*\(", source)
    if not call:
        fail(f"the current lyric callback call is missing: {name}(")
    opening = source.find("(", call.start())
    depth = 0
    quote = None
    escaped = False
    line_comment = False
    block_comment = False
    index = opening
    while index < len(source):
        char = source[index]
        pair = source[index:index + 2]
        if line_comment:
            if char == "\n":
                line_comment = False
        elif block_comment:
            if pair == "*/":
                block_comment = False
                index += 1
        elif quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif pair == "//":
            line_comment = True
            index += 1
        elif pair == "/*":
            block_comment = True
            index += 1
        elif char in ("\"", "'"):
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
        index += 1
    fail(f"the current lyric callback call is unterminated: {name}(")


if not coordinator:
    fail("the extracted playback-mode coordinator is missing")
if not lyrics_route:
    fail("the extracted lyric viewport route is missing")
if not lyric_row:
    fail("the shared lyric row is missing")
if not lyrics_viewport:
    fail("the stateless lyric viewport renderer is missing")
if "internal class FullPlayerPlaybackCoordinator(" not in coordinator:
    fail("the playback-mode coordinator must remain an internal boundary")
if "remember(" in coordinator or "mutableStateOf(" in coordinator or "mutableIntStateOf(" in coordinator:
    fail("the coordinator must not create or own Compose state")
for state_name in (
    "chromeVisibleState",
    "immersiveModeState",
    "interactionTickState",
    "midPanelState",
    "queueReturnImmersiveState",
    "queueReturnShowLyricsState",
    "queueReturnForcedDismissedState",
    "forcedLyricsDismissedState",
):
    if f"val {state_name} = remember" not in full:
        fail(f"FullPlayer must remain the remember/state owner for {state_name}")
for method in (
    "fun returnToPreviousPlayerMode()",
    "fun openLyricsImmersive()",
    "fun toggleCoverLyricsMode()",
    "fun openQueueInLyrics()",
):
    if method not in coordinator:
        fail(f"the route coordinator is missing {method}")
for token in (
    "delay(280)",
    "queueVisibilityState.targetState = false",
    "queueVisibilityState.targetState = true",
    "playerChromeVisibleAfterLyricsEntry(layout, playing)",
    "if (lyricsStyleLayout && showLyrics != queueReturnShowLyricsState.value)",
    "if (layoutForcesLyrics) forcedLyricsDismissedState.value = true",
    "onToggleLyrics()",
):
    if token not in coordinator:
        fail(f"the existing route transition contract is missing: {token}")
if "val playbackCoordinator = FullPlayerPlaybackCoordinator(" not in full:
    fail("FullPlayer must wire the coordinator to its own state holders")
for call in (
    "fun returnToPreviousPlayerMode(): Boolean = playbackCoordinator.returnToPreviousPlayerMode()",
    "fun openLyricsImmersive() = playbackCoordinator.openLyricsImmersive()",
    "fun toggleCoverLyricsMode() = playbackCoordinator.toggleCoverLyricsMode()",
    "fun openQueueInLyrics() = playbackCoordinator.openQueueInLyrics()",
):
    if call not in full:
        fail(f"FullPlayer must preserve its existing route call boundary: {call}")
if full.count("fun FullPlayer(") != 1:
    fail("the public FullPlayer entry point must remain singular")
# r672: every style's queue is the shared bottom sheet (DefaultPlayerQueuePage); the Apple-only queue route was removed.
for component in ("DefaultPlayerQueuePage(", "PlayerLyricsRoute(", "PlayerCoverArtworkCard("):
    if component not in full:
        fail(f"the existing presentation split must remain wired: {component}")
if "PlayerLyricsViewport(" in full:
    fail("FullPlayer must call the state-owning lyric route, not the lower-level viewport renderer")
if not re.search(r"@Composable\s+internal fun PlayerLyricsRoute\s*\(", lyrics_route):
    fail("PlayerLyricsRoute must remain an internal Compose boundary")
for token in (
    "rememberLazyListState(initialFirstVisibleItemIndex = initialLyricIndex)",
    "var viewportPx by remember { mutableIntStateOf(0) }",
    "var activeItemPx by remember { mutableIntStateOf(0) }",
    "lastLyricScrollRouteKey by remember",
    "LaunchedEffect(",
    "PlayerLyricsViewport(",
    "playerLyricsActiveScrollOffsetPx(viewportPx, activeItemPx, activeIndex)",
):
    if token not in lyrics_route:
        fail(f"the lyric route no longer owns/preserves its viewport contract: {token}")

route_viewport_args = call_arguments(lyrics_route, "PlayerLyricsViewport")
size_callback = re.search(
    r"\bonItemSizeChanged\s*=\s*\{\s*index\s*,\s*sizePx\s*->(?P<body>.*?)\}",
    route_viewport_args,
    re.S,
)
if not size_callback:
    fail("the route must receive each lyric row's index and measured pixel height")
if normalize(size_callback.group("body")) != (
    "if (index == activeIndex && activeItemPx != sizePx) activeItemPx = sizePx "
    "if (index == selectedIndex && selectedItemPx != sizePx) selectedItemPx = sizePx"
):
    fail("only the matching active index may update activeItemPx; selected-row measurement must remain index-scoped")

row_modifier = re.search(
    r"\bval\s+lineModifier\s*=\s*Modifier(?P<body>.*?)\.then\s*\(",
    lyric_row,
    re.S,
)
if not row_modifier or "Box(lineModifier)" not in lyric_row:
    fail("the measured modifier must be attached to the rendered lyric row")
if not re.search(
    r"\.onSizeChanged\s*\{\s*onItemSizeChanged\s*\(\s*it\.height\s*\)\s*\}",
    row_modifier.group("body"),
):
    fail("PlayerLyricRow must report its actual onSizeChanged height in pixels")

viewport_row_args = call_arguments(lyrics_viewport, "PlayerLyricRow")
if not re.search(
    r"\bonItemSizeChanged\s*=\s*\{\s*sizePx\s*->\s*onItemSizeChanged\s*\(\s*index\s*,\s*sizePx\s*\)\s*\}",
    viewport_row_args,
):
    fail("the viewport must forward the rendered row index together with its measured pixel height")

for argument in ("onSelect = onSelect", "onPlay = onPlay", "onLongPress = onLongPress"):
    if argument not in normalize(route_viewport_args):
        fail(f"the lyric route must preserve its existing viewport callback: {argument}")
for argument in (
    "onSelect = onSelect",
    "onPlay = { onPlay(lyrics[index]) }",
    "onLongPress = { onLongPress(index) }",
):
    if argument not in normalize(viewport_row_args):
        fail(f"the viewport must preserve the selected row's existing callback mapping: {argument}")

row_click_args = call_arguments(lyric_row, "combinedClickable")
if not re.search(
    r"\bonClick\s*=\s*\{\s*if\s*\(\s*compactPreview\s*&&\s*onCompactPreviewTap\s*!=\s*null\s*\)\s*"
    r"onCompactPreviewTap\(\)\s*else\s*onSelect\(index\)\s*\}",
    normalize(row_click_args),
):
    fail("a normal lyric-row tap must select that row while compact-preview taps keep their existing route")
if not re.search(r"\bonClick\s*=\s*onPlay\b", normalize(call_arguments(lyric_row, "clickable"))):
    fail("the lyric row's play control must retain its existing onPlay callback")

full_route_args = call_arguments(full, "PlayerLyricsRoute")
for argument in (
    "onSelect = { selectLyric(it) }",
    "onPlay = { playLyricLine(it) }",
    "onLongPress = { openLyricSelection(it) }",
):
    if argument not in normalize(full_route_args):
        fail(f"FullPlayer must preserve the existing lyric selection/play callback: {argument}")
for token in (
    "playerLyricsViewportIndices(lyrics.size, active, fullLyricsPage = lyricLayout)",
    "val fullLyricsPageScrollMode = lyricLayout && !immersiveCoverLyricsMode",
    "playerLyricSeekProgress(line.timeMs, duration, lyricOffset.toLong())",
    "seekLyricLine(target)",
):
    if token not in full:
        fail(f"FullPlayer must retain lyric route/seek coordination: {token}")
if not re.search(
    r"BackHandler\s*\(\s*enabled\s*=\s*midPanel\s*==\s*\"queue\"\s*\|\|\s*immersiveMode\s*\|\|\s*lyricLayout\s*\)\s*"
    r"\{\s*returnToPreviousPlayerMode\(\)\s*\}",
    full,
):
    fail("FullPlayer must preserve the existing back-route callback for queue and lyric modes")
print("[player-mode-coordinator-guard] PASS: route coordination is extracted while FullPlayer retains state ownership and call surface")
PY
