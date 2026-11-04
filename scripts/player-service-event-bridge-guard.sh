#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
bridge_path = base / "ui/player/PlayerServiceEventBridge.kt"
main_path = base / "MainActivity.kt"
controller_path = base / "PlaybackController.kt"
recovery_path = base / "PlaybackFailureRecovery.kt"
bridge = bridge_path.read_text(encoding="utf-8")
main = main_path.read_text(encoding="utf-8")
controller = controller_path.read_text(encoding="utf-8")
recovery = recovery_path.read_text(encoding="utf-8")

def require(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit(f"[player-service-event-bridge-guard] FAIL: {message}")

callbacks = (
    "onSongChanged", "onError", "onNeedNext", "onNeedPrev", "onPreparedNext",
    "onNeedReplay", "onLikeToggle", "onLyricFloatToggle", "onSleepTick", "onModeChanged",
)
attach = re.search(r"private fun attachPlayerServiceEventCallbacks\(callbacks: PlayerServiceEventCallbacks\)\s*\{(?P<body>.*?)\n\}", bridge, re.S)
detach = re.search(r"private fun detachPlayerServiceEventCallbacks\(callbacks: PlayerServiceEventCallbacks\)\s*\{(?P<body>.*?)\n\}", bridge, re.S)
require(attach is not None and detach is not None, "concrete PlayerService attach/detach boundary is missing")
for name in callbacks:
    bound = "bound" + name[0].upper() + name[1:]
    require(f"PlayerService.{name} = callbacks.{bound}" in attach.group("body"), f"{name} is not attached through its unique forwarding callback")
    require(f"if (PlayerService.{name} === callbacks.{bound}) PlayerService.{name} = null" in detach.group("body"), f"{name} cleanup must clear only the forwarding callback still owned by this Activity")
require(all(f"internal val bound{name[0].upper() + name[1:]}" in bridge for name in callbacks),
        "every event needs a per-registration forwarding callback identity")
require("onPlayState" not in bridge, "the removed duplicate play-state callback must stay absent")
require("stopSelf(" not in bridge and "PlayerService.stop" not in bridge, "event cleanup must not stop foreground playback")

require("remember { PlayerServiceEventBridge() }" in main, "AppRoot must retain one bridge for its composition")
require(re.search(r"playerServiceEventBridge\.register\s*\(\s*PlayerServiceEventCallbacks\s*\(", main) is not None,
        "AppRoot must install its existing handler closures through the bridge")
require(re.search(r"DisposableEffect\(playerServiceEventBridge\)\s*\{\s*onDispose\s*\{\s*playerServiceEventBridge\.dispose\(\)", main) is not None,
        "AppRoot disposal must pair the event registration with bridge cleanup")
require(not re.search(r"PlayerService\.(?:onSongChanged|onError|onNeedNext|onNeedPrev|onPreparedNext|onNeedReplay|onLikeToggle|onLyricFloatToggle|onSleepTick|onModeChanged)\s*=", main),
        "direct service callback registration/cleanup must stay outside MainActivity")
require("playback.playbackTransactionGate.hasCurrent(changed.key(), queueRevision)" in main,
        "song identity/generation transaction gate must remain in the existing handler")
require("onError = { failure -> playback.handlePlaybackFailure(failure) }" in main,
        "the bridge error callback must delegate to PlaybackController")
require("fun handlePlaybackFailure(failure: PlayerService.Companion.PlaybackFailure)" in controller,
        "PlaybackController must own playback-failure recovery")
require("currentFailedSong(failure.songKey, failure.generation)" in controller,
        "failure recovery must validate song key and Service generation before starting")
require("it.key() == songKey && currentGeneration() == generation" in recovery,
        "failure identity validation must preserve both song key and Service generation")
require("clearSongCache(failedSong)" in recovery and
        "if (currentFailedSong(songKey, generation) == null) return@launch" in recovery,
        "failure recovery must revalidate after cache clearing before retrying")
require("UrlResolver.markWinningScriptBad(failed.key())" in controller and
        "PlaybackDiagnostics.markFailure(failed, PlaybackDiagnostics.failureLayerOf(msg))" in controller,
        "failure recovery must retain bad-script cooldown and diagnostics")
require("SmartSourceStore.forget(failed.key())" in controller and
        "queue.ifEmpty { listOf(failed) }" in controller and
        "PlayerService.snapshot().queueIndex.coerceAtLeast(0)" in controller and
        "forceCross = true" in controller and
        "skipScripts = blockedScript.takeIf { it.isNotBlank() }?.let(::setOf).orEmpty()" in controller,
        "failure retry must preserve cache, script skip, forceCross, queue and index behavior")
require((root / "VERSION").read_text(encoding="utf-8").strip() == "1.0.675", "VERSION must be 1.0.675")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
require('versionName "1.0.675"' in build and re.search(r"versionCode\s+675\b", build) is not None,
        "Gradle versionName/versionCode must match 1.0.675 / 675")
print("[player-service-event-bridge-guard] PASS: ten callbacks have paired ownership, error recovery retains pre/post song-generation guards, and 1.0.675 is synchronized")
PY
