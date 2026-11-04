#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
today = (base / "ui/today/TodayHeaderExtraActions.kt").read_text(encoding="utf-8")
extra = (base / "ExtraPages.kt").read_text(encoding="utf-8")
probe = (base / "ScriptProbeScheduler.kt").read_text(encoding="utf-8")
runtime = (base / "ScriptRuntime.kt").read_text(encoding="utf-8")
lanes = (base / "script/ScriptEvaluationLanes.kt").read_text(encoding="utf-8")
resolver = (base / "UrlResolver.kt").read_text(encoding="utf-8")
download = (base / "DownloadController.kt").read_text(encoding="utf-8")
playlist = (base / "PlaylistImportChecker.kt").read_text(encoding="utf-8")
startup_probe = (base / "probe/ScriptProbeStartup.kt").read_text(encoding="utf-8")
main_activity = (base / "MainActivity.kt").read_text(encoding="utf-8")
app_http = (base / "AppHttp.kt").read_text(encoding="utf-8")
lane_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/script/ScriptEvaluationLanesTest.kt").read_text(encoding="utf-8")
runtime_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptRuntimeWebViewIsolationTest.kt").read_text(encoding="utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[suspend-cancellation-guard] FAIL: {message}")


def section(source: str, start: str, end: str) -> str:
    begin = source.find(start)
    finish = source.find(end, begin + len(start))
    require(begin >= 0 and finish > begin, f"source boundary missing: {start}")
    return source[begin:finish]


refresh = section(today, "scope.launch {", "refreshAvailable = StylePreferenceStore.refreshAvailable(context)")
require("catch (cancelled: CancellationException)" in refresh and "throw cancelled" in refresh and "finally" in refresh,
        "today recommendation refresh must rethrow cancellation and clear busy in finally")
require("runCatching" not in refresh, "today refresh must not use runCatching around suspend work")

resume = section(extra, "BackupStore.resume(ctx, review)", "recoveryStatus = withContext(NonCancellable + Dispatchers.IO)")
restore = section(extra, "BackupStore.restore(ctx, pending.root)", "recoveryStatus = withContext(NonCancellable + Dispatchers.IO)")
for name, source in (("backup resume", resume), ("backup restore", restore)):
    require("catch (cancelled: CancellationException)" in source and "throw cancelled" in source,
            f"{name} UI coroutine must propagate cancellation")
require("recoveryBusy = false" in resume and extra.count("withContext(NonCancellable + Dispatchers.IO)") >= 2,
        "backup recovery must reset busy and refresh recovery state in a non-cancellable cleanup")

worker = section(probe, "override suspend fun doWork()", "/** 检测完成系统通知")
require("runCatching" not in worker, "periodic probe Worker must not swallow suspend cancellation through runCatching")
require(worker.count("catch (cancelled: CancellationException)") >= 2 and worker.count("throw cancelled") >= 2,
        "periodic probe request and outer Worker catch must propagate cancellation")

get_url = section(runtime, "suspend fun getMusicUrl(", "/** 预热：只 init 不取链 */")
warmup = section(runtime, "suspend fun warmup(", "/**\n     * r658c S")
declared = section(runtime, "suspend fun declaredSources(", "private fun buildJs(")
require("private val evaluationLanes = ScriptEvaluationLanes()" in runtime and "evalMutex" not in runtime,
        "the network-await global evalMutex must be removed")
for name, source in (("getMusicUrl", get_url), ("warmup", warmup), ("declaredSources", declared)):
    require("evaluationLanes.withSlot(scriptSlot)" in source and "evaluationLanes.submit" in source and "def.await()" in source,
            f"{name} must protect a script slot while keeping WebView submission separate")
    require("withContext(NonCancellable)" in source and "releaseRequest(capability" in source,
            f"{name} must release capability-owned work on cancellation")
    require(re.search(r"pendingUrl\.remove\(capability\)\s*releaseRequest\(capability, invalidate = !def\.isCompleted \|\| def\.isCancelled\)", source),
            f"{name} must close the JS capability scope even after its completion callback removed pendingUrl")
require("suspend fun <T> submit(block: () -> T)" in lanes and "Array(stripeCount) { Mutex() }" in lanes,
        "global submit gate must be non-suspending and script-slot locks must be fixed/bounded")
require("differentSlotsProceedWhileSameSlotRemainsSingleFlight" in lane_test,
        "script-slot lane concurrency needs a deterministic fake regression")

for lane_name in ("playResolveLane", "prefetchResolveLane", "probeResolveLane", "downloadResolveLane"):
    require(f"private val {lane_name} = Mutex()" in resolver, f"missing independent Resolver lane: {lane_name}")
for fragment in (
    "val lane = when (ticket.purpose)",
    "ResolveTicket.Purpose.PLAY -> playResolveLane",
    "ResolveTicket.Purpose.PREFETCH -> prefetchResolveLane",
    "ResolveTicket.Purpose.PROBE -> probeResolveLane",
    "ResolveTicket.Purpose.DOWNLOAD -> downloadResolveLane",
):
    require(fragment in resolver, f"purpose-specific Resolver routing is missing: {fragment}")

require("val scriptDispatcher: Dispatcher" in app_http and "maxRequests = 8" in app_http and "maxRequestsPerHost = 4" in app_http,
        "script OkHttp async dispatcher must be separate and bounded")
require("else AppHttp.scriptDispatcher" in runtime and "ScriptRequestScheduler" in runtime,
        "ScriptRuntime must use its separate dispatcher while retaining the native fetch scheduler")
require("private fun closeProbeHttpResources()" in runtime and
        runtime.count("closeProbeHttpResources()") >= 3 and
        "if (!probeOnly) return" in runtime and
        "http.dispatcher.executorService.shutdown()" in runtime,
        "probe-only HTTP resources must close at teardown without shutting down shared playback dispatch")
require("scriptRuntime.forkForProbe()" in download and "isolated.closeAndWait()" in download,
        "download quality selection must own and close an isolated PROBE Runtime")
require("runtime.forkForProbe()" in playlist and "probeRuntime.closeAndWait()" in playlist,
        "playlist import checks must not share the playback WebView")
require("withContext(NonCancellable) { runtime.closeAndWait() }" in probe,
        "periodic probe Worker must close its dedicated Runtime even after cancellation")
require("withContext(NonCancellable) { isolatedRuntime.closeAndWait() }" in startup_probe and
        "withContext(NonCancellable) { isolatedRuntime.closeAndWait() }" in main_activity,
        "startup, manual single and batch PROBE Runtime cleanup must survive cancellation")

require("scriptRuntimeAndProbeRuntimeUseIsolatedBoundedDispatchers" in runtime_test,
        "OkHttp dispatcher isolation needs a no-network Robolectric regression")

print("[suspend-cancellation-guard] PASS: audited suspend boundaries propagate cancellation; WebView awaits use bounded script slots and short submit gate; PLAY/PREFETCH/PROBE/DOWNLOAD lanes and PROBE Runtime/dispatcher isolation are enforced (snapshot-export checks retired with the deleted server-snapshot subsystem, SW-02-12 ledger)")
PY
