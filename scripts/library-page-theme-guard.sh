#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
source = (root / "app/src/main/java/xyz/cncnlp/soundwave/LibraryPage.kt").read_text()
design_row = (root / "app/src/main/java/xyz/cncnlp/soundwave/LibraryDesignRow.kt").read_text()
sleeve = (root / "app/src/main/java/xyz/cncnlp/soundwave/LibrarySleeveCards.kt").read_text()
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/LibraryPageThemeColorsTest.kt").read_text()


def section(start_marker: str, end_marker: str) -> str:
    start = source.find(start_marker)
    end = source.find(end_marker, start + len(start_marker)) if start >= 0 else -1
    if start < 0 or end <= start:
        raise SystemExit(f"[library-page-theme-guard] FAIL: component boundary missing: {start_marker}")
    return source[start:end]


mapping = section("internal fun libraryPageThemeColors(", "/** Fade only the lower edge")
detail_roles = section("val detailHeaderTint =", "var showSortSheet by remember(detail.id)")
ordinary_play_row = section("val buttonShape = RoundedCornerShape(24.dp)", "if (displaySongs.isEmpty()) item(")
# Only follow the extracted component; keep every existing theme assertion unchanged.
playlist_menu = section("menuPl?.let { pl ->", "if (showDeleteSongs) {")
delete_sheet = section("if (showDeleteSongs) {", "renamePl?.let { pl ->")

checks = {
    "theme resolver maps every used Material role directly": all(
        f"{name} = colorScheme.{name}" in mapping
        for name in (
            "background", "onBackground", "surface", "onSurface", "surfaceVariant",
            "onSurfaceVariant", "primary", "onPrimary", "outline",
        )
    ),
    "page content and dialogs resolve through the active Material scheme": (
        "libraryPageThemeColors(MaterialTheme.colorScheme)" in source
        and "val dialogBg = pageColors.surface" in source
        and "val dialogFg = pageColors.onSurface" in source
    ),
    "ordinary detail headers and play sheet actions use semantic roles": all(
        token in detail_roles
        for token in (
            "else pageColors.onBackground",
            "else pageColors.onSurface",
            "val playActionBackground = pageColors.primary",
            "val playActionForeground = pageColors.onPrimary",
            "pageColors.surfaceVariant",
            "else pageColors.onSurfaceVariant",
        )
    ),
    "shuffle and play-all buttons use theme surface, primary, and content roles": all(
        token in ordinary_play_row
        for token in (
            "background(actionPrimary)",
            "else pageColors.onSurfaceVariant",
            "else pageColors.surfaceVariant",
            "if (editorialDetailStyle)",
            "tint = pageColors.onPrimary",
            "color = pageColors.onPrimary",
        )
    ),
    # ACCEPT5（用户 2026-11-04 裁定）：「排序歌单」弹层随功能删除退役——删除契约代之：
    # 源内不得再有排序弹层/菜单项；在线歌单排序 = 整行长按拖动（LibraryDesignRow 手势）；
    # 原槽位为在线歌单 ⇄ 收藏视图切换；收藏行无小字行、区内无 ITEMS 计数标签。
    "playlist sort sheet is deleted; reorder is long-press drag and the menu slot switches views": (
        "LibraryPlaylistSortSheet" not in source
        and "sort_playlists" not in source
        and "detectDragGesturesAfterLongPress" in design_row
        and "toggle_view" in source
        and "切换到收藏" in source and "切换到在线歌单" in source
        and " ITEMS" not in source
    ),
    # r657 第二轮：歌单菜单改为统一设计抽屉（DesignSheetDialog / DesignSheetRow 内部按 MaterialTheme 角色取色、磨砂半透明）。
    "playlist action cards and their glyphs follow active theme roles": all(
        token in playlist_menu
        for token in (
            "DesignSheetDialog(onDismiss = { menuPl = null })",
            "DesignSheetHeader(",
            "DesignSheetRow(",
        )
    ) and all(literal not in playlist_menu for literal in ("Color.White", "Color.Black", "Color(0x")),
    "delete selection surfaces and labels follow the theme without changing callbacks": all(
        token in delete_sheet
        for token in (
            ".background(pageColors.surfaceVariant)",
            "color = pageColors.onSurface",
            "color = pageColors.onSurfaceVariant",
            "onDeleteSongs(picked, false)",
            "onDeleteSongs(picked, true)",
        )
    ) and all(literal not in delete_sheet for literal in ("Color.White", "Color.Black", "Color(0x")),
    "night and light role mapping, including action contrast, has a direct unit test": all(
        token in test
        for token in (
            "nightSchemeMapsLibrarySurfacesAndActionsToSemanticRoles",
            "actionAndSheetRolesFollowBothActiveColorSchemes",
            "NightPalette.SurfaceVariant",
            "NightPalette.OnAccent",
            "contrastRatio(colors.onPrimary, colors.primary)",
        )
    ),
    "cover-sampled contrast and art placeholder glyphs remain intact": all(
        token in source
        for token in (
            "detailAdaptiveTextForeground(detailSurfaceTone)",
            "if (adaptiveDetailStyle) {",
            "Color.White.copy(alpha = 0.20f)",
            "Color.Black.copy(alpha = 0.22f)",
            "Color.White.copy(alpha = 0.9f)",
            "detailCover.isNotEmpty() && !suppressArtworkBackdrop",
        )
    ),
    # 1.0.655 (user request: 音乐库顶部卡按设计稿重做 + 发光播放键): shortcut cards follow design v2 .library-card —
    # stacked tilted covers + Newsreader serif title + a glowing play button; open on tap, More stays hidden behind long press.
    # 1.0.657 (v4 #5): 我的喜欢 = v2 sleeve card (cover + half-revealed vinyl, glowing coral play + shuffle, no ♥ count);
    # 本地歌单 = row card. Both open on tap and keep More behind long press.
    "shortcut cards keep long-press more, hide visible more, and use the sleeve card with glowing play": (
        "combinedClickable(onClick = onOpen, onLongClick = onMore)" in sleeve
        and "Icons.Filled.MoreHoriz" not in sleeve
        and "accentGlow(" in sleeve
        # r657 修复：中文衬线改用打包的 Noto Serif SC（Newsreader 无中文字形）。
        and "DesignFonts.SerifCJK" in sleeve
        and "LibraryFavoritesSleeveCard(" in source and "LibraryLocalRowCard(" in source
    ),
}

for label, ok in checks.items():
    print(f"[library-page-theme-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
