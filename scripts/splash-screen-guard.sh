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
        raise SystemExit(f"[splash-screen-guard] FAIL: {message}")

gradle = read("app/build.gradle")
require("androidx.core:core-splashscreen:1.0.1" in gradle,
        "core-splashscreen dependency missing from app/build.gradle")

gate = read("app/src/main/java/xyz/cncnlp/soundwave/startup/SplashGate.kt")
require("const val MAX_HOLD_MS: Long = 1_500L" in gate,
        "splash hard cap must stay at the user-ruled 1500ms")
require("setKeepOnScreenCondition" in gate and "SplashGatePolicy.shouldKeepOnScreen" in gate,
        "splash exit must be ready-driven via setKeepOnScreenCondition + SplashGatePolicy")
require("releaseOnFirstContentFrame" in gate,
        "first-frame ready release is missing from SplashGate")
test = read("app/src/test/java/xyz/cncnlp/soundwave/startup/SplashGatePolicyTest.kt")
require("SplashGatePolicy.MAX_HOLD_MS" in test and "1_500L" in test,
        "policy unit test must pin the 1500ms cap")

main = read("app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt")
install_at = main.find("SplashGate.install(this)")
set_content_at = main.find("setContent {")
require(0 <= install_at < set_content_at,
        "SplashGate.install must run in MainActivity.onCreate before setContent")
require("splashGate.releaseOnFirstContentFrame(window)" in main,
        "MainActivity must wire the first-frame ready release after setContent")

manifest = read("app/src/main/AndroidManifest.xml")
activity_at = manifest.find('android:name=".MainActivity"')
require('android:theme="@style/Theme.SoundWave.Splash"' in manifest[activity_at:activity_at + 500],
        "MainActivity must launch with the splash theme")

light = read("app/src/main/res/values/themes.xml")
night = read("app/src/main/res/values-night/themes.xml")
for label, themes, paper in (("light", light, "#F4F4F0"), ("night", night, "#101210")):
    require('name="Theme.SoundWave.Splash"' in themes and 'parent="Theme.SplashScreen"' in themes,
            f"{label} splash theme (Theme.SplashScreen parent) missing")
    require(f'<item name="windowSplashScreenBackground">{paper}</item>' in themes,
            f"{label} splash background must be the palette paper {paper}")
    require('<item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash_disc</item>' in themes,
            f"{label} splash icon must be the vinyl disc drawable")
    require('<item name="postSplashScreenTheme">@style/Theme.SoundWave</item>' in themes,
            f"{label} splash theme must hand back to Theme.SoundWave")
    require("#07070D" not in themes and "#7C6CFF" not in themes,
            f"{label} themes still carry a retired launch color (#07070D / #7C6CFF)")
require('<item name="android:colorPrimary">#E8452F</item>' in light,
        "colorPrimary must be the brand coral, not the retired purple")

workflow = read(".github/workflows/build-apk.yml")
require("scripts/splash-screen-guard.sh" in workflow,
        "the splash guard must run in portable quality gates")

print("[splash-screen-guard] PASS: splash dependency, 1.5s fuse policy + test, ready-driven wiring, dual themes, retired launch colors absent, CI registration")
PY
