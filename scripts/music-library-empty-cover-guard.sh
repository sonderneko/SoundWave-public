#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
policy = (base / "LibraryCoverPolicy.kt").read_text()
artwork = (base / "LibraryCoverArtwork.kt").read_text()
pager = (base / "LibraryQuickPager.kt").read_text()
lib_page = (base / "LibraryPage.kt").read_text()
lib_entry = (base / "LibPage.kt").read_text()
# r657：我的喜欢唱片套卡 / 本地歌单横向卡拆到 LibrarySleeveCards.kt，快捷卡断言同时覆盖两个文件。
sleeve = (base / "LibrarySleeveCards.kt").read_text()
lib_cards = lib_page + "\n" + sleeve
def uses_kind(kind):
    return f"fallbackKind = LibraryDefaultCoverKind.{kind}" in lib_cards or f"cover, defaultRes, LibraryDefaultCoverKind.{kind}," in sleeve
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/LibraryCoverPolicyTest.kt").read_text()
chrome = (base / "ListChrome.kt").read_text()
version = (root / "VERSION").read_text().strip()
gradle = (root / "app/build.gradle").read_text()
local_play_button_is_liked_only = re.search(
    r"if \(page == 0\) \{\s*Box\(\s*Modifier\.align\(Alignment\.BottomEnd\)", pager
) is not None

checks = {
    "liked and local use dedicated, visibly different palettes and motifs": (
        "LIKED(" in policy and "LOCAL_PLAYLIST(" in policy
        and "motif = LibraryCoverMotif.HEART" in policy
        and "motif = LibraryCoverMotif.PLAYLIST" in policy
        and "Color(kind.gradientStart)" in artwork and "Color(kind.gradientEnd)" in artwork
        and "LibraryCoverMotif.HEART ->" in artwork
        and "LibraryCoverMotif.PLAYLIST ->" in artwork
    ),
    "custom playlist art precedes the first nonblank track cover": (
        policy.find("playlistCover?.takeIf { it.isNotBlank() }") >= 0
        and policy.find("songCovers.firstOrNull { it.isNotBlank() }") > policy.find("playlistCover?.takeIf { it.isNotBlank() }")
        and "libraryCoverUrl(" in pager and "playlist.cover" in pager
        and "listOf(customCover) + songs.take(2)" in lib_page
        and "libraryStackCovers(loggedIn, localShortcut.cover" in lib_page
        and "playlistDisplayCover(pl)" in lib_page
        and "playlistDisplayCover(album)" in lib_page
    ),
    "both quick cards use the shared artwork component and distinct fallback kinds": (
        "repeat(2)" in pager and "LibraryCoverArtwork(" in pager
        and "LibraryDefaultCoverKind.LIKED" in pager
        and "LibraryDefaultCoverKind.LOCAL_PLAYLIST" in pager
        and "LibraryCoverArtwork(cover = cover.orEmpty(), fallbackKind = fallbackKind" in lib_cards
        and uses_kind("LIKED")
        and uses_kind("LOCAL_PLAYLIST")
    ),
    "empty covers are drawn locally and the cover resolver is gated on real artwork": (
        "Canvas(" in artwork and "LibraryDefaultCover(kind = fallbackKind" in artwork
        and "AsyncImage" not in artwork[artwork.find("internal fun LibraryDefaultCover"):]
        and "if (libraryCoverHasImage(cover))" in pager
        and "CacheManager.ensureCover(context, cover)" in pager
        and pager.find("if (libraryCoverHasImage(cover))") < pager.find("CacheManager.ensureCover(context, cover)")
    ),
    "unauthenticated first composition synchronously includes the local card": (
        "if (loggedIn) add(\"liked\")" in policy
        and 'add("local")' in policy
        and "libraryOrderIds(defaultIds, libOrder)" in lib_page
        and "remember(defaultIds.joinToString(), libOrder.joinToString())" in lib_page
        and "libraryQuickCardsVisible(orderIds)" in lib_page
        and "firstUnauthenticatedLibraryFrameShowsLocalCardAndBlankArtworkNeedsNoImageLoad" in test
    ),
    "the local shortcut uses its distinct default only when no real cover exists": (
        uses_kind("LOCAL_PLAYLIST")
        and "LibraryCoverArtwork(cover = cover.orEmpty(), fallbackKind = fallbackKind" in sleeve
        and "LibraryDefaultCover(kind = fallbackKind" in (base / "LibraryCoverArtwork.kt").read_text()
    ),
    "personal playlist count badges and row glyphs are removed; local card has no play button": (
        "userPlayCount" not in pager and "PlayCountHeadset" not in pager
        and "trailingText" not in lib_page and "trailingText" not in lib_entry
        and local_play_button_is_liked_only
        and "formatVisiblePlayCount(value: Long)" in chrome
    ),
    "policy tests cover precedence, distinctness, and no-cover behavior": all(
        name in test for name in (
            "userPlaylistCoverWinsAndMissingCoverFallsBackToFirstUsableTrackArtwork",
            "likedAndLocalPlaylistEmptyCoversHaveDifferentPalettesAndMotifs",
            "firstUnauthenticatedLibraryFrameShowsLocalCardAndBlankArtworkNeedsNoImageLoad",
        )
    ),
    "VERSION, Gradle versionName, and versionCode match 1.0.659 / 659": (
        version == "1.0.659"
        and 'versionName "1.0.659"' in gradle
        and re.search(r"versionCode\s+659\b", gradle) is not None
    ),
}

for label, ok in checks.items():
    print(f"[music-library-empty-cover-guard] {'PASS' if ok else 'FAIL'}: {label}")
if not all(checks.values()):
    raise SystemExit(1)
PY
