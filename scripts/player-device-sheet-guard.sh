#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player"
sheet = (base / "PlayerDeviceSheet.kt").read_text(encoding="utf-8")
test_path = root / "app/src/test/java/xyz/cncnlp/soundwave/ui/player/PlayerDeviceSheetStyleTest.kt"
test = test_path.read_text(encoding="utf-8") if test_path.is_file() else ""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[player-device-sheet-guard] FAIL: {message}")


require(bool(sheet) and bool(test), "the device sheet and its direct style tests must exist")
# r656：设备抽屉改用统一设计抽屉外壳（DesignSheetDialog / DesignSheetPanel），不再自带 Surface。
require("Surface(" not in sheet and "DesignSheetDialog(onDismiss = onDismissRequest)" in sheet and 'DesignSheetHeader("Audio output", "播放设备")' in sheet,
        "the device sheet must use the shared design sheet shell; do not add filled option surfaces")
require("PLAYER_DEVICE_MIN_TOUCH_TARGET_DP = 48" in sheet,
        "interactive device options must preserve at least 48dp targets")
require(sheet.count("heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).clickable") == 4,
        "local, system-output, DLNA, and remote-route option rows must keep 48dp touch targets")
require(".clickable { onRefreshDlna() }.padding(horizontal = 8.dp).heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp)" in sheet,
        "refresh action must keep a minimum-height touch target")
require(".clickable { onStopDlna() }.padding(horizontal = 8.dp).heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp)" in sheet,
        "DLNA disconnect action must keep a minimum-height touch target")
require("route.deviceType" in sheet and "outputSnapshot.isSelected(route)" in sheet,
        "system route glyph and selected marker must derive from the real MediaRouter route")
require("outputSnapshot.isDefaultRouteSelected()" in sheet and "playerDlnaOptionStyle(isActive)" in sheet,
        "local and DLNA current-output indicators must keep their existing state sources")
for token in (
    "MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> PlayerDeviceGlyph.BLUETOOTH",
    "MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> PlayerDeviceGlyph.SPEAKER",
    "MediaRouter.RouteInfo.DEVICE_TYPE_TV -> PlayerDeviceGlyph.TELEVISION",
    "else -> PlayerDeviceGlyph.REMOTE_OUTPUT",
    "PlayerDeviceColorRole.PRIMARY -> MaterialTheme.colorScheme.primary",
    "PlayerDeviceColorRole.SECONDARY -> MaterialTheme.colorScheme.secondary",
    "PlayerDeviceColorRole.ON_SURFACE_VARIANT -> MaterialTheme.colorScheme.onSurfaceVariant",
):
    require(token in sheet, f"semantic route icon/color mapping is missing: {token}")
for callback in (
    "onSelectLocalRoute)", "onSelectSystemRoute(route)", "onOpenSystemOutput()",
    "onRefreshDlna()", "onCastDlna(device)", "onPauseDlna()", "onResumeDlna()", "onStopDlna()",
):
    require(callback in sheet, f"existing option callback must remain: {callback}")
require("background(" not in "\n".join(line for line in sheet.splitlines() if "Row(Modifier.fillMaxWidth().heightIn" in line),
        "option rows must remain transparent")
require("no per-route connecting/error state" in sheet,
        "the sheet must document that it does not invent status absent from its device models")
require('"播放中 · " else "已暂停 · "' in sheet and "session?.isPlaying" in sheet,
        "DLNA playback and paused states must remain visible independent of color")
for token in (
    "systemRouteGlyphFollowsAndroidDeviceTypeAndSelectedState",
    "activeDlnaHasAnExplicitNonColorStatusAndSemanticColorRole",
    "interactiveDeviceOptionsKeepAtLeastFortyEightDpTargets",
):
    require(token in test, f"direct unit-test coverage is missing: {token}")
print("[player-device-sheet-guard] PASS: transparent option rows, semantic Android route types, explicit current state, existing callbacks, and 48dp targets")
PY
