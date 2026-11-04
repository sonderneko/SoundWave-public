#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricCorrection.kt"
SEARCH="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricCorrectionSearch.kt"
STORE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricCorrectionStorage.kt"
LOADER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LyricSourceLoader.kt"
DIRECT="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/direct/LyricDirectFetch.kt"
UI="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerLyricActionsSheet.kt"
TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/LyricCorrectionTest.kt"

for mapping in '"mg" -> song.copyrightId' '"tx" -> song.id' '"kg" -> (song.hash.ifBlank { song.id })'; do
  grep -Fq "$mapping" "$CORE" || { echo "missing source-native lyric ID mapping: $mapping" >&2; exit 1; }
done
grep -Fq 'mediaMid' "$CORE"
grep -Fq 'mediaMid must not substitute for songmid' "$TEST"
grep -Fq '搜索结果的 contentId/Song.id 不等价' "$DIRECT"
grep -Fq 'fetchMiguLyricByCopyrightId(nativeId)' "$SEARCH"
grep -Fq 'lyricCorrectionPlatformId(it) == platform' "$SEARCH"
grep -Fq 'rankTrustedLyricCorrectionSongs(target, samePlatformSongs)' "$SEARCH"
grep -Fq 'NET_CAPABILITY_VALIDATED' "$SEARCH"
grep -Fq 'ProfileOwner.LegacyUnassigned -> "lyric_corrections"' "$STORE"
grep -Fq '"profile_lyric_corrections/${identity.id.value}"' "$STORE"
grep -Fq 'LyricCorrectionRepository(FileStore(context, owner))' "$STORE"
grep -Fq 'ProfileWorkIdentity.isRuntime(owner)' "$STORE"
grep -Fq 'preferUserLyricCorrection(LyricCorrectionStorage.load(context, song, owner))' "$LOADER"
grep -Fq 'loadSidecar(song).ifBlank { loadNetworkCached(context, song, owner) }' "$LOADER"
grep -Fq 'CacheManager.saveLyric(context, lyricKey, raw)' "$LOADER"
grep -Fq 'onApplyLyricCorrection(candidate)' "$UI"
grep -Fq 'selectedCandidateIndex in correctionCandidates.indices' "$UI"
grep -Fq '清除修正，恢复默认歌词' "$UI"
for test_name in \
  exactNativeIdsUsePlatformSemanticsAndNeverCrossUseIdentifiers \
  miguContentIdSongKeyIsSeparatedByCopyrightVariant \
  missingOrUnreliableIdsFallBackToSameSourceTitleArtistAndDurationCandidates \
  onlyExplicitlyConfirmedCandidateIsSaved \
  cancellationAndFailedOrCrossPlatformCandidateLeaveExistingOverrideUntouched \
  overridesAreIsolatedBySongKeyAndSource \
  oldNetworkCacheIsLazyFallbackAndClearRestoresDefaultLyrics; do
  grep -Fq "fun $test_name()" "$TEST" || { echo "missing lyric correction JVM test: $test_name" >&2; exit 1; }
done
echo 'Lyric correction source/identity/override guard passed'
