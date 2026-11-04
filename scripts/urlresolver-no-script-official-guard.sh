#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
resolver = (root / "app/src/main/java/xyz/cncnlp/soundwave/UrlResolver.kt").read_text(encoding="utf-8")
controller = (root / "app/src/main/java/xyz/cncnlp/soundwave/PlaybackController.kt").read_text(encoding="utf-8")
models = (root / "app/src/main/java/xyz/cncnlp/soundwave/ResolutionModels.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/AnonymousResolutionPolicyTest.kt").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()


def require(condition, message):
    if not condition:
        raise SystemExit(f"[urlresolver-no-script-official-guard] FAIL: {message}")

resolve_start = controller.find("suspend fun resolveAndPlay(")
resolve_end = controller.find("/** 播放切歌调用点顶层异常", resolve_start)
require(resolve_start >= 0 and resolve_end > resolve_start, "resolveAndPlay boundary must remain identifiable")
play = controller[resolve_start:resolve_end]
require("enabledN" not in play and "暂无可用音源，请到设置→脚本管理导入文件或链接" not in play,
        "PlaybackController must not stop before the anonymous resolver when scripts are absent")

official_gate = resolver.find("if (canAttemptAnonymousResolutionBeforeScripts(src, preferQuality, originOnly, forceCross))")
script_pool = resolver.find("var pool = try {", official_gate)
require(official_gate >= 0 and script_pool > official_gate,
        "the anonymous direct stage must remain before script-pool admission")
# I-wave S1 made /music/url a gated last-resort fallback; ACCEPT5 (user ruling
# 2026-11-04) deletes that fallback OUTRIGHT. The checks below pin the deletion
# contract at equal-or-higher strictness: the endpoint must have ZERO call sites,
# and the fallback helper, its admission predicate, its trace stage and the user
# setting object must all be gone from the resolver.
require("val officialUrl =" not in resolver[official_gate:script_pool],
        "the /music/url server call must not precede script admission")
require(resolver.count("ApiClient.officialUrlCancellable(") == 0,
        "the deleted server URL fallback must have no call site left")
require("tryServerUrlFallback" not in resolver
        and "canAttemptServerUrlFallback" not in resolver
        and "STAGE_SERVER_URL_FALLBACK" not in resolver
        and "ServerFallbackSetting" not in resolver,
        "the deleted fallback helper, predicate, stage and setting must be gone from the resolver")

internal_start = resolver.find("private suspend fun resolveResultInternal(")
internal_end = resolver.find("\n    suspend fun warmup(", internal_start)
require(internal_start >= 0 and internal_end > internal_start, "resolveResultInternal boundary must remain identifiable")
internal = resolver[internal_start:internal_end]
require("shouldRejectForMissingScripts(usableScripts, anonymousEligible)" in internal,
        "missing scripts may only reject when anonymous resolution is ineligible")
require(internal.find("shouldRejectForMissingScripts(") < internal.find("val pair = resolve("),
        "the no-script gate must be evaluated before the resolver call without blocking eligible anonymous requests")
require("resolveFailureFromAttempts(attempts)" in internal,
        "post-attempt failures must retain the no-enabled-script cause")
require("fun canAttemptAnonymousResolutionBeforeScripts(" in models and "fun shouldRejectForMissingScripts(" in models
        and "fun canAttemptServerUrlFallback(" not in models and "fun serverSearchFallbackAdmitted(" not in models,
        "the tested pure resolution gates must remain available (url fallback predicate deleted; cross-source server-search admission predicates deleted by the ACCEPT6 retirement contract)")
require("tx playback skips the structurally dead anonymous official stage" in test and
        "non-tx platforms keep anonymous resolution admitted before scripts" in test and
        "failed anonymous attempt with no scripts retains actionable missing-script message" in test,
        "JVM regressions must cover anonymous eligibility and final failure messaging")
require("scripts/urlresolver-no-script-official-guard.sh" in workflow,
        "this guard must run in portable CI quality gates")

version_match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
version_name = re.search(r'versionName\s+"([^"]+)"', build)
version_code = re.search(r"versionCode\s+(\d+)", build)
require(version_match is not None and version_name is not None and version_code is not None,
        "version declarations must use the expected three-part format")
require(version_name.group(1) == version and version_code.group(1) == version_match.group(3),
        "VERSION, Gradle versionName and versionCode must remain synchronized")

print("[urlresolver-no-script-official-guard] PASS: anonymous direct precedes script admission; /music/url server URL fallback deleted outright; missing-script failure stays actionable")
PY
