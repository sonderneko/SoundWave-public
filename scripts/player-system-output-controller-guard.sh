#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
source_root = root / "app/src/main/java/xyz/cncnlp/soundwave"
full = (source_root / "FullPlayer.kt").read_text(encoding="utf-8")
controller_path = source_root / "ui/player/PlayerSystemOutputController.kt"
snapshot_path = source_root / "ui/player/PlayerSystemOutputSnapshot.kt"
sheet_path = source_root / "ui/player/PlayerDeviceSheet.kt"
controller = controller_path.read_text(encoding="utf-8") if controller_path.is_file() else ""
snapshot = snapshot_path.read_text(encoding="utf-8") if snapshot_path.is_file() else ""
sheet = sheet_path.read_text(encoding="utf-8") if sheet_path.is_file() else ""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-system-output-controller-guard] FAIL: {message}")


require(bool(controller), "the Android MediaRouter controller is missing")
require(bool(snapshot), "the pure route-snapshot policy is missing")
require(bool(sheet), "the stateless device-picker surface is missing")
require(len(controller.splitlines()) <= 120, "the system-output controller must stay below 120 lines")
require(len(snapshot.splitlines()) <= 60, "the pure snapshot policy must stay below 60 lines")
require(len(sheet.splitlines()) < 300, "the device-picker surface must stay below 300 lines")
for token in (
    "mediaRouter.addCallback(",
    "MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN",
    "fun start(",
    "fun stop()",
    "mediaRouter::removeCallback",
    "onSnapshotChanged = null",
    "MediaRouter.ROUTE_TYPE_LIVE_AUDIO",
    "isEnabled = { route -> route.isEnabled }",
    "supportedTypes = { route -> route.supportedTypes }",
):
    require(token in controller, f"the controller lifecycle/snapshot contract is missing: {token}")
require("DlnaController" not in controller and "AudioManager" not in controller,
        "DLNA and system-volume ownership must remain outside the route controller")
for token in (
    'Intent("android.settings.panel.action.MEDIA_OUTPUT")',
    "Settings.ACTION_BLUETOOTH_SETTINGS",
    "系统未提供媒体输出弹窗，已打开蓝牙设备连接页",
    "无法打开系统蓝牙设备连接页",
    '"连接失败：${error.message ?: "设备不可用"}"',
):
    require(token in controller, f"existing system-output behavior is missing: {token}")
for token in (
    "isEnabled(route) && (supportedTypes(route) and liveAudioType) != 0",
    "fun isSelected(route: T)",
    "fun isDefaultRouteSelected()",
):
    require(token in snapshot, f"the testable snapshot policy is missing: {token}")
for token in (
    "playerOutputRouteMonitoringEnabled(showDeviceSheet)",
    "systemOutputController.start",
    "systemOutputController.stop()",
    "systemOutputController.selectRoute(route)",
    "systemOutputController.openSystemOutput()",
    "PlayerDeviceSheet(",
    "onDismissRequest = { showDeviceSheet = false }",
    "onSelectLocalRoute = { route -> toast(systemOutputController.selectRoute(route)) }",
    "onSelectSystemRoute = { route -> toast(systemOutputController.selectRoute(route)) }",
    "onOpenSystemOutput = { systemOutputController.openSystemOutput()?.let(::toast) }",
    "onRefreshDlna = { scanDlna() }",
    "onCastDlna = { device -> castDlna(device) }",
    "onPauseDlna = { dlnaPause() }",
    "onResumeDlna = { dlnaResume() }",
    "onStopDlna = { dlnaStop() }",
):
    require(token in full, f"FullPlayer must preserve the output-sheet contract: {token}")
for token in (
    "outputSnapshot.isDefaultRouteSelected()",
    "outputSnapshot.isSelected(route)",
    "onSelectLocalRoute)",
    "onSelectSystemRoute(route)",
    "onOpenSystemOutput()",
    "onRefreshDlna()",
    "onCastDlna(device)",
    "onPauseDlna()",
    "onResumeDlna()",
    "onStopDlna()",
):
    require(token in sheet, f"the device surface must render routes and emit its explicit action callback: {token}")
for forbidden in ("showDeviceSheet", "systemOutputController", "dlnaCoordinator", "Toast", "fun scanDlna", "fun castDlna"):
    require(forbidden not in sheet, f"device-sheet state or side-effect ownership leaked into the UI surface: {forbidden}")
require("MediaRouter" not in full and "mediaRouter" not in full,
        "FullPlayer must consume the controller rather than own Android route side effects")
print("[player-system-output-controller-guard] PASS: scoped controller, visible-only lifecycle, stateless device surface, explicit actions and bounded files")
PY
