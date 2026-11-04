#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
source = root / "app/src/main/java/xyz/cncnlp/soundwave"
# r672: all styles share the bottom-sheet queue (DefaultPlayerQueuePage); the Apple-only queue route was removed.
queue = (source / "ui/player/DefaultPlayerQueuePage.kt").read_text(encoding="utf-8")
sheet = (source / "PlayerMenuSheet.kt").read_text(encoding="utf-8")
full = (source / "FullPlayer.kt").read_text(encoding="utf-8")
service = (source / "PlayerService.kt").read_text(encoding="utf-8")
models = (source / "Models.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/PlayerLayoutModesTest.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-menu-playback-order-guard] FAIL: {message}")

require('Text("播放队列"' in queue and 'UP NEXT · ${state.queueSize} 首' in queue,
        "queue heading with count must remain")
require('"定位当前播放歌曲"' in queue and "onClick = actions.onLocateCurrent" in queue,
        "locate-current action must remain available")
# 用户 10-18：队列抽屉用 顺序/随机/单曲循环 分段控件直接设定顺序（不是循环切换的 onMode）。
require("onSetMode" in queue and "actions.onMode" not in queue,
        "the queue sheet sets the order through its segmented control, not the cycling onMode toggle")
require("onMode: () -> Unit" in sheet and ".clickable(onClick = onMode)" in sheet,
        "the menu playback-order button must invoke the shared mode callback")
require('contentDescription = "播放顺序：${playModeLabel(playMode)}"' in sheet and
        "role = Role.Button" in sheet,
        "the menu button must expose a mode-specific accessible description")
# r658c P：⋯ 抽屉头部改由共用 TrackActionsSheetContent → DesignSheetItemHeader 渲染（歌名 / 歌手 单行省略）。
design_sheet = (source / "ui/common/DesignSheet.kt").read_text(encoding="utf-8")
track_sheet = (source / "TrackActionsSheet.kt").read_text(encoding="utf-8")
item_header = design_sheet[design_sheet.index("fun DesignSheetItemHeader("):design_sheet.index("fun DesignSheetRow(")]
require("TrackActionsSheetContent(" in sheet and "DesignSheetItemHeader(" in track_sheet
        and "song.name" in track_sheet and 'song.singer.ifBlank { "未知歌手" }' in track_sheet
        and item_header.count("overflow = TextOverflow.Ellipsis") >= 2 and item_header.count("maxLines = 1") >= 2,
        "long song title and singer must both remain single-line ellipsis text")
menu_call = re.search(r"FullPlayerMenuSheet\([\s\S]*?\n\s*\)", full)
require(menu_call is not None and "onMode = onMode" in menu_call.group(0),
        "FullPlayer must route the menu click through the existing onMode callback")
require("playMode = nextPlayMode(playMode)" in service and
        "internal fun nextPlayMode(mode: PlayMode)" in models,
        "mode changes must continue to use the single playback state transition")
require("playbackModeCyclesThroughEverySupportedModeAndWraps" in test,
        "JVM coverage must exercise all four modes and wraparound")
require(version == "1.0.659" and 'versionName "1.0.659"' in build and
        re.search(r"versionCode\s+659\b", build) is not None,
        "VERSION, versionName and versionCode must match 1.0.659 / 659")
print("[player-menu-playback-order-guard] PASS: queue controls, menu callback, dynamic accessibility, ellipsis, mode cycle test, and version")
PY
