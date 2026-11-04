#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])

def read(path):
    return (root / path).read_text()

def require(ok, message):
    if not ok:
        raise SystemExit(f"[actual-audio-quality-guard] FAIL: {message}")

def require_all(text, *tokens):
    return all(token in text for token in tokens)

version = (root / "VERSION").read_text().strip()
gradle = read("app/build.gradle")
service = read("app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt")
models = read("app/src/main/java/xyz/cncnlp/soundwave/ResolutionModels.kt")
controller = read("app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt")
full_player = read("app/src/main/java/xyz/cncnlp/soundwave/FullPlayer.kt")
renderer = read("app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerBottomControlChromeRenderer.kt")
format_model = read("app/src/main/java/xyz/cncnlp/soundwave/playback/PlaybackAudioFormat.kt")
format_test = read("app/src/test/java/xyz/cncnlp/soundwave/PlaybackAudioFormatTest.kt")

require(version == "1.0.659" and 'versionName "1.0.659"' in gradle and re.search(r"versionCode\s+659\b", gradle),
        "VERSION, versionName and versionCode must match 1.0.659/659")
require_all(service,
            "onAudioInputFormatChanged(",
            "currentMediaItem?.mediaId != mediaId",
            "eventMediaId != mediaId",
            "audioFormatsByPlayer[p] = observed",
            "if (p === player) publishPlayback()",
            "clearAudioFormatTracking(p)")
require_all(models, "val audioFormat: PlaybackAudioFormat?", "val sourcePlatform: String")
require("snapshot.audioFormat.takeIf" in controller and "snapshot.song?.key() == currentSong?.key()" in controller,
        "controller must reject a previous song's renderer format")
require_all(controller,
            "PlayerService.playUrl(context, playUrl, song, startMs, sourcePlatform = result.platform)",
            "PlayerService.playUrl(context, plainPath, song, startMs, mediaLease, result.platform)",
            'sourcePlatform = "local"')
require_all(format_model, "format.sampleMimeType", "sampleRateHz", "channelCount", "播放格式信息暂不可用")
require("播放格式：" not in format_model and "双声道" not in format_model,
        "the player format label must omit its prefix and channel count")
require("playbackAudioFormat = playbackAudioFormat" in full_player
        and "playbackAudioFormatLabel(playbackAudioFormat)" in renderer
        and "actualQuality = actualQuality" in renderer
        and "song.actualQuality.ifBlank" not in full_player and "preferQ" not in full_player,
        "FullPlayer must pass the observed format to the control renderer; never invent a label from requested quality")
require_all(format_test,
            "renderer FLAC format displays codec and sample rate only",
            '"FLAC · 192 kHz"',
            "unknown fields are omitted and no preferred quality is invented",
            "AAC codec string and non-integer kHz sample rate are formatted accurately")
print("[actual-audio-quality-guard] PASS: lifecycle-bound renderer format, song identity, platform propagation, no preferred-quality fallback, and format-label regressions")
PY
