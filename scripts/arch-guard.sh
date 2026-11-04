#!/usr/bin/env bash
#
# arch-guard.sh - architecture guard (P0-2).
#
#   R1 (enforced): MainActivity.kt must not grow past the committed cap.
#   R2 (enforced): legacy PlayerService accessor references outside
#                  PlayerService.kt must not exceed the committed cap
#                  (the cap only ratchets DOWN after refactors).
#   R3 (advisory): new UI files belong in a ui/ subpackage, not the flat
#                  root package. Warnings only; never fails the build.
#   R4-R6 (enforced): player-entry, snapshot/statistics ownership, and feed
#                     State backing invariants; failures affect the exit code.
#
# Caps are read from app/arch.json (mainActivityLoc, legacyAccessorRefs).
# Exit code is 0 only when all enforced gates pass; R3 remains advisory.
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
ARCH_JSON="$REPO_ROOT/app/arch.json"
MAIN_ACTIVITY="$REPO_ROOT/app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt"
SRC_ROOT="$REPO_ROOT/app/src/main/java"
ROOT_PKG="$SRC_ROOT/xyz/cncnlp/soundwave"
REL_ROOT_PKG="app/src/main/java/xyz/cncnlp/soundwave"

LEGACY_ACCESSORS='getPosition|getDuration|isPlayingNow|current|queueSnapshot|queuePos|mode'
# 锚定调用括号，避免误匹配 currentKey/currentGeneration 等前缀派生访问器
LEGACY_ACCESSOR_CALL='(getPosition|getDuration|isPlayingNow|current|queueSnapshot|queuePos|mode)\s*\('

fail=0

# json_number <key>: prints the first bare-integer value for the key.
json_number() {
  grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[0-9]+" "$ARCH_JSON" 2>/dev/null \
    | grep -oE '[0-9]+$' | head -n 1
}

if [ ! -f "$ARCH_JSON" ]; then
  echo "[arch-guard] FATAL: caps file not found: $ARCH_JSON"
  exit 1
fi

cap_loc="$(json_number mainActivityLoc)"
cap_refs="$(json_number legacyAccessorRefs)"
for cap in "$cap_loc" "$cap_refs"; do
  case "$cap" in
    ''|*[!0-9]*)
      echo "[arch-guard] FATAL: missing or non-numeric cap in $ARCH_JSON"
      exit 1
      ;;
  esac
done

# --- R1: MainActivity.kt LOC ratchet -----------------------------------------
if [ ! -r "$MAIN_ACTIVITY" ]; then
  echo "[arch-guard] R1 FAIL: cannot read $MAIN_ACTIVITY (cap $cap_loc) - no new logic in MainActivity.kt"
  exit 1
fi

cur_loc="$(wc -l < "$MAIN_ACTIVITY" | tr -d ' ')"
if [ "$cur_loc" -gt "$cap_loc" ]; then
  echo "[arch-guard] R1 FAIL: $MAIN_ACTIVITY is $cur_loc lines (cap $cap_loc) - no new logic in MainActivity.kt"
  fail=1
else
  echo "[arch-guard] R1 PASS: $MAIN_ACTIVITY $cur_loc/$cap_loc LOC"
fi

# --- R2: legacy PlayerService accessor refs ratchet --------------------------
count_legacy_refs() {
  if command -v rg >/dev/null 2>&1; then
    rg -n "PlayerService\.$LEGACY_ACCESSOR_CALL" "$SRC_ROOT" --glob '!**/PlayerService.kt' 2>/dev/null | wc -l
  else
    grep -rnE "PlayerService\.$LEGACY_ACCESSOR_CALL" "$SRC_ROOT" \
      --include='*.kt' --exclude='PlayerService.kt' 2>/dev/null | wc -l
  fi
}

cur_refs="$(count_legacy_refs | tr -d ' ')"
if [ "$cur_refs" -gt "$cap_refs" ]; then
  echo "[arch-guard] R2 FAIL: $cur_refs legacy PlayerService accessor reference(s) outside PlayerService.kt (cap $cap_refs) - refactor to the new accessors; this cap only ratchets down"
  fail=1
else
  echo "[arch-guard] R2 PASS: legacy PlayerService accessor refs outside PlayerService.kt $cur_refs/$cap_refs"
fi

# --- R3 (advisory): new files belong under a ui/ subpackage ------------------
new_files=()
if command -v git >/dev/null 2>&1 && git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  # 1) Uncommitted new files in the working tree.
  while IFS= read -r line; do
    case "$line" in
      "?? "*) new_files+=("${line#\?\? }") ;;
    esac
  done < <(git -C "$REPO_ROOT" status --porcelain --untracked-files=all -- "$ROOT_PKG")
  # 2) Files added by the most recent commit (only when history is available).
  if git -C "$REPO_ROOT" rev-parse --verify -q HEAD^ >/dev/null 2>&1; then
    while IFS= read -r f; do
      [ -n "$f" ] && new_files+=("$f")
    done < <(git -C "$REPO_ROOT" diff-tree --no-commit-id --name-only --diff-filter=A -r HEAD^ HEAD -- "$ROOT_PKG")
  fi
fi

flat_new=()
for f in "${new_files[@]:-}"; do
  f="${f#$REPO_ROOT/}"                                  # normalize to repo-root-relative
  # The FullPlayer menu extraction is explicitly kept in the host package.
  case "$f" in
    "$REL_ROOT_PKG/PlayerMenuSheet.kt") continue ;;
  esac
  case "$f" in
    "$REL_ROOT_PKG"/*/*) continue ;;                    # already in a subpackage
    "$REL_ROOT_PKG"/*.kt|"$REL_ROOT_PKG"/*.java) ;;
    *) continue ;;
  esac
  case " ${flat_new[*]} " in
    *" $f "*) continue ;;
  esac
  flat_new+=("$f")
done

if [ "${#flat_new[@]}" -gt 0 ]; then
  echo "[arch-guard] R3 WARN (advisory): new file(s) in the flat root package should live under a ui/ subpackage:"
  printf '      %s\n' "${flat_new[@]}"
else
  echo "[arch-guard] R3 PASS (advisory): no new files in the flat root package"
fi

# --- R4: detail mini opens the existing player without resolving/restarting audio ---
if python3 - "$MAIN_ACTIVITY" <<'PY'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
detail = re.search(
    r'if\s*\(detailDockOnlyMini\s*&&\s*currentSong\s*!=\s*null\)\s*\{(?P<body>.*?)\n[ ]{20}\}',
    source,
    re.S,
)
if detail is None:
    print("[arch-guard] R4 FAIL: detail-only mini entry branch not found")
    sys.exit(1)
body = detail.group("body")
if "DetailTransitionMiniDock(" in body:
    # After extraction the same parent-owned navigation callback is passed into
    # the stateless view; inspect that prop instead of requiring an inline child.
    click = re.search(r'onOpenPlayer\s*=\s*\{([^}]*)\}', body)
else:
    click = re.search(r'onClick\s*=\s*\{([^}]*)\}', body)
if click is None:
    print("[arch-guard] R4 FAIL: detail mini player click handler not found")
    sys.exit(1)
handler = click.group(1)
if "tab = Tab.PLAYER" not in handler or any(token in handler for token in ("resolveAndPlay", "playSong", "PlayerService")):
    print("[arch-guard] R4 FAIL: detail mini click must enter the player without restarting playback")
    sys.exit(1)
print("[arch-guard] R4 PASS: detail mini opens the existing player without audio restart")
PY
then
  :
else
  fail=1
fi

# --- R5: periodic activity polling must not duplicate snapshot progress writes -
PLAYBACK_CONTROLLER="$ROOT_PKG/PlaybackController.kt"
if python3 - "$MAIN_ACTIVITY" "$PLAYBACK_CONTROLLER" <<'PY'
import re
import sys

activity = open(sys.argv[1], encoding="utf-8").read()
controller = open(sys.argv[2], encoding="utf-8").read()
effect = re.search(
    r"LaunchedEffect\s*\(\s*currentSong\s*,\s*playing\s*,\s*lyrics\s*\)\s*\{",
    activity,
)
if effect is None:
    print("[arch-guard] R5 FAIL: playback polling effect not found")
    sys.exit(1)
end = activity.find("delay(350)", effect.end())
if end < 0:
    print("[arch-guard] R5 FAIL: 350ms playback polling boundary not found")
    sys.exit(1)
polling = activity[effect.end():end]
duplicate = re.search(r"(?m)^\s*(?:currentTime|duration|progress)\s*=", polling)
if duplicate:
    print("[arch-guard] R5 FAIL: polling effect writes progress fields already owned by onSnapshot")
    sys.exit(1)
# r658c D#2: listening statistics moved to PlayerService's media tick (counts even without UI).
if "LyricParser.indexAt(lyrics, pos)" not in polling:
    print("[arch-guard] R5 FAIL: lyric position update disappeared")
    sys.exit(1)
if "addDuration" in polling or "markPlay" in polling:
    print("[arch-guard] R5 FAIL: UI polling must not write listening statistics (service owns it)")
    sys.exit(1)
service_src = open("app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt", encoding="utf-8").read()
if "recordListenStats(SystemClock.elapsedRealtime(), currentSong, p.isPlaying)" not in service_src or "data.addDuration(it.song, it.seconds)" not in service_src:
    print("[arch-guard] R5 FAIL: service-owned listening statistics disappeared")
    sys.exit(1)
snapshot = re.search(r"fun onSnapshot\(snapshot: PlaybackSnapshot\)\s*\{", controller)
if snapshot is None:
    print("[arch-guard] R5 FAIL: PlaybackController.onSnapshot not found")
    sys.exit(1)
next_method = re.search(r"\n\s*(?:private\s+)?fun\s+", controller[snapshot.end():])
end_snapshot = snapshot.end() + next_method.start() if next_method else len(controller)
on_snapshot = controller[snapshot.end():end_snapshot]
required = (
    r"currentTime\s*=\s*snapshot\.positionMs\s*/\s*1000f",
    r"duration\s*=\s*snapshot\.durationMs\s*/\s*1000f",
    r"progress\s*=\s*if\s*\(snapshot\.durationMs\s*>\s*0\)",
    r"snapshot\.positionMs\.toFloat\(\)\s*/\s*snapshot\.durationMs\.toFloat\(\)",
)
if not all(re.search(pattern, on_snapshot) for pattern in required):
    print("[arch-guard] R5 FAIL: snapshot no longer maps position, duration, and progress")
    sys.exit(1)
print("[arch-guard] R5 PASS: progress fields use snapshot updates; stats and lyric polling remain")
PY
then
  :
else
  fail=1
fi

# --- R6: feed State projections must keep writes on their mutable backings ---
FEED_CONTROLLER="$ROOT_PKG/HomeFeedController.kt"
if python3 - "$FEED_CONTROLLER" <<'PY'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
state_backings = dict(re.findall(
    r"(?m)^\s*val\s+(\w+)\s*:\s*State<.+>\s*=\s*(\w+)\s*$",
    source,
))
setters = list(re.finditer(
    r"(?m)^\s*private\s+var\s+(\w+)\s*:[^\n]*\n"
    r"\s*get\(\)\s*=\s*(\w+)\.value\s*\n"
    r"\s*set\(v\)\s*\{\s*([^{}]+?)\s*\}",
    source,
))
if not state_backings or not setters:
    print("[arch-guard] R6 FAIL: feed State backings or property setters not found")
    sys.exit(1)
for match in setters:
    property_name, getter, body = match.groups()
    writes = set(re.findall(r"\b(\w+)\.value\s*=", body))
    expected = state_backings.get(getter, getter)
    if expected not in writes:
        print(
            f"[arch-guard] R6 FAIL: {property_name} setter must write through "
            f"{expected}.value, not {sorted(writes)}"
        )
        sys.exit(1)
    if getter in state_backings and getter in writes:
        print(f"[arch-guard] R6 FAIL: {property_name} writes through read-only {getter}")
        sys.exit(1)
print(f"[arch-guard] R6 PASS: {len(setters)} feed setters write through their mutable owners")
PY
then
  :
else
  fail=1
fi

echo "[arch-guard] done: $([ "$fail" -eq 0 ] && echo ALL GATES PASS || echo GATE FAILURES PRESENT)"
exit "$fail"
