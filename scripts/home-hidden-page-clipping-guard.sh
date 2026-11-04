#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
main = (root / "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[home-hidden-page-clipping-guard] FAIL: {message}")

# r657 第二轮：首页层走 fade-through（rememberHomeLayerFadeThrough），离开时保留布局 90ms 淡出后再收为 0 尺寸。
retained_home_layer = re.compile(
    r"val homePageVisible\s*=\s*tab == Tab\.HOME\s*\|\|\s*"
    r"\(tab == Tab\.PLAYER && prevTab == Tab\.HOME\)\s*"
    r"(?:val \(homeLaidOut, homeFade\) = [\w.]*rememberHomeLayerFadeThrough\(homePageVisible\)[^\n]*\s*)?"
    r"Box\(\s*Modifier\s*"
    r"\.clipToBounds\(\)\s*"
    r"\.then\(if \((?:homePageVisible|homeLaidOut)\) Modifier\.fillMaxSize\(\)(?:\.then\(homeFade\))? else Modifier\.size\(0\.dp\)\)\s*"
    r"\.zIndex\(if \((?:homePageVisible|homeLaidOut)\) 0\.5f else -1f\)\s*"
    r"\)\s*\{\s*persistentHomePage\(\)\s*\}",
    re.DOTALL,
)
require("import androidx.compose.ui.draw.clipToBounds" in main,
        "MainActivity must import Compose's bounds-clipping modifier")
require(retained_home_layer.search(main) is not None,
        "the retained HomePage must stay full-size when visible and be clipped when its hidden wrapper is zero-sized")
require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675")
require("scripts/home-hidden-page-clipping-guard.sh" in workflow,
        "the hidden-home clipping guard must run in portable quality gates")
print("[home-hidden-page-clipping-guard] PASS: retained HomePage stays clipped to its visible or zero-size wrapper; 1.0.675/675 synchronized")
PY
