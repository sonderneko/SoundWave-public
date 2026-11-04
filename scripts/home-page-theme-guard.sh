#!/usr/bin/env bash
set -euo pipefail
# 2026-11-05 删除合同重钉（public-cleanup）：HomeTopBar.kt 系零调用死代码已删，本守卫不再读取该文件，「standalone home top bar」条目随其退役；其余断言逐字不变。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
source = (root / "app/src/main/java/xyz/cncnlp/soundwave/HomePage.kt").read_text()
design_sections = (root / "app/src/main/java/xyz/cncnlp/soundwave/HomeDesignSections.kt").read_text()
detail_pages_source = (root / "app/src/main/java/xyz/cncnlp/soundwave/DetailDesignPages.kt").read_text()
badge_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/HomeContentBadgeStyleTest.kt").read_text()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[home-page-theme-guard] FAIL: {message}")


def section(start_marker: str, end_marker: str, label: str) -> str:
    start = source.find(start_marker)
    end = source.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    require(start >= 0 and end > start, f"{label} boundary missing")
    return source[start:end]


scroll_button = section(
    "private fun HomeScrollTopButton(",
    "data class HomeFeedRenderState(",
    "scroll-to-top button",
)
hot_top_start = source.find("if (hotShowTop) {")
hot_top_end = source.find('"hot_rank" -> {', hot_top_start + 1) if hot_top_start >= 0 else -1
require(hot_top_start >= 0 and hot_top_end > hot_top_start, "hot-page scroll-to-top boundary missing")
hot_top_button = source[hot_top_start:hot_top_end]
group_filters = section(
    'item(key = "playlist_groups_$platform")',
    'if (subCats.isNotEmpty())',
    "playlist group filters",
)
subcategory_filters = section(
    'item(key = "playlist_subcats_$platform")',
    'item(key = "popular_playlists_gap")',  # r657：「探索歌单」标题行已删除
    "playlist subcategory filters",
)

checks = {
    # 1.0.655: every page's scroll-to-top / locate button shares DetailAccentFab (surface + primary + shadow, the home style).
    "generic scroll-to-top control uses active surface and primary roles": (
        "DetailAccentFab(" in scroll_button
        and ".background(colors.surface)" in detail_pages_source
        and "tint = colors.primary" in detail_pages_source
        and all(literal not in scroll_button for literal in ("Color.White", "Color.Black", "Color(0x"))
    ),
    "hot-page inline scroll-to-top control follows the same theme roles": (
        "HomeScrollTopButton(" in hot_top_button
        and all(literal not in hot_top_button for literal in ("Color.White", "Color.Black", "Color(0x"))
    ),
    # Links moved from inline Chinese Text calls to HomeSectionHeader. Non-clickable captions stay muted.
    "home section action links use the active primary role": (
        source.count("HomeSectionHeader(") >= 3
        and "link, color = if (onLink != null) colors.primary else colors.onSurfaceVariant" in design_sections
        and "Modifier.clickable(onClick = onLink)" in design_sections
        and "color = if (isDark) Color.White else Color.Black" not in source
    ),
    "playlist group filters keep selected semantics with theme surface/content roles": all(
        token in group_filters
        for token in (
            # 设计稿 v2 .playlist-chip：选中 = ink 实底 + 页面底色文字，未选 = surface + outline 描边；selected 语义显式声明。
            ".background(if (on) onBg else MaterialTheme.colorScheme.surface)",
            "MaterialTheme.colorScheme.outline",
            "color = if (on) MaterialTheme.colorScheme.background else onBg",
            "selected = on",
            'HomePageStateStore.putString(homeContext, "playlist-group:',
        )
    ) and all(literal not in group_filters for literal in ("Color.White", "Color.Black", "Color(0x")),
    "playlist subcategory filters keep selected semantics with theme surface/content roles": all(
        token in subcategory_filters
        for token in (
            # 设计稿 v2 .playlist-chip：选中 = ink 实底 + 页面底色文字，未选 = surface + outline 描边；selected 语义显式声明。
            ".background(if (on) onBg else MaterialTheme.colorScheme.surface)",
            "MaterialTheme.colorScheme.outline",
            "color = if (on) MaterialTheme.colorScheme.background else onBg",
            "selected = on",
            'HomePageStateStore.putString(homeContext, "playlist-category:',
        )
    ) and all(literal not in subcategory_filters for literal in ("Color.White", "Color.Black", "Color(0x")),
    "home hero keeps readable image overlay and coral recommendation action": (
        # r657 r3b: v5 .hero::before scrim -- rgba(6,8,12) 0@25% -> .55@62% -> .86@100% keeps white text readable.
        "0.62f to Color(0xFF06080C).copy(alpha = 0.55f)" in design_sections
        and "1f to Color(0xFF06080C).copy(alpha = 0.86f)" in design_sections
        and 'Text("今日推荐"' in design_sections
        and "SoundwaveDesignPalette.Accent" in design_sections
        and "internal fun HomeDailyHero(" in design_sections
        and ".background(HeroInk)" in design_sections
        and 'Text("今日推荐", color = Color.White' in design_sections
    ),
    "home header follows the latest title/platform/avatar design without visible section tabs": (
        "AppPageHeader(" in source
        and 'title = when (currentSection)' in source
        and "showAvatar = true" in source
        and "platformPickerOpen = true" in source
        and 'title = "切换音源"' in source
        and "onAvatarLongClick = onAvatarLongClick" in source
        and "HorizontalPager(state = pagerState, userScrollEnabled = false" in source
    ),
}

for label, ok in checks.items():
    print(f"[home-page-theme-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
