#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1])
source_root = root / "app/src/main/java/xyz/cncnlp/soundwave"
full = (source_root / "FullPlayer.kt").read_text(encoding="utf-8")
coordinator_path = source_root / "ui/player/PlayerDlnaSessionCoordinator.kt"
coordinator = coordinator_path.read_text(encoding="utf-8") if coordinator_path.is_file() else ""
test_path = root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerDlnaSessionCoordinatorTest.kt"
test = test_path.read_text(encoding="utf-8") if test_path.is_file() else ""
def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-dlna-session-coordinator-guard] FAIL: {message}")
require(bool(coordinator), "the DLNA session coordinator is missing")
require(len(coordinator.splitlines()) <= 300, "the DLNA coordinator must stay at or below 300 lines")
require("internal class PlayerDlnaSessionCoordinator(" in coordinator, "the coordinator must remain an internal boundary")
require("interface PlayerDlnaGateway" in coordinator and "DlnaControllerGateway" in coordinator,
        "the coordinator must use an injectable gateway with a thin system adapter")
for forbidden in ("DatagramSocket", "HttpURLConnection", "Dispatchers.IO", "MediaRouter", "AudioManager"):
    require(forbidden not in coordinator, f"network, system-route, or audio-volume implementation leaked into the coordinator: {forbidden}")
for token in ("scanDevices()", "pollRemoteSession", "DISCONNECTED_AFTER_MS = 15_000L", "POLL_INTERVAL_MS = 2_500L",
              "gateway.markDisconnected()", "StateFlow<PlayerDlnaPanelSnapshot>"):
    require(token in coordinator, f"the existing session/snapshot contract is missing: {token}")
for token in ("val playerScope = rememberCoroutineScope()", "scope = playerScope", "dlnaCoordinator.snapshot.collectAsState()",
              "fun scanDlna() = dlnaCoordinator.scan()", "onConnected = { toast(\"已连接 $it\") }",
              "dlnaCoordinator.pause", "dlnaCoordinator.resume", "dlnaCoordinator.seek", "dlnaCoordinator.stop"):
    require(token in full, f"FullPlayer must retain scope, panel state collection, or UI callback ownership: {token}")
require("DlnaController." not in full, "FullPlayer must delegate DLNA session side effects")
require("showDeviceSheet" not in coordinator, "closing the device panel must not own or cancel DLNA jobs")
require(bool(test), "the deterministic JVM regression test is missing")
for token in ("CompletableDeferred", "TestCoroutineScheduler", "runTest", "concurrentScanRequestsAreDeduplicated",
              "successfulRemotePollRefreshes", "pollingStopsOnSessionSwitch", "FifteenSecondThreshold",
              "failedRemoteControlsClearTheSession"):
    require(token in test or (token == "TestCoroutineScheduler" and "testScheduler" in test),
            f"the injected-gateway scheduler regression is missing: {token}")
require(len(test.splitlines()) <= 300, "the DLNA coordinator regression test must stay at or below 300 lines")
print("[player-dlna-session-coordinator-guard] PASS: injected session boundary, scope/lifecycle contract, deterministic regressions and bounded files")
PY
