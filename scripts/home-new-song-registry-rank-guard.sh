#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_PAGE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt"
NEW_SONG="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ui/home/HomeNewSongExpress.kt"
NEW_SONG_TEST="$ROOT/app/src/test/java/xyz/cncnlp/soundwave/HomeNewSongExpressTest.kt"
REGISTRY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSourceRegistry.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
LIBRARY="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt"

home_platform_ids='val platforms = listOf("wy" to "网易云", "kg" to "酷狗", "kw" to "酷我", "tx" to "QQ", "mg" to "咪咕")'
registry_platform_ids='val platformIds: List<String> = listOf("wy", "kg", "kw", "tx", "mg")'
grep -Fq "$home_platform_ids" "$HOME_PAGE"
grep -Fq "$registry_platform_ids" "$REGISTRY"
grep -Fq 'require(id in platformIds)' "$REGISTRY"

mapfile -t picker_ids < <(
  sed -n '/val filterOrder = listOf(/,/^[[:space:]]*)/p' "$LIBRARY" |
    sed -nE 's/^[[:space:]]*"([^"]*)" to .*/\1/p'
)
expected_picker_ids=("" wy tx kg kw mg)
[[ "${picker_ids[*]}" == "${expected_picker_ids[*]}" ]]
grep -Fq 'if (source.isNotBlank()) onPlatformClick(source)' "$LIBRARY"

grep -Fq 'loadHomeNewSongExpress(nb)' "$HOME_PAGE"
grep -Fq 'MusicSourceRegistry.platformSource(platform).rank(boardId)' "$HOME_PAGE"
grep -Fq 'if (preview.size >= 10 || board.id.isBlank()) return preview.take(10)' "$NEW_SONG"
grep -Fq 'runCatching { loadDetail(board.id) }.getOrDefault(emptyList())' "$NEW_SONG"
grep -Fq 'CancellationException' "$NEW_SONG"
for test_case in \
  'missing board returns empty without requesting detail' \
  'blank board id keeps preview and does not request detail' \
  'ten or more preview songs return the first ten without requesting detail' \
  'short preview is replaced by successful detail capped at ten' \
  'empty detail falls back to preview' \
  'detail exception falls back to preview' \
  'cancellation exception from detail retains legacy fallback behavior'; do
  grep -Fq "$test_case" "$NEW_SONG_TEST"
done
test "$(wc -l < "$NEW_SONG")" -lt 300
grep -Fq 'return api.rankDetail(id, source = platform)' "$ADAPTER"

echo 'Home new-song registry rank guard passed'
