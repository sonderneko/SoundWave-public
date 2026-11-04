#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ROUTING="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/LyricSourceRouting.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
LOADER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricSourceLoader.kt"
CACHE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/CacheManager.kt"
IDENTITY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricCacheIdentity.kt"
DOWNLOAD="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/DownloadHelper.kt"
TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/source/PlatformLyricRoutingTest.kt"

for mapping in \
  '"wy", "netease" -> "wy"' \
  '"kg", "kugou" -> "kg"' \
  '"kw", "kuwo" -> "kw"' \
  '"tx", "qq", "tencent" -> "tx"' \
  '"mg", "migu" -> "mg"'; do
  grep -Fq "$mapping" "$REGISTRY" || { echo "missing lyric alias mapping: $mapping" >&2; exit 1; }
done

grep -Fq 'internal const val LYRIC_SERVER_FALLBACK_ROUNDS = 4' "$ROUTING"
grep -Fq 'for (attempt in 0 until LYRIC_SERVER_FALLBACK_ROUNDS)' "$ROUTING"
grep -Fq 'raw.isBlank() && song.id.isNotBlank()' "$ROUTING"
grep -Fq '220L * (attempt + 1)' "$ROUTING"
grep -Fq 'direct = { request -> fetchLyricDirect(request.source, request) }' "$ADAPTER"
grep -Fq 'server = api::lyric' "$ADAPTER"
grep -Fq 'MusicSourceRegistry.lyricPlatformId(song.source)' "$LOADER"
# r656-G（P1-6）：歌词加载改为 suspend，平台 adapter 直接挂起调用，禁止 runBlocking 阻塞调用线程。
grep -Fq 'MusicSourceRegistry.platformSource(platformId).lyric(song)' "$LOADER"
if grep -Fq 'runBlocking' "$LOADER"; then
  echo 'LyricSourceLoader must not use runBlocking (suspend instead)' >&2
  exit 1
fi
grep -Fq 'lyricCacheKey(song, "v2yrc")' "$LOADER"
grep -Fq 'encodeOwner(owner)}:' "$LOADER"
grep -Fq 'internal fun persistentLyricKey' "$LOADER"
grep -Fq 'ProfileOwner.LegacyUnassigned' "$LOADER"
if grep -Fq 'encodeOwner(owner)}:${owner.generation}:' "$LOADER"; then
  echo 'persistent lyric cache identity must not contain a process/session lease' >&2
  exit 1
fi
grep -Fq 'ProfileWorkIdentity.commitWork(owner)' "$LOADER"
grep -Fq 'CacheManager.loadLyric(context, lyricKey)' "$LOADER"
grep -Fq 'CacheManager.saveLyric(context, lyricKey, raw)' "$LOADER"
grep -Fq 'lyricCacheFileName(key)' "$CACHE"
grep -Fq 'lyric-file-v3' "$IDENTITY"
grep -Fq 'song.mediaMid' "$IDENTITY"
grep -Fq 'lyricCacheKey(song, "v2yrc")' "$DOWNLOAD"
if grep -Fq 'File(lyricsDir(ctx), "$safe.lrc")' "$CACHE"; then
  echo 'lyric cache filenames must not use the legacy sanitized key' >&2
  exit 1
fi
grep -Fq 'loadSidecar(song).ifBlank { loadNetworkCached(context, song, owner) }' "$LOADER"
grep -Fq 'if (priority == "embedded") embedded().ifBlank { external() }' "$LOADER"
grep -Fq 'else external().ifBlank { embedded() }' "$LOADER"
grep -Fq 'ApiClient.lyric(song)' "$LOADER" # non-five-platform fallback remains

grep -Fq 'assertEquals(8, serverSongs.size)' "$TEST"
grep -Fq 'assertEquals(4, calls)' "$TEST"
echo 'Lyric source contract guard passed'
