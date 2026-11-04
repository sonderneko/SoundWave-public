#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
cache = (base / "ScriptProbeCache.kt").read_text()
resolver = (base / "UrlResolver.kt").read_text()
matrix = (base / "ScriptProbeMatrix.kt").read_text()
# 1.0.656: script detail/check sheets live in ScriptsPageParts.kt (structured brief via SmartSourceScriptProbeSnapshot).
scripts_page = (base / "ScriptsPage.kt").read_text() + (base / "ScriptsPageParts.kt").read_text() + (base / "SmartSourcePresentation.kt").read_text()
main = (base / "MainActivity.kt").read_text()

def require(ok, message):
    if not ok:
        raise SystemExit(f"[script-probe-report-isolation-guard] FAIL: {message}")

require('K_QUICK_RECORDS = "quick_records"' in cache and 'K_FULL_RECORDS = "full_records"' in cache,
        "quick and full reports must use separate preference records")
require("enum class ReportType" in cache and "type: ReportType" in cache,
        "reports must use an explicit type, not an inferred display-only mode")
require("fun fullReport(script: Script)" in cache and "isValidFullReport(it, script)" in cache,
        "sorting must obtain only a valid full report for the current script")
require("report.type == ReportType.FULL && report.complete && isReportCurrent(report, script)" in cache,
        "full reports must match report type, completion, current script identity/hash and runtime ABI")
require("report.scriptHash == scriptHash(script.code)" in cache and "report.runtimeAbi == RUNTIME_ABI" in cache,
        "current report validity must bind both source hash and runtime ABI")
require("fun sortValue(script: Script" in cache and "val r = fullReport(script) ?: return Long.MIN_VALUE" in cache,
        "sort scores must never read quick or stale reports")
require("K_QUICK_RECORDS" in cache and "K_FULL_RECORDS" in cache and "Report.fromJson(raw)" in cache,
        "legacy reports must be migrated according to their explicit legacy mode")
require("实际样本×请求音质覆盖" in cache and "本次未探测" in cache and "Outcome.NO_MATCH" in cache,
        "report text must show per-sample coverage and distinguish unmatched samples")
# r658c S: no sampling. Every check is a full traversal of the script's declared platforms × all fixed samples × declared tiers.
require('qualityTiers = listOf("master", "atmos", "hires", "flac24bit", "flac", "320k", "128k")' in matrix
        and "ScriptProbeMatrix.plan(ScriptProbeMatrix.parseDeclaredSources(declared), maxPlats)" in resolver
        and "val qOrder = plan.qualitiesFor(plat)" in resolver
        and "runtime.declaredSources(sc.id, sc.code, scriptMetaJson(sc))" in resolver
        and "quick" not in resolver[resolver.index("suspend fun probeScriptScored("):resolver.index("/** Compatibility entry point")]
        and "take(1)" not in matrix,
        "checks must traverse every declared platform/sample/tier with no quick sampling path")
require("FULL_PROBE_BUDGET_MS" in matrix and "PLATFORM_TIMEOUT_STREAK_LIMIT" in matrix
        and "budgetExceeded = true" in resolver and "timeoutStreak[plat]" in resolver,
        "full traversal must keep a per-script time budget and a per-platform timeout breaker that still records every item")
require("!budgetExceeded && maxPlats >= ScriptProbeMatrix.platformOrder.size" in resolver
        and "attempts.size == plan.totalItems" in resolver,
        "only a fully executed declared platform/sample/tier plan may be saved as a complete full report")
require("sampleQueryName" in cache and "匹配到:" in cache and "请求档位 " in cache
        and "不冒充响应实际码率" in cache,
        "details must retain the matrix query, actual matched song, and requested-tier semantics")
require("sampleAttempts.filter { it !== a }.map { it.toQualityObservation() }" in cache,
        "downgrade evidence must compare tiers only within the same fixed song sample")
require("qualities = listOf(preferQ, \"320k\", \"128k\")" not in main,
        "manual complete detection must not narrow the seven-tier matrix")
require("fun safeUrlSummary(raw: String)" in cache and "URI(raw.trim())" in cache
        and "return INVALID_URL_HINT" in cache and 'pair.substringBefore(\'=\')' in cache
        and '"${encodeUrlComponent(safeName)}=$MASKED"' in cache,
        "URL summary must fail closed, retain safe host/path clues, and mask all query values")
require('put("urlHint", safeUrlSummary(urlHint))' in cache
        and 'urlHint = safeUrlSummary(o.optString("urlHint"))' in cache
        and 'root.put(report.scriptId, report.toJson())' in cache
        and 'append(" · URL ").append(safeUrlSummary(a.urlHint))' in cache
        and "fun safeDiagnosticText(value: String)" in cache,
        "serialized, migrated, and formatted reports must normalize URL hints and scrub embedded URLs")
probe = resolver[resolver.index("suspend fun probeScriptScored("):resolver.index("/** Compatibility entry point", resolver.index("suspend fun probeScriptScored("))]
require("urlHint = ScriptProbeCache.safeUrlSummary(mediaUrl)" in probe
        and "runtimeFailure = e.javaClass.simpleName" in probe
        and "e.message" not in probe and "Log." not in probe,
        "probe failures must not copy exception messages or raw URLs into report/log output")
require("type = ScriptProbeCache.ReportType.FULL," in resolver and "quick = true" not in (base / "ScriptProbeScheduler.kt").read_text(),
        "probe execution must persist an explicit full report type; scheduled checks must not sample")
require("latestReport(script.id)" in scripts_page and "isReportCurrent(report, script)" in scripts_page,
        "script details must display the latest report and flag stale script/runtime results")
require("ScriptProbeCache.sortValue(script, mode)" in main
        and "ScriptProbeCache::fullReport" in main
        and "ScriptProbeCache.buildAutoConfigurationPlan(" in main
        and "currentPlatforms.map { it.code }" in main
        and "isValidFullReport(report, it)" in cache,
        "manual sorting and smart configuration must use current-script full-report evidence")
startup = (base / "probe/ScriptProbeStartup.kt").read_text()
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptProbeCacheValidityTest.kt").read_text()
require("missingFullReportTargets" in main and "ScriptProbeBatchRunner.runFull(initialTargets" in main
        and "sortWithVerifiedReports(" in main,
        "startup must probe only missing full reports and sort after the batch")
require("filter { it.enabled && it.code.isNotBlank() }" in startup
        and "ScriptProbeCache.isValidFullReport(it, script)" in startup,
        "startup targets must be enabled coded scripts without a current valid full report")
require("if (!hasVerifiedSuccess) return null" in startup
        and "ScriptProbeCache.isValidFullReport(it, script) && it.valid.isNotEmpty()" in startup,
        "startup must preserve order unless a current full report proves playable success")
require("ScriptProbeBatchRunner.runFull(targets, scriptRuntime, preferQ, progress)" in main
        and "maxPlats = 5" in startup and "ScriptProbeCache.ReportType" not in startup,
        "manual and automatic batch checks must share the fixed full-probe path without changing report semantics")
require("startupTargetsOnlyEnabledScriptsWithoutCurrentCompleteFullReports" in test
        and "startupSortRequiresVerifiedSuccessAndRanksOnlyBySuppliedFullReportScore" in test,
        "JVM regressions must cover startup target selection and conservative sorting")
print("[script-probe-report-isolation-guard] PASS: isolated reports, current-script validation, declared platforms × all fixed samples × declared tiers (no sampling), accurate result classes/coverage, redacted URL diagnostics, and full-only sorting")
PY
