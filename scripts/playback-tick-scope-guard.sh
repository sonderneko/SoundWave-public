#!/usr/bin/env bash
# BATCH2 反馈 F4 守卫：播放 tick（Service 每 400ms 发布 PlaybackSnapshot → PlaybackController
# .onSnapshot 推进 progressState / currentTimeState）的高频读取不得回到页面级作用域。
#
# 钉三件事：
# 1. FullPlayer：progress / currentTime 的委托读只准出现在 PlayerBottomControlChrome 函数体
#    与 IsolatedSection 的 content lambda 内；函数主体顶层区（FullPlayer 起始 →
#    PlayerBottomControlChrome 之前）出现即失败（那正是修复前的病灶位置）。
# 2. MiniPlayer：主体不得再出现 `val progress by progressState` 顶层委托读；环形进度必须
#    在 drawBehind 绘制阶段读 progressState.value（tick 只重绘不重组）。
# 3. PlaybackController：progress / currentTime 必须保持独立的分字段 State（不得把进度
#    并回整快照对象让相等性守卫失效）。
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path

def read(p):
    return Path(p).read_text(encoding="utf-8")

def require(ok, message):
    if not ok:
        raise SystemExit(f"[playback-tick-scope-guard] FAIL: {message}")

full = read("app/src/main/java/xyz/cncnlp/soundwave/FullPlayer.kt").splitlines()
chrome_at = next(i for i, l in enumerate(full) if "fun PlayerBottomControlChrome(" in l)
iso_at = next(i for i, l in enumerate(full) if l.strip() == "IsolatedSection {")
reads = [i for i, l in enumerate(full)
         if "val progress by progressState" in l or "val currentTime by currentTimeState" in l]
require(len(reads) >= 4,
        "FullPlayer must keep the sunk progress/currentTime reads (chrome body + IsolatedSection lambda)")
require(all((chrome_at < i < iso_at) or i > iso_at for i in reads),
        "FullPlayer progress/currentTime reads must stay inside PlayerBottomControlChrome or the "
        "IsolatedSection lambda; a top-level read re-subscribes the whole player body to the 400ms tick")

mini_lines = read("app/src/main/java/xyz/cncnlp/soundwave/MiniPlayer.kt").splitlines()
require(not any("val progress by progressState" in l for l in mini_lines),
        "MiniPlayer must not read progressState at body top level (BATCH2 F4: tick recomposed the whole bar)")
draw_at = next((i for i, l in enumerate(mini_lines) if ".drawBehind {" in l), -1)
require(draw_at >= 0 and any("progressState.value" in l for l in mini_lines[draw_at:draw_at + 6]),
        "MiniPlayer progress ring must read progressState.value inside drawBehind (draw-phase read)")

controller = read("app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt")
require("val progressState" in controller and "val currentTimeState" in controller,
        "PlaybackController must keep progress/currentTime as separate per-field states")

workflow = read(".github/workflows/build-apk.yml")
require("scripts/playback-tick-scope-guard.sh" in workflow,
        "the playback tick scope guard must run in portable quality gates")

print("[playback-tick-scope-guard] PASS: tick reads sunk (FullPlayer chrome + IsolatedSection lambda), MiniPlayer draw-phase progress, per-field playback states, CI registration")
PY
