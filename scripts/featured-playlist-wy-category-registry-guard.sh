#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
interface = (base / "source/MusicSource.kt").read_text()
adapter = (base / "source/PlatformSourceAdapter.kt").read_text()
feed = (base / "PlaylistFeedRepository.kt").read_text()
direct = (base / "source/direct/PlaylistDirectSearch.kt").read_text()
matrix = (root / "docs/source-capability-matrix.md").read_text()
workflow = (root / ".github/workflows/build-apk.yml").read_text()
version = (root / "VERSION").read_text().strip()
build = (root / "app/build.gradle").read_text()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[featured-playlist-wy-category-registry-guard] FAIL: {message}")

require(
    "suspend fun playlistCategorySquare(category: String, limit: Int, page: Int = 1): List<Playlist> = emptyList()" in interface,
    "MusicSource must provide a harmless default for unsupported category-square routes",
)
require(
    "override suspend fun playlistCategorySquare(category: String, limit: Int, page: Int): List<Playlist> =\n"
    "        if (platform == \"wy\") fetchWyPlaylistCategoryDirect(category, limit, page) else emptyList()" in adapter,
    "the adapter must delegate only to the original NetEase category function and pass through category/limit/page",
)
require(
    "val provider = MusicSourceRegistry.platformSource(platform)" in feed
    and "if (platform == \"wy\") provider.playlistCategorySquare(term, 40)\n"
        "                        else provider.searchPlaylists(term, platform, 40)" in feed
    and "fetchWyPlaylistCategoryDirect(" not in feed,
    "only the existing WY branch may route through Registry; other platform calls must remain unchanged",
)
require(
    '"$WY_SQUARE_URL?cat=$encoded&order=hot&limit=${request.limit}&offset=${request.offset}"' in direct
    and "normalizePlaylists(\n            parseWyPlaylistSquare(" in direct
    and "parseWyPlaylistKeywordOutcome" in direct,
    "the original category-square URL/normalization must remain distinct from keyword search",
)
require(
    "## PlaylistFeedRepository 网易分类广场 Source Registry 委派" in matrix
    and "该入口会同时取分类广场与关键词结果" in matrix
    and "非 `wy` 平台仍调用原 `provider.searchPlaylists(term, platform, 40)`" in matrix,
    "the matrix must record the pure-delegation boundary and non-equivalence reason",
)
require(
    "scripts/featured-playlist-wy-category-registry-guard.sh" in workflow,
    "the dedicated guard must remain in the portable CI guard list",
)
version_match = re.fullmatch(r"1\.0\.(\d+)", version)
name_match = re.search(r'versionName\s+"([^"]+)"', build)
code_match = re.search(r"versionCode\s+(\d+)", build)
require(
    version_match is not None and name_match is not None and code_match is not None
    and name_match.group(1) == version
    and code_match.group(1) == version_match.group(1),
    "VERSION and Gradle version declarations must remain synchronized",
)
print("[featured-playlist-wy-category-registry-guard] PASS: one WY category-square delegate, unchanged non-WY route, matrix boundary, and synchronized version")
PY
