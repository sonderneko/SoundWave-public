#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
source = root / "app/src/main/java/xyz/cncnlp/soundwave"
full = (source / "FullPlayer.kt").read_text(encoding="utf-8")
row = (source / "ui/player/PlayerLyricRow.kt").read_text(encoding="utf-8")
preview_source = (source / "ui/player/PlayerLyricsStylePreview.kt").read_text(encoding="utf-8")
coordinator = (source / "ui/player/FullPlayerPlaybackCoordinator.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        print(f"[player-lyrics-style-immersive-entry-guard] FAIL: {message}")
        raise SystemExit(1)


start_token = "Spacer(Modifier.height(PLAYER_LYRICS_STYLE_COVER_TO_LYRICS_GAP_DP.dp))"
end_token = "Spacer(Modifier.height(PLAYER_LYRICS_STYLE_LYRICS_TO_METADATA_GAP_DP.dp))"
start = preview_source.find(start_token)
end = preview_source.find(end_token, start + len(start_token))
require(start >= 0 and end > start, "Lyrics Style cover preview boundaries were not found")
preview = preview_source[start:end]
require("verticalScroll(rememberScrollState())" in preview, "the preview must keep its existing touch-scroll path")
require(
    "onCompactPreviewTap = if (" in preview_source
    and "playerLyricsStylePreviewTapShouldEnterImmersive(layout, compactPreview = true)" in preview
    and "{ onCompactPreviewTap(lineIndex) }" in preview_source
    and "onCompactPreviewTap = { index -> selectLyric(index); openLyricsImmersive() }" in full,
    "only Lyrics Style preview rows may select the tapped line and open the existing immersive route",
)
require(full.count("PlayerLyricsStylePreview(") == 1 and "if (lyricsStyleLayout)" in full,
        "the extracted preview must remain conditional on the existing Lyrics Style route")
require(preview_source.count("onCompactPreviewTap = if (") == 1, "the new route callback must remain scoped to the single Lyrics Style preview")
require("browseState = lyricBrowseState" in full and "selectedIndex = focusedLyricIndex" in full,
        "the immersive route must continue consuming the same caller-owned browse/selection state")

clickable = row.find(".combinedClickable(")
row_box = row.find("Box(lineModifier)", clickable)
play_button = row.find(".size(48.dp)", row_box)
require(clickable >= 0 and row_box > clickable and play_button > row_box,
        "lyric-row taps and the nested seek control must retain separate hit targets")
require("onClickLabel = if (compactPreview && onCompactPreviewTap != null) \"进入沉浸歌词模式\" else null" in row,
        "the lyrics entry needs an explicit accessibility click label")
require("if (compactPreview && onCompactPreviewTap != null) onCompactPreviewTap()" in row,
        "the entry callback must only replace compact-preview row taps")
require("onLongClickLabel = \"选择歌词\"" in row and "onLongClick = { onLongPress() }" in row,
        "long-press lyric selection must remain available")
require('.clickable(role = Role.Button, onClick = onPlay)' in row
        and 'WhiteGlyph("play_round", 22.dp, Color.White)' in row
        and "contentDescription = \"从 ${playerLyricTimeLabel(line.timeMs)} 播放此句\"" in row,
        "right-side lyric jump must remain an independent accessible action")

require("fun openLyricsImmersive()" in coordinator and "fun returnToPreviousPlayerMode()" in coordinator,
        "entry and back must use the existing route coordinator, not a new overlay")
print("[player-lyrics-style-immersive-entry-guard] PASS: Lyrics Style preview-only entry, accessible row click, scroll/long-press/seek isolation, shared selection state, and existing route reuse")
PY
