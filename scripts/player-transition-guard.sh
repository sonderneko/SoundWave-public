#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FULL="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/FullPlayer.kt"
MAIN="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
BACKDROP="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerTransitionBackdrop.kt"
HERO="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerImmersiveCoverHero.kt"
VISIBILITY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerOverlayVisibility.kt"
LAYOUT="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlayerLayout.kt"
LIBRARY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt"
PLAYBACK="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt"
DISMISS_GESTURE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerDismissGestureModifier.kt"

python3 - "$FULL" "$MAIN" "$BACKDROP" "$HERO" "$VISIBILITY" "$LAYOUT" "$LIBRARY" "$PLAYBACK" "$DISMISS_GESTURE" <<'PY'
import re
import sys

full_path, main_path, backdrop_path, hero_path, visibility_path, layout_path, library_path, playback_path, dismiss_gesture_path = sys.argv[1:]
full = open(full_path, encoding="utf-8").read()
main = open(main_path, encoding="utf-8").read()
backdrop = open(backdrop_path, encoding="utf-8").read()
hero = open(hero_path, encoding="utf-8").read()
visibility = open(visibility_path, encoding="utf-8").read()
layout = open(layout_path, encoding="utf-8").read()
library = open(library_path, encoding="utf-8").read()
playback = open(playback_path, encoding="utf-8").read()
dismiss_gesture = open(dismiss_gesture_path, encoding="utf-8").read()


def fail(message):
    print(f"[player-transition-guard] FAIL: {message}")
    raise SystemExit(1)


def matching_block(source, opening):
    if opening < 0 or source[opening] != "{":
        fail("could not locate the player surface content block")
    depth = 0
    index = opening
    state = "code"
    block_depth = 0
    while index < len(source):
        if state == "line_comment":
            if source[index] == "\n":
                state = "code"
            index += 1
            continue
        if state == "block_comment":
            if source.startswith("/*", index):
                block_depth += 1
                index += 2
            elif source.startswith("*/", index):
                block_depth -= 1
                index += 2
                if block_depth == 0:
                    state = "code"
            else:
                index += 1
            continue
        if state in ("string", "char"):
            quote = '"' if state == "string" else "'"
            if source[index] == "\\":
                index += 2
            elif source[index] == quote:
                state = "code"
                index += 1
            else:
                index += 1
            continue
        if state == "raw_string":
            if source.startswith('"""', index):
                state = "code"
                index += 3
            else:
                index += 1
            continue

        if source.startswith("//", index):
            state = "line_comment"
            index += 2
        elif source.startswith("/*", index):
            state = "block_comment"
            block_depth = 1
            index += 2
        elif source.startswith('"""', index):
            state = "raw_string"
            index += 3
        elif source[index] == '"':
            state = "string"
            index += 1
        elif source[index] == "'":
            state = "char"
            index += 1
        elif source[index] == "{":
            depth += 1
            index += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
            index += 1
        else:
            index += 1
    fail("unterminated player surface content block")


# The artwork backdrop, foreground and gesture detector must share one measured
# surface. Checking only a translating backdrop Box missed the detached sheet.
root = re.search(
    r'Box\(\s*Modifier\s*\.fillMaxSize\(\)'
    r'(?P<mods>.*?)\)\s*\{\s*'
    r'if\s*\(playerTransitionBackdropMovesWithSheet\(layout\)\)',
    full,
    re.S,
)
if root is None:
    fail("the single player surface containing the transition backdrop was not found")
if not re.search(
    r'\.graphicsLayer\s*\{\s*clip\s*=\s*true\s*'
    r'translationY\s*=\s*playerDismissSurfaceOffsetPx\('
    r'dismissOffsetState\.floatValue,\s*size\.height\)\s*\}', root.group("mods")
):
    fail("the moving player surface must clip artwork overflow and use its measured dismiss offset")
if not re.search(r'\.onSizeChanged\s*\{\s*playerSurfaceHeightPx\s*=\s*it\.height\s*\}', root.group("mods")):
    fail("the moving surface must record the same measured height used by drag thresholds")
surface_body_start = root.end()
surface_body_open = full.rfind("{", root.start(), surface_body_start)
surface_body = matching_block(full, surface_body_open)
if surface_body.find("PlayerTransitionBackdrop(") < 0 or surface_body.find("if (song.cover.isNotEmpty())") < 0:
    fail("the moving surface must own both immersive artwork and foreground player content")
if surface_body.find("PlayerTransitionBackdrop(") > surface_body.find("if (song.cover.isNotEmpty())"):
    fail("the moving backdrop must stay behind the foreground player content")
if "PlayerTransitionBackdrop(" not in full:
    fail("FullPlayer no longer hosts the extracted transition backdrop")
for token in (
    "internal fun PlayerTransitionBackdrop(",
    "painter = coverPainter",
    "contentScale = ContentScale.Crop",
    "alpha = IMMERSIVE_PLAYER_BACKDROP_ALPHA",
    "scaleX = IMMERSIVE_PLAYER_BACKDROP_SCALE",
    "scaleY = IMMERSIVE_PLAYER_BACKDROP_SCALE",
    ".blur(IMMERSIVE_PLAYER_BACKDROP_BLUR_DP.dp)",
    "immersivePlayerBackdropTintAlpha",
    "immersivePlayerBackdropScrimAlpha",
    "ThemeTokens.PlayerBackground",
):
    if token not in backdrop:
        fail(f"extracted backdrop is missing its existing visual contract: {token}")
if "PlayerImmersiveCoverHero(" not in full:
    fail("FullPlayer no longer hosts the extracted immersive-cover hero")
for token in (
    "internal fun PlayerImmersiveCoverHero(",
    "painter = coverPainter",
    "contentDescription = coverDescription",
    "contentScale = ContentScale.Crop",
    ".playerHeroFade(bridge = fadeBridgeColor)",
    "fadeFraction: Float = IMMERSIVE_HERO_FADE_FRACTION_PLAYER",
    "internal const val IMMERSIVE_HERO_FADE_FRACTION_PLAYER = 0.42f",
    "WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp",
    "Color.Black.copy(alpha = 0.34f)",
    "onClick = onClick",
):
    if token not in hero:
        fail(f"extracted hero is missing its existing visual contract: {token}")

if full.count("playerDismissSurfaceOffsetPx(dismissOffsetState.floatValue, size.height)") != 1:
    fail("dismiss offset must have exactly one measured player-surface translation")
if surface_body.count(".then(dismissGestureModifier)") != 1:
    fail("the foreground gesture detector must remain a single child of the moving surface")
if "val screenHpx = playerSurfaceHeightPx.takeIf { it > 0 }?.toFloat() ?: configuredScreenHpx" not in full:
    fail("dismiss thresholds must use the measured surface height after first layout")
if "rememberPlayerDismissGestureModifier(" not in full:
    fail("FullPlayer must delegate pointer and nested-scroll recognition to the focused gesture modifier")
for token in (
    "object : NestedScrollConnection",
    "NestedScrollSource.UserInput",
    "PointerEventPass.Final",
    "queueListState.canScrollBackward",
    "latestOnDismissEnd.value()",
    "latestOnResetDismiss.value()",
):
    if token not in dismiss_gesture:
        fail(f"the focused gesture modifier is missing its scroll/dismiss contract: {token}")

# Entry/exit of the player route remains one vertical-only host transition;
# do not add fade/scale or a second host animation to disguise desynchronization.
host = re.search(
    r'fun\s+AppRootFullPlayerLayer\s*\(\s*\)\s*\{(?P<body>.*?)label\s*=\s*"playerSheetTransition"',
    main,
    re.S,
)
if host is None:
    fail("player AnimatedVisibility host was not found")
body = host.group("body")
for token in ("slideInVertically", "slideOutVertically"):
    if token not in body:
        fail(f"player host is missing {token}")
for token in ("fadeIn", "fadeOut", "scaleIn", "scaleOut", "expandVertically", "shrinkVertically"):
    if token in body:
        fail(f"player host must remain vertical-only; found {token}")

if "drawPlayerUnderlayDuringTransition(\n            playerLayout, fullPlayerVisibilityState.currentState, fullPlayerVisibilityState.targetState" not in main:
    if not re.search(
        r'drawPlayerUnderlayDuringTransition\(\s*playerLayout,\s*'
        r'fullPlayerVisibilityState\.currentState,\s*fullPlayerVisibilityState\.targetState,\s*'
        r'playerDismissOffsetHolder\s*\)', main, re.S
    ):
        fail("MainActivity must apply shared interactive-dismiss ownership to the main route")
source_root = re.search(
    r'Box\(Modifier\.fillMaxSize\(\)\.background\(bg\)\)\s*\{(?P<body>.*?)\n\s*Scaffold\(',
    main,
    re.S,
)
if source_root is None:
    fail("the root must retain the app/source-page background beneath the moving player")
if "SharedCoverOverlayRoot(" not in source_root.group("body"):
    fail("the shared-cover flight layer must remain a root sibling above Scaffold")
if "val immersivePlayerSurfaceVisible =" in main or "if (immersivePlayerSurfaceVisible) Modifier.background(ThemeTokens.PlayerBackground)" in main:
    fail("a root-level player-color plate recreates the black/double-background transition")
for token in (
    "internal fun playerRouteUnderlayShouldDraw(",
    "internal class PlayerDismissOffsetHolder",
    "internal fun playerDismissExitOffsetPx(",
    "internal fun playerDismissGestureRevealsSource(",
    "internal fun playerDismissSurfaceOffsetPx(",
    "internal fun playerImmersiveTransitionSurfaceVisible(",
):
    if token not in layout:
        fail(f"layout visibility policy is missing: {token}")
for token in (
    "playerUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)",
    "playerDismissGestureRevealsSource(currentState, targetState, dismissOffsetPx)",
    "playerDismissSurfaceOffsetPx(dismissOffsetHolder.state.floatValue, size.height)",
    "clipRect(left = 0f, top = 0f, right = size.width, bottom = dismissOffsetPx)",
    "playerDetailUnderlayShouldDraw(rawLayout, currentState, targetState, dismissOffsetPx)",
):
    if token not in visibility:
        fail(f"source-underlay gesture rendering is missing: {token}")
if "playerDismissExitOffsetPx(fullHeight, playerDismissOffsetHolder.latestOffsetPx)" not in main:
    fail("route exit must continue only the remaining distance after an interactive drag")
if "PlayerDismissDecision.CLOSE_PLAYER -> {\n                updateDismissStatusBarExposure(0f)\n                onBack()" not in full:
    fail("gesture close must hand its current offset directly to the single host exit")
if "animateDismissTo(screenHpx" in full or "closeAfter = true" in full:
    fail("gesture close must not start a second full-height sheet animation")
if "suppressArtworkBackdrop = playerImmersiveTransitionSurfaceVisible(" not in main:
    fail("immersive detail transitions must avoid a second full-screen artwork blur")
if "if (detailCover.isNotEmpty() && !suppressArtworkBackdrop)" not in library:
    fail("detail artwork blur must be suppressible while the moving player owns the surface")
if "val progressState = playback.progressState" not in main or "val currentTimeState = playback.currentTimeState" not in main:
    fail("playback progress/time states must be passed as state holders instead of read at AppRoot")
if "var progress by playback.progressState" in main or "var currentTime by playback.currentTimeState" in main:
    fail("high-frequency playback progress must not invalidate the full AppRoot composition")
if "collectAsState()" in main or "val playbackSnapshot by PlayerService.playback" in main:
    fail("the 400ms PlayerService flow must not be collected as Compose state by AppRoot")
if not re.search(r'LaunchedEffect\(playback\)\s*\{\s*PlayerService\.playback\.collect\s*\{\s*playback\.onSnapshot\(it\)', main, re.S):
    fail("AppRoot must consume playback snapshots in a coroutine without subscribing its composition")
if "val progress by progressState" not in full or "val currentTime by currentTimeState" not in full:
    fail("the full player must observe its high-frequency playback values locally")
if "queueCurrentIndexState = playback.queueCurrentIndexState" not in main or "val queueCurrentIndex by queueCurrentIndexState" not in full:
    fail("the queue index must update the player locally without exposing each service tick to AppRoot")
if "queueCurrentIndex = snapshot.queueIndex" not in playback:
    fail("PlaybackController must update the queue-index state from each accepted service snapshot")
if "sourceStatusBarExposed = !fullPlayerVisibilityState.currentState" not in main or "onDismissGestureStatusBarExposure = { playerDismissStatusBarExposed = it }" not in main:
    fail("status-bar icon ownership must follow the page exposed by the interactive sheet")
if "fun updateStatusBarExposure(" not in layout or "playerDismissStatusBarShouldExpose(offsetPx, insetPx)" not in layout:
    fail("gesture status-bar exposure must share the dismiss state owner and respect the actual inset threshold")
if "dismissOffsetHolder.updateStatusBarExposure(offsetPx, statusBarRevealThresholdPx, onDismissGestureStatusBarExposure)" not in full:
    fail("FullPlayer must delegate status-bar exposure state to its shared dismiss holder")
print("[player-transition-guard] PASS: shared drag/exit offset, clipped source-page exposure, single backdrop ownership, local playback-state subscriptions, status-bar handoff, and vertical-only host transition")
PY
