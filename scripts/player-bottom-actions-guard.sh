#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
layout = (root / "app/src/main/java/xyz/cncnlp/soundwave/PlayerLayout.kt").read_text()
full = (root / "app/src/main/java/xyz/cncnlp/soundwave/FullPlayer.kt").read_text()
controls = (root / "app/src/main/java/xyz/cncnlp/soundwave/PlayerControls.kt").read_text()
renderer = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerBottomControlChromeRenderer.kt").read_text()
footer = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/FullPlayerFooterRenderer.kt").read_text()
immersive_header = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerImmersiveCoverLyricsHeader.kt").read_text()
lyrics_header = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerLyricsPageHeader.kt").read_text()
rounded_button = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerRoundedIconButton.kt").read_text()

def require(condition, message):
    if not condition:
        raise SystemExit(f"[player-bottom-actions-guard] FAIL: {message}")

require(
    "!playerUsesDedicatedImmersiveCoverLyricsViewport(raw, lyricsPage, queuePage)" in layout
    and "queuePage: Boolean\n): Boolean =" in layout,
    "device and queue actions are hidden only on the dedicated immersive lyrics page",
)
show_call = 'playerShowsDeviceAndQueueActions(layout, lyricLayout, midPanel == "queue")'
require(full.count(show_call) == 1 and "collapseHiddenDeviceAndQueueActions" not in full,
        "the shared bottom-control adapter must keep device and queue actions independent of play/pause")
require("FullPlayerFooterRenderer(" in full and "PlayerBottomControlChrome(applyOffset = false)" in full
        and "controls: @Composable () -> Unit" in footer and "controls()" in footer,
        "the extracted footer must render through FullPlayer's existing control adapter")
require("showDeviceAndQueueActions: Boolean = true" in controls
        and controls.count("else Spacer(Modifier.size(46.dp))") == 2,
        "only the dedicated lyrics route hides both actions while preserving their slots")
require("collapseHiddenDeviceAndQueueActions" not in renderer
        and "verticalOffset: Dp = (-14).dp" in renderer
        and "progress: Float" in renderer and "dragRatio: Float" in renderer
        and "val ratio = if (dragging) dragRatio else progress.coerceIn(0f, 1f)" in renderer
        and "playbackAudioFormatLabel(playbackAudioFormat)" in renderer
        and "showDeviceAndQueueActions = showDeviceAndQueueActions" in renderer,
        "control renderer must retain its default offset and derive only presentation values")
footer_controls_start = full.find('                if (midPanel != "queue") {\n                    Spacer(Modifier.height(if (immersiveCoverLyricsPage)')
footer_controls_end = full.find("\n            } else {", footer_controls_start)
require(footer_controls_start >= 0 and footer_controls_end > footer_controls_start,
        "the shared lyrics/footer control boundary must remain identifiable")
lyrics_footer = full[footer_controls_start:footer_controls_end]
require(lyrics_footer.count("if (!immersiveCoverLyricsPage)") == 1
        and "if (immersiveCoverLayout)" in lyrics_footer
        and "AppleMusicBottomAction(" not in lyrics_footer,
        "the dedicated immersive-cover lyrics route must be excluded by one parent gate and render no separate device/queue row")
require(immersive_header.count("PlayerRoundedIconButton(") == 2
        and lyrics_header.count("PlayerRoundedIconButton(") == 2
        and "containerColor" not in immersive_header,
        "both immersive lyrics headers must reuse the same two playback action buttons without a translucent fill")
require(".clickable(onClick = onClick)" in rounded_button
        and ".background(" not in rounded_button,
        "shared lyrics-header action buttons must keep the circular hit target but no circular background")
print("[player-bottom-actions-guard] PASS: pause-independent device/queue actions, hidden immersive-lyrics actions, matched transparent title buttons, and stable slots")
PY
