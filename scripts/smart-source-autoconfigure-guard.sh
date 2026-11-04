#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
main = (root / "app/src/main/java/xyz/cncnlp/soundwave/MainActivity.kt").read_text()
cache = (root / "app/src/main/java/xyz/cncnlp/soundwave/ScriptProbeCache.kt").read_text()
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptProbeCacheValidityTest.kt").read_text()

def require(ok, message):
    if not ok:
        raise SystemExit(f"[smart-source-autoconfigure-guard] FAIL: {message}")

start = main.find("onSmartAutoConfigure = {")
end = main.find("\n                            }\n                        )", start)
require(start >= 0 and end > start, "the one-click configuration callback must remain identifiable")
callback = main[start:end]
require("ScriptProbeCache::fullReport" in callback and "buildAutoConfigurationPlan" in callback,
        "one-click configuration must consume current valid full reports")
require("probeScriptScored" not in callback and "withProbeLane" not in callback,
        "one-click configuration must not start network probes")
require("没有可用的完整检测报告，请先执行完整检测；现有设置保持不变" in callback,
        "missing full reports must ask for a full check without changing settings")
require("完整检测报告中没有已验证成功的链路，现有设置保持不变" in callback,
        "reports with no verified success must leave settings unchanged")
first_mutation = callback.find("SmartSourceStore.setSmartEnabled(true)")
require(first_mutation > callback.find("if (!plan.hasVerifiedSuccess)") > callback.find("if (reports.isEmpty())"),
        "report availability and verified-success checks must precede every configuration mutation")
require("enabled = currentPlatforms.firstOrNull { it.code == platform }?.enabled ?: true" in callback,
        "unverified outcomes must not disable platforms")
require("plan.platformScriptOrders.forEach" in callback and "plan.platformQualities.forEach" in callback,
        "only report-derived per-platform script orders and quality capabilities may be persisted")
require("best?.quality" in cache and "report.valid" in cache and "isValidFullReport(report, it)" in cache,
        "the plan must rank only verified attempts in current complete reports")
require("Outcome.FAILED" in test and "Outcome.NO_MATCH" in test and "Outcome.UNSUPPORTED" in test,
        "JVM regressions must preserve distinct failed, unmatched, unsupported and unprobed cases")
version = (root / "VERSION").read_text().strip()
gradle = (root / "app/build.gradle").read_text()
require(re.search(rf'versionName "{re.escape(version)}"', gradle) is not None
        and re.search(rf"versionCode\s+{re.escape(version.rsplit('.', 1)[1])}\b", gradle) is not None,
        "VERSION and Gradle version declarations must remain synchronized")
print("[smart-source-autoconfigure-guard] PASS: no re-probe, current full reports only, success-only capabilities, and unchanged settings without usable evidence")
PY
