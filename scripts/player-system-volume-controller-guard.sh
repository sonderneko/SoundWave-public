#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
controller_path = base / "ui/player/PlayerSystemVolumeController.kt"
test_path = root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerSystemVolumeControllerTest.kt"
controller = controller_path.read_text(encoding="utf-8") if controller_path.is_file() else ""
test = test_path.read_text(encoding="utf-8") if test_path.is_file() else ""
full = (base / "FullPlayer.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-system-volume-controller-guard] FAIL: {message}")


require(bool(controller), "the system-volume controller is missing")
require(bool(test), "the deterministic system-volume JVM tests are missing")
require(len(controller.splitlines()) < 150, "the controller must remain below 150 lines")
for token in (
    "interface PlayerSystemVolumeGateway",
    "class PlayerSystemVolumeController",
    "fun initialVolumeLevel()",
    "fun setVolume(value: Float)",
    "object PlayerSystemVolumePolicy",
    "getStreamMaxVolume(AudioManager.STREAM_MUSIC)",
    "getStreamVolume(AudioManager.STREAM_MUSIC)",
    "audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)",
):
    require(token in controller, f"the system-volume ownership contract is missing: {token}")
require("AudioManager" not in full and "setStreamVolume(" not in full,
        "FullPlayer must delegate Android audio-volume side effects")
require("PlayerSystemVolumeController.from(ctx)" in full and
        "systemVolumeController.initialVolumeLevel()" in full and
        "systemVolumeController.setVolume(value)" in full,
        "FullPlayer must delegate system-volume creation, initialization, and writes")
require("var volumePressed" in full and "var showVolumeInMenu" in full,
        "volume interaction and display state must remain owned by FullPlayer")
require("MediaRouter" not in controller and "Dlna" not in controller,
        "system-output routing and DLNA must remain outside the volume controller")
for token in (
    "initialSystemStepIsNormalizedAndClampedForUi",
    "requestedLevelsAreClampedAtBothSystemVolumeBounds",
    "normalizedLevelsMapToNearestDiscreteStepAndInvokeTheGatewayWrite",
    "writtenSteps",
):
    require(token in test, f"a deterministic JVM volume regression is missing: {token}")
require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675")
require("scripts/player-system-volume-controller-guard.sh" in workflow,
        "the system-volume guard must be registered in portable quality gates")
print("[player-system-volume-controller-guard] PASS: bounded controller, Android write contract, delegated UI ownership, deterministic regressions, and 1.0.675")
PY
