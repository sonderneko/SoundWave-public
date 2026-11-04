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
renderer_path = source_root / "ui/player/PlayerLyricsPageHeader.kt"
renderer = renderer_path.read_text(encoding="utf-8") if renderer_path.is_file() else ""
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()


def fail(message: str) -> None:
    print(f"[player-lyrics-header-presentation-guard] FAIL: {message}")
    raise SystemExit(1)


if not renderer:
    fail("the extracted lyrics-page header renderer is missing")
if len(renderer.splitlines()) < 30:
    fail("the extracted renderer must remain a meaningful presentation block (30+ lines)")
if not re.search(r"@Composable\s+internal fun PlayerLyricsPageHeader\s*\(", renderer):
    fail("PlayerLyricsPageHeader must remain an internal Compose presentation component")

# The renderer may draw values and dispatch callbacks; it must not own page state,
# playback/animation orchestration, artwork requests, caching, or measurement.
for token in (
    "remember(", "LaunchedEffect", "DisposableEffect", "SideEffect",
    "CoroutineScope", "Animatable", "animateFloat", "animateColor",
    "AsyncImage", "ImageRequest", "CacheManager", "onSizeChanged",
    "rememberLazyListState", "rememberScrollState", "MediaRouter",
    "AudioManager", "DlnaController",
):
    if token in renderer:
        fail(f"the presentation renderer must not own state/effects/requests ({token})")
for expected in (
    "artworkContent: @Composable (Modifier) -> Unit",
    "artworkContent(Modifier.fillMaxSize())",
    ".graphicsLayer { scaleX = coverScale; scaleY = coverScale }",
    ".clickable(onClick = onCoverClick)",
    "if (immersiveMode)",
    "onClick = onToggle",
    "onClick = onNext",
    "onClick = onLike",
    "onClick = onMore",
    "basicMarquee()",
):
    if expected not in renderer:
        fail(f"the renderer no longer preserves its display/callback contract: {expected}")

route = re.search(
    r'if\s*\(midPanel\s*!=\s*"queue"\s*&&\s*!immersiveLayout\s*&&\s*!immersiveCoverLayout\)\s*\{(?P<body>.*?)\n\s*\}\s*if\s*\(immersiveCoverLyricsPage\)',
    full,
    re.S,
)
if route is None or "PlayerLyricsPageHeader(" not in route.group("body"):
    fail("the ordinary lyrics route must continue to host the extracted header")
if full.count("PlayerLyricsPageHeader(") != 1:
    fail("FullPlayer must host exactly one lyrics-page header renderer")
for expected in (
    "coverScale = coverScale",
    "immersiveMode = immersiveMode",
    "artworkContent = { artworkModifier ->",
    "CacheManager.coverModel(ctx, song.cover) ?: song.cover",
    "Box(artworkModifier) { DefaultLandscapeCover(Modifier.fillMaxSize())",
    "moreIconTint = if (LocalThemeVisual.current.menuIconWhite) Color.White else white",
    "onCoverClick = { toggleCoverLyricsMode() }",
    "onMore = { menuLevel = 0; showMenu = true }",
    "onLike = onLike",
    "onToggle = onToggle",
    "onNext = onNext",
):
    if expected not in route.group("body"):
        fail(f"FullPlayer must retain state/request ownership and pass it into the renderer: {expected}")
for expected in (
    "val coverScale by animateFloatAsState(",
    "var showMenu by remember",
    "var menuLevel by remember",
):
    if expected not in full:
        fail(f"page-owned animation/menu state was moved or removed: {expected}")

version_name = re.search(r'versionName\s+"([^\"]+)"', build)
version_code = re.search(r"versionCode\s+(\d+)", build)
expected_code = version.rsplit(".", 1)[-1]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    fail("VERSION must use the three-part numeric version format")
if version_name is None or version_name.group(1) != version:
    fail("VERSION and Gradle versionName must match")
if not expected_code.isdigit() or version_code is None or version_code.group(1) != expected_code:
    fail("Gradle versionCode must match the final VERSION component")

print(f"[player-lyrics-header-presentation-guard] PASS: stateless 30+ line header, parent-owned animation/menu/artwork loading, direct callbacks, and synchronized {version}/{expected_code} version markers")
PY
