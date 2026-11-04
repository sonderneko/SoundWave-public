#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INTERFACE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/MusicSource.kt"
ADAPTER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/source/PlatformSourceAdapter.kt"
CONTROLLER="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomeFeedController.kt"
API="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt"
HOME_PAGE="$ROOT/app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt"
MATRIX="$ROOT/docs/source-capability-matrix.md"

# Directory metadata and single-board song details are separate interface methods.
grep -Fq 'suspend fun rankBoards(): List<RankBoard> = emptyList()' "$INTERFACE"
grep -Fq 'suspend fun rank(id: String): List<Song>' "$INTERFACE"
grep -Fq 'override suspend fun rankBoards(): List<RankBoard>' "$ADAPTER"
grep -Fq 'return api.rank(source = platform)' "$ADAPTER"
grep -Fq 'return api.rankDetail(id, source = platform)' "$ADAPTER"

# Only fixed platforms use the adapter; preserve the original API path for all other values.
rank_req_block="$(sed -n '/val rankReq = async(Dispatchers.IO)/,/val playlistReq = async(Dispatchers.IO)/p' "$CONTROLLER")"
grep -Fq 'if (p in MusicSourceRegistry.platformIds)' <<<"$rank_req_block"
grep -Fq 'MusicSourceRegistry.platformSource(p).rankBoards()' <<<"$rank_req_block"
grep -Fq 'ApiClient.rank(p)' <<<"$rank_req_block"

# The underlying directory fallback and extracted new-song detail route remain distinct.
grep -Fq 'runDirectFallback { fetchRankBoardsDirect(source) }' "$API"
grep -Fq 'return rankServer(source)' "$API"
grep -Fq 'get(if (source.isNotEmpty()) "/music/rank?src=$source" else "/music/rank")' "$API"
grep -Fq 'loadHomeNewSongExpress(nb)' "$HOME_PAGE"
grep -Fq 'MusicSourceRegistry.platformSource(platform).rank(boardId)' "$HOME_PAGE"
grep -Fq 'rankBoards()' "$MATRIX"
grep -Fq 'rankDetail(id,source)' "$MATRIX"

echo 'Home rank-board directory contract guard passed'
