#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import subprocess
import sys
from collections import Counter

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
full = (base / "FullPlayer.kt").read_text()
row = (base / "ui/player/PlayerLyricRow.kt").read_text()
preview = (base / "ui/player/PlayerLyricsStylePreview.kt").read_text()
viewport = (base / "ui/player/FullPlayerLyricsViewport.kt").read_text()
route = (base / "ui/player/PlayerLyricsRoute.kt").read_text()
layout_code = (base / "PlayerLayout.kt").read_text()
selection = (base / "ui/player/PlayerLyricActionsSheet.kt").read_text()
state = (base / "ui/player/PlayerLyricBrowseState.kt").read_text()
rounded_button = (base / "ui/player/PlayerRoundedIconButton.kt").read_text()
immersive_header = (base / "ui/player/PlayerImmersiveCoverLyricsHeader.kt").read_text()
state_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerLyricBrowseStateTest.kt").read_text()
seek_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerLyricSeekTest.kt").read_text()
layout_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/PlayerLayoutModesTest.kt").read_text()
version = (root / "VERSION").read_text().strip()
build = (root / "app/build.gradle").read_text()
main_activity = (base / "MainActivity.kt").read_text()

def require(ok, message):
    if not ok:
        raise SystemExit(f"[player-lyric-selection-guard] FAIL: {message}")

require("PlayerLyricRow(" in viewport and "PlayerLyricRow(" in preview,
        "full-page routes and lyrics-style ordinary preview must share the same row")
require("selectedIndex = selectedIndex" in viewport and "onSelect = onSelect" in viewport
        and "onPlay = { onPlay(lyrics[index]) }" in viewport,
        "full-page rows must separate selected-line, tap-select and play-button callbacks")
require("selectedIndex = previewFocusIndex" in preview and "onSelect = onSelect" in preview
        and "onPlay = { onPlay(line) }" in preview
        and "onSelect = { selectLyric(it) }" in full and "onPlay = { playLyricLine(it) }" in full,
        "ordinary lyrics-style preview must expose the same selection and play actions")
require("playerLyricShouldShowSelectionBackground(browseState, index)" in row
        and ".background(if (showSelectedBackground) Color.White.copy" in row
        and "playerLyricShouldUsePlaybackHighlight(index, activeIndex)" in row
        and "playerLyricShouldReserveRowActionSlots(browseState, compactPreview)" in row
        and "playerLyricShouldShowRowActions(" in row
        and "Alignment.CenterStart" in row and "Alignment.CenterEnd" in row,
        "lyric rows must keep playback highlight separate and stabilize manual seek-control slots")
require("hasManuallyScrolled = true" in state
        and "state.hasManuallyScrolled && state.selectedIndex == index && seekAvailable" in state
        and "hasManuallyScrolled: Boolean = false" in state
        and "isUserScrollActive: Boolean = false" in state
        and "state.isBrowsing && !state.isUserScrollActive && state.selectedIndex == index" in state,
        "only a real user-scroll entry may expose actions for the one selected seekable row")
require("browseState: PlayerLyricBrowseState" in row
        and "browseState: PlayerLyricBrowseState" in viewport
        and "browseState: PlayerLyricBrowseState" in route
        and "browseState = lyricBrowseState" in full,
        "the same browse state must reach preview and full-page lyric rows")
require("LaunchedEffect(lyricLayout)" in full
        and "if (!lyricLayout) lyricBrowseState = playerLyricExitLyricsPage(lyricBrowseState)" in full
        and "playerLyricExitLyricsPage" in state,
        "leaving the lyrics page must clear manual actions and restore follow mode")
require("Color.White.copy(alpha = 0.92f)" in row
        and 'WhiteGlyph("play_round", 22.dp, Color.White)' in row
        and "height(1.dp)" not in row
        and "PlayerRoundedIconButton(" not in row,
        "manual seek affordance must use a backgroundless rounded white triangle, readable timestamp, and no horizontal rule")
require("Box(" in rounded_button and ".clip(CircleShape)" in rounded_button
        and ".clickable(onClick = onClick)" in rounded_button
        and ".background(" not in rounded_button
        and "contentDescription = contentDescription" in rounded_button
        and "PlayerRoundedIconButton(" in immersive_header
        and '.size(48.dp)' in row
        and 'contentDescription = "从 ${playerLyricTimeLabel(line.timeMs)} 播放此句"' in row,
        "lyric play action must keep a stable accessible touch target while immersive controls retain their shared button")
require("onClick = onToggle" in immersive_header and "onClick = onNext" in immersive_header
        and 'contentDescription = if (playing) "暂停" else "播放"' in immersive_header
        and 'contentDescription = "下一首"' in immersive_header,
        "immersive play/pause and next controls must retain their original callbacks and accessibility labels")
require("if (lineTimeMs < 0L" in (base / "ui/player/PlayerLyricSeek.kt").read_text()
        and "seekAvailable = playerLyricSeekProgress" in viewport
        and "seekAvailable = playerLyricSeekProgress" in preview,
        "plain text or invalid duration rows must not expose a seek action")
require("fun playLyricLine(line: LyricLine)" in full
        and "playerLyricSeekProgress(line.timeMs, duration, lyricOffset.toLong()) ?: return" in full
        and "seekLyricLine(target)" in full,
        "seeking must happen only from the explicit valid row play action")
require("onLongPress = { openLyricSelection(it) }" in full
        and "onLongPress = { onLongPress(lineIndex) }" in preview
        and "playerLyricOpenSelection" in state,
        "long press in both UI paths must enter selection, not seek")
require("if (manualBrowsing || activeIndex < 0) return@LaunchedEffect" in route
        and "playerLyricsCenteredScrollOffsetPx" in route
        and "playerLyricsActiveScrollOffsetPx" in route
        and "DragInteraction.Start" in route and "DragInteraction.Stop" in route
        and "PlayerLyricsScrollEvent.Position(listState.isScrollInProgress, index)" in route
        and "latestSettleBrowsing(centeredIndex ?: centeredLyricIndex() ?: -1)" in route
        and "return maxTop / 2" in layout_code
        and "viewportStartOffset + info.viewportEndOffset" in route,
        "manual browse must freeze auto-follow while both manual and active playback rows remain centered")
require("if (lyricBrowseState.isSelecting)" in full
        and "PlayerLyricActionsSheet(" in full
        and "PlayerLyricCorrectionDialog(" in full
        and "PlayerLyricSelectionPage(" not in full
        and "BackHandler(enabled = lyricBrowseState.isSelecting)" in full
        and "playerLyricCloseSelection" in full,
        "long press must open a bottom action sheet and correction dialog without replacing the player mode")
require('title = "歌词纠错"' in selection and "onSearchLyricCorrection()" in selection
        and "onApplyLyricCorrection(candidate)" in selection
        and "onClearLyricCorrection()" in selection
        and "候选" in selection and "similarity" in selection,
        "lyric correction must search, require explicit candidate confirmation, show similarity, and support undo")
candidate_start = selection.index("correctionCandidates.forEachIndexed { index, candidate ->")
candidate_end = selection.index("\n                if (hasLyricCorrection)", candidate_start)
candidate_rows = selection[candidate_start:candidate_end]
require(".background(" not in candidate_rows
        and ".clickable(role = Role.RadioButton) { onSelectCandidate(index) }" in candidate_rows
        and "RadioButtonDefaults.colors(" in candidate_rows
        and "selectedColor = MaterialTheme.colorScheme.primary" in candidate_rows
        and "unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant" in candidate_rows
        and "color = MaterialTheme.colorScheme.onSurface" in candidate_rows
        and "color = MaterialTheme.colorScheme.onSurfaceVariant" in candidate_rows
        and "fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal" in candidate_rows
        and "onClick = { onSelectCandidate(index) }" in candidate_rows,
        "candidate options must stay transparent, theme-readable, radio-selectable, and bold only when selected")
require("onLyricCorrectionRefresh = { correctedSong ->" in main_activity
        and "refreshLyrics(correctedSong)" in main_activity,
        "confirmed correction and undo must reload lyrics through the existing playback refresh path")
require("selectedLine?.translation.isNullOrBlank()" in selection
        and "selectedLine?.romanization.isNullOrBlank()" in selection
        and "fontSize = (base * 0.78f).sp" in row
        and "fontSize = (base * 0.68f).sp" in row,
        "translation and romanization actions must only enable for existing fields and render smaller")
for name in (
    "playbackCannotTakeFocusBackWhileTheUserBrowsesLyrics",
    "longPressEntersSelectionModeWithoutChangingPlaybackFocusOrRequestingSeek",
    "closingSelectionKeepsThePlayerSubmodeBrowseSelectionAndFocusIntact",
    "pressingPlayResumesPlaybackFollowingAndClearsManualSelection",
    "initialPlaybackFollowingHidesRowActionsAndSelectedBackground",
    "manualScrollShowsActionsWithoutBackgroundWhileTheUserIsMoving",
    "settledManualScrollShowsBackgroundForTheSelectedLineAndKeepsActions",
    "resumingManualScrollImmediatelyHidesSettledBackground",
    "longPressSelectionDoesNotRevealRowActions",
    "exitingLyricsPageClearsManualBrowseActionsAndSelectionBackground",
    "manualSelectionKeepsSeekSlotsFixedAndDoesNotTurnSelectedLyricIntoPlaybackHighlight",
    "longPressActionSheetDoesNotRevealSeekControlsOrReserveManualScrollSlots",
):
    require(f"fun {name}()" in state_test, f"missing browse regression test: {name}")
require("formatsTimedRowsAndLeavesPlainTextWithoutASeekTimestamp" in seek_test
        and "playerLyricsCenteredScrollOffsetPx(240, 70)" in layout_test,
        "JVM tests must cover untimed rows and center alignment")
match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
name = re.search(r'versionName\s+"([^"]+)"', build)
code = re.search(r"versionCode\s+(\d+)", build)
require(match is not None and name is not None and code is not None
        and name.group(1) == version and code.group(1) == match.group(3),
        "VERSION, versionName and versionCode must stay synchronized")
changed = set(subprocess.check_output(["git", "-C", str(root), "diff", "--name-only"], text=True).splitlines())
require("app/src/main/java/xyz/cncnlp/soundwave/LyricModels.kt" not in changed,
        "core lyric timeline parser must remain unchanged")
allowed_parse_edits = {
    "app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt": Counter([
        "-lines = LyricParser.parse(rawLrc)", "+lines = parseLyricTextBundle(rawLrc)",
        "-lines = LyricParser.parse(rawLrc)", "+lines = parseLyricTextBundle(rawLrc)",
        "-val lines = LyricParser.parse(rawLrc)", "+val lines = parseLyricTextBundle(rawLrc)",
    ]),
    "app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt": Counter([
        "-runCatching { parseLyricTextBundle(LyricSourceLoader.load(applicationContext, song, source)) }",
        "+runCatching { parseLyricTextBundle(LyricSourceLoader.load(applicationContext, song, source, serviceOwner)) }",
    ]),
}
for relative, expected in allowed_parse_edits.items():
    if relative not in changed:
        continue
    patch = subprocess.check_output(["git", "-C", str(root), "diff", "--unified=0", "--", relative], text=True)
    edits = Counter((line[0] + line[1:].strip()) for line in patch.splitlines() if line.startswith(("+", "-")) and not line.startswith(("+++", "---")))
    # Enforce the lyric parser boundary, not unrelated owner/checkpoint changes in these files.
    parser_edits = Counter({line: count for line, count in edits.items() if "LyricParser.parse(" in line or "parseLyricTextBundle(" in line})
    # A structured-coroutine refactor can move/reindent an identical parser call.
    # Cancel only exact normalized +/- pairs; altered calls and call-count changes
    # still reach the explicit compatibility allowlist below.
    for line in list(parser_edits):
        if not line.startswith("-"):
            continue
        added = "+" + line[1:]
        unchanged = min(parser_edits[line], parser_edits[added])
        parser_edits[line] -= unchanged
        parser_edits[added] -= unchanged
    parser_edits = +parser_edits
    require(not parser_edits or parser_edits == expected, f"{relative} lyric parser edits must remain compatible")
print("[player-lyric-selection-guard] PASS: shared row, manual-focus freeze, centered selection, explicit valid seek, long-press selection, route preservation, truthful unavailable actions, and version/boundary checks")
PY
