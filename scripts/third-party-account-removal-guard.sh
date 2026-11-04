#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FORBIDDEN='PlatformAccountUi|PlatformAccountApi|PlatformAccountStore|PlatformLoginActivity|PlatformPlaybackAdapter|NeteaseCrypto|QQDirectResolve|RecommendDirectFetch|fetchRecommendDirect|parseWyDailyRecommend|dailyRecommend|personalRadar|私人雷达|每日推荐|登录推荐|X-Platform-Cookie|cookieForUrl|accountCookie|platformCookie|loginCookie|userCookie|thirdPartyCookie|cookieHeader|anonymousCookieHeader'
if grep -R -n -E -i -- "$FORBIDDEN" app/src/main/java app/src/test/java app/src/main/AndroidManifest.xml; then
  echo 'Third-party music-account or account-recommendation code has returned.' >&2
  exit 1
fi

if find app/src/main/java -type f | grep -E -i '/(PlatformAccount|PlatformLogin|PlatformPlayback|NeteaseCrypto|QQDirectResolve|RecommendDirectFetch)'; then
  echo 'Retired third-party account source files have returned.' >&2
  exit 1
fi

if grep -n -E -- 'accountProbe|userPlaylists' app/src/main/java/xyz/cncnlp/soundwave/ProviderRegistry.kt; then
  echo 'Third-party account capabilities must remain absent from the public-data model.' >&2
  exit 1
fi

# Only the fixed, anonymous NetEase protocol marker may use a Cookie header.
grep -E -q -- 'private const val WY_ANONYMOUS_PROTOCOL_MARKER = "os=pc"' app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlatformHttp.kt
grep -E -q -- 'header\("Cookie", WY_ANONYMOUS_PROTOCOL_MARKER\)' app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlatformHttp.kt
if grep -n -E -i -- '(^|[,(])[[:space:]]*[[:alnum:]_]*cookie[[:alnum:]_]*[[:space:]]*:' app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlatformHttp.kt; then
  echo 'Platform HTTP must not accept caller-supplied Cookie parameters.' >&2
  exit 1
fi
COOKIE_HEADER_COUNT="$(grep -E -i -c -- 'header[[:space:]]*\([[:space:]]*"cookie"[[:space:]]*,' app/src/main/java/xyz/cncnlp/soundwave/source/direct/PlatformHttp.kt || true)"
if [ "$COOKIE_HEADER_COUNT" -ne 1 ]; then
  echo 'Platform HTTP may emit only its single fixed anonymous protocol Cookie header.' >&2
  exit 1
fi

# Old login credentials are removed on upgrade; SoundWave-owned account/local data is untouched.
grep -E -q -- 'LegacyPlatformAuthCleanup\.clearLegacyThirdPartyMusicAuth' app/src/main/java/xyz/cncnlp/soundwave/CrashGuard.kt
grep -E -q -- 'sw_plat_acct_v1' app/src/main/java/xyz/cncnlp/soundwave/migration/LegacyPlatformAuthCleanup.kt
grep -E -q -- 'third_party_music_auth_removed_v1' app/src/main/java/xyz/cncnlp/soundwave/migration/LegacyPlatformAuthCleanup.kt
grep -E -q -- 'entry\.remove\("accountProbe"\)' app/src/main/java/xyz/cncnlp/soundwave/migration/LegacyPlatformAuthCleanup.kt
grep -E -q -- 'entry\.remove\("userPlaylists"\)' app/src/main/java/xyz/cncnlp/soundwave/migration/LegacyPlatformAuthCleanup.kt
grep -E -q -- 'class CloudSyncController' app/src/main/java/xyz/cncnlp/soundwave/CloudSyncController.kt
grep -E -q -- '(object|class) LocalStore' app/src/main/java/xyz/cncnlp/soundwave/LocalStore.kt

# Non-account platform data and ordinary playback/download routes must stay available.
# (ACCEPT5 deletion contract: officialUrl is NOT in this list — it was deleted
# outright with the /music/url server fallback by user ruling 2026-11-04.)
grep -E -q -- 'fun searchPlaylists\(' app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt
grep -E -q -- 'fun recommendPlaylists\(' app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt
grep -E -q -- 'fetchRankBoardsDirect' app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt
grep -E -q -- 'fetchPlaylistDetailDirect' app/src/main/java/xyz/cncnlp/soundwave/ApiClient.kt
grep -E -q -- 'fun download\(' app/src/main/java/xyz/cncnlp/soundwave/DownloadHelper.kt
grep -E -q -- 'DefaultDataSource\.Factory' app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt
grep -E -q -- 'ScriptNetworkPolicy\.filterHeaders' app/src/main/java/xyz/cncnlp/soundwave/ScriptRuntime.kt

# The account sheet has no provider-brand badges; the two library cards share the rank selector surface.
if grep -n -E -- 'accountPlatformBadgeColor|PlatformBrand' app/src/main/java/xyz/cncnlp/soundwave/ui/common/AccountUi.kt; then
  echo 'Third-party provider badges have returned to the SoundWave account sheet.' >&2
  exit 1
fi
grep -E -q -- 'surfaceVariant\.copy\(alpha = 0\.52f\)' app/src/main/java/xyz/cncnlp/soundwave/LibraryQuickPager.kt
if ! python3 - <<'PY'
from pathlib import Path
import re
import sys

source = Path("app/src/main/java/xyz/cncnlp/soundwave/LibraryQuickPager.kt").read_text(encoding="utf-8")
checks = {
    "exactly one Icon call": len(re.findall(r"\bIcon\s*\(", source)) == 1,
    # 全局播放三角统一为圆角 DesignGlyphs.PlayTriangle（不再使用尖角 Material PlayArrow）。
    "exactly one play-arrow vector": source.count("DesignGlyphs.PlayTriangle") == 1 and "Icons.Filled.PlayArrow" not in source,
    "exact accessible play description": source.count('contentDescription = "播放$title"') == 1,
}
checks["bottom-end play control is limited to the liked card and invokes onPlay only for a nonempty list"] = bool(re.search(
    r"if\s*\(page\s*==\s*0\)\s*\{\s*Box\(\s*Modifier\.align\(Alignment\.BottomEnd\)"
    r"[\s\S]*?\.clickable\(enabled\s*=\s*songs\.isNotEmpty\(\)\)\s*\{"
    r"\s*songs\.firstOrNull\(\)\?\.let\s*\{\s*onPlay\(it,\s*songs,\s*playlist\.id\)\s*\}\s*\}"
    r"[\s\S]*?contentDescription\s*=\s*\"播放\$title\"",
    source,
    re.S,
))
for label, passed in checks.items():
    if not passed:
        print(f"FAIL: library quick-card icon check: {label}", file=sys.stderr)
        sys.exit(1)
PY
then
  echo 'Library quick cards must retain exactly one accessible play arrow with the reviewed nonempty-play callback; provider/cover or other card icons are forbidden.' >&2
  exit 1
fi

echo 'Third-party account removal guard passed; public data, regular playback/download and SoundWave-owned sync remain.'
