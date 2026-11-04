#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
root = Path('app/src/main/java/xyz/cncnlp/soundwave')
notice = (root / 'ui/notice/AppNotice.kt').read_text()
host = (root / 'ui/notice/AppNoticeHost.kt').read_text()
theme = (root / 'Theme.kt').read_text()
main = (root / 'MainActivity.kt').read_text()

def require(condition, message):
    if not condition:
        raise SystemExit('[notice-tier-theme-guard] FAIL: ' + message)

# A. 分类体系：Tier 枚举 + NoticePolicy 单点时长，用户裁定的横幅时长（3s/5s）。
require('enum class Tier { ALERT, FEEDBACK, SILENT }' in notice,
        'AppNotice must expose the Tier classification (ALERT/FEEDBACK/SILENT)')
require('object NoticePolicy' in notice,
        'notice durations must be centralized in NoticePolicy')
require('BANNER_DURATION_MS = 3_000L' in notice and 'BANNER_ACTION_DURATION_MS = 5_000L' in notice,
        'banner durations must stay at the user-ruled shortened 3s/5s')
require('UNDO_DURATION_MS = 4_000L' in notice,
        'undo window must stay 4s (DownloadPendingDelete contract)')

# A.3 静默类：脚本检测完成的 inbox 不许再带 alsoBanner/alsoToast 升级。
for line in main.splitlines():
    if 'AppNotice.inbox' in line and 'Category.SCRIPT' in line:
        require('alsoBanner' not in line and 'alsoToast' not in line,
                'script-detection completion notices must stay silent (inbox only): ' + line.strip()[:80])

# B/D. 宿主：底色走主题 surface；写死深墨底与 inverseSurface 清退；撤销去药丸底、走强调文字色。
require('0xFF1F2225' not in host, 'capsule must not hardcode the dark-ink background')
require('inverseSurface' not in host and 'inverseOnSurface' not in host and 'inversePrimary' not in host,
        'banner must not use inverse surface colors; theme surface is the standard')
require('noticeFrostFill' in host and 'Color.Black.copy(alpha = 0.62f)' in host
        and 'colors.surface.copy(alpha = 0.86f)' in host,
        'notice surfaces must use the frost fill (user ruling 2026-11-04 19:19/19:20: translucent theme surface by day, translucent black by night)')
require('background(SoundwaveDesignPalette.Accent)' not in host.replace('background = SoundwaveDesignPalette.Accent', ''),
        'capsule action must not keep the solid accent pill background (text-only undo)')
require(host.count('designAccentTextColor()') >= 2,
        'capsule undo and banner action text must use the accent text token')
require('SoundwaveDanger' in host, 'failure badge must use the single SoundwaveDanger token')
# B.1 ACCEPT6（用户真机反馈 2026-11-04）：胶囊/横幅边缘不许有描边线（只留填充+柔和阴影）；
# 淡入淡出必须走内容根节点的整层 alpha（animateEnterExit），不许再挂回 AnimatedVisibility
# 本层分别淡出（退场末段填充与边缘脱节会显成与背景的分割线）。
require('.border(' not in host,
        'notice capsule/banner must not draw an edge border (user ruling 2026-11-04: borderless toast)')
require('animateEnterExit' in host,
        'notice fade must composite the whole capsule/banner as one layer (animateEnterExit)')

# C. 横幅手势与点击查看：右滑（detectDragGestures）+ triggerBannerClick 落点链。
require('detectDragGestures' in host, 'banner must support horizontal swipe-to-dismiss')
require('triggerBannerClick' in host and 'bannerViewHandler' in notice,
        'banner tap-to-view must be wired through AppNotice.bannerViewHandler')
require('settingsSub = "notifications"' in main,
        'MainActivity must register the notification-center destination for banner taps')

# E. 页面背景统一（终局口径：偏白为准）：设计浅色 scheme 不许再用暖纸 Paper 作 background。
require('background = SoundwaveDesignPalette.Paper' not in theme,
        'design light scheme must not use warm Paper as page background')
require('background = Color(0xFFFAFAFA.toInt())' in theme,
        'design light background must equal the root whiter standard #FAFAFA')

print('[notice-tier-theme-guard] OK')
PY
