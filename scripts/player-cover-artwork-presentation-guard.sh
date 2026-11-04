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
renderer_path = source_root / "ui/player/PlayerCoverArtworkCard.kt"
renderer = renderer_path.read_text(encoding="utf-8") if renderer_path.is_file() else ""
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()


def fail(message: str) -> None:
    print(f"[player-cover-artwork-presentation-guard] FAIL: {message}")
    raise SystemExit(1)


if not renderer:
    fail("the extracted cover artwork renderer is missing")
if len(renderer.splitlines()) < 30:
    fail("the extracted renderer must remain a meaningful presentation block (30+ lines)")
if not re.search(r"@Composable\s+internal fun PlayerCoverArtworkCard\s*\(", renderer):
    fail("PlayerCoverArtworkCard must remain an internal Compose presentation component")

# The renderer may render a supplied image slot and dispatch clicks, but must not
# own page/player state, artwork requests, palette sampling, animation, or gestures.
for token in (
    "remember(", "LaunchedEffect", "DisposableEffect", "SideEffect",
    "CoroutineScope", "Animatable", "animateFloat", "animateColor",
    "AsyncImage", "ImageRequest", "CacheManager", "sampleCoverBackground",
    "onSizeChanged", "pointerInput", "detectTapGestures",
    "detectDragGestures", "MediaRouter", "AudioManager", "DlnaController",
):
    if token in renderer:
        fail(f"the presentation renderer must not own state/effects/requests/gestures ({token})")

for expected in (
    "artworkContent: @Composable (Modifier) -> Unit",
    "artworkContent(Modifier.fillMaxSize())",
    "Modifier.fillMaxWidth(coverFraction).aspectRatio(1f).offset(x = (-1).dp)",
    ".graphicsLayer { scaleX = coverScale; scaleY = coverScale }",
    "clickable(indication = null, interactionSource = null, onClick = onClick)",
    ".background(primaryColor.copy(alpha = 0.12f))",
    "Brush.verticalGradient(",
    "coverLower.copy(alpha = 0.18f)",
    "if (resolving)",
):
    if expected not in renderer:
        fail(f"the renderer no longer preserves its display/callback contract: {expected}")
if "cardAlpha" in renderer or "visual.cardAlpha" in full:
    fail("cover card must not consume removed custom-card transparency")
if renderer.count("onClick = onClick") != 2:
    fail("both the outer artwork area and cover card must dispatch the supplied click callback")

if full.count("PlayerCoverArtworkCard(") != 1:
    fail("FullPlayer must host exactly one cover artwork card renderer")
for expected in (
    "coverFraction = mainCoverFraction",
    "coverScale = coverScale",
    "fullBleedCoverPage = fullBleedCoverPage",
    "hasArtwork = song.cover.isNotEmpty()",
    "coverLower = coverLower",
    "playerBackground = ThemeTokens.PlayerBackground",
    "resolving = resolving",
    "status = status",
    "onClick = { toggleCoverLyricsMode() }",
    "artworkContent = { artworkModifier ->",
    "model = CacheManager.coverModel(ctx, song.cover) ?: song.cover",
    "onSuccess = { result -> sampleCoverBackground(result.result.drawable)?.let { coverPalette = it } }",
):
    if expected not in full:
        fail(f"FullPlayer must retain state/request ownership and pass it into the renderer: {expected}")

for expected in (
    "val coverScale by animateFloatAsState(",
    "var coverPalette by remember(song.key(), song.cover)",
    "val coverLower by animateColorAsState(",
):
    if expected not in full:
        fail(f"cover animation/palette state was moved or removed from FullPlayer: {expected}")

version_name = re.search(r'versionName\s+"([^\"]+)"', build)
version_code = re.search(r"versionCode\s+(\d+)", build)
expected_code = version.rsplit(".", 1)[-1]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    fail("VERSION must use the three-part numeric version format")
if version_name is None or version_name.group(1) != version:
    fail("VERSION and Gradle versionName must match")
if not expected_code.isdigit() or version_code is None or version_code.group(1) != expected_code:
    fail("Gradle versionCode must match the final VERSION component")

print(f"[player-cover-artwork-presentation-guard] PASS: stateless cover card, parent-owned artwork/cache/palette state, callbacks, and synchronized {version}/{expected_code} version markers")
PY
