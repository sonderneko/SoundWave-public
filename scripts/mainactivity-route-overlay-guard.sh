#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
main = (base / "MainActivity.kt").read_text()
nav_events = (base / "MainNavigationEvents.kt").read_text()
presentation = (base / "MiniPlayerPresentation.kt").read_text()
route = (base / "ui/route/AppRootMainTabRouteRenderer.kt").read_text()
app_route = (base / "AppRoute.kt").read_text()


def require(ok, message):
    if not ok:
        raise SystemExit(f"[mainactivity-route-overlay-guard] FAIL: {message}")


content_start = main.rfind(") { pad ->")
content_end = main.rfind("} // Scaffold content lambda")
require(content_start >= 0 and content_end > content_start,
        "Scaffold route content boundary not found")
content = main[content_start:content_end]
route_calls = [
    content.rfind("AppRootDetailOverlay()"),
    content.rfind("AppRootHomeLayer()"),
    content.rfind("AppRootMainTabRouteRenderer("),
]
require(all(position >= 0 for position in route_calls),
        "detail/home/main-tab route layer call is missing")
require(route_calls == sorted(route_calls),
        "detail, home and main-tab route layer call order changed")
require("route = AppRoute.MainTab(if (tab == Tab.PLAYER) prevTab else tab)" in content,
        "player route must keep the previous content tab underneath")

player_layer = main.rfind("AppRootFullPlayerLayer()")
dialog_layer = main.rfind("AppRootOverlayDialogs()")
require(content_end < player_layer < dialog_layer,
        "full player must remain above Scaffold content and global dialogs above the player")
require(main.count("AppRootFullPlayerLayer()") == 2,
        "expected one local player-layer declaration and one root call")
require(main.count("AppRootOverlayDialogs()") == 2,
        "expected one local dialog-layer declaration and one root call")

host_start = main.find("val playerOverlayTransitionActive = playerOverlayState.sheetVisible")
host_end = main.find("val detailTransitionActive", host_start)
require(host_start >= 0 and host_end > host_start,
        "player dock host visibility branch not found")
host_branch = main[host_start:host_end]
require("if (playerOverlayTransitionActive)" in host_branch and
        re.search(r"Spacer\(Modifier\.fillMaxWidth\(\)\.height\(adaptiveDockHostHeight\)\)", host_branch) and
        "val adaptiveDockHostHeight = miniPlayerDockHostHeight(miniDockPresentation)" in main and
        "PLAYER_DOCK_HOST_HEIGHT_DP.dp + (presentation.height - DockMetrics.MiniHeight).coerceAtLeast(0.dp)" in presentation,
        "player overlay must preserve the dock-host measurement (fixed 150dp base plus adaptive font-metric delta)")

back_start = app_route.find("): BackAction = when {")
back_end = app_route.find("else -> BackAction.BackToHome", back_start)
require(back_start >= 0 and back_end > back_start,
        "pure back-action priority reducer not found")
back = app_route[back_start:back_end]
priorities = [
    back.find("tab == Tab.PLAYER -> BackAction.ClosePlayer"),
    back.find("detailOpen -> BackAction.CloseDetail"),
    back.find("settingsSub != null -> BackAction.CloseSettingsSub"),
    back.find("tab == Tab.SEARCH && searchResult != null -> BackAction.ClearSearch"),
]
require(all(position >= 0 for position in priorities) and priorities == sorted(priorities),
        "player/detail/settings/search back priority changed")

handler_start = nav_events.find("fun back(nowMillis: Long)")
handler_end = nav_events.find("fun selectMainTab", handler_start)
require(handler_start >= 0 and handler_end > handler_start,
        "root back-event boundary not found in MainNavigationEvents")
handler = nav_events[handler_start:handler_end]
require("BackHandler { events.back(System.currentTimeMillis()) }" in nav_events and
        "AppRootNavigationBackHandler(navigationEvents)" in main,
        "the root BackHandler must stay wired from AppRoot through AppRootNavigationBackHandler")
download_picker = handler.find("if (state.downloadPickerOpen)")
route_reducer = handler.find("AppRoute.backAction(")
require(download_picker >= 0 and route_reducer > download_picker,
        "download selection must still close before route/back decisions")
require("searchResult, downloadPickSong != null, lastBackAt" in main,
        "navigation snapshot must keep search/download state parent-owned")
# 账号系统去除：登录对话框返回动作已退役，禁止以任何形式回潮。
require("DismissLoginDialog" not in app_route and "DismissLoginDialog" not in nav_events and
        "showLoginDlg" not in main and "showLoginDlg" not in nav_events,
        "retired login-dialog back action must not reappear")

for expected in (
    "Tab.HOME -> Unit",
    "Tab.SEARCH -> searchRoute()",
    "Tab.PLAYLISTS -> playlistsRoute()",
    "Tab.SETTINGS -> settingsRoute()",
    "Tab.PLAYER -> Unit",
):
    require(expected in route, f"main-tab renderer mapping missing: {expected}")
for stateful in ("remember(", "LaunchedEffect(", "SideEffect(", "mutableStateOf("):
    require(stateful not in route,
            f"main-tab route renderer must stay stateless; found {stateful}")

print("[mainactivity-route-overlay-guard] PASS: download overlay back priority, stateless route map, detail/home/main-tab ordering, fixed player host, and full-player/global-dialog stacking")
PY
