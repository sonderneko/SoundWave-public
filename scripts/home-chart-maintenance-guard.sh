#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
ui = (root / 'app/src/main/java/xyz/cncnlp/soundwave/ui/home/HomeChartDesign.kt').read_text()
home = (root / 'app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt').read_text()
models = (root / 'app/src/main/java/xyz/cncnlp/soundwave/Models.kt').read_text()
row = ui[ui.index('internal fun ChartRankRow('):ui.index('internal fun ChartPickerRow(')]
rank = home[home.index('"rank" -> {'):home.index('if (boardPickerOpen) {')]
checks = {
    'all rank song covers use 52dp': 'val coverSize = 52.dp' in row and 'if (top) 64.dp' not in row,
    'first three numbers enlarged without changing rank order': 'fontSize = if (top) 16.sp else 12.sp' in row and 'rank = index + 1' in rank,
    'more action is independent and hidden in multiselect': 'else if (onMore != null)' in row and 'IconButton(onClick = onMore' in row,
    'home wires actual song into reusable sheet': 'onMore = { rankMenuSong = song }' in rank and 'SongActionSheet(' in home,
    # ACCEPT5（退出动画普查 S1）改钉：宿主回调不再同步清 rankMenuSong（退出动画站规，
    # 显隐由弹层退场后 onDismiss 清）；本 check 意图（动作真调宿主操作）不变，字面等严更新。
    'sheet actions call actual host operations': all(token in home for token in (
        'onLike = { onLike(song) }',
        'onAdd = { onAdd(song) }',
        'onAddNext = { onAddNext(song) }',
        'onDownload = { onDownload(song) }')),
    'only real nonzero rank movement is drawn': 'val change = song.rankChange' in row and 'change == null || change == 0 -> ""' in ui,
    'hero uses real update label only when known': 'val updateLabel = board.officialUpdateLabel()' in ui and 'if (updateLabel.isNotBlank())' in ui,
    'unknown update cannot become fabricated freshness': '"刚刚更新"' not in models and 'val timestamp = updatedAt?.takeIf' in models,
}
for label, passed in checks.items():
    print(f"[home-chart-maintenance] {'PASS' if passed else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
