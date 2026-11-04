#!/usr/bin/env bash
# 1.0.655：全新「默认」播放器样式（主页 / 歌词页 / 队列）+ 普通歌词页回退到 1.0.655 之前的视口。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
src = root / "app/src/main/java/xyz/cncnlp/soundwave"
player = src / "ui/player"

def read(p: Path) -> str:
    return p.read_text(encoding="utf-8") if p.is_file() else ""

def require(cond: bool, msg: str) -> None:
    if not cond:
        raise SystemExit(f"[player-default-mode-guard] FAIL: {msg}")

layout = read(src / "PlayerLayout.kt")
full = read(src / "FullPlayer.kt")
main = read(src / "MainActivity.kt")
require('const val PLAYER_LAYOUT_DEFAULT = "default"' in layout, "new installs must default to the 默认 player layout")
require('"default" to "默认"' in layout and layout.index('"default" to "默认"') < layout.index('"apple" to "Apple Music"'),
        "默认 must be offered first in the player style choices")
require("PlayerVisualMode.DEFAULT" in layout, "默认 needs its own visual mode")
for name in ("DefaultPlayerRoute.kt", "DefaultPlayerMainPage.kt", "DefaultPlayerLyricsPage.kt",
             "DefaultPlayerQueuePage.kt", "DefaultPlayerParts.kt", "DefaultPlayerGlyphs.kt"):
    require((player / name).is_file(), f"the 默认 player lives in its own file: ui/player/{name}")
require(full.count("DefaultPlayerRoute(") == 1 and "if (defaultLayout)" in full,
        "FullPlayer hosts exactly one 默认 route behind the defaultLayout branch")
require("DefaultPlayerBackdrop(" in full, "默认 draws its own theme-aware backdrop")
# Round 1.0.655 swapped the ordinary lyrics page for DesignLyricsPage; 1.0.655 reverts it.
require("DesignLyricsPage" not in full and not (player / "DesignLyricsPage.kt").exists(),
        "the ordinary (Apple Music) lyrics page must use the original lyrics viewport again")
require("PlayerLyricsRoute(" in full and "PlayerLyricsPageHeader(" in full,
        "Apple Music / 歌词样式 / 沉浸封面 keep their original lyrics route")
require(len(full.splitlines()) <= 1200, "FullPlayer.kt must stay within 1200 lines")
route = read(player / "DefaultPlayerRoute.kt")
# r658c P：主页 / 歌词页常驻组合 + graphicsLayer 交叉淡化 + 顶层飞行封面连续过渡（不再 AnimatedContent / Crossfade 重建页面）。
morph = read(player / "PlayerCoverSharedTransition.kt")
require("queueVisibilityState" in route and "PlayerCoverMorph(" in route and "DefaultPlayerFlyingCover(" in route
        and "AnimatedContent(" not in route and "Crossfade(" not in route and "PlayerCoverSharedPages(" not in route
        and "playerMainPageAlpha(morph.progress)" in route and "playerLyricsPageAlpha(morph.progress)" in route
        and "following = lyricsOpen" in route,
        "默认 routes keep both pages composed, cross-fade them by graphicsLayer, fly the cover, and reuse the queue visibility state")
require("AnimatedContent(" not in morph and "SharedTransitionLayout {" not in morph
        and "fun playerCoverMorphFrame(" in morph and "TransformOrigin(0f, 0f)" in morph,
        "the cover morph is a draw-phase graphicsLayer transform between measured slots")
main_page = read(player / "DefaultPlayerMainPage.kt")
require("playerCoverMainSlot(morph)" in main_page and "maxWidth - DefaultPlayerSeekInsetDp * 2" in main_page
        and "Modifier.padding(horizontal = DefaultPlayerSeekInsetDp)" in main_page
        and "Spacer(Modifier.height(DefaultPlayerControlsLiftDp))" in main_page,
        "默认 cover is narrowed to the progress-bar insets and the metadata/controls group is lifted")
queue = read(player / "DefaultPlayerQueuePage.kt")
require("onQueueMove" in queue and "onQueueRemove" in queue and "onLocateCurrent" in queue
        and "onSourcePlaylist" not in queue and "来源歌单" not in queue.split("*/", 1)[1],
        "默认 queue keeps reorder, remove and locate actions; the 播放来源 / 来源歌单 entry is removed")
require("queueSheetBleed(0, navBottomPx)" in queue and "QueueSheetBorderShape" in queue,
        "默认 queue sheet reaches the screen bottom edge like DesignSheet (no bottom gap, no bottom border)")
lyrics = read(player / "DefaultPlayerLyricsPage.kt")
require("onLyricLine" in lyrics and "onLyricLongPress" in lyrics, "默认 lyrics keep tap-to-seek and long-press actions")
for f in ("DefaultPlayerMainPage.kt", "DefaultPlayerLyricsPage.kt", "DefaultPlayerQueuePage.kt"):
    text = read(player / f)
    for token in ("PlayerService", "SharedPreferences", "mutableStateListOf"):
        require(token not in text, f"{f} must not own playback state ({token})")
require("playerLayoutIsDefault(" in main, "system bar icons follow the light 默认 player")
print("[player-default-mode-guard] PASS: 默认 player is the default layout, lives in ui/player/DefaultPlayer*, ordinary lyrics page reverted")
PY
