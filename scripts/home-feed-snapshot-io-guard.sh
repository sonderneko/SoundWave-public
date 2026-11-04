#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
main = (base / 'MainActivity.kt').read_text()
cache = (base / 'HomeFeedCache.kt').read_text()
controller = (base / 'HomeFeedController.kt').read_text()
session = (base / 'ui/home/HomeFeedSession.kt').read_text()
tests = (root / 'app/src/test/java/xyz/cncnlp/soundwave/HomeFeedSnapshotIoTest.kt').read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f'[home-feed-snapshot-io-guard] FAIL: {message}')


require('HomeFeedCache.load(' not in main, 'Compose startup must not synchronously load/decode the full snapshot')
require('HomeFeedCache.encodeSnapshot(' not in main, 'Compose code must not encode a full snapshot directly')
require('rememberHomeFeedSession(context, scope) { homePlatform }' in main, 'MainActivity must delegate startup to the home session')
require('HomeFeedController(context, scope, getHomePlatform, HomeFeedCache.Snapshot())' in session, 'the session must start with an empty snapshot and let the controller load disk asynchronously')
require('LaunchedEffect(controller) { controller.refreshOnHomeReturn() }' in session, 'the first home-return refresh remains lifecycle-scoped to the session')
require('LaunchedEffect(feedLoading, gestureRefreshing)' in session, 'gesture refresh state must clear only after feed loading ends')
require('setGestureRefreshing(true)' in session and 'controller.refreshHomeFeed(forceNetwork = true)' in session, 'pull-to-refresh must preserve its forced-network behavior')
require('suspend fun load(ctx: Context): Snapshot = snapshotIo.load' in cache, 'cache reads must use the suspending IO boundary')
require('snapshotIo.save(snapshot, writeRaw =' in cache, 'cache writes must use the suspending IO boundary')
require('withContext(ioDispatcher)' in cache, 'snapshot codec must execute on the IO dispatcher')
require('writeMutex.withLock' in cache and 'AtomicLong' in cache, 'snapshot writes must be serialized and versioned')
require('requestedVersion != writeSequence.get()' in cache, 'an older pending snapshot write must be dropped when superseded')
load = controller.find('HomeFeedCache.load(context)')
seed = controller.find('seedContentFromCache()', load)
short_circuit = controller.find('shouldShortCircuitHomeFeed(snap', load)
require(load >= 0 and seed > load and short_circuit > seed, 'first cache restoration must finish before freshness short-circuit/network decisions')
require('if (!initialCacheSeeded)' in controller, 'only the first disk read may seed content, avoiding refresh-time clearing')
require('encodedSnapshotIsReadableFromCacheAndRoundTripsAllFeedResources' in tests, 'tests must cover serialized cache hits and round-trips')
require('newerSnapshotSupersedesAnOlderWriteQueuedForTheIoDispatcher' in tests, 'tests must cover stale/new write ordering')
require('cancelledCallerLifecycleDoesNotStartQueuedReadOrWrite' in tests, 'tests must cover cancellation/lifecycle behavior')
print('[home-feed-snapshot-io-guard] PASS: startup read and snapshot codec stay on the IO boundary; first cache seed, write ordering, and lifecycle checks are covered')
PY
