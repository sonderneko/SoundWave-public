#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/xyz/cncnlp/soundwave"
runtime = (base / "ScriptRuntime.kt").read_text(encoding="utf-8")
evaluation_lanes = (base / "script/ScriptEvaluationLanes.kt").read_text(encoding="utf-8")
limits = (base / "script/ScriptExecutionLimits.kt").read_text(encoding="utf-8")
dns_resolver = (base / "script/BoundedScriptDnsResolver.kt").read_text(encoding="utf-8")
expiry_policy = (base / "resolve/UrlCacheExpiryPolicy.kt").read_text(encoding="utf-8")
fetch_transport_text = (base / "ScriptFetchTransport.kt").read_text(encoding="utf-8")
resolver_text = (base / "UrlResolver.kt").read_text(encoding="utf-8")
transport = (base / "ScriptFetchTransport.kt").read_text(encoding="utf-8")
request_scheduler = (base / "ScriptRequestScheduler.kt").read_text(encoding="utf-8")
policy = (base / "ScriptNetworkPolicy.kt").read_text(encoding="utf-8")
test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptRuntimeWebViewIsolationTest.kt").read_text(encoding="utf-8")
request_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptRequestSchedulerTest.kt").read_text(encoding="utf-8")
network_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptNetworkPolicyTest.kt").read_text(encoding="utf-8")
setting = (base / "source/ScriptHttpCredentialGuardSetting.kt").read_text(encoding="utf-8")
settings_ui = (base / "MePage.kt").read_text(encoding="utf-8")
setting_test = (root / "app/src/test/java/xyz/cncnlp/soundwave/ScriptHttpCredentialGuardSettingTest.kt").read_text(encoding="utf-8")
build = (root / "app/build.gradle").read_text(encoding="utf-8")
version = (root / "VERSION").read_text(encoding="utf-8").strip()
workflow = (root / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"[script-runtime-webview-guard] FAIL: {message}")

require(version == "1.0.675" and 'versionName "1.0.675"' in build and re.search(r"versionCode\s+675\b", build),
        "VERSION, Gradle versionName and versionCode must match 1.0.675 / 675")
for fragment in (
    "wv.settings.blockNetworkLoads = true",
    "wv.settings.allowFileAccess = false",
    "wv.webViewClient = ScriptRuntimeWebViewClient",
    "scriptRuntimeBootstrapHtml()",
    "Content-Security-Policy",
    "shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean = true",
    "shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true",
    "shouldInterceptRequest(view: WebView?, request: WebResourceRequest)",
    "shouldInterceptRequest(view: WebView?, url: String?)",
    'WebResourceResponse(\n            "text/plain", "UTF-8", 403',
):
    require(fragment in runtime, f"ScriptRuntime WebView isolation is missing: {fragment}")
for fragment in (
    "default-src 'none'", "connect-src 'none'", "frame-src 'none'", "form-action 'none'",
    "script-src 'unsafe-inline' 'unsafe-eval'",
):
    require(fragment in runtime, f"CSP must preserve required policy/kernel behavior: {fragment}")
for fragment in (
    'window.__swBridge = window.NativeBridge',
    'window.__swBridge.nativeFetch.apply(window.__swBridge, arguments)',
    "@JavascriptInterface\n        fun nativeFetch(",
):
    require(fragment in runtime, f"The controlled native script-request path must remain available: {fragment}")
require("ScriptNetworkPolicy.inspectScriptUrl(url)" in transport,
        "The controlled native script-request path must remain available: ScriptNetworkPolicy.inspectScriptUrl(url)")
require("MAX_RUNNING = 8" in request_scheduler and "MAX_QUEUED = 16" in request_scheduler,
        "native script requests must retain the approved process-wide 8-running/16-queued cap")
require(len(request_scheduler.splitlines()) <= 350,
        "the bounded scheduler must remain a small, auditable component")
require("MAX_SCRIPT_CODE_BYTES = 1 * 1024 * 1024" in limits and
        "MAX_SCRIPT_METADATA_BYTES = 64 * 1024" in limits and
        "MAX_MUSIC_INFO_BYTES = 64 * 1024" in limits and
        "MAX_REQUEST_BODY_BYTES = 1 * 1024 * 1024" in limits and
        "MAX_REQUEST_HEADERS_BYTES = 64 * 1024" in limits and
        "MAX_RESPONSE_BYTES = 2 * 1024 * 1024" in limits and
        "ScriptExecutionLimits.scriptCodeAllowed(code)" in runtime and
        "ScriptExecutionLimits.scriptMetadataAllowed(scriptMeta)" in runtime and
        "ScriptExecutionLimits.musicInfoAllowed(musicInfo.toString())" in runtime,
        "untrusted source, metadata, song info, and native request/response payloads must retain explicit byte budgets")
require("ScriptExecutionLimits.requestAllowed(url, headersJson, body)" in fetch_transport_text and
        "ScriptFetchCodec.readLimited" in fetch_transport_text and
        "Math.max(250,Math.min(15000,requestedTimeout))" in runtime,
        "script request URL/header/body size and custom HTTP timeout must be bounded")
require("ArrayBlockingQueue(16)" in dns_resolver and "2, 2" in dns_resolver and
        "timeoutMillis: Long = 1_500L" in dns_resolver and
        fetch_transport_text.count("BoundedScriptDnsResolver.processWide.resolve(host)") >= 2,
        "initial and redirected hostname checks must use the finite, cancellation-aware DNS pool")
require("NEGATIVE_RESULT_TTL_MS = 60_000L" in limits and
        "MAX_NEGATIVE_RESULTS = 128" in limits and
        "SingleFlightNegativeCache<String, ScriptUrlCallResult>" in runtime and
        "isFailure = { it.url.isBlank() && it.failureCategory.isBlank() && it.cacheableFailure }" in runtime,
        "only bounded short-lived negative script results may be cached; same in-flight requests coalesce")
require("SCRIPT_URL_CALL_TOTAL_MS = 24_000L" in limits and
        "withTimeoutOrNull(ScriptExecutionLimits.SCRIPT_URL_CALL_TOTAL_MS)" in runtime,
        "the getMusicUrl total budget must include script-slot admission plus its staged waits")
require("it.failureCategory.isBlank()" in runtime and
        "val failure = if (url.isBlank()) lastFailureByScript[id].orEmpty() else \"\"" in runtime,
        "only semantic empty URLs may enter the negative cache; transient classified failures must remain retryable")
require('lastFetchFailure[parent] = "queue_full"' in runtime and
        'lastFailureByScript[it] = "queue_full"' in runtime,
        "scheduler rejection must be attributed to the owning script rather than reported as a semantic empty URL")
require("finally{delete __swResponseHeaders[cid];}" in runtime and
        "finally{delete __swResponseHeaders[cid];}" in test,
        "every terminal JS request callback must release its private safe-response-header slot")
require("expiresAtEpochMs <= System.currentTimeMillis()" in resolver_text and
        "MAX_SUCCESS_TTL_MS = 30 * 60 * 1000L" in expiry_policy and
        'name == "auth_key"' in expiry_policy,
        "resolved-URL cache entries must honor signed-link expiry hints within the 30-minute ceiling")
for fragment in (
    "fetchScheduler.submit(",
    'onRejected = {',
    'lastFetchFailure[parent] = "queue_full"',
    'lastFailureByScript[it] = "queue_full"',
    'dispatchFetchResult(capability, 429, "request queue full")',
    "fetchScheduler.cancel(fetchOwner, id)",
    "fetchScheduler.cancelOwner(fetchOwner)",
    "window.__swNativeCancel && window.__swNativeCancel(cid)",
):
    require(fragment in runtime, f"native request admission/cancellation contract is missing: {fragment}")
require("else ScriptRequestScheduler.processWide" in runtime,
        "the runtime scheduler must remain the shared process-wide instance (probes excepted)")
# Check the actual ownership consumers, not merely names elsewhere in this large kernel.
def section(start: str, end: str) -> str:
    begin = runtime.find(start)
    finish = runtime.find(end, begin + len(start))
    require(begin >= 0 and finish > begin, f"request ownership boundary missing: {start}")
    return re.sub(r'(?m)^\s*//[^\n]*', '', runtime[begin:finish])

release = section('private fun releaseRequest(', '/** capability → scriptId')
native = section('fun nativeFetch(url:', 'private fun dispatchFetchResult(')
cancel = section('private fun cancelUrlRequests(', 'fun destroy()')
get_url = section('suspend fun getMusicUrl(', '/** 预热：')
js_request = section('self.request=function(url, options, cb)', 'return requestHandle;\n        };')
for fragment in (
    'synchronized(requestLock)',
    'fetchParents.entries.filter { it.value == capability }.map { it.key }.forEach { cid ->',
    'fetchScheduler.cancel(fetchOwner, cid)',
    # ACCEPT6-FREEZE 重钉（等严更严）：仍只取消该 cid 自己的子调用（意图不变），
    # 但 Call.cancel() 改经 cancelCallAsync 投递后台线程——内联执行会在主线程关 socket 致崩。
    'activeCalls.remove(cid)?.let { cancelCallAsync(it) }',
    'forgetFetch(cid)',
    'window.__swCancelRequest(${JSONObject.quote(capability)},$invalidate)',
):
    require(fragment in release, f"URL cleanup must cancel only its actual cid children: {fragment}")
for fragment in (
    'requestCapability: String?',
    'val parent = requestCapability?.takeIf { it.isNotBlank() } ?: return',
    'synchronized(requestLock)',
    'val parentRequest = pendingUrl[parent] ?: return',
    'if (fetchParents.putIfAbsent(capability, parent) != null) return',
    'cid = capability',
    'onCancelled = {',
    'activeCalls.remove(capability)?.let { cancelCallAsync(it) }',
):
    require(fragment in native, f"native child admission/Call cancellation must be owner-bound: {fragment}")
require('scope=requestScope, parentCap=requestCap' in js_request and
        "if(!scope || scope.closed) throw new Error('request owner closed')" in js_request and
        re.search(r'window\.__swNativeFetch\([\s\S]*?cid, parentCap\s*\)', js_request) is not None,
        "lx.request must pass its live URL owner to nativeFetch")
require('cancelUrlRequests { it.purpose == ResolveTicket.Purpose.PLAY }' in runtime and
        'cancelUrlRequests { it.purpose == ResolveTicket.Purpose.PREFETCH || it.purpose == ResolveTicket.Purpose.PROBE }' in runtime and
        'pendingUrl.remove(capability, pending)' in cancel and 'releaseRequest(capability)' in cancel and
        'pending.ownerJob?.cancel(' in cancel and 'cancelOwner(' not in cancel + release and
        'activeCalls.clear(' not in cancel + release,
        "PLAY/speculative cancellation must release selected owners without cancelling DOWNLOAD")
require('): String = coroutineScope {' in get_url and
        0 <= get_url.find('pendingUrl[capability] = PendingUrlRequest(') < get_url.find('ensureStarted()') and
        'releaseRequest(capability, invalidate = !def.isCompleted || def.isCancelled)' in get_url and
        get_url.find('releaseRequest(capability, invalidate = !def.isCompleted || def.isCancelled)') < get_url.find('if (ownerCurrent()) result else') and
        'cancelOwner(' not in get_url and 'destroy()' not in get_url and 'reset' not in get_url,
        "queued URL ownership and timeout cleanup must remain local to the structured request")
require('private val evaluationLanes = ScriptEvaluationLanes()' in runtime and 'evalMutex' not in runtime and
        'evaluationLanes.withSlot(scriptSlot)' in get_url and
        'suspend fun <T> submit(block: () -> T)' in evaluation_lanes and
        'Array(stripeCount) { Mutex() }' in evaluation_lanes,
        "WebView request waiting must use bounded per-script single-flight lanes and a synchronous-only submit gate")
js_cancel = section('window.__swCancelRequest=function(cap,invalidate)', 'window.__swDispatch=function(')
require('scope.closed=true' in js_cancel and
        'Object.keys(scope.children).forEach(function(cid){scope.children[cid]();});' in js_cancel and
        'e.lx.__ownsRequest(scope)' in js_cancel and 'delete window.__swScripts[scope.sid]' in js_cancel,
        "cancelled JS continuation must close only its owning scope and slot")
for fragment in (
    "admitsEightRunsSixteenQueuedAndRejectsTheTwentyFifthImmediately",
    "eightRequestPromiseAllStyleBurstRemainsConcurrent",
    "freeingOneRunningSlotDispatchesExactlyOneQueuedRequest",
    "duplicateCidCannotCauseTwoCallbacks",
    "cancellingQueuedRequestPreventsItsFakeTransportFromRunning",
    "cancellingRunningRequestCancelsFakeCallAndSuppressesLateCompletion",
    "runtimesShareOneLimitButOwnerCancellationLeavesOtherRuntimeWorkIntact",
):
    require(fragment in request_test, f"a deterministic bounded-request regression is missing: {fragment}")
for fragment in (
    "ScriptNetworkPolicy.inspectScriptUrl(url)",
    "urlValidation.validatedTarget",
):
    require(fragment in transport, f"Private/special destinations must not egress at all: {fragment}")
require("/music/http" not in transport and "shouldUseProxyFallback" not in transport,
        "the retired server relay must not reappear in the script transport")
require("isAllowedScriptUrl(" in policy and "filterHeaders(" in policy,
        "the existing native HTTP policy must remain present")
require('PREFERENCE_KEY = "script_http_credential_guard"' in setting and
        'preferences.getBoolean(PREFERENCE_KEY, false)' in setting,
        "script cleartext credential protection must be persisted and default off")
require('RowCard("脚本 HTTP 令牌/密钥保护"' in settings_ui and
        "ScriptHttpCredentialGuardSetting.setEnabled(mePagePrefs, enabled)" in settings_ui,
        "the existing source settings UI must explain and persist the explicit guard")
initial_guard = transport.index("val cleartextDecision = ScriptNetworkPolicy.assessCleartextCredentialRequest(")
direct_request = transport.index("val urlValidation = ScriptNetworkPolicy.inspectScriptUrl(url)")
require(initial_guard < direct_request and
        '"HTTP request with token/key headers is blocked"' in transport,
        "initial HTTP credential rejection must happen before direct egress")
redirect_strip = transport.index("ScriptNetworkPolicy.redirectCredentialHeaders(current.headers.names(), sameOrigin)")
redirect_recheck = transport.index("val redirectedDecision = ScriptNetworkPolicy.assessCleartextCredentialRequest(")
redirect_validation = transport.index("val redirectValidation = ScriptNetworkPolicy.inspectScriptUrl(redirectUrl)")
require(redirect_strip < redirect_recheck < redirect_validation,
        "redirected outgoing headers must be checked after credential stripping and before target DNS/network")
require('return ScriptFetchResult(403, "redirect blocked")' in transport,
        "a rejected redirect target must end the exchange (no relay remains)")
require("ScriptNetworkPolicy.isHttpsDowngrade(current.url.toString(), redirectUrl)" in transport and
        '"HTTPS redirect downgrade is blocked"' in transport and
        "bypassProxyFallback" not in transport,
        "HTTPS redirects must never downgrade to HTTP")
for fragment in (
    "cleartextCredentialGuardDefaultsOffAndPreservesLegacyHttpBehavior",
    "enabledGuardRejectsHttpWithEveryAllowedSensitiveTokenOrKeyHeader",
    "enabledGuardAllowsPublicHttpWithoutSensitiveHeadersAndHttpsWithSensitiveHeaders",
    "redirectToHttpIsBlockedWhenAnOutgoingSensitiveHeaderSurvives",
    "httpsToHttpRedirectRemainsForbiddenAfterCrossOriginCredentialsAreStripped",
):
    require(fragment in network_test, f"a deterministic cleartext credential regression is missing: {fragment}")
require("preferenceDefaultsOffAndPersistsExplicitChanges" in setting_test,
        "the setting default and persistence must have a JVM regression test")
for fragment in (
    "privateLocalAndLinkLocalLiteralsAreRejectedBeforeNetwork",
    "privateOrMixedDnsAnswersAreRejected",
    "restrictedRedirectTargetIsRejected",
    "specialUseIpv4AndIpv6RangesAreRejectedForDirectEgress",
    "publicHttpAndHttpsKeepDirectEligibility",
    "publicLiteralIpIsDirectEligibleWithoutDnsLookup",
):
    require(fragment in network_test, f"a deterministic direct-egress policy regression is missing: {fragment}")
for fragment in (
    "hiddenRuntimeWebViewBlocksNetworkButKeepsJavaScriptEnabled",
    "clientRejectsMainFrameAndFrameNavigationsAndInterceptsHttpResources",
    "cspBlocksBrowserEgressWhileAllowingRuntimeInlineAndEvalKernel",
    "blockNetworkLoads",
    "nativeFetch.isAnnotationPresent(JavascriptInterface::class.java)",
):
    require(fragment in test, f"Robolectric compatibility regression is missing: {fragment}")
for path in base.rglob("*.kt"):
    if path.name == "ScriptRuntime.kt":
        continue
    source = path.read_text(encoding="utf-8")
    require(not re.search(r"\bWebView\s*\(", source),
            f"WebView isolation must not leak to another component: {path.relative_to(root)}")
require("scripts/script-runtime-webview-guard.sh" in workflow,
        "the ScriptRuntime guard must be included in portable quality gates")
print("[script-runtime-webview-guard] PASS: hidden WebView egress blocked, native lx.request bridge retained, default-off persisted cleartext credential guard blocks before direct egress and after redirects, bounded scheduling and cancellation verified, existing direct-egress/media policy retained, and 1.0.675 synchronized")
PY
python3 "$ROOT/scripts/test_runtime_handler_health.py"
