#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
lease = (base / "MediaCacheLease.kt").read_text(encoding="utf-8")
cache = (base / "CacheManager.kt").read_text(encoding="utf-8")
decrypt = (base / "MediaDecryptCache.kt").read_text(encoding="utf-8")
controller = (base / "PlaybackController.kt").read_text(encoding="utf-8")
resolver = (base / "UrlResolver.kt").read_text(encoding="utf-8")
service = (base / "PlayerService.kt").read_text(encoding="utf-8")
extra = (base / "SettingsMaintenancePages.kt").read_text(encoding="utf-8")  # r656: cache page moved
main = (base / "MainActivity.kt").read_text(encoding="utf-8")
media_lease = (base / "cache/Media3CacheReadLease.kt").read_text(encoding="utf-8")
data_source = (base / "cache/LeasedCacheDataSource.kt").read_text(encoding="utf-8")
evictor = (base / "cache/LeaseAwareCacheEvictor.kt").read_text(encoding="utf-8")
operations = (base / "cache/Media3CacheOperations.kt").read_text(encoding="utf-8")
legacy_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/MediaCacheLeaseTest.kt").read_text(encoding="utf-8")
cache_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/MediaDecryptCacheConcurrencyTest.kt").read_text(encoding="utf-8")
media_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/Media3CacheReadLeaseTest.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[media-cache-lease-guard] FAIL: {message}")

for token in ("reserveInDirectory", "acquireExisting", "deleteIfUnleased", "deleteRecursively", "leasePathsUnder"):
    require(token in lease, f"the existing file lease registry is missing {token}")
require("synchronized(lock)" in lease and "leasePathsUnderLocked" in lease,
        "file lease registration and unlink must share their registry lock")
require("reserveInDirectory(dir, part)" in decrypt and "reserveInDirectory(dir, plainTmp)" in decrypt,
        "encrypted and plaintext staging paths must remain reserved before writing")
require("acquireExisting(cached)" in decrypt and "prepareLeased" in decrypt,
        "decrypted cache hits and newly prepared media must return an owned file lease")
require('MessageDigest.getInstance("SHA-256")' in decrypt and "cacheFileId(cacheKey)" in decrypt and
        "operationLock.withKey(cacheId)" in decrypt,
        "disk filenames and operation locks must use a full-key SHA-256 identity")
require('file.nameWithoutExtension == "dec_${cacheId}"' in decrypt,
        "cache hits must compare the exact hashed basename, not a filename prefix")
require("MediaDecryptCache.encryptedExtOf(url) != null" in resolver and
        "MediaDecryptCache.encryptedExtOf(raw)" in controller,
        "probe and playback must share the same final-path encrypted-extension detector")
require("deleteIfUnleased(f)" in cache and "deleteRecursively(path)" in cache,
        "ordinary automatic trim and recursive clearing must still honor file leases")
require("skippedActiveFiles" in cache and "leasePathsUnder(ctx.cacheDir)" in cache,
        "manual clear must still report active and not-yet-created file reservations")
require("prepareLeased(context" in controller and "PlayerService.playUrl(context, plainPath, song, startMs, mediaLease, result.platform)" in controller,
        "decrypted-file playback must hand its lease to PlayerService")
require("pendingCacheLease?.close()" in controller,
        "stale, cancelled, or failed play transactions must close untransferred file leases")
for token in (
    "mediaLeasesByPlayer", "setPlayerMedia(p, url, ownedLease)", "clearPlayerMedia(p)",
    "releaseTrackedPlayer(np)", "releaseTrackedPlayer(old)", "crossfadePlayers.add(old)",
    "crossfadePlayers.toList().forEach(::releaseTrackedPlayer)", "pending.cacheLease",
    "cacheLease: MediaCacheLease? = null",
):
    require(token in service, f"PlayerService file-lease ownership is missing {token}")
# SW-02-06: playUrl/prepareNext must acquire a lease for local media when the caller
# did not hand one over, and encrypted container URLs must never reach the player raw.
require(service.count("MediaCacheLeaseManager.acquireForPlayback(url)") >= 2,
        "playUrl and prepareNext must acquire a playback lease for local media")
require("MediaDecryptCache.encryptedExtOf(url) != null" in service,
        "encrypted container URLs must be rejected before reaching the player")
require("deleteLocalMediaResource" in lease,
        "the shared lease-aware local media deletion entry point must exist")
require(service.count("clearMediaItems()") == 1,
        "all player detach paths must continue to use the file-lease-aware clear helper")

require("cacheKeyFactory.buildCacheKey(dataSpec)" in data_source and "leases.acquire" in data_source,
        "each CacheDataSource must acquire the exact configured Media3 cache key before open")
require("closeAfterFailure(failure)" in data_source and "currentLease.close()" in data_source,
        "open/read errors and normal close must release the read lease")
require("setCacheKeyFactory(cacheKeyFactory)" in service and "LeasedCacheDataSource.Factory" in service,
        "the shared production data-source factory must use one exact CacheKeyFactory for both players")
require(service.count("buildCachedPlayer(app)") >= 2,
        "the primary and preloaded player must share the leased cached-player factory")
require("LeaseAwareCacheEvictor(cacheLimitBytes, mediaCacheReadLeases)" in service,
        "automatic SimpleCache LRU must skip active read keys")
require("trimMedia3Cache(cache, target, mediaCacheReadLeases)" in service and
        "clearMedia3CacheResources(cache, mediaCacheReadLeases)" in service,
        "manual trim and clear must use the guarded Media3 cache operations")
require("withClearFence" in media_lease and "removingKeys" in media_lease,
        "full clear must block new opens and serialize against resource unlink")
require("fence.removeIfUnleased" in operations and "remainingKeys.all" in operations,
        "manual clear must atomically skip active keys and verify every leftover is reported")
require("leases.removeIfUnleased(span.key)" in evictor and "leases.isActive(span.key)" in evictor,
        "automatic LRU must atomically skip active resources while allowing temporary over-budget cache")
require("skippedActiveMediaResources" in cache and "recordSkippedActiveMediaResources" in cache,
        "automatic trim outcomes must be reflected in cache diagnostics")
require("recordMediaCacheTrim" in cache and "裁剪未达预算" in cache,
        "incomplete automatic Media3 trims must remain visible in diagnostics")
require("result.skippedActiveMediaResources" in extra and "clearResult.skippedActiveMediaResources" in main,
        "both manual-clear entry points must report skipped Media3 resources")

for regression in (
    "real cache span open cannot race manual resource clear",
    "automatic lru leaves active resource over budget then trims after release",
    "deferredTrim.complete",
    "resource removal and lease acquisition serialize without a deletion gap",
    "open and read errors plus close cancellation release cache leases",
    "multiple readers keep one resource active until the final close",
):
    require(regression in media_test, f"a Media3 cache read-lifetime regression is missing: {regression}")
for regression in (
    "multiple owners protect a file until the final lease closes",
    "clear reports live staging and playback leases then succeeds after release",
    "lease acquisition and unlink are atomic against each other",
):
    require(regression in legacy_test, f"an existing file-lease regression is missing: {regression}")
for regression in (
    "punctuation and long prefix collisions keep separate files and locks",
    "cache lookup rejects a filename with only an extended hash stem",
    "encrypted format detection uses only the normalized final path extension",
):
    require(regression in cache_test, f"a decrypt-cache identity/extension regression is missing: {regression}")
require(version == "1.0.675" and 'versionName "1.0.675"' in build and
        re.search(r"versionCode\s+675\b", build) is not None,
        "VERSION, Gradle versionName, and versionCode must match 1.0.675 / 675")
require("scripts/media-cache-lease-guard.sh" in workflow,
        "the lease guard must run in portable quality gates")
print("[media-cache-lease-guard] PASS: file and Media3 read leases, both player factories, atomic clear/trim, race/error/cancel coverage, and 1.0.675")
PY
