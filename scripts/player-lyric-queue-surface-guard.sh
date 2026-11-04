#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
# r672: the Apple-only queue (PlayerQueuePanel / PlayerQueueSurface) was removed; all styles share DefaultPlayerQueuePage.
queue = (base / "ui/player/DefaultPlayerQueuePage.kt").read_text()
actions = (base / "ui/player/PlayerLyricActionsSheet.kt").read_text()
full = (base / "FullPlayer.kt").read_text()
backdrop = (base / "ui/player/PlayerTransitionBackdrop.kt").read_text()
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/R672PlayerFixesTest.kt").read_text()

def require(ok: bool, message: str) -> None:
    if not ok:
        raise SystemExit(f"[player-lyric-queue-surface-guard] FAIL: {message}")

require(
    "DefaultPlayerQueuePage(defaultPlayerState, defaultPlayerActions, queueListState, enterScope = this)" in full
    and "PlayerQueueRouteContent(" not in full
    and not (base / "ui/player/PlayerQueuePanel.kt").exists()
    and not (base / "ui/player/PlayerQueueSurface.kt").exists()
    and "onLocateCurrent" in queue,
    "every player style must use the shared bottom-sheet queue; the removed Apple queue surface must not return",
)
require(
    # r658c P：公共 DesignSheetDialog 抽屉；三项核心操作 + 可选悬浮歌词。
    "DesignSheetDialog(" in actions
    and actions.count('title = "') == 4
    and all(f'title = "{title}"' in actions for title in ("歌词纠错", "翻译", "罗马音"))
    and "playerQueueSurface" not in actions,
    "lyric actions must use the compact bottom sheet rather than the former queue-backed full-page cards",
)
require(
    "sampleCoverBackground(result.result.drawable)?.let { coverPalette = it }" in full
    and "coverLower by animateColorAsState(coverPalette.lower" in full
    and "coverPalette = coverPalette" in full
    and "coverLower = coverLower" in full
    and "coverPalette.top.copy(alpha = immersivePlayerBackdropTintAlpha" in backdrop
    and "coverLower.copy(alpha = immersivePlayerBackdropTintAlpha" in backdrop,
    "the shared player/queue backdrop must continue to use the sampled, animated cover palette rather than a fixed card color",
)
require(
    "appleUsesTheSharedQueueSheetAndTheOldQueueIsGone" in test,
    "JVM regression tests must lock the shared queue sheet for every style",
)
print("[player-lyric-queue-surface-guard] PASS: shared queue sheet and animated cover backdrop remain intact; lyric actions use the compact sheet")
PY
