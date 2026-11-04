#!/usr/bin/env bash
# r657 P0-5：正式包日志收敛——带歌曲 key 的 Log 必须 BuildConfig.DEBUG 守卫；R8 剥离 Log.v/d/i。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re, sys
root = Path(sys.argv[1])
SRC = root / "app/src/main/java/xyz/cncnlp/soundwave"
checked = {name: (SRC / name).read_text(encoding="utf-8") for name in ("PlaybackController.kt", "PlayerService.kt", "source/ScriptSourceAdapter.kt")}
rules = (root / "app/proguard-rules.pro").read_text(encoding="utf-8")
def require(ok, msg):
    if not ok:
        raise SystemExit(f"[release-log-hygiene-guard] FAIL: {msg}")
for name, text in checked.items():
    for no, line in enumerate(text.splitlines(), 1):
        if re.search(r"\bLog\.[vdiwe]\(", line) and ("key()" in line or "e.message" in line):
            guarded, _, release = line.partition(" else ")
            require(guarded.lstrip().startswith("if (BuildConfig.DEBUG)") and "key()" not in release and "e.message" not in release,
                    f"{name}:{no} logs a song key / message outside a BuildConfig.DEBUG guard")
rule = re.search(r"-assumenosideeffects\s+class\s+android\.util\.Log\s*\{([^}]*)\}", rules)
require(rule is not None, "proguard-rules.pro must declare -assumenosideeffects for android.util.Log")
body = rule.group(1)
for level in ("v", "d", "i"):
    require(re.search(rf"\b{level}\(\.\.\.\)", body) is not None, f"Log.{level} must be stripped in release")
for level in ("w", "e"):
    require(re.search(rf"\b{level}\(\.\.\.\)", body) is None, f"Log.{level} must be kept in release")
print("[release-log-hygiene-guard] PASS: song-key logs are debug-only and release strips Log.v/d/i")
PY
