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
# ICON-SPLASH-REDO 规格变更（2026-11-05）：图标资源全家换名 ic_launcher* →
# ic_launcher_wave*。理由：F1 摘除 monochrome 后用户装新包桌面仍呈黑图，父级对交付
# release 包 aapt2 终核证伪包内问题（包内即选定浅色稿、monochrome 计数 0、各密度
# PNG 平均色 #E1D5CF）——黑图系 vivo 启动器按旧资源名键缓存的陈旧渲染。换名使缓存
# 键失效、装包即刷新；图形与色值逐值不变。本守卫随之重钉新名，并新增旧名 absence
# 钉与 manifest/代码接线钉，严格度不降。
for name in ("ic_launcher_wave.xml", "ic_launcher_wave_round.xml"):
    xml = read(f"app/src/main/res/mipmap-anydpi-v26/{name}")
    require('android:drawable="@drawable/ic_launcher_wave_background"' in xml,
            f"{name}: background must be the gradient drawable, not the retired flat color")
    require('android:drawable="@drawable/ic_launcher_wave_foreground"' in xml,
            f"{name}: foreground layer missing")
    # BATCH2 反馈 F1 规格变更（2026-11-05，用户真机点名桌面图标发黑）：monochrome 层退役——
    # 主题图标管线会用黑色剪影顶替用户选定的浅色彩稿；用户只选了浅色一稿，任何呈现路径
    # 都只许是该稿。此处由「必须在场」重钉为「必须缺席」，与旧钉同等严格。
    require('ic_launcher_monochrome' not in xml,
            f"{name}: monochrome layer must stay removed (themed-icon pipelines rendered it as a black icon on device)")
require(not (res / "drawable/ic_launcher_monochrome.xml").exists(),
        "ic_launcher_monochrome.xml must stay deleted (BATCH2 F1)")

# --- 换名接线钉（manifest + 代码 + widget）与旧名 absence 钉（ICON-SPLASH-REDO） ---
manifest = read("app/src/main/AndroidManifest.xml")
require('android:icon="@mipmap/ic_launcher_wave"' in manifest,
        "manifest application icon must reference the renamed @mipmap/ic_launcher_wave")
require('android:roundIcon="@mipmap/ic_launcher_wave_round"' in manifest,
        "manifest roundIcon must reference the renamed @mipmap/ic_launcher_wave_round")
for old in ("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml",
            "drawable/ic_launcher_background.xml", "drawable/ic_launcher_foreground.xml",
            "drawable/ic_launcher_fg_bitmap.png"):
    require(not (res / old).exists(), f"pre-rename resource must stay absent: {old}")
for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
    for old in ("ic_launcher.png", "ic_launcher_round.png"):
        require(not (res / f"mipmap-{density}/{old}").exists(),
                f"pre-rename density icon must stay absent: mipmap-{density}/{old}")
    for new in ("ic_launcher_wave.png", "ic_launcher_wave_round.png"):
        require((res / f"mipmap-{density}/{new}").exists(),
                f"renamed density icon missing: mipmap-{density}/{new}")
presenter = read("app/src/main/java/xyz/cncnlp/soundwave/NotificationPresenter.kt")
require("R.drawable.ic_launcher_wave_fg_bitmap" in presenter,
        "NotificationPresenter large-icon fallback must use the renamed fg bitmap")
require("R.drawable.ic_launcher_fg_bitmap" not in presenter.replace(
        "R.drawable.ic_launcher_wave_fg_bitmap", ""),
        "NotificationPresenter still references the pre-rename fg bitmap")
widget = read("app/src/main/java/xyz/cncnlp/soundwave/widget/WidgetUpdater.kt")
require("R.mipmap.ic_launcher_wave" in widget,
        "WidgetUpdater fallback cover must use the renamed mipmap icon")
for layout in ("widget_player_4x1.xml", "widget_player_4x2.xml"):
    require('android:src="@mipmap/ic_launcher_wave"' in read(f"app/src/main/res/layout/{layout}"),
            f"{layout} placeholder must use the renamed mipmap icon")

# --- 选定稿色值钉（用户 2026-11-04 选定「声纹环-浅色」，源分层 SVG 逐值） ---
fg = read("app/src/main/res/drawable/ic_launcher_wave_foreground.xml")
for token, label in (
    ("#FF2C2C2F", "ink ring start (track gradient)"),
    ("#FFFF5C45", "coral arc mid (arc gradient)"),
    ("#FFDD3920", "coral arc end (arc gradient)"),
    ("#FF7DAA99", "sage wave start (wv gradient, user-selected green kept as-is)"),
    ("#FF436E5E", "sage wave end (wv gradient)"),
    ("#FFE1DDD6", "warm background end (bg gradient lives in ic_launcher_wave_background)"),
):
    haystack = fg if "background" not in label else read(
        "app/src/main/res/drawable/ic_launcher_wave_background.xml")
    require(token in haystack, f"selected palette value missing: {label} {token}")

# --- 旧资产 absence 钉（batch-1 黑胶图标全家退役） ---
for old in ("#FF2B302C", "#FF3D433E"):
    for f in ("ic_launcher_wave_foreground.xml",
              "ic_splash_mark.xml", "ic_stat_wave.xml"):
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
        'name="ic_launcher_background"' not in read("app/src/main/res/values/colors.xml"),
        "the retired flat ic_launcher_background color must stay deleted")

# --- CI 登记 ---
workflow = read(".github/workflows/build-apk.yml")
require("scripts/launcher-icon-guard.sh" in workflow,
        "the launcher icon guard must run in portable quality gates")

print("[launcher-icon-guard] PASS: renamed ic_launcher_wave wiring (adaptive/manifest/code/widget), selected palette pinned, monochrome layer absent (BATCH2 F1), pre-rename names absent (ICON-SPLASH-REDO), retired vinyl assets absent, CI registration")
PY
