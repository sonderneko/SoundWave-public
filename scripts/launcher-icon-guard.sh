#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])

def read(path):
    return (root / path).read_text(encoding="utf-8")

def require(ok, message):
    if not ok:
        raise SystemExit(f"[launcher-icon-guard] FAIL: {message}")

res = root / "app/src/main/res"

# --- 接线：adaptive icon 三层指向现行资产（普通 + round 两份同构） ---
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    xml = read(f"app/src/main/res/mipmap-anydpi-v26/{name}")
    require('android:drawable="@drawable/ic_launcher_background"' in xml,
            f"{name}: background must be the gradient drawable, not the retired flat color")
    require('android:drawable="@drawable/ic_launcher_foreground"' in xml,
            f"{name}: foreground layer missing")
    require('android:drawable="@drawable/ic_launcher_monochrome"' in xml,
            f"{name}: monochrome layer missing")

# --- 选定稿色值钉（用户 2026-11-04 选定「声纹环-浅色」，源分层 SVG 逐值） ---
fg = read("app/src/main/res/drawable/ic_launcher_foreground.xml")
for token, label in (
    ("#FF2C2C2F", "ink ring start (track gradient)"),
    ("#FFFF5C45", "coral arc mid (arc gradient)"),
    ("#FFDD3920", "coral arc end (arc gradient)"),
    ("#FF7DAA99", "sage wave start (wv gradient, user-selected green kept as-is)"),
    ("#FF436E5E", "sage wave end (wv gradient)"),
    ("#FFE1DDD6", "warm background end (bg gradient lives in ic_launcher_background)"),
):
    haystack = fg if "background" not in label else read(
        "app/src/main/res/drawable/ic_launcher_background.xml")
    require(token in haystack, f"selected palette value missing: {label} {token}")

# --- 旧资产 absence 钉（batch-1 黑胶图标全家退役） ---
for old in ("#FF2B302C", "#FF3D433E"):
    for f in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml",
              "ic_splash_disc.xml", "ic_stat_wave.xml"):
        require(old not in read(f"app/src/main/res/drawable/{f}"),
                f"{f} still carries the retired vinyl palette {old}")
require(not (res / "drawable/ic_stat_disc.xml").exists(),
        "ic_stat_disc.xml must stay deleted (replaced by ic_stat_wave)")
scheduler = read("app/src/main/java/xyz/cncnlp/soundwave/ScriptProbeScheduler.kt")
require("R.drawable.ic_stat_wave" in scheduler,
        "ScriptProbeScheduler completion notification must use ic_stat_wave")
require("R.drawable.ic_stat_disc" not in scheduler,
        "ScriptProbeScheduler still references the retired ic_stat_disc")
require(not (res / "values/colors.xml").exists() or
        "ic_launcher_background" not in read("app/src/main/res/values/colors.xml"),
        "the retired flat ic_launcher_background color must stay deleted")

# --- CI 登记 ---
workflow = read(".github/workflows/build-apk.yml")
require("scripts/launcher-icon-guard.sh" in workflow,
        "the launcher icon guard must run in portable quality gates")

print("[launcher-icon-guard] PASS: adaptive wiring, selected palette pinned, retired vinyl assets absent, CI registration")
PY
