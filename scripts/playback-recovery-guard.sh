#!/usr/bin/env bash
# r657 播放可靠性守卫：自动续播 / 断流恢复 / 唤醒锁 / 取链超时 / 下载续传不得回退。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re, sys
root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
svc = (base / "PlayerService.kt").read_text(encoding="utf-8")
ctl = (base / "PlaybackController.kt").read_text(encoding="utf-8")
api = (base / "ApiClient.kt").read_text(encoding="utf-8")
dl = (base / "DownloadHelper.kt").read_text(encoding="utf-8")
worker = (base / "DownloadScheduler.kt").read_text(encoding="utf-8")

def require(ok, msg):
    if not ok:
        raise SystemExit(f"[playback-recovery-guard] FAIL: {msg}")

# 1. 唤醒锁：播放器缓冲期 + 曲间过渡
require("setWakeMode(C.WAKE_MODE_NETWORK)" in svc, "ExoPlayer must hold WAKE_MODE_NETWORK while buffering/playing")
require("PARTIAL_WAKE_LOCK" in svc and "fun holdTransitionWake()" in svc, "transition wake lock missing")
ended = re.search(r"fun handleTrackEnded\(\)\s*\{(?P<b>.*?)\n        \}\n", svc, re.S)
require(ended and "holdTransitionWake()" in ended.group("b"), "handleTrackEnded must hold the transition wake lock")
require("PlaybackLoadErrorPolicy()" in svc, "custom load-error policy must be installed on media source factories")

# 2. 无界面恢复：onError 为空时 Service 自己恢复；无脚本也要试官方直连
# Scope to the main listener; prepared-player errors have a different contract.
listener = svc[svc.index("    fun attachListener(p: ExoPlayer)"):svc.index("    private fun createChannel(")] if "    private fun createChannel(" in svc else svc[svc.index("    fun attachListener(p: ExoPlayer)"):]
err = re.search(r"override fun onPlayerError\(error: PlaybackException\)\s*\{(?P<b>.*?)\n            \}\n", listener, re.S)
require(err is not None, "main player onPlayerError not found")
error_body = err.group("b")
require(re.search(r"if\s*\(\s*!isCurrentTransaction\(\)\s*\|\|\s*isPlaybackInterrupted\(\)\s*\)\s*return", error_body),
        "main error consumer must reject stale and interrupted playback")
require(re.search(r"if\s*\(handler != null\)\s*handler.invoke\(failure\)\s*else\s*recoverWithoutUi\(failure\)", error_body),
        "main error must dispatch to foreground consumer or headless recovery")
require(re.search(r"val failure = PlaybackFailure\(listenerSongKey, listenerGeneration,.*failedAtMs, failedDurationMs\)", error_body),
        "main error must retain transaction identity and resume progress")
require("positionMs" in svc and "val durationMs: Long = 0L" in svc, "PlaybackFailure must carry position/duration for resume")
fallback = re.search(r"private fun resolveInService\((?P<b>.*?)\n    \}\n", svc, re.S)
require(fallback is not None, "resolveInService missing")
require("NO_ENABLED_SCRIPT" not in fallback.group("b"), "headless resolve must not bail out before official/direct resolution when no script is enabled")
require("recoveryPolicy.onResolveFailure" in fallback.group("b"), "headless resolve failure must skip/stop through recoveryPolicy")
require("prefetchNextWithoutUi(" in svc, "headless prefetch of the next track missing")

# 3. 前台恢复：续播进度 + 自动跳过
hpf = re.search(r"fun handlePlaybackFailure\((?P<b>.*?)\n    \}\n", ctl, re.S)
require(hpf and "recoveryPolicy.onPlaybackError" in hpf.group("b") and "startMs = decision.resumeAtMs" in hpf.group("b"),
        "foreground failure recovery must use recoveryPolicy and resume from the failure position")
require("autoSkipOnFailure = delta > 0" in ctl, "forward playDelta must auto-skip unresolvable tracks")

# 4. （ACCEPT5 删除兜底棒退役）服务端取链 officialUrl 已随 /music/url 兜底整条
# 删除，其「有界 resolveClient 短超时」钉子对象不复存在——本节按删除合同退役，
# 其余各节的行为钉子不受影响。

# 5. 下载续传必须校验来源指纹；HTTP 4xx 清 URL 缓存
require("DownloadResumePolicy.resumeOffset(" in dl, "download resume must verify the .part fingerprint")
require("DownloadHttpStatusException" in worker and "clearSongCache(song, ownerContext)" in worker, "download worker must drop stale URLs before retry")
print("[playback-recovery-guard] PASS")
PY
