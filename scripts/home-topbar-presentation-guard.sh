#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
source_root = root / "app/src/main/java/xyz/cncnlp/soundwave"
home_page = (source_root / "HomePage.kt").read_text(encoding="utf-8")
renderer_path = source_root / "ui/home/HomeTopBar.kt"
renderer = renderer_path.read_text(encoding="utf-8") if renderer_path.is_file() else ""
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()


def fail(message: str) -> None:
    print(f"[home-topbar-presentation-guard] FAIL: {message}")
    raise SystemExit(1)


if re.search(r"HomeTopBar\s*\(", home_page):
    fail("the obsolete Hot/Rank/Playlist top tabs must not be rendered on the redesigned home feed")
for expected in (
    "AppPageHeader(",
    'title = when (currentSection)',
    "showAvatar = true",
    "onAvatarLongClick = onAvatarLongClick",
    "platformPickerOpen = true",
    'title = "切换音源"',
    "rememberPagerState(initialPage = sectionIdx)",
    "LaunchedEffect(pagerState, sections)",
    "HomeSectionNavigation.syncExternalPager(",
    "HorizontalPager(state = pagerState, userScrollEnabled = false",
    'onSection("hot")',
    'onSection("rank")',
    'onSection("pl")',
):
    if expected not in home_page:
        fail(f"the redesigned header/page routes are missing required behavior: {expected}")

version_name = re.search(r'versionName\s+"([^"]+)"', build)
version_code = re.search(r"versionCode\s+(\d+)", build)
expected_code = version.rsplit(".", 1)[-1]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    fail("VERSION must use the three-part numeric version format")
if version_name is None or version_name.group(1) != version:
    fail("VERSION and Gradle versionName must match")
if not expected_code.isdigit() or version_code is None or version_code.group(1) != expected_code:
    fail("Gradle versionCode must match the final VERSION component")

print(f"[home-topbar-presentation-guard] PASS: screenshot-aligned title/platform/avatar header without old tabs, retained deep-section/back routes, and synchronized {version}/{expected_code} version markers")
PY
