#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
controller = (base / 'PlaybackController.kt').read_text()
service = (base / 'PlayerService.kt').read_text()
main_activity = (base / 'MainActivity.kt').read_text()
full_player = (base / 'FullPlayer.kt').read_text()
mini_player = (base / 'MiniPlayer.kt').read_text()
dock = (base / 'ui/player/DetailTransitionMiniDock.kt').read_text()
session = (base / 'ui/home/HomeFeedSession.kt').read_text()
coalescer = (base / 'playback/ResolveProgressCoalescer.kt').read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[playback-main-thread-freeze-guard] FAIL: {message}')


# --- ACCEPT6-FREEZE: resolve progress must be coalesced before it touches Compose state.
# UrlResolver fires onProgress per attempt; unthrottled writes to `status` invalidated the
# scopes reading it and saturated the main thread for exactly the resolve window
# ("freezes while loading, recovers when playback starts").
require('const val DEFAULT_MIN_INTERVAL_MS: Long = 350L' in coalescer,
        'progress coalescer must keep the 350ms publish window')
window = controller[controller.index('status = "智能取链…"'):controller.index('val playUrl = ApiClient.playableUrl(raw)')]
require('ResolveProgressCoalescer()' in window,
        'resolveAndPlay must create a ResolveProgressCoalescer for its progress sink')
require('progressCoalescer.offer(label)?.let { status = it }' in window,
        'resolve progress writes must go through the coalescer (latest-wins, 350ms)')
require('onProgress = { if (thisResolveJob?.isActive == true && resolving && requestIsCurrent()) status = it }' not in controller,
        'the unthrottled direct status progress sink must not return')

# --- Import-marks store: full-table JSON parse/serialize must stay inside IO segments on
# both real-play paths (controller resolveAndPlay runs on the caller/Main dispatcher
# between suspensions; PlayerService.resolveInService runs on Main.immediate).
io_at = window.find('withContext(Dispatchers.IO) {')
require(io_at >= 0, 'resolveAndPlay must keep its IO resolve segment')
require(window.find('PlaylistImportChecker.shouldForceCross(') > io_at,
        'shouldForceCross (full import-marks parse) must run inside the IO segment')
require('withContext(Dispatchers.IO) {\n                    PlaylistImportChecker.notePlayOutcome(' in window,
        'notePlayOutcome (full import-marks rewrite) must be IO-wrapped in resolveAndPlay')

svc = service[service.index('var effectiveForceCross = forceCross'):service.index('playUrl(', service.index('var effectiveForceCross = forceCross'))]
svc_io_at = svc.find('withContext(Dispatchers.IO) {')
require(svc_io_at >= 0, 'resolveInService must keep its IO resolve segment')
require(svc.find('PlaylistImportChecker.shouldForceCross(') > svc_io_at,
        'resolveInService shouldForceCross must run inside the IO segment')
require('withContext(Dispatchers.IO) {\n                    PlaylistImportChecker.notePlayOutcome(' in svc,
        'resolveInService notePlayOutcome must be IO-wrapped')

# --- Deferred reads: resolving/status travel as State objects into the player and the
# mini dock so host scopes (AppRoot body, full-player layer) no longer subscribe to them.
require('resolvingState: androidx.compose.runtime.State<Boolean>' in full_player,
        'FullPlayer must take resolvingState as a State object')
require('statusState: androidx.compose.runtime.State<String>' in full_player,
        'FullPlayer must take statusState as a State object')
require('resolvingState.value' in full_player and 'statusState.value' in full_player,
        'FullPlayer must dereference the playback states inside its render scopes')
require('resolvingState: androidx.compose.runtime.State<Boolean>' in mini_player,
        'MiniPlayer must take resolvingState as a State object')
require('resolvingState.value' in mini_player,
        'MiniPlayer must dereference resolvingState inside its own scope')
require('resolvingState: State<Boolean>' in dock and 'resolvingState = resolvingState' in dock,
        'DetailTransitionMiniDock must forward resolvingState untouched')
require('statusState = playback.statusState, resolvingState = playback.resolvingState' in main_activity,
        'AppRootFullPlayerLayer must pass playback state objects, not values, to FullPlayer')
require(main_activity.count('resolvingState = playback.resolvingState') >= 3,
        'FullPlayer layer and both mini-dock hosts must pass playback.resolvingState')
require('status = status, resolving = resolving' not in main_activity,
        'by-value status/resolving passing into FullPlayer must not return')
require('resolving = resolving,' not in main_activity,
        'by-value resolving passing into the mini dock must not return')

# --- Recommendation profile: the 365-day decayed-facts parse happens in the IO stats
# gather, never inside the root-scope remember (it re-ran on every history write, i.e.
# on every song commit).
require('ListenStatsDailyStore.decayedFacts(dailyJson, System.currentTimeMillis())' in session,
        'recommendation stats snapshot must precompute decayed facts on IO')
require('precomputedDecayedFacts = statsSnapshot?.decayedFacts' in session,
        'rememberRecommendationLibrary must feed buildProfile the precomputed decayed facts')

# --- Crash addendum (device evidence 2026-11-04 19:15, vivo V2453A / API 36):
# NetworkOnMainThreadException from ConscryptEngineSocket.close() on the main thread.
# OkHttp Call.cancel() closes the SSLSocket synchronously on the caller's thread (TLS
# close-notify flush). Play-path cancellation initiators run on the main thread (tap
# supersede cancels the previous resolve job / script requests), and both
# invokeOnCancellation handlers and scheduler cancel actions execute synchronously on
# the initiating thread — so every transport cancel must be dispatched to a dedicated
# background executor, never inline.
resolve_budget = (base / 'ResolveBudget.kt').read_text()
runtime = (base / 'ScriptRuntime.kt').read_text()
transport = (base / 'ScriptFetchTransport.kt').read_text()
require('invokeOnCancellation { call.cancel() }' not in resolve_budget,
        'resolveCall must not cancel its OkHttp call inline on the cancelling thread')
require('ResolveBlockingStage.cancelCallAsync(call)' in resolve_budget,
        'resolveCall cancellation must dispatch the socket close to the IO stage scope')
require('activeCalls.remove(cid)?.cancel()' not in runtime,
        'releaseRequest must not cancel a call inline on the caller (main) thread')
require('try { call.cancel() }' not in runtime,
        'runtime fetch callbacks must not cancel calls inline; dispatch via cancelCallAsync')
require('cancelCallAsync(it)' in runtime,
        'ScriptRuntime must route removed active calls through cancelCallAsync')
require('installCancellation(call) { call.cancel() }' not in transport,
        'the script fetch transport must not install an inline call.cancel() action')
require('installCancellation(call) { cancelFetchCallAsync(call) }' in transport,
        'the script fetch transport must install the dispatched cancel action')

# --- Crash addendum 2 (device evidence 2026-11-05 02:24, vivo V2453A / API 36):
# the same NetworkOnMainThreadException family, reached through the mg direct path.
# OfficialCallScope registers each operation's calls and its
# invokeOnCompletion(onCancelling = true) handler runs synchronously on the thread
# that cancels the job tree — for search that is the main thread (query edit, tab
# switch, page exit). Its inline teardown cancelled the calls right there, a shape
# none of the pins above covered. All transport teardown across the diff surface
# (OfficialCallScope, the download call registry/loops, the WebDAV lease handler)
# now dispatches to ResolveBlockingStage.cancelCallAsync (the stage IO scope);
# the inline forms must not return, and explicit response close()/disconnect()
# must not spread beyond the one wrapped legacy site in ApiClient.
official_scope = (base / 'OfficialCallScope.kt').read_text()
download_helper = (base / 'DownloadHelper.kt').read_text()
webdav_sync = (base / 'sync/WebDavSync.kt').read_text()
require('it.cancel()' not in official_scope and 'call.cancel()' not in official_scope,
        'OfficialCallScope must never cancel a call inline on the cancelling thread')
require(official_scope.count('ResolveBlockingStage.cancelCallAsync') >= 2,
        'OfficialCallScope teardown (completion handler + execute pre-flight) must dispatch to the stage IO scope')
require('DownloadRequestRegistry<Call> { ResolveBlockingStage.cancelCallAsync(it) }' in download_helper,
        'the download call registry must dispatch cancels to the stage IO scope')
require('call.cancel();' not in download_helper and 'activeCall.cancel();' not in download_helper,
        'download loops must not cancel calls inline')
require('lease.onCancel { call.cancel() }' not in webdav_sync,
        'WebDAV lease handlers must not cancel calls inline')
require('lease.onCancel { ResolveBlockingStage.cancelCallAsync(call) }' in webdav_sync,
        'WebDAV request teardown must dispatch to the stage IO scope')

disconnect_files = []
response_close_files = []
for kt in base.rglob('*.kt'):
    text = kt.read_text(errors='ignore')
    if '.disconnect()' in text:
        disconnect_files.append(kt.name)
    if 'response.close()' in text or '.body?.close()' in text:
        response_close_files.append(kt.name)
require(disconnect_files == ['ApiClient.kt'],
        'HttpURLConnection disconnect() must stay confined to the wrapped legacy site in ApiClient')
require(not response_close_files,
        'responses must be consumed/closed via use{} on the executing thread, never explicit close()')

print('[playback-main-thread-freeze-guard] OK')
PY
