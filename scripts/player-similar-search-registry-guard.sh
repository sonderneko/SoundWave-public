#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
interface = (base / "source/MusicSource.kt").read_text()
adapter = (base / "source/PlatformSourceAdapter.kt").read_text()
controller = (base / "PlaybackController.kt").read_text()
matrix = (root / "docs/source-capability-matrix.md").read_text()

def require(ok, message):
    if not ok:
        raise SystemExit(f"FAIL: {message}")

require(
    'suspend fun searchServer(keyword: String, type: String = "song"): List<Song> = emptyList()' in interface,
    "MusicSource exposes an explicit server-only search contract",
)
require(
    'override suspend fun searchServer(keyword: String, type: String): List<Song> =\n        api.searchCancellable(keyword, source = platform, type = type)' in adapter,
    "platform adapter forwards the identical keyword/source/type to ApiClient without direct search",
)
marker = "    suspend fun playSimilarSong(item: Song) {"
if controller.count(marker) != 1:
    raise SystemExit("FAIL: expected one playSimilarSong entry")
start = controller.index(marker)
next_method = re.search(r"\n    (?:suspend )?fun [A-Za-z0-9_]+\(", controller[start + len(marker):])
if next_method is None:
    raise SystemExit("FAIL: cannot isolate playSimilarSong body")
end = start + len(marker) + next_method.start()
body = controller[start:end]
require(
    'val query = listOf(item.singer, item.name.take(8)).filter { it.isNotBlank() }.joinToString(" ")' in body,
    "legacy query construction is preserved",
)
require(
    'val source = item.source.ifEmpty { "wy" }' in body,
    "legacy source fallback is preserved without normalization",
)
require(
    'if (source in MusicSourceRegistry.platformIds)' in body
    and 'MusicSourceRegistry.platformSource(source).searchServer(query)' in body,
    "only exact canonical platform sources use the Registry server-only route",
)
require(
    'ApiClient.search(query, source)' in body,
    "non-canonical sources keep the exact legacy ApiClient fallback",
)
require(
    'TasteProfileV2.pickSimilar(item,' in body
    and 'searchHeartSimilarServer(item.singer, source)' in body
    and 'DislikeStore.filter(context, candidates + artistCandidates)' in body
    and 'candidates.firstOrNull { it.key() != item.key() }' not in body,
    "similar-song selection is taste-scored via TasteProfileV2.pickSimilar over merged recalls, never first-hit",
)
require(
    "相似歌曲搜索（`PlaybackController.playSimilarSong`）" in matrix
    and "不调用会增加平台直连候选的 `MusicSource.search()`" in matrix,
    "matrix documents the server-only behavior and boundary",
)
print("Playback similar-search Source Registry contract guard passed")
PY
