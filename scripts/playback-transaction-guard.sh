#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTROLLER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt"
GATE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/PlaybackTransactionGate.kt"
SNAPSHOT_POLICY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/playback/PlaybackSnapshotPolicy.kt"
SNAPSHOT_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/PlaybackSnapshotPolicyTest.kt"
python3 - "$CONTROLLER" "$GATE" "$SNAPSHOT_POLICY" "$SNAPSHOT_TEST" <<'PY'
import re
import sys

controller = open(sys.argv[1], encoding="utf-8").read()
gate = open(sys.argv[2], encoding="utf-8").read()
snapshot_policy = open(sys.argv[3], encoding="utf-8").read()
snapshot_test = open(sys.argv[4], encoding="utf-8").read()

def fail(message):
    print(f"[playback-transaction-guard] FAIL: {message}")
    raise SystemExit(1)

# Tie the test contract to the actual transaction identity implementation.
for token in (
    "transaction.generation == generation",
    "transaction.songKey == songKey",
    "current.queueRevision == queueRevision",
):
    if token not in gate:
        fail(f"transaction validity no longer checks {token}")

start = re.search(r"suspend\s+fun\s+resolveAndPlay\s*\(", controller)
if start is None:
    fail("PlaybackController.resolveAndPlay was not found")
end = controller.find("\n    /** 播放切歌调用点顶层异常", start.end())
if end < 0:
    fail("resolveAndPlay boundary was not found")
play = re.sub(r'//[^\n]*', '', controller[start.start():end])

normalized = re.sub(r"\s+", " ", play)
if "fun requestIsCurrent(): Boolean = synchronized(playbackTransactionGate) { playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision) && PlayerService.currentGeneration() == serviceGeneration" not in normalized:
    fail("foreground resolver callback is not bound to the active song and queue revision")
if "requestStillCurrent = ::requestIsCurrent" not in play:
    fail("the primary UrlResolver request is not given the live transaction check")
if play.count("UrlResolver.resolveResult(") != 1 or play.count("playbackTransactionGate.begin(") != 1 or "forcedViaStale" in play or "retryTxn" in play:
    fail("STALE must not resurrect a request through retry transactions or bypass guards")
stale_terminal = "if (result.failure == ResolveFailure.STALE_REQUEST) return@resolve"
result_at = play.find("val pair = result.url to result.quality")
stale_at = play.find(stale_terminal, result_at)
failure_at = play.find("if (!result.isSuccess)", result_at)
if not (0 <= result_at < stale_at < failure_at) or "if (!requestIsCurrent()) return@resolve" not in play[result_at:stale_at]:
    fail("STALE and obsolete results must terminate before failure or playback commits")

commit_guard = "if (!requestIsCurrent()) return@resolve"
first_guard = play.find(commit_guard)
normal_commits = (
    "song.resolvedUrl = playUrl",
    "song.actualQuality = q",
    "PlayerService.playUrl(context, playUrl, song, startMs, sourcePlatform = result.platform)",
)
if first_guard < 0 or any(play.find(commit) < first_guard for commit in normal_commits):
    fail("normal playback state/media commits must follow the live transaction guard")
if "if (!requestIsCurrent()) return@resolve val playUrl = ApiClient.playableUrl(raw)" not in normalized:
    fail("normal media preparation must recheck identity after suspending failure/cache work")
if "if (localHit != null && requestIsCurrent()) {" not in play:
    fail("local media must use the same live identity before playback commits")

ext_at = play.find("val extOf = MediaDecryptCache.encryptedExtOf(raw)")
lease_prepare = play.find("MediaDecryptCache.prepareLeased(", ext_at)
lease_owner = play.find("pendingCacheLease = mediaLease", ext_at)
plain_guard = play.find(commit_guard, ext_at)
plain_url = play.find("song.resolvedUrl = plainPath", ext_at)
plain_play = play.find("PlayerService.playUrl(context, plainPath, song, startMs, mediaLease, result.platform)", ext_at)
lease_transfer = play.find("pendingCacheLease = null", plain_play)
if ext_at < 0 or min(lease_prepare, lease_owner, plain_guard, plain_url, plain_play, lease_transfer) < 0 or not (
    ext_at < lease_prepare < lease_owner < plain_guard < plain_url < plain_play < lease_transfer
):
    fail("decrypted media must be revalidated before URL/player commits, with its lease transferred only after prepare")
if "pendingCacheLease?.close()" not in play:
    fail("an untransferred decrypted-media lease must be closed on stale, failed, or cancelled transactions")
if ".also { pendingCacheLease = it }" not in play[lease_prepare:lease_owner]:
    fail("lease ownership must be captured inside the IO producer before cancellation handoff")

if "if (synchronized(playbackTransactionGate) { playbackTransactionGate.isCurrent(transaction, currentSong?.key(), queueRevision) }) { resolving = false status = \"\" }" not in normalized:
    fail("a stale resolver must not clear the newer transaction's resolving state")
for fragment in (
    "): Unit = coroutineScope resolve@{",
    "activeResolveJob?.takeIf { it !== thisResolveJob }?.cancel(",
    "scriptRuntime.cancelPlayUrlRequests()",
    # ACCEPT6-FREEZE 重钉（等严更严）：进度写仍由同一组所有权条件
    # （job 存活 && resolving && requestIsCurrent）把门，只是先经合并器节流再落 status。
    "onProgress = { label -> if (thisResolveJob?.isActive == true && resolving && requestIsCurrent()) { progressCoalescer.offer(label)?.let { status = it } } }",
    "finally { pendingCacheLease?.close() PlayerService.releaseNaturalEndHold(naturalEndHoldToken) finishIfCurrent() }",
    "if (activeResolveJob === thisResolveJob) activeResolveJob = null",
):
    if fragment not in normalized:
        fail(f"structured request ownership/cleanup is missing: {fragment}")

snapshot_start = controller.find("fun onSnapshot(snapshot: PlaybackSnapshot) {")
if snapshot_start < 0:
    fail("PlaybackController.onSnapshot was not found")
snapshot_end = controller.find("\n    /** 队列唯一命令入口", snapshot_start)
if snapshot_end < 0:
    fail("onSnapshot boundary was not found")
on_snapshot = controller[snapshot_start:snapshot_end]
for token in (
    "PlaybackSnapshotPolicy.decide(",
    "currentSongKey = currentSong?.key()",
    "snapshotSongKey = snapshot.song?.key()",
    "acceptedSnapshotGeneration = acceptedSnapshotGeneration",
    "snapshotGeneration = snapshot.generation",
    "controllerQueueIsNotEmpty = queue.isNotEmpty()",
    "when (snapshotDecision.songAction)",
    "SnapshotSongAction.HYDRATE_EMPTY ->",
    "SnapshotSongAction.ACCEPT_SERVICE_CHANGE ->",
    "SnapshotSongAction.IGNORE_SERVICE_CHANGE ->",
    "if (snapshotDecision.preserveControllerQueue)",
):
    if token not in on_snapshot:
        fail(f"onSnapshot no longer routes through the tested snapshot decision: {token}")

for token in (
    "snapshotSongKey == null || snapshotSongKey == currentSongKey",
    "currentSongKey == null && !resolving",
    "!resolving && snapshotGeneration >= acceptedSnapshotGeneration",
    "resolving && controllerQueueIsNotEmpty",
):
    if token not in snapshot_policy:
        fail(f"snapshot policy no longer checks {token}")

for token in (
    "stale A snapshot during B resolution preserves B song and queue",
    "SnapshotSongAction.IGNORE_SERVICE_CHANGE",
    "decision.preserveControllerQueue",
):
    if token not in snapshot_test:
        fail(f"JVM regression test no longer asserts {token}")

print("[playback-transaction-guard] PASS: transaction callbacks and onSnapshot song/queue commits use tested pure decision boundaries")
PY
