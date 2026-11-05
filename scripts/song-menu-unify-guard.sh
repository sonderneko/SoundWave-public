#!/usr/bin/env bash
# MENU-UNIFY 守卫（用户 2026-11-05「统一啊」）：全 App 歌曲菜单只此一家——
# TrackActionsSheet 家族（TrackActionsSheet / DesignTrackActionSheet 委托 / FullPlayerMenuSheet 内容）。
#
# 钉四件事：
# 1. 旧自绘菜单标识 SongActionSheet 不得在 app/src/main 任何文件复活（含注释）。
#    注：「DesignTrackActionSheet」不含子串 "SongActionSheet"，统一委托层不受影响。
# 2. 两个曾用旧实现的入口必须保持归一形态：SongRow 行内弹层走 TrackActionsSheet(、
#    HomePage 排行榜 ⋯ 走 DesignTrackActionSheet(。
# 3. HomePage 统一菜单的「相似 / 不喜欢」接线不得回退成死瓦片：HomePage 须声明
#    onSimilarSong / onDislikeSong 形参，MainActivity 须以既有同口径字面绑定至少两处
#    （搜索页 + 首页各一；归一前各只有搜索页一处）。
# 4. 本守卫须注册进 CI portable gates（与 playback-tick-scope-guard 同口径）。
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path

def read(p):
    return Path(p).read_text(encoding="utf-8")

def require(ok, message):
    if not ok:
        raise SystemExit(f"[song-menu-unify-guard] FAIL: {message}")

src_root = Path("app/src/main/java/xyz/cncnlp/soundwave")
offenders = [str(p) for p in src_root.rglob("*.kt") if "SongActionSheet" in p.read_text(encoding="utf-8")]
require(not offenders, f"retired SongActionSheet must not reappear in main sources: {offenders}")

song_row = read(src_root / "SongRow.kt")
require("TrackActionsSheet(" in song_row,
        "SongRow's overflow menu must delegate to the unified TrackActionsSheet")

home = read(src_root / "HomePage.kt")
require("DesignTrackActionSheet(" in home and "rankMenuSong" in home,
        "the home chart overflow menu must use the unified DesignTrackActionSheet")
require("onSimilarSong: (Song) -> Unit" in home and "onDislikeSong: (Song) -> Unit" in home,
        "HomePage must keep the unified menu's similar/dislike wiring parameters")

main = read(src_root / "MainActivity.kt")
require(main.count("onSimilarSong = { s -> scope.launch { playSimilarSong(s) } }") >= 2,
        "MainActivity must bind onSimilarSong for both search and home (unified menu tiles must not be dead)")
require(main.count("onDislikeSong = { s -> DislikeStore.mark(context, s)") >= 2,
        "MainActivity must bind onDislikeSong for both search and home (unified menu tiles must not be dead)")

workflow = read(".github/workflows/build-apk.yml")
require("scripts/song-menu-unify-guard.sh" in workflow,
        "the song menu unify guard must run in portable quality gates")

print("[song-menu-unify-guard] PASS: single song-menu implementation (TrackActionsSheet family), SongRow + home chart delegated, similar/dislike wired at search + home, CI registration")
PY
