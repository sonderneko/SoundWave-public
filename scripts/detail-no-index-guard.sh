#!/usr/bin/env bash
# r657 第三轮（用户 2026-10-18 再次确认）：所有详情曲目列表（歌单 / 专辑 / 每日推荐 / 我的喜欢 / 沉浸样式）不显示曲目编号。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
F="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt"
grep -Fq 'internal const val DetailTrackListShowsIndex = false' "$F" || { echo "[detail-no-index-guard] FAIL: DetailTrackListShowsIndex must be false"; exit 1; }
n=$(grep -c 'showIndex = DetailTrackListShowsIndex' "$F")
[ "$n" -ge 2 ] || { echo "[detail-no-index-guard] FAIL: TodayTrackRow and SongRow detail paths must use DetailTrackListShowsIndex ($n)"; exit 1; }
if grep -nE 'showIndex = (true|\(\(isTodayRec)' "$F"; then echo "[detail-no-index-guard] FAIL: detail list re-enabled track numbers"; exit 1; fi
echo "[detail-no-index-guard] PASS: detail track lists hide track numbers"
