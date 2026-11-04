#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
source = (root / "app/src/main/java/xyz/cncnlp/soundwave/SongRow.kt").read_text()
equalizer = (root / "app/src/main/java/xyz/cncnlp/soundwave/ui/player/PlayerSongEqualizer.kt").read_text()

def section(start_marker: str, end_marker: str) -> str:
    start = source.find(start_marker)
    end = source.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    if start < 0 or end <= start:
        raise SystemExit(f"[song-row-theme-guard] FAIL: component boundary missing: {start_marker}")
    return source[start:end]

row = section("fun SongRow(", "internal fun SongActionSheet(")
sheet = section("internal fun SongActionSheet(", "internal fun ActionIcon(")
checks = {
    "ordinary title and secondary labels use semantic text roles": all(
        token in row for token in (
            "detailForeground ?: colors.onSurface",
            "detailForeground ?: colors.onSurfaceVariant",
        )
    ),
    "indices and row action icons use theme accent/content roles": all(
        token in row for token in (
            "nowPlaying && !uniformIndexColor -> colors.primary",
            "else -> colors.onSurfaceVariant",
            "if (liked) colors.primary else colors.onSurfaceVariant",
            "tint = detailForeground ?: colors.onSurfaceVariant",
        )
    ),
    "selection uses theme roles while custom-detail contrast remains intact": all(
        token in row for token in (
            "colors.onSurfaceVariant.copy(alpha = 0.15f)",
            "Modifier.clip(CircleShape).background(colors.primary)",
            "tint = colors.onPrimary",
            "if (detailForeground.luminance() < 0.5f) Color.White else Color.Black",
        )
    ),
    "ordinary row colors contain no fixed gray/blue palette literals": all(
        token not in row for token in (
            "Color(0xFF101010", "Color(0xFF444444", "Color(0xFF555555",
            "Color(0xFF8A8A8A", "Color(0xFF9E9E9E", "Color(0xFF1A1A1A",
        )
    ),
    "action sheet surface, text, and accent follow the active theme": all(
        token in sheet for token in (
            "val menuFg = colors.onSurface",
            "val menuBg = colors.surface",
            "val menuSel = colors.primary",
        )
    ),
    "now-playing highlight fades from theme accent instead of a fixed solid fill": all(
        token in row for token in (
            "if (nowPlaying) Modifier.background(Brush.horizontalGradient(",
            "0f to colors.primary.copy(alpha = 0.12f), 0.85f to Color.Transparent",
        )
    ),
    # r657 第三轮第 30 项（用户 10-18）：封面暗层 + 白色律动去掉，改为歌手名左侧珊瑚小律动。
    "now-playing row uses the coral eq bars beside the artist, no artwork scrim": (
        "NowPlayingEqBars(isPlayingNow)" in row
        and "NowPlayingBars(active = isPlayingNow)" not in row
        and "Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))" not in row
    ),
}
for label, ok in checks.items():
    print(f"[song-row-theme-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
